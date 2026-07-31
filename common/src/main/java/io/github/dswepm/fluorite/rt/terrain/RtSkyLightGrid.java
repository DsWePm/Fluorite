package io.github.dswepm.fluorite.rt.terrain;

import java.nio.ByteBuffer;
import java.util.Arrays;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.lighting.LayerLightEventListener;

/**
 * Coarse sky-openness field around the camera, for the water's ambient scattering term.
 *
 * <p>The term it feeds had no occlusion at all: a constant source with a depth falloff, which meant one
 * knob was simultaneously too bright for a sealed underwater cave and too dark for a pond in a hill's
 * shadow — there is no constant that expresses both. What distinguishes those two cases is a property of
 * the world, and vanilla already computes it: sky light. It is column-based rather than sun-directional,
 * so a hill's shadow leaves it at 15 while a cave roof zeroes it, which is precisely the split the
 * ambient (not the sun) term needs.
 *
 * <p>Each cell is {@link #CELL_SIZE} blocks across and holds one byte: vanilla sky light averaged over
 * the cell's WATER blocks, or over all sampled positions when the cell holds no water. Water-first on
 * purpose — a cell straddling the sea floor would otherwise be dragged toward zero by samples inside
 * stone, and the consumer only ever evaluates this inside water.
 *
 * <p>Deliberately NOT part of {@code RtLightGridManager}'s emitter hierarchy, although that is also a
 * camera-area grid: the emitter grid is rebuilt through an async request/upload/publish lifecycle keyed
 * to section meshing, while this field's data comes from the light engine on the render thread and
 * refreshes on its own cadence. Sharing the arena would couple two lifecycles for the price of one
 * small buffer.
 *
 * <p>Refresh is amortized: a fixed number of cells per frame, round-robin, so a full sweep takes about
 * half a second and no frame pays a spike. The grid does not chase the camera cell-by-cell; it re-anchors
 * only when the camera drifts {@link #RECENTER_SLACK_CELLS} cells off centre, and the move is a
 * whole-cell content shift with clamped-edge fill (the same move-by-whole-texels lesson as R22 — resampling
 * under a moving origin smears).
 */
public final class RtSkyLightGrid {
    public static final int CELL_SIZE = 8;
    public static final int DIM_X = 24;
    public static final int DIM_Y = 16;
    public static final int DIM_Z = 24;
    public static final int CELL_COUNT = DIM_X * DIM_Y * DIM_Z;

    /** Full sweep in CELL_COUNT / CELLS_PER_FRAME = 36 frames — about half a second at 60 fps. */
    private static final int CELLS_PER_FRAME = 256;
    private static final int RECENTER_SLACK_CELLS = 2;
    /** Sample offsets within a cell, per axis: two interior planes rather than corners shared with neighbours. */
    private static final int[] SAMPLE_OFFSETS = {2, 6};

    private final byte[] cells = new byte[CELL_COUNT];
    private final byte[] shiftScratch = new byte[CELL_COUNT];
    private final BlockPos.MutableBlockPos samplePos = new BlockPos.MutableBlockPos();
    private int originX;
    private int originY;
    private int originZ;
    private boolean anchored;
    private int cursor;

    public boolean ready() {
        return anchored;
    }

    /** Grid origin in world block coordinates; the push subtracts the current terrain rebase. */
    public int originX() {
        return originX;
    }

    public int originY() {
        return originY;
    }

    public int originZ() {
        return originZ;
    }

