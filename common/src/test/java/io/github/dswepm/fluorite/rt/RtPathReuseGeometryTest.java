package io.github.dswepm.fluorite.rt;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Numerical references and shader bindings for the independent R2b hit-metadata query. */
class RtPathReuseGeometryTest {
    private static final int OK = 0, MISSING = 1, AMBIGUOUS = 2, INVALID = 3;
    private static final double FLOAT_UNITS = Math.scalb(1.0, -20);

    private record V(double x, double y, double z) {
        V add(V b) { return new V(x + b.x, y + b.y, z + b.z); }
        V sub(V b) { return new V(x - b.x, y - b.y, z - b.z); }
        V scale(double s) { return new V(x * s, y * s, z * s); }
        double dot(V b) { return x * b.x + y * b.y + z * b.z; }
        V cross(V b) { return new V(y * b.z - z * b.y, z * b.x - x * b.z, x * b.y - y * b.x); }
        V unit() { return scale(1.0 / Math.sqrt(dot(this))); }
    }

    private static V linear(double[][] m, V p) {
        return new V(m[0][0] * p.x + m[0][1] * p.y + m[0][2] * p.z,
                m[1][0] * p.x + m[1][1] * p.y + m[1][2] * p.z,
                m[2][0] * p.x + m[2][1] * p.y + m[2][2] * p.z);
    }

    private static V normal(double[][] m, V a, V b, V c, V incoming) {
        V n = linear(m, b.sub(a)).cross(linear(m, c.sub(a))).unit();
        return n.dot(incoming) > 0.0 ? n.scale(-1.0) : n;
    }

    private static void same(V expected, V actual) {
        assertEquals(expected.x, actual.x, 1e-12);
        assertEquals(expected.y, actual.y, 1e-12);
        assertEquals(expected.z, actual.z, 1e-12);
    }

    @Test
    void nonuniformInstanceScaleUsesTransformedEdges() {
        double[][] m = {{2, 0, 0}, {0, 3, 0}, {0, 0, .5}};
        V a = new V(0, 0, 0), b = new V(1, 0, 1), c = new V(0, 1, 1);
        V n = normal(m, a, b, c, new V(0, 0, -1));
        // The transformed plane is z = x/4 + y/6, hence normal (-1/4, -1/6, 1).
        same(new V(-.25, -1.0 / 6.0, 1).unit(), n);
        assertEquals(0, n.dot(linear(m, b)), 1e-12);
        assertEquals(0, n.dot(linear(m, c)), 1e-12);
        assertTrue(n.dot(linear(m, b.cross(c)).unit()) < .5,
                "multiplying the object normal by the instance matrix is wrong for nonuniform scale");
    }

    @Test
    void mirroredInstanceAndReversedWindingFaceTheIncomingRay() {
        double[][] m = {{-2, 0, 0}, {0, 3, 0}, {0, 0, .5}};
        V a = new V(0, 0, 0), b = new V(1, 0, 1), c = new V(0, 1, 1);
        V incoming = new V(0, 0, -1);
        V expected = new V(.25, -1.0 / 6.0, 1).unit();
        same(expected, normal(m, a, b, c, incoming));
        same(expected, normal(m, a, c, b, incoming));
        same(expected.scale(-1), normal(m, a, b, c, incoming.scale(-1)));
    }

    @Test
    void fetchedObjectPositionReceivesInstanceTranslationExactlyOnce() {
        double[][] m = {{2, .25, 0}, {0, 3, .5}, {0, 0, .5}};
        V translation = new V(731, -23, 49);
        V a = new V(4, 2, 1), b = new V(5, 2, 2), c = new V(4, 3, 2);
        double u = .2, v = .3;
        V fromEdges = linear(m, a).add(translation)
                .add(linear(m, b.sub(a)).scale(u)).add(linear(m, c.sub(a)).scale(v));
        V reference = linear(m, a.scale(1 - u - v).add(b.scale(u)).add(c.scale(v))).add(translation);
        same(reference, fromEdges);
        V n = normal(m, a, b, c, new V(0, 0, -1));
        same(n, normal(m, a.add(translation), b.add(translation), c.add(translation), new V(0, 0, -1)));
    }

    private static double tolerance(V origin, double t) {
        return FLOAT_UNITS * (1 + Math.max(Math.abs(origin.x),
                Math.max(Math.abs(origin.y), Math.abs(origin.z))) + Math.abs(t));
    }

    private record Candidate(double t, int object, int geometry, int primitive, boolean valid) {
        boolean sameKey(Candidate b) {
            return object == b.object && geometry == b.geometry && primitive == b.primitive;
        }
    }

    // Mirror only the candidate-selection state machine; numerical geometry is checked separately.
    private static int select(V origin, double t, List<Candidate> candidates) {
        Candidate first = null;
        boolean valid = false, invalid = false, ambiguous = false;
        for (Candidate c : candidates) {
            if (Math.abs(c.t - t) > tolerance(origin, t)) continue;
            if (first == null) first = c;
            else if (!first.sameKey(c)) ambiguous = true;
            valid |= c.valid;
            invalid |= !c.valid;
        }
        return ambiguous ? AMBIGUOUS : invalid ? INVALID : valid ? OK : MISSING;
    }

