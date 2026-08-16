package io.github.dswepm.fluorite.rt;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;

/**
 * Loading a compiled SPIR-V module off the resource classpath, in one place.
 *
 * <p>This existed thirteen times — once in every pipeline class and in RtSky — under the same name and
 * the same signature, differing only in local variable names, brace style, and whether the failure said
 * "SPIR-V resource" or "shader". Thirteen copies of a routine nobody had reason to change is cheap right
 * up until one of them needs to change, at which point the other twelve are the bug.
 *
 * <p><b>Why one class's {@code getResourceAsStream} can serve all of them.</b> The path is absolute, and
 * {@code Class.getResourceAsStream} with a leading slash delegates straight to the class loader — the
 * class it is called on selects a loader and nothing else. Every caller ships in the same jar as this
 * one, so they all resolve identically. If that ever stops being true, this is the method to give an
 * explicit owner rather than the thing to copy again.
 */
public final class RtShaderModules {
    /** Where {@code compileShaders} puts its SPIR-V on the resource classpath. */
    private static final String SHADER_DIR = "/fluorite/rt/";

    private RtShaderModules() {}

    /**
     * Reads {@code name} from the shader resource directory and creates a Vulkan shader module from it.
     *
     * <p>The caller owns the returned handle and must destroy it; every caller does so at the end of the
     * pipeline creation that consumed it, which is why this deliberately does not try to cache.
     *
     * @param vk    the device to create the module on
     * @param stack the frame the create-info is allocated from; the module outlives it, the info does not
     * @param name  file name within the shader directory, e.g. {@code "display.comp.spv"}
     */
    public static long load(VkDevice vk, MemoryStack stack, String name) {
        byte[] bytes;
        try (InputStream in = RtShaderModules.class.getResourceAsStream(SHADER_DIR + name)) {
            if (in == null) {
                throw new IllegalStateException("missing SPIR-V resource: " + SHADER_DIR + name);
            }
            bytes = in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("failed to read SPIR-V resource: " + SHADER_DIR + name, e);
        }
        // Off-heap and explicitly freed rather than stack-allocated: a SPIR-V module is far larger than
        // a MemoryStack frame is meant to carry, and vkCreateShaderModule has copied it out by the time
        // this returns.
        ByteBuffer code = MemoryUtil.memAlloc(bytes.length).put(bytes);
        code.flip();
        try {
            VkShaderModuleCreateInfo info =
                    VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code);
            LongBuffer out = stack.mallocLong(1);
            RtContext.check(VK10.vkCreateShaderModule(vk, info, null, out),
                    "vkCreateShaderModule(" + name + ")");
            return out.get(0);
        } finally {
            MemoryUtil.memFree(code);
        }
    }
}
