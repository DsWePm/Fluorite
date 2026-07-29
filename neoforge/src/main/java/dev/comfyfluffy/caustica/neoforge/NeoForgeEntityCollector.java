package dev.comfyfluffy.caustica.neoforge;

import dev.comfyfluffy.caustica.rt.entity.RtEntityCollectorBase;

/**
 * Nothing to add.
 *
 * <p>The shared base carries the vanilla {@code submitBlockModel} overload with a populated parts list,
 * which is the path that actually runs here. Fabric needs a subclass because FRAPI overwrites
 * {@code BlockStateModelWrapper.update} — block-display models then arrive as a mesh through an injected
 * overload with an empty parts list — and because {@code submitCustom} is injected as an abstract method
 * there. Neither applies on this loader, so this class exists only because the base is abstract.
 */
final class NeoForgeEntityCollector extends RtEntityCollectorBase {
}
