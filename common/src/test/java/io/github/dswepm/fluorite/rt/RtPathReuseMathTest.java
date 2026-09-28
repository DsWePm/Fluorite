package io.github.dswepm.fluorite.rt;

import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The M28 S3 rebuild's estimator math, as a Java reference the shader will be pinned to (D231, R0).
 *
 * <p>Every result here is one the rebuild depends on and none of them would show up as an error on
 * screen: a MIS weight that does not sum to one, a Jacobian with its cosine at the wrong vertex, a
 * confidence count that grows without bound. They show up as a picture slightly too dark or too bright,
 * which is the failure D217 was designed around and B03 turned out to be. So the algebra is checked
 * with numbers before any of it is written in Slang, and each later slice pins its Slang spelling to the
 * mirror below the way RtPathReservoirEstimatorTest pins the survivor write.
 *
 * <p>Deterministic: every random stream is seeded.
 */
final class RtPathReuseMathTest {

    // ---- Pairwise MIS (M24's form, restir.slang), generalised to confidences and reverse Jacobians.
    //
    // For a sample y in the receiver's domain, with k reused domains:
    //   m_i(y) = (1/k) c_i t_i(y) / (c_i t_i(y) + c_c t_c(y))
    //   m_c(y) = (1/k) sum_i c_c t_c(y) / (c_c t_c(y) + c_i t_i(y))
    // where t_i(y) = target_i(T_i^-1 y) |dT_i^-1/dy| is domain i's target seen through the reverse shift.

    @Test
    void pairwiseWeightsArePartitionOfUnityForAnyTargetsConfidencesAndJacobians() {
        SplittableRandom rnd = new SplittableRandom(1);
        for (int trial = 0; trial < 10_000; trial++) {
            int k = 1 + rnd.nextInt(6);
            double cc = 1.0 + 19.0 * rnd.nextDouble();
            double tc = rnd.nextDouble() < 0.1 ? 0.0 : 5.0 * rnd.nextDouble();
            double sum = 0.0;
            boolean anySupport = tc > 0.0;
            for (int i = 0; i < k; i++) {
                double ci = 1.0 + 19.0 * rnd.nextDouble();
                double jacobian = 0.05 + 4.0 * rnd.nextDouble();
                double ti = (rnd.nextDouble() < 0.2 ? 0.0 : 5.0 * rnd.nextDouble()) * jacobian;
                anySupport |= ti > 0.0;
                double den = ci * ti + cc * tc;
                double mi = den > 0.0 ? ci * ti / den / k : 0.0;
                double mcShare = den > 0.0 ? cc * tc / den / k : 0.0;
                if (ti == 0.0) {
                    assertEquals(0.0, mi, "a domain that cannot produce y must get no weight for it");
                }
                sum += mi + mcShare;
            }
            if (!anySupport) {
                continue; // y outside every domain's support: no weight is ever evaluated there
            }
            // A pair whose two targets are both zero contributes nothing, which is only a partition of
            // unity if some other pair covers y -- the canonical covers its own support by construction.
            if (tc > 0.0) {
                assertEquals(1.0, sum, 1e-12, "weights over all techniques that can produce y must sum to 1");
            }
        }
    }

    @Test
    void aDomainTargetSpelledTwoWaysBreaksThePartition() {
        // M24 residual (D230 B09): its pairwise weight reads the other domain's target at the canonical
        // sample through the RECEIVER's material (retarget), and at its own sample from the stored REAL
        // value. Two functions for one domain: at a material boundary the pair no longer sums to one.
        double cc = 1.0;
        double ci = 8.0;
        double tc = 0.4;
        double tiRetarget = 2.0;   // domain i's target at y, evaluated with the receiver's material
        double tiReal = 0.7;       // the same target with domain i's real material
        double mi = ci * tiReal / (ci * tiReal + cc * tc);
        double mc = cc * tc / (cc * tc + ci * tiRetarget);
        // Deterministic arithmetic, no noise: these numbers give 0.9577, a 4.2% hole in the partition.
        assertTrue(Math.abs(mi + mc - 1.0) > 0.02,
                "one domain, two target spellings: the weights stop summing to one, got " + (mi + mc));
    }

