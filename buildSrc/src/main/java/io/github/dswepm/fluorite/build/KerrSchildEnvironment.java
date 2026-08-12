package io.github.dswepm.fluorite.build;

/**
 * Offline Kerr null-geodesic and local accretion-disk path extractor.
 *
 * <p>The old generator integrated the Hamiltonian in Boyer-Lindquist spherical coordinates. Those
 * coordinates are singular on the spin axis; an RK step that crossed a pole acquired an arbitrary
 * azimuth and produced the vertical discontinuity visible in the first M14 build. This implementation
 * uses horizon-penetrating Cartesian Kerr-Schild coordinates. The game never runs this integrator: it
 * samples the KTX2 transfer maps produced by the Gradle task.</p>
 *
 * <p>The metric is {@code g_mn = eta_mn + 2 H l_m l_n}. Null rays are advanced with the stationary
 * 3+1 Hamiltonian {@code E = alpha sqrt(gamma^ij p_i p_j) - beta^i p_i}. All spatial derivatives below
 * are analytic; no finite-difference epsilon or pole repair is hidden in the result.</p>
 */
final class KerrSchildEnvironment {
    static final double SPIN = 0.9;
    static final double OBSERVER_R = 50.0;
    static final double OBSERVER_THETA = Math.toRadians(60.0);
    static final double DISK_INNER_R = progradeIsco(SPIN);
    static final double DISK_OUTER_R = 12.0;
    /** Largest authored thickness is 2x the 0.55M baseline; leave interpolation margin around it. */
    static final double DISK_CAPTURE_HALF_HEIGHT = 1.2;

    private static final double HORIZON_R = 1.0 + Math.sqrt(1.0 - SPIN * SPIN);
    private static final double ESCAPE_R = 2000.0;
    private static final int MAX_STEPS = 4096;
    private static final double EPS = 1.0e-12;

    private static final Vec3 OBSERVER_POSITION = boyerLindquistPosition(
            OBSERVER_R, OBSERVER_THETA, 0.0);
    private static final Frame OBSERVER_FRAME = observerFrame();

    private KerrSchildEnvironment() {}

    static Trace trace(Vec3 canonicalDirection) {
        Vec3 canonical = canonicalDirection.normalized();
        Vec3 euclideanDirection = OBSERVER_FRAME.right.mul(canonical.x)
                .add(OBSERVER_FRAME.up.mul(canonical.y))
                .add(OBSERVER_FRAME.forward.mul(canonical.z)).normalized();

        Fields observer = fields(OBSERVER_POSITION);
        if (observer == null) return Trace.unresolved();
        double localNorm = Math.sqrt(euclideanDirection.dot(euclideanDirection)
                + 2.0 * observer.h * square(observer.l.dot(euclideanDirection)));
        Vec3 spatialDirection = euclideanDirection.mul(1.0 / localNorm);
        Vec3 momentum = spatialDirection.add(observer.l.mul(
                2.0 * observer.h * observer.l.dot(spatialDirection)));
        State state = new State(OBSERVER_POSITION, momentum);
        double energy = hamiltonian(observer, momentum);
        double angularMomentum = state.position.x * momentum.y - state.position.y * momentum.x;
        double lambda = angularMomentum / Math.max(energy, EPS);
        DiskChordBuilder disk = new DiskChordBuilder(energy, lambda);

        for (int step = 0; step < MAX_STEPS; step++) {
            Fields current = fields(state.position);
            if (current == null || !state.finite()) return Trace.unresolved();
            if (current.r <= HORIZON_R + 2.0e-4) {
                return Trace.capture(disk.finish());
            }
            Derivative first = derivative(state, current);
            if (first == null) return Trace.unresolved();
            if (current.r >= ESCAPE_R && state.position.dot(first.position) > 0.0) {
                Vec3 global = first.position.normalized();
                Vec3 escaped = new Vec3(
                        global.dot(OBSERVER_FRAME.right),
                        global.dot(OBSERVER_FRAME.up),
                        global.dot(OBSERVER_FRAME.forward)).normalized();
                return Trace.escape(escaped, disk.finish());
            }

            double timeStep = stepSize(current, state, first);
            State next = rk4(state, timeStep);
            if (next == null || !next.finite()) return Trace.unresolved();
            disk.accept(state.position, next.position);
            state = next;
        }
        return Trace.unresolved();
    }

