package io.github.dswepm.fluorite.rt;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.*;

/** Selected-lobe source evaluation, independently checked against sampled normals and BRDF/PDF. */
final class RtPathLobeEvaluationTest {
    private static final int DIFFUSE = 1, SPECULAR = 2, FILM = 3;
    private static final double FILM_F0 = .020373;
    private record V(double x, double y, double z) {
        V add(V b) { return new V(x + b.x, y + b.y, z + b.z); }
        V mul(double s) { return new V(x * s, y * s, z * s); }
        V mul(V b) { return new V(x * b.x, y * b.y, z * b.z); }
        double dot(V b) { return x * b.x + y * b.y + z * b.z; }
        V unit() { return mul(1 / Math.sqrt(dot(this))); }
        boolean nonnegativeFinite() { return Double.isFinite(x + y + z) && x >= 0 && y >= 0 && z >= 0; }
    }
    private record C(V v, V diffuse, V f0, double rough, double ax, double ay,
                     double pf, double ps, double walk, double film, double filmAlpha) {
        double ndv() { return Math.max(1e-4, v.z); }
    }
    private record Sample(double alpha, double pdf, V weight) {}
    private static C context(V v, double ax, double ay, double pf, double ps, double walk, double film) {
        return new C(v, new V(.7, .2, .05), new V(.2, .05, .7), Math.sqrt(ax * ay),
                ax, ay, pf, ps, walk, film, .037);
    }
    private static double g1(double n, double alpha) {
        return 2 * n / (n + Math.sqrt(alpha * alpha + (1 - alpha * alpha) * n * n) + 1e-7);
    }
    private static double g1a(V d, double n, double ax, double ay) {
        return 2 * n / (n + Math.sqrt(ax * ax * d.x * d.x + ay * ay * d.y * d.y + n * n) + 1e-7);
    }
    private static double d(double nh, double alpha) {
        double a2 = alpha * alpha, den = nh * nh * (a2 - 1) + 1;
        return a2 / (Math.PI * den * den + 1e-20);
    }
    private static double da(V h, double ax, double ay) {
        double ax2 = ax * ax, ay2 = ay * ay;
        double t = h.x * h.x * ay2 + h.y * h.y * ax2 + h.z * h.z * ax2 * ay2;
        return ax2 * ax * ay2 * ay / (Math.PI * t * t + 1e-30);
    }
    private static V fresnel(double cosine, V f0) {
        double m = Math.pow(Math.clamp(1 - cosine, 0, 1), 5);
        return f0.mul(1 - m).add(new V(m, m, m));
    }
    private static double transmission(C c, V wi) {
        if (c.film <= 0) return 1;
        V f0 = new V(FILM_F0, FILM_F0, FILM_F0);
        return (1 - c.film * fresnel(c.ndv(), f0).x) * (1 - c.film * fresnel(Math.max(0, wi.z), f0).x);
    }
    private static Sample evaluate(C c, int lobe, V wi) {
        if (!Double.isFinite(wi.x) || !Double.isFinite(wi.y) || !Double.isFinite(wi.z)) return null;
        if (!(wi.z > 0) || !Double.isFinite(wi.z)
                || !(c.pf >= 0 && c.pf <= 1 && c.ps >= 0 && c.ps <= 1 && c.walk >= 0 && c.walk <= 1)) return null;
        Sample s;
        if (lobe == DIFFUSE) {
            double probability = (1 - c.pf) * (1 - c.ps) * (1 - c.walk);
            if (!(probability > 0)) return null;
            s = new Sample(1, probability * wi.z / Math.PI, c.diffuse.mul(transmission(c, wi) / probability));
        } else if (lobe == SPECULAR || lobe == FILM) {
            V sum = c.v.add(wi);
            if (!(sum.dot(sum) > 0) || !Double.isFinite(sum.dot(sum))) return null;
            V h = sum.unit();
            if (lobe == SPECULAR) {
                double probability = (1 - c.pf) * c.ps;
                if (!(probability > 0 && c.ax > 0 && c.ay > 0)) return null;
                double pdf = probability * da(h, c.ax, c.ay) * g1a(c.v, c.ndv(), c.ax, c.ay) / Math.max(4 * c.ndv(), 1e-8);
                V weight = fresnel(Math.max(0, c.v.dot(h)), c.f0)
                        .mul(g1a(wi, wi.z, c.ax, c.ay) * transmission(c, wi) / probability);
                s = new Sample(c.rough, pdf, weight);
            } else {
                if (!(c.pf > 0 && c.filmAlpha > 0 && c.film > 0)) return null;
                double pdf = c.pf * d(Math.max(0, h.z), c.filmAlpha) * g1(c.ndv(), c.filmAlpha) / Math.max(4 * c.ndv(), 1e-8);
                V weight = fresnel(Math.max(0, c.v.dot(h)), new V(FILM_F0, FILM_F0, FILM_F0))
                        .mul(c.film * g1(wi.z, c.filmAlpha) / c.pf);
                s = new Sample(c.filmAlpha, pdf, weight);
            }
        } else return null;
        return s.pdf > 0 && Double.isFinite(s.pdf) && s.weight.nonnegativeFinite() ? s : null;
    }
    private static void near(double a, double b) { assertEquals(a, b, 1e-8 * Math.max(1e-10, Math.max(Math.abs(a), Math.abs(b)))); }
    private static void near(V a, V b) { near(a.x, b.x); near(a.y, b.y); near(a.z, b.z); }
    private static double error(V a, V b) {
        double scale = Math.max(Math.max(Math.max(Math.abs(a.x), Math.abs(a.y)), Math.abs(a.z)),
                Math.max(Math.max(Math.abs(b.x), Math.abs(b.y)), Math.abs(b.z)));
        return scale > 0 ? Math.max(Math.max(Math.abs(a.x - b.x), Math.abs(a.y - b.y)), Math.abs(a.z - b.z)) / scale : 0;
    }
    private static V hemisphere(SplittableRandom r) {
        double z = r.nextDouble(.0002, 1), phi = r.nextDouble(2 * Math.PI), xy = Math.sqrt(1 - z * z);
        return new V(xy * Math.cos(phi), xy * Math.sin(phi), z);
    }

