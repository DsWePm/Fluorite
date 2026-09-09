package io.github.dswepm.fluorite.rt;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Random replay (M28 S2) works ONLY while the path tracer's random stream is deterministic from the
 * path seed: the reservoir stores the seed a path was traced with, and a neighbour pixel re-drives the
 * same decision stream on shifted geometry. Nothing at runtime checks that -- a replay that produces
 * different decisions produces wrong reuse WEIGHTS, which look like ordinary noise or a slight brightness
 * shift, never like an error.
 *
 * <p>So the contract is pinned at the source level, in three parts: the seed travels with the record
 * (segment.slang), the stream is derived deterministically from it (world.rgen's loop idiom), and the
 * reservoir module that stores it exists with the layout the Java allocation assumes (restir_pt.slang).
 * Any of these moving means replay semantics moved; change them and this test in the same commit.
 */
final class RtPathReplayContractTest {

    @Test
    void theSeedTravelsWithThePackedRecord() throws IOException {
        String segment = source("shaders/world/segment.slang");
        // The packed record carries the path's RNG state -- replay reads it from the queue-side record.
        assertTrue(segment.contains("p.seed = seg.seed;"));
        // The banner is the only warning future editors get; its absence is how silent replay breakage
        // ships.
        assertTrue(segment.contains("REPLAY CONTRACT"));
    }

    @Test
    void theBounceLoopStreamIsDerivedDeterministicallyFromTheSeed() throws IOException {
        String world = source("shaders/world/world.rgen.slang");
        // The loop's first draw derives from the segment seed XOR the sample index. Replay re-derives the
        // same stream from the stored seed, so this exact derivation idiom is load-bearing: changing the
        // constant, the xor, or the pcg placement changes every stored seed's meaning.
        assertTrue(world.contains("uint seed = seg.seed ^ sampleIndex * 2246822519u;"));
        assertTrue(world.contains("seed = pcg(seed);"));
    }

    @Test
    void thePathReservoirModuleExistsWithThePinnedLayout() throws IOException {
        String restirPt = source("shaders/world/restir_pt.slang");
        // 48 bytes: the queue record's cache line. Same reasoning as PackedPathSegment -- a per-frame
        // read/write buffer must not straddle.
        assertTrue(restirPt.contains("PATH_RESERVOIR_BYTES = 48u"));
        assertTrue(restirPt.contains("pathSeed"));
        assertTrue(restirPt.contains("reconPos"));
        assertTrue(restirPt.contains("pathReservoirSlot"));
        // The seed field is the replay contract's storage half; losing it breaks temporal reuse quietly.
        assertTrue(restirPt.contains("REPLAY IS A CONTRACT"));
    }

    @Test
    void thePathReservoirSwitchExistsAndDefaultsToOff() throws IOException {
        String config = source("common/src/main/java/io/github/dswepm/fluorite/FluoriteConfig.java");
        assertTrue(config.contains("PATH_RESERVOIR ="));
        assertTrue(config.contains("\"composite.path-reservoir\", false"));
    }

    private static String source(String relativePath) throws IOException {
        java.nio.file.Path root = java.nio.file.Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !java.nio.file.Files.isRegularFile(root.resolve("settings.gradle"))) {
            root = root.getParent();
        }
        if (root == null) {
            throw new IOException("Could not locate repository root from " + System.getProperty("user.dir"));
        }
        return String.join("\n", java.nio.file.Files.readAllLines(root.resolve(relativePath)));
    }
}
