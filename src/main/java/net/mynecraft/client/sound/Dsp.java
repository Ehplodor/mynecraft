package net.mynecraft.client.sound;

import java.util.SplittableRandom;

/** Small signal-processing toolbox for the synthesiser: noises, filters, envelopes, resonators. */
final class Dsp {
    static final int RATE = 44100;

    private Dsp() {}

    static int samples(double seconds) {
        return (int) Math.round(seconds * RATE);
    }

    static double white(SplittableRandom r) {
        return r.nextDouble() * 2 - 1;
    }

    /** Pink noise (Paul Kellet's refined filter): equal energy per octave, the hiss of water. */
    static float[] pink(int n, SplittableRandom r) {
        double b0 = 0, b1 = 0, b2 = 0, b3 = 0, b4 = 0, b5 = 0, b6 = 0;
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            double w = white(r);
            b0 = 0.99886 * b0 + w * 0.0555179;
            b1 = 0.99332 * b1 + w * 0.0750759;
            b2 = 0.96900 * b2 + w * 0.1538520;
            b3 = 0.86650 * b3 + w * 0.3104856;
            b4 = 0.55000 * b4 + w * 0.5329522;
            b5 = -0.7616 * b5 - w * 0.0168980;
            out[i] = (float) ((b0 + b1 + b2 + b3 + b4 + b5 + b6 + w * 0.5362) * 0.11);
            b6 = w * 0.115926;
        }
        return out;
    }

    /** Brown (red) noise: integrated white noise, leaky so it stays centred; a deep rumble. */
    static float[] brown(int n, SplittableRandom r) {
        float[] out = new float[n];
        double v = 0;
        for (int i = 0; i < n; i++) {
            v = (v + white(r) * 0.04) * 0.9985;
            out[i] = (float) (v * 3.5);
        }
        return out;
    }

    /**
     * A rumble that small speakers can still play: pink noise kept between {@code lo} and
     * {@code hi} Hz (brown noise puts nearly all its energy under 50 Hz, which laptops do not reproduce).
     */
    static float[] rumble(int n, SplittableRandom r, double lo, double hi) {
        float[] s = pink(n, r);
        highpass(s, lo);
        highpass(s, lo);
        lowpass(s, hi);
        return lowpass(s, hi);
    }

    /** Soft limiter (tanh): tames the loudest transients so the body of a sound can be louder. */
    static float[] soft(float[] s, double drive) {
        double m = 1e-9;
        for (float v : s) m = Math.max(m, Math.abs(v));
        for (int i = 0; i < s.length; i++) s[i] = (float) Math.tanh(s[i] / m * drive);
        return s;
    }

    /** Before looping: no DC and nothing below 30 Hz (inaudible, and it would thump at the seam). */
    static float[] clean(float[] s) {
        return Biquad.highpass(30, 0.707).apply(dc(s));
    }

    static float[] white(int n, SplittableRandom r) {
        float[] out = new float[n];
        for (int i = 0; i < n; i++) out[i] = (float) white(r);
        return out;
    }

    /** RBJ-cookbook biquad. */
    static final class Biquad {
        private double b0, b1, b2, a1, a2, x1, x2, y1, y2;

        static Biquad lowpass(double f, double q) { return new Biquad().set(0, f, q); }

        static Biquad highpass(double f, double q) { return new Biquad().set(1, f, q); }

        static Biquad bandpass(double f, double q) { return new Biquad().set(2, f, q); }

        /** Recomputes the coefficients (sweeping filters), keeping the state. */
        Biquad set(int type, double f, double q) {
            double w = 2 * Math.PI * Math.min(f, RATE * 0.45) / RATE, cos = Math.cos(w), alpha = Math.sin(w) / (2 * q);
            double a0 = 1 + alpha;
            switch (type) {
                case 0 -> { b0 = (1 - cos) / 2; b1 = 1 - cos; b2 = (1 - cos) / 2; }
                case 1 -> { b0 = (1 + cos) / 2; b1 = -(1 + cos); b2 = (1 + cos) / 2; }
                default -> { b0 = alpha; b1 = 0; b2 = -alpha; }
            }
            a1 = -2 * cos;
            a2 = 1 - alpha;
            b0 /= a0; b1 /= a0; b2 /= a0; a1 /= a0; a2 /= a0;
            return this;
        }

        double process(double x) {
            double y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1; x1 = x; y2 = y1; y1 = y;
            return y;
        }

        float[] apply(float[] s) {
            for (int i = 0; i < s.length; i++) s[i] = (float) process(s[i]);
            return s;
        }
    }

    static float[] lowpass(float[] s, double f) {
        return Biquad.lowpass(f, 0.707).apply(s);
    }

    static float[] highpass(float[] s, double f) {
        return Biquad.highpass(f, 0.707).apply(s);
    }

    static float[] bandpass(float[] s, double f, double q) {
        return Biquad.bandpass(f, q).apply(s);
    }

    /** Adds {@code src * gain} into {@code dst} from sample {@code at} (clipped to the buffer). */
    static void mix(float[] dst, float[] src, int at, double gain) {
        for (int i = 0; i < src.length; i++) {
            int k = at + i;
            if (k < 0) continue;
            if (k >= dst.length) break;
            dst[k] += (float) (src[i] * gain);
        }
    }

    /** Adds into a loop buffer, wrapping around its end (events near the end continue at the start). */
    static void mixWrapped(float[] dst, float[] src, int at, double gain) {
        for (int i = 0; i < src.length; i++) dst[Math.floorMod(at + i, dst.length)] += (float) (src[i] * gain);
    }

    /** A damped sine (a struck mode): frequency f Hz, decay time constant tau s. */
    static float[] mode(double f, double tau, double seconds) {
        int n = samples(seconds);
        float[] out = new float[n];
        double w = 2 * Math.PI * f / RATE;
        for (int i = 0; i < n; i++) out[i] = (float) (Math.sin(w * i) * Math.exp(-i / (tau * RATE)));
        return out;
    }

    /**
     * An air bubble ringing in water (Minnaert resonance): a sine whose pitch rises as the bubble
     * nears the surface, dying away in a few tens of milliseconds.
     */
    static float[] bubble(double f, double tau, double rise) {
        int n = samples(Math.min(0.25, tau * 6));
        float[] out = new float[n];
        double phase = 0;
        for (int i = 0; i < n; i++) {
            double t = i / (double) RATE;
            phase += 2 * Math.PI * f * (1 + rise * t / tau / 3) / RATE;
            double attack = Math.min(1, t / 0.0006);
            out[i] = (float) (Math.sin(phase) * attack * Math.exp(-t / tau));
        }
        return out;
    }

    /** A burst of band-limited noise with a fast attack and exponential decay (impacts, grains). */
    static float[] burst(SplittableRandom r, double seconds, double lowCut, double highCut, double attack, double tau) {
        int n = Math.max(1, samples(seconds));
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            double t = i / (double) RATE;
            out[i] = (float) (white(r) * Math.min(1, t / Math.max(attack, 1e-5)) * Math.exp(-t / tau));
        }
        if (lowCut > 0) highpass(out, lowCut);
        if (highCut > 0) lowpass(out, highCut);
        return out;
    }

    /** Multiplies by an envelope given as a function of time (s). */
    static float[] shape(float[] s, java.util.function.DoubleUnaryOperator env) {
        for (int i = 0; i < s.length; i++) s[i] *= (float) env.applyAsDouble(i / (double) RATE);
        return s;
    }

    static float[] scale(float[] s, double g) {
        for (int i = 0; i < s.length; i++) s[i] *= (float) g;
        return s;
    }

    /** Removes any DC offset (a filter's leftover would thump at loop points). */
    static float[] dc(float[] s) {
        double y = 0, x1 = 0;
        for (int i = 0; i < s.length; i++) { // one-pole DC blocker
            double x = s[i];
            y = x - x1 + 0.9995 * y;
            x1 = x;
            s[i] = (float) y;
        }
        return s;
    }

    /**
     * Finishes a one-shot: no DC, a 10 ms fade at the end (a ring cut off at the buffer's end
     * would click), peak at {@code peak}.
     */
    static float[] oneShot(float[] s, double peak) {
        dc(s);
        int f = Math.min(s.length, samples(0.01));
        for (int i = 0; i < f; i++) s[s.length - 1 - i] *= (float) (i / (double) f);
        return normalize(s, peak);
    }

    /**
     * Perceived loudness in dB (full scale = 0): RMS through a rough A-weighting (the ear hears
     * little below 300 Hz and above 10 kHz, most around 2-4 kHz). The quiet tail of a one-shot
     * would drag a plain RMS down, so only the loudest 300 ms window counts.
     */
    static double loudness(float[] s) {
        float[] w = s.clone();
        Biquad.highpass(300, 0.6).apply(w);
        Biquad.lowpass(10000, 0.6).apply(w);
        int win = Math.min(w.length, samples(0.3)), hop = Math.max(1, win / 4);
        double best = 1e-12;
        for (int start = 0; start + win <= w.length; start += hop) {
            double sum = 0;
            for (int i = start; i < start + win; i++) sum += w[i] * w[i];
            best = Math.max(best, sum / win);
        }
        return 10 * Math.log10(best);
    }

    /**
     * Sets a sound to a perceived loudness (dB, e.g. -30), never letting a peak pass 0.9: sounds
     * are mixed by how loud they feel, not by their peaks (a click and a hiss with the same peak
     * are very different in loudness).
     */
    static float[] level(float[] s, double db) {
        double g = Math.pow(10, (db - loudness(s)) / 20);
        double m = 1e-9;
        for (float v : s) m = Math.max(m, Math.abs(v));
        return scale(s, Math.min(g, 0.9 / m));
    }

    /**
     * A few early reflections (ground and nearby surfaces: 6-30 ms, each darker and weaker): a
     * dry synthetic sound sounds as if it were somewhere.
     */
    static float[] space(float[] s, double wet) {
        float[] out = java.util.Arrays.copyOf(s, s.length + samples(0.04));
        double[][] taps = {{0.0063, 0.5}, {0.0117, 0.35}, {0.0171, 0.25}, {0.0233, 0.18}, {0.0297, 0.12}};
        Biquad dark = Biquad.lowpass(3500, 0.7);
        float[] darker = dark.apply(s.clone());
        for (double[] t : taps) mix(out, darker, samples(t[0]), wet * t[1]);
        return out;
    }

    /**
     * A soft grain: band-limited noise under a raised-cosine envelope (no instant attack, which is
     * what makes synthesised crunches sizzle). Centre {@code f} Hz, length {@code seconds}.
     */
    static float[] grain(SplittableRandom r, double f, double seconds) {
        int n = Math.max(8, samples(seconds));
        float[] g = new float[n];
        for (int i = 0; i < n; i++) g[i] = (float) (white(r) * 0.5 * (1 - Math.cos(2 * Math.PI * i / n)));
        Biquad.bandpass(f, 1.2).apply(g);
        return g;
    }

    /** Scales so the peak is {@code peak} (0..1). */
    static float[] normalize(float[] s, double peak) {
        double m = 1e-9;
        for (float v : s) m = Math.max(m, Math.abs(v));
        return scale(s, peak / m);
    }

    /** Scales to an RMS level (loops of different textures at matched loudness), peak-limited. */
    static float[] rms(float[] s, double level) {
        double sum = 0, m = 1e-9;
        for (float v : s) {
            sum += v * v;
            m = Math.max(m, Math.abs(v));
        }
        double g = level / Math.max(1e-9, Math.sqrt(sum / s.length));
        return scale(s, Math.min(g, 0.97 / m));
    }

    /**
     * Makes a seamless loop of {@code length} samples from a longer render: the extra tail is
     * cross-faded (equal power) over the start, so the end flows into the beginning.
     */
    static float[] loop(float[] s, int length) {
        int fade = s.length - length;
        float[] out = java.util.Arrays.copyOf(s, length);
        for (int i = 0; i < fade; i++) {
            double t = (i + 0.5) / fade;
            out[i] = (float) (s[i] * Math.sin(t * Math.PI / 2) + s[length + i] * Math.cos(t * Math.PI / 2));
        }
        return out;
    }

    /** Log-uniform random value between a and b (frequencies, sizes). */
    static double logUniform(SplittableRandom r, double a, double b) {
        return a * Math.pow(b / a, r.nextDouble());
    }
}
