package io.github.dswepm.fluorite.rt;

import io.github.dswepm.fluorite.FluoriteConfig;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M25 step (a): the two volumetric-ReSTIR source switches exist, and their OFF positions are the shipped
 * renderer bit for bit.
 *
 * <p>Iron law 8 is the whole point of this file. An A/B switch whose off position is merely "close to" the
 * published behaviour measures nothing, because every difference it then reports is ambiguous between the
 * change under test and the scaffolding around it. Here the guarantee is structural rather than careful:
 * both bits are RAISED on the ReSTIR side, so a zero environment-flags word is the shipped picture and no
 * code path has to remember to clear anything.
 *
 * <p>D196 chose two independent switches over one scope knob precisely so the published behaviour stays a
 * REACHABLE POSITION rather than a branch deleted along the way.
 */
final class RtVolumeRestirContractTest {

    /**
     * The defaults are the published renderer.
     *
     * <p>Not a style preference. Every picture and performance comparison this milestone makes is a ratio
     * against this position, so a default that quietly enabled either half would make the whole measurement
     * programme report differences against a baseline that never shipped.
     */
    @Test
    void bothSwitchesDefaultToTheBehaviourThatAlreadyShipped() {
        assertEquals("grid", FluoriteConfig.Rt.Volumetrics.VOLUME_RESTIR_NEAR_SOURCE.defaultValue());
        assertEquals("clamp", FluoriteConfig.Rt.Volumetrics.VOLUME_RESTIR_FAR_SOURCE.defaultValue());
        assertFalse(FluoriteConfig.Rt.Volumetrics.volumeRestirNear());
        assertFalse(FluoriteConfig.Rt.Volumetrics.volumeRestirFar());
    }

    /**
     * The bits are raised on the ReSTIR side, never on the shipped side.
     *
     * <p>The inverse spelling would pass every functional test and still be wrong: it makes "no flags" mean
     * "the new thing", so any future site that forgets to populate the word silently opts into ReSTIR.
     * worldPush.flags bit 1 and bit 15 already follow this convention.
     */
    @Test
    void aZeroEnvironmentWordIsTheShippedPicture() throws IOException {
        String composite = code(source("common/src/main/java/io/github/dswepm/fluorite/rt/RtComposite.java"));
        String body = between(composite, "private static int environmentFlags(", "\n    }");

        assertTrue(body.contains("if (FluoriteConfig.Rt.Volumetrics.volumeRestirNear()) {"));
        assertTrue(body.contains("if (FluoriteConfig.Rt.Volumetrics.volumeRestirFar()) {"));
        assertTrue(body.contains("flags |= 1 << 2;"));
        assertTrue(body.contains("flags |= 1 << 3;"));
        // The negated spelling is the failure this test exists for.
        assertFalse(body.contains("!FluoriteConfig.Rt.Volumetrics.volumeRestirNear()"));
        assertFalse(body.contains("!FluoriteConfig.Rt.Volumetrics.volumeRestirFar()"));
    }

    /**
     * The shader constants name the same two bits the Java raises.
     *
     * <p>Two literals that agree today are exactly how they stop agreeing later, and this pair fails as a
     * picture -- fog reading the wrong switch -- rather than as an error.
     */
    @Test
    void theShaderConstantsAndTheJavaBitsAreTheSameTwoBits() throws IOException {
        String common = code(source("shaders/world/world_common.slang"));

        assertTrue(common.contains("ENVIRONMENT_VOLUME_RESTIR_NEAR = 1u << 2u;"));
        assertTrue(common.contains("ENVIRONMENT_VOLUME_RESTIR_FAR = 1u << 3u;"));
        // Bits 0 and 1, and the 8-15 layer byte, were already spoken for.
        assertTrue(common.contains("ENVIRONMENT_AMBIENT_UNOCCLUDED = 1u << 0u;"));
        assertTrue(common.contains("ENVIRONMENT_FROXEL_LOCAL_LIGHTS = 1u << 1u;"));
        assertTrue(common.contains("ENVIRONMENT_LAYER_SHIFT = 8u;"));
    }

