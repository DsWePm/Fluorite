package dev.comfyfluffy.caustica.neoforge;

import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.platform.Platform;

import net.neoforged.fml.common.Mod;

/**
 * NeoForge entrypoint. Installs the platform services, then hands off to the shared init.
 *
 * <p>Nothing else to register. The three client events the Fabric build used to subscribe to — tick,
 * stopping, render-state invalidation — are mixin injections in the shared code, so both loaders reach
 * {@code CausticaLifecycle} the same way and this side needs no event wiring at all.
 */
@Mod("caustica")
public final class CausticaNeoForge {
	public CausticaNeoForge() {
		Platform.install(new NeoForgePlatformServices());
		CausticaMod.init();
	}
}
