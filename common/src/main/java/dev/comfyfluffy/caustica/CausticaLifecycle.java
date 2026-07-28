package dev.comfyfluffy.caustica;

import dev.comfyfluffy.caustica.client.WorldRenderScaler;
import dev.comfyfluffy.caustica.client.VanillaRenderController;
import dev.comfyfluffy.caustica.rt.RtComposite;
import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.RtDeviceBringup;
import dev.comfyfluffy.caustica.rt.RtFrameStats;
import dev.comfyfluffy.caustica.rt.RtFramePresenter;
import dev.comfyfluffy.caustica.rt.RtReflex;
import dev.comfyfluffy.caustica.rt.RtUiOverlay;
import dev.comfyfluffy.caustica.rt.entity.RtEntities;
import dev.comfyfluffy.caustica.rt.entity.RtEntityTextures;
import dev.comfyfluffy.caustica.rt.material.RtBlockMaterials;
import dev.comfyfluffy.caustica.rt.pipeline.RtDlssFg;
import dev.comfyfluffy.caustica.rt.terrain.RtTerrain;
import dev.comfyfluffy.caustica.rt.terrain.RtWorkerPool;
import dev.comfyfluffy.caustica.ngx.NgxRuntime;

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
public final class CausticaLifecycle {
	private static boolean rtInitDone = false;

	private CausticaLifecycle() {
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
	}

	public static void shutdown() {
		WorldRenderScaler.INSTANCE.destroy();
		RtUiOverlay.destroy(); // GUI redirect is not gated by rtInitDone; always release its TextureTarget
		if (!rtInitDone) {
			RtWorkerPool.INSTANCE.shutdown();
			return;
		}

		RtContext ctx = RtContext.currentOrNull();
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
