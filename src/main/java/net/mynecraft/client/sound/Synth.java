package net.mynecraft.client.sound;

import java.util.SplittableRandom;

import static net.mynecraft.client.sound.Dsp.*;

/**
 * Every sound of the game, synthesised from physical ingredients rather than raw noise: impacts
 * are a soft transient plus damped modes, bubbles are rising, dying sines (Minnaert resonance),
 * rubbing and crunching are clouds of small, soft grains. Rules kept throughout, because
 * synthesised sound easily turns harsh: no instant attacks (grains have a raised-cosine envelope),
 * little content above 6-8 kHz (air absorbs it), a hint of early reflections on one-shots, and
 * every sound set to a perceived loudness for its kind ({@link Dsp#level}) instead of to a peak.
 * Loops are rendered longer than their length and cross-faded into themselves.
 * Pure Java: sounds can be rendered to WAV and checked offline.
 */
public final class Synth {
    private Synth() {}

    // Perceived loudness of each kind of sound (dB): footsteps discreet, thunder the loudest.
    private static final double AMBIENT_QUIET = -40, AMBIENT = -34, AMBIENT_LOUD = -30;
    private static final double STEP = -36, HIT = -34, PLACE = -33, BREAK = -30, SPLASH = -30, BIRD = -36,
        DRIP = -36, THUNDER_NEAR = -24, THUNDER_FAR = -30, CLICK = -40;

    // =====================================================================================
    // Rain, hail
    // =====================================================================================

    /**
     * Rain on open ground: mostly a soft, even "shhh" (thousands of distant drops merged: pink
     * noise from 300 Hz, rolled off above 5 kHz) whose level wavers a little, and near drops on
     * top: small soft ticks centred 1-4 kHz, a few landing in puddles with a faint bubble.
     */
    public static float[] rain(double seconds, double density, double wash, long seed) {
        SplittableRandom r = new SplittableRandom(seed);
        int len = samples(seconds), fade = samples(0.5), n = len + fade;
        float[] s = pink(n, r);
        highpass(s, 300);
        lowpass(s, 5000);
        lowpass(s, 7000);
        double p1 = r.nextDouble() * 6.28, p2 = r.nextDouble() * 6.28;
        for (int i = 0; i < n; i++) { // the shower thickens and thins a little
            double t = (i % len) / (double) len;
            s[i] *= (float) (wash * (0.85 + 0.1 * Math.sin(2 * Math.PI * 2 * t + p1) + 0.05 * Math.sin(2 * Math.PI * 5 * t + p2)));
        }
        if (density > 100) mix(s, rumble(n, r, 150, 700), 0, wash * 0.3); // a downpour hitting everything
        int drops = (int) (density * seconds);
        for (int d = 0; d < drops; d++) {
            int at = r.nextInt(len);
            double near = r.nextDouble();
            double a = (0.03 + 0.09 * near * near) * Math.exp(r.nextGaussian() * 0.35);
            float[] tick = grain(r, logUniform(r, 1000, 3500 + 1500 * near), 0.0025 + r.nextDouble() * 0.002);
            mixWrapped(s, tick, at, a);
            if (r.nextDouble() < 0.15)
                mixWrapped(s, bubble(logUniform(r, 1200, 3000), 0.004 + r.nextDouble() * 0.005, 0.5), at + samples(0.003), a * 0.25);
        }
        return level(loop(clean(s), len), density > 100 ? AMBIENT_LOUD : AMBIENT);
    }

    /** Rain heard from under a roof: a dull drumming of drops on the roof and the outside through walls. */
    public static float[] rainRoof(double seconds, long seed) {
        SplittableRandom r = new SplittableRandom(seed);
        int len = samples(seconds), fade = samples(0.5), n = len + fade;
        float[] s = rumble(n, r, 90, 900);
        scale(s, 0.5);
        for (int d = 0; d < (int) (60 * seconds); d++) {
            float[] thud = grain(r, logUniform(r, 180, 600), 0.012 + r.nextDouble() * 0.01);
            mixWrapped(s, thud, r.nextInt(len), 0.25 * Math.exp(r.nextGaussian() * 0.4));
        }
        return level(loop(clean(s), len), AMBIENT);
    }

    /** Hail: ice pellets clicking on the ground, each a short knock with a little glassy ring. */
    public static float[] hail(double seconds, long seed) {
        SplittableRandom r = new SplittableRandom(seed);
        int len = samples(seconds), fade = samples(0.4), n = len + fade;
        float[] s = lowpass(highpass(pink(n, r), 800), 6000);
        scale(s, 0.08);
        for (int h = 0; h < (int) (45 * seconds); h++) {
            int at = r.nextInt(len);
            double a = 0.25 * Math.exp(r.nextGaussian() * 0.4) / (0.5 + r.nextDouble());
            mixWrapped(s, grain(r, logUniform(r, 1500, 4000), 0.0012), at, a);
            mixWrapped(s, mode(logUniform(r, 2000, 4500), 0.004 + r.nextDouble() * 0.004, 0.03), at, a * 0.12);
            mixWrapped(s, grain(r, logUniform(r, 150, 400), 0.01), at, a * 0.3);
        }
        return level(loop(clean(s), len), AMBIENT);
    }

    // =====================================================================================
    // Wind, leaves
    // =====================================================================================

