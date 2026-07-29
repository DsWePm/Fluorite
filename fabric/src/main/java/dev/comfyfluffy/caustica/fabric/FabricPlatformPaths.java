package dev.comfyfluffy.caustica.fabric;

import dev.comfyfluffy.caustica.platform.PlatformPaths;

import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;

final class FabricPlatformPaths implements PlatformPaths {
	@Override
	public Path configDir() {
		return FabricLoader.getInstance().getConfigDir();
	}

	@Override
	public Path gameDir() {
		return FabricLoader.getInstance().getGameDir();
	}
}
