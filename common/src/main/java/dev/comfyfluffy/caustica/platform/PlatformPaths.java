package dev.comfyfluffy.caustica.platform;

import java.nio.file.Path;
import java.util.Optional;

/**
 * The filesystem locations the mod needs from its loader. This is the whole of it — three methods,
 * covering every place the shared code used to reach for {@code FabricLoader}.
 */
public interface PlatformPaths {
	/** Where {@code caustica.toml} lives. */
	Path configDir();

	/** The game directory. Roots the extracted NGX natives and the frame-stats CSV output. */
	Path gameDir();

	/**
	 * Resolve a directory inside this mod's own container, e.g. {@code "caustica/natives/windows-x64"},
	 * or empty when the mod file has no such entry.
	 *
	 * <p>Shaped after NeoForge's {@code IModFile.findResource(String)} rather than Fabric's
	 * {@code ModContainer.getRootPaths()}: the Fabric side can iterate its roots and return the first
	 * match, but the NeoForge side has no roots to iterate, so the narrower contract is the portable one.
	 * Callers only ever list the directory, which both loaders support once they have the path.
	 */
	Optional<Path> findModResource(String path);
}