    @Test
    void alphaCutoutCandidatesAtOtherDistancesCannotReplaceTheActualHit() {
        V ro = new V(10, 20, 30);
        Candidate cutout = new Candidate(2, 1, 1, 0, true);
        Candidate actual = new Candidate(8, 2, 0, 5, true);
        Candidate later = new Candidate(12, 3, 0, 1, true);
        for (List<Candidate> order : List.of(List.of(cutout, actual, later), List.of(actual, later, cutout),
                List.of(later, cutout, actual))) {
            assertEquals(OK, select(ro, 8, order));
        }
        assertEquals(MISSING, select(ro, 8, List.of(cutout, later)));
    }

    @Test
    void coplanarTiesAndSharedEdgesAreRejectedRegardlessOfTraversalOrder() {
        V ro = new V(100, 0, 0);
        Candidate a = new Candidate(8, 2, 0, 5, true);
        for (Candidate b : List.of(new Candidate(8, 3, 0, 5, true),
                new Candidate(8, 2, 1, 5, true), new Candidate(8, 2, 0, 6, true),
                new Candidate(8 + .5 * tolerance(ro, 8), 2, 0, 6, true))) {
            assertEquals(AMBIGUOUS, select(ro, 8, List.of(a, b)));
            assertEquals(AMBIGUOUS, select(ro, 8, List.of(b, a)));
        }
        assertEquals(OK, select(ro, 8, List.of(a, a)), "a repeated identical candidate is not another hit");
    }

    @Test
    void invalidMetadataDoesNotBecomeMissingOrDependOnCandidateOrder() {
        V ro = new V(0, 0, 0);
        Candidate bad = new Candidate(8, 2, 0, 5, false);
        Candidate good = new Candidate(8, 2, 0, 6, true);
        assertEquals(INVALID, select(ro, 8, List.of(bad)));
        assertEquals(AMBIGUOUS, select(ro, 8, List.of(good, bad)));
        assertEquals(AMBIGUOUS, select(ro, 8, List.of(bad, good)));
        assertEquals(MISSING, select(ro, 8, List.of()));
    }

    @Test
    void toleranceScalesWithFloatCoordinatesAndRayDistance() {
        assertEquals(Math.scalb(1.0, -20), tolerance(new V(0, 0, 0), 0), 0);
        assertEquals(1013 * FLOAT_UNITS, tolerance(new V(-1000, 4, 2), 12), 0);
        V ro = new V(1000, 0, 0);
        assertEquals(MISSING, select(ro, 12, List.of(new Candidate(
                12 + 2 * tolerance(ro, 12), 0, 0, 0, true))));
    }

    private static V uv(V a, V b, V c) {
        return a.scale(.5).add(b.scale(.2)).add(c.scale(.3));
    }

    @Test
    void terrainAndEntityUvIndexingIncludeTheirOwnGeometryTriangleBase() {
        V[] terrain = new V[30];
        for (int i = 0; i < terrain.length; i++) terrain[i] = new V(i * 7, i * 11, 0);
        int[] terrainBase = {0, 3, 5, 7};
        int terrainPid = 1 + terrainBase[2];
        same(new V(131.6, 206.8, 0), uv(terrain[3 * terrainPid],
                terrain[3 * terrainPid + 1], terrain[3 * terrainPid + 2]));
        int[] entityBase = {0, 2};
        int[] indices = {0, 1, 2, 3, 4, 5, 9, 2, 7, 8, 3, 6};
        V[] vertices = new V[10];
        for (int i = 0; i < vertices.length; i++) vertices[i] = new V(i * 7, i * 11, 0);
        int entityPid = 1 + entityBase[1];
        same(new V(44.8, 70.4, 0), uv(vertices[indices[3 * entityPid]],
                vertices[indices[3 * entityPid + 1]], vertices[indices[3 * entityPid + 2]]));
    }