    /**
     * D197's floor is enforced, not merely documented.
     *
     * <p>At p=16 the measurement was 2.1-4.3 samples per cell per frame: the spatiotemporal reuse this
     * milestone exists to demonstrate cannot amortise anything at that density, and the table costs 685 MB
     * while failing to. A config below the floor does not produce a worse picture -- it produces a
     * configuration in which the milestone's proposition is untestable.
     */
    @Test
    void theLodKnobCannotBeSetFinerThanTheReuseCanFeed() throws IOException {
        assertEquals(32, FluoriteConfig.Rt.Volumetrics.VOLUME_RESTIR_LOD_PIXELS.defaultValue().intValue());
        assertEquals(45, FluoriteConfig.Rt.Volumetrics.VOLUME_RESTIR_EVICTION_FRAMES.defaultValue().intValue());
        assertEquals(4, FluoriteConfig.Rt.Volumetrics.VOLUME_RESTIR_CANDIDATES.defaultValue().intValue());
        assertTrue(code(source("common/src/main/java/io/github/dswepm/fluorite/FluoriteConfig.java"))
                .contains("Math.clamp(VOLUME_RESTIR_LOD_PIXELS.value(), 16, 256)"));
    }

    /**
     * Every key the option screen asks for exists in all three maintained locales.
     *
     * <p>A missing key renders as the raw translation id in the settings screen, which is the kind of defect
     * that ships because nobody opens that tab in the language it broke in.
     */
    @Test
    void allThreeMaintainedLocalesCarryEveryKeyTheScreenAsksFor() throws IOException {
        String[] keys = {
            "fluorite.options.rt.volumeRestirNearSource",
            "fluorite.options.rt.volumeRestirNearSource.grid",
            "fluorite.options.rt.volumeRestirNearSource.restir",
            "fluorite.options.rt.volumeRestirNearSource.tooltip",
            "fluorite.options.rt.volumeRestirFarSource",
            "fluorite.options.rt.volumeRestirFarSource.clamp",
            "fluorite.options.rt.volumeRestirFarSource.restir",
            "fluorite.options.rt.volumeRestirFarSource.tooltip",
        };
        for (String locale : new String[] {"en_us", "zh_cn", "zh_tw"}) {
            String lang = source("common/src/main/resources/assets/fluorite/lang/" + locale + ".json");
            for (String key : keys) {
                assertTrue(lang.contains(quote() + key + quote() + ":"), "missing in " + locale + ": " + key);
            }
        }
    }

    /**
     * The allocator reproduces the number the decision was actually made on.
     *
     * <p>D197 chose p=32 over p=16 and p=64 by reading a table of costs. If the code that allocates the
     * buffer computes something else, the choice was made about a renderer that does not exist -- and the
     * discrepancy would surface as a VRAM figure in a log line nobody diffs against a devlog.
     */
    @Test
    void theTableSizeReproducesTheNumberTheDecisionWasMadeOn() {
        long slots = RtComposite.volumeGridSlotsThatFit(32, 45, 1600, 900, 515.0);
        assertEquals(1_815_605L, slots);
        long mib = slots * 64L / (1024L * 1024L);
        assertEquals(110L, mib);
    }

    /**
     * At the finest LOD the floor allows, the VRAM ceiling is what binds -- not the model.
     *
     * <p>Worth pinning because it is the one configuration where the two limits meet: p=16 wants 685 MB,
     * the budget grants 512, and the table silently runs at a higher load factor instead of failing. That
     * is the intended behaviour and it is invisible from the picture, so only a test can hold it.
     */
    @Test
    void theCeilingBindsAtTheFinestLodTheFloorAllows() {
        long capSlots = RtComposite.VOLUME_GRID_MAX_BYTES / 64L;
        assertEquals(capSlots, RtComposite.volumeGridSlotsThatFit(16, 45, 1600, 900, 515.0));
        assertTrue(RtComposite.volumeGridSlotsThatFit(32, 45, 1600, 900, 515.0) < capSlots);
        assertTrue(RtComposite.volumeGridSlotsThatFit(64, 45, 1600, 900, 515.0) < capSlots);
    }

    /**
     * A render extent that does not exist yet allocates nothing.
     *
     * <p>ensureOutput calls this with the PREVIOUS render size, which is -1 before the first allocation.
     * Returning a slot count there would size the first table from a negative extent.
     */
    @Test
    void aRenderExtentThatDoesNotExistYetSizesNothing() {
        assertEquals(0L, RtComposite.volumeGridSlotsThatFit(32, 45, -1, -1, 515.0));
        assertEquals(0L, RtComposite.volumeGridSlotsThatFit(32, 45, 1600, 900, 0.0));
    }