    /**
     * Wind: deep noise through a band-pass whose centre and level follow gusts (slow sines in
     * whole cycles per loop), a breathy hint of whistle only in the strongest gusts.
     */
    public static float[] wind(double seconds, long seed) {
        SplittableRandom r = new SplittableRandom(seed);
        int len = samples(seconds), fade = samples(0.8), n = len + fade;
        float[] base = pink(n, r), air = pink(n, r);
        Biquad body = Biquad.bandpass(300, 0.9), whistle = Biquad.bandpass(700, 6), top = Biquad.lowpass(1600, 0.7);
        float[] s = new float[n];
        double p1 = r.nextDouble() * 6.28, p2 = r.nextDouble() * 6.28, p3 = r.nextDouble() * 6.28;
        for (int i = 0; i < n; i++) {
            double t = (i % len) / (double) len;
            double gust = 0.55 + 0.25 * Math.sin(2 * Math.PI * t + p1) + 0.13 * Math.sin(2 * Math.PI * 3 * t + p2)
                + 0.06 * Math.sin(2 * Math.PI * 7 * t + p3);
            if (i % 64 == 0) {
                body.set(2, 140 + 320 * gust * gust, 0.9);
                whistle.set(2, 600 + 300 * gust, 5);
            }
            double w = body.process(base[i]) * gust * gust + whistle.process(air[i]) * Math.max(0, gust - 0.75) * 0.4;
            s[i] = (float) top.process(w);
        }
        return level(loop(clean(s), len), AMBIENT);
    }

    /** Leaves in the wind: a soft rustle of many small grains (1-4 kHz) swelling with the gusts. */
    public static float[] leaves(double seconds, long seed) {
        SplittableRandom r = new SplittableRandom(seed);
        int len = samples(seconds), fade = samples(0.5), n = len + fade;
        float[] s = new float[n];
        double p = r.nextDouble() * 6.28;
        for (int i = 0; i < len; i += samples(0.0015)) {
            double t = i / (double) len;
            double gust = 0.55 + 0.3 * Math.sin(2 * Math.PI * 2 * t + p) + 0.12 * Math.sin(2 * Math.PI * 5 * t);
            if (r.nextDouble() > gust * 0.8) continue;
            mixWrapped(s, grain(r, logUniform(r, 1000, 4000), 0.006 + r.nextDouble() * 0.012), i, gust * (0.4 + r.nextDouble()));
        }
        lowpass(s, 6000);
        return level(loop(clean(s), len), AMBIENT_QUIET);
    }

    // =====================================================================================
    // Running water, sea
    // =====================================================================================

    /**
     * A brook. Running water is mostly the sound of air bubbles trapped and ringing as the water
     * tumbles (Minnaert resonances, rising in pitch as they surface): a dense, irregular cloud of
     * tiny, soft "blips" between 400 Hz and 2 kHz, over a gentle low wash. No hiss on top: the
     * highs are rolled off, which is what makes water sound wet rather than like static.
     */
    public static float[] brook(double seconds, long seed) {
        SplittableRandom r = new SplittableRandom(seed);
        int len = samples(seconds), fade = samples(0.4), n = len + fade;
        float[] s = flow(n, len, r, 300, 1400, 1);
        bubbles(s, r, len, seconds * 90, 450, 1800, 0.15);
        lowpass(s, 3000);
        return level(loop(clean(s), len), AMBIENT_QUIET + 2);
    }

    /** A river: a deep, soft rush of the current, and many lower, duller bubbles further off. */
    public static float[] river(double seconds, long seed) {
        SplittableRandom r = new SplittableRandom(seed);
        int len = samples(seconds), fade = samples(0.5), n = len + fade;
        float[] s = flow(n, len, r, 150, 900, 1);
        mix(s, rumble(n, r, 60, 300), 0, 0.5);
        float[] cloud = new float[n];
        bubbles(cloud, r, len, seconds * 120, 250, 1000, 0.08);
        mix(s, lowpass(cloud, 1500), 0, 1);
        double p = r.nextDouble() * 6.28;
        for (int i = 0; i < n; i++) s[i] *= (float) (0.85 + 0.15 * Math.sin(2 * Math.PI * 2 * (i % len) / len + p));
        return level(loop(clean(s), len), AMBIENT);
    }

    /**
     * The rush of a current: pink noise through three broad, slowly wandering bands between lo
     * and hi Hz (the water's surface sliding and swirling), each band swelling on its own.
     */
    private static float[] flow(int n, int len, SplittableRandom r, double lo, double hi, double gain) {
        float[] noise = pink(n, r), s = new float[n];
        Biquad[] f = new Biquad[3];
        double[] p = new double[3], c = new double[3];
        for (int b = 0; b < 3; b++) {
            p[b] = r.nextDouble() * 6.28;
            c[b] = 1 + r.nextInt(3); // whole cycles per loop: seamless
            f[b] = Biquad.bandpass(lo, 1.2);
        }
        for (int i = 0; i < n; i++) {
            double u = (i % len) / (double) len, v = 0;
            for (int b = 0; b < 3; b++) {
                double w = 0.5 + 0.5 * Math.sin(2 * Math.PI * c[b] * u + p[b]);
                if (i % 64 == 0) f[b].set(2, lo * Math.pow(hi / lo, (b + w) / 3), 1.2);
                v += f[b].process(noise[i]) * (0.6 + 0.4 * w);
            }
            s[i] = (float) (v * gain);
        }
        return s;
    }