    @Test
    void shaderUsesActualPositionsWithoutGrowingOrMutatingThePayload() throws IOException {
        String s = source("shaders/world/path_reuse_geometry.slang");
        for (String operation : List.of("RayQuery<RAY_FLAG_FORCE_NON_OPAQUE>",
                "query.CandidateType() != CANDIDATE_NON_OPAQUE_TRIANGLE",
                "abs(query.CandidateTriangleRayT() - hitT) > tolerance",
                "query.CandidateGetIntersectionTriangleVertexPositions()",
                "query.CandidateObjectToWorld3x4()", "cross(edge1, edge2)",
                "mul((float3x3)transform, positions[1] - positions[0])",
                "mul((float3x3)transform, positions[2] - positions[0])",
                "mul(transform, float4(positions[0], 1.0))",
                "dot(miss, miss) > tolerance * tolerance", "dot(normal, rd) > 0.0 ? -normal : normal",
                "9.5367431640625e-7 * (1.0 + scale + abs(hitT))",
                "seen && any(key != firstKey)", "hit = emptyReuseHitGeometry();")) {
            assertTrue(s.contains(operation), operation);
        }
        assertFalse(s.contains(".Commit"), "committing a candidate could hide ties");
        assertFalse(s.contains("payload."), "the metadata query must not mutate the radiance payload");
        assertFalse(s.contains("rndf(") || s.contains("TraceRay("), "no random draws or second radiance trace");
        assertTrue(s.contains("geometryIndex >= (entity ? 2u : TERRAIN_BUCKETS)"));
        assertTrue(s.indexOf("(objectId & PARTICLE_BIT) != 0u") < s.indexOf("ConstPtr<EntityGeom>"));
        assertTrue(s.contains("primitiveIndex + geom.triBase[geometryIndex]"));
        assertTrue(s.contains("primitiveIndex + section.triBase[geometryIndex]"));
        for (int j = 0; j < 3; j++) {
            assertTrue(s.contains("uvs[indices[3u * pid + " + j + "u]]"));
            assertTrue(s.contains("uvs[3u * pid + " + j + "u]"));
        }
        assertTrue(source("shaders/world/path_reuse_trace.slang").contains("#include \"path_reuse_geometry.slang\""));
        assertFalse(source("shaders/world/world.rgen.slang").contains("path_reuse_geometry.slang"));
    }

    @Test
    void metadataDiagnosticUsesTheActualRayAndComparesValuesNotSuccessFlags() throws IOException {
        String s = source("shaders/world/path_reuse_trace.slang");
        int capture = s.indexOf("// R2b geometry diagnostic:");
        assertTrue(capture > s.indexOf("traceRadianceReordered(", s.indexOf("public float3 tracePathReuse(")));
        assertTrue(capture < s.indexOf("SegmentIntegral segIntegral =", capture));
        assertTrue(s.contains("if (identityCheck && indirectDepth < PATH_REUSE_IDENTITY_DEPTH && payload.hitT >= 0.0)"));
        assertTrue(s.contains("ro, rd, payload.hitT, identityGeometry)"));
        assertTrue(s.contains("ro, rd, payload.hitT, lastGeometry)"));
        assertTrue(s.contains("if (queryGeometry && reached == vertices)"));
        assertTrue(s.contains("identityGeometryStatus == REUSE_GEOMETRY_OK"));
        assertTrue(s.contains("reuseSameHitGeometry(identityGeometry, replayedGeometry)"));
        String g = source("shaders/world/path_reuse_geometry.slang");
        for (String check : List.of("source.objectId == replayed.objectId",
                "source.geometryIndex == replayed.geometryIndex", "source.primitiveIndex == replayed.primitiveIndex",
                "dot(source.normal, replayed.normal) >= 1.0 - 1.0e-6",
                "all(uvDelta <= 9.5367431640625e-7 * uvScale)")) {
            assertTrue(g.contains(check));
        }
    }

    @Test
    void sourceStatusesPartitionAttemptsAndTheJavaReaderConsumesEveryLane() throws IOException {
        String g = source("shaders/world/path_reuse_geometry.slang");
        for (String constant : List.of("OK = 0u", "MISSING = 1u", "AMBIGUOUS = 2u", "INVALID = 3u")) {
            assertTrue(g.contains("REUSE_GEOMETRY_" + constant));
        }
        assertTrue(g.contains("worldPush.pathReservoirStatsAddr)[2], 1u"));
        assertTrue(g.contains("worldPush.pathReservoirStatsAddr)[3u + status], 1u"));
        String tracer = source("shaders/world/path_reuse_trace.slang");
        for (int lane : new int[] {7, 8}) assertTrue(tracer.contains("pathReservoirStatsAddr)[" + lane + "], 1u"));
        String java = source("common/src/main/java/io/github/dswepm/fluorite/rt/RtPathReservoirStats.java");
        assertEquals(9, RtPathReservoirStats.LANES);
        assertEquals(36, RtPathReservoirStats.BYTE_SIZE);
        for (int lane = 1; lane < 9; lane++) assertTrue(java.contains("memGetInt(src.mapped + " + (4 * lane) + "L)"));
        assertTrue(java.contains("geometryResolved, geometryAttempt"));
        assertTrue(java.contains("geometryMissing, geometryAmbiguous, geometryInvalid"));
        assertTrue(java.contains("geometryReplayPass, geometryReplayAttempt"));
        assertTrue(java.contains("attempted == 0L ? \"n/a\""));
    }

    private static String source(String relativePath) throws IOException {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("settings.gradle"))) root = root.getParent();
        if (root == null) throw new IOException("Repository root not found");
        return String.join("\n", Files.readAllLines(root.resolve(relativePath)));
    }
}
