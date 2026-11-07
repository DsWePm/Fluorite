package io.github.dswepm.fluorite.rt;

import java.util.Arrays;
import java.util.Objects;

/** Exact, one-frame correspondence of an RT surface table entry; no address or hash identity. */
public final class RtPathSurfaceIdentity {
    private final Object owner;
    private final float[] transform;
    private final int mask;

    private RtPathSurfaceIdentity(Object owner, float[] transform, int mask) {
        this.owner=owner;
        this.transform=transform.clone();
        this.mask=mask;
    }

    public static RtPathSurfaceIdentity of(Object owner, float[] transform, int mask) {
        if(owner==null || transform==null || transform.length!=12) return null;
        for(float value:transform) if(!Float.isFinite(value)) return null;
        return new RtPathSurfaceIdentity(owner,transform,mask);
    }

    boolean sameSurface(RtPathSurfaceIdentity other) {
        if(other==null || owner!=other.owner || mask!=other.mask) return false;
        for(int i=0;i<12;i++)
            if(Float.floatToRawIntBits(transform[i])!=Float.floatToRawIntBits(other.transform[i])) return false;
        return true;
    }

    static int requiredWords(int count) {
        if(count<0) throw new IllegalArgumentException("Negative surface count");
        return (int)((count+31L)/32L);
    }

    static void fillTerrainWords(int[] words,Object[] previous,Object[] current) {
        Objects.requireNonNull(current);
        if(words.length<requiredWords(current.length)) throw new IllegalArgumentException("Terrain mask too small");
        Arrays.fill(words,0);
        if(previous==null) return;
        for(int i=0;i<Math.min(previous.length,current.length);i++)
            if(current[i]!=null && current[i]==previous[i]) words[i>>>5]|=1<<(i&31);
    }

    static void fillEntityWords(int[] words,RtPathSurfaceIdentity[] previous,RtPathSurfaceIdentity[] current) {
        Objects.requireNonNull(current);
        if(words.length<requiredWords(current.length)) throw new IllegalArgumentException("Entity mask too small");
        Arrays.fill(words,0);
        if(previous==null) return;
        for(int i=0;i<Math.min(previous.length,current.length);i++)
            if(current[i]!=null && current[i].sameSurface(previous[i])) words[i>>>5]|=1<<(i&31);
    }
}