    @Test
    void reconstructingTheSampledHalfVectorRecoversBothGgxLobesAndTheirBrdfOverPdf() {
        SplittableRandom r = new SplittableRandom(64);
        int accepted = 0;
        for (int trial = 0; trial < 30_000; trial++) {
            V v = hemisphere(r), h = hemisphere(r);
            V wi = h.mul(2 * v.dot(h)).add(v.mul(-1));
            if (v.dot(h) <= 0 || wi.z <= .0001) continue;
            C c = context(v, r.nextDouble(.001, 1.4), r.nextDouble(.001, 1.4), .2, .35, .12, .65);
            for (int lobe : new int[]{SPECULAR, FILM}) {
                Sample evaluated = evaluate(c, lobe, wi);
                assertNotNull(evaluated);
                double probability = lobe == SPECULAR ? (1 - c.pf) * c.ps : c.pf;
                double distribution = lobe == SPECULAR ? da(h, c.ax, c.ay) : d(h.z, c.filmAlpha);
                double viewMask = lobe == SPECULAR ? g1a(v, c.ndv(), c.ax, c.ay) : g1(c.ndv(), c.filmAlpha);
                double exitMask = lobe == SPECULAR ? g1a(wi, wi.z, c.ax, c.ay) : g1(wi.z, c.filmAlpha);
                double sampledPdf = probability * distribution * viewMask / Math.max(4 * c.ndv(), 1e-8);
                V f0 = lobe == SPECULAR ? c.f0 : new V(FILM_F0, FILM_F0, FILM_F0);
                V fcos = fresnel(v.dot(h), f0).mul(distribution * viewMask * exitMask / (4 * c.ndv()))
                        .mul(lobe == SPECULAR ? transmission(c, wi) : c.film);
                near(sampledPdf, evaluated.pdf);
                near(fcos.mul(1 / sampledPdf), evaluated.weight);
            }
            accepted++;
        }
        assertTrue(accepted > 4000);
    }