    private static double stepSize(Fields fields, State state, Derivative derivative) {
        double step = clamp(0.025 * Math.max(fields.r, 1.0), 0.008, 4.0);
        if (fields.r < DISK_OUTER_R + 2.0 && Math.abs(state.position.z) < 2.0) {
            step = Math.min(step, 0.06);
        }
        // Do not jump across the horizon in one coarse far-field step.
        double radialSpeed = Math.abs(state.position.dot(derivative.position))
                / Math.max(state.position.length(), EPS);
        if (radialSpeed > EPS && fields.r < 4.0) {
            step = Math.min(step, 0.2 * Math.max(fields.r - HORIZON_R, 0.01) / radialSpeed);
        }
        return Math.max(step, 0.002);
    }

    private static State rk4(State state, double h) {
        Derivative k1 = derivative(state, fields(state.position));
        if (k1 == null) return null;
        State s2 = state.add(k1, h * 0.5);
        Derivative k2 = derivative(s2, fields(s2.position));
        if (k2 == null) return null;
        State s3 = state.add(k2, h * 0.5);
        Derivative k3 = derivative(s3, fields(s3.position));
        if (k3 == null) return null;
        State s4 = state.add(k3, h);
        Derivative k4 = derivative(s4, fields(s4.position));
        if (k4 == null) return null;
        return new State(
                state.position.add(k1.position.add(k2.position.mul(2.0))
                        .add(k3.position.mul(2.0)).add(k4.position).mul(h / 6.0)),
                state.momentum.add(k1.momentum.add(k2.momentum.mul(2.0))
                        .add(k3.momentum.mul(2.0)).add(k4.momentum).mul(h / 6.0)));
    }

    private static Derivative derivative(State state, Fields f) {
        if (f == null) return null;
        Vec3 p = state.momentum;
        double lp = f.l.dot(p);
        double q = p.dot(p) - f.b * lp * lp;
        if (!(q > EPS) || !Double.isFinite(q)) return null;
        double root = Math.sqrt(q);
        Vec3 gammaP = p.sub(f.l.mul(f.b * lp));
        Vec3 velocity = gammaP.mul(f.alpha / root).sub(f.l.mul(f.b));

        Vec3 gradLp = f.gradLx.mul(p.x).add(f.gradLy.mul(p.y)).add(f.gradLz.mul(p.z));
        Vec3 gradQ = f.gradB.mul(-lp * lp).add(gradLp.mul(-2.0 * f.b * lp));
        Vec3 gradRoot = gradQ.mul(0.5 / root);
        Vec3 gradHamiltonian = f.gradAlpha.mul(root).add(gradRoot.mul(f.alpha))
                .sub(f.gradB.mul(lp)).sub(gradLp.mul(f.b));
        Vec3 momentumRate = gradHamiltonian.mul(-1.0);
        if (!velocity.finite() || !momentumRate.finite()) return null;
        return new Derivative(velocity, momentumRate);
    }

    private static double hamiltonian(Fields f, Vec3 p) {
        double lp = f.l.dot(p);
        return f.alpha * Math.sqrt(Math.max(p.dot(p) - f.b * lp * lp, EPS)) - f.b * lp;
    }

