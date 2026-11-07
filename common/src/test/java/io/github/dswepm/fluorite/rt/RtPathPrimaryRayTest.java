package io.github.dswepm.fluorite.rt;

import io.github.dswepm.fluorite.rt.gen.PackedPathReservoirData;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.SplittableRandom;
import static org.junit.jupiter.api.Assertions.*;

final class RtPathPrimaryRayTest {
    @Test void theRealSerializerRetainsOriginalPrimaryQueueBitsWithin128Bytes() {
        var random = new SplittableRandom(31);
        for (int i=0; i<10_000; i++) {
            float x=i==0 ? -0.0f : (float)random.nextDouble(-4096,4096);
            float y=(float)random.nextDouble(-4096,4096), z=Float.intBitsToFloat(i);
            int direction=random.nextInt(), cone=random.nextInt(), object=random.nextInt();
            var bytes=ByteBuffer.allocate(PackedPathReservoirData.BYTE_SIZE).order(ByteOrder.LITTLE_ENDIAN);
            new PackedPathReservoirData(new PackedPathReservoirData.Float3(0,0,0),0,
                    new PackedPathReservoirData.Float3(x,y,z),direction,0,0,0,1,1,1,0,1,
                    object,cone,0,0,0,0,0,0,0,0,new PackedPathReservoirData.Float2(0,0),
                    0,0,0,0).write(bytes);
            assertEquals(128,bytes.capacity());
            assertEquals(Float.floatToRawIntBits(x),bytes.getInt(16));
            assertEquals(Float.floatToRawIntBits(y),bytes.getInt(20));
            assertEquals(Float.floatToRawIntBits(z),bytes.getInt(24));
            assertEquals(direction,bytes.getInt(28));
            assertEquals(object,bytes.getInt(64));
            assertEquals(cone,bytes.getInt(68));
        }
    }

    @Test void theProducerCopiesRawQueueWordsBeforeFinalisingTheWholeTree() throws Exception {
        String caller=source("shaders/world/world.rgen.slang");
        assertTrue(caller.contains("tracePathReuse(segment, unpackActiveMediumFlags(packed.pathFlags), packed.rd, packed.rayCone,"));
        String tracer=source("shaders/world/path_reuse_trace.slang");
        String tail=tracer.substring(tracer.indexOf("// Record-only: no temporal/spatial reads"));
        assertTrue(tail.indexOf("reuseStorePrimaryRay(selectedRecord, seg.ro, primaryPackedDirection, primaryRayCone)")
                < tail.indexOf("reuseFinishTree(selectedRecord, dTree)"));
        String primary=source("shaders/world/path_reuse_primary.slang");
        for (String term:new String[]{"record.primaryOrigin = origin;",
                "record.primaryPackedDirection = packedDirection;","record.primaryRayCone = packedCone;"})
            assertTrue(primary.contains(term));
        assertFalse(java.util.regex.Pattern.compile("\\b(?:octEncode|packHalf2)\\s*\\(").matcher(primary).find());
    }

    @Test void sourceChecksActuallyTraceTheRecordedPrimaryAndRequireBitwiseIdentity() throws Exception {
        for(String file:new String[]{"path_reuse_trace.slang","path_reuse_connection.slang"}) {
            String s=source("shaders/world/"+file);
            assertTrue(s.contains("reuseRestorePrimaryRay(seg, record)"));
            assertTrue(s.contains("reusePrimaryRayMatchesSegment(recordedSegment, seg)"));
        }
        String trace=source("shaders/world/path_reuse_trace.slang");
        assertTrue(trace.contains("reuseReplayPrefix(recordedSegment, entryMediumFlags, record.pathSeed, depth"));
        String primary=source("shaders/world/path_reuse_primary.slang");
        for(String term:new String[]{"all(asuint(restored.ro) == asuint(actual.ro))",
                "all(asuint(restored.rd) == asuint(actual.rd))",
                "asuint(restored.rayConeWidth) == asuint(actual.rayConeWidth)",
                "asuint(restored.rayConeSpread) == asuint(actual.rayConeSpread)"}) assertTrue(primary.contains(term));
    }

    @Test void initialAndRestoredPrimaryUseOneControlledDecoderOnlyInTheReuseVariant() throws Exception {
        String caller=source("shaders/world/world.rgen.slang");
        assertTrue(caller.contains("#ifdef FLUORITE_PATH_REUSE\n        segment.rd = reuseDecodePrimaryDirection(packed.rd);\n#endif"));
        assertTrue(source("shaders/world/path_reuse_primary.slang").contains(
                "restored.rd = reuseDecodePrimaryDirection(record.primaryPackedDirection);"));
        String direction=source("shaders/world/path_reuse_direction.slang");
        int start=direction.indexOf("public float3 reuseDecodePrimaryDirection("),end=direction.indexOf("public float3 reuseReflect(",start);
        String decoder=direction.substring(start,end);
        assertTrue(decoder.contains("return replayNormalize(n);"));
        assertFalse(decoder.contains("rndf(") || decoder.contains("pcg(") || decoder.contains("normalize("));
    }

    private static String source(String relative) throws Exception {
        Path root=Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while(root!=null && !Files.isRegularFile(root.resolve("settings.gradle"))) root=root.getParent();
        assertNotNull(root);
        return Files.readString(root.resolve(relative)).replace("\r\n","\n");
    }
}
