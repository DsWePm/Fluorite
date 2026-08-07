package io.github.dswepm.fluorite.rt.terrain;

import io.github.dswepm.fluorite.rt.RtContext;
import io.github.dswepm.fluorite.rt.accel.RtAccel;
import io.github.dswepm.fluorite.rt.accel.RtBuffer;
import io.github.dswepm.fluorite.rt.material.RtMaterialAbi;
import io.github.dswepm.fluorite.rt.terrain.RtTerrainMesher.PackedSection;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkMemoryBarrier2;

import static org.lwjgl.vulkan.KHRSynchronization2.VK_PIPELINE_STAGE_2_ACCELERATION_STRUCTURE_BUILD_BIT_KHR;
import static org.lwjgl.vulkan.KHRSynchronization2.vkCmdPipelineBarrier2KHR;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT;

/** Worker-owned terrain buffer allocation/fill and BLAS preparation. */
final class RtSectionBuilder {
    private RtSectionBuilder() {
    }

    /** Upload a non-empty packed section and prepare, but do not record, its BLAS build. */
    /**
     * Build one section's GPU residency.
     *
     * <p>{@code deformable} keeps this section ready for the water deformation (M12.5): its positions and
     * indices STAY RESIDENT rather than being freed after the build, it gets an untouched copy of its
     * water vertices to displace from, and its BLAS is built updatable so the displaced vertices can be
     * refit in place.
     *
     * <p>All three are the same decision and none of them is free. Compaction is given up (an updatable
     * BLAS cannot be compacted -- see F24, where the spec turns out not to say and the validation layer
     * turns out not to check), the build inputs stay resident instead of being reclaimed, and the rest
     * copy is a third buffer. That is why this is asked for per section and only for the few inside the
     * deformation range, rather than for every section that happens to contain water.
     */
    static PreparedSection prepare(RtContext ctx, PackedSection packed,
                                   RtAccel.OpacityMicromapInput ommInput,
                                   boolean compactBlas, boolean deformable,
                                   long key, int sox, int soy, int soz) {
        RtMaterialAbi.requireTriangleParity(packed.material().length, packed.indices().length);
        // Hash the packed arrays before they are uploaded and the CPU copies go away. No-op unless the
        // terrain-digest diagnostic is on.
        RtTerrainDigest.record(key, sox, soy, soz, packed);
        int vertCount = packed.positions().length / 3;
        int asInput = org.lwjgl.vulkan.KHRAccelerationStructure
                .VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;
        int storage = VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
        String label = "terrain section " + sox + "," + soy + "," + soz;
        // Resident geometry is device-local. A single mapped staging allocation exists only until
        // the async copy/build completes, avoiding a render-distance-sized host-visible working set.
        RtBuffer positions = null;
        RtBuffer indices = null;
        RtBuffer uvs = null;
        RtBuffer material = null;
        RtBuffer upload = null;
        RtBuffer waterRest = null;
        RtAccel.PreparedBlas blas = null;
        long updateScratch = 0L;
        // Nothing to displace means nothing to keep resident, whatever the caller asked for.
        boolean deform = deformable && packed.waterVertCount() > 0;
        try {
            long positionsBytes = (long) packed.positions().length * Float.BYTES;
            long indicesBytes = (long) packed.indices().length * Integer.BYTES;
            long uvsBytes = (long) packed.uvs().length * Float.BYTES;
            long materialBytes = (long) packed.material().length * Float.BYTES;
            int transferDst = VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;

            // The displacement compute pass WRITES the live positions and READS the rest copy, so both
            // need storage usage on top of being build inputs.
            int deformUsage = deform ? storage : 0;
            positions = ctx.createAsyncBuffer(positionsBytes, asInput | transferDst | deformUsage, false,
                    label + " positions");
            indices = ctx.createAsyncBuffer(indicesBytes, asInput | transferDst, false,
                    label + " indices");
            uvs = ctx.createAsyncBuffer(uvsBytes, storage | transferDst, false, label + " uvs");
            material = ctx.createAsyncBuffer(materialBytes, storage | transferDst, false, label + " material");
            upload = ctx.createUploadBuffer(positionsBytes + indicesBytes + uvsBytes + materialBytes,
                    label + " upload");
            if (deform) {
                // The vertices as the mesher laid them out, never written again. Displacing in place
                // would make this frame's output next frame's input and the surface would walk away from
                // the block face within a second. One contiguous range, which is only possible because
                // the mesher packs each bucket's vertices in a single run.
                waterRest = ctx.createAsyncBuffer((long) packed.waterVertCount() * 3L * Float.BYTES,
                        storage | transferDst, false, label + " water rest");
            }

            long cursor = upload.mapped;
            MemoryUtil.memFloatBuffer(cursor, packed.positions().length).put(packed.positions());
            cursor += positionsBytes;
            MemoryUtil.memIntBuffer(cursor, packed.indices().length).put(packed.indices());
            cursor += indicesBytes;
            MemoryUtil.memFloatBuffer(cursor, packed.uvs().length).put(packed.uvs());
            cursor += uvsBytes;
            MemoryUtil.memFloatBuffer(cursor, packed.material().length).put(packed.material());
            upload.flush();

            if (deform) {
                RtAccel.UpdatableBuild build = RtAccel.prepareTerrainBlas(ctx, positions, vertCount,
                        indices, packed.bucketTris(), ommInput, false, true, label + " BLAS");
                blas = build.op();
                updateScratch = build.updateScratchSize();
            } else {
                blas = RtAccel.prepareTerrainBlas(ctx, positions, vertCount, indices,
                        packed.bucketTris(), ommInput, compactBlas, label + " BLAS");
            }
            return new PreparedSection(key, positions, indices, uvs, material, upload, waterRest, blas,
                    packed.triBase(), sox, soy, soz, packed.lights(),
                    deform ? packed.waterVertBase() : 0, deform ? packed.waterVertCount() : 0,
                    updateScratch);
        } catch (Throwable t) {
            if (blas != null) {
                destroy(new PreparedSection(key, positions, indices, uvs, material, upload, waterRest,
                        blas, packed.triBase(), sox, soy, soz, packed.lights(), 0, 0, 0L));
            } else {
                if (waterRest != null) waterRest.destroy();
                if (upload != null) upload.destroy();
                if (material != null) material.destroy();
                if (uvs != null) uvs.destroy();
                if (indices != null) indices.destroy();
                if (positions != null) positions.destroy();
            }
            throw t;
        }
    }

