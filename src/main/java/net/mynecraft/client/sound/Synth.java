package net.mynecraft.client.sound;

import java.util.SplittableRandom;

import static net.mynecraft.client.sound.Dsp.*;

/**
 * Every sound of the game, synthesised from physical ingredients rather than raw noise: impacts
 * are a short transient plus damped modes, bubbles are rising, dying sines (Minnaert resonance),
 * rubbing and crunching are clouds of tiny filtered grains. Loops are rendered longer than their
 * length and cross-faded into themselves, with slow modulations in whole cycles per loop.
 * Pure Java (no audio API): sounds can be rendered to WAV and checked offline.
 */
public final class Synth {
    private Synth() {}

    // =====================================================================================
    // Rain, hail
    // =====================================================================================

    /**
     * Rain on open ground. A soft wash (distant drops merged: pink noise without its rumble and
     * its harshest top) plus individual drops near the listener: each a tick of bright noise, and
     * for drops landing in puddles a small bubble ringing 1.5-4 kHz. {@code density} drops per
     * second; heavy rain also gets a low body.
     */
    public static float[] rain(double seconds, double density, double wash, long seed) {
        SplittableRandom r = new SplittableRandom(seed);
        int len = samples(seconds), fade = samples(0.5), n = len + fade;
        float[] s = pink(n, r);
        highpass(s, 350);
        lowpass(s, 7500);
        scale(s, wash);
        if (density > 100) { // heavy rain: the dull roar of a downpour hitting everything at once
            float[] body = rumble(n, r, 120, 600);
            mix(s, body, 0, wash * 0.5);
        }
        int drops = (int) (density * seconds);
        for (int d = 0; d < drops; d++) {
            int at = r.nextInt(len);
            double dist = 0.3 + r.nextDouble();          // nearer drops louder and brighter
            double a = Math.exp(r.nextGaussian() * 0.6) * 0.18 / dist;
            double center = logUniform(r, 2500, 8000) / Math.sqrt(dist);
            float[] tick = burst(r, 0.006, center * 0.6, center * 1.6, 0.0002, 0.0012);
            mixWrapped(s, tick, at, a);
            if (r.nextDouble() < 0.35) { // into a puddle
                float[] b = bubble(logUniform(r, 1500, 4200), 0.004 + r.nextDouble() * 0.008, 0.4 + r.nextDouble());
                mixWrapped(s, b, at + samples(0.002), a * 0.35);
            }
        }
        return rms(loop(clean(s), len), 0.12);
    }

    /**
     * Rain heard from under a roof: the drops drum on the roof (low, dull thuds) and the outside
     * reaches us through walls, lowpassed.
     */
    public static float[] rainRoof(double seconds, long seed) {
        SplittableRandom r = new SplittableRandom(seed);
        int len = samples(seconds), fade = samples(0.5), n = len + fade;
        float[] s = rumble(n, r, 90, 1000);
        scale(s, 0.6);
        int drops = (int) (70 * seconds);
        for (int d = 0; d < drops; d++) {
            int at = r.nextInt(len);
            float[] thud = burst(r, 0.03, 120, 900, 0.0005, 0.006);
            mixWrapped(s, thud, at, Math.exp(r.nextGaussian() * 0.5) * 0.25);
        }
        return rms(loop(clean(s), len), 0.1);
    }

    /**
     * Hail: hard ice pellets. Each hit is a sharp click with a short glassy ring (2-6 kHz) and,
     * on the ground, a little thud; fewer events than rain but much louder, over a light hiss.
     */
    public static float[] hail(double seconds, long seed) {
        SplittableRandom r = new SplittableRandom(seed);
        int len = samples(seconds), fade = samples(0.4), n = len + fade;
        float[] s = highpass(pink(n, r), 1200);
        scale(s, 0.15);
        int hits = (int) (55 * seconds);
        for (int h = 0; h < hits; h++) {
            int at = r.nextInt(len);
            double a = Math.exp(r.nextGaussian() * 0.5) * 0.35 / (0.4 + r.nextDouble());
            mixWrapped(s, burst(r, 0.003, 2000, 0, 0.00005, 0.0006), at, a);
            mixWrapped(s, mode(logUniform(r, 2200, 6000), 0.006 + r.nextDouble() * 0.008, 0.05), at, a * 0.25);
            mixWrapped(s, burst(r, 0.02, 80, 600, 0.0003, 0.004), at, a * 0.4);
        }
        return rms(soft(loop(clean(s), len), 2.5), 0.16);
    }

    // =====================================================================================
    // Wind, leaves
    // =====================================================================================

