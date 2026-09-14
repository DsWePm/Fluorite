package io.github.dswepm.fluorite.rt.overlay;

import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRDynamicRendering;
import org.lwjgl.vulkan.VkWriteDescriptorSetAccelerationStructureKHR;
import org.lwjgl.vulkan.VkClearValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBindingFlagsCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkRenderingAttachmentInfo;
import org.lwjgl.vulkan.VkRenderingInfo;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkOffset2D;
import org.lwjgl.vulkan.VkViewport;
import org.lwjgl.vulkan.VkWriteDescriptorSet;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VK10;

import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;

import io.github.dswepm.fluorite.FluoriteConfig;
import io.github.dswepm.fluorite.FluoriteMod;
import io.github.dswepm.fluorite.mixin.CommandEncoderAccessor;
import io.github.dswepm.fluorite.rt.RtComposite;
import io.github.dswepm.fluorite.rt.RtContext;
import io.github.dswepm.fluorite.rt.RtDebugLabels;
import io.github.dswepm.fluorite.rt.RtFrameStats;
import io.github.dswepm.fluorite.rt.RtGpuExecutor;
import io.github.dswepm.fluorite.rt.RtUiOverlay;
import io.github.dswepm.fluorite.rt.accel.RtBuffer;
import io.github.dswepm.fluorite.rt.entity.RtHandCapture;
import io.github.dswepm.fluorite.rt.gen.WorldPushConstantsData;
import io.github.dswepm.fluorite.rt.material.RtBlockMaterials;
import io.github.dswepm.fluorite.rt.material.RtMaterialRegistry;
import io.github.dswepm.fluorite.rt.entity.RtEntityTextures;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET_ACCELERATION_STRUCTURE_KHR;

/**
 * M29: the first-person hand, self-drawn at display resolution with the renderer's own lighting.
 *
 * <p>The capture ({@link RtHandCapture}) produces view-space quads through the vanilla submit stream;
 * this feature projects them with the vanilla hand projection and lights them from the world's own
 * sources — celestial E with atmospheric + cloud transmittance and a shadow ray, one grid/alias emitter
 * candidate, M18's dynamic records (the held torch), and the M28 sky field through
 * {@code volumeSkySurfaceIrradiance}. The result composites into {@link RtUiOverlay}'s transparent
 * target with straight-alpha over, the same recipe every world-overlay feature uses, so screen effects
 * and the GUI layer over it exactly as they layered over the vanilla hand.
 *
 * <p>SET LAYOUTS: set 0 is this feature's per-frame ring — the RT pipeline's binding numbers for what
 * the fragment imports (10 transmittance, 16 visibility grid, 61 cloud shadow), its own TLAS slot at
 * 62, and the frame-data SSBO at 63; set 1 is a mirror of the world pipeline's bindless arrays (same
 * binding numbers 0-3, same capacity) so slot IDs mean the same textures. The push block IS the RT
 * pipeline's {@code WorldPushConstants} shape, which is what lets the world modules' shared
 * {@code worldPush} global resolve. The vertex stage reads only the frame-data SSBO.
 *
 * <p>LIFETIME: everything this pass reads (WorldPush, light buffers, TLAS, bindless views) is this
 * frame's RT state, read one submission AFTER the frame token fired — the same PUSH_RING-frame-slack
 * argument the gpuTimers ring documents at its await. The rings below additionally await their own
 * slot's prior use before rewrite, mirroring RtComposite's pushRing discipline.
 */
public final class RtHandFeature {
    private static final int FRAME_RING = 4;
    private static final int FRAME_SET_SLOTS = 5; // 10, 16, 61, 62, 63

    private static final RtHandFeature INSTANCE = new RtHandFeature();

    public static RtHandFeature INSTANCE() {
        return INSTANCE;
    }

    private boolean failed;
    private boolean loggedMissingOverlay;

