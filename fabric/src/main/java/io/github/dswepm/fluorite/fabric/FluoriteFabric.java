package io.github.dswepm.fluorite.fabric;

import io.github.dswepm.fluorite.FluoriteMod;
import io.github.dswepm.fluorite.platform.Platform;

import net.fabricmc.api.ModInitializer;

/**
 * Fabric entrypoint. Installs the platform services, then hands off to the shared init.
 *
 * <p>There is no {@code ClientModInitializer} counterpart any more. The three client events this mod
 * used to register — tick, stopping, render-state invalidation — are mixin injections in
 * {@code FluoriteLifecycle}'s callers now, so both loaders take the same path and there is nothing
 * client-specific left to register.
 */
public final class FluoriteFabric implements ModInitializer {
	@Override
	public void onInitialize() {
		Platform.install(new FabricPlatformServices());
		FluoriteMod.init();
	}
}
