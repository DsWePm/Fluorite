package io.github.dswepm.fluorite.rt;

import java.util.Arrays;
import java.util.Objects;

/** CPU history transaction; publication occurs only after the corresponding pass B was recorded. */
final class RtPathHistoryState {
    static final class Context {
        final Object level;
        final int width,height,x,y,z;
        final Object[] parameters;
        Context(Object level,int width,int height,int x,int y,int z,Object... parameters) {
            this.level=Objects.requireNonNull(level);
            if(width<=0||height<=0)throw new IllegalArgumentException("Empty render size");
            this.width=width;this.height=height;this.x=x;this.y=y;this.z=z;
            this.parameters=parameters.clone();
        }
        boolean sameDomain(Context other) {
            return other!=null&&level==other.level&&width==other.width&&height==other.height
                    &&x==other.x&&y==other.y&&z==other.z&&Arrays.equals(parameters,other.parameters);
        }
    }
    static final class Frame {
        final Context context;
        final long index;
        final Object[] terrain;
        final RtPathSurfaceIdentity[] entities;
        final int[] terrainWords,entityWords;
        final boolean previousValid;
        Frame(Context context,long index,Object[] terrain,RtPathSurfaceIdentity[] entities,Frame previous) {
            this.context=context;this.index=index;this.terrain=terrain.clone();this.entities=entities.clone();
            previousValid=previous!=null&&index==previous.index+1&&context.sameDomain(previous.context);
            terrainWords=new int[RtPathSurfaceIdentity.requiredWords(terrain.length)];
            entityWords=new int[RtPathSurfaceIdentity.requiredWords(entities.length)];
            RtPathSurfaceIdentity.fillTerrainWords(terrainWords,previousValid?previous.terrain:null,this.terrain);
            RtPathSurfaceIdentity.fillEntityWords(entityWords,previousValid?previous.entities:null,this.entities);
        }
    }
    private Frame previous,pending;
    Frame prepare(Context context,long index,Object[] terrain,RtPathSurfaceIdentity[] entities) {
        pending=new Frame(context,index,terrain,entities,previous);
        return pending;
    }
    void publish(Frame frame) {
        if(frame!=pending)throw new IllegalStateException("Not the current history transaction");
        previous=frame;pending=null;
    }
    void abandon(Frame frame) {
        if(frame==pending)pending=null;
    }
    void reset() { previous=null;pending=null; }
}
