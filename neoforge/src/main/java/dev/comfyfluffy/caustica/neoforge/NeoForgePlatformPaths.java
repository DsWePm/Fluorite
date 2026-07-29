package dev.comfyfluffy.caustica.neoforge;

import dev.comfyfluffy.caustica.platform.PlatformPaths;

import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Path;

final class NeoForgePlatformPaths implements PlatformPaths {
	@Override
	public Path configDir() {
		return FMLPaths.CONFIGDIR.get();
	}

	@Override
	public Path gameDir() {
		return FMLPaths.GAMEDIR.get();
	}
}
