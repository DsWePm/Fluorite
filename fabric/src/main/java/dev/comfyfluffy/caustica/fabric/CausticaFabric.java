package dev.comfyfluffy.caustica.fabric;

import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.platform.Platform;

import net.fabricmc.api.ModInitializer;

/**
 * Fabric entrypoint. Installs the platform services, then hands off to the shared init.
 *
 * <p>There is no {@code ClientModInitializer} counterpart any more. The three client events this mod
 * used to register — tick, stopping, render-state invalidation — are mixin injections in
 * {@code CausticaLifecycle}'s callers now, so both loaders take the same path and there is nothing
 * client-specific left to register.
 */
public final class CausticaFabric implements ModInitializer {
	@Override
	public void onInitialize() {
		Platform.install(new FabricPlatformServices());
		CausticaMod.init();
	}
}
