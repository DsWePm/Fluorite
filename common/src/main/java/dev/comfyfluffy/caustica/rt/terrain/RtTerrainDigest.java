package dev.comfyfluffy.caustica.rt.terrain;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.platform.Platform;
import dev.comfyfluffy.caustica.rt.terrain.RtTerrainMesher.PackedSection;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Content hash of every section this run tessellated, for comparing geometry extraction across builds.
 *
 * <p>This exists for the multi-loader port. Terrain currently comes out of the Fabric Renderer API; the
 * NeoForge path has to decode vanilla {@code BakedQuad}s instead, and the semantics that do not survive
 * that trip — per-quad chunk layer above all, which is what lets one model emit a SOLID base and a CUTOUT
 * tinted overlay and what the coplanar-resolution pass keys on — fail *quietly*. They do not crash; they
 * put the wrong triangles in the wrong bucket. Screenshots catch some of that. A digest catches all of it.
 *
 * <p>Enable with {@code -Dcaustica.rt.terrainDigest=true}, load a fixed-seed world from a fixed position,
 * and diff the two dumps. Sections are keyed by their own coordinates and the dump is coordinate-sorted,
 * so worker scheduling order does not perturb it.
 *
 * <p>Off by default and costs one hash per section build when on — sections are built once and cached, so
 * this is not a per-frame cost.
 */
public final class RtTerrainDigest {
	private static final long FNV_OFFSET = 0xcbf29ce484222325L;
	private static final long FNV_PRIME = 0x100000001b3L;
	/** Rewrite cadence, in client ticks, so a crash still leaves a usable dump behind. */
	private static final int DUMP_INTERVAL_TICKS = 100;

	private static final Map<Long, Section> SECTIONS = new ConcurrentHashMap<>();
	private static final AtomicBoolean DIRTY = new AtomicBoolean();
	private static int ticksSinceDump;
	/**
	 * Consecutive dumps with an unchanged combined hash.
	 *
	 * <p>Without this the file gives no way to tell a settled capture from one taken mid-stream, and a
	 * mid-stream capture is actively misleading rather than merely incomplete: a section meshed before its
	 * neighbours arrive keeps faces that later get culled, so an unsettled baseline carries *more*
	 * geometry than the truth and every later comparison reads as "lost triangles". Do not trust a dump
	 * whose stableDumps is 0.
	 */
	private static int stableDumps;
	private static long lastCombined;

	private RtTerrainDigest() {
	}

	public static boolean enabled() {
		return CausticaConfig.Rt.Diagnostics.TERRAIN_DIGEST.value();
	}

	/**
	 * {@code builds} counts how many times this section was tessellated with content different from the
	 * previous time. One means it was built once and never changed — the only sections a cross-run
	 * comparison can trust. Anything higher is re-meshing under a moving world: flowing fluids, mostly,
	 * which in a lava-bearing world never stop and make a globally stable digest unreachable rather than
	 * merely slow to arrive.
	 */
	private record Section(int sx, int sy, int sz, int verts, int tris, int lights, long digest, int builds) {
	}

	/** Called from the worker that packed this section, before it is uploaded. */
	static void record(long key, int sx, int sy, int sz, PackedSection packed) {
		if (!enabled()) {
			return;
		}
		long h = FNV_OFFSET;
		h = hashFloats(h, packed.positions());
		h = hashInts(h, packed.indices());
		h = hashFloats(h, packed.uvs());
		h = hashFloats(h, packed.material());
		h = hashInts(h, packed.bucketTris());
		h = hashInts(h, packed.triBase());
		h = hashFloats(h, packed.lights());
		long digest = h;
		SECTIONS.compute(key, (k, previous) -> new Section(sx, sy, sz,
				packed.positions().length / 3,
				packed.indices().length / 3,
				packed.lights() == null ? 0 : packed.lights().length,
				digest,
				previous == null || previous.digest() != digest
						? (previous == null ? 1 : previous.builds() + 1)
						: previous.builds()));
		DIRTY.set(true);
	}

