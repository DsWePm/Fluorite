package io.github.dswepm.fluorite.neoforge;

import io.github.dswepm.fluorite.platform.PlatformPaths;
import io.github.dswepm.fluorite.platform.PlatformServices;
import io.github.dswepm.fluorite.platform.QuadPipeline;

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