    /** Scatters {@code count} rising bubbles over a loop, in bursts (water tumbles in little gulps). */
    private static void bubbles(float[] s, SplittableRandom r, int len, double count, double lo, double hi, double gain) {
        double t = 0, rate = count / (len / (double) RATE);
        while (t < len / (double) RATE) {
            double gulp = 0.5 + 1.5 * r.nextDouble(); // a moment of more or fewer bubbles
            for (int k = 0; k < 6; k++) {
                t += -Math.log(1 - r.nextDouble()) / (rate * gulp);
                double f = logUniform(r, lo, hi), tau = 0.004 + 0.01 * r.nextDouble() * (lo / f);
                mixWrapped(s, bubble(f, tau, 0.6 + r.nextDouble()), samples(t), gain * Math.pow(r.nextDouble(), 2));
            }
        }
    }

    /** A waterfall: a broad roar, heavy in the lows, the top rolled off. */
    public static float[] waterfall(double seconds, long seed) {
        SplittableRandom r = new SplittableRandom(seed);
        int len = samples(seconds), fade = samples(0.4), n = len + fade;
        float[] s = lowpass(highpass(pink(n, r), 300), 5000);
        mix(s, rumble(n, r, 150, 1200), 0, 0.8);
        mix(s, rumble(n, r, 55, 220), 0, 0.8);
        return level(loop(clean(s), len), AMBIENT_LOUD);
    }

    /**
     * Surf on a shore, two waves per loop: the wave swells (noise opening up as it builds),
     * breaks (a broad crash), then the foam fizzes away over seconds.
     */
    public static float[] surf(double seconds, long seed) {
        SplittableRandom r = new SplittableRandom(seed);
        int len = samples(seconds), fade = samples(0.8), n = len + fade;
        float[] s = new float[n];
        float[] noise = highpass(pink(n, r), 70), hiss = pink(n, r);
        Biquad sweep = Biquad.lowpass(300, 0.7), foam = Biquad.bandpass(2500, 0.8);
        double[] breaks = {seconds * 0.22, seconds * 0.71};
        for (int i = 0; i < n; i++) {
            double t = (i % len) / (double) RATE;
            double swell = 0, fizz = 0;
            for (double b : breaks) {
                double dt = t - b;
                if (dt < 0 && dt > -1.8) swell = Math.max(swell, Math.pow(1 + dt / 1.8, 2));
                if (dt >= 0 && dt < 4.5) {
                    swell = Math.max(swell, Math.exp(-dt / 0.6));
                    fizz = Math.max(fizz, Math.min(1, dt / 0.2) * Math.exp(-dt / 1.6));
                }
                double dtw = dt + seconds;
                if (dtw >= 0 && dtw < 4.5) fizz = Math.max(fizz, Math.exp(-dtw / 1.6));
            }
            if (i % 64 == 0) sweep.set(0, 250 + 1800 * swell, 0.7);
            s[i] = (float) (sweep.process(noise[i]) * (0.15 + swell) + foam.process(hiss[i]) * fizz * 0.3);
        }
        lowpass(s, 6000);
        return level(loop(clean(s), len), AMBIENT);
    }

    // =====================================================================================
    // Places and creatures
    // =====================================================================================

    /** The air of a cave: a low, slowly breathing hum. */
    public static float[] cave(double seconds, long seed) {
        SplittableRandom r = new SplittableRandom(seed);
        int len = samples(seconds), fade = samples(1), n = len + fade;
        float[] s = rumble(n, r, 50, 220);
        mix(s, bandpass(pink(n, r), 110, 6), 0, 0.8);
        for (int i = 0; i < n; i++) s[i] *= (float) (0.8 + 0.2 * Math.sin(2 * Math.PI * (i % len) / len));
        return level(loop(clean(s), len), AMBIENT_QUIET);
    }

    /**
     * Crickets at night: a field of insects, each with its own pitch (3.8-4.8 kHz), chirping in
     * little trains of soft pulses at its own unsteady rhythm; most are far (quiet).
     */
    public static float[] crickets(double seconds, long seed) {
        SplittableRandom r = new SplittableRandom(seed);
        int len = samples(seconds), fade = samples(0.3), n = len + fade;
        float[] s = new float[n];
        for (int c = 0; c < 9; c++) {
            double f = 3800 + r.nextDouble() * 1000, period = 0.45 + r.nextDouble() * 0.7;
            double gain = 0.15 + 0.85 * Math.pow(r.nextDouble(), 3);
            int pulses = 3 + r.nextInt(3);
            for (double t = r.nextDouble() * period; t < seconds; t += period * (0.9 + r.nextDouble() * 0.2))
                for (int p = 0; p < pulses; p++) {
                    float[] pulse = mode(f, 0.005, 0.018);
                    shape(pulse, x -> Math.sin(Math.PI * Math.min(1, x / 0.018))); // rounded, not clicked
                    mixWrapped(s, pulse, samples(t + p * 0.022), gain);
                }
        }
        return level(loop(clean(s), len), AMBIENT_QUIET);
    }

    /**
     * Cicadas on a summer day, as their body makes them: a tymbal clicking 150-250 times a second,
     * each click ringing the hollow abdomen (a resonance around 4-5 kHz, damped fast). A few
     * insects in the trees, each singing phrases that swell and fade over seconds; all are far,
     * so their highs are softened.
     */
    public static float[] insects(double seconds, long seed) {
        SplittableRandom r = new SplittableRandom(seed);
        int len = samples(seconds), fade = samples(0.5), n = len + fade;
        float[] s = new float[n];
        for (int c = 0; c < 3; c++) {
            double f = 4500 + r.nextDouble() * 1500, rate = 250 + r.nextDouble() * 150, gain = 0.35 + 0.65 * r.nextDouble();
            float[] click = mode(f, 0.0012, 0.006);
            shape(click, t -> Math.min(1, t / 0.0003));
            for (double start = r.nextDouble() * 2; start < seconds; ) {
                double phrase = 2 + r.nextDouble() * 3;
                double beat = 1 / (5 + r.nextDouble() * 5), echeme = beat * (0.45 + r.nextDouble() * 0.2);
                for (double t = 0; t < phrase; t += (1 + 0.04 * white(r)) / rate) {
                    double inBeat = (t % beat) / echeme; // clicks come in short trains, several a second
                    if (inBeat > 1) continue;
                    double env = Math.pow(Math.sin(Math.PI * t / phrase), 1.5) * Math.sin(Math.PI * inBeat);
                    mixWrapped(s, click, samples(start + t), gain * env * (0.8 + 0.2 * r.nextDouble()));
                }
                start += phrase + 1 + r.nextDouble() * 3;
            }
        }
        lowpass(s, 6500);
        return level(loop(clean(s), len), AMBIENT_QUIET - 10);
    }

