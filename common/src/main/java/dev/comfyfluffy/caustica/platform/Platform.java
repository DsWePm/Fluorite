package dev.comfyfluffy.caustica.platform;

/**
 * Holder for the loader-specific services. Each loader module installs its implementation from its own
 * entrypoint, before anything else touches the shared code.
 *
 * <p>{@link #get()} throws when nothing has been installed. That is deliberate and load-bearing in one
 * place: {@code CausticaConfig.resolveConfigPath()} wraps its call in {@code catch (Throwable)} and falls
 * back to a relative {@code config/caustica.toml}, which is what lets the JUnit suite construct settings
 * without a loader present. Do not soften this into returning null.
 */
public final class Platform {
	private static volatile PlatformServices services;

	private Platform() {
	}

	public static void install(PlatformServices platformServices) {
		if (platformServices == null) {
			throw new IllegalArgumentException("platform services must not be null");
		}
		services = platformServices;
	}

	public static PlatformServices get() {
		PlatformServices current = services;
		if (current == null) {
			throw new IllegalStateException("Caustica platform services were not installed; "
					+ "the loader entrypoint must call Platform.install(...) before anything else runs");
		}
		return current;
	}

	/** Convenience for the common case. */
	public static PlatformPaths paths() {
		return get().paths();
	}
}