    /**
     * Wind: deep noise through a band-pass whose centre and level follow gusts (several slow
     * sines, whole cycles per loop so it loops cleanly); in the strongest gusts a faint whistle
     * appears (a narrow resonance around 700-1000 Hz), and a high breath of air over the top.
     */
    public static float[] wind(double seconds, long seed) {
        SplittableRandom r = new SplittableRandom(seed);
        int len = samples(seconds), fade = samples(0.8), n = len + fade;
        float[] base = brown(n, r), air = white(n, r);
        Biquad body = Biquad.bandpass(400, 0.9), whistle = Biquad.bandpass(850, 14), hiss = Biquad.highpass(2500, 0.7);
        float[] s = new float[n];
        double p1 = r.nextDouble() * 6.28, p2 = r.nextDouble() * 6.28, p3 = r.nextDouble() * 6.28;
        for (int i = 0; i < n; i++) {
            double t = (i % len) / (double) len; // phase in the loop: modulations repeat exactly
            double gust = 0.55 + 0.25 * Math.sin(2 * Math.PI * 1 * t + p1) + 0.15 * Math.sin(2 * Math.PI * 3 * t + p2)
                + 0.08 * Math.sin(2 * Math.PI * 7 * t + p3);
            if (i % 64 == 0) {
                body.set(2, 260 + 520 * gust * gust, 0.9);
                whistle.set(2, 700 + 350 * gust, 14);
            }
            double w = body.process(base[i]) * gust;
            w += whistle.process(air[i]) * Math.max(0, gust - 0.7) * 1.4;
            w += hiss.process(air[i]) * 0.04 * gust;
            s[i] = (float) w;
        }
        return rms(loop(clean(s), len), 0.12);
    }

    /**
     * Leaves in the wind: a crisp rustle made of many short grains of high noise whose density
     * swells and falls with the gusts (same modulation idea as the wind).
     */
    public static float[] leaves(double seconds, long seed) {
        SplittableRandom r = new SplittableRandom(seed);
        int len = samples(seconds), fade = samples(0.5), n = len + fade;
        float[] s = new float[n];
        double p = r.nextDouble() * 6.28;
        for (int i = 0; i < len; i += samples(0.002)) {
            double t = i / (double) len;
            double gust = 0.55 + 0.3 * Math.sin(2 * Math.PI * 2 * t + p) + 0.12 * Math.sin(2 * Math.PI * 5 * t); // never below ~0.13
            if (r.nextDouble() > gust * 0.9) continue;
            float[] g = burst(r, 0.025, logUniform(r, 1800, 5000), 9000, 0.002, 0.004 + r.nextDouble() * 0.006);
            mixWrapped(s, g, i, 0.3 * gust * (0.5 + r.nextDouble()));
        }
        return rms(loop(clean(s), len), 0.08);
    }

    // =====================================================================================
    // Running water, sea
    // =====================================================================================

    /**
     * A brook: the sound of shallow running water is mostly bubbles: a dense cloud of small
     * Minnaert resonances (400-2500 Hz, rising chirps) over a light, bright wash.
     */
    public static float[] brook(double seconds, long seed) {
        SplittableRandom r = new SplittableRandom(seed);
        int len = samples(seconds), fade = samples(0.4), n = len + fade;
        float[] s = highpass(pink(n, r), 900);
        scale(s, 0.1);
        int bubbles = (int) (140 * seconds);
        for (int b = 0; b < bubbles; b++) {
            double f = logUniform(r, 420, 2600);
            float[] bb = bubble(f, 0.006 + 6 / f * r.nextDouble(), 0.2 + r.nextDouble() * 0.8);
            mixWrapped(s, bb, r.nextInt(len), Math.exp(r.nextGaussian() * 0.5) * 0.12);
        }
        return rms(loop(clean(s), len), 0.11);
    }

    /**
     * A river: a fuller rush (pink noise rolled off above 1.5 kHz), a low rumble of the current,
     * and fewer, deeper bubbles; the level breathes slightly.
     */
    public static float[] river(double seconds, long seed) {
        SplittableRandom r = new SplittableRandom(seed);
        int len = samples(seconds), fade = samples(0.5), n = len + fade;
        float[] s = rumble(n, r, 120, 1600);
        mix(s, rumble(n, r, 60, 260), 0, 0.6); // the current's low rumble
        int bubbles = (int) (60 * seconds);
        for (int b = 0; b < bubbles; b++) {
            double f = logUniform(r, 200, 950);
            mixWrapped(s, bubble(f, 0.01 + r.nextDouble() * 0.02, 0.3 + r.nextDouble() * 0.5), r.nextInt(len),
                Math.exp(r.nextGaussian() * 0.4) * 0.1);
        }
        double p = r.nextDouble() * 6.28;
        for (int i = 0; i < n; i++) s[i] *= (float) (0.9 + 0.1 * Math.sin(2 * Math.PI * 2 * (i % len) / len + p));
        return rms(loop(clean(s), len), 0.12);
    }