    private static Fields fields(Vec3 position) {
        double x = position.x, y = position.y, z = position.z;
        double a2 = SPIN * SPIN;
        double radius2 = x * x + y * y + z * z;
        double q = radius2 - a2;
        double discriminant = Math.sqrt(Math.max(q * q + 4.0 * a2 * z * z, 0.0));
        double r2 = 0.5 * (q + discriminant);
        if (!(r2 > EPS) || !Double.isFinite(r2)) return null;
        double r = Math.sqrt(r2);

        double inverse2r = 0.5 / r;
        double safeDiscriminant = Math.max(discriminant, EPS);
        Vec3 gradR = new Vec3(
                x * (1.0 + q / safeDiscriminant) * inverse2r,
                y * (1.0 + q / safeDiscriminant) * inverse2r,
                z * (1.0 + (radius2 + a2) / safeDiscriminant) * inverse2r);

        double r3 = r2 * r;
        double r4 = r2 * r2;
        double hDenominator = r4 + a2 * z * z;
        if (!(hDenominator > EPS)) return null;
        Vec3 gradHDenominator = gradR.mul(4.0 * r3).add(new Vec3(0.0, 0.0, 2.0 * a2 * z));
        double h = r3 / hDenominator;
        Vec3 gradH = gradR.mul(3.0 * r2 / hDenominator)
                .sub(gradHDenominator.mul(r3 / square(hDenominator)));

        double lDenominator = r2 + a2;
        Vec3 gradLDenominator = gradR.mul(2.0 * r);
        double lNumeratorX = r * x + SPIN * y;
        double lNumeratorY = r * y - SPIN * x;
        Vec3 gradLNumeratorX = gradR.mul(x).add(new Vec3(r, SPIN, 0.0));
        Vec3 gradLNumeratorY = gradR.mul(y).add(new Vec3(-SPIN, r, 0.0));
        Vec3 gradLx = quotientGradient(lNumeratorX, gradLNumeratorX,
                lDenominator, gradLDenominator);
        Vec3 gradLy = quotientGradient(lNumeratorY, gradLNumeratorY,
                lDenominator, gradLDenominator);
        Vec3 gradLz = quotientGradient(z, new Vec3(0.0, 0.0, 1.0), r, gradR);
        Vec3 l = new Vec3(lNumeratorX / lDenominator, lNumeratorY / lDenominator, z / r);

        double onePlus2H = 1.0 + 2.0 * h;
        double b = 2.0 * h / onePlus2H;
        Vec3 gradB = gradH.mul(2.0 / square(onePlus2H));
        double alpha = 1.0 / Math.sqrt(onePlus2H);
        Vec3 gradAlpha = gradH.mul(-alpha * alpha * alpha);
        if (!Double.isFinite(h) || !l.finite() || !gradLx.finite() || !gradLy.finite()
                || !gradLz.finite()) return null;
        return new Fields(r, h, b, alpha, l, gradAlpha, gradB, gradLx, gradLy, gradLz);
    }

    private static Vec3 quotientGradient(double numerator, Vec3 gradNumerator,
                                         double denominator, Vec3 gradDenominator) {
        return gradNumerator.mul(denominator).sub(gradDenominator.mul(numerator))
                .mul(1.0 / square(denominator));
    }

    /**
     * Keeps the first contiguous local segment that can contain the maximum authored disk. The runtime
     * takes a short set of samples along this Kerr-derived chord, so changing radius/thickness changes
     * real support rather than multiplying a static picture. Later windings are deliberately ignored:
     * the front interval owns transmittance, while distinct higher-order images still arrive as distinct
     * screen directions and therefore receive their own first interval.
     */
    private static final class DiskChordBuilder {
        private final double energy;
        private final double lambda;
        private Vec3 entry;
        private Vec3 exit;
        private boolean closed;

        private DiskChordBuilder(double energy, double lambda) {
            this.energy = energy;
            this.lambda = lambda;
        }

        void accept(Vec3 start, Vec3 end) {
            if (closed) return;
            Vec3 midpoint = start.add(end).mul(0.5);
            Fields f = fields(midpoint);
            boolean inside = f != null
                    && f.r >= DISK_INNER_R - 0.5
                    && f.r <= DISK_OUTER_R + 0.5
                    && Math.abs(midpoint.z) <= DISK_CAPTURE_HALF_HEIGHT;
            if (inside) {
                if (entry == null) entry = start;
                exit = end;
            } else if (entry != null) {
                closed = true;
            }
        }

        DiskChord finish() {
            return entry == null || exit == null
                    ? DiskChord.NONE : new DiskChord(entry, exit, energy, lambda);
        }
    }

