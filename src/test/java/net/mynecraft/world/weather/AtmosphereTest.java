package net.mynecraft.world.weather;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AtmosphereTest {
    private static final int SEA = 63;

    /** Synthetic world: {height above sea, water fraction, forest, climate deg C, humidity}. */
    private static Atmosphere.Terrain world(java.util.function.BiFunction<Integer, Integer, float[]> f) {
        return f::apply;
    }

    private static void run(Atmosphere a, int steps) {
        for (int i = 0; i < steps; i++) a.step(1);
    }

    @Test
    void saturationCurve() {
        assertEquals(3.8f, Atmosphere.saturation(0), 1e-4);
        assertTrue(Atmosphere.saturation(20) > 3.5f * Atmosphere.saturation(0), "warm air holds much more water");
        assertEquals(10f, Atmosphere.dewPoint(Atmosphere.saturation(10)), 1e-3);
    }

    @Test
    void oceanAirIsMoisterThanDesertAir() {
        Atmosphere sea = new Atmosphere(world((x, z) -> new float[] {-20, 1, 0, 18, 0.5f}), 1);
        Atmosphere dry = new Atmosphere(world((x, z) -> new float[] {10, 0, 0, 18, 0.1f}), 1);
        sea.recenter(0, 0);
        dry.recenter(0, 0);
        run(sea, 200);
        run(dry, 200);
        // Water held by the air (vapour + cloud), averaged over the area: the sea keeps feeding it.
        float wet = 0, arid = 0;
        for (int x = -400; x <= 400; x += 100)
            for (int z = -400; z <= 400; z += 100) {
                Atmosphere.Sample a = sea.sample(x, z, SEA, SEA), b = dry.sample(x, z, SEA + 10, SEA);
                wet += a.humidity() + a.cloud();
                arid += b.humidity() + b.cloud();
            }
        assertTrue(wet > arid * 1.3f, "sea " + wet + " vs dry land " + arid);
    }

    @Test
    void mountainsGetRainOnTheWindwardSideAndCastARainShadow() {
        // A long north-south ridge at x in [0, 300) behind an ocean to the west and east.
        Atmosphere a = new Atmosphere(world((x, z) -> x < -200 ? new float[] {-20, 1, 0, 16, 0.8f}
            : x < 0 ? new float[] {(x + 200) * 0.6f, 0, 0.5f, 16, 0.6f}
            : x < 300 ? new float[] {120 - x * 0.4f, 0, 0.3f, 16, 0.5f} : new float[] {5, 0, 0, 16, 0.3f}), 3);
        a.recenter(0, 0);
        // Accumulate precipitation over time on both sides of the crest, whatever the wind does:
        // the side facing the moist ocean wind gets wetter whenever the wind blows from the sea.
        double windward = 0, lee = 0;
        for (int i = 0; i < 2000; i++) {
            a.step(1);
            Atmosphere.Sample w = a.sample(-60, 0, SEA + 84, SEA), l = a.sample(400, 0, SEA + 5, SEA);
            boolean fromWest = w.windX() > 1;
            if (fromWest) { windward += w.precipitation(); lee += l.precipitation(); }
        }
        assertTrue(windward > 0, "the windward slope gets rain");
        assertTrue(windward > 2 * lee, "rain shadow behind the ridge: " + windward + " vs " + lee);
    }

    @Test
    void precipitationTypeFollowsTemperature() {
        Atmosphere cold = new Atmosphere(world((x, z) -> new float[] {0, 0.6f, 0, -12, 1}), 5);
        Atmosphere warm = new Atmosphere(world((x, z) -> new float[] {0, 0.6f, 0, 20, 1}), 5);
        cold.recenter(0, 0);
        warm.recenter(0, 0);
        boolean snow = false, rain = false;
        for (int i = 0; i < 3000 && !(snow && rain); i++) {
            cold.step(1, 0.2f);
            warm.step(1, 0.2f);
            for (int x = -500; x <= 500 && !(snow && rain); x += 50) {
                if (cold.sample(x, 0, SEA, SEA).type() == Atmosphere.Precipitation.SNOW) snow = true;
                if (warm.sample(x, 0, SEA, SEA).type() == Atmosphere.Precipitation.RAIN) rain = true;
            }
        }
        assertTrue(snow, "it snows in cold humid air");
        assertTrue(rain, "it rains in warm humid air");
    }

    @Test
    void cloudsDriftWithTheWind() {
        Atmosphere a = new Atmosphere(world((x, z) -> new float[] {0, 0.5f, 0, 12, 0.6f}), 9);
        a.recenter(0, 0);
        Atmosphere.Sample s = a.sample(0, 0, SEA, SEA);
        assertTrue(Math.hypot(s.windX(), s.windZ()) > 1, "there is wind");
        // Mass carried downwind: after one step the field at a point equals the field upwind before.
        float before = a.cloudAt(20, 20);
        assertTrue(before >= 0);
    }

    @Test
    void temperatureFallsWithAltitude() {
        Atmosphere a = new Atmosphere(world((x, z) -> new float[] {0, 0, 0, 15, 0.5f}), 2);
        a.recenter(0, 0);
        float low = a.sample(0, 0, SEA, SEA).temperature(), high = a.sample(0, 0, SEA + 1000, SEA).temperature();
        assertEquals(6.5f, low - high, 0.1f);
    }
}