	/** Drop everything, e.g. when terrain residency is fully cleared for a new world. */
	public static void reset() {
		SECTIONS.clear();
		stableDumps = 0;
		lastCombined = 0L;
		DIRTY.set(true);
	}

	/** Called once per client tick from the terrain update; rewrites the dump periodically when dirty. */
	public static void tick() {
		if (!enabled()) {
			return;
		}
		if (++ticksSinceDump < DUMP_INTERVAL_TICKS) {
			return;
		}
		ticksSinceDump = 0;
		dumpIfDirty();
	}

	public static void dumpIfDirty() {
		if (!enabled()) {
			return;
		}
		// Deliberately not gated on the dirty flag. Stability is the whole point of this dump, and terrain
		// settling is exactly when nothing is dirty any more — an early return here would freeze the file
		// at stableDumps=0 precisely when it finally became trustworthy. The flag is also not a reliable
		// change signal: a section re-meshed to identical content sets it. The combined hash is the truth.
		DIRTY.set(false);
		// Sort by coordinate so worker completion order cannot change the file.
		List<Section> sorted = new ArrayList<>(SECTIONS.values());
		sorted.sort(Comparator.comparingInt(Section::sy)
				.thenComparingInt(Section::sz)
				.thenComparingInt(Section::sx));

		long combined = FNV_OFFSET;
		for (Section s : sorted) {
			combined = mix(combined, s.digest());
		}
		if (combined == lastCombined) {
			stableDumps++;
		} else {
			stableDumps = 0;
			lastCombined = combined;
		}

		Path file;
		try {
			Path dir = Platform.paths().gameDir().resolve("rt-terrain-digest");
			Files.createDirectories(dir);
			file = dir.resolve(Platform.get().loaderName() + ".txt");
		} catch (Exception e) {
			CausticaMod.LOGGER.warn("Could not prepare the terrain digest directory", e);
			return;
		}

		try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
				StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
			out.write("# caustica terrain digest\n");
			out.write("# loader=" + Platform.get().loaderName() + " sections=" + sorted.size() + "\n");
			out.write(String.format("# combined=%016x%n", combined));
			// Wait for this to be non-zero before using a capture as a baseline. Zero means terrain was
			// still re-meshing, and an unsettled capture has extra faces that later get culled.
			out.write("# stableDumps=" + stableDumps + "\n");
			out.write("# sx sy sz verts tris lights digest builds\n");
			for (Section s : sorted) {
				out.write(String.format("%d %d %d %d %d %d %016x %d%n",
						s.sx(), s.sy(), s.sz(), s.verts(), s.tris(), s.lights(), s.digest(), s.builds()));
			}
		} catch (IOException e) {
			CausticaMod.LOGGER.warn("Could not write the terrain digest to {}", file, e);
			return;
		}
		long rebuilt = sorted.stream().filter(s2 -> s2.builds() > 1).count();
		CausticaMod.LOGGER.info("Terrain digest: {} sections ({} re-meshed), combined={}, stable for {} dump(s) -> {}",
				sorted.size(), rebuilt, String.format("%016x", combined), stableDumps, file);
	}

	private static long hashFloats(long h, float[] values) {
		if (values == null) {
			return mix(h, 0xffff_ffff_ffff_ffffL);
		}
		h = mix(h, values.length);
		for (float v : values) {
			// Raw bits, not the value: -0.0 and 0.0 must stay distinguishable, and NaN payloads are
			// content too. Text formatting would hide both.
			h = mix(h, Float.floatToRawIntBits(v) & 0xffff_ffffL);
		}
		return h;
	}

	private static long hashInts(long h, int[] values) {
		if (values == null) {
			return mix(h, 0xffff_ffff_ffff_ffffL);
		}
		h = mix(h, values.length);
		for (int v : values) {
			h = mix(h, v & 0xffff_ffffL);
		}
		return h;
	}

	private static long mix(long h, long value) {
		long result = h;
		for (int shift = 0; shift < 64; shift += 8) {
			result ^= (value >>> shift) & 0xffL;
			result *= FNV_PRIME;
		}
		return result;
	}
}
