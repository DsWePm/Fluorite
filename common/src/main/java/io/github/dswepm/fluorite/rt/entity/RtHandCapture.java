package io.github.dswepm.fluorite.rt.entity;

import io.github.dswepm.fluorite.platform.Platform;
import io.github.dswepm.fluorite.rt.RtFrameStats;
import org.joml.Matrix4f;

/**
 * M29: the first-person hand's capture, from the same submit stream vanilla rasterizes the hand with.
 *
 * <p>{@code GameRenderer.renderItemInHand} submits the arms + held items through
 * {@code ItemInHandRenderer.submitHandsWithItems(poseStack, SubmitNodeCollector, ...)} — the collector is
 * an argument, so a mixin swap (see GameRendererMixin) routes the whole stream into an
 * {@link RtEntityCollectorBase} instance here and the vanilla path sees an empty storage (which is how
 * the vanilla hand is suppressed: it is never submitted at all). Every material/texture/tint resolution
 * the entity path performs comes along for free; the collector is driven with
 * {@code begin(capture, false)} and never configured for dynamic lights, so the capture cannot
 * double-record the held-light sideband that RtEntities already owns for the player's world model.
 *
 * <p>The quads arrive in VIEW space (renderItemInHand applies modelView⁻¹ + bob before submitting; the
 * pose stack is pure rotation beyond that), which is exactly what the self-drawn pass wants: the vertex
 * stage projects with the vanilla hand projection, and the fragment stage's world transform is the view
 * rotation's transpose — the capture therefore stores that rotation and interleaves one flat vertex per
 * triangle corner (position/uv/normal/tangent/tint + the per-prim lanes the entity branch consumes).
 * The per-triangle tangent is derived here, on the CPU, with the same arithmetic as the shader-side
 * {@code uvTangent}: a raster fragment has no neighbouring vertices to re-derive the UV gradient from.
 */
public final class RtHandCapture {
    /** One interleaved raster vertex: 16 floats then 4 uints as float bits then 4 floats (96 B). */
    public static final int VERTEX_FLOATS = 24;
    public static final int VERTEX_BYTES = VERTEX_FLOATS * Float.BYTES;

    private static final RtHandCapture INSTANCE = new RtHandCapture();

    public static RtHandCapture INSTANCE() {
        return INSTANCE;
    }

    private final RtEntityCapture capture = new RtEntityCapture();
    private RtEntityCollectorBase collector;
    private boolean submitting;

    private final Matrix4f worldFromView = new Matrix4f();
    private Matrix4f handProjection;
    private float[] rasterVerts = new float[VERTEX_FLOATS * 1024];
    private int rasterFloatCount;
    private int quadCount;

    private RtHandCapture() {
    }

    /** The collector the hand-submit mixin swaps in. Created once; stateless between submissions. */
    public synchronized RtEntityCollectorBase collector() {
        if (collector == null) {
            collector = Platform.get().quads().newEntityCollector();
        }
        return collector;
    }

    /** Reset per frame; a stale projection from a menu frame must never project a gameplay hand. */
    public void beginFrame() {
        handProjection = null;
        rasterFloatCount = 0;
        quadCount = 0;
    }

    /** Capture the hand/3D-HUD projection at the exact {@code setProjectionMatrix} vanilla uses. */
    public void captureProjection(Matrix4f projection) {
        handProjection = new Matrix4f(projection);
    }

    /** Begin the collector swap for one {@code submitHandsWithItems} call. */
    public void beginSubmit() {
        capture.reset(256);
        collector().begin(capture, false);
        submitting = true;
    }

