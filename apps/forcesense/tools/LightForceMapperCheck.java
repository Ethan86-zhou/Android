import com.forcesense.app.LightForceMapper;

/** Standalone checks: compile with LightForceMapper.java, then run this class. */
public final class LightForceMapperCheck {
    private static int checks;

    public static void main(String[] args) {
        LightForceMapper mapper = new LightForceMapper();
        expect(!mapper.hasBaseline(), "new mapper has no baseline");
        expectNaN(mapper.getBaselineLux(), "unavailable baseline is NaN");
        expectNaN(mapper.forceForLux(0.0f), "darkness before calibration is unavailable");
        expectNaN(mapper.forceForLux(100.0f), "light before calibration is unavailable");

        float[] invalidReadings = {
            Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -1.0f
        };
        for (float invalid : invalidReadings) {
            expect(!mapper.calibrate(invalid), "invalid baseline rejected: " + invalid);
            expectNaN(mapper.forceForLux(invalid), "invalid reading rejected: " + invalid);
        }
        expect(!mapper.calibrate(0.0f), "zero baseline rejected");
        expect(!mapper.calibrate(0.5f), "baseline below one lux rejected");
        expect(!mapper.hasBaseline(), "failed initial calibrations do not create a baseline");

        expect(mapper.calibrate(100.0f), "valid baseline accepted");
        expect(mapper.hasBaseline(), "successful calibration becomes available");
        expectClose(mapper.getBaselineLux(), 100.0f, "baseline saved");
        expectClose(mapper.forceForLux(100.0f), 0.0f, "unchanged light gives zero percent");
        expectClose(mapper.forceForLux(0.0f), 100.0f, "complete darkness gives full force");
        expectClose(mapper.forceForLux(50.0f), 50.0f, "half the baseline gives half force");
        expectClose(mapper.forceForLux(200.0f), 0.0f, "brighter light clamps at zero");
        expectClose(mapper.forceForLux(25.0f), 75.0f, "greater attenuation gives greater force");
        expectClose(mapper.forceForLux(25.0f), 75.0f, "held shadow does not change baseline");
        expectClose(mapper.getBaselineLux(), 100.0f, "reading preserves baseline");

        for (float invalid : invalidReadings) {
            expect(!mapper.calibrate(invalid), "invalid recalibration rejected: " + invalid);
            expectNaN(mapper.forceForLux(invalid), "invalid calibrated reading rejected: " + invalid);
        }
        expect(!mapper.calibrate(0.0f), "dark recalibration rejected");
        expectClose(mapper.getBaselineLux(), 100.0f, "failed recalibration preserves baseline");
        expect(mapper.calibrate(200.0f), "explicit recalibration accepted");
        expectClose(mapper.forceForLux(100.0f), 50.0f, "new baseline changes mapping");

        expect(mapper.calibrate(1.0f), "minimum baseline accepted");
        expectClose(mapper.forceForLux(0.5f), 50.0f, "minimum baseline maps correctly");
        expectClose(mapper.forceForLux(Float.MAX_VALUE), 0.0f, "extreme brightness stays finite");
        expect(mapper.calibrate(Float.MAX_VALUE), "extreme finite baseline accepted");
        expectClose(mapper.forceForLux(Float.MAX_VALUE / 2.0f), 50.0f,
                "extreme baseline maps without overflow");
        expectClose(mapper.forceForLux(0.0f), 100.0f, "extreme baseline handles darkness");

        System.out.println("PASS: " + checks + " LightForceMapper checks");
    }

    private static void expect(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void expectNaN(float actual, String message) {
        expect(Float.isNaN(actual), message + "; actual=" + actual);
    }

    private static void expectClose(float actual, float expected, String message) {
        expect(!Float.isNaN(actual) && !Float.isInfinite(actual)
                        && Math.abs(actual - expected) <= 0.0001f,
                message + "; expected=" + expected + ", actual=" + actual);
    }
}