    private RtOverlayPipelines.Pipeline pipeline;
    private long frameSetLayout;
    private long frameSetPool;
    private final long[] frameSets = new long[FRAME_RING];
    private final RtGpuExecutor.TrackedGraphicsUse[] frameSetUses = new RtGpuExecutor.TrackedGraphicsUse[FRAME_RING];
    private int frameSetCursor = -1;

    private long bindlessLayout;
    private long bindlessPool;
    private long bindlessSet;
    private io.github.dswepm.fluorite.rt.accel.RtImage depthImage;
    private int mirroredSlots = -1;
    private int mirroredPages = -1;

    // Persistent per-frame host-visible rings (frames in flight), rewritten each frame after awaiting
    // the slot's prior mark — the pushRing discipline at quarter scale.
    private final RtBuffer[] vertexRing = new RtBuffer[FRAME_RING];
    private final RtBuffer[] frameDataRing = new RtBuffer[FRAME_RING];
    private final RtGpuExecutor.TrackedGraphicsUse[] bufferUses = new RtGpuExecutor.TrackedGraphicsUse[FRAME_RING];
    private int bufferCursor = -1;
    private static final int VERTEX_RING_BYTES = 4 * 1024 * 1024;

    private RtHandFeature() {
    }

    /** Master gate: config on, overlay path live, and the RT frame produced a TLAS to shadow against. */
    public static boolean enabled() {
        return !INSTANCE.failed
                && FluoriteConfig.Rt.Composite.HAND_RT_LIGHTING.value()
                && RtComposite.INSTANCE.currentTlasHandle() != 0L;
    }