    /**
     * Frogs by the water at night: a few voices, each croak a short "rrr-ek" of glottal pulses
     * (70-110 a second) through two throat resonances, falling a little in pitch; each frog calls
     * again at its own unsteady pace, and the far ones are dull.
     */
    public static float[] frogs(double seconds, long seed) {
        SplittableRandom r = new SplittableRandom(seed);
        int len = samples(seconds), fade = samples(0.3), n = len + fade;
        float[] s = new float[n];
        for (int c = 0; c < 4; c++) {
            double f = 380 + r.nextDouble() * 250, period = 1.2 + r.nextDouble() * 1.8, rate = 70 + r.nextDouble() * 40;
            double gain = 0.25 + 0.75 * Math.pow(r.nextDouble(), 2);
            for (double t = r.nextDouble() * period; t < seconds; t += period * (0.7 + r.nextDouble() * 0.6)) {
                int syllables = 1 + r.nextInt(2);
                for (int y = 0; y < syllables; y++) {
                    double length = y == 0 ? 0.09 + r.nextDouble() * 0.05 : 0.05;
                    float[] croak = new float[samples(length + 0.02)];
                    for (int k = 0; k < length * rate; k++) {
                        double drop = 1 - 0.12 * k / (length * rate);
                        mix(croak, mode(f * drop, 0.004, 0.02), samples(k / rate), 1);
                        mix(croak, mode(f * 2.7 * drop, 0.002, 0.012), samples(k / rate), 0.5);
                    }
                    shape(croak, x -> Math.sin(Math.PI * Math.min(1, x / (length + 0.02))));
                    float[] voice = gain < 0.5 ? lowpass(croak, 1200) : croak;
                    mixWrapped(s, voice, samples(t + y * 0.16), gain);
                }
            }
        }
        return level(loop(clean(s), len), AMBIENT_QUIET);
    }

    /** An owl at night: two or three soft, breathy hoots around 350-450 Hz, the last one longer. */
    public static float[] owl(long seed) {
        SplittableRandom r = new SplittableRandom(seed);
        double f = 350 + r.nextDouble() * 100;
        int hoots = 2 + r.nextInt(2);
        float[] s = new float[samples(hoots * 0.55 + 0.9)];
        for (int k = 0; k < hoots; k++) {
            double len = k == hoots - 1 ? 0.7 : 0.3, at = k * 0.5 + (k == hoots - 1 ? 0.15 : 0);
            float[] h = note(r, t -> f * (1 + 0.05 * Math.sin(Math.PI * t / len)), len, 0.15);
            float[] breath = scale(bandpass(white(h.length, r), f * 2, 2), 0.25);
            for (int i = 0; i < h.length; i++) h[i] += breath[i] * (float) Math.pow(Math.sin(Math.PI * i / h.length), 2);
            mix(s, h, samples(at), 0.9);
        }
        return level(space(lowpass(s, 1800), 0.5), BIRD);
    }

    /** One bird call; {@code kind} 0..5 picks a song type, the seed varies it. */
    public static float[] bird(int kind, long seed) {
        SplittableRandom r = new SplittableRandom(seed);
        float[] s = switch (kind % 6) {
            case 0 -> song(r, 4 + r.nextInt(3), 3400, -0.3, 0.09, 0.05);
            case 1 -> trill(r);
            case 2 -> twoTone(r);
            case 3 -> warble(r);
            case 4 -> song(r, 3, 4200, 0.5, 0.04, 0.07);
            default -> song(r, 2 + r.nextInt(2), 2400, -0.12, 0.22, 0.12);
        };
        return level(space(s, 0.35), BIRD);
    }

    /** A note: a sine along a frequency path with natural jitter, smooth envelope, a little second harmonic. */
    private static float[] note(SplittableRandom r, java.util.function.DoubleUnaryOperator f, double seconds, double harmonic) {
        int n = samples(seconds);
        float[] out = new float[n];
        double phase = 0, jitter = 0;
        for (int i = 0; i < n; i++) {
            double t = i / (double) RATE;
            jitter = jitter * 0.999 + white(r) * 0.0004; // a living voice is never perfectly steady
            phase += 2 * Math.PI * f.applyAsDouble(t) * (1 + jitter) / RATE;
            double env = Math.pow(Math.sin(Math.PI * t / seconds), 2);
            out[i] = (float) ((Math.sin(phase) + harmonic * Math.sin(2 * phase)) * env);
        }
        return out;
    }