    /** Copy the packed staging allocation into device-local section buffers before the BLAS build. */
    static void recordUpload(VkCommandBuffer cmd, PreparedSection prepared) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack);
            long srcOffset = 0L;
            copy(cmd, prepared.upload, prepared.positions, srcOffset, region);
            srcOffset += prepared.positions.size;
            copy(cmd, prepared.upload, prepared.indices, srcOffset, region);
            srcOffset += prepared.indices.size;
            copy(cmd, prepared.upload, prepared.uvs, srcOffset, region);
            srcOffset += prepared.uvs.size;
            copy(cmd, prepared.upload, prepared.material, srcOffset, region);
            if (prepared.waterRest != null) {
                // From the SAME staging bytes the live positions came from, so the two are identical by
                // construction rather than by a copy that could be recorded out of order.
                region.get(0).srcOffset((long) prepared.waterVertBase * 3L * Float.BYTES)
                        .dstOffset(0L).size(prepared.waterRest.size);
                VK10.vkCmdCopyBuffer(cmd, prepared.upload.handle, prepared.waterRest.handle, region);
            }

            VkMemoryBarrier2.Buffer barrier = VkMemoryBarrier2.calloc(1, stack);
            barrier.get(0).sType$Default()
                    .srcStageMask(VK_PIPELINE_STAGE_2_TRANSFER_BIT)
                    .srcAccessMask(VK_ACCESS_2_TRANSFER_WRITE_BIT)
                    .dstStageMask(VK_PIPELINE_STAGE_2_ACCELERATION_STRUCTURE_BUILD_BIT_KHR)
                    // Vertex/index build inputs are shader reads at the AS-build stage. The
                    // ACCELERATION_STRUCTURE_READ access class is for reading AS objects themselves.
                    .dstAccessMask(VK_ACCESS_2_SHADER_READ_BIT);
            VkDependencyInfo dependency = VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(barrier);
            vkCmdPipelineBarrier2KHR(cmd, dependency);
        }
    }

    private static void copy(VkCommandBuffer cmd, RtBuffer upload, RtBuffer destination,
                             long srcOffset, VkBufferCopy.Buffer region) {
        region.get(0).srcOffset(srcOffset).dstOffset(0L).size(destination.size);
        VK10.vkCmdCopyBuffer(cmd, upload.handle, destination.handle, region);
    }

    static void destroy(PreparedSection prepared) {
        RtAccel.freeBlasScratch(java.util.List.of(prepared.blas));
        prepared.blas.accel.destroy();
        prepared.upload.destroy();
        if (prepared.waterRest != null) {
            prepared.waterRest.destroy();
        }
        prepared.material.destroy();
        prepared.uvs.destroy();
        prepared.indices.destroy();
        prepared.positions.destroy();
    }

    /** Worker-owned native section state paired with its prepared BLAS. {@code lights} = packed
     *  section-local RIS light records (CPU-side, flattened into the global buffer at publish). */
    record PreparedSection(long key, RtBuffer positions, RtBuffer indices, RtBuffer uvs,
                           RtBuffer material, RtBuffer upload, RtBuffer waterRest,
                           RtAccel.PreparedBlas blas, int[] triBase,
                           int sx, int sy, int sz, float[] lights,
                           int waterVertBase, int waterVertCount, long updateScratchSize) {
        boolean deformable() {
            return waterRest != null;
        }

        void releaseUpload() {
            upload.destroy();
        }

        /**
         * Reclaim what the build needed and the shading does not.
         *
         * <p>A DEFORMABLE SECTION KEEPS BOTH. The displacement rewrites the positions every frame and the
         * refit reads positions and indices back as build inputs, so freeing them here would leave the
         * refit pointing at dead memory -- and per F24 nothing would report that, it would just produce
         * wrong geometry. They are freed with the section instead.
         */
        void releaseBuildInputs() {
            if (deformable()) {
                return;
            }
            indices.destroy();
            positions.destroy();
        }

        PreparedSection withBlas(RtAccel.PreparedBlas replacement) {
            return new PreparedSection(key, positions, indices, uvs, material, upload, waterRest,
                    replacement, triBase, sx, sy, sz, lights,
                    waterVertBase, waterVertCount, updateScratchSize);
        }
    }
}
