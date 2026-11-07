package io.github.dswepm.fluorite.rt;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class RtPathHistoryStateTest {
    private final Object level = new Object();
    private RtPathHistoryState.Context context(Object... settings) {
        return new RtPathHistoryState.Context(level, 1920, 1080, 0, 64, 0, settings);
    }
    private static float[] transform() { return new float[]{1,0,0,0,0,1,0,0,0,0,1,0}; }
    @Test void exactOwnerAndPlacementRejectReplacementReorderingAndMotion() {
        Object a=new Object(), b=new Object();
        int[] words={-1,-1};
        RtPathSurfaceIdentity.fillTerrainWords(words,new Object[]{a,b,null},new Object[]{a,new Object(),null});
        assertArrayEquals(new int[]{1,0},words);
        RtPathSurfaceIdentity.fillTerrainWords(words,new Object[]{a,b},new Object[]{b,a});
        assertArrayEquals(new int[]{0,0},words);
        float[] t=transform();
        var id=RtPathSurfaceIdentity.of(a,t,255);
        assertTrue(id.sameSurface(RtPathSurfaceIdentity.of(a,t,255)));
        assertFalse(id.sameSurface(RtPathSurfaceIdentity.of(b,t,255)));
        assertFalse(id.sameSurface(RtPathSurfaceIdentity.of(a,t,127)));
        t[3]=1; assertFalse(id.sameSurface(RtPathSurfaceIdentity.of(a,t,255)));
        t=transform(); t[1]=-0f; assertFalse(id.sameSurface(RtPathSurfaceIdentity.of(a,t,255)));
        t[1]=Float.NaN; assertNull(RtPathSurfaceIdentity.of(a,t,255));
        assertNull(RtPathSurfaceIdentity.of(a,new float[11],255));
        assertNull(RtPathSurfaceIdentity.of(null,transform(),255));
        RtPathSurfaceIdentity.fillEntityWords(words,new RtPathSurfaceIdentity[]{id},new RtPathSurfaceIdentity[]{id,null});
        assertArrayEquals(new int[]{1,0},words);
    }
    @Test void masksHandleWordBoundaryAndClearStaleUnusedBits() {
        Object[] old=new Object[33], now=new Object[33];
        old[31]=now[31]=new Object(); old[32]=now[32]=new Object();
        int[] words={-1,-1,-1}; RtPathSurfaceIdentity.fillTerrainWords(words,old,now);
        assertArrayEquals(new int[]{0x80000000,1,0},words);
        assertEquals(0,RtPathSurfaceIdentity.requiredWords(0));
        assertThrows(IllegalArgumentException.class,()->RtPathSurfaceIdentity.requiredWords(-1));
        assertThrows(IllegalArgumentException.class,()->RtPathSurfaceIdentity.fillTerrainWords(new int[1],old,now));
    }
    @Test void onlyPublishedConsecutiveMatchingFramesCanSupplyHistory() {
        var state=new RtPathHistoryState(); Object owner=new Object(); Object[] surfaces={owner};
        var a=state.prepare(context(1),10,surfaces,new RtPathSurfaceIdentity[0]); assertFalse(a.previousValid);
        surfaces[0]=new Object(); state.publish(a);
        var b=state.prepare(context(1),11,new Object[]{owner},new RtPathSurfaceIdentity[0]);
        assertTrue(b.previousValid); assertEquals(1,b.terrainWords[0]);
        state.abandon(b);
        var c=state.prepare(context(1),12,new Object[]{owner},new RtPathSurfaceIdentity[0]); assertFalse(c.previousValid);
        assertThrows(IllegalStateException.class,()->state.publish(b));
        state.publish(c); state.reset();
        assertFalse(state.prepare(context(1),13,new Object[]{owner},new RtPathSurfaceIdentity[0]).previousValid);
    }
    @Test void worldResolutionRebaseMediumAndConfigInvalidateTheWholeDomain() {
        var baseline=context(1,Float.floatToRawIntBits(.3f));
        assertTrue(baseline.sameDomain(context(1,Float.floatToRawIntBits(.3f))));
        assertFalse(baseline.sameDomain(context(2,Float.floatToRawIntBits(.3f))));
        assertFalse(baseline.sameDomain(context(1,Float.floatToRawIntBits(.4f))));
        assertFalse(baseline.sameDomain(new RtPathHistoryState.Context(new Object(),1920,1080,0,64,0,1,Float.floatToRawIntBits(.3f))));
        assertFalse(baseline.sameDomain(new RtPathHistoryState.Context(level,960,540,0,64,0,1,Float.floatToRawIntBits(.3f))));
        assertFalse(baseline.sameDomain(new RtPathHistoryState.Context(level,1920,1080,16,64,0,1,Float.floatToRawIntBits(.3f))));
        Object[] config={1}; var captured=context(config); config[0]=2;
        assertTrue(captured.sameDomain(context(1)));
    }
}