    @Test
    void diffuseUsesItsDiscreteProbabilityAndExcludesTheOtherLobesPdf() {
        C c = context(new V(0, 0, 1), .2, .3, .2, .35, .12, .65);
        V wi = new V(.3, .4, Math.sqrt(.75));
        Sample diffuse = evaluate(c, DIFFUSE, wi), spec = evaluate(c, SPECULAR, wi), film = evaluate(c, FILM, wi);
        double probability = .8 * .65 * .88;
        near(probability * wi.z / Math.PI, diffuse.pdf);
        near(c.diffuse.mul(transmission(c, wi) / probability), diffuse.weight);
        assertTrue(diffuse.pdf < diffuse.pdf + spec.pdf + film.pdf);
        assertEquals(1, diffuse.alpha);
    }

    @Test
    void anisotropicAxisSwapIsAnExactChangeOfCoordinates() {
        V v = new V(.4, .6, .8).unit(), wi = new V(-.1, .5, .9).unit();
        Sample original = evaluate(context(v, .05, .6, .2, .4, 0, .3), SPECULAR, wi);
        Sample swapped = evaluate(context(new V(v.y, v.x, v.z), .6, .05, .2, .4, 0, .3), SPECULAR,
                new V(wi.y, wi.x, wi.z));
        near(original.pdf, swapped.pdf); near(original.weight, swapped.weight);
    }

    @Test
    void changingTheIncidentViewReevaluatesFresnelMaskingAndTheHalfVector() {
        V wi = new V(.3, 0, Math.sqrt(.91));
        Sample a = evaluate(context(new V(0, 0, 1), .08, .4, .2, .4, 0, .6), SPECULAR, wi);
        Sample b = evaluate(context(new V(.9, 0, Math.sqrt(.19)), .08, .4, .2, .4, 0, .6), SPECULAR, wi);
        assertTrue(Math.abs(a.pdf - b.pdf) > .01);
        assertTrue(error(a.weight, b.weight) > 1e-5);
    }

    @Test
    void filmHasItsOwnProbabilityAlphaAndSingleFilmResponse() {
        C c = context(new V(0, 0, 1), .2, .5, .2, .4, .3, .65);
        Sample film = evaluate(c, FILM, new V(0, 0, 1));
        near(c.film * FILM_F0 * g1(1, c.filmAlpha) / c.pf, film.weight.x);
        assertEquals(c.filmAlpha, film.alpha);
        assertEquals(c.rough, evaluate(c, SPECULAR, new V(0, 0, 1)).alpha);
    }

    @Test
    void darkColourChannelsRemainZeroWithoutDividingByTheColour() {
        C c = new C(new V(0, 0, 1), new V(0, .2, 0), new V(.04, .04, .04), .2, .2, .2, 0, .3, 0, 0, .02);
        Sample diffuse = evaluate(c, DIFFUSE, new V(0, 0, 1));
        near(new V(0, .2 / .7, 0), diffuse.weight);
        assertTrue(Double.isFinite(diffuse.pdf));
        assertEquals(1, error(new V(0, .2, 0), new V(0, 0, 0)));
        assertEquals(0, error(new V(0, 0, 0), new V(0, 0, 0)));
    }

    @Test
    void unsupportedMeasuresZeroSharesAndNonfiniteOrBelowSurfaceDirectionsAreRejected() {
        C c = context(new V(0, 0, 1), .2, .4, .2, .4, 0, .5);
        V up = new V(0, 0, 1);
        for (int lobe : new int[]{0, 4, 5, 99}) assertNull(evaluate(c, lobe, up));
        assertNull(evaluate(c, SPECULAR, new V(0, 0, -1)));
        assertNull(evaluate(c, DIFFUSE, new V(Double.NaN, 0, 1)));
        assertNull(evaluate(context(up, 0, 0, 0, .5, 0, 0), SPECULAR, up));
        assertNull(evaluate(context(up, .2, .4, 1, .5, 0, .5), SPECULAR, up));
        assertNull(evaluate(context(up, .2, .4, 0, 1, 0, 0), DIFFUSE, up));
        assertNull(evaluate(context(up, .2, .4, 0, .5, 0, 0), FILM, up));
        assertNull(evaluate(context(up, .2, .4, Double.NaN, .5, 0, .5), FILM, up));
    }

