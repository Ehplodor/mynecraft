package net.mynecraft.client.sound;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SynthTest {
    private static void sane(String name, float[] s) {
        for (float v : s) {
            assertFalse(Float.isNaN(v) || Float.isInfinite(v), name + ": not a number");
            assertTrue(Math.abs(v) <= 1, name + ": clipped");
        }
    }

    /** A loop must flow from its last sample into its first like from one sample to the next. */
    private static void seamless(String name, float[] s) {
        sane(name, s);
        double meanStep = 0;
        for (int i = 1; i < s.length; i++) meanStep += Math.abs(s[i] - s[i - 1]);
        meanStep /= s.length - 1;
        assertTrue(Math.abs(s[0] - s[s.length - 1]) < 4 * meanStep + 1e-4, name + ": clicks at the loop point");
    }

    @Test
    void loopsAreSeamless() {
        seamless("rain", Synth.rain(3, 60, 0.3, 1));
        seamless("heavy rain", Synth.rain(3, 260, 0.7, 2));
        seamless("roof", Synth.rainRoof(3, 3));
        seamless("hail", Synth.hail(3, 4));
        seamless("wind", Synth.wind(6, 5));
        seamless("leaves", Synth.leaves(4, 6));
        seamless("brook", Synth.brook(3, 7));
        seamless("river", Synth.river(3, 8));
        seamless("waterfall", Synth.waterfall(3, 9));
        seamless("surf", Synth.surf(8, 10));
        seamless("cave", Synth.cave(4, 11));
        seamless("crickets", Synth.crickets(3, 12));
        seamless("insects", Synth.insects(3, 13));
        seamless("frogs", Synth.frogs(3, 14));
    }

    @Test
    void oneShotsEndInSilenceAndAreRepeatable() {
        for (Synth.Material m : Synth.Material.values()) {
            for (float[] s : new float[][] {Synth.step(m, 1), Synth.breakBlock(m, 1), Synth.placeBlock(m, 1), Synth.hit(m, 1)}) {
                sane(m.name(), s);
                assertTrue(Math.abs(s[s.length - 1]) < 1e-3, m + ": cut off while still sounding");
            }
            assertArrayEquals(Synth.step(m, 5), Synth.step(m, 5), m + ": same seed, same sound");
        }
        sane("thunder", Synth.thunder(true, 1));
        sane("far thunder", Synth.thunder(false, 1));
        for (int k = 0; k < 6; k++) sane("bird " + k, Synth.bird(k, k));
        sane("owl", Synth.owl(1));
    }
}