    /**
     * Close the capture and interleave the raster vertex stream. {@code viewRotationMatrix} is the
     * camera's world→view rotation (pure rotation, position handled separately in the shader).
     */
    public void endSubmit(Matrix4f viewRotationMatrix) {
        submitting = false;
        collector().begin(null, false);
        if (handProjection == null || capture.isEmpty()) {
            return;
        }
        // View -> world is the rotation's transpose (pure rotation: transpose is the inverse). The
        // translation is deliberately absent — the rebased camera position lives in WorldPush.camOffset
        // and the fragment stage adds it there, keeping ONE source for it.
        worldFromView.set(viewRotationMatrix).transpose();
        int tris = capture.idx.size() / 3;
        int needed = tris * 3 * VERTEX_FLOATS;
        if (rasterVerts.length < needed) {
            rasterVerts = new float[needed];
        }
        rasterFloatCount = 0;
        quadCount = tris / 2;
        for (int tri = 0; tri < tris; tri++) {
            int p0 = capture.idx.get(tri * 3);
            int p1 = capture.idx.get(tri * 3 + 1);
            int p2 = capture.idx.get(tri * 3 + 2);
            int primBase = tri * 12;
            float nx = capture.prim.getFloat(primBase);
            float ny = capture.prim.getFloat(primBase + 1);
            float nz = capture.prim.getFloat(primBase + 2);
            float emission = capture.prim.getFloat(primBase + 3);
            float tr = capture.prim.getFloat(primBase + 4);
            float tg = capture.prim.getFloat(primBase + 5);
            float tb = capture.prim.getFloat(primBase + 6);
            float texSlot = capture.prim.getFloat(primBase + 7);
            int materialId = Float.floatToRawIntBits(capture.prim.getFloat(primBase + 8));
            int primFlags = Float.floatToRawIntBits(capture.prim.getFloat(primBase + 9));
            int aux0 = Float.floatToRawIntBits(capture.prim.getFloat(primBase + 10));
            float overlayStrength = capture.prim.getFloat(primBase + 11);
            float alphaBucket = capture.alphaBuckets.get(tri);
            // Triangle tangent in view space, the uvTangent arithmetic (cross-product Gram-Schmidt
            // against the face normal, handedness from the UV winding).
            float x0 = capture.verts.getFloat(p0 * 3);
            float y0 = capture.verts.getFloat(p0 * 3 + 1);
            float z0 = capture.verts.getFloat(p0 * 3 + 2);
            float x1 = capture.verts.getFloat(p1 * 3);
            float y1 = capture.verts.getFloat(p1 * 3 + 1);
            float z1 = capture.verts.getFloat(p1 * 3 + 2);
            float x2 = capture.verts.getFloat(p2 * 3);
            float y2 = capture.verts.getFloat(p2 * 3 + 1);
            float z2 = capture.verts.getFloat(p2 * 3 + 2);
            float u0 = capture.uvList.getFloat(p0 * 2);
            float v0 = capture.uvList.getFloat(p0 * 2 + 1);
            float u1 = capture.uvList.getFloat(p1 * 2);
            float v1 = capture.uvList.getFloat(p1 * 2 + 1);
            float u2 = capture.uvList.getFloat(p2 * 2);
            float v2 = capture.uvList.getFloat(p2 * 2 + 1);
            float g1u = u1 - u0;
            float g1v = v1 - v0;
            float g2u = u2 - u0;
            float g2v = v2 - v0;
            float det = g1u * g2v - g1v * g2u;
            float tx = 0f;
            float ty = 0f;
            float tz = 0f;
            float handedness = 1f;
            if (Math.abs(det) > 1.0e-12f) {
                float r = 1f / det;
                float rawX = ((x1 - x0) * g2v - (x2 - x0) * g1v) * r;
                float rawY = ((y1 - y0) * g2v - (y2 - y0) * g1v) * r;
                float rawZ = ((z1 - z0) * g2v - (z2 - z0) * g1v) * r;
                handedness = det < 0f ? -1f : 1f;
                float dot = rawX * nx + rawY * ny + rawZ * nz;
                rawX -= nx * dot;
                rawY -= ny * dot;
                rawZ -= nz * dot;
                float len2 = rawX * rawX + rawY * rawY + rawZ * rawZ;
                if (len2 > 1.0e-12f) {
                    float len = (float) Math.sqrt(len2);
                    tx = rawX / len;
                    ty = rawY / len;
                    tz = rawZ / len;
                }
            }
            for (int corner = 0; corner < 3; corner++) {
                int pv = corner == 0 ? p0 : corner == 1 ? p1 : p2;
                rasterVerts[rasterFloatCount++] = capture.verts.getFloat(pv * 3);
                rasterVerts[rasterFloatCount++] = capture.verts.getFloat(pv * 3 + 1);
                rasterVerts[rasterFloatCount++] = capture.verts.getFloat(pv * 3 + 2);
                rasterVerts[rasterFloatCount++] = capture.uvList.getFloat(pv * 2);
                rasterVerts[rasterFloatCount++] = capture.uvList.getFloat(pv * 2 + 1);
                rasterVerts[rasterFloatCount++] = nx;
                rasterVerts[rasterFloatCount++] = ny;
                rasterVerts[rasterFloatCount++] = nz;
                rasterVerts[rasterFloatCount++] = tx;
                rasterVerts[rasterFloatCount++] = ty;
                rasterVerts[rasterFloatCount++] = tz;
                rasterVerts[rasterFloatCount++] = handedness;
                rasterVerts[rasterFloatCount++] = tr;
                rasterVerts[rasterFloatCount++] = tg;
                rasterVerts[rasterFloatCount++] = tb;
                rasterVerts[rasterFloatCount++] = emission;
                rasterVerts[rasterFloatCount++] = Float.intBitsToFloat(texSlot == (int) texSlot ? (int) texSlot : 0);
                rasterVerts[rasterFloatCount++] = Float.intBitsToFloat(materialId);
                rasterVerts[rasterFloatCount++] = Float.intBitsToFloat(primFlags);
                rasterVerts[rasterFloatCount++] = Float.intBitsToFloat(aux0);
                rasterVerts[rasterFloatCount++] = overlayStrength;
                rasterVerts[rasterFloatCount++] = alphaBucket;
                rasterVerts[rasterFloatCount++] = 0f;
                rasterVerts[rasterFloatCount++] = 0f;
            }
        }
        RtFrameStats.FRAME.count("handQuads", quadCount);
    }

    public boolean hasFrame() {
        return handProjection != null && rasterFloatCount > 0;
    }

    public Matrix4f handProjection() {
        return handProjection;
    }

    /** The view→world rotation (pure rotation; translation is WorldPush.camOffset in the shader). */
    public Matrix4f worldFromView() {
        return worldFromView;
    }

    public float[] rasterVerts() {
        return rasterVerts;
    }

    public int rasterFloatCount() {
        return rasterFloatCount;
    }

    public int vertexCount() {
        return rasterFloatCount / VERTEX_FLOATS;
    }

    public int quadCount() {
        return quadCount;
    }

    /** True between beginSubmit and endSubmit — the mixin's suppression predicate. */
    public boolean isSubmitting() {
        return submitting;
    }
}
