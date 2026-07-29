package dev.comfyfluffy.caustica.neoforge;

import dev.comfyfluffy.caustica.platform.PlatformPaths;
import dev.comfyfluffy.caustica.platform.PlatformServices;
import dev.comfyfluffy.caustica.platform.QuadPipeline;

final class NeoForgePlatformServices implements PlatformServices {
	private final PlatformPaths paths = new NeoForgePlatformPaths();
	private final QuadPipeline quads = new NeoForgeQuadPipeline();

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
		return "neoforge";
	}
}
