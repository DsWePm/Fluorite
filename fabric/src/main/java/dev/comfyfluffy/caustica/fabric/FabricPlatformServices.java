package dev.comfyfluffy.caustica.fabric;

import dev.comfyfluffy.caustica.platform.PlatformPaths;
import dev.comfyfluffy.caustica.platform.PlatformServices;
import dev.comfyfluffy.caustica.platform.QuadPipeline;

final class FabricPlatformServices implements PlatformServices {
	private final PlatformPaths paths = new FabricPlatformPaths();
	private final QuadPipeline quads = new FabricQuadPipeline();

	@Override
	public PlatformPaths paths() {
		return paths;
	}

	@Override
	public QuadPipeline quads() {
		return quads;
	}

	@Override
	public String loaderName() {
		return "fabric";
	}
}
