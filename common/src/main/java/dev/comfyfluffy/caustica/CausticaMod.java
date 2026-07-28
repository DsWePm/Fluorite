package dev.comfyfluffy.caustica;

import dev.comfyfluffy.caustica.platform.Platform;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared mod entry. Each loader module installs its {@link Platform} services and then calls
 * {@link #init()} from its own entrypoint; there is no loader interface on this class.
 *
 * <p>Nothing else is registered here. The client lifecycle lives in {@link CausticaLifecycle} and is
 * driven from mixins rather than loader events.
 */
public final class CausticaMod {
	public static final String MOD_ID = "caustica";
	public static final Logger LOGGER = LoggerFactory.getLogger("Caustica");

	private CausticaMod() {
	}

	/**
	 * Must run after {@code Platform.install(...)}: registering the settings resolves the config path,
	 * which comes from the platform.
	 */
	public static void init() {
		// Register every setting (applying TOML file values) and write a default config on first run.
		CausticaConfig.ensureRegistered();
		CausticaConfig.saveIfMissing();
		LOGGER.info("Caustica initialized on {}; config: {}",
				Platform.get().loaderName(), CausticaConfig.configPath());
	}
}
