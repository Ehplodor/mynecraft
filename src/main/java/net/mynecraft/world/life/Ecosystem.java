package net.mynecraft.world.life;

import net.mynecraft.world.Landscape;

/**
 * The living conditions the creatures read: time of day, the season (a year of {@link #YEAR_DAYS}
 * days turning with the world's clock), how much food a place offers, how many of a species it
 * can feed. The spatial community model is {@link Ecology}.
 */
public final class Ecosystem {
    /** Days in a year (four seasons of 8 days), used once the seasons turn. */
    public static final int YEAR_DAYS = 32;

    /**
     * A moment of the year: {@code phase} 0..1 from the start of spring; vegetation growth
     * (0 bare winter .. 1 lush), whether animals raise young, and how much snow lies in the cold places.
     */
    public record Season(String name, double phase, float vegetation, boolean breeding, float snowline) {}

    /** Late spring, where a new world's year starts. */
    public static final Season LATE_SPRING = seasonAt(YEAR_DAYS * 0.18);

    private Season season = LATE_SPRING;
    /** 1 at noon, ~0 at night (same as the sky light multiplier, without the weather). */
    private float daylight = 1;
    private boolean turning = true;
    private double day = YEAR_DAYS * 0.18;

    /** Lets the seasons turn with the days (on by default; off keeps late spring). */
    public void setTurning(boolean on) {
        turning = on;
        if (!on) season = LATE_SPRING;
    }

    /**
     * Called once per frame with the game time. Day 0 of a world is the start of spring shifted to
     * late spring, so new worlds open green and young animals are about.
     */
    public void update(double ticks, float daylight, long dayTicks) {
        this.daylight = daylight;
        day = ticks / dayTicks + YEAR_DAYS * 0.18;
        if (turning) season = seasonAt(day);
    }

    /** The calendar day (fractional) the season is read from. */
    public double day() {
        return day;
    }

    /** Game ticks at which a season starts (0 spring .. 3 winter) in the given year, keeping the hour of {@code ticks}. */
    public static double ticksForSeason(int season, double ticks, long dayTicks) {
        double hour = ticks % dayTicks;
        double startDay = season * YEAR_DAYS / 4.0 + 2 - YEAR_DAYS * 0.18; // two days into it
        if (startDay < 0) startDay += YEAR_DAYS;
        return Math.floor(startDay) * dayTicks + hour;
    }

    /** Air temperature swing of the season (deg C): about +7 in midsummer, -11 in midwinter. */
    public static double temperatureOffset(double phase) {
        return 9 * Math.cos((phase - 0.375) * 2 * Math.PI) - 2;
    }

    public Season season() {
        return season;
    }

    public float daylight() {
        return daylight;
    }

    /** The season on a given day (fractional): smooth vegetation over the year, snowline lowest in winter. */
    public static Season seasonAt(double day) {
        double phase = ((day / YEAR_DAYS) % 1.0 + 1.0) % 1.0;
        String[] names = {"printemps", "été", "automne", "hiver"};
        String name = names[(int) (phase * 4) % 4];
        float vegetation = (float) (0.55 + 0.45 * Math.cos((phase - 0.3) * 2 * Math.PI));
        float snowline = (float) (0.2 + 0.25 * Math.max(0, -Math.cos((phase - 0.3) * 2 * Math.PI)));
        return new Season(name, phase, vegetation, phase < 0.4, snowline);
    }

    /** Whether a species is out at this hour. */
    public boolean active(Species s) {
        if (s == Species.MOSSBACK && season.phase() >= 0.75) return false; // hibernating
        return switch (s.rhythm) {
            case DAY -> daylight > 0.45f;
            case NIGHT -> daylight < 0.5f;
            case TWILIGHT -> daylight > 0.18f; // day, dawn and dusk; only deep night is spent resting
            case ALWAYS -> true;
        };
    }

    /** Ground covered by snow here (temperature 0..1): coats turn white above this. */
    public float snowCover(float temperature, byte biome) {
        if (biome == Landscape.SNOWY_PLAINS || biome == Landscape.SNOWY_TAIGA || biome == Landscape.SNOWY_PEAKS
            || biome == Landscape.GLACIER || biome == Landscape.FROZEN_RIVER || biome == Landscape.FROZEN_OCEAN) return 1;
        return temperature < season.snowline() ? 1 : 0;
    }

    /** Plant food a biome offers (0..1) at this season. */
    public float forage(byte biome) {
        return forageBase(biome) * season.vegetation();
    }

    /** Plant food a biome offers (0..1) at the height of the growing season. */
    public float forageBase(byte biome) {
        float base = switch (biome) {
            case Landscape.PLAINS, Landscape.FOREST, Landscape.BIRCH_FOREST -> 1.0f;
            case Landscape.DARK_FOREST, Landscape.TAIGA, Landscape.LAKE, Landscape.RIVER -> 0.8f;
            case Landscape.DRY_PLAINS, Landscape.PLATEAU, Landscape.SNOWY_TAIGA -> 0.55f;
            case Landscape.STONY_PEAKS, Landscape.SNOWY_PLAINS, Landscape.BEACH -> 0.35f;
            default -> 0.15f;
        };
        return base;
    }

    /**
     * How many of a species a disc of 64 blocks radius can hold here: the place's food times
     * the species' own density (big animals are fewer, prey more numerous than predators).
     */
    public float capacity(Species s, byte biome, float temperature) {
        if (!s.habitat.has(biome) || temperature < s.habitat.minTemp() - 0.05f || temperature > s.habitat.maxTemp() + 0.05f) return 0;
        float density = switch (s) {
            case RABBIT -> 7;
            case DEER -> 5;
            case TROUT -> 8;
            case IBEX -> 5;
            case FOX, HERON -> 2;
            case LANTERN -> 7;
            case FROSTLING -> 4;
            case MOSSBACK, SKY_MANTA -> 1;
        };
        float food = switch (s.diet) {
            case GRASS -> forage(biome);
            case MEAT, FISH -> 0.5f + 0.5f * season.vegetation();
            case NONE -> 1;
        };
        return density * food;
    }

    /** Hunger gained per second (a full day out of food takes ~8 minutes). */
    public double hungerRate(Species s) {
        return s.diet == Species.Diet.NONE ? 0 : 1 / 480.0;
    }
}
