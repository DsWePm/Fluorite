package io.github.dswepm.fluorite.rt.entity;

import io.github.dswepm.fluorite.FluoriteConfig;
import io.github.dswepm.fluorite.rt.RtFrameStats;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * D127A's selected CPU view of the same deterministic event field used by shader ripples. Slanted rays
 * turn a target subset of those events into real-surface neutral geometry in the RT particle BLAS.
 * There is deliberately no GPU depth readback: that would synchronise the render queue for four points.
 */
public final class RtRainImpacts {
    static final int SPLASH_LIFETIME_TICKS = 12;
    private static final int MAX_ACTIVE_SPLASHES = 256;
    private static final int SEARCH_RADIUS_CELLS = 48; // 24-block camera footprint
    private static final int SEARCH_DIAMETER_CELLS = SEARCH_RADIUS_CELLS * 2 + 1;
    private static final double EVENT_HIT_TOLERANCE = 0.20;
    private static final ArrayList<Splash> SPLASHES = new ArrayList<>();
    private static final HashMap<RtRainImpactEvents.EventKey, Long> CLAIMED_EVENTS = new HashMap<>();
    private static ClientLevel splashLevel;
    private static long splashClock;
    private static int scanCursor;

    /** Immutable landing event; animation is derived from the monotonic client-tick clock. */
    public record Splash(double x, double y, double z, float nx, float ny, float nz,
                         long bornTick, int seed) {
        float age(float partialTick) {
            return splashClock - bornTick + Math.clamp(partialTick, 0f, 1f);
        }
    }

    private RtRainImpacts() {
    }

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        Entity camera = mc.getCameraEntity();
        splashClock++;
        if (level != splashLevel) {
            SPLASHES.clear();
            CLAIMED_EVENTS.clear();
            splashLevel = level;
            scanCursor = 0;
        }
        SPLASHES.removeIf(splash -> splashClock - splash.bornTick() >= SPLASH_LIFETIME_TICKS);
        CLAIMED_EVENTS.entrySet().removeIf(entry -> entry.getValue() <= splashClock);
        if (!FluoriteConfig.Rt.Weather.RAIN_PARTICLES_ENABLED.value()) {
            SPLASHES.clear();
            CLAIMED_EVENTS.clear();
            return;
        }
        if (level == null || camera == null) {
            return;
        }
        float rain = Math.clamp(level.getRainLevel(1f), 0f, 1f);
        int target = Math.clamp(FluoriteConfig.Rt.Weather.RAIN_SPLASH_TARGET.value(), 0,
                MAX_ACTIVE_SPLASHES);
        while (SPLASHES.size() > target) {
            SPLASHES.removeFirst();
        }
        if (target == 0 || rain <= 1.0e-5f) {
            return;
        }
        int missing = target - SPLASHES.size();
        // Events are selected only in their first 20%, leaving roughly ten ticks of geometry lifetime.
        // Replacing target/10 each tick therefore converges on the requested live pool without bursts.
        int spawnQuota = Math.min(missing, Math.max(1, (target + 9) / 10));
        if (spawnQuota <= 0) {
            return;
        }

        double heading = Math.toRadians(FluoriteConfig.Rt.Composite.WIND_ANGLE.value());
        double slant = Math.toRadians(FluoriteConfig.Rt.Weather.RAIN_SLANT_DEGREES.value());
        double horizontal = Math.sin(slant);
        Vec3 direction = new Vec3(Math.cos(heading) * horizontal, -Math.cos(slant),
                Math.sin(heading) * horizontal);
        Vec3 eye = camera.position();
        int topY = level.getMinY() + level.getHeight() + 1;
        int bottomY = level.getMinY() - 1;
        double fullRayLength = (bottomY - topY) / direction.y;
        double animationTime = RtRainImpactEvents.animationTimeSeconds();
        int cameraCellX = RtRainImpactEvents.cell(eye.x);
        int cameraCellZ = RtRainImpactEvents.cell(eye.z);
        int spawned = 0;
        int rays = 0;
        int maxCandidates = spawnQuota * 16;
        for (int attempt = 0; attempt < maxCandidates && spawned < spawnQuota; attempt++) {
            int sequence = scanCursor++;
            int hashX = RtRainImpactEvents.impactMix(sequence ^ (int) splashClock * 0x9E3779B9);
            int hashZ = RtRainImpactEvents.impactMix(sequence ^ (int) splashClock * 0x85EBCA6B
                    ^ 0x27D4EB2F);
            int cellX = cameraCellX + Math.floorMod(hashX, SEARCH_DIAMETER_CELLS)
                    - SEARCH_RADIUS_CELLS;
            int cellZ = cameraCellZ + Math.floorMod(hashZ, SEARCH_DIAMETER_CELLS)
                    - SEARCH_RADIUS_CELLS;
            RtRainImpactEvents.Event event = RtRainImpactEvents.sample(
                    cellX, cellZ, animationTime, rain);
            if (event == null || event.phase() > RtRainImpactEvents.EARLY_SPLASH_PHASE
                    || CLAIMED_EVENTS.containsKey(event.key())) {
                continue;
            }

            double targetX = event.worldX();
            double targetZ = event.worldZ();
            int estimatedSurfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING,
                    (int) Math.floor(targetX), (int) Math.floor(targetZ));
            double toSurfacePlane = (estimatedSurfaceY - topY) / direction.y;
            Vec3 start = new Vec3(targetX - direction.x * toSurfacePlane, topY,
                    targetZ - direction.z * toSurfacePlane);
            Vec3 end = start.add(direction.scale(fullRayLength));
            HitResult result = level.clip(new ClipContext(start, end,
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, camera));
            rays++;
            if (!(result instanceof BlockHitResult hit) || result.getType() != HitResult.Type.BLOCK
                    || level.getPrecipitationAt(hit.getBlockPos()) != Biome.Precipitation.RAIN) {
                continue;
            }
            Vec3 p = hit.getLocation();
            Vec3 normal = Vec3.atLowerCornerOf(hit.getDirection().getUnitVec3i());
            double dx = p.x - targetX;
            double dz = p.z - targetZ;
            if (normal.y <= 0.5 || dx * dx + dz * dz > EVENT_HIT_TOLERANCE * EVENT_HIT_TOLERANCE) {
                continue;
            }
            long phaseAge = Math.min(SPLASH_LIFETIME_TICKS - 1,
                    (long) Math.floor(event.phase() * SPLASH_LIFETIME_TICKS));
            SPLASHES.add(new Splash(
                    targetX, p.y + normal.y * 0.02, targetZ,
                    (float) normal.x, (float) normal.y, (float) normal.z,
                    splashClock - phaseAge, event.seed()));
            CLAIMED_EVENTS.put(event.key(), splashClock + 40);
            spawned++;
            RtFrameStats.FRAME.count("rainImpactsSpawned", 1);
        }
        RtFrameStats.FRAME.count("rainImpactRays", rays);
    }

    static List<Splash> activeSplashes(ClientLevel level) {
        // Tick and RT capture both run on the client/render thread; expose a read-only-by-contract view
        // without allocating one List.copyOf per rendered frame.
        return level == splashLevel ? SPLASHES : List.of();
    }
}