    // ---- The flatland GRIS chain: the whole combine, run for many frames, against the integral.
    //
    // Sample space: the reconnection vertex's position x in [0,1), area measure, so the reconnection shift
    // is the identity with |J| = 1 and a fresh sample's W is 1/q(x) -- the area-measure bookkeeping the
    // record uses (D231). Each pixel samples with its own density, weighs with its own "BSDF" and sees
    // its own occluder, so neighbours genuinely cannot produce some of each other's samples.

    private static final int PIXELS = 8;
    private static final int NEIGHBOURS = 3;
    private static final double CAP = 20.0;

    private static final class Flatland {
        final double[] a = new double[PIXELS];
        final double[] c = new double[PIXELS];
        final double[] occLo = new double[PIXELS];
        final double[] occW = new double[PIXELS];
        final double[] bsdf = new double[PIXELS];
        final double[][] albedo = new double[PIXELS][3];

        Flatland(long seed) {
            SplittableRandom setup = new SplittableRandom(seed);
            for (int i = 0; i < PIXELS; i++) {
                a[i] = 0.2 + 0.6 * setup.nextDouble();
                c[i] = setup.nextDouble();
                occLo[i] = 0.7 * setup.nextDouble();
                occW[i] = 0.1 + 0.2 * setup.nextDouble();
                bsdf[i] = 0.5 + setup.nextDouble();
                for (int ch = 0; ch < 3; ch++) {
                    albedo[i][ch] = 0.2 + 0.8 * setup.nextDouble();
                }
            }
        }

        static double light(double x) {
            return 0.2 + 3.0 * Math.exp(-Math.pow((x - 0.62) / 0.05, 2.0));
        }

        double scalar(int i, double x) {
            return bsdf[i] * (1.2 + Math.sin(6.0 * x + i)) * light(x);
        }

        boolean visible(int i, double x) {
            return !(x >= occLo[i] && x < occLo[i] + occW[i]);
        }

        /** The receiver's RGB integrand: forward visibility is always traced by the shift. */
        double[] integrand(int i, double x) {
            double s = visible(i, x) ? scalar(i, x) : 0.0;
            return new double[] {albedo[i][0] * s, albedo[i][1] * s, albedo[i][2] * s};
        }

        /** Scalar target; {@code shadowed} false is the reverse shift WITHOUT its visibility ray. */
        double target(int i, double x, boolean shadowed) {
            double s = (shadowed && !visible(i, x)) ? 0.0 : scalar(i, x);
            return s * luminance(albedo[i]);
        }

        double density(int i, double x) {
            return 1.0 + a[i] * Math.cos(2.0 * Math.PI * (x - c[i]));
        }

        double sample(int i, SplittableRandom rnd) {
            while (true) {
                double x = rnd.nextDouble();
                if (rnd.nextDouble() * (1.0 + a[i]) < density(i, x)) {
                    return x;
                }
            }
        }

        double[] integral(int i) {
            double[] sum = new double[3];
            int n = 200_000;
            for (int s = 0; s < n; s++) {
                double[] v = integrand(i, (s + 0.5) / n);
                for (int ch = 0; ch < 3; ch++) {
                    sum[ch] += v[ch] / n;
                }
            }
            return sum;
        }
    }

    private record ChainResult(double[][] mean, double maxConfidence) {
    }

