package io.github.dswepm.fluorite.rt.terrain;

import io.github.dswepm.fluorite.FluoriteConfig;
import io.github.dswepm.fluorite.FluoriteMod;
import io.github.dswepm.fluorite.platform.Platform;
import io.github.dswepm.fluorite.rt.terrain.RtTerrainMesher.PackedSection;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * <p>Enable with {@code -Dfluorite.rt.terrainDigest=true}, load a fixed-seed world from a fixed position,
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
	/** One-shot raw sample, so a disagreement can be read rather than inferred from hashes. */
	private static boolean loggedSample;
	/** Parsed form of the pinned-section config; null until first use. */
	private static volatile Set<Long> pinned;
	private static long lastCombined;

	private RtTerrainDigest() {
	}

	public static boolean enabled() {
		return FluoriteConfig.Rt.Diagnostics.TERRAIN_DIGEST.value();
	}

	/**
	 * {@code builds} counts how many times this section was tessellated with content different from the
	 * previous time. One means it was built once and never changed — the only sections a cross-run
	 * comparison can trust. Anything higher is re-meshing under a moving world: flowing fluids, mostly,
	 * which in a lava-bearing world never stop and make a globally stable digest unreachable rather than
	 * merely slow to arrive.
	 */
	private record Section(int sx, int sy, int sz, int verts, int tris, int lights, long digest, int builds,
			long posDigest, long idxDigest, long uvDigest, long matDigest, long lightDigest,
			long uvCoarseDigest, long uvMediumDigest, long posOrderless, long uvDelta,
			long lightOrderless) {
	}

	/** Called from the worker that packed this section, before it is uploaded. */
	static void record(long key, int sx, int sy, int sz, PackedSection packed) {
		if (!enabled()) {
			return;
		}
		// Hashed per array as well as combined. A whole-section hash tells you a section differs; it does
		// not tell you whether the geometry moved, the UVs shifted or a material id changed — and when two
		// implementations of the same extraction disagree, that is the only question worth asking first.
		long posDigest = hashFloats(FNV_OFFSET, packed.positions());
		long idxDigest = hashInts(hashInts(hashInts(FNV_OFFSET, packed.indices()),
				packed.bucketTris()), packed.triBase());
		long uvDigest = hashFloats(FNV_OFFSET, packed.uvs());
		// Two hashes built to survive a change of loader, because the plain ones cannot.
		//
		// posOrderless is commutative, so it ignores the order quads were emitted in. The Fabric Renderer
		// API emits in the model's own order; collecting vanilla parts yields the direction-independent
		// list first and then the six cullface lists. Same quads, different sequence — which an ordered
		// hash reports as a difference and a reader reasonably mistakes for wrong geometry.
		//
		// uvDelta hashes each triangle's UVs relative to its own first corner, which removes the sprite's
		// position in the atlas while keeping its size and orientation. Absolute atlas UVs are not
		// comparable across loaders at all: NeoForge contributes a mod_resources pack that Fabric does
		// not, the atlas stitches differently, and every sprite lands somewhere else. What does survive is a
		// sprite's extent in UV space, as long as the two atlases came out the same size — verify that from
		// the "Created: NxN ... blocks.png-atlas" line in both logs before trusting a uvDelta comparison.
		long posOrderless = hashOrderless(packed.positions(), 3, 1.0e-5f);
		// Emitters are appended in quad emission order, so the plain light hash inherits the same
		// order-sensitivity the position hash has. 20 floats per light — RtLightCollector.FLOATS_PER_LIGHT.
		long lightOrderless = hashOrderless(packed.lights(), RtLightCollector.FLOATS_PER_LIGHT, 1.0e-5f);
		long uvDelta = hashTriangleUvDeltas(packed.uvs(), 1.0e-6f);
		long uvCoarseDigest = hashQuantised(packed.uvs(), 1.0e-3f);
		long uvMediumDigest = hashQuantised(packed.uvs(), 1.0e-5f);
		long matDigest = hashFloats(FNV_OFFSET, packed.material());
		long lightDigest = hashFloats(FNV_OFFSET, packed.lights());
		long digest = mix(mix(mix(mix(mix(FNV_OFFSET, posDigest), idxDigest), uvDigest), matDigest),
				lightDigest);
		SECTIONS.compute(key, (k, previous) -> new Section(sx, sy, sz,
				packed.positions().length / 3,
				packed.indices().length / 3,
				packed.lights() == null ? 0 : packed.lights().length,
				digest,
				previous == null || previous.digest() != digest
						? (previous == null ? 1 : previous.builds() + 1)
						: previous.builds(),
				posDigest, idxDigest, uvDigest, matDigest, lightDigest,
				uvCoarseDigest, uvMediumDigest, posOrderless, uvDelta, lightOrderless));
		DIRTY.set(true);
		logSampleOnce(sx, sy, sz, packed);
		dumpSection(sx, sy, sz, packed);
	}

	/**
	 * Write one named section's triangles out individually.
	 *
	 * <p>The per-array hashes narrow a disagreement to "the positions differ in this section", which is as
	 * far as a hash can take you. It cannot say <em>which</em> faces, and when one implementation emits two
	 * quads fewer than the other, which faces is the entire question — it names the block, and the block
	 * names the model feature being mishandled.
	 *
	 * <p>Sorted, so quad emission order (which legitimately differs between quad sources) cannot show up as
	 * a difference; diffing two of these files yields exactly the faces one side is missing.
	 *
	 * <p>Rewritten on every build rather than captured on the first one. A section next to flowing fluid is
	 * re-meshed repeatedly, and its first build is mid-flow — the digest excludes those sections by requiring
	 * {@code builds == 1}, and a first-build capture would put exactly the sections that filter distrusts
	 * into a file that looks authoritative.
	 */
	private static synchronized void dumpSection(int sx, int sy, int sz, PackedSection packed) {
		if (!pinnedSections().contains(sectionKey(sx, sy, sz))) {
			return;
		}
		float[] pos = packed.positions();
		int[] idx = packed.indices();
		List<String> lines = new ArrayList<>(idx.length / 3);
		for (int t = 0; t + 3 <= idx.length; t += 3) {
			int a = idx[t] * 3;
			int b = idx[t + 1] * 3;
			int c = idx[t + 2] * 3;
			if (a + 2 >= pos.length || b + 2 >= pos.length || c + 2 >= pos.length) {
				continue;
			}
			float e1x = pos[b] - pos[a];
			float e1y = pos[b + 1] - pos[a + 1];
			float e1z = pos[b + 2] - pos[a + 2];
			float e2x = pos[c] - pos[a];
			float e2y = pos[c + 1] - pos[a + 1];
			float e2z = pos[c + 2] - pos[a + 2];
			float nx = e1y * e2z - e1z * e2y;
			float ny = e1z * e2x - e1x * e2z;
			float nz = e1x * e2y - e1y * e2x;
			float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
			if (len > 0.0f) {
				nx /= len;
				ny /= len;
				nz /= len;
			}
			// Centroid plus facing. The centroid alone separates the two triangles of a quad and locates the
			// face to well inside a block; the normal says which way it points, which is what tells a missing
			// cullface apart from a missing model part.
			lines.add(String.format("%9.4f %9.4f %9.4f  n=%6.3f %6.3f %6.3f",
					(pos[a] + pos[b] + pos[c]) / 3.0f,
					(pos[a + 1] + pos[b + 1] + pos[c + 1]) / 3.0f,
					(pos[a + 2] + pos[b + 2] + pos[c + 2]) / 3.0f,
					nx, ny, nz));
		}
		lines.sort(null);

		Path file;
		try {
			Path dir = Platform.paths().gameDir().resolve("rt-terrain-digest");
			Files.createDirectories(dir);
			file = dir.resolve(String.format("%s-section-%d_%d_%d.txt",
					Platform.get().loaderName(), sx, sy, sz));
		} catch (IOException e) {
			FluoriteMod.LOGGER.warn("Could not prepare the terrain digest directory", e);
			return;
		}
		try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
				StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
			out.write("# loader=" + Platform.get().loaderName()
					+ " section=" + sx + "," + sy + "," + sz
					+ " triangles=" + lines.size() + "\n");
			for (String line : lines) {
				out.write(line);
				out.write('\n');
			}
		} catch (IOException e) {
			FluoriteMod.LOGGER.warn("Could not write the section dump to {}", file, e);
			return;
		}
		FluoriteMod.LOGGER.info("Terrain digest: dumped {} triangles of section {},{},{} -> {}",
				lines.size(), sx, sy, sz, file);
	}

	/**
	 * Whether any block position is under cull tracing.
	 *
	 * <p>Read on the face-culling path, which runs six times per block for every block in the world, so it
	 * has to be free when nobody asked for a trace — a static final boolean the JIT folds away, not a set
	 * lookup and not a config read.
	 */
	public static final boolean CULL_TRACE_ACTIVE;

	private static final Set<Long> PINNED_SECTIONS;
	private static final Set<Long> CULL_TRACE_POSITIONS;

	static {
		PINNED_SECTIONS = parsePositions(FluoriteConfig.Rt.Diagnostics.TERRAIN_DIGEST_SECTIONS.get(),
				"terrain-digest-sections");
		CULL_TRACE_POSITIONS = parsePositions(FluoriteConfig.Rt.Diagnostics.CULL_TRACE.get(), "cull-trace");
		CULL_TRACE_ACTIVE = !CULL_TRACE_POSITIONS.isEmpty();
	}

	/** Parses {@code "x,y,z;x,y,z"} into packed keys, complaining about but skipping malformed entries. */
	private static Set<Long> parsePositions(String spec, String what) {
		Set<Long> parsed = new HashSet<>();
		for (String entry : spec.split(";")) {
			String trimmed = entry.trim();
			if (trimmed.isEmpty()) {
				continue;
			}
			String[] parts = trimmed.split(",");
			if (parts.length != 3) {
				FluoriteMod.LOGGER.warn("Ignoring {} entry '{}': want three comma-separated integers",
						what, trimmed);
				continue;
			}
			try {
				parsed.add(positionKey(Integer.parseInt(parts[0].trim()),
						Integer.parseInt(parts[1].trim()), Integer.parseInt(parts[2].trim())));
			} catch (NumberFormatException e) {
				FluoriteMod.LOGGER.warn("Ignoring {} entry '{}': not three integers", what, trimmed);
			}
		}
		return parsed;
	}

	/**
	 * Report one face-culling decision, for positions named by {@code cull-trace}.
	 *
	 * <p>The decision itself is shared code, so the two loaders cannot reach it differently — which is
	 * exactly why a disagreement about a face needs this: if both sides log the same verdict for a
	 * direction and only one side emits the face, the quad sources are bucketing that quad under different
	 * cullfaces, and the culling logic is not where to look.
	 */
	public static void traceCull(int x, int y, int z, Object state, Object neighbor, Object direction,
			boolean culled) {
		if (!CULL_TRACE_POSITIONS.contains(positionKey(x, y, z))) {
			return;
		}
		FluoriteMod.LOGGER.info("Cull trace {},{},{} face {}: {} -> culled={} (neighbour {})",
				x, y, z, direction, state, culled, neighbor);
	}

	private static Set<Long> pinnedSections() {
		return PINNED_SECTIONS;
	}

	private static long sectionKey(int sx, int sy, int sz) {
		return positionKey(sx, sy, sz);
	}

	private static long positionKey(int x, int y, int z) {
		return ((long) x & 0x1FFFFFL) << 42 | ((long) y & 0xFFFFFL) << 22 | ((long) z & 0x3FFFFFL);
	}

	/**
	 * Print one section's first quad verbatim, once per run. Hashes localise a disagreement to an array;
	 * they cannot say whether the values are rotated, offset, or in a different space altogether, and those
	 * want different fixes. One chosen section keeps the two runs comparable.
	 */
	private static synchronized void logSampleOnce(int sx, int sy, int sz, PackedSection packed) {
		// A fixed coordinate, not "the first one seen": worker completion order varies between runs, and
		// two samples from different sections compare different blocks with different sprites, which is
		// exactly as useless as it sounds.
		if (loggedSample || sx != 0 || sy != -64 || sz != 0
				|| packed.uvs().length < 24 || packed.positions().length < 12) {
			return;
		}
		loggedSample = true;
		StringBuilder sb = new StringBuilder();
		sb.append("Terrain digest sample section ").append(sx).append(',').append(sy).append(',').append(sz);
		sb.append(" verts=").append(packed.positions().length / 3);
		sb.append(" pos[0..3]=");
		for (int i = 0; i < 4; i++) {
			sb.append(String.format("(%.6f,%.6f,%.6f)", packed.positions()[i * 3],
					packed.positions()[i * 3 + 1], packed.positions()[i * 3 + 2]));
		}
		sb.append(" uv[0..11]=");
		for (int i = 0; i < 12; i++) {
			sb.append(String.format("(%.6f,%.6f)", packed.uvs()[i * 2], packed.uvs()[i * 2 + 1]));
		}
		FluoriteMod.LOGGER.info(sb.toString());
	}

	/** Drop everything, e.g. when terrain residency is fully cleared for a new world. */
	public static void reset() {
		SECTIONS.clear();
		stableDumps = 0;
		lastCombined = 0L;
		loggedSample = false;
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
			FluoriteMod.LOGGER.warn("Could not prepare the terrain digest directory", e);
			return;
		}

		try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
				StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
			out.write("# fluorite terrain digest\n");
			out.write("# loader=" + Platform.get().loaderName() + " sections=" + sorted.size() + "\n");
			out.write(String.format("# combined=%016x%n", combined));
			// Wait for this to be non-zero before using a capture as a baseline. Zero means terrain was
			// still re-meshing, and an unsettled capture has extra faces that later get culled.
			out.write("# stableDumps=" + stableDumps + "\n");
			out.write("# sx sy sz verts tris lights digest builds pos idx uv mat light uvCoarse uvMedium posSet uvDelta\n");
			for (Section s : sorted) {
				out.write(String.format(
						"%d %d %d %d %d %d %016x %d %016x %016x %016x %016x %016x %016x %016x %016x %016x %016x%n",
						s.sx(), s.sy(), s.sz(), s.verts(), s.tris(), s.lights(), s.digest(), s.builds(),
						s.posDigest(), s.idxDigest(), s.uvDigest(), s.matDigest(), s.lightDigest(),
						s.uvCoarseDigest(), s.uvMediumDigest(), s.posOrderless(), s.uvDelta(),
						s.lightOrderless()));
			}
		} catch (IOException e) {
			FluoriteMod.LOGGER.warn("Could not write the terrain digest to {}", file, e);
			return;
		}
		long rebuilt = sorted.stream().filter(s2 -> s2.builds() > 1).count();
		FluoriteMod.LOGGER.info("Terrain digest: {} sections ({} re-meshed), combined={}, stable for {} dump(s) -> {}",
				sorted.size(), rebuilt, String.format("%016x", combined), stableDumps, file);
	}

	/** Commutative over groups of {@code stride} floats, so emission order cannot register. */
	private static long hashOrderless(float[] values, int stride, float step) {
		if (values == null) {
			return 0L;
		}
		long acc = values.length;
		for (int i = 0; i + stride <= values.length; i += stride) {
			long h = FNV_OFFSET;
			for (int c = 0; c < stride; c++) {
				h = mix(h, Math.round(values[i + c] / step));
			}
			acc += h; // addition, so any permutation of the groups gives the same total
		}
		return acc;
	}

	/**
	 * Per-triangle UVs relative to that triangle's first corner. Drops where the sprite sits in the atlas,
	 * keeps how big it is and which way round it goes.
	 */
	private static long hashTriangleUvDeltas(float[] uvs, float step) {
		if (uvs == null) {
			return 0L;
		}
		long h = mix(FNV_OFFSET, uvs.length);
		for (int t = 0; t + 6 <= uvs.length; t += 6) {
			float u0 = uvs[t];
			float v0 = uvs[t + 1];
			for (int c = 1; c < 3; c++) {
				h = mix(h, Math.round((uvs[t + c * 2] - u0) / step));
				h = mix(h, Math.round((uvs[t + c * 2 + 1] - v0) / step));
			}
		}
		return h;
	}

	/** Hash on a fixed grid, so differences finer than {@code step} cannot register. */
	private static long hashQuantised(float[] values, float step) {
		if (values == null) {
			return mix(FNV_OFFSET, 0xffff_ffff_ffff_ffffL);
		}
		long h = mix(FNV_OFFSET, values.length);
		for (float v : values) {
			h = mix(h, Math.round(v / step));
		}
		return h;
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