    /**
     * A waterfall: broadband roar (water crashing on water and rock), heavy low end, the top
     * gently rolled off, plus a dense bed of splashes.
     */
    public static float[] waterfall(double seconds, long seed) {
        SplittableRandom r = new SplittableRandom(seed);
        int len = samples(seconds), fade = samples(0.4), n = len + fade;
        float[] s = lowpass(highpass(white(n, r), 400), 6500);
        scale(s, 0.35);
        mix(s, rumble(n, r, 150, 1500), 0, 1.0);
        mix(s, rumble(n, r, 55, 220), 0, 0.9); // the plunge pool's roar
        for (int b = 0; b < (int) (90 * seconds); b++)
            mixWrapped(s, burst(r, 0.04, 600, 4000, 0.002, 0.01), r.nextInt(len), 0.15 * r.nextDouble());
        return rms(loop(clean(s), len), 0.15);
    }

    /**
     * Surf on a shore: two waves per loop. Each swells (noise with a rising low-pass as the wave
     * builds), breaks (a broad crash), then the foam fizzes away (high hiss and small bubbles
     * fading over seconds) while the backwash drags gravel.
     */
    public static float[] surf(double seconds, long seed) {
        SplittableRandom r = new SplittableRandom(seed);
        int len = samples(seconds), fade = samples(0.8), n = len + fade;
        float[] s = new float[n];
        float[] noise = highpass(pink(n, r), 70), hissNoise = white(n, r);
        Biquad sweep = Biquad.lowpass(300, 0.8), foam = Biquad.highpass(1800, 0.7);
        double[] breaks = {seconds * 0.22, seconds * 0.71}; // crash times
        for (int i = 0; i < n; i++) {
            double t = (i % len) / (double) RATE;
            double swell = 0, fizz = 0;
            for (double b : breaks) {
                double dt = t - b;
                if (dt < 0 && dt > -1.6) swell = Math.max(swell, Math.pow(1 + dt / 1.6, 2));       // building
                if (dt >= 0 && dt < 4) {
                    swell = Math.max(swell, Math.exp(-dt / 0.5));                                 // crash
                    fizz = Math.max(fizz, Math.min(1, dt / 0.15) * Math.exp(-dt / 1.6));          // foam
                }
                double dtw = dt + seconds; // the previous loop's wave still fizzing
                if (dtw >= 0 && dtw < 4) fizz = Math.max(fizz, Math.exp(-dtw / 1.6));
            }
            if (i % 64 == 0) sweep.set(0, 250 + 2600 * swell, 0.8);
            s[i] = (float) (sweep.process(noise[i]) * (0.15 + swell) + foam.process(hissNoise[i]) * fizz * 0.25);
        }
        for (double b : breaks) // foam bubbles
            for (int k = 0; k < 500; k++) {
                double at = b + 0.1 + Math.pow(r.nextDouble(), 2) * 3;
                mixWrapped(s, bubble(logUniform(r, 900, 4000), 0.003 + r.nextDouble() * 0.006, 0.5), samples(at), 0.05 * r.nextDouble());
            }
        return rms(loop(clean(s), len), 0.12);
    }

    // =====================================================================================
    // Places and creatures
    // =====================================================================================

    /** The air of a cave: a very low, slowly breathing hum (room tone of a large cavity). */
    public static float[] cave(double seconds, long seed) {
        SplittableRandom r = new SplittableRandom(seed);
        int len = samples(seconds), fade = samples(1), n = len + fade;
        float[] s = rumble(n, r, 50, 220);
        float[] hum = bandpass(white(n, r), 110, 8); // the cavity's resonance
        mix(s, hum, 0, 0.6);
        mix(s, bandpass(white(n, r), 230, 10), 0, 0.25);
        for (int i = 0; i < n; i++) s[i] *= (float) (0.8 + 0.2 * Math.sin(2 * Math.PI * (i % len) / len));
        return rms(loop(clean(s), len), 0.08);
    }