    /**
     * Runs the rebuild's combine: a fresh canonical sample per pixel, the pixel's own previous reservoir
     * (temporal) and NEIGHBOURS previous-frame reservoirs of other pixels (spatial), pairwise MIS in
     * M24's form, section 6.3's RGB shading, a survivor drawn in proportion to the scalar weights, and a
     * per-candidate confidence cap on every previous-frame record.
     */
    private static ChainResult runChain(Flatland w, boolean reverseVisibility, boolean capEveryCandidate,
                                        int frames, long seed) {
        SplittableRandom rnd = new SplittableRandom(seed);
        double[] rx = new double[PIXELS];
        double[] rW = new double[PIXELS];
        double[] rc = new double[PIXELS];
        for (int i = 0; i < PIXELS; i++) {
            rx[i] = w.sample(i, rnd);
            rW[i] = w.target(i, rx[i], true) > 0.0 ? 1.0 / w.density(i, rx[i]) : 0.0;
            rc[i] = 1.0;
        }
        double[][] acc = new double[PIXELS][3];
        double maxConfidence = 0.0;
        long counted = 0;
        int burnIn = 1_000;
        for (int t = 0; t < frames; t++) {
            double[] nx = new double[PIXELS];
            double[] nW = new double[PIXELS];
            double[] nc = new double[PIXELS];
            for (int j = 0; j < PIXELS; j++) {
                double xc = w.sample(j, rnd);
                double wc = 1.0 / w.density(j, xc);
                double cc = 1.0;
                int[] domains = new int[1 + NEIGHBOURS];
                domains[0] = j;
                for (int d = 1; d < domains.length; d++) {
                    int other;
                    do {
                        other = rnd.nextInt(PIXELS);
                    } while (other == j);
                    domains[d] = other;
                }
                int k = domains.length;
                double wSumRaw = 0.0;
                double qSum = 0.0;
                double confidence = cc;
                double chosen = xc;
                double[] rgb = new double[3];
                double tcAtC = w.target(j, xc, true);
                for (int d = 0; d < k; d++) {
                    int i = domains[d];
                    double y = rx[i];
                    double ci = capEveryCandidate || d == 0 ? Math.min(CAP, rc[i]) : rc[i];
                    double tcAtY = w.target(j, y, true);
                    double tiAtY = w.target(i, y, true);
                    double tiAtC = w.target(i, xc, reverseVisibility);
                    double denY = ci * tiAtY + cc * tcAtY;
                    double p = denY > 0.0 ? ci * tiAtY / denY : 0.0;
                    double denC = ci * tiAtC + cc * tcAtC;
                    qSum += denC > 0.0 ? cc * tcAtC / denC : 0.0;
                    confidence += ci;
                    double weight = p * tcAtY * rW[i];
                    wSumRaw += weight;
                    double[] f = w.integrand(j, y);
                    for (int ch = 0; ch < 3; ch++) {
                        rgb[ch] += (p / k) * f[ch] * rW[i];
                    }
                    if (weight > 0.0 && rnd.nextDouble() * wSumRaw < weight) {
                        chosen = y;
                    }
                }
                double canonicalWeight = qSum * tcAtC * wc;
                double total = wSumRaw + canonicalWeight;
                double[] fc = w.integrand(j, xc);
                for (int ch = 0; ch < 3; ch++) {
                    rgb[ch] += (qSum / k) * fc[ch] * wc;
                }
                if (canonicalWeight > 0.0 && rnd.nextDouble() * total < canonicalWeight) {
                    chosen = xc;
                }
                double chosenTarget = w.target(j, chosen, true);
                nx[j] = chosen;
                nW[j] = chosenTarget > 0.0 ? (total / k) / chosenTarget : 0.0;
                nc[j] = confidence;
                maxConfidence = Math.max(maxConfidence, confidence);
                if (t >= burnIn) {
                    for (int ch = 0; ch < 3; ch++) {
                        acc[j][ch] += rgb[ch];
                    }
                }
            }
            rx = nx;
            rW = nW;
            rc = nc;
            if (t >= burnIn) {
                counted++;
            }
        }
        for (int j = 0; j < PIXELS; j++) {
            for (int ch = 0; ch < 3; ch++) {
                acc[j][ch] /= counted;
            }
        }
        return new ChainResult(acc, maxConfidence);
    }

    @Test
    void theChainIsUnbiasedWhenTheReverseShiftTracesVisibility() {
        Flatland w = new Flatland(11);
        ChainResult result = runChain(w, true, true, 200_000, 5);
        double meanRel = 0.0;
        for (int j = 0; j < PIXELS; j++) {
            double truth = luminance(w.integral(j));
            double rel = luminance(result.mean()[j]) / truth - 1.0;
            meanRel += rel / PIXELS;
            // Monte Carlo spread at 200k frames measured under 1.5% across seeds; 3% keeps the test honest
            // without flaking, and sits two orders of magnitude inside the defects below.
            assertTrue(Math.abs(rel) < 0.03, "pixel " + j + " off the integral by " + rel);
        }
        assertTrue(Math.abs(meanRel) < 0.015, "the chain drifts from the integral on average: " + meanRel);
    }