    /**
     * A wooden flute's phrase (M34): six to nine notes of a D pentatonic scale, a breathy tone (a
     * sine with a little second and third harmonic, a wisp of filtered noise), gentle vibrato.
     */
    public static float[] flute(long seed) {
        SplittableRandom r = new SplittableRandom(seed);
        double[] scale = {587.3, 659.3, 740.0, 880.0, 987.8, 1174.7};
        int notes = 6 + r.nextInt(4), step = r.nextInt(scale.length);
        double len = 0.32;
        float[] s = new float[samples(notes * len + 0.6)];
        for (int k = 0; k < notes; k++) {
            step = Math.max(0, Math.min(scale.length - 1, step + r.nextInt(-2, 3)));
            double f = scale[step], l = k == notes - 1 ? len * 2.5 : len * (r.nextDouble() < 0.25 ? 2 : 1);
            float[] tone = note(r, t -> f * (1 + 0.006 * Math.sin(2 * Math.PI * 5.5 * t)), l, 0.25);
            float[] breath = new float[tone.length];
            for (int i = 0; i < tone.length; i++) breath[i] = (float) (white(r) * 0.04 * Math.sin(Math.PI * i / tone.length));
            mix(tone, breath, 0, 1);
            mix(s, tone, samples(k * len), 0.7);
        }
        return level(space(s, 0.3), BIRD);
    }

    /**
     * A frame drum's rhythm (M34): eight beats, strong and light, each a low thud whose pitch falls
     * fast under a slap of noise on the skin.
     */
    public static float[] drum(long seed) {
        SplittableRandom r = new SplittableRandom(seed);
        double beat = 0.24;
        float[] s = new float[samples(8 * beat + 0.5)];
        for (int k = 0; k < 8; k++) {
            if (k > 0 && r.nextDouble() < 0.2) continue; // a rest
            boolean strong = k % 4 == 0 || r.nextDouble() < 0.2;
            double f0 = strong ? 90 : 140;
            float[] hit = new float[samples(0.3)];
            double phase = 0;
            for (int i = 0; i < hit.length; i++) {
                double t = i / (double) RATE, env = Math.exp(-t * (strong ? 12 : 20));
                phase += 2 * Math.PI * f0 * (1 + 1.5 * Math.exp(-t * 30)) / RATE;
                hit[i] = (float) (env * (Math.sin(phase) + white(r) * 0.25 * Math.exp(-t * 60)));
            }
            mix(s, hit, samples(k * beat), strong ? 0.9 : 0.55);
        }
        return level(space(s, 0.25), BIRD);
    }

    private static float[] song(SplittableRandom r, int notes, double f0, double slide, double length, double gap) {
        float[] s = new float[samples(notes * (length + gap) + 0.1)];
        double f = f0 * (0.9 + r.nextDouble() * 0.2);
        for (int k = 0; k < notes; k++) {
            double start = f, len = length * (0.85 + r.nextDouble() * 0.3);
            mix(s, note(r, t -> start * (1 + slide * t / len), len, 0.08), samples(k * (length + gap)), 0.8);
            f *= 0.93 + r.nextDouble() * 0.05;
        }
        return s;
    }

    private static float[] trill(SplittableRandom r) {
        double f = 3200 + r.nextDouble() * 1200, rate = 14 + r.nextDouble() * 8, seconds = 0.7 + r.nextDouble() * 0.6;
        float[] s = note(r, t -> f * (1 + 0.1 * Math.sin(2 * Math.PI * rate * t)), seconds, 0.06);
        return shape(s, t -> 0.5 + 0.5 * Math.sin(2 * Math.PI * rate * t));
    }

    private static float[] twoTone(SplittableRandom r) {
        double f = 550 + r.nextDouble() * 150;
        float[] s = new float[samples(1.1)];
        mix(s, note(r, t -> f * (1 + 0.04 * t), 0.35, 0.25), 0, 0.9);
        mix(s, note(r, t -> f * 0.8, 0.5, 0.25), samples(0.45), 0.8);
        return lowpass(s, 2500);
    }

    private static float[] warble(SplittableRandom r) {
        double seconds = 1 + r.nextDouble() * 0.6, base = 2600 + r.nextDouble() * 700;
        double[] path = new double[24];
        for (int i = 0; i < path.length; i++) path[i] = base * (0.8 + r.nextDouble() * 0.45);
        float[] s = note(r, t -> {
            double x = t / seconds * (path.length - 1);
            int i = Math.min(path.length - 2, (int) x);
            double u = x - i, sm = u * u * (3 - 2 * u);
            return path[i] + (path[i + 1] - path[i]) * sm;
        }, seconds, 0.08);
        return shape(s, t -> 0.65 + 0.35 * Math.sin(2 * Math.PI * 9 * t));
    }

    /** A drop falling into a pool in a cave: a soft tick and a bright "plink", with faint echoes. */
    public static float[] drip(long seed) {
        SplittableRandom r = new SplittableRandom(seed);
        float[] s = new float[samples(0.7)];
        float[] plink = bubble(logUniform(r, 900, 1800), 0.012 + r.nextDouble() * 0.01, 1.2 + r.nextDouble());
        mix(s, grain(r, 2500, 0.001), 0, 0.2);
        mix(s, plink, samples(0.001), 1);
        float[] echo = lowpass(plink.clone(), 1400);
        mix(s, echo, samples(0.13), 0.22);
        mix(s, echo, samples(0.29), 0.1);
        mix(s, echo, samples(0.47), 0.05);
        return level(oneShot(s, 0.8), DRIP);
    }

    // =====================================================================================
    // Thunder
    // =====================================================================================