    /**
     * The shader and the allocator implement ONE law, and both say so.
     *
     * <p>{@code volumeCellLevel} decides which cell a sample lands in; {@code volumeGridCellEdge} decides
     * how many cells the table is sized for. They are the same formula written twice in two languages, so
     * they will drift, and the drift is silent: the table simply runs at a load factor nobody chose.
     */
    @Test
    void theShaderAndTheAllocatorQuantiseDistanceTheSameWay() throws IOException {
        String hash = code(source("shaders/world/volume_hash.slang"));
        String composite = code(source("common/src/main/java/io/github/dswepm/fluorite/rt/RtComposite.java"));

        // Both floor the cell at one block, and both quantise by rounding in log2.
        assertTrue(hash.contains("if (want <= 1.0) {"));
        assertTrue(hash.contains("max(int(round(log2(want))), 0)"));
        assertTrue(composite.contains("if (want <= VOLUME_GRID_MIN_CELL) {"));
        assertTrue(composite.contains("Math.round(Math.log(want) / Math.log(2.0))"));
    }

    /**
     * The two hashes are independent, and the key can never be zero.
     *
     * <p>Both properties are load-bearing and both fail as a picture. A key derived from the slot mix
     * would confirm every collision as a match, so two cells sharing a slot would read each other's
     * samples -- light leaking between places that cannot see each other. And the table is cleared to
     * zeros, so a key that could legitimately be zero lets a live cell adopt a slot nobody wrote.
     */
    @Test
    void theSlotAndKeyHashesShareNoConstantAndTheKeyIsNeverZero() throws IOException {
        String hash = code(source("shaders/world/volume_hash.slang"));
        String slot = between(hash, "public uint volumeCellSlot(", "\n}");
        String key = between(hash, "public uint volumeCellKey(", "\n}");

        assertTrue(key.contains("h == 0u ? 1u : h"));
        assertTrue(slot.contains("uint(level)"), "the level must be mixed in, not just the coordinate");
        assertTrue(key.contains("uint(level)"));
        for (String constant : new String[] {"0x8DA6B343u", "0xD8163841u", "0xCB1AB31Fu", "0x2C1B3C6Du"}) {
            assertFalse(key.contains(constant), "key reuses a slot-hash constant: " + constant);
        }
    }

    /**
     * Eviction measures distance with unsigned subtraction, so a wrapped frame counter is harmless.
     *
     * <p>The natural spelling, {@code stamp < frame - n}, underflows once every 2^32 frames and recycles
     * the entire table for one frame when it does. Rare enough to never be reproduced, and it presents as
     * a single frame of noise.
     */
    @Test
    void evictionSurvivesTheFrameCounterWrapping() throws IOException {
        String expired = between(code(source("shaders/world/volume_hash.slang")),
                "public bool volumeCellExpired(", "\n}");
        assertTrue(expired.contains("(frame - stamp) > evictionFrames"));
        assertFalse(expired.contains("frame - evictionFrames"));
    }

    /**
     * The read path is silent unless a switch is on AND a live cell exists.
     *
     * <p>D199 widened the interface, which means the off state now depends on three fields staying unread
     * rather than on a branch not being taken. {@code skyDirValid} is false on every path that does not
     * set it, and both callers fall back to the shipped scalar line, so the published picture survives by
     * construction. This test holds the two ends of that: the early return, and the fallback.
     */
    @Test
    void theWidenedInterfaceIsInertUntilACellAnswers() throws IOException {
        String vis = code(source("shaders/world/volume_visibility.slang"));
        String volume = code(source("shaders/world/volume.slang"));

        String lookup = between(vis, "void volumeRestirSky(", "\n}");
        assertTrue(lookup.contains("worldPush.environmentFlags & switchBit) == 0u"));
        assertTrue(lookup.contains("worldPush.volumeGridSlots == 0u"));
        assertTrue(lookup.contains("worldPush.volumeGridAddr == 0"));
        // Both switches are dispatched, and on the two regions D196 named.
        assertTrue(vis.contains("volumeInsideVisibilityGrid(p)"));
        assertTrue(vis.contains("ENVIRONMENT_VOLUME_RESTIR_NEAR : ENVIRONMENT_VOLUME_RESTIR_FAR"));
        // Every consumer keeps the shipped scalar as its else-branch.
        assertTrue(volume.contains(": skySource * visSample.sky;"));
        assertTrue(volume.contains("float stepSkyVis = visSample.sky;"));
    }

    /**
     * One thread per cell per frame is elected with an atomic, not asserted by a comment.
     *
     * <p>The first wiring read the stamp, compared it, and wrote. That enforces nothing: every thread
     * landing in a cell reads before any write lands, so all of them pass, all trace their own candidate
     * rays, and all store. The rays become per-sample, which voids D198's entire cost argument, and the
     * concurrent stores tear the 64-byte record. It reached the screen as full-frame flicker.
     */
    @Test
    void theOneDepositPerCellRuleIsElectedRatherThanAsserted() throws IOException {
        String deposit = between(code(source("shaders/world/volume.slang")),
                "void volumeRestirDeposit(", "\n}");
        assertTrue(deposit.contains("InterlockedExchange(grid[slot].ident.y, frame, previous)"));
        assertTrue(deposit.contains("if (previous == frame) {"));
        // The check-then-act that did not work must not come back.
        assertFalse(deposit.contains("volumeCellStamp(c) == worldPush.frameIndex"));
    }