    /**
     * Crickets at night: a field of insects, each with its own pitch (4-5.2 kHz) chirping in
     * little pulse trains (3-5 pulses, 20 ms apart) at its own unsteady rhythm.
     */
    public static float[] crickets(double seconds, long seed) {
        SplittableRandom r = new SplittableRandom(seed);
        int len = samples(seconds), fade = samples(0.3), n = len + fade;
        float[] s = highpass(pink(n, r), 3000);
        scale(s, 0.02);
        for (int c = 0; c < 9; c++) {
            double f = 4000 + r.nextDouble() * 1200, period = 0.45 + r.nextDouble() * 0.7;
            double gain = 0.04 + 0.12 * Math.pow(r.nextDouble(), 2); // most far, a few close
            int pulses = 3 + r.nextInt(3);
            for (double t = r.nextDouble() * period; t < seconds; t += period * (0.9 + r.nextDouble() * 0.2)) {
                for (int p = 0; p < pulses; p++) {
                    float[] pulse = mode(f, 0.004, 0.016);
                    shape(pulse, x -> Math.min(1, x / 0.002));
                    mixWrapped(s, pulse, samples(t + p * 0.021), gain);
                }
            }
        }
        return rms(loop(clean(s), len), 0.06);
    }

    /** One bird call; {@code kind} 0..5 picks a song type, the seed varies it. */
    public static float[] bird(int kind, long seed) {
        SplittableRandom r = new SplittableRandom(seed);
        return switch (kind % 6) {
            case 0 -> song(r, 4 + r.nextInt(3), 3800, -0.35, 0.09, 0.05);   // descending whistles
            case 1 -> trill(r);                                             // fast trill
            case 2 -> twoTone(r);                                          // soft two notes (dove-like)
            case 3 -> warble(r);                                           // wandering warble
            case 4 -> song(r, 3, 5200, 0.6, 0.035, 0.07);                  // short rising chips
            default -> song(r, 2 + r.nextInt(2), 2600, -0.15, 0.22, 0.12); // long clear whistles
        };
    }

    /** A note: a sine following a frequency path f(t), with a smooth (sin^2) envelope and a hint of second harmonic. */
    private static float[] note(java.util.function.DoubleUnaryOperator f, double seconds, double harmonic) {
        int n = samples(seconds);
        float[] out = new float[n];
        double phase = 0;
        for (int i = 0; i < n; i++) {
            double t = i / (double) RATE;
            phase += 2 * Math.PI * f.applyAsDouble(t) / RATE;
            double env = Math.pow(Math.sin(Math.PI * t / seconds), 2);
            out[i] = (float) ((Math.sin(phase) + harmonic * Math.sin(2 * phase)) * env);
        }
        return out;
    }

    private static float[] song(SplittableRandom r, int notes, double f0, double slide, double length, double gap) {
        float[] s = new float[samples(notes * (length + gap) + 0.1)];
        double f = f0 * (0.9 + r.nextDouble() * 0.2);
        for (int k = 0; k < notes; k++) {
            double start = f, len = length * (0.85 + r.nextDouble() * 0.3);
            mix(s, note(t -> start * (1 + slide * t / len), len, 0.12), samples(k * (length + gap)), 0.8);
            f *= 0.93 + r.nextDouble() * 0.05; // each note a little lower
        }
        return normalize(s, 0.8);
    }

    private static float[] trill(SplittableRandom r) {
        double f = 3500 + r.nextDouble() * 1500, rate = 14 + r.nextDouble() * 8, seconds = 0.7 + r.nextDouble() * 0.6;
        float[] s = note(t -> f * (1 + 0.12 * Math.sin(2 * Math.PI * rate * t)), seconds, 0.08);
        shape(s, t -> 0.5 + 0.5 * Math.sin(2 * Math.PI * rate * t)); // the trill's beats
        return normalize(s, 0.75);
    }

    private static float[] twoTone(SplittableRandom r) {
        double f = 550 + r.nextDouble() * 150;
        float[] s = new float[samples(1.1)];
        mix(s, note(t -> f * (1 + 0.04 * t), 0.35, 0.3), 0, 0.9);
        mix(s, note(t -> f * 0.8, 0.5, 0.3), samples(0.45), 0.8);
        return normalize(lowpass(s, 2500), 0.7);
    }

    private static float[] warble(SplittableRandom r) {
        double seconds = 1 + r.nextDouble() * 0.6, base = 2800 + r.nextDouble() * 800;
        double[] path = new double[24];
        for (int i = 0; i < path.length; i++) path[i] = base * (0.8 + r.nextDouble() * 0.5);
        float[] s = note(t -> {
            double x = t / seconds * (path.length - 1);
            int i = Math.min(path.length - 2, (int) x);
            double u = x - i, sm = u * u * (3 - 2 * u);
            return path[i] + (path[i + 1] - path[i]) * sm;
        }, seconds, 0.1);
        shape(s, t -> 0.6 + 0.4 * Math.sin(2 * Math.PI * 9 * t));
        return normalize(s, 0.7);
    }