    /**
     * Thunder: a bolt is kilometres of channel, each piece's crack arriving at a different time
     * (farther parts later and duller), which makes the rumble. Near: a crack (a short, dark
     * burst, not a raw pulse), a tearing rip, then a rolling rumble with bumps. Far: the rumble
     * alone, rising softly.
     */
    public static float[] thunder(boolean near, long seed) {
        SplittableRandom r = new SplittableRandom(seed);
        double seconds = near ? 6.5 : 7.5;
        int n = samples(seconds);
        float[] s = new float[n];
        if (near) {
            float[] crack = burst(r, 0.05, 80, 3000, 0.0015, 0.012);
            mix(s, crack, 0, 1.2);
            float[] rip = lowpass(highpass(pink(samples(0.8), r), 300), 3500);
            for (int i = 0; i < rip.length; i++) {
                double t = i / (double) RATE;
                rip[i] *= (float) (Math.min(1, t / 0.01) * Math.exp(-t / 0.22) * (1 + 0.8 * Math.sin(t * 90 + Math.sin(t * 37) * 3)));
            }
            mix(s, rip, samples(0.01), 1.4);
        }
        float[] body = rumble(n, r, 35, 2000);
        double[] bumps = new double[10 + r.nextInt(6)];
        for (int b = 0; b < bumps.length; b++) bumps[b] = (near ? 0.15 : 0.5) + Math.pow(r.nextDouble(), 1.6) * (seconds - 2);
        Biquad lp = Biquad.lowpass(near ? 1200 : 400, 0.7);
        for (int i = 0; i < n; i++) {
            double t = i / (double) RATE;
            double env = 0;
            for (double b : bumps) env += Math.exp(-Math.pow((t - b) / (0.25 + 0.15 * b), 2)) * (1.2 / (1 + b * 0.4));
            env *= Math.min(1, t / (near ? 0.08 : 0.7)) * Math.exp(-Math.max(0, t - seconds + 2.5) / 0.8);
            if (i % 64 == 0) lp.set(0, (near ? 1100 : 350) * Math.exp(-t / 2.2) + 130, 0.7);
            s[i] += (float) (lp.process(body[i]) * env * 2.2);
        }
        return level(oneShot(Biquad.highpass(35, 0.707).apply(s), 0.9), near ? THUNDER_NEAR : THUNDER_FAR);
    }

    // =====================================================================================
    // Footsteps, blocks
    // =====================================================================================

    /** Ground and block materials, for steps and block sounds. */
    public enum Material { GRASS, DIRT, SAND, GRAVEL, STONE, WOOD, SNOW, GLASS, LEAVES, WATER }

    /** A crunch: soft grains centred between lo and hi Hz spread over {@code spread} s, front-loaded. */
    private static void grains(float[] s, SplittableRandom r, int at, int count, double lo, double hi, double spread, double gain) {
        for (int g = 0; g < count; g++) {
            double t = Math.pow(r.nextDouble(), 1.6) * spread;
            float[] grain = grain(r, logUniform(r, lo, hi), 0.0015 + r.nextDouble() * 0.003);
            mix(s, grain, at + samples(t), gain * (0.4 + r.nextDouble()) * (1 - 0.5 * t / spread));
        }
    }

    /** An impact on something that rings a little: a soft knock and struck modes {f, tau, amp}. */
    private static void knock(float[] s, SplittableRandom r, int at, double[][] modes, double click, double gain) {
        mix(s, burst(r, 0.006, 300, 5000, 0.0004, 0.0012), at, click * gain);
        for (double[] m : modes) {
            float[] ring = mode(m[0] * (0.95 + r.nextDouble() * 0.1), m[1], m[1] * 6);
            shape(ring, t -> Math.min(1, t / 0.0005));
            mix(s, ring, at, gain * m[2]);
        }
    }

    /** A soft swish: noise with a smooth rise and fall (shoes brushing grass, sand shifting). */
    private static float[] swish(SplittableRandom r, double seconds, double lo, double hi) {
        float[] w = lowpass(highpass(white(samples(seconds), r), lo), hi);
        return shape(w, t -> Math.sin(Math.PI * t / seconds) * Math.exp(-t / seconds));
    }

