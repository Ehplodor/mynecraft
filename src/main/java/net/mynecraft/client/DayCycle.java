package net.mynecraft.client;

/** Vanilla-compatible time of day: 24000 ticks per day, 20 ticks per second. */
final class DayCycle {
    static final int DAY_TICKS = 24000;
    private static final float[] DAY_SKY = {0.55f, 0.75f, 0.95f}, NIGHT_SKY = {0.02f, 0.03f, 0.07f};

    private DayCycle() {}

    /** Sun height in [-1, 1]: 0 at sunrise (tick 0), 1 at noon (6000), -1 at midnight (18000). */
    static double sunHeight(double ticks) {
        return Math.sin((ticks % DAY_TICKS) / DAY_TICKS * 2 * Math.PI);
    }

    /** Sky light multiplier: full by day, ~15% at night, smooth around sunrise and sunset. */
    static float daylight(double ticks) {
        double f = Math.max(0, Math.min(1, sunHeight(ticks) * 2.5 + 0.5));
        return (float) (0.15 + 0.85 * f);
    }

    static float[] skyColor(double ticks, float[] out) {
        float d = (daylight(ticks) - 0.15f) / 0.85f;
        for (int i = 0; i < 3; i++) out[i] = NIGHT_SKY[i] + (DAY_SKY[i] - NIGHT_SKY[i]) * d;
        return out;
    }
}
