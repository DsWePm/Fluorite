package dev.comfyfluffy.caustica.fabric;

import dev.comfyfluffy.caustica.platform.PlatformPaths;
import dev.comfyfluffy.caustica.platform.PlatformServices;

final class FabricPlatformServices implements PlatformServices {
	private final PlatformPaths paths = new FabricPlatformPaths();

	@Override
	public PlatformPaths paths() {
		return paths;
	}

	@Override
	public String loaderName() {
		return "fabric";
	}
}
