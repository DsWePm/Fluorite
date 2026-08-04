package io.github.dswepm.fluorite;

import io.github.dswepm.fluorite.client.WorldRenderScaler;
import io.github.dswepm.fluorite.client.VanillaRenderController;
import io.github.dswepm.fluorite.rt.RtComposite;
import io.github.dswepm.fluorite.rt.RtContext;
import io.github.dswepm.fluorite.rt.RtDeviceBringup;
import io.github.dswepm.fluorite.rt.RtFrameStats;
import io.github.dswepm.fluorite.rt.RtFramePresenter;
import io.github.dswepm.fluorite.rt.RtReflex;
import io.github.dswepm.fluorite.rt.RtUiOverlay;
import io.github.dswepm.fluorite.rt.entity.RtEntities;
import io.github.dswepm.fluorite.rt.entity.RtEntityTextures;
import io.github.dswepm.fluorite.rt.material.RtBlockMaterials;
import io.github.dswepm.fluorite.rt.pipeline.RtDlssFg;
import io.github.dswepm.fluorite.rt.pipeline.RtDlssRr;
import io.github.dswepm.fluorite.rt.terrain.RtTerrain;
import io.github.dswepm.fluorite.rt.terrain.RtTerrainDigest;
import io.github.dswepm.fluorite.rt.terrain.RtWorkerPool;
import io.github.dswepm.fluorite.ngx.NgxRuntime;

/**
 * The renderer's client lifecycle, driven from mixins rather than from loader events.
 *
 * <p>This used to be three Fabric event registrations. All three are served by mixins the mod already
 * owns, so nothing here needs a platform abstraction:
 *
 * <ul>
 *   <li>{@link #onClientTick()} — {@code MinecraftMixin} at {@code Minecraft.tick()} HEAD, which is the
 *       same point Fabric's {@code START_CLIENT_TICK} fires from.</li>
 *   <li>{@link #shutdown()} — {@code MinecraftMixin} at {@code Minecraft.close()} HEAD, where Fabric's
 *       {@code CLIENT_STOPPING} also fires.</li>
 *   <li>{@link #onRenderStateInvalidated()} — {@code LevelExtractorMixin} at {@code allChanged()} TAIL,
 *       the method behind Fabric's {@code InvalidateRenderStateCallback}. NeoForge has no equivalent
 *       event at all, which is the other reason this is a mixin now.</li>
 * </ul>
 */
public final class FluoriteLifecycle {
	private static boolean rtInitDone = false;

	private FluoriteLifecycle() {
	}

	/**
	 * Per game tick. The GpuDevice exists well before the first tick, so a one-shot here runs on the
	 * render thread with the device idle between frames.
	 */
	public static void onClientTick() {
		if (!VanillaRenderController.rtRuntimeWorkRequested()) {
			if (rtInitDone) {
				shutdown();
			}
			return;
		}

		// Bring the RT device/context up once; terrain residency + the composite follow below.
		if (!rtInitDone && RtDeviceBringup.rtRequested()) {
			RtContext ctx = RtContext.get();
			if (ctx != null) {
				rtInitDone = true;
			}
		}

		// Once RT is up, keep section residency synced to vanilla's loaded chunks around the player —
		// builds newly-in-range sections, frees out-of-range ones, per tick.
		if (rtInitDone) {
			RtContext ctx = RtContext.currentOrNull();
			if (ctx != null) {
				RtFrameStats.FRAME.beginIfInactive();
				// Bring the world pipeline + LabPBR atlases up before terrain tessellates, so per-prim
				// material flags resolve from the first section (PBR on join, no re-extract). No-op until
				// we're in a world with the block atlas loaded, or once already created.
				RtComposite.INSTANCE.ensureResourcesReady(ctx);
				RtTerrain.update(ctx);
				RtTerrainDigest.tick();
				// Log DLSS-FG availability once when frame generation is enabled (capability query only).
				if (RtDlssFg.enabled()) {
					RtDlssFg.INSTANCE.probeAvailabilityOnce();
				}
			}
		}
	}

	/**
	 * Vanilla's full render-state invalidation ({@code LevelExtractor.allChanged()}: dimension change via
	 * setLevel, render-distance change, F3+A) — drop RT terrain residency so it rebuilds for the new
	 * world. Fixes stale geometry persisting across an End→Overworld switch (coords alone aren't
	 * world-unique). Resource reloads do NOT reach here; that path is handled separately.
	 */
	public static void onRenderStateInvalidated() {
		RtTerrain.requestFullClear();
		RtComposite.INSTANCE.resetFailureLatch(); // F3+A doubles as manual RT recovery after a latched failure
		RtDlssRr.INSTANCE.requestHistoryReset();
		// Residency is about to be rebuilt from scratch; keeping the old hashes would mix two worlds.
		RtTerrainDigest.reset();
	}

	public static void shutdown() {
		RtTerrainDigest.dumpIfDirty();

		// Make the device idle before anything is destroyed.
		//
		// Four teardown paths document that they may free immediately because the device is already
		// idle by the time they run, and RtComposite.destroy names the mechanism: "CLIENT_STOPPING
		// waits". This method IS the CLIENT_STOPPING handler, and it did not wait. Nothing did. Every
		// destroy below ran against a device that could still have the last frame in flight, freeing
		// images and buffers the GPU was reading.
		//
		// The symptom was an intermittent access violation inside vkDestroyDevice, in the driver,
		// after the final frame — crashing on roughly half of exits with a byte-identical stack each
		// time. Identical stack with intermittent occurrence is the shape of a race, not of a wrong
		// destroy order: how far the GPU happened to get decides whether the driver's bookkeeping
		// survives to the device destroy that trips over it.
		//
		// Placed above every destroy, including the two that are not gated on rtInitDone.
		RtContext ctx = RtContext.currentOrNull();
		if (ctx != null) {
			ctx.waitIdle();
		}

		WorldRenderScaler.INSTANCE.destroy();
		RtUiOverlay.destroy(); // GUI redirect is not gated by rtInitDone; always release its TextureTarget
		if (!rtInitDone) {
			RtWorkerPool.INSTANCE.shutdown();
			return;
		}

		if (ctx != null) {
			// Let the terrain epoch cancel queued work and release every active-task token before
			// shutdownNow(): discarded worker Runnables cannot deliver their terminal callbacks.
			RtTerrain.shutdown(ctx);
		}
		RtWorkerPool.INSTANCE.shutdown();
		if (ctx != null) {
			RtEntities.INSTANCE.shutdown();
		}
		RtComposite.INSTANCE.destroy();
		RtEntityTextures.INSTANCE.reset();
		RtBlockMaterials.INSTANCE.destroy();
		RtDlssFg.INSTANCE.destroy();
		if (ctx != null) {
			RtFramePresenter.INSTANCE.destroy(ctx.device());
			RtReflex.INSTANCE.destroy(ctx.device().vkDevice());
		}
		// Shut NGX down once, after every feature (RR + FG) has been released above.
		NgxRuntime.INSTANCE.shutdown();
		if (ctx != null) {
			ctx.destroy();
		}
		rtInitDone = false;
	}
}