    /**
     * A drop falling into a pool in a cave: a tiny click then a bright bubble "plink" that rises
     * fast; a few faint, darker echoes are baked in for when there is no reverb.
     */
    public static float[] drip(long seed) {
        SplittableRandom r = new SplittableRandom(seed);
        float[] s = new float[samples(0.7)];
        float[] plink = bubble(logUniform(r, 900, 2200), 0.012 + r.nextDouble() * 0.01, 1.5 + r.nextDouble());
        mix(s, burst(r, 0.002, 3000, 0, 0.00005, 0.0004), 0, 0.3);
        mix(s, plink, samples(0.001), 1);
        float[] echo = lowpass(plink.clone(), 1500);
        mix(s, echo, samples(0.13), 0.25);
        mix(s, echo, samples(0.29), 0.12);
        mix(s, echo, samples(0.47), 0.06);
        return normalize(s, 0.8);
    }

    // =====================================================================================
    // Thunder
    // =====================================================================================

    /**
     * Thunder. A bolt is kilometres of channel: each piece's crack arrives at a different time
     * (longer paths later and duller, as air absorbs the highs), which makes the rumble. Near:
     * a sharp N-wave crack, a tearing rip over half a second, then a rolling rumble with bumps.
     * Far: only the low rumble, rising softly.
     */
    public static float[] thunder(boolean near, long seed) {
        SplittableRandom r = new SplittableRandom(seed);
        double seconds = near ? 6.5 : 7.5;
        int n = samples(seconds);
        float[] s = new float[n];
        if (near) {
            // N-wave: a sharp positive then negative pressure pulse (a few ms), then the rip.
            for (int i = 0; i < samples(0.006); i++) s[i] += (float) (i < samples(0.003) ? 1 : -1) * 0.9f;
            float[] rip = white(samples(0.7), r);
            highpass(rip, 400);
            for (int i = 0; i < rip.length; i++) {
                double t = i / (double) RATE;
                double crackle = r.nextDouble() < 0.02 * Math.exp(-t / 0.25) ? 3 : 1; // jagged
                rip[i] *= (float) (crackle * Math.exp(-t / 0.18) * 0.6);
            }
            mix(s, rip, samples(0.004), 1);
        }
        // Rumble: brown noise shaped by bumps (segments arriving), through a closing low-pass.
        float[] body = rumble(n, r, 35, 2500);
        double[] bumps = new double[10 + r.nextInt(6)];
        for (int b = 0; b < bumps.length; b++) bumps[b] = (near ? 0.15 : 0.5) + Math.pow(r.nextDouble(), 1.6) * (seconds - 2);
        Biquad lp = Biquad.lowpass(near ? 1400 : 450, 0.7);
        for (int i = 0; i < n; i++) {
            double t = i / (double) RATE;
            double env = 0;
            for (double b : bumps) env += Math.exp(-Math.pow((t - b) / (0.25 + 0.15 * b), 2)) * (1.2 / (1 + b * 0.4));
            env *= Math.min(1, t / (near ? 0.05 : 0.6)) * Math.exp(-Math.max(0, t - seconds + 2.5) / 0.8);
            if (i % 64 == 0) lp.set(0, (near ? 1300 : 380) * Math.exp(-t / 2.2) + 140, 0.7); // air dulls the later, farther parts
            s[i] += (float) (lp.process(body[i]) * env * (near ? 2.2 : 3));
        }
        return normalize(soft(Biquad.highpass(35, 0.707).apply(dc(s)), 1.6), 0.95);
    }

    // =====================================================================================
    // Footsteps, blocks
    // =====================================================================================

    /** Ground and block materials, for steps and block sounds. */
    public enum Material { GRASS, DIRT, SAND, GRAVEL, STONE, WOOD, SNOW, GLASS, LEAVES, WATER }

    /** A crunch: {@code grains} tiny clicks band-limited to (lo, hi) Hz spread over {@code spread} s, front-loaded. */
    private static void grains(float[] s, SplittableRandom r, int at, int grains, double lo, double hi, double spread, double gain) {
        for (int g = 0; g < grains; g++) {
            double t = Math.pow(r.nextDouble(), 1.7) * spread;
            double f = logUniform(r, lo, hi);
            float[] grain = burst(r, 0.003, f * 0.7, f * 1.4, 0.00005, 0.0003 + r.nextDouble() * 0.0006);
            mix(s, grain, at + samples(t), gain * (0.4 + r.nextDouble()) * (1 - 0.5 * t / spread));
        }
    }