    /** One footstep (heel then toe) on a material; variants by seed. */
    public static float[] step(Material m, long seed) {
        SplittableRandom r = new SplittableRandom(seed);
        float[] s = new float[samples(0.3)];
        int toe = samples(0.05 + r.nextDouble() * 0.03);
        for (int part = 0; part < 2; part++) {
            int at = part == 0 ? 0 : toe;
            double g = part == 0 ? 1 : 0.55;
            switch (m) {
                case GRASS -> { // blades brushed and pressed, soft soil underneath
                    mix(s, swish(r, 0.07, 800, 3500), at, 0.5 * g);
                    grains(s, r, at, 14, 900, 3000, 0.06, 0.25 * g);
                    mix(s, grain(r, 140, 0.03), at, 0.5 * g);
                }
                case DIRT -> {
                    mix(s, grain(r, 160, 0.035), at, 0.9 * g);
                    grains(s, r, at, 10, 600, 2200, 0.05, 0.2 * g);
                }
                case SAND -> {
                    mix(s, swish(r, 0.11, 700, 3000), at, 0.6 * g);
                    grains(s, r, at, 20, 1200, 3500, 0.08, 0.08 * g);
                    mix(s, grain(r, 150, 0.03), at, 0.3 * g);
                }
                case GRAVEL -> {
                    grains(s, r, at, 35, 900, 4000, 0.1, 0.35 * g);
                    for (int k = 0; k < 3; k++) {
                        float[] pebble = mode(logUniform(r, 1200, 2800), 0.004, 0.025);
                        mix(s, shape(pebble, t -> Math.min(1, t / 0.0005)), at + samples(r.nextDouble() * 0.07), 0.1 * g);
                    }
                    mix(s, grain(r, 150, 0.03), at, 0.4 * g);
                }
                case STONE -> { // a sole on rock: a short, dry knock and a light scrape
                    knock(s, r, at, new double[][] {{900, 0.005, 0.35}, {1900, 0.004, 0.2}, {420, 0.007, 0.35}}, 0.6, g);
                    grains(s, r, at + samples(0.005), 6, 1500, 4500, 0.03, 0.08 * g);
                }
                case WOOD -> knock(s, r, at, new double[][] {{170, 0.016, 0.5}, {400, 0.012, 0.35}, {900, 0.008, 0.2}}, 0.5, g);
                case SNOW -> { // packing snow: grains crowding then stopping short
                    for (int k = 0; k < 45; k++) {
                        double t = Math.sqrt(r.nextDouble()) * 0.11;
                        mix(s, grain(r, logUniform(r, 700, 2500), 0.003), at + samples(t), 0.2 * g * (0.5 + r.nextDouble()));
                    }
                    mix(s, grain(r, 160, 0.03), at, 0.3 * g);
                }
                case WATER -> {
                    mix(s, swish(r, 0.18, 400, 2500), at, 0.7 * g);
                    for (int k = 0; k < 5; k++)
                        mix(s, bubble(logUniform(r, 500, 1500), 0.008, 0.5), at + samples(0.03 + r.nextDouble() * 0.1), 0.12 * g);
                }
                case LEAVES -> {
                    mix(s, swish(r, 0.08, 1000, 4000), at, 0.4 * g);
                    grains(s, r, at, 20, 1200, 4000, 0.08, 0.2 * g);
                }
                case GLASS -> knock(s, r, at, new double[][] {{1600, 0.008, 0.25}, {3100, 0.006, 0.12}, {700, 0.006, 0.3}}, 0.5, g);
            }
        }
        return level(space(lowpass(lowpass(oneShot(s, 0.8), 2600), 2600), 0.5), STEP - 6); // heard from head height: soft, a little distant
    }

    /** A block broken: how it gives way and what falls tell what it was made of. */
    public static float[] breakBlock(Material m, long seed) {
        SplittableRandom r = new SplittableRandom(seed);
        float[] s = new float[samples(0.8)];
        switch (m) {
            case STONE -> {
                knock(s, r, 0, new double[][] {{520, 0.02, 0.45}, {1100, 0.012, 0.3}, {2100, 0.008, 0.15}}, 0.8, 1);
                mix(s, lowpass(burst(r, 0.1, 300, 4000, 0.001, 0.025), 4000), 0, 0.5);
                for (int p = 0; p < 5; p++) { // pebbles falling and bouncing, quieter and quicker each time
                    double t = 0.06 + r.nextDouble() * 0.1, gap = 0.08 + r.nextDouble() * 0.06, a = 0.3;
                    double f = logUniform(r, 1200, 3000);
                    while (t < 0.75 && a > 0.03) {
                        float[] tap = mode(f, 0.004, 0.025);
                        mix(s, shape(tap, x -> Math.min(1, x / 0.0006)), samples(t), a);
                        t += gap;
                        gap *= 0.65;
                        a *= 0.55;
                    }
                }
            }
            case WOOD -> {
                for (int c = 0; c < 4; c++) // the fibres splitting
                    mix(s, lowpass(burst(r, 0.02, 600, 5000, 0.0008, 0.004), 5000), samples(r.nextDouble() * 0.12), 0.7 + r.nextDouble() * 0.4);
                grains(s, r, 0, 20, 900, 3500, 0.15, 0.3);
                knock(s, r, 0, new double[][] {{150, 0.04, 0.45}, {420, 0.025, 0.35}, {950, 0.012, 0.2}}, 0.5, 1);
            }
            case DIRT, GRASS, SAND, GRAVEL -> {
                mix(s, grain(r, 140, 0.06), 0, 1.0);                               // the clod gives
                double lo = m == Material.SAND ? 900 : m == Material.GRAVEL ? 1000 : 400;
                double hi = m == Material.SAND ? 3500 : m == Material.GRAVEL ? 4000 : 1800;
                grains(s, r, samples(0.02), 140, lo, hi, 0.45, m == Material.SAND ? 0.05 : 0.09); // crumbs falling
                float[] trickle = shape(lowpass(highpass(white(samples(0.5), r), lo * 0.8), hi), t -> Math.min(1, t / 0.02) * Math.exp(-t / 0.14));
                mix(s, trickle, samples(0.02), m == Material.SAND ? 0.25 : 0.12);
                if (m == Material.GRASS) mix(s, swish(r, 0.08, 1000, 3500), 0, 0.3);   // roots and blades tearing
            }
            case SNOW -> {
                mix(s, swish(r, 0.15, 300, 2500), 0, 0.6);
                grains(s, r, 0, 50, 600, 2500, 0.25, 0.12);
            }
            case GLASS -> {
                mix(s, lowpass(burst(r, 0.02, 1500, 6000, 0.0005, 0.005), 6000), 0, 0.6); // the crack
                for (int k = 0; k < 35; k++) {                                     // shards tinkling down
                    double t = Math.pow(r.nextDouble(), 1.5) * 0.65, f = logUniform(r, 1800, 5500), tau = 0.015 + r.nextDouble() * 0.05;
                    float[] shard = mode(f, tau, tau * 5);
                    mix(shard, mode(f * (2.3 + r.nextDouble() * 0.6), tau * 0.4, tau * 5), 0, 0.25);
                    shape(shard, x -> Math.min(1, x / 0.0006));
                    mix(s, shard, samples(t), 0.08 * (1 - t));
                }
            }
            case LEAVES -> {
                mix(s, swish(r, 0.25, 900, 4000), 0, 0.6);
                grains(s, r, 0, 60, 1000, 4000, 0.3, 0.15);
                for (int k = 0; k < 2; k++) mix(s, grain(r, 1800, 0.002), samples(r.nextDouble() * 0.2), 0.5); // twigs snapping
            }
            case WATER -> mix(s, splashBody(r), 0, 0.8);
        }
        return level(space(oneShot(s, 0.8), 0.3), BREAK);
    }

