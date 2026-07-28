package dev.comfyfluffy.caustica.neoforge;

import net.neoforged.fml.common.Mod;

/**
 * NeoForge entrypoint.
 *
 * <p>M0 skeleton: this module does not yet compile the shared sources under {@code common/}, so there is
 * nothing to initialize here. Its purpose right now is to keep the NeoForge half of the toolchain
 * honest — ModDevGradle resolves, the NeoForge artifact resolves, {@code neoforge.mods.toml} parses, and
 * this class compiles against the real Minecraft jar.
 *
 * <p>At M3 this grows the body that Fabric's two entrypoints carry today: install the platform services
 * ({@code Platform.install(...)}) before anything else touches them, then run the same
 * {@code CausticaConfig.ensureRegistered()} / {@code saveIfMissing()} pair that
 * {@code CausticaMod.onInitialize} does. The per-tick RT driver, the shutdown hook and the
 * render-state-invalidation hook are NOT events on this side — they are {@code @Inject}s on mixins the
 * mod already owns ({@code MinecraftMixin}, {@code LevelExtractorMixin}), so they need no wiring here.
 */
@Mod("caustica")
public final class CausticaNeoForge {
	public CausticaNeoForge() {
	}
}