    /**
     * Readers take the parity nobody is writing.
     *
     * <p>Two halves, exactly as the reservoir store does it. Reading the half being filled is unordered
     * against the writes in the same dispatch, so a reader can observe a partially stored record -- the
     * other half of what the screen was showing.
     */
    @Test
    void theHalfBeingWrittenIsNeverTheHalfBeingRead() throws IOException {
        String deposit = between(code(source("shaders/world/volume.slang")),
                "void volumeRestirDeposit(", "\n}");
        String read = between(code(source("shaders/world/volume_visibility.slang")),
                "void volumeRestirSky(", "\n}");
        assertTrue(deposit.contains("volumeHalfBase(slots, frame)"), "writes this parity");
        assertTrue(deposit.contains("volumeHalfBase(slots, frame + 1u)"), "seeds from the other");
        assertTrue(read.contains("volumeHalfBase(worldPush.volumeGridSlots, worldPush.frameIndex + 1u)"));
        assertTrue(code(source("common/src/main/java/io/github/dswepm/fluorite/rt/RtComposite.java"))
                .contains("Math.multiplyExact(volumeGridSlots * 2L, RESERVOIR_BYTES)"));
    }

    /**
     * wSum is stored, and both W and M are bounded.
     *
     * <p>W is defined as wSum/(M*phat), so recovering wSum from W is circular and drifts -- which the
     * first version did. And unlike a surface reservoir this W multiplies a radiance that lights a whole
     * cell for as long as the cell lives, so one lucky sample in a mostly-occluded cave paints a bright
     * blob that persists for the eviction window. Both caps bound that.
     */
    @Test
    void theWeightIsBoundedAndTheSumIsStoredRatherThanRecovered() throws IOException {
        String hash = code(source("shaders/world/volume_hash.slang"));
        assertTrue(hash.contains("public float4 stats;"));
        assertTrue(hash.contains("VOLUME_W_MAX"));
        assertTrue(hash.contains("VOLUME_M_MAX"));
        String update = between(hash, "public void volumeCellUpdate(", "\n}");
        assertTrue(update.contains("c.stats.y += w;"), "wSum accumulates in its own lane");
        assertTrue(update.contains("min(c.stats.y / (c.stats.x * c.leT.w), VOLUME_W_MAX)"));
        assertFalse(update.contains("wSumBefore"), "wSum must not be reconstructed from W");
        // M IS CAPPED BY SCALING BOTH LANES. Clamping M alone was the second flicker: W is
        // wSum/(M*phat), so a frozen M with a still-growing wSum makes W climb without bound until it
        // hits the ceiling, every cell arriving there on its own schedule while eviction restarts others
        // on a stagger. The whole screen pulses, and no near/far tuning touches it.
        assertTrue(update.contains("float scale = VOLUME_M_MAX / c.stats.x;"));
        assertTrue(update.contains("c.stats.x *= scale;"));
        assertTrue(update.contains("c.stats.y *= scale;"));
        assertFalse(update.contains("min(c.stats.x + 1.0, VOLUME_M_MAX)"),
                "clamping M without scaling wSum is the defect this replaced");
    }

    private static String quote() {
        return String.valueOf((char) 34);
    }

    private static String between(String haystack, String needle, String end) {
        int start = haystack.indexOf(needle);
        assertTrue(start >= 0, "not found: " + needle);
        int stop = haystack.indexOf(end, start);
        assertTrue(stop > start, "unterminated: " + needle);
        return haystack.substring(start, stop);
    }

    /** Strip // comments so an assertion cannot be satisfied by the prose that describes the bug. */
    private static String code(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (String line : text.split("\n", -1)) {
            int slash = line.indexOf("//");
            out.append(slash >= 0 ? line.substring(0, slash) : line).append('\n');
        }
        return out.toString();
    }

    /** CR stripped: the shader tree is CRLF, so multi-line assertions otherwise never match. */
    private static String source(String relativePath) throws IOException {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("settings.gradle"))) {
            root = root.getParent();
        }
        assertTrue(root != null, "repository root not found");
        return Files.readString(root.resolve(relativePath)).replace(String.valueOf((char) 13), "");
    }
}
