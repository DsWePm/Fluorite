package io.github.dswepm.fluorite.rt.sky;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.LayerLightEventListener;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Minecraft's own sky light, shipped to the GPU as a toroidal 3D field so that fog beyond the ray-traced
 * visibility box can be shaded from a measurement instead of an extrapolation.
 *
 * <p><b>WHY THIS EXISTS.</b> The visibility grid reaches about 32 blocks. Past it the marched fog was
 * handed a fully open sky, which lit distant cave fog (#41). Clamping to the box's boundary cell instead
 * was tried and rejected in game: it darkened distant fog OUTDOORS too, because 100 blocks away the
 * boundary cell is simply a measurement of somewhere else. Both failures are the same failure -- there
 * was no data out there.
 *
 * <p><b>WHY IT COSTS NO RAYS.</b> Growing the visibility grid to this reach would cast its one ray per
 * cell over 56 million cells instead of 131 thousand, roughly 11 ms a frame extrapolated from the
 * 0.042 ms that grid measures today. Minecraft has ALREADY run this flood fill and keeps it current.
 * This class does not compute sky light; it moves it. That asymmetry is the whole argument.
 *
 * <p><b>WHY THE CELLS ARE ONE BLOCK.</b> Every coarser cell reproduces a bug this project has already
 * shipped and fixed. Take a four-block cell straddling a dirt roof: its maximum is the 15 above the
 * roof, so the sealed room below reads lit -- verbatim the "fog is bright inside a windowless dirt hut"
 * report. Its average pulls an outdoor ground cell, half of it stone at sky light 0, down to a half-lit
 * sky. Its minimum takes that same cell to black. VISIBILITY_CELL_SIZE's javadoc records that a coarse
 * CPU sky-light grid is where this line of work started and how it failed. Minecraft's value is per
 * block and correct per block; downsampling is the only operation that can break it.
 *
 * <p><b>WHY IT IS TOROIDAL RATHER THAN SLID.</b> A camera-anchored field has to move its contents when
 * the camera does, and an image cannot copy over itself -- which costs either a second image and a GPU
 * copy every sixteen blocks, or a 56 MB re-upload at the same rate. Addressing the image modulo its own
 * size costs neither: a section leaving the window is overwritten by the one entering at the same
 * modular slot, so the only work a step of travel creates is reading the slab that actually became
 * visible. The whole vertical axis is world-absolute for the same reason it can be -- the world is 384
 * blocks tall and so is this, so Y never wraps at all, and the +Y hole this field's predecessor left
 * above its box (issue #73) does not exist here.
 *
 * <p><b>WHAT THE TORUS COSTS.</b> One texel of the sampler's filtering blends the window's two ends,
 * which sit 192 blocks from the camera on each horizontal axis. That is a one-block band of wrong values
 * at 192 blocks, inside fog that at any interesting density is long since opaque. Accepted deliberately,
 * with the double-buffered alternative costing exactly the same memory and kept as the fallback if the
 * band ever becomes visible.
 *
 * <p><b>UNRESOLVED CELLS READ FULL SKY, NOT ZERO,</b> and that is what makes the fill invisible. Full
 * sky is the renderer's behaviour before this field existed, so a field that is half full looks like the
 * old renderer over the half it has not reached. Zero would black out the distance for a second after
 * every teleport, and a distinguishable sentinel would be worse still -- the sampler filters, so a
 * sentinel would bleed into real values along every boundary of the filled region.
 */
public final class RtSkyLightField {

    /** Blocks per axis. 384 vertically because that is the whole overworld, floor to build limit. */
    public static final int DIM = 384;
    public static final int SECTIONS = DIM / 16;
    private static final int SECTION_COUNT = SECTIONS * SECTIONS * SECTIONS;

    private static final int BLOCKS_PER_SECTION = 16 * 16 * 16;
    /** Sky light is 0..15; times 17 lands 15 on 255, so the sampler's UNORM read is already the fraction. */
    private static final int LIGHT_SCALE = 17;
    private static final byte FULL_SKY = (byte) 255;

    /**
     * Sections refilled per frame. A budget, not a queue drain.
     *
     * <p>Reading a section is 4096 nibble unpacks against a live chunk on the render thread -- the same
     * trade uploadWaterObstacles already documents for its 65k block lookups. Bounding it turns a
     * dimension change from one long hitch into a couple of seconds of the old look receding, which is
     * the failure mode the FULL_SKY default was chosen to make invisible. One horizontal step of travel
     * dirties a 576-section slab, so this drains a step in six frames with room to spare.
     */
    private static final int SECTION_BUDGET = 96;

    /**
     * The field itself, and it is the UPLOAD BUFFER rather than a Java array that gets copied into one.
     *
     * <p>56 MB is too much to hold twice and too much to hand the collector. Writing straight into
     * mapped host memory also removes the gather step an upload would otherwise need: because this holds
     * the same row-major layout the image does, a section is a sub-box of both, so vkCmdCopyBufferToImage
     * reads it in place with bufferRowLength and bufferImageHeight naming the field's own extent.
     */
    private final ByteBuffer cells;
    /** One section row, so a uniform fill is 256 bulk puts rather than 4096 single-byte ones. */
    private final byte[] rowScratch = new byte[16];
    private final boolean[] sectionQueued = new boolean[SECTION_COUNT];
    private final ArrayDeque<Integer> pending = new ArrayDeque<>();
    private final ArrayDeque<Integer> uploads = new ArrayDeque<>();

    /**
     * Which world section each modular slot currently holds, per horizontal axis.
     *
     * <p>Separable because the wrap is: a slot (sx, sy, sz) holds world section
     * (slotWorldX[sx], baseSectionY + sy, slotWorldZ[sz]). Tracking it per axis rather than per slot is
     * what makes a step of travel dirty exactly one slab instead of needing a search.
     */
    private final int[] slotWorldX = new int[SECTIONS];
    private final int[] slotWorldZ = new int[SECTIONS];

    /**
     * Light changes arriving from outside the render thread's own update.
     *
     * <p>Minecraft routes lighting-only invalidations through LevelExtractor.setSectionDirty, and while
     * that is normally the main thread it is not this class's place to assume so -- the queues below are
     * plain ArrayDeques driven by update(), and one off-thread add would corrupt them silently. A
     * concurrent inbox drained at the top of update() costs an uncontended poll per frame and removes
     * the question.
     */
    private static final ConcurrentLinkedQueue<Long> INBOX = new ConcurrentLinkedQueue<>();

    private int lastRead;
    private int lastProbed;
    private Object anchoredLevel;
    private boolean hasSkyLight = true;
    private int baseSectionY = Integer.MIN_VALUE;
    private int windowSectionX = Integer.MIN_VALUE;
    private int windowSectionZ = Integer.MIN_VALUE;

    public RtSkyLightField(ByteBuffer mirror) {
        if (mirror.capacity() < DIM * DIM * DIM) {
            throw new IllegalArgumentException("sky light mirror is too small: " + mirror.capacity());
        }
        this.cells = mirror;
        fillAll(FULL_SKY);
        Arrays.fill(slotWorldX, Integer.MIN_VALUE);
        Arrays.fill(slotWorldZ, Integer.MIN_VALUE);
    }

    public boolean anchored() {
        return windowSectionX != Integer.MIN_VALUE;
    }

    /** World Y of the field's bottom plane. X and Z have no origin: they wrap. */
    public int originBlockY() {
        return baseSectionY == Integer.MIN_VALUE ? 0 : baseSectionY * 16;
    }

    /**
     * Whether the field carries a usable answer at all.
     *
     * <p>False in a dimension without sky light -- the Nether, where every sky-light value is zero.
     * Multiplying the fog by that would delete the Nether's ambient glow entirely, so the field reports
     * itself unusable instead and the consumer keeps its published behaviour. A dimension's sky is a
     * property of the dimension, not a measurement this field is entitled to override.
     */
    public boolean usable() {
        return anchored() && hasSkyLight;
    }

    /**
     * Re-window around the camera and refill up to the frame's budget.
     *
     * @return true when any section's bytes changed, so the caller knows an upload is worth recording
     */
    public boolean update(ClientLevel level, double camX, double camY, double camZ) {
        if (level == null) {
            return false;
        }
        for (Long packed = INBOX.poll(); packed != null; packed = INBOX.poll()) {
            long v = packed;
            markSectionDirty((int) (v >> 42), (int) ((v << 22) >> 42), (int) ((v << 42) >> 42));
        }
        boolean sky = level.dimensionType().hasSkyLight();
        int minSectionY = SectionPos.blockToSectionCoord(level.getMinY());
        if (level != anchoredLevel || sky != hasSkyLight || minSectionY != baseSectionY) {
            reset(level, sky, minSectionY);
        }
        if (!hasSkyLight) {
            return false;
        }
        int centreX = SectionPos.blockToSectionCoord((int) Math.floor(camX));
        int centreZ = SectionPos.blockToSectionCoord((int) Math.floor(camZ));
        boolean moved = slideWindow(centreX - SECTIONS / 2, centreZ - SECTIONS / 2);
        return fill(level) || moved;
    }

    private void reset(ClientLevel level, boolean sky, int minSectionY) {
        anchoredLevel = level;
        hasSkyLight = sky;
        baseSectionY = minSectionY;
        windowSectionX = Integer.MIN_VALUE;
        windowSectionZ = Integer.MIN_VALUE;
        pending.clear();
        uploads.clear();
        Arrays.fill(sectionQueued, false);
        Arrays.fill(slotWorldX, Integer.MIN_VALUE);
        Arrays.fill(slotWorldZ, Integer.MIN_VALUE);
        fillAll(FULL_SKY);
    }

    /**
     * Point each modular slot at the world section the new window puts there, dirtying what changed.
     *
     * <p>Nothing is copied and nothing is uploaded here: a slot whose world section did not change keeps
     * bytes that are still correct, and a slot whose did is queued to be read. That is the entire cost of
     * movement in a toroidal field, and it is why this one does not need a second image.
     */
    private boolean slideWindow(int minSectionX, int minSectionZ) {
        if (minSectionX == windowSectionX && minSectionZ == windowSectionZ) {
            return false;
        }
        windowSectionX = minSectionX;
        windowSectionZ = minSectionZ;
        boolean changed = false;
        for (int i = 0; i < SECTIONS; i++) {
            int worldX = minSectionX + i;
            int slotX = Math.floorMod(worldX, SECTIONS);
            if (slotWorldX[slotX] != worldX) {
                slotWorldX[slotX] = worldX;
                dirtySlabX(slotX);
                changed = true;
            }
            int worldZ = minSectionZ + i;
            int slotZ = Math.floorMod(worldZ, SECTIONS);
            if (slotWorldZ[slotZ] != worldZ) {
                slotWorldZ[slotZ] = worldZ;
                dirtySlabZ(slotZ);
                changed = true;
            }
        }
        return changed;
    }

    private void dirtySlabX(int slotX) {
        for (int z = 0; z < SECTIONS; z++) {
            for (int y = 0; y < SECTIONS; y++) {
                enqueue(sectionIndex(slotX, y, z));
            }
        }
    }

    private void dirtySlabZ(int slotZ) {
        for (int x = 0; x < SECTIONS; x++) {
            for (int y = 0; y < SECTIONS; y++) {
                enqueue(sectionIndex(x, y, slotZ));
            }
        }
    }

    private boolean fill(ClientLevel level) {
        if (pending.isEmpty()) {
            // Zeroed on the way out too. Leaving the previous frame's count standing would report a
            // field that read nothing as one that read whatever it last did, which is a counter that
            // lies exactly when it is being trusted to say the fill has stopped.
            lastRead = 0;
            lastProbed = 0;
            return false;
        }
        LayerLightEventListener sky = level.getLightEngine().getLayerListener(LightLayer.SKY);
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        int done = 0;
        lastRead = 0;
        lastProbed = 0;
        while (done < SECTION_BUDGET && !pending.isEmpty()) {
            int index = pending.poll();
            sectionQueued[index] = false;
            int sx = index % SECTIONS;
            int sy = (index / SECTIONS) % SECTIONS;
            int sz = index / (SECTIONS * SECTIONS);
            if (slotWorldX[sx] == Integer.MIN_VALUE || slotWorldZ[sz] == Integer.MIN_VALUE) {
                continue; // the window has not claimed this slot yet
            }
            readSection(sky, probe, sx, sy, sz);
            uploads.add(index);
            done++;
        }
        lastRead = done;
        return done > 0;
    }

    /**
     * One section, preferring its packed DataLayer and falling back to a single probe for all of it.
     *
     * <p>The DataLayer is the fast path and the common one: 4096 nibbles, an array index each. A null
     * layer means Minecraft is not storing this section, which for SKY means uniform rather than absent,
     * so one getLightValue answers for all 4096 cells at a four-thousandth of the cost of asking per
     * block.
     */
    private void readSection(LayerLightEventListener sky, BlockPos.MutableBlockPos probe,
                             int sx, int sy, int sz) {
        int worldSecX = slotWorldX[sx];
        int worldSecY = baseSectionY + sy;
        int worldSecZ = slotWorldZ[sz];
        DataLayer layer = sky.getDataLayerData(SectionPos.of(worldSecX, worldSecY, worldSecZ));
        if (layer == null) {
            // ONE VALUE FOR 4096 CELLS, which is the coarse grid this class exists to avoid -- so it is
            // counted. A probed section straddling a cave roof takes the roof's open sky down over the
            // cave below it, which is the failure mode a coarse field has and this one is not supposed
            // to.
            lastProbed++;
            probe.set(worldSecX * 16 + 8, worldSecY * 16 + 8, worldSecZ * 16 + 8);
            fillSection(sx, sy, sz, scale(sky.getLightValue(probe)));
            return;
        }
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                // Built in a scratch row and put in one go. The mirror is mapped host memory VMA chose
                // for sequential write, which usually means write-combined -- where a run of single-byte
                // stores is the case that behaves worst.
                for (int x = 0; x < 16; x++) {
                    rowScratch[x] = scale(layer.get(x, y, z));
                }
                cells.put(cellIndex(sx * 16, sy * 16 + y, sz * 16 + z), rowScratch, 0, 16);
            }
        }
    }

    /**
     * A light change now matters, which it did not before this field existed.
     *
     * <p>LevelExtractorMixin deliberately does not hook setSectionDirty, and records why: lighting-only
     * invalidations route through it, and the renderer ray-traces its lighting, so a light change never
     * altered any geometry. That reasoning is still true of geometry and simply does not cover this
     * field, which IS Minecraft's lighting. Without the hook, a torch lit in a cave or a roof broken open
     * leaves the field describing a world that no longer exists.
     */
    /**
     * A section's lighting changed, from wherever Minecraft noticed it.
     *
     * <p>Static because the mixin that calls it has no handle on the renderer, and queued rather than
     * applied because it may not be the render thread. Packed into one long so the queue holds one box
     * per event instead of three.
     */
    public static void onSectionLightChanged(int sectionX, int sectionY, int sectionZ) {
        INBOX.add(((long) sectionX << 42) | (((long) sectionY & 0x3FFFFFL) << 21)
                | ((long) sectionZ & 0x1FFFFFL));
    }

    public void markSectionDirty(int sectionX, int sectionY, int sectionZ) {
        if (!usable()) {
            return;
        }
        int sy = sectionY - baseSectionY;
        if (sy < 0 || sy >= SECTIONS) {
            return;
        }
        int sx = Math.floorMod(sectionX, SECTIONS);
        int sz = Math.floorMod(sectionZ, SECTIONS);
        if (slotWorldX[sx] != sectionX || slotWorldZ[sz] != sectionZ) {
            return; // outside the window: that slot is holding a different section entirely
        }
        enqueue(sectionIndex(sx, sy, sz));
    }

    /** Drains sections whose bytes changed since the last call, for the caller to upload. */
    public int drainUploads(int[] out) {
        int n = 0;
        while (n < out.length && !uploads.isEmpty()) {
            out[n++] = uploads.poll();
        }
        return n;
    }

    public boolean hasUploads() {
        return !uploads.isEmpty();
    }

    /**
     * Sections still owed a read, which is the only way to tell an unfilled cell from an open one.
     *
     * <p>They are the same byte by design: full sky is the renderer's published answer, so an unfilled
     * field looks like the old renderer rather than like a hole. The cost of that choice is exactly this
     * ambiguity, and the counter is what pays it back -- a red region with pending at zero is red.
     */
    public int pendingSections() {
        return pending.size();
    }

    /** Sections read during the last update, so a stalled fill is distinguishable from a finished one. */
    public int sectionsReadLastUpdate() {
        return lastRead;
    }

    /** How many of those had no DataLayer and were filled from a single probe. */
    public int sectionsProbedLastUpdate() {
        return lastProbed;
    }

    public void invalidate() {
        anchoredLevel = null;
        baseSectionY = Integer.MIN_VALUE;
        windowSectionX = Integer.MIN_VALUE;
        windowSectionZ = Integer.MIN_VALUE;
        pending.clear();
        uploads.clear();
        Arrays.fill(sectionQueued, false);
        Arrays.fill(slotWorldX, Integer.MIN_VALUE);
        Arrays.fill(slotWorldZ, Integer.MIN_VALUE);
        fillAll(FULL_SKY);
    }

    private void enqueue(int index) {
        if (!sectionQueued[index]) {
            sectionQueued[index] = true;
            pending.add(index);
        }
    }

    private void fillSection(int sx, int sy, int sz, byte value) {
        Arrays.fill(rowScratch, value);
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                cells.put(cellIndex(sx * 16, sy * 16 + y, sz * 16 + z), rowScratch, 0, 16);
            }
        }
    }

    private void fillAll(byte value) {
        Arrays.fill(rowScratch, value);
        for (int i = 0; i + 16 <= cells.capacity(); i += 16) {
            cells.put(i, rowScratch, 0, 16);
        }
    }

    private static byte scale(int skyLight) {
        return (byte) (Math.clamp(skyLight, 0, 15) * LIGHT_SCALE);
    }

    public static int sectionIndex(int x, int y, int z) {
        return (z * SECTIONS + y) * SECTIONS + x;
    }

    /** Row-major with x fastest, matching the 3D image these bytes are copied into. */
    public static int cellIndex(int x, int y, int z) {
        return (z * DIM + y) * DIM + x;
    }

    /** Bytes one section contributes to an upload, which is also the copy granularity. */
    public static int sectionBytes() {
        return BLOCKS_PER_SECTION;
    }
}
