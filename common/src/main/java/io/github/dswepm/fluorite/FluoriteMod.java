package io.github.dswepm.fluorite;

import io.github.dswepm.fluorite.platform.Platform;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared mod entry. Each loader module installs its {@link Platform} services and then calls
 * {@link #init()} from its own entrypoint; there is no loader interface on this class.
 *
 * <p>Nothing else is registered here. The client lifecycle lives in {@link FluoriteLifecycle} and is
 * driven from mixins rather than loader events.
 */
public final class FluoriteMod {
	public static final String MOD_ID = "fluorite";
	public static final Logger LOGGER = LoggerFactory.getLogger("Fluorite");

	private FluoriteMod() {
	}

	/**
	 * Must run after {@code Platform.install(...)}: registering the settings resolves the config path,
	 * which comes from the platform.
	 */
	public static void init() {
		// Register every setting (applying TOML file values) and write a default config on first run.
		FluoriteConfig.ensureRegistered();
		FluoriteConfig.saveIfMissing();
		LOGGER.info("Fluorite initialized on {}; config: {}",
				Platform.get().loaderName(), FluoriteConfig.configPath());
	}
}
