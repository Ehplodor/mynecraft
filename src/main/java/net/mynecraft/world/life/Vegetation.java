package net.mynecraft.world.life;

import net.mynecraft.world.TerrainGenerator;

/**
 * The plants' own calendar at a given place. The year's seasons are the same everywhere, but a
 * mountain meadow wakes later and goes to sleep earlier than a valley floor, and a hot, dry plain
 * browns in high summer: growth, flowering and the colour of the year are read from the local
 * temperature (the climate of the column plus the season's swing) rather than from the date alone.
 *
 * <p>The growing season is the part of the year whose mean temperature is above {@link #GROW}. Its
 * start and end are where {@link Ecosystem#temperatureOffset} crosses that threshold; the
 * {@link #localPhase} of a place maps its growing season onto a temperate lowland's, so the
 * colour of spring, autumn and winter can be drawn with one calendar.
 */
public final class Vegetation {
    /** Mean air temperature (deg C) above which plants grow. */
    public static final double GROW = 5;
    /** Climate of a temperate lowland meadow, whose calendar the colours are drawn for. */
    static final double REFERENCE = 6;
    /** Lapse rate (deg C per block above the sea), as in the ecology and the atmosphere. */
    private static final double LAPSE = 0.0065;
    /** Half the growing season (fraction of the year) is kept within these bounds. */
    private static final double MIN_HALF = 0.08, MAX_HALF = 0.42;
    private static final double REF_START, REF_END;

    static {
        double[] r = growingSeason(REFERENCE);
        REF_START = r[0];
        REF_END = r[1];
    }

    private Vegetation() {}

    /** Climate of a column (deg C before the season) from the generator's temperature 0..1 and the ground height. */
    public static double climate(float temperature, int height) {
        return -14 + 40 * temperature - LAPSE * Math.max(0, height - TerrainGenerator.SEA_LEVEL);
    }

    /** Air temperature at a place with this climate at this moment of the year (deg C). */
    public static double celsius(double climate, double phase) {
        return climate + Ecosystem.temperatureOffset(phase);
    }

    /**
     * Phases of the year (0..1, may run below 0 or above 1) at which growth starts and stops here.
     * Warm places grow nearly all year, the cold heights only for a short summer.
     */
    public static double[] growingSeason(double climate) {
        // temperatureOffset = 9 cos(2 pi (phase - 0.375)) - 2, so climate + offset = GROW at cos = x.
        double x = (GROW - climate + 2) / 9;
        double half = x <= -1 ? MAX_HALF : x >= 1 ? MIN_HALF : Math.acos(x) / (2 * Math.PI);
        half = Math.max(MIN_HALF, Math.min(MAX_HALF, half));
        return new double[] {0.375 - half, 0.375 + half};
    }

    /**
     * The moment of the year as a lowland meadow would live it: the local growing season is
     * stretched or squeezed onto the reference one, and the dormant season onto the reference winter.
     */
    public static double localPhase(double climate, double phase) {
        double[] g = growingSeason(climate);
        double start = g[0], end = g[1], length = end - start;
        double p = wrap(phase - start); // 0 at the local start of growth
        double out;
        if (p < length) out = REF_START + p / length * (REF_END - REF_START);
        else out = REF_END + (p - length) / (1 - length) * (1 - (REF_END - REF_START));
        return wrap(out);
    }

    /** How much a hot, dry summer holds the grass back (0 none .. 1 parched). */
    public static double drought(double climate, double rain, double phase) {
        double heat = smooth(20, 30, celsius(climate, phase));
        return heat * clamp((0.55 - rain) / 0.4);
    }

    /** How fast plants grow here and now (0 dormant .. 1 full growth): warmth, less in a summer drought. */
    public static double growth(double climate, double rain, double phase) {
        return smooth(GROW - 3, GROW + 5, celsius(climate, phase)) * (1 - 0.7 * drought(climate, rain, phase));
    }

    /**
     * Flowering here (0..1): late spring and early summer of the local calendar, so poppies open in
     * May in the valleys and in high summer on the mountain pastures.
     */
    public static double bloom(double climate, double phase) {
        // As long after the start of growth as in the meadow (whose flowers peak at 0.3), within a short summer.
        double[] g = growingSeason(climate);
        double length = g[1] - g[0];
        double centre = Math.min(0.3 - REF_START, 0.6 * length), width = Math.min(0.1, 0.35 * length);
        double d = wrap(phase - g[0] - centre);
        d = Math.min(d, 1 - d);
        return Math.exp(-d * d / (2 * width * width));
    }

    static double wrap(double phase) {
        return ((phase % 1.0) + 1.0) % 1.0;
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }

    /** 0 below {@code a}, 1 above {@code b}, smooth in between. */
    private static double smooth(double a, double b, double v) {
        double t = clamp((v - a) / (b - a));
        return t * t * (3 - 2 * t);
    }
}
