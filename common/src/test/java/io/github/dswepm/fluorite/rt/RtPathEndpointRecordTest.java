package io.github.dswepm.fluorite.rt;

import io.github.dswepm.fluorite.rt.gen.PackedPathReservoirData;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.SplittableRandom;
import static org.junit.jupiter.api.Assertions.*;

final class RtPathEndpointRecordTest {
    @Test void canonicalSurfaceIdentityAndFloat32BarycentricsSurviveTheRealSerializer() {
        var random = new SplittableRandom(26);
        for (int i = 0; i < 10_000; i++) {
            int object = random.nextInt(), geometry = random.nextInt(), primitive = random.nextInt();
            float x = random.nextFloat(), y = random.nextFloat() * (1 - x);
            ByteBuffer bytes = ByteBuffer.allocate(PackedPathReservoirData.BYTE_SIZE).order(ByteOrder.LITTLE_ENDIAN);
            new PackedPathReservoirData(new PackedPathReservoirData.Float3(1,2,3), 0,
                    new PackedPathReservoirData.Float3(4,5,6), 0,0,0,0,1,1,1,0,1,
                    0,0,0,0,0,0,0,0,object,primitive,new PackedPathReservoirData.Float2(x,y),
                    geometry,Float.floatToRawIntBits(x),Float.floatToRawIntBits(y),Float.floatToRawIntBits(-x)).write(bytes);
            assertEquals(object, bytes.getInt(96));
            assertEquals(primitive, bytes.getInt(100));
            assertEquals(Float.floatToRawIntBits(x), bytes.getInt(104));
            assertEquals(Float.floatToRawIntBits(y), bytes.getInt(108));
            assertEquals(geometry, bytes.getInt(112));
            assertEquals(Float.floatToRawIntBits(x), bytes.getInt(116));
            assertEquals(Float.floatToRawIntBits(y), bytes.getInt(120));
            assertEquals(Float.floatToRawIntBits(-x), bytes.getInt(124));
        }
    }

    @Test void alphaAndClosestHitUseTheSameCanonicalBarycentricSelectionAndOneMaterialBody() throws Exception {
        String closest = source("shaders/world/world.rchit.slang");
        String alpha = source("shaders/world/world.rahit.slang");
        for (String s : new String[]{closest,alpha}) {
            String wrapper = s.substring(s.lastIndexOf("void main(inout PathHitPayload hit,"));
            assertTrue(wrapper.contains("pathHitShadingBarycentrics(hit.geometry, attr.barycentrics,"));
            assertTrue(wrapper.contains("InstanceID(), GeometryIndex(), PrimitiveIndex()"));
            assertTrue(wrapper.contains("surface, shadingAttributes"));
        }
        String wrapper = closest.substring(closest.lastIndexOf("void main(inout PathHitPayload hit,"));
        assertTrue(wrapper.indexOf("!pathEndpointRequestMatches(") < wrapper.indexOf("shadePathHit("));
        assertTrue(wrapper.indexOf("hit.surface.hitT = -1.0;") < wrapper.indexOf("shadePathHit("));
        assertEquals(2, closest.split("geometry.barycentrics = attribs;",-1).length-1);
    }

    @Test void theRecordProducerAndConnectionCarryTheWholeKeyWithoutAnotherGeometryQuery() throws Exception {
        String tree = source("shaders/world/path_reuse_tree.slang");
        for (String field : new String[]{"objectId","geometryIndex","primitiveIndex","barycentrics"})
            assertTrue(tree.contains("= endpoint."+field+";"));
        assertTrue(source("shaders/world/path_reuse_event.slang").contains("reuseRecordEndpoint(record, recon, edgeDirection)"));
        assertTrue(source("shaders/world/path_reuse_trace.slang").contains("reuseRecordEndpoint(dRecord, hitGeometry, footprintEdgeDirection)"));
        String connection = source("shaders/world/path_reuse_connection.slang");
        for (String term : new String[]{"record.reconObjectId", "record.reconGeometryIndex",
                "record.reconPrimitiveIndex", "record.reconBarycentrics"}) assertTrue(connection.contains(term));
        String dispatch = source("shaders/world/trace_path.slang");
        String endpoint = dispatch.substring(dispatch.indexOf("public void tracePathEndpointRadiance("));
        assertEquals(1, endpoint.split("TraceRay\\(",-1).length-1);
        assertFalse(endpoint.contains("RayQuery") || endpoint.contains("RAY_FLAG_FORCE_NON_OPAQUE"));
        assertTrue(dispatch.contains("makePathEndpointRequest(objectId, geometryIndex, primitiveIndex, barycentrics)"));
    }

    private static String source(String relative) throws Exception {
        Path root=Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while(root!=null && !Files.isRegularFile(root.resolve("settings.gradle"))) root=root.getParent();
        assertNotNull(root);
        return Files.readString(root.resolve(relative)).replace("\r\n","\n");
    }
}
