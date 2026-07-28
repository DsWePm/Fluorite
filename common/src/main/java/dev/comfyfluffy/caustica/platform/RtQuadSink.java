package dev.comfyfluffy.caustica.platform;

/**
 * Receives quads from a {@link BlockQuadSource}. The view is reused between calls — read it, do not keep
 * it.
 */
@FunctionalInterface
public interface RtQuadSink {
	void quad(RtQuadView quad);
}