    // The existing oct16 representation, including its finite bit grid at the pole.
    private static V packedDirection(V v) {
        double sum = Math.abs(v.x) + Math.abs(v.y) + Math.abs(v.z), x = v.x / sum, y = v.y / sum;
        if (v.z < 0) { double old = x; x = (1 - Math.abs(y)) * (x >= 0 ? 1 : -1); y = (1 - Math.abs(old)) * (y >= 0 ? 1 : -1); }
        x = Math.floor((x * .5 + .5) * 65535 + .5) / 65535 * 2 - 1;
        y = Math.floor((y * .5 + .5) * 65535 + .5) / 65535 * 2 - 1;
        double z = 1 - Math.abs(x) - Math.abs(y);
        if (z < 0) { double old = x; x = (1 - Math.abs(y)) * (x >= 0 ? 1 : -1); y = (1 - Math.abs(old)) * (y >= 0 ? 1 : -1); }
        return new V(x, y, z).unit();
    }

    @Test
    void oct16CanPerturbAThinLobesPdfWhileItsWeightBarelyChanges() {
        C c = context(new V(0, 0, 1), .0005, .0005, 0, .5, 0, 0);
        double maxPdfError = 0, maxWeightError = 0;
        for (int i = 0; i < 128; i++) {
            double x = .0006 + i * .000006;
            V wi = new V(x, 0, Math.sqrt(1 - x * x));
            Sample a = evaluate(c, SPECULAR, wi), b = evaluate(c, SPECULAR, packedDirection(wi));
            maxPdfError = Math.max(maxPdfError, Math.abs(a.pdf - b.pdf) / Math.max(a.pdf, b.pdf));
            maxWeightError = Math.max(maxWeightError, error(a.weight, b.weight));
        }
        assertTrue(maxPdfError > .01, "PDF error must be measured separately from RGB error");
        assertTrue(maxWeightError < 1e-6);
    }

    @Test
    void rgb9e5CanLoseAWeakIncidentChannelThatDominatesAColouredReceiver() {
        // The shared exponent at R=32768 gives a step of 128. A green incident value of 1 disappears.
        // Packing the incident and reevaluating the source must expose this; targetOwn alone cannot.
        V incident = new V(32768, 1, 0), decoded = new V(32768, 0, 0), receiver = new V(0, 1, 0);
        assertEquals(1, error(receiver.mul(incident), receiver.mul(decoded)));
        assertTrue(error(incident, decoded) < .001);
    }

    @Test
    void theActualShaderAndDiagnosticConsumerMatchTheNumericalModel() throws Exception {
        String eval = source("shaders/world/path_reuse_eval.slang");
        for (String term : new String[]{"(1.0 - pf) * (1.0 - ps) * (1.0 - walkSssShare)",
                "result.pdf = probability * ndl * INV_PI;", "c.diffAlb * rainBaseTransmission(c, wi) / probability",
                "float3 sum = c.v + wi;", "float3 h = sum * rsqrt(sumSq);", "result.alpha = c.rough;",
                "ggxReflectPdfAniso(dot(h, c.t), dot(h, c.b), ndh,", "c.ndv, c.alphaX, c.alphaY, c.g1v)",
                "ggxG1Aniso(dot(wi, c.t), dot(wi, c.b), ndl, c.alphaX, c.alphaY)",
                "result.pdf = pf * ggxReflectPdf(ndh, c.ndv, alpha, ggxG1(c.ndv, alpha));",
                "reuseFresnelSchlickDirections(c.v, wi, c.F0)",
                "c.rain.film * reuseFresnelSchlickDirections(c.v, wi,", "ggxG1(ndl, alpha) / pf", "any(result.weight < 0.0)"})
            assertTrue(eval.contains(term), term);
        assertFalse(eval.contains("rndf(") || eval.contains("traceRadiance(") || eval.contains("bsdfContinuationPdf("));
        String loop = source("shaders/world/path_reuse_trace.slang");
        assertTrue(loop.contains("bc, pf, ps, walkSssShare, continuation.lobe, rd)"));
        assertTrue(loop.contains("bc, pf, ps, walkSssShare, continuation.lobe, packedDirection)"));
        assertTrue(loop.contains("dPackedSourceWeight = sourceEvalPrefix * packedEval.weight;"));
        assertTrue(loop.contains("dPackedSourceWeight * reuseLoadIncident(produced)"));
        assertTrue(loop.contains("reuseRelativeScalarError(continuation.pdf, packedEval.pdf)"));
        assertTrue(loop.indexOf("float3 sourceEvalPrefix =") < loop.lastIndexOf("if (!reuseSampleContinuation(vtx"));
        assertFalse(source("shaders/world/world.rgen.slang").contains("path_reuse_eval.slang"));
        assertTrue(source("shaders/world/rain_surface.slang").contains("WATER_FILM_F0 = " + FILM_F0));
    }

