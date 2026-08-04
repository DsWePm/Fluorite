package io.github.dswepm.fluorite.fabric;

import io.github.dswepm.fluorite.platform.PlatformPaths;
import io.github.dswepm.fluorite.platform.PlatformServices;
import io.github.dswepm.fluorite.platform.QuadPipeline;

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
