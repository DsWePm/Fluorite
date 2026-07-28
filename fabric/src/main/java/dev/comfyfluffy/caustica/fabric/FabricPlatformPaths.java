package dev.comfyfluffy.caustica.fabric;

import dev.comfyfluffy.caustica.platform.PlatformPaths;

import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

final class FabricPlatformPaths implements PlatformPaths {
	@Override
	public Path configDir() {
		return FabricLoader.getInstance().getConfigDir();
	}

	@Override
	public Path gameDir() {
		return FabricLoader.getInstance().getGameDir();
	}

	/**
	 * A Fabric mod container can be backed by several root paths (a jar, or several source sets in a dev
	 * run), so this walks them and returns the first that actually contains the entry. The narrower
	 * NeoForge-shaped contract is what the shared code sees.
	 */
	@Override
	public Optional<Path> findModResource(String path) {
		return FabricLoader.getInstance().getModContainer("caustica")
				.flatMap(container -> container.getRootPaths().stream()
						.map(root -> root.resolve(path))
						.filter(Files::isDirectory)
						.findFirst());
	}
}