    /** Struck modes {f, tau} with a click: a hard impact on something that rings a little. */
    private static void knock(float[] s, SplittableRandom r, int at, double[][] modes, double click, double gain) {
        mix(s, burst(r, 0.003, 1500, 0, 0.00003, 0.0005), at, click * gain);
        for (double[] m : modes) mix(s, mode(m[0] * (0.95 + r.nextDouble() * 0.1), m[1], m[1] * 6), at, gain * m[2]);
    }

    /** One footstep (heel then toe) on a material; variants by seed. */
    public static float[] step(Material m, long seed) {
        SplittableRandom r = new SplittableRandom(seed);
        float[] s = new float[samples(0.35)];
        int toe = samples(0.045 + r.nextDouble() * 0.03);
        for (int part = 0; part < 2; part++) {
            int at = part == 0 ? 0 : toe;
            double g = part == 0 ? 1 : 0.55;
            switch (m) {
                case GRASS -> {
                    grains(s, r, at, 45, 2200, 6500, 0.08, 0.35 * g);            // blades crushed
                    mix(s, burst(r, 0.04, 60, 250, 0.002, 0.008), at, 0.5 * g);  // the soil under them
                }
                case DIRT -> {
                    mix(s, burst(r, 0.05, 60, 450, 0.002, 0.012), at, 0.9 * g);
                    grains(s, r, at, 18, 900, 3000, 0.06, 0.25 * g);
                }
                case SAND -> {
                    mix(s, shape(burst(r, 0.12, 900, 3500, 0.01, 0.04), t -> 1), at, 0.45 * g);
                    grains(s, r, at, 60, 2500, 8000, 0.1, 0.08 * g);
                    mix(s, burst(r, 0.04, 60, 300, 0.003, 0.01), at, 0.25 * g);
                }
                case GRAVEL -> {
                    grains(s, r, at, 70, 1800, 8000, 0.12, 0.4 * g);
                    for (int k = 0; k < 4; k++) // a few pebbles knocking together
                        mix(s, mode(logUniform(r, 1500, 3500), 0.004, 0.03), at + samples(r.nextDouble() * 0.08), 0.15 * g);
                    mix(s, burst(r, 0.04, 60, 300, 0.002, 0.008), at, 0.35 * g);
                }
                case STONE -> { // a hard sole on rock: a dry clack, little body, a gritty scrape
                    knock(s, r, at, new double[][] {{1150, 0.006, 0.45}, {2400, 0.004, 0.35}, {520, 0.008, 0.25}}, 1.4, g);
                    grains(s, r, at + samples(0.004), 10, 3000, 9000, 0.03, 0.12 * g);
                }
                case WOOD -> knock(s, r, at, new double[][] {{165, 0.018, 0.45}, {390, 0.014, 0.4}, {880, 0.01, 0.3}, {1700, 0.006, 0.2}}, 1.0, g);
                case SNOW -> {
                    // Packing snow: grains whose density rises then stops abruptly, a faint squeak.
                    for (int k = 0; k < 90; k++) {
                        double t = Math.sqrt(r.nextDouble()) * 0.12;
                        double f = logUniform(r, 900, 3500);
                        mix(s, burst(r, 0.003, f * 0.7, f * 1.3, 0.0001, 0.0006), at + samples(t), 0.22 * g * (0.5 + r.nextDouble()));
                    }
                    float[] squeak = mode(1000 + r.nextDouble() * 300, 0.03, 0.08);
                    mix(s, shape(squeak, t -> Math.min(1, t / 0.02)), at + samples(0.03), 0.04 * g);
                }
                case WATER -> {
                    mix(s, shape(burst(r, 0.2, 500, 4000, 0.02, 0.06), t -> 1), at, 0.6 * g);
                    for (int k = 0; k < 8; k++)
                        mix(s, bubble(logUniform(r, 500, 2000), 0.008, 0.6), at + samples(0.02 + r.nextDouble() * 0.12), 0.25 * g);
                }
                case LEAVES -> grains(s, r, at, 40, 2500, 8000, 0.1, 0.3 * g);
                case GLASS -> knock(s, r, at, new double[][] {{2100, 0.01, 0.3}, {4300, 0.007, 0.2}, {1200, 0.006, 0.4}}, 0.6, g);
            }
        }
        return oneShot(s, 0.8);
    }

