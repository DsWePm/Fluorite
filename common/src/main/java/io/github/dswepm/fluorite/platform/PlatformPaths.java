package io.github.dswepm.fluorite.platform;

import java.nio.file.Path;

/**
 * The filesystem locations the mod needs from its loader. This is the whole of it — three methods,
 * covering every place the shared code used to reach for {@code FabricLoader}.
 */
public interface PlatformPaths {
	/** Where {@code fluorite.toml} lives. */
	Path configDir();

	/**
	 * The game directory. Roots the extracted NGX natives and the frame-stats CSV output.
	 *
	 * <p>The only other thing this interface ever needed — listing a directory inside the mod container —
	 * is gone. Both loaders make that awkward and they make it awkward differently, so the build now
	 * records the file list beside the files and the runtime reads it off the classpath instead.
	 */
	Path gameDir();
}