    /** A block placed: a short, solid set-down in its own material. */
    public static float[] placeBlock(Material m, long seed) {
        SplittableRandom r = new SplittableRandom(seed);
        float[] s = new float[samples(0.22)];
        switch (m) {
            case STONE -> knock(s, r, 0, new double[][] {{230, 0.015, 0.5}, {520, 0.01, 0.35}, {1150, 0.006, 0.2}}, 0.5, 1);
            case WOOD -> knock(s, r, 0, new double[][] {{150, 0.022, 0.5}, {370, 0.015, 0.35}, {820, 0.009, 0.2}}, 0.5, 1);
            case GLASS -> knock(s, r, 0, new double[][] {{1700, 0.015, 0.3}, {3600, 0.008, 0.12}, {600, 0.008, 0.3}}, 0.4, 1);
            case LEAVES -> mix(s, swish(r, 0.1, 900, 3500), 0, 0.6);
            case SNOW -> mix(s, swish(r, 0.08, 300, 2000), 0, 0.7);
            case GRAVEL -> {
                mix(s, grain(r, 150, 0.04), 0, 0.8);
                grains(s, r, 0, 25, 1000, 4000, 0.09, 0.25);
            }
            case WATER -> mix(s, swish(r, 0.12, 400, 2500), 0, 0.6);
            default -> { // dirt, grass, sand: a soft thump and a few grains settling
                mix(s, grain(r, 150, 0.04), 0, 1);
                grains(s, r, samples(0.01), 12, m == Material.SAND ? 1200 : 600, m == Material.SAND ? 3500 : 2200, 0.06, 0.12);
            }
        }
        return level(space(oneShot(s, 0.8), 0.25), PLACE);
    }

    /** A tool hitting the block while mining (much shorter than breaking). */
    public static float[] hit(Material m, long seed) {
        SplittableRandom r = new SplittableRandom(seed);
        float[] s = new float[samples(0.12)];
        switch (m) {
            case STONE, GLASS -> knock(s, r, 0, new double[][] {{750, 0.008, 0.4}, {1700, 0.005, 0.25}}, 0.6, 1);
            case WOOD -> knock(s, r, 0, new double[][] {{230, 0.012, 0.6}, {600, 0.008, 0.3}}, 0.5, 1);
            case LEAVES -> mix(s, swish(r, 0.06, 1000, 3500), 0, 0.6);
            case SAND -> mix(s, swish(r, 0.06, 700, 3000), 0, 0.7);
            case GRAVEL -> {
                mix(s, grain(r, 160, 0.03), 0, 0.6);
                grains(s, r, 0, 15, 1000, 4000, 0.05, 0.3);
            }
            case SNOW -> mix(s, swish(r, 0.06, 300, 2000), 0, 0.7);
            case WATER -> mix(s, bubble(600 + r.nextDouble() * 300, 0.015, 0.5), 0, 0.6);
            default -> { // dirt (grass adds the blades being cut)
                mix(s, grain(r, 150, 0.035), 0, 0.9);
                grains(s, r, 0, 8, 600, 2200, 0.04, 0.25);
                if (m == Material.GRASS) mix(s, swish(r, 0.05, 900, 3500), 0, 0.35);
            }
        }
        return level(space(oneShot(s, 0.8), 0.25), HIT);
    }

    private static float[] splashBody(SplittableRandom r) {
        float[] s = new float[samples(0.9)];
        mix(s, burst(r, 0.02, 200, 3000, 0.001, 0.006), 0, 0.6);                   // the slap
        mix(s, swish(r, 0.4, 300, 2500), 0, 0.8);                                   // water thrown up
        mix(s, bubble(250 + r.nextDouble() * 60, 0.05, 0.6), samples(0.06), 0.4);   // the cavity closing: "plunk"
        for (int k = 0; k < 30; k++) {
            double t = 0.05 + Math.pow(r.nextDouble(), 1.4) * 0.55;
            mix(s, bubble(logUniform(r, 350, 1600), 0.006 + r.nextDouble() * 0.012, 0.5), samples(t), 0.08 * (1 - t));
        }
        return s;
    }

    /** Falling into water: the slap, the water thrown up, the plunk and the bubbles rising. */
    public static float[] splash(long seed) {
        return level(space(oneShot(splashBody(new SplittableRandom(seed)), 0.8), 0.3), SPLASH);
    }

    /** Interface click: a small, soft tick. */
    public static float[] click(long seed) {
        float[] s = new float[samples(0.04)];
        float[] t = mode(1200, 0.004, 0.03);
        mix(s, shape(t, x -> Math.min(1, x / 0.0008)), 0, 0.6);
        mix(s, shape(mode(2600, 0.002, 0.02), x -> Math.min(1, x / 0.0008)), 0, 0.2);
        return level(oneShot(s, 0.8), CLICK);
    }
}