    /** A block broken: what it is made of shows in how it gives way and what falls. */
    public static float[] breakBlock(Material m, long seed) {
        SplittableRandom r = new SplittableRandom(seed);
        float[] s = new float[samples(0.9)];
        switch (m) {
            case STONE -> {
                knock(s, r, 0, new double[][] {{620, 0.02, 0.5}, {1350, 0.012, 0.4}, {2600, 0.008, 0.3}}, 1, 1);
                mix(s, burst(r, 0.08, 800, 6000, 0.0005, 0.02), 0, 0.5);
                // Debris: pebbles falling and bouncing, quieter and closer together each time.
                for (int p = 0; p < 6; p++) {
                    double t = 0.06 + r.nextDouble() * 0.1, gap = 0.08 + r.nextDouble() * 0.06, a = 0.4;
                    double f = logUniform(r, 1800, 4500);
                    while (t < 0.85 && a > 0.03) {
                        mix(s, mode(f, 0.004, 0.025), samples(t), a);
                        mix(s, burst(r, 0.003, 2000, 0, 0.00003, 0.0004), samples(t), a * 0.4);
                        t += gap;
                        gap *= 0.65;
                        a *= 0.55;
                    }
                }
            }
            case WOOD -> {
                // Splitting: a few cracks over a tenth of a second, the plank's hollow body, a creak.
                for (int c = 0; c < 4; c++)
                    mix(s, burst(r, 0.025, 900, 8000, 0.0002, 0.004), samples(r.nextDouble() * 0.12), 1.2 + r.nextDouble() * 0.6);
                grains(s, r, 0, 30, 1500, 6000, 0.15, 0.35); // splinters
                knock(s, r, 0, new double[][] {{150, 0.05, 0.4}, {420, 0.03, 0.35}, {950, 0.015, 0.3}}, 0.8, 1);
                float[] creak = note(t -> 380 + 120 * t, 0.18, 0.4);
                mix(s, highpass(creak, 300), samples(0.1), 0.08);
            }
            case DIRT, GRASS, SAND, GRAVEL -> {
                mix(s, burst(r, 0.08, 50, 500, 0.002, 0.02), 0, 0.9);              // the clod gives
                double lo = m == Material.SAND ? 1500 : m == Material.GRAVEL ? 1800 : 600, hi = m == Material.SAND ? 6000 : m == Material.GRAVEL ? 8000 : 2500;
                grains(s, r, samples(0.02), m == Material.GRAVEL ? 260 : 220, lo, hi, 0.45, m == Material.SAND ? 0.06 : 0.11); // crumbs falling
                float[] trickle = burst(r, 0.5, lo * 0.8, hi, 0.01, 0.14); // the fine stuff pouring down
                mix(s, trickle, samples(0.02), m == Material.SAND ? 0.3 : 0.18);
                if (m == Material.GRASS) grains(s, r, 0, 40, 2500, 7000, 0.08, 0.2);         // roots and blades tearing
            }
            case GLASS, SNOW -> {
                if (m == Material.SNOW) {
                    mix(s, burst(r, 0.15, 300, 3000, 0.01, 0.04), 0, 0.6);
                    grains(s, r, 0, 80, 800, 3500, 0.25, 0.15);
                    break;
                }
                mix(s, burst(r, 0.02, 2500, 0, 0.0001, 0.004), 0, 0.8);            // the crack
                for (int k = 0; k < 70; k++) {                                     // shards tinkling down
                    double t = Math.pow(r.nextDouble(), 1.5) * 0.7, f = logUniform(r, 2500, 9000), tau = 0.02 + r.nextDouble() * 0.08;
                    float[] shard = mode(f, tau, tau * 5);
                    mix(shard, mode(f * (2.3 + r.nextDouble() * 0.6), tau * 0.5, tau * 5), 0, 0.4); // inharmonic partial
                    mix(s, shard, samples(t), 0.1 * (1 - t));
                }
            }
            case LEAVES -> {
                grains(s, r, 0, 120, 2000, 8000, 0.3, 0.25);
                for (int k = 0; k < 3; k++) mix(s, burst(r, 0.006, 1500, 0, 0.00005, 0.001), samples(r.nextDouble() * 0.2), 0.5);
            }
            case WATER -> mix(s, splash(seed), 0, 0.8);
        }
        return oneShot(s, 0.85);
    }