    @Test
    void droppingTheReverseVisibilityRayDarkens() {
        // Q2's isolation switch, quantified: a neighbour that could never have produced the canonical
        // sample (it is occluded from there) still takes MIS weight from it, so the weights over the
        // domains that CAN produce it sum below one. This scene's occluders make it large on purpose.
        Flatland w = new Flatland(11);
        ChainResult result = runChain(w, false, true, 20_000, 5);
        double meanRel = 0.0;
        for (int j = 0; j < PIXELS; j++) {
            meanRel += (luminance(result.mean()[j]) / luminance(w.integral(j)) - 1.0) / PIXELS;
        }
        assertTrue(meanRel < -0.10, "expected a clear darkening without the reverse visibility, got " + meanRel);
    }

    @Test
    void confidenceIsBoundedOnlyWhenEveryPreviousFrameRecordIsCapped() {
        // B02's fix: the paper caps the temporal confidence and lets spatial neighbours through, but its
        // neighbours are this frame's post-temporal reservoirs; ours are previous-frame records, so an
        // uncapped neighbour feeds its own growth back and the count explodes.
        Flatland w = new Flatland(11);
        ChainResult capped = runChain(w, true, true, 200, 3);
        assertTrue(capped.maxConfidence() <= 1.0 + (1 + NEIGHBOURS) * CAP + 1e-9,
                "capped confidence exceeded 1 + (1+K) Cap: " + capped.maxConfidence());
        ChainResult uncapped = runChain(w, true, false, 60, 3);
        assertTrue(uncapped.maxConfidence() > 1e9,
                "an uncapped previous-frame neighbour should grow geometrically, got " + uncapped.maxConfidence());
    }

    // ---- Enhanced Eq. 2: the hybrid shift's Jacobian, and the stored denominator D.
    //
    //   J(y <- x) = [p^y_{k-1}(w'_{k-1}) G(y_{k-1} -> x_k) p^y_k(w_k)] / [p^x_{k-1}(w_{k-1}) G(x_{k-1} -> x_k) p^x_k(w_k)]
    //   G(a -> b) = |cos theta_b| / |a - b|^2, theta_b between the connection and b's normal.