    private static Frame observerFrame() {
        Fields f = fields(OBSERVER_POSITION);
        Vec3 outward = f.l.normalized();
        Vec3 forward = outward.mul(-1.0);
        Vec3 up = new Vec3(0.0, 0.0, 1.0);
        up = up.sub(forward.mul(up.dot(forward))).normalized();
        Vec3 right = up.cross(forward).normalized();
        return new Frame(right, up, forward);
    }

    private static Vec3 boyerLindquistPosition(double r, double theta, double phi) {
        double sinTheta = Math.sin(theta);
        return new Vec3(
                (r * Math.cos(phi) - SPIN * Math.sin(phi)) * sinTheta,
                (r * Math.sin(phi) + SPIN * Math.cos(phi)) * sinTheta,
                r * Math.cos(theta));
    }

    private static double progradeIsco(double a) {
        double z1 = 1.0 + Math.cbrt(1.0 - a * a)
                * (Math.cbrt(1.0 + a) + Math.cbrt(1.0 - a));
        double z2 = Math.sqrt(3.0 * a * a + z1 * z1);
        return 3.0 + z2 - Math.sqrt((3.0 - z1) * (3.0 + z1 + 2.0 * z2));
    }

    private static double smoothstep(double lo, double hi, double value) {
        double x = clamp((value - lo) / (hi - lo), 0.0, 1.0);
        return x * x * (3.0 - 2.0 * x);
    }

    private static double clamp(double value, double lo, double hi) {
        return Math.max(lo, Math.min(hi, value));
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static Vec3 lerp(Vec3 a, Vec3 b, double t) {
        return a.mul(1.0 - t).add(b.mul(t));
    }

    private static double square(double value) {
        return value * value;
    }

    enum Kind { ESCAPE, CAPTURE, UNRESOLVED }

    record Trace(Kind kind, Vec3 escapedDirection, DiskChord diskChord) {
        static Trace escape(Vec3 direction, DiskChord diskChord) {
            return new Trace(Kind.ESCAPE, direction, diskChord);
        }

        static Trace capture(DiskChord diskChord) {
            return new Trace(Kind.CAPTURE, Vec3.ZERO, diskChord);
        }

        static Trace unresolved() {
            return new Trace(Kind.UNRESOLVED, Vec3.ZERO, DiskChord.NONE);
        }
    }

    record DiskChord(Vec3 entry, Vec3 exit, double energy, double lambda) {
        static final DiskChord NONE = new DiskChord(Vec3.ZERO, Vec3.ZERO, 0.0, 0.0);
        boolean present() { return energy > 0.0; }
    }

    record Vec3(double x, double y, double z) {
        static final Vec3 ZERO = new Vec3(0.0, 0.0, 0.0);

        Vec3 add(Vec3 other) { return new Vec3(x + other.x, y + other.y, z + other.z); }
        Vec3 sub(Vec3 other) { return new Vec3(x - other.x, y - other.y, z - other.z); }
        Vec3 mul(double scale) { return new Vec3(x * scale, y * scale, z * scale); }
        double dot(Vec3 other) { return x * other.x + y * other.y + z * other.z; }
        Vec3 cross(Vec3 other) {
            return new Vec3(y * other.z - z * other.y,
                    z * other.x - x * other.z,
                    x * other.y - y * other.x);
        }
        double length() { return Math.sqrt(dot(this)); }
        Vec3 normalized() { return mul(1.0 / Math.max(length(), EPS)); }
        boolean finite() { return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z); }
    }

    private record Frame(Vec3 right, Vec3 up, Vec3 forward) {}
    private record State(Vec3 position, Vec3 momentum) {
        State add(Derivative derivative, double scale) {
            return new State(position.add(derivative.position.mul(scale)),
                    momentum.add(derivative.momentum.mul(scale)));
        }
        boolean finite() { return position.finite() && momentum.finite(); }
    }
    private record Derivative(Vec3 position, Vec3 momentum) {}
    private record Fields(double r, double h, double b, double alpha, Vec3 l,
                          Vec3 gradAlpha, Vec3 gradB, Vec3 gradLx, Vec3 gradLy, Vec3 gradLz) {}
}
