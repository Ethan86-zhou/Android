package com.forcesense.app;

/**
 * Maps ambient-light attenuation to an illustrative force percentage.
 *
 * <p>This is a simulation: light readings do not measure physical force or
 * sensor deformation. The baseline represents uncovered ambient light and
 * only changes when {@link #calibrate(float)} succeeds.</p>
 *
 * <p>A missing reading must be represented as {@link Float#NaN}, not zero.
 * Zero lux is a valid reading from a sensor and represents complete darkness.</p>
 */
public final class LightForceMapper {
    public static final float MIN_BASELINE_LUX = 1.0f;

    private float baselineLux = Float.NaN;

    /**
     * Saves an uncovered-light baseline of at least one lux.
     *
     * @return false for darkness, insufficient light, or an invalid reading;
     *         the previously calibrated baseline, if any, is preserved.
     */
    public boolean calibrate(float lux) {
        if (!isValidReading(lux) || lux < MIN_BASELINE_LUX) {
            return false;
        }
        baselineLux = lux;
        return true;
    }

    public boolean hasBaseline() {
        return !Float.isNaN(baselineLux);
    }

    /** @return the calibration baseline, or NaN before successful calibration. */
    public float getBaselineLux() {
        return baselineLux;
    }

    /**
     * Returns a simulated force percentage in [0, 100].
     *
     * <p>An unchanged or brighter light reading gives 0%; half the baseline
     * gives 50%; zero lux gives 100%. There is no automatic baseline tracking,
     * so holding a shadow in place does not reduce the simulated force.</p>
     *
     * @return NaN when calibration or a valid sensor reading is unavailable.
     */
    public float forceForLux(float lux) {
        if (!hasBaseline() || !isValidReading(lux)) {
            return Float.NaN;
        }
        // Double arithmetic avoids overflow even with extreme finite floats.
        double attenuation = 1.0 - (double) lux / baselineLux;
        return (float) (Math.max(0.0, Math.min(1.0, attenuation)) * 100.0);
    }

    private static boolean isValidReading(float lux) {
        return !Float.isNaN(lux) && !Float.isInfinite(lux) && lux >= 0.0f;
    }
}