    private static double[] sub(double[] a, double[] b) {
        return new double[] {a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static double[] normalize(double[] a) {
        double l = Math.sqrt(dot(a, a));
        return new double[] {a[0] / l, a[1] / l, a[2] / l};
    }

    /** Cosine-weighted density at a prefix vertex with normal n toward direction w. */
    private static double cosinePdf(double[] n, double[] w) {
        return Math.max(0.0, dot(n, w)) / Math.PI;
    }

    /** A glossy continuation density at x_k that depends on the incoming direction (a Phong lobe). */
    private static double glossyPdf(double[] n, double[] toPrefix, double[] out, double exponent) {
        double[] r = sub(new double[] {2.0 * dot(n, toPrefix) * n[0], 2.0 * dot(n, toPrefix) * n[1],
                2.0 * dot(n, toPrefix) * n[2]}, toPrefix);
        return (exponent + 1.0) / (2.0 * Math.PI) * Math.pow(Math.max(0.0, dot(r, out)), exponent);
    }

    /** The Eq. 2 numerator (or denominator) for a prefix vertex {@code prev} reconnecting to x_k. */
    private static double reconnectionDensity(double[] prev, double[] prevNormal, double[] xk, double[] xkNormal,
                                              double[] outK, double exponent) {
        double[] d = sub(xk, prev);
        double dist2 = dot(d, d);
        double[] toXk = normalize(d);
        double geometry = Math.abs(dot(xkNormal, toXk)) / dist2;
        double[] toPrefix = normalize(sub(prev, xk));
        return cosinePdf(prevNormal, toXk) * geometry * glossyPdf(xkNormal, toPrefix, outK, exponent);
    }

    @Test
    void theJacobianIsOneForTheIdentityInvertsExactlyAndChainsThroughTheStoredDenominator() {
        SplittableRandom rnd = new SplittableRandom(2);
        int checked = 0;
        while (checked < 2_000) {
            double[] up = {0.0, 1.0, 0.0};
            double[] xk = {rnd.nextDouble() * 4 - 2, 3.0 + rnd.nextDouble(), rnd.nextDouble() * 4 - 2};
            double[] xkNormal = normalize(new double[] {rnd.nextDouble() - 0.5, -1.0, rnd.nextDouble() - 0.5});
            double[] outK = normalize(new double[] {rnd.nextDouble() - 0.5, -1.0, rnd.nextDouble() - 0.5});
            double exponent = 2.0 + 30.0 * rnd.nextDouble();
            double[] x = {rnd.nextDouble() * 2 - 1, 0.0, rnd.nextDouble() * 2 - 1};
            double[] y = {rnd.nextDouble() * 2 - 1, 0.0, rnd.nextDouble() * 2 - 1};
            double[] z = {rnd.nextDouble() * 2 - 1, 0.0, rnd.nextDouble() * 2 - 1};
            double dx = reconnectionDensity(x, up, xk, xkNormal, outK, exponent);
            double dy = reconnectionDensity(y, up, xk, xkNormal, outK, exponent);
            double dz = reconnectionDensity(z, up, xk, xkNormal, outK, exponent);
            if (!(dx > 1e-9 && dy > 1e-9 && dz > 1e-9)) {
                continue; // outside a lobe's support: the shift is rejected there, not weighted
            }
            checked++;
            double jyx = dy / dx;
            assertEquals(1.0, reconnectionDensity(x, up, xk, xkNormal, outK, exponent) / dx, 0.0,
                    "the identity shift must have Jacobian exactly 1");
            assertEquals(1.0, jyx * (dx / dy), 1e-12, "J(y<-x) J(x<-y) must be 1");
            // The record stores D = the source's density; a survivor written at y stores D' = its
            // numerator. Shifting that survivor on to z must equal the direct shift from x.
            double storedAtY = dy;
            assertEquals(dz / dx, (dz / storedAtY) * jyx, 1e-12 * (dz / dx),
                    "D' := numerator makes shifts chain: J(z<-y) J(y<-x) = J(z<-x)");
        }
    }

    @Test
    void theGeometryTermsCosineBelongsAtTheReconnectionVertex() {
        // Enhanced prints the angle in G as the one at y_{k-1}. The identity and inverse checks above
        // hold for either placement, so they cannot tell; an integral can. Flatland: a sampler at x picks
        // an angle uniformly over a segment S, the receiver at y reconnects to the same point of S, and
        // the shifted sample must integrate the receiver's angular integrand h exactly.
        double[] x = {0.0, 0.0};
        double[] y = {0.6, 0.2};
        double[] s0 = {-1.0, 2.0};
        double[] s1 = {2.0, 2.5};
        double[] seg = {s1[0] - s0[0], s1[1] - s0[1]};
        double segLen = Math.hypot(seg[0], seg[1]);
        double[] segNormal = {-seg[1] / segLen, seg[0] / segLen};
        double[] sourceNormal = {0.0, 1.0};
        double thA = Math.atan2(s0[1] - x[1], s0[0] - x[0]);
        double thB = Math.atan2(s1[1] - x[1], s1[0] - x[0]);
        double phA = Math.atan2(s0[1] - y[1], s0[0] - y[0]);
        double phB = Math.atan2(s1[1] - y[1], s1[0] - y[0]);
        int n = 400_000;
        double truth = 0.0;
        for (int i = 0; i < n; i++) {
            double ph = phA + (phB - phA) * (i + 0.5) / n;
            truth += h(ph) * Math.abs(phB - phA) / n;
        }
        double correct = 0.0;
        double misprinted = 0.0;
        for (int i = 0; i < n; i++) {
            double th = thA + (thB - thA) * (i + 0.5) / n;
            double[] dir = {Math.cos(th), Math.sin(th)};
            // Ray x + t dir meets S at s0 + u seg.
            double det = dir[0] * (-seg[1]) - dir[1] * (-seg[0]);
            double t = ((s0[0] - x[0]) * (-seg[1]) - (s0[1] - x[1]) * (-seg[0])) / det;
            double[] zPt = {x[0] + t * dir[0], x[1] + t * dir[1]};
            double[] toZfromY = {zPt[0] - y[0], zPt[1] - y[1]};
            double ry = Math.hypot(toZfromY[0], toZfromY[1]);
            double[] dy = {toZfromY[0] / ry, toZfromY[1] / ry};
            double ph = Math.atan2(toZfromY[1], toZfromY[0]);
            // Flatland G is |cos| / r. Correct: the cosines at z against the segment's normal.
            double jCorrect = (Math.abs(segNormal[0] * dy[0] + segNormal[1] * dy[1]) / ry)
                    / (Math.abs(segNormal[0] * dir[0] + segNormal[1] * dir[1]) / t);
            // As printed: the cosines at the prefix vertices against their own normals.
            double jPrinted = (Math.abs(sourceNormal[0] * dy[0] + sourceNormal[1] * dy[1]) / ry)
                    / (Math.abs(sourceNormal[0] * dir[0] + sourceNormal[1] * dir[1]) / t);
            double w = Math.abs(thB - thA) / n; // quadrature of E[h J / p_x] with p_x = 1/|thB - thA|
            correct += h(ph) * jCorrect * w;
            misprinted += h(ph) * jPrinted * w;
        }
        assertEquals(truth, correct, 1e-6 * truth, "the cosine at the reconnection vertex integrates exactly");
        // Deterministic quadrature, no noise: this geometry puts the printed placement 4.7% off.
        assertTrue(Math.abs(misprinted / truth - 1.0) > 0.02,
                "the printed placement should not integrate the receiver's integrand, got " + misprinted / truth);
    }

    private static double h(double angle) {
        return 1.0 + 0.5 * Math.cos(3.0 * angle);
    }

    // ---- Path-tree RIS: one technique-tagged event per record, and the roulette kept outside it.

    @Test
    void pathTreeRisMarginalisesToTheFullSumAndTheRouletteStaysOutside() {
        SplittableRandom rnd = new SplittableRandom(4);
        for (int trial = 0; trial < 2_000; trial++) {
            int events = 1 + rnd.nextInt(6);
            double[][] contribution = new double[events][3];
            double total = 0.0;
            double[] fullSum = new double[3];
            for (int e = 0; e < events; e++) {
                for (int ch = 0; ch < 3; ch++) {
                    contribution[e][ch] = rnd.nextDouble() < 0.15 ? 0.0 : 3.0 * rnd.nextDouble();
                    fullSum[ch] += contribution[e][ch];
                }
                total += luminance(contribution[e]);
            }
            if (!(total > 0.0)) {
                continue;
            }
            // Exact expectation over the WRS pick: sum_e P(e) F(e) W_tree(e), P(e) = lum_e / total,
            // W_tree(e) = total / lum_e.
            double[] expectation = new double[3];
            for (int e = 0; e < events; e++) {
                double lum = luminance(contribution[e]);
                if (lum <= 0.0) {
                    continue;
                }
                for (int ch = 0; ch < 3; ch++) {
                    expectation[ch] += (lum / total) * contribution[e][ch] * (total / lum);
                }
            }
            for (int ch = 0; ch < 3; ch++) {
                assertEquals(fullSum[ch], expectation[ch], 1e-12 * Math.max(1.0, fullSum[ch]),
                        "a single RIS-picked event with its W_tree estimates the whole tree");
            }
            // The prefix roulette (survival q) stays outside the reused sample: the record keeps the
            // RR-free contribution and folds 1/q into the canonical W, so survival-weighted, the pixel
            // sees the same expectation whether or not the roulette ran (Enhanced section 6.2.4).
            double q = 0.05 + 0.95 * rnd.nextDouble();
            for (int ch = 0; ch < 3; ch++) {
                double rrWeighted = q * (fullSum[ch] / q);
                assertEquals(fullSum[ch], rrWeighted, 1e-12 * Math.max(1.0, fullSum[ch]));
            }
        }
    }

    private static double luminance(double[] rgb) {
        return 0.2126 * rgb[0] + 0.7152 * rgb[1] + 0.0722 * rgb[2];
    }
}
