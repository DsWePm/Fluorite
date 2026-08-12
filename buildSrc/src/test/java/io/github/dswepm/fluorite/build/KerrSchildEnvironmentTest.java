package io.github.dswepm.fluorite.build;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class KerrSchildEnvironmentTest {
    private static final int WIDTH = 2048;
    private static final int HEIGHT = 1024;

    @Test
    void weakLensingIsContinuousAcrossTheOldPolarAxisSeam() {
        int left = WIDTH / 2 - 1;
        int right = WIDTH / 2;
        for (int y : new int[]{64, 96, 128, 160, 192}) {
            KerrSchildEnvironment.Trace a = KerrSchildEnvironment.trace(direction(left, y));
            KerrSchildEnvironment.Trace b = KerrSchildEnvironment.trace(direction(right, y));
            assertEquals(KerrSchildEnvironment.Kind.ESCAPE, a.kind(), "left y=" + y);
            assertEquals(KerrSchildEnvironment.Kind.ESCAPE, b.kind(), "right y=" + y);
            double angle = Math.acos(clamp(a.escapedDirection().dot(b.escapedDirection()), -1.0, 1.0));
            assertTrue(angle < Math.toRadians(2.0),
                    "Adjacent rays diverged by " + Math.toDegrees(angle) + " degrees at y=" + y);
        }
    }

    @Test
    void representativeTracesAndDiskChordsStayFinite() {
        int escaped = 0;
        for (int y : new int[]{128, 384, 512, 640, 896}) {
            for (int x : new int[]{128, 512, 1024, 1536, 1920}) {
                KerrSchildEnvironment.Trace trace = KerrSchildEnvironment.trace(direction(x, y));
                KerrSchildEnvironment.DiskChord chord = trace.diskChord();
                assertTrue(chord.entry().finite());
                assertTrue(chord.exit().finite());
                assertTrue(Double.isFinite(chord.energy()));
                assertTrue(Double.isFinite(chord.lambda()));
                if (chord.present()) {
                    double length = chord.exit().sub(chord.entry()).length();
                    assertTrue(length > 0.0 && length < 30.0,
                            "Unexpected disk chord length " + length + " at " + x + "," + y);
                }
                if (trace.kind() == KerrSchildEnvironment.Kind.ESCAPE) {
                    escaped++;
                    assertTrue(Math.abs(trace.escapedDirection().length() - 1.0) < 1.0e-8);
                }
            }
        }
        assertTrue(escaped > 15, "Most representative rays should escape the compact lens");
    }

    private static KerrSchildEnvironment.Vec3 direction(int x, int y) {
        double theta = Math.PI * (y + 0.5) / HEIGHT;
        double phi = 2.0 * Math.PI * ((x + 0.5) / WIDTH - 0.5);
        double radial = Math.sin(theta);
        return new KerrSchildEnvironment.Vec3(
                radial * Math.sin(phi),
                Math.cos(theta),
                radial * Math.cos(phi));
    }

    private static double clamp(double value, double lo, double hi) {
        return Math.max(lo, Math.min(hi, value));
    }
}