    @Test
    void codecIsolationDoesNotAttributeSourceEvaluationRoundoffToDirectionStorage() throws Exception {
        V sampled = new V(1e-19, 1e-19, 1e-19), evaluated = sampled.mul(1.002);
        assertTrue(error(sampled, evaluated) > .001);
        assertEquals(0, error(evaluated, evaluated));
        String loop = source("shaders/world/path_reuse_trace.slang");
        assertTrue(loop.contains("reuseRelativeScalarError(evaluated.pdf, packedEval.pdf)"));
        assertTrue(loop.contains("reuseRelativeRgbError(evaluated.weight, packedEval.weight)"));
        assertTrue(loop.contains("any(asuint(rd) != asuint(packedDirection))"));
        for (int i : new int[]{40, 41}) assertTrue(loop.contains("pathReservoirStatsAddr)[" + i + "], 1u"));
        for (int i : new int[]{42, 43}) assertTrue(loop.contains("pathReservoirStatsAddr)[" + i + "], asuint("));
        String stats = source("common/src/main/java/io/github/dswepm/fluorite/rt/RtPathReservoirStats.java");
        for (int i = 40; i < 44; i++) assertTrue(stats.contains("memGetInt(src.mapped + " + 4 * i + "L)"));
        assertTrue(stats.contains("direction codec {} (bits changed {}, max pdf {}, weight {})"));
    }

    @Test
    void allDiagnosticLanesHaveAConsumerAndPackedErrorBinsAreDisjoint() throws Exception {
        assertEquals(48, RtPathReservoirStats.LANES);
        assertEquals(192, RtPathReservoirStats.BYTE_SIZE);
        String stats = source("common/src/main/java/io/github/dswepm/fluorite/rt/RtPathReservoirStats.java");
        for (int i = 20; i < 40; i++) assertTrue(stats.contains("memGetInt(src.mapped + " + 4 * i + "L)"));
        String loop = source("shaders/world/path_reuse_trace.slang");
        for (int i : new int[]{20, 21, 22, 23, 26, 27, 33, 35, 37, 39}) assertTrue(loop.contains("pathReservoirStatsAddr)[" + i + "], 1u"));
        for (int i : new int[]{24, 25, 31, 32, 34, 36, 38}) assertTrue(loop.contains("pathReservoirStatsAddr)[" + i + "], asuint("));
        assertTrue(loop.contains("sourceEvalPrefix * evaluated.weight;"));
        assertTrue(loop.contains("float3 directionOnly = dPackedSourceWeight * dTree.selectedIncident;"));
        assertTrue(loop.contains("float3 incidentOnly = dSourceWeight * reuseLoadIncident(produced);"));
        assertTrue(loop.contains("error <= 0.001 ? 28 : (error <= 0.01 ? 29 : 30)"));
        assertTrue(stats.contains("Float.intBitsToFloat"));
        for (double e : new double[]{0, .00099, .001, .00101, .01, .01001, 1}) {
            boolean low = e <= .001, medium = e > .001 && e <= .01, high = e > .01;
            assertEquals(1, (low ? 1 : 0) + (medium ? 1 : 0) + (high ? 1 : 0));
        }
    }

    private static String source(String relative) throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("settings.gradle"))) root = root.getParent();
        assertNotNull(root);
        return Files.readString(root.resolve(relative)).replace("\r\n", "\n");
    }
}
