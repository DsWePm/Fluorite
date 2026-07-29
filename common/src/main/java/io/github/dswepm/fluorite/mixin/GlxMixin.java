package io.github.dswepm.fluorite.mixin;

import com.mojang.blaze3d.platform.GLX;
import io.github.dswepm.fluorite.FluoriteConfig;
import io.github.dswepm.fluorite.FluoriteMod;
import java.util.Locale;
import java.util.function.LongSupplier;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Selects the native Wayland window system required for Linux HDR presentation. */
@Mixin(GLX.class)
public abstract class GlxMixin {
    @Inject(
            method = "_initGlfw",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/lwjgl/glfw/GLFW;glfwInit()Z",
                    shift = At.Shift.BEFORE))
    private static void fluorite$preferWaylandForHdr(CallbackInfoReturnable<LongSupplier> cir) {
        if (!FluoriteConfig.Rt.Hdr.enabled() || !fluorite$isLinux()) {
            return;
        }

        if (!GLFW.glfwPlatformSupported(GLFW.GLFW_PLATFORM_WAYLAND)) {
            FluoriteMod.LOGGER.warn(
                    "HDR: this GLFW build has no Wayland support; Linux HDR presentation is unavailable");
            return;
        }

        String waylandDisplay = System.getenv("WAYLAND_DISPLAY");
        String sessionType = System.getenv("XDG_SESSION_TYPE");
        boolean waylandSession = waylandDisplay != null && !waylandDisplay.isBlank()
                || "wayland".equalsIgnoreCase(sessionType);
        if (!waylandSession) {
            FluoriteMod.LOGGER.warn(
                    "HDR: no Wayland session detected; Linux HDR requires launching Minecraft in a native Wayland session");
            return;
        }

        // Minecraft prefers X11 when both backends exist. This later hint replaces that choice for HDR.
        GLFW.glfwInitHint(GLFW.GLFW_PLATFORM, GLFW.GLFW_PLATFORM_WAYLAND);
        FluoriteMod.LOGGER.info("HDR: selecting GLFW's native Wayland backend for Linux HDR presentation");
    }

    @Inject(method = "_initGlfw", at = @At("RETURN"))
    private static void fluorite$logHdrWindowSystem(CallbackInfoReturnable<LongSupplier> cir) {
        if (!FluoriteConfig.Rt.Hdr.enabled() || !fluorite$isLinux()) {
            return;
        }

        int platform = GLFW.glfwGetPlatform();
        if (platform == GLFW.GLFW_PLATFORM_WAYLAND) {
            FluoriteMod.LOGGER.info("HDR: GLFW initialized with the native Wayland backend");
        } else {
            FluoriteMod.LOGGER.warn(
                    "HDR: GLFW initialized with platform {} instead of Wayland; the Vulkan surface is unlikely to expose HDR formats",
                    fluorite$platformName(platform));
        }
    }

    @Unique
    private static boolean fluorite$isLinux() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
    }

    @Unique
    private static String fluorite$platformName(int platform) {
        return switch (platform) {
            case GLFW.GLFW_PLATFORM_WAYLAND -> "Wayland";
            case GLFW.GLFW_PLATFORM_X11 -> "X11";
            case GLFW.GLFW_PLATFORM_WIN32 -> "Win32";
            case GLFW.GLFW_PLATFORM_COCOA -> "Cocoa";
            case GLFW.GLFW_PLATFORM_NULL -> "Null";
            default -> "unknown (" + platform + ")";
        };
    }
}