    public void update(ClientLevel level, double camX, double camY, double camZ) {
        // The layer listener rather than a per-position convenience accessor: 26.2's Level no longer
        // exposes getBrightness(LightLayer, pos), and getEffectiveSkyBrightness folds in time-of-day
        // dimming, which is wrong here — the SOURCE radiance already carries the sun's state, so this
        // field must be pure geometry (how open the column is), or dusk would darken the water twice.
        LayerLightEventListener sky = level.getLightEngine().getLayerListener(LightLayer.SKY);
        int targetX = alignedOrigin(camX, DIM_X);
        int targetY = alignedOrigin(camY, DIM_Y);
        int targetZ = alignedOrigin(camZ, DIM_Z);
        if (!anchored) {
            originX = targetX;
            originY = targetY;
            originZ = targetZ;
            // Seed with the camera's own sky light rather than 0 or 15: either extreme is wrong for one
            // of the two common spawns (surface water vs cave), and wrong for the ~half second the first
            // sweep takes. The camera's value is at least right nearby, which is where the player looks.
            samplePos.set(Mth.floor(camX), Mth.floor(camY), Mth.floor(camZ));
            int seed = level.isLoaded(samplePos)
                    ? Math.min(255, sky.getLightValue(samplePos) * 17) : 0;
            Arrays.fill(cells, (byte) seed);
            anchored = true;
        } else if (Math.abs(targetX - originX) > RECENTER_SLACK_CELLS * CELL_SIZE
                || Math.abs(targetY - originY) > RECENTER_SLACK_CELLS * CELL_SIZE
                || Math.abs(targetZ - originZ) > RECENTER_SLACK_CELLS * CELL_SIZE) {
            shiftTo(targetX, targetY, targetZ);
        }
        for (int i = 0; i < CELLS_PER_FRAME; i++) {
            int c = cursor;
            cursor = (cursor + 1) % CELL_COUNT;
            int sampled = sampleCell(level, sky, c % DIM_X, (c / DIM_X) % DIM_Y, c / (DIM_X * DIM_Y));
            if (sampled >= 0) {
                cells[c] = (byte) sampled;
            }
        }
    }

    public void copyInto(ByteBuffer dst) {
        dst.put(0, cells, 0, CELL_COUNT);
    }

    private static int alignedOrigin(double cam, int dim) {
        return (Mth.floor(cam / CELL_SIZE) - dim / 2) * CELL_SIZE;
    }

    /** Whole-cell content move; cells sliding in from outside take the nearest old edge value. */
    private void shiftTo(int newX, int newY, int newZ) {
        int dx = (newX - originX) / CELL_SIZE;
        int dy = (newY - originY) / CELL_SIZE;
        int dz = (newZ - originZ) / CELL_SIZE;
        for (int z = 0; z < DIM_Z; z++) {
            int sz = Math.clamp(z + dz, 0, DIM_Z - 1);
            for (int y = 0; y < DIM_Y; y++) {
                int sy = Math.clamp(y + dy, 0, DIM_Y - 1);
                int dstRow = (z * DIM_Y + y) * DIM_X;
                int srcRow = (sz * DIM_Y + sy) * DIM_X;
                for (int x = 0; x < DIM_X; x++) {
                    shiftScratch[dstRow + x] = cells[srcRow + Math.clamp(x + dx, 0, DIM_X - 1)];
                }
            }
        }
        System.arraycopy(shiftScratch, 0, cells, 0, CELL_COUNT);
        originX = newX;
        originY = newY;
        originZ = newZ;
    }

    /** Byte value 0..255, or -1 to keep the old value (cell's chunk not loaded). */
    private int sampleCell(ClientLevel level, LayerLightEventListener sky, int cx, int cy, int cz) {
        int bx = originX + cx * CELL_SIZE;
        int by = originY + cy * CELL_SIZE;
        int bz = originZ + cz * CELL_SIZE;
        if (by + CELL_SIZE <= level.getMinY() || by > level.getMaxY()) {
            return 0; // outside the build height there is no water and no cave — treat as dark, cheaply
        }
        if (!level.isLoaded(samplePos.set(bx + CELL_SIZE / 2, by + CELL_SIZE / 2, bz + CELL_SIZE / 2))) {
            return -1;
        }
        int waterSum = 0;
        int waterCount = 0;
        int allSum = 0;
        int allCount = 0;
        for (int oy : SAMPLE_OFFSETS) {
            for (int oz : SAMPLE_OFFSETS) {
                for (int ox : SAMPLE_OFFSETS) {
                    samplePos.set(bx + ox, by + oy, bz + oz);
                    int light = sky.getLightValue(samplePos);
                    allSum += light;
                    allCount++;
                    if (level.getFluidState(samplePos).is(FluidTags.WATER)) {
                        waterSum += light;
                        waterCount++;
                    }
                }
            }
        }
        int average = waterCount > 0 ? waterSum / waterCount : allSum / Math.max(allCount, 1);
        return Math.min(255, average * 17);
    }
}
