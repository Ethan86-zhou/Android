package com.agm.blank;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Regression tests for independent gyro integration preserved during the Gradle migration. */
public class GyroHeadingTest {
    private static final float TOLERANCE = 0.0001f;
    private static final long START = 1_000_000_000L;
    private static final long SAMPLE_INTERVAL = 10_000_000L;
    private static final float QUARTER_TURN_PER_SECOND = (float) (Math.PI / 2);

    private static float[] identity() {
        return new float[] {1, 0, 0, 0, 1, 0, 0, 0, 1};
    }

    private static void integrateOneSecond(GyroHeading gyro, float x, float y, float z,
                                           long start) {
        for (int sample = 1; sample <= 100; sample++) {
            gyro.update(x, y, z, start + sample * SAMPLE_INTERVAL);
        }
    }

    @Test
    public void constantZRateIntegratesToNinetyDegreeRotation() {
        GyroHeading gyro = new GyroHeading();
        gyro.align(identity(), START);

        integrateOneSecond(gyro, 0, 0, QUARTER_TURN_PER_SECOND, START);

        assertArrayEquals(new float[] {0, -1, 0, 1, 0, 0, 0, 0, 1},
                gyro.matrix(), TOLERANCE);
    }

    @Test
    public void successiveLocalAxisRotationsComposeInDeviceCoordinates() {
        GyroHeading gyro = new GyroHeading();
        gyro.align(identity(), START);

        integrateOneSecond(gyro, 0, 0, QUARTER_TURN_PER_SECOND, START);
        integrateOneSecond(gyro, QUARTER_TURN_PER_SECOND, 0, 0,
                START + 1_000_000_000L);

        assertArrayEquals(new float[] {0, 0, 1, 1, 0, 0, 0, 1, 0},
                gyro.matrix(), TOLERANCE);
    }

    @Test
    public void stationaryGyroKeepsItsCopiedSeedWhenMagneticSourceChanges() {
        GyroHeading gyro = new GyroHeading();
        float[] magneticSeed = {0, -1, 0, 1, 0, 0, 0, 0, 1};
        gyro.align(magneticSeed, START);

        magneticSeed[0] = 1;
        gyro.update(0, 0, 0, START + SAMPLE_INTERVAL);

        assertArrayEquals(new float[] {0, -1, 0, 1, 0, 0, 0, 0, 1},
                gyro.matrix(), TOLERANCE);
    }

    @Test
    public void samplingGapInvalidatesIntegrationAndAllowsRealignment() {
        GyroHeading gyro = new GyroHeading();
        gyro.align(new float[] {0, -1, 0, 1, 0, 0, 0, 0, 1}, START);
        gyro.update(0, 0, 0, START + SAMPLE_INTERVAL);

        gyro.update(0, 0, 1, START + 1_000_000_000L);
        assertFalse("A long sampling gap must invalidate integration", gyro.initialized);

        gyro.align(identity(), START + 2_000_000_000L);
        assertTrue(gyro.initialized);
        assertArrayEquals(identity(), gyro.matrix(), TOLERANCE);
    }

    @Test
    public void zeroStartsAtIdentityAndStaysThereWithoutRotation() {
        GyroHeading gyro = new GyroHeading();
        gyro.zero(START);

        gyro.update(0, 0, 0, START + SAMPLE_INTERVAL);

        assertTrue(gyro.initialized);
        assertArrayEquals(identity(), gyro.matrix(), TOLERANCE);
    }

    @Test
    public void zeroRestoresRelativeOriginAfterThreeAxisMotion() {
        GyroHeading gyro = new GyroHeading();
        gyro.zero(START);
        gyro.update(0.5f, -0.2f, 0.3f, START + SAMPLE_INTERVAL);

        gyro.zero(START + 2 * SAMPLE_INTERVAL);

        assertArrayEquals(identity(), gyro.matrix(), TOLERANCE);
        gyro.update(0, 0, 0, START + 3 * SAMPLE_INTERVAL);
        assertArrayEquals(identity(), gyro.matrix(), TOLERANCE);
    }

    @Test
    public void relativeAircraftOriginIsIndependentOfMagneticHeadingSeed() {
        GyroHeading heading = new GyroHeading();
        GyroHeading aircraft = new GyroHeading();
        heading.align(new float[] {1, 0, 0, 0, 0, -1, 0, 1, 0}, START);
        aircraft.zero(START);

        heading.update(0, 0, 0, START + SAMPLE_INTERVAL);
        aircraft.update(0, 0, 0, START + SAMPLE_INTERVAL);

        assertArrayEquals(identity(), aircraft.matrix(), TOLERANCE);
        assertArrayEquals(new float[] {1, 0, 0, 0, 0, -1, 0, 1, 0},
                heading.matrix(), TOLERANCE);

        aircraft.update(0.5f, -0.2f, 0.3f, START + 2 * SAMPLE_INTERVAL);
        aircraft.zero(START + 3 * SAMPLE_INTERVAL);
        assertArrayEquals(identity(), aircraft.matrix(), TOLERANCE);
        assertArrayEquals(new float[] {1, 0, 0, 0, 0, -1, 0, 1, 0},
                heading.matrix(), TOLERANCE);
    }
}
