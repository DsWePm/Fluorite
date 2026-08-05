package io.github.dswepm.fluorite.rt.entity;

import net.minecraft.client.renderer.texture.OverlayTexture;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins vanilla's entity colour overlay against {@link RtEntityCapture#packOverlay}.
 *
 * <p>The trap this exists for is the alpha convention. {@code entity.fsh} applies the overlay as
 * {@code color.rgb = mix(overlayColor.rgb, color.rgb, overlayColor.a)}, so <b>alpha 1 means NO
 * overlay</b> — the reading opposite to the intuitive one, and a sign error there produces a
 * permanently red mob rather than an obviously broken image. The coordinates come from vanilla's own
 * {@code OverlayTexture} helpers so a future change to its packing fails here rather than on screen.
 */
final class RtEntityOverlayTest {
    private static final int HURT_TEXEL = 0xB2FF0000; // alpha 178, pure red
    private static final int NO_HURT = OverlayTexture.v(false);
    private static final int HURT = OverlayTexture.v(true);

    @Test
    void noOverlayResolvesToZero() {
        // The identity texel is white at alpha 255. Storing 0 instead keeps the common path free and
        // keeps 0xFFFFFFFF — that texel's literal encoding, and a float NaN — out of the aux lane.
        assertEquals(0, RtEntityCapture.packOverlay(OverlayTexture.NO_OVERLAY));
        assertEquals(0, RtEntityCapture.packOverlay(OverlayTexture.pack(0, NO_HURT)));
    }

    @Test
    void hurtIsRedAtPartialAlphaAndIgnoresTheWhiteAxis() {
        assertEquals(HURT_TEXEL, RtEntityCapture.packOverlay(OverlayTexture.pack(0, HURT)));
        // Rows below v = 8 are uniform in u: while hurt, the white-flash axis has no effect at all.
        assertEquals(HURT_TEXEL, RtEntityCapture.packOverlay(OverlayTexture.pack(15, HURT)));
        assertEquals(HURT_TEXEL, RtEntityCapture.packOverlay(OverlayTexture.pack(OverlayTexture.u(0.5f), HURT)));
        // ~30% red wash, not a replacement: mix(red, colour, 0.698) keeps most of the entity.
        assertEquals(178, HURT_TEXEL >>> 24);
    }

    @Test
    void whiteFlashScalesAlphaWithProgress() {
        // alpha = (int)((1 - u/15 * 0.75) * 255), white RGB. Full progress is a 75% white flash.
        assertEquals(0x3FFFFFFF, RtEntityCapture.packOverlay(OverlayTexture.pack(OverlayTexture.u(1.0f), NO_HURT)));
        assertEquals(63, 0x3FFFFFFF >>> 24);
        // Truncation, not rounding: 7/15 gives 165.75 -> 165.
        assertEquals(0xA5FFFFFF, RtEntityCapture.packOverlay(OverlayTexture.pack(7, NO_HURT)));
        // Monotone: more progress means lower alpha means more white.
        int quarter = RtEntityCapture.packOverlay(OverlayTexture.pack(OverlayTexture.u(0.25f), NO_HURT));
        int half = RtEntityCapture.packOverlay(OverlayTexture.pack(OverlayTexture.u(0.5f), NO_HURT));
        assertNotEquals(0, quarter);
        assertTrue((quarter >>> 24) > (half >>> 24), "alpha must fall as the flash progresses");
    }

    @Test
    void everyOverlaySurvivesTheFloatLanes() {
        // aux0 travels to the GPU as a float and is read back as a uint, so no encoding may be a NaN
        // bit pattern — the JVM is permitted to canonicalise those, which would silently rewrite the
        // overlay's colour. This is what caught the original single-lane 0xAARRGGBB packing: white at
        // alpha 127 is 0x7FFFFFFF, and alpha 127 is an ordinary point on the flash ramp (u = 10).
        for (int v = 0; v < 16; v++) {
            for (int u = 0; u < 16; u++) {
                int texel = RtEntityCapture.packOverlay(OverlayTexture.pack(u, v));
                int rgbLane = RtEntityCapture.overlayRgb(texel);
                float strength = RtEntityCapture.overlayStrength(texel);
                assertFalse(Float.isNaN(Float.intBitsToFloat(rgbLane)),
                        "overlay (" + u + "," + v + ") colour lane encodes to NaN");
                assertFalse(Float.isNaN(strength), "overlay (" + u + "," + v + ") strength is NaN");
                assertTrue(strength >= 0f && strength <= 1f,
                        "overlay (" + u + "," + v + ") strength out of range: " + strength);
            }
        }
    }

    @Test
    void bothLanesAreZeroIsIdentity() {
        // The record's lanes default to zero, and paths that never set an overlay leave them there. Zero
        // therefore has to mean "no overlay" — with vanilla's own alpha it would have meant the opposite.
        assertEquals(0, RtEntityCapture.overlayRgb(0));
        assertEquals(0f, RtEntityCapture.overlayStrength(0));
        // Hurt is a ~30% wash toward red, which is 1 - 178/255 of the way.
        int hurt = RtEntityCapture.packOverlay(OverlayTexture.pack(0, HURT));
        assertEquals(0x00FF0000, RtEntityCapture.overlayRgb(hurt));
        assertEquals(1f - 178f / 255f, RtEntityCapture.overlayStrength(hurt), 1.0e-6f);
        // Full flash is white at 75% strength.
        int flash = RtEntityCapture.packOverlay(OverlayTexture.pack(OverlayTexture.u(1.0f), NO_HURT));
        assertEquals(0x00FFFFFF, RtEntityCapture.overlayRgb(flash));
        assertEquals(1f - 63f / 255f, RtEntityCapture.overlayStrength(flash), 1.0e-6f);
    }

    @Test
    void outOfRangeWhiteAxisIsClamped() {
        // texelFetch would be undefined past the texture; the resolved value saturates at full flash.
        assertEquals(RtEntityCapture.packOverlay(OverlayTexture.pack(15, NO_HURT)),
                RtEntityCapture.packOverlay(OverlayTexture.pack(99, NO_HURT)));
    }
}
