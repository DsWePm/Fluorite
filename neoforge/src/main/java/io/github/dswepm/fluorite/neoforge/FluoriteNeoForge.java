package io.github.dswepm.fluorite.neoforge;

import io.github.dswepm.fluorite.FluoriteMod;
import io.github.dswepm.fluorite.platform.Platform;

import net.neoforged.fml.common.Mod;

/**
 * NeoForge entrypoint. Installs the platform services, then hands off to the shared init.
 *
 * <p>Nothing else to register. The three client events the Fabric build used to subscribe to — tick,
 * stopping, render-state invalidation — are mixin injections in the shared code, so both loaders reach
 * {@code FluoriteLifecycle} the same way and this side needs no event wiring at all.
 */
@Mod("fluorite")
public final class FluoriteNeoForge {
	public FluoriteNeoForge() {
		Platform.install(new NeoForgePlatformServices());
		FluoriteMod.init();
	}
}