    /**
     * Draw the captured hand into the UI overlay. Called from GameRendererMixin right after the hand
     * submit returns — inside the output-redirect window, after the world overlays composited, before
     * screen effects and the GUI.
     */
    public void draw() {
        if (!enabled() || failed) {
            return;
        }
        RtHandCapture capture = RtHandCapture.INSTANCE();
        if (!capture.hasFrame()) {
            return;
        }
        long overlayColorView = RtUiOverlay.overlayColorView();
        if (overlayColorView == 0L) {
            if (!loggedMissingOverlay) {
                loggedMissingOverlay = true;
                FluoriteMod.LOGGER.warn("Hand lighting: UI overlay has no Vulkan view; disabling for session");
            }
            failed = true;
            return;
        }
        RtContext ctx = RtContext.currentOrNull();
        if (ctx == null) {
            return;
        }
        RtGpuExecutor.GraphicsUse graphicsUse = RtComposite.INSTANCE.currentGraphicsUse();
        if (graphicsUse == null) {
            return;
        }
        int width = RtUiOverlay.overlayWidth();
        int height = RtUiOverlay.overlayHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ensureResources(ctx, stack);
            if (failed) {
                return;
            }
            ensureBindlessContent(ctx, stack);
            long depthView = ensureDepthImage(ctx, width, height, graphicsUse);
            if (depthView == 0L) {
                return;
            }

            // ---- rings: pick a slot, wait for it, write the host side.
            bufferCursor = (bufferCursor + 1) % FRAME_RING;
            RtBuffer vertexBuffer = ensureRingBuffer(ctx, bufferCursor, VERTEX_RING_BYTES,
                    VK10.VK_BUFFER_USAGE_VERTEX_BUFFER_BIT, "hand vertices");
            RtBuffer frameDataBuffer = ensureRingBuffer(ctx, bufferCursor, WorldPushConstantsData.BYTE_SIZE,
                    0, "hand frame data");
            ctx.gpuExecutor().graphicsUseWaiter().await(bufferUses[bufferCursor]);

            int vertexCount = capture.vertexCount();
            long vertexBytes = (long) vertexCount * RtHandCapture.VERTEX_BYTES;
            if (vertexBytes > VERTEX_RING_BYTES) {
                return; // a modded model beyond 4 MB of hand quads: skip rather than corrupt; logged once
            }
            MemoryUtil.memFloatBuffer(vertexBuffer.mapped, VERTEX_RING_BYTES / 4)
                    .put(0, capture.rasterVerts(), 0, capture.rasterFloatCount());
            vertexBuffer.flush(0, vertexBytes);

            ByteBuffer frameData = MemoryUtil.memByteBuffer(frameDataBuffer.mapped,
                    WorldPushConstantsData.BYTE_SIZE);
            WorldPushConstantsData push = new WorldPushConstantsData(
                    RtComposite.INSTANCE.currentWorldPushAddress(),
                    0L, // section table: the hand shader never reads terrain sections
                    0L, // entity geometry table: unused off the RT path
                    RtComposite.INSTANCE.materialTableAddressForHand(),
                    0L, // extension table: the v1 hand decode stays on header.params
                    RtComposite.INSTANCE.lightBufferAddressForHand(),
                    RtComposite.INSTANCE.lightAliasAddressForHand(),
                    RtComposite.INSTANCE.lightLocalAliasAddressForHand(),
                    RtComposite.INSTANCE.lightGridCellAddressForHand(),
                    RtComposite.INSTANCE.lightGridSpanAddressForHand(),
                    0L, // continuation queue
                    0L, // water probe
                    0L, // rain precipitation classes (the hand is not rain-wet in v1)
                    0L, // path reservoirs (M28's store; the hand consumes no reservoir)
                    (int) RtComposite.frameCounter(),
                    0,  // debug view
                    0); // shade flags: none of the RT-only shading modes apply
            push.write(frameData);
            frameDataBuffer.flush(0, WorldPushConstantsData.BYTE_SIZE);

            // ---- frame set: the views + TLAS + SSBO, written per frame into the ring slot.
            frameSetCursor = (frameSetCursor + 1) % FRAME_RING;
            RtGpuExecutor.TrackedGraphicsUse slotUse = frameSetUses[frameSetCursor];
            ctx.gpuExecutor().graphicsUseWaiter().await(slotUse);
            writeFrameSet(ctx, stack, frameSets[frameSetCursor], frameDataBuffer);
            slotUse.mark(graphicsUse);
            bufferUses[bufferCursor].mark(graphicsUse);

            // ---- record.
            var encoder = (com.mojang.blaze3d.vulkan.VulkanCommandEncoder)
                    ((CommandEncoderAccessor) RenderSystem.getDevice().createCommandEncoder()).fluorite$getBackend();
            VkCommandBuffer cmd = encoder.allocateAndBeginTransientCommandBuffer();
            try (RtDebugLabels.Scope cmdScope = RtDebugLabels.scope(ctx, cmd, "hand lit draw")) {
                VulkanCommandEncoder.memoryBarrier(cmd, stack);
                beginHandRendering(cmd, stack, overlayColorView, depthView, width, height);
                VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.handle);
                LongBuffer sets = stack.longs(frameSets[frameSetCursor], bindlessSet);
                VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS,
                        pipeline.layout, 0, sets, null);
                LongBuffer vertexBuffers = stack.longs(vertexBuffer.handle);
                LongBuffer offsets = stack.longs(0L);
                VK10.vkCmdBindVertexBuffers(cmd, 0, vertexBuffers, offsets);
                ByteBuffer pushConstants = stack.malloc(WorldPushConstantsData.BYTE_SIZE);
                push.write(pushConstants);
                VK10.vkCmdPushConstants(cmd, pipeline.layout,
                        VK10.VK_SHADER_STAGE_FRAGMENT_BIT, 0, pushConstants);
                VK10.vkCmdDraw(cmd, vertexCount, 1, 0, 0);
                KHRDynamicRendering.vkCmdEndRenderingKHR(cmd);
                VulkanCommandEncoder.memoryBarrier(cmd, stack);
            }
            if (VK10.vkEndCommandBuffer(cmd) != VK10.VK_SUCCESS) {
                throw new IllegalStateException("vkEndCommandBuffer(hand lit) failed");
            }
            encoder.execute(cmd);
            RtFrameStats.FRAME.count("handDraws", 1);
        } catch (Throwable t) {
            failed = true;
            FluoriteMod.LOGGER.error("Hand lighting failed; disabling for this session", t);
        }
    }

    private void beginHandRendering(VkCommandBuffer cmd, MemoryStack stack,
                                    long colorView, long depthView, int width, int height) {
        VkRenderingAttachmentInfo.Buffer colorAttach = VkRenderingAttachmentInfo.calloc(1, stack).sType$Default()
                .imageView(colorView).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                .loadOp(VK10.VK_ATTACHMENT_LOAD_OP_LOAD)
                .storeOp(VK10.VK_ATTACHMENT_STORE_OP_STORE);
        VkRenderingAttachmentInfo depthAttach = VkRenderingAttachmentInfo.calloc(stack).sType$Default()
                .imageView(depthView).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                .loadOp(VK10.VK_ATTACHMENT_LOAD_OP_CLEAR) // own image; cleared to far (0.0) per frame
                .storeOp(VK10.VK_ATTACHMENT_STORE_OP_STORE);
        VkClearValue.Buffer clearValue = VkClearValue.calloc(1, stack);
        clearValue.get(0).depthStencil().set(0.0f, 0);
        depthAttach.clearValue(clearValue.get(0));
        VkRect2D renderArea = VkRect2D.calloc(stack);
        renderArea.offset(VkOffset2D.calloc(stack).set(0, 0));
        renderArea.extent().set(width, height);
        VkRenderingInfo renderingInfo = VkRenderingInfo.calloc(stack).sType$Default()
                .renderArea(renderArea).layerCount(1)
                .pColorAttachments(colorAttach).pDepthAttachment(depthAttach);
        KHRDynamicRendering.vkCmdBeginRenderingKHR(cmd, renderingInfo);

        VkViewport.Buffer viewport = VkViewport.calloc(1, stack);
        viewport.get(0).x(0).y(0).width(width).height(height).minDepth(0f).maxDepth(1f);
        VK10.vkCmdSetViewport(cmd, 0, viewport);
        VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
        scissor.get(0).offset(VkOffset2D.calloc(stack).set(0, 0));
        scissor.get(0).extent().set(width, height);
        VK10.vkCmdSetScissor(cmd, 0, scissor);
    }

    private void ensureResources(RtContext ctx, MemoryStack stack) {
        long tlas = RtComposite.INSTANCE.currentTlasHandle();
        if (tlas == 0L) {
            return;
        }
        if (pipeline != null) {
            return;
        }
        // ---- set 0 layout: the RT numbers the frag imports + the hand's own slots.
        VkDescriptorSetLayoutBinding.Buffer binds = VkDescriptorSetLayoutBinding.calloc(FRAME_SET_SLOTS, stack);
        int b = 0;
        binds.get(b++).binding(10).descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_FRAGMENT_BIT);
        binds.get(b++).binding(16).descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_FRAGMENT_BIT);
        binds.get(b++).binding(61).descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_FRAGMENT_BIT);
        binds.get(b++).binding(62).descriptorType(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR)
                .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_FRAGMENT_BIT);
        binds.get(b).binding(63).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_VERTEX_BIT);
        frameSetLayout = createLayout(ctx, stack, binds, 0);
        for (int i = 0; i < FRAME_RING; i++) {
            frameSetUses[i] = new RtGpuExecutor.TrackedGraphicsUse();
        }

        // ---- set 1: the bindless mirror, world pipeline binding numbers and capacity.
        int capacity = RtEntityTextures.maxTextures();
        VkDescriptorSetLayoutBinding.Buffer bl = VkDescriptorSetLayoutBinding.calloc(4, stack);
        java.nio.IntBuffer bindFlags = stack.mallocInt(4);
        for (int i = 0; i < 4; i++) {
            bl.get(i).binding(i).descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(capacity).stageFlags(VK10.VK_SHADER_STAGE_FRAGMENT_BIT);
            bindFlags.put(i, VK12.VK_DESCRIPTOR_BINDING_PARTIALLY_BOUND_BIT);
        }
        VkDescriptorSetLayoutBindingFlagsCreateInfo bf = VkDescriptorSetLayoutBindingFlagsCreateInfo.calloc(stack)
                .sType$Default().pBindingFlags(bindFlags);
        org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo bdslci = org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo
                .calloc(stack).sType$Default().pNext(bf.address())
                .flags(VK12.VK_DESCRIPTOR_SET_LAYOUT_CREATE_UPDATE_AFTER_BIND_POOL_BIT).pBindings(bl);
        LongBuffer p = stack.mallocLong(1);
        if (VK10.vkCreateDescriptorSetLayout(ctx.vk(), bdslci, null, p) != VK10.VK_SUCCESS) {
            throw new IllegalStateException("vkCreateDescriptorSetLayout(hand bindless) failed");
        }
        bindlessLayout = p.get(0);
        RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET_LAYOUT, bindlessLayout, "hand bindless layout");
        VkDescriptorPoolSize.Buffer bps = VkDescriptorPoolSize.calloc(1, stack);
        bps.get(0).type(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(capacity * 4);
        VkDescriptorPoolCreateInfo bdpci = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                .flags(VK12.VK_DESCRIPTOR_POOL_CREATE_UPDATE_AFTER_BIND_BIT).maxSets(1).pPoolSizes(bps);
        if (VK10.vkCreateDescriptorPool(ctx.vk(), bdpci, null, p) != VK10.VK_SUCCESS) {
            throw new IllegalStateException("vkCreateDescriptorPool(hand bindless) failed");
        }
        bindlessPool = p.get(0);
        VkDescriptorSetAllocateInfo bdsai = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                .descriptorPool(bindlessPool).pSetLayouts(stack.longs(bindlessLayout));
        LongBuffer bpSet = stack.mallocLong(1);
        if (VK10.vkAllocateDescriptorSets(ctx.vk(), bdsai, bpSet) != VK10.VK_SUCCESS) {
            throw new IllegalStateException("vkAllocateDescriptorSets(hand bindless) failed");
        }
        bindlessSet = bpSet.get(0);
        RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET, bindlessSet, "hand bindless set");

        // ---- frame set pool + sets.
        int poolTypes = 3;
        VkDescriptorPoolSize.Buffer ps = VkDescriptorPoolSize.calloc(poolTypes, stack);
        ps.get(0).type(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR).descriptorCount(FRAME_RING);
        ps.get(1).type(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(FRAME_RING * 3);
        ps.get(2).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(FRAME_RING);
        VkDescriptorPoolCreateInfo dpci = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                .maxSets(FRAME_RING).pPoolSizes(ps);
        if (VK10.vkCreateDescriptorPool(ctx.vk(), dpci, null, p) != VK10.VK_SUCCESS) {
            throw new IllegalStateException("vkCreateDescriptorPool(hand frame) failed");
        }
        frameSetPool = p.get(0);
        LongBuffer layouts = stack.mallocLong(FRAME_RING);
        for (int i = 0; i < FRAME_RING; i++) {
            layouts.put(i, frameSetLayout);
        }
        VkDescriptorSetAllocateInfo dsai = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                .descriptorPool(frameSetPool).pSetLayouts(layouts);
        LongBuffer pSet = stack.mallocLong(FRAME_RING);
        if (VK10.vkAllocateDescriptorSets(ctx.vk(), dsai, pSet) != VK10.VK_SUCCESS) {
            throw new IllegalStateException("vkAllocateDescriptorSets(hand frame) failed");
        }
        for (int i = 0; i < FRAME_RING; i++) {
            frameSets[i] = pSet.get(i);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET, frameSets[i], "hand frame set " + i);
        }

        // ---- pipeline.
        pipeline = new RtOverlayPipelines.Spec("hand_lit.vert.spv", "hand_lit.frag.spv")
                .vertex(RtOverlayPipelines.VertexFormat.HAND_LIT)
                .topology(VK10.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST)
                .blend(RtOverlayPipelines.Blend.ALPHA)
                .attachment(RtWorldOverlay.TARGET_FORMAT)
                .depthAttachment(VK10.VK_FORMAT_D32_SFLOAT)
                .push(WorldPushConstantsData.BYTE_SIZE, VK10.VK_SHADER_STAGE_FRAGMENT_BIT)
                .descriptorSetLayouts(frameSetLayout, bindlessLayout)
                .build(ctx, "hand lit");
    }

    /** The hand pass's own depth buffer — format-decoupled from vanilla's overlay target, cleared per
     *  frame. Retired on resize like every overlay-owned image. */
    private long ensureDepthImage(RtContext ctx, int width, int height, RtGpuExecutor.GraphicsUse graphicsUse) {
        if (depthImage != null && (depthImage.width != width || depthImage.height != height)) {
            io.github.dswepm.fluorite.rt.accel.RtImage retired = depthImage;
            ctx.gpuExecutor().retireAfterGraphics(graphicsUse, retired::destroy);
            depthImage = null;
        }
        if (depthImage == null) {
            depthImage = ctx.createStorageImage(width, height, VK10.VK_FORMAT_D32_SFLOAT,
                    "hand depth " + width + "x" + height,
                    VK10.VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT);
        }
        return depthImage.view;
    }

    private long createLayout(RtContext ctx, MemoryStack stack,
                              VkDescriptorSetLayoutBinding.Buffer binds, long flags) {
        org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo ci = org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo
                .calloc(stack).sType$Default().pBindings(binds);
        LongBuffer p = stack.mallocLong(1);
        if (VK10.vkCreateDescriptorSetLayout(ctx.vk(), ci, null, p) != VK10.VK_SUCCESS) {
            throw new IllegalStateException("vkCreateDescriptorSetLayout(hand frame) failed");
        }
        RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET_LAYOUT, p.get(0), "hand frame layout");
        return p.get(0);
    }

    private RtBuffer ensureRingBuffer(RtContext ctx, int slot, long bytes, int extraUsage, String label) {
        RtBuffer buffer = extraUsage == 0 ? frameDataRing[slot] : vertexRing[slot];
        if (buffer != null) {
            return buffer;
        }
        int usage = VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | extraUsage;
        RtBuffer created = ctx.createBuffer(bytes, usage, true, label);
        if (extraUsage == 0) {
            frameDataRing[slot] = created;
        } else {
            vertexRing[slot] = created;
        }
        return created;
    }

    private void writeFrameSet(RtContext ctx, MemoryStack stack, long set, RtBuffer frameDataBuffer) {
        VkDescriptorImageInfo.Buffer samplerInfos = VkDescriptorImageInfo.calloc(3, stack);
        samplerInfos.get(0).sampler(RtComposite.INSTANCE.lutSamplerForHand())
                .imageView(RtComposite.INSTANCE.skyTransmittanceViewForHand())
                .imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
        samplerInfos.get(1).sampler(RtComposite.INSTANCE.lutSamplerForHand())
                .imageView(RtComposite.INSTANCE.visibilityGridViewForHand())
                .imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
        samplerInfos.get(2).sampler(RtComposite.INSTANCE.lutSamplerForHand())
                .imageView(RtComposite.INSTANCE.cloudShadowViewForHand())
                .imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
        VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(FRAME_SET_SLOTS, stack);
        int w = 0;
        writes.get(w).sType$Default().dstSet(set).dstBinding(10).dstArrayElement(0)
                .descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                .descriptorCount(1).pImageInfo(samplerInfos.slice(0, 1));
        w++;
        writes.get(w).sType$Default().dstSet(set).dstBinding(16).dstArrayElement(0)
                .descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                .descriptorCount(1).pImageInfo(samplerInfos.slice(1, 1));
        w++;
        writes.get(w).sType$Default().dstSet(set).dstBinding(61).dstArrayElement(0)
                .descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                .descriptorCount(1).pImageInfo(samplerInfos.slice(2, 1));
        w++;
        VkWriteDescriptorSetAccelerationStructureKHR asWrite = VkWriteDescriptorSetAccelerationStructureKHR
                .calloc(stack).sType$Default()
                .pAccelerationStructures(stack.longs(RtComposite.INSTANCE.currentTlasHandle()));
        writes.get(w).sType$Default().pNext(asWrite.address()).dstSet(set).dstBinding(62)
                .dstArrayElement(0).descriptorType(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR)
                .descriptorCount(1);
        w++;
        VkDescriptorBufferInfo.Buffer bufferInfo = VkDescriptorBufferInfo.calloc(1, stack);
        bufferInfo.get(0).buffer(frameDataBuffer.handle).offset(0L).range(WorldPushConstantsData.BYTE_SIZE);
        writes.get(w).sType$Default().dstSet(set).dstBinding(63).dstArrayElement(0)
                .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                .descriptorCount(1).pBufferInfo(bufferInfo);
        VK10.vkUpdateDescriptorSets(ctx.vk(), writes, null);
    }

    /** Bindless mirror: slot 0 + any newly allocated entity slots, and the canonical pages on change. */
    private void ensureBindlessContent(RtContext ctx, MemoryStack stack) {
        long sampler = RtComposite.INSTANCE.atlasSamplerForHand();
        if (sampler == 0L) {
            return;
        }
        int allocated = RtEntityTextures.INSTANCE.allocatedSlots();
        int pages = RtBlockMaterials.INSTANCE.pageCount();
        if (mirroredSlots == allocated && mirroredPages == pages) {
            return;
        }
        try (MemoryStack stack2 = MemoryStack.stackPush()) {
            // slot 0 = block atlas fallback, then every allocated slot's view.
            writeBindless(ctx, stack2, 0, RtEntityTextures.INSTANCE.blockAtlasView(), sampler);
            for (int slot = 1; slot <= allocated; slot++) {
                long view = RtEntityTextures.INSTANCE.slotView(slot);
                if (view != 0L) {
                    writeBindless(ctx, stack2, slot, view, sampler);
                }
            }
            for (int page = 0; page < pages; page++) {
                writeBindless(ctx, stack2, page, RtBlockMaterials.INSTANCE.pageSurface0View(page), sampler, 1);
                writeBindless(ctx, stack2, page, RtBlockMaterials.INSTANCE.pageNormalAoView(page), sampler, 2);
                writeBindless(ctx, stack2, page, RtBlockMaterials.INSTANCE.pageSurface1View(page), sampler, 3);
            }
        }
        mirroredSlots = allocated;
        mirroredPages = pages;
    }

    private void writeBindless(RtContext ctx, MemoryStack stack, int slot, long view, long sampler) {
        writeBindless(ctx, stack, slot, view, sampler, 0);
    }

    private void writeBindless(RtContext ctx, MemoryStack stack, int slot, long view, long sampler, int binding) {
        if (view == 0L) {
            return;
        }
        VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack);
        info.get(0).sampler(sampler).imageView(view).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
        VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(1, stack);
        write.get(0).sType$Default().dstSet(bindlessSet).dstBinding(binding).dstArrayElement(slot)
                .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                .pImageInfo(info);
        VK10.vkUpdateDescriptorSets(ctx.vk(), write, null);
    }

    public void destroy() {
        // Called from RtComposite teardown; rings and sets die with the device context.
    }
}