    /** A block placed: a short, solid set-down in its own material. */
    public static float[] placeBlock(Material m, long seed) {
        SplittableRandom r = new SplittableRandom(seed);
        float[] s = new float[samples(0.25)];
        switch (m) {
            case STONE -> knock(s, r, 0, new double[][] {{210, 0.02, 0.5}, {470, 0.012, 0.4}, {1100, 0.006, 0.3}, {2300, 0.004, 0.2}}, 1.0, 1);
            case WOOD -> knock(s, r, 0, new double[][] {{140, 0.025, 0.5}, {360, 0.018, 0.4}, {800, 0.01, 0.3}, {1600, 0.006, 0.15}}, 0.8, 1);
            case GRAVEL -> {
                mix(s, burst(r, 0.05, 60, 500, 0.002, 0.012), 0, 0.7);
                grains(s, r, 0, 40, 1800, 8000, 0.1, 0.35);
            }
            case WATER -> mix(s, burst(r, 0.12, 400, 3000, 0.01, 0.03), 0, 0.6);
            case GLASS -> knock(s, r, 0, new double[][] {{1900, 0.02, 0.4}, {4100, 0.012, 0.25}, {6300, 0.006, 0.1}}, 0.4, 1);
            case LEAVES -> grains(s, r, 0, 35, 2000, 7000, 0.08, 0.3);
            case SNOW -> mix(s, burst(r, 0.08, 200, 2000, 0.005, 0.02), 0, 0.7);
            default -> { // dirt, grass, sand, gravel: a soft thump and a few grains settling
                mix(s, burst(r, 0.06, 50, 400, 0.002, 0.014), 0, 1);
                grains(s, r, samples(0.01), 20, m == Material.SAND ? 2000 : 800, m == Material.SAND ? 7000 : 3000, 0.07, 0.15);
            }
        }
        return oneShot(s, 0.8);
    }

    /** A pick hitting the block while mining (much shorter than breaking). */
    public static float[] hit(Material m, long seed) {
        SplittableRandom r = new SplittableRandom(seed);
        float[] s = new float[samples(0.12)];
        switch (m) {
            case STONE, GLASS -> knock(s, r, 0, new double[][] {{900, 0.008, 0.4}, {2300, 0.005, 0.3}}, 1, 1);
            case WOOD -> knock(s, r, 0, new double[][] {{230, 0.012, 0.6}, {600, 0.008, 0.3}}, 0.7, 1);
            case LEAVES -> grains(s, r, 0, 20, 2500, 8000, 0.05, 0.4);
            case SAND -> {
                mix(s, burst(r, 0.05, 900, 4000, 0.003, 0.015), 0, 0.6);
                grains(s, r, 0, 25, 2500, 8000, 0.05, 0.15);
            }
            case GRAVEL -> {
                mix(s, burst(r, 0.03, 80, 600, 0.001, 0.006), 0, 0.6);
                grains(s, r, 0, 30, 1800, 8000, 0.05, 0.4);
            }
            case SNOW -> mix(s, burst(r, 0.06, 300, 2500, 0.004, 0.015), 0, 0.7);
            case WATER -> mix(s, bubble(600 + r.nextDouble() * 400, 0.02, 0.6), 0, 0.6);
            default -> { // dirt and grass: a dull thud and a few clods
                mix(s, burst(r, 0.04, 60, 600, 0.001, 0.008), 0, 0.9);
                grains(s, r, 0, 10, 900, 3500, 0.04, m == Material.GRASS ? 0.45 : 0.3);
                if (m == Material.GRASS) grains(s, r, 0, 12, 3000, 7000, 0.03, 0.2);
            }
        }
        return oneShot(s, 0.7);
    }

    /**
     * A body falling into water: the slap of impact, a hollow "plunk" as the cavity closes, then
     * a cloud of bubbles rising for half a second.
     */
    public static float[] splash(long seed) {
        SplittableRandom r = new SplittableRandom(seed);
        float[] s = new float[samples(1)];
        mix(s, burst(r, 0.01, 300, 0, 0.0001, 0.002), 0, 0.9);
        mix(s, shape(burst(r, 0.4, 400, 3500, 0.015, 0.09), t -> 1), 0, 0.7);
        mix(s, bubble(260 + r.nextDouble() * 60, 0.05, 0.8), samples(0.06), 0.5);
        for (int k = 0; k < 60; k++) {
            double t = 0.05 + Math.pow(r.nextDouble(), 1.4) * 0.6;
            mix(s, bubble(logUniform(r, 350, 2500), 0.006 + r.nextDouble() * 0.012, 0.6), samples(t), 0.15 * (1 - t));
        }
        return oneShot(s, 0.85);
    }

    /** Interface click: a small, soft tick with a little body. */
    public static float[] click(long seed) {
        SplittableRandom r = new SplittableRandom(seed);
        float[] s = new float[samples(0.05)];
        mix(s, mode(1500, 0.004, 0.03), 0, 0.6);
        mix(s, mode(3100, 0.002, 0.02), 0, 0.25);
        mix(s, burst(r, 0.002, 2000, 0, 0.00002, 0.0003), 0, 0.3);
        return normalize(s, 0.5);
    }
}
