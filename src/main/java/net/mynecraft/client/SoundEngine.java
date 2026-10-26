package net.mynecraft.client;

import org.lwjgl.BufferUtils;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALCCapabilities;

import java.nio.ShortBuffer;
import java.util.ArrayDeque;
import java.util.SplittableRandom;

import static org.lwjgl.openal.AL10.*;
import static org.lwjgl.openal.ALC10.*;

/**
 * Sound through OpenAL, every sound synthesised at start-up (no audio files: the repository ships
 * no Mojang assets): rain, wind, running water and thunder from shaped noise, footsteps and block
 * sounds from short filtered bursts. Ambient loops follow the weather; rivers and waterfalls are
 * placed in 3D at the nearest running water; thunder comes after the flash, delayed by the
 * distance (sound travels 343 m/s). Without an audio device the engine stays silent.
 */
final class SoundEngine {
    private static final int RATE = 22050;
    enum Step { GRASS, STONE, SAND, WOOD, SNOW, WATER }

    private long device, context;
    private boolean enabled;
    private int rainBuf, windBuf, riverBuf, thunderBuf, stepBuf, breakBuf, placeBuf, splashBuf;
    private int rain, wind;
    private final int[] rivers = new int[3];
    private final int[] oneShots = new int[8];
    private int nextShot;
    private final SplittableRandom random = new SplittableRandom(42);
    private final ArrayDeque<double[]> thunder = new ArrayDeque<>(); // {time, x, y, z, loudness}

    SoundEngine() {
        try {
            device = alcOpenDevice((java.nio.ByteBuffer) null);
            if (device == 0) {
                System.err.println("[sound] no audio device: silent");
                return;
            }
            ALCCapabilities caps = ALC.createCapabilities(device);
            context = alcCreateContext(device, (java.nio.IntBuffer) null);
            alcMakeContextCurrent(context);
            AL.createCapabilities(caps);
            alDistanceModel(AL_INVERSE_DISTANCE_CLAMPED);
            rainBuf = buffer(rain(4));
            windBuf = buffer(wind(6));
            riverBuf = buffer(river(4));
            thunderBuf = buffer(thunder(5));
            stepBuf = buffer(burst(0.09, 900, 0.004, 0.06));
            breakBuf = buffer(burst(0.22, 1600, 0.002, 0.12));
            placeBuf = buffer(thump());
            splashBuf = buffer(burst(0.35, 2600, 0.01, 0.2));
            rain = loop(rainBuf, true);
            wind = loop(windBuf, true);
            for (int i = 0; i < rivers.length; i++) rivers[i] = loop(riverBuf, false);
            for (int i = 0; i < oneShots.length; i++) oneShots[i] = alGenSources();
            enabled = true;
        } catch (Throwable e) {
            System.err.println("[sound] disabled: " + e);
        }
    }

    boolean enabled() {
        return enabled;
    }

    // ---- synthesis ----

    /** Pink noise (Paul Kellet's filter): the hiss of rain or a river, softer than white noise. */
    private double[] pink(int n, long seed) {
        SplittableRandom r = new SplittableRandom(seed);
        double b0 = 0, b1 = 0, b2 = 0, b3 = 0, b4 = 0, b5 = 0, b6 = 0;
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            double w = r.nextDouble() * 2 - 1;
            b0 = 0.99886 * b0 + w * 0.0555179;
            b1 = 0.99332 * b1 + w * 0.0750759;
            b2 = 0.96900 * b2 + w * 0.1538520;
            b3 = 0.86650 * b3 + w * 0.3104856;
            b4 = 0.55000 * b4 + w * 0.5329522;
            b5 = -0.7616 * b5 - w * 0.0168980;
            out[i] = (b0 + b1 + b2 + b3 + b4 + b5 + b6 + w * 0.5362) * 0.11;
            b6 = w * 0.115926;
        }
        return out;
    }

    private double[] rain(double seconds) {
        int n = (int) (seconds * RATE);
        double[] s = pink(n, 1);
        SplittableRandom r = new SplittableRandom(2);
        for (int i = 0; i < n; i++) if (r.nextInt(900) == 0) { // drops ticking on leaves and ground
            double a = 0.25 + r.nextDouble() * 0.35;
            for (int k = 0; k < 160 && i + k < n; k++) s[i + k] += a * Math.exp(-k / 25.0) * (r.nextDouble() * 2 - 1);
        }
        return seamless(s);
    }

    private double[] wind(double seconds) {
        int n = (int) (seconds * RATE);
        SplittableRandom r = new SplittableRandom(3);
        double[] s = new double[n];
        double brown = 0, low = 0;
        for (int i = 0; i < n; i++) {
            brown = (brown + (r.nextDouble() * 2 - 1) * 0.02) * 0.998;
            low += (brown - low) * 0.05;
            double gust = 0.55 + 0.45 * Math.sin(2 * Math.PI * i / n) * Math.sin(2 * Math.PI * 3 * i / n + 1);
            s[i] = low * 6 * gust;
        }
        return seamless(s);
    }

    private double[] river(double seconds) {
        int n = (int) (seconds * RATE);
        double[] s = pink(n, 4);
        double lp = 0;
        for (int i = 0; i < n; i++) { // a rounder, lower rush than rain
            lp += (s[i] - lp) * 0.25;
            s[i] = lp * 1.6;
        }
        SplittableRandom r = new SplittableRandom(5);
        for (int i = 0; i < n; i++) if (r.nextInt(500) == 0) { // bubbles: short rising chirps
            double f = 400 + r.nextDouble() * 900, a = 0.08 + r.nextDouble() * 0.1;
            for (int k = 0; k < 900 && i + k < n; k++)
                s[i + k] += a * Math.sin(2 * Math.PI * (f + k * 0.9) * k / RATE) * Math.exp(-k / 180.0);
        }
        return seamless(s);
    }

    private double[] thunder(double seconds) {
        int n = (int) (seconds * RATE);
        SplittableRandom r = new SplittableRandom(6);
        double[] s = new double[n];
        double brown = 0, low = 0;
        for (int i = 0; i < n; i++) {
            brown = (brown + (r.nextDouble() * 2 - 1) * 0.06) * 0.997;
            low += (brown - low) * 0.08;
            double t = i / (double) RATE;
            double env = (t < 0.05 ? t / 0.05 : 1) * Math.exp(-t / 1.6) * (1 + 0.6 * Math.sin(t * 7 + Math.sin(t * 3)));
            double crack = t < 0.35 ? (r.nextDouble() * 2 - 1) * Math.exp(-t / 0.08) * 0.8 : 0;
            s[i] = low * 5 * env + crack;
        }
        return s;
    }

    /** A short burst of filtered noise (footstep, breaking), {@code cutoff} Hz, attack and decay in seconds. */
    private double[] burst(double seconds, double cutoff, double attack, double decay) {
        int n = (int) (seconds * RATE);
        SplittableRandom r = new SplittableRandom((long) (cutoff * 7 + seconds * 1000));
        double a = 1 - Math.exp(-2 * Math.PI * cutoff / RATE), lp = 0;
        double[] s = new double[n];
        for (int i = 0; i < n; i++) {
            double t = i / (double) RATE;
            lp += ((r.nextDouble() * 2 - 1) - lp) * a;
            s[i] = lp * 2.2 * Math.min(1, t / attack) * Math.exp(-t / decay);
        }
        return s;
    }

    private double[] thump() {
        int n = (int) (0.12 * RATE);
        double[] s = burst(0.12, 1200, 0.002, 0.03);
        for (int i = 0; i < n; i++) s[i] += 0.6 * Math.sin(2 * Math.PI * 140 * i / RATE) * Math.exp(-i / (0.04 * RATE));
        return s;
    }

    /** Cross-fades the end into the start so the loop has no click. */
    private static double[] seamless(double[] s) {
        int f = Math.min(s.length / 4, RATE / 4);
        double[] out = java.util.Arrays.copyOf(s, s.length - f);
        for (int i = 0; i < f; i++) {
            double t = i / (double) f;
            out[i] = s[i] * t + s[s.length - f + i] * (1 - t);
        }
        return out;
    }

    private static int buffer(double[] s) {
        double peak = 1e-6;
        for (double v : s) peak = Math.max(peak, Math.abs(v));
        double gain = Math.min(1, 0.9 / peak);
        ShortBuffer pcm = BufferUtils.createShortBuffer(s.length);
        for (double v : s) pcm.put((short) Math.round(Math.max(-1, Math.min(1, v * gain)) * 32000));
        pcm.flip();
        int b = alGenBuffers();
        alBufferData(b, AL_FORMAT_MONO16, pcm, RATE);
        return b;
    }

    private static int loop(int buffer, boolean ambient) {
        int s = alGenSources();
        alSourcei(s, AL_BUFFER, buffer);
        alSourcei(s, AL_LOOPING, AL_TRUE);
        alSourcef(s, AL_GAIN, 0);
        if (ambient) { // around the listener, not at a place
            alSourcei(s, AL_SOURCE_RELATIVE, AL_TRUE);
            alSource3f(s, AL_POSITION, 0, 0, 0);
        } else {
            alSourcef(s, AL_REFERENCE_DISTANCE, 6);
            alSourcef(s, AL_ROLLOFF_FACTOR, 1.2f);
            alSourcef(s, AL_MAX_DISTANCE, 64);
        }
        alSourcePlay(s);
        return s;
    }

    // ---- per frame ----

    /**
     * Updates the listener and the ambient sounds. {@code rain} 0..1 (0.4 under a roof),
     * {@code wind} blocks per second (stronger high up), {@code water} up to 3 spots of running
     * water {x, y, z, loudness 0..1}, nearest first.
     */
    void update(double x, double y, double z, double yaw, double pitch, float rainLevel, float windSpeed,
                double[][] water, double now) {
        if (!enabled) return;
        alListener3f(AL_POSITION, (float) x, (float) y, (float) z);
        float fx = (float) (-Math.sin(yaw) * Math.cos(pitch)), fy = (float) Math.sin(pitch), fz = (float) (-Math.cos(yaw) * Math.cos(pitch));
        float[] orientation = {fx, fy, fz, 0, 1, 0};
        alListenerfv(AL_ORIENTATION, orientation);
        alSourcef(rain, AL_GAIN, Math.min(1, rainLevel) * 0.7f);
        alSourcef(wind, AL_GAIN, Math.min(0.6f, Math.max(0, (windSpeed - 2) / 14)));
        alSourcef(wind, AL_PITCH, 0.8f + Math.min(0.5f, windSpeed / 30));
        for (int i = 0; i < rivers.length; i++) {
            double[] w = i < water.length ? water[i] : null;
            if (w == null) {
                alSourcef(rivers[i], AL_GAIN, 0);
                continue;
            }
            alSource3f(rivers[i], AL_POSITION, (float) w[0], (float) w[1], (float) w[2]);
            alSourcef(rivers[i], AL_GAIN, (float) Math.min(1, w[3]));
            alSourcef(rivers[i], AL_PITCH, (float) (0.85 + 0.3 * (1 - w[3])));
        }
        while (!thunder.isEmpty() && thunder.peekFirst()[0] <= now) {
            double[] t = thunder.pollFirst();
            play(thunderBuf, t[1], t[2], t[3], (float) t[4], 0.8f + random.nextFloat() * 0.3f, 400);
        }
    }

    /** A lightning strike at (x, y, z) seen at {@code now}: its thunder arrives after the flash. */
    void lightning(double x, double y, double z, double listenerX, double listenerY, double listenerZ, double now) {
        if (!enabled) return;
        double d = Math.sqrt((x - listenerX) * (x - listenerX) + (y - listenerY) * (y - listenerY) + (z - listenerZ) * (z - listenerZ));
        thunder.add(new double[] {now + d / 343, x, y, z, Math.max(0.25, 1 - d / 600)});
    }

    void step(Step kind, double x, double y, double z) {
        if (!enabled) return;
        float pitch = switch (kind) {
            case STONE -> 1.35f;
            case SAND -> 0.8f;
            case WOOD -> 1.1f;
            case SNOW -> 0.7f;
            case WATER -> 1.0f;
            default -> 0.95f;
        };
        play(kind == Step.WATER ? splashBuf : stepBuf, x, y, z, kind == Step.WATER ? 0.35f : 0.45f, pitch * (0.92f + random.nextFloat() * 0.16f), 16);
    }

    void blockBroken(double x, double y, double z) {
        if (enabled) play(breakBuf, x, y, z, 0.8f, 0.85f + random.nextFloat() * 0.3f, 24);
    }

    void blockPlaced(double x, double y, double z) {
        if (enabled) play(placeBuf, x, y, z, 0.8f, 0.9f + random.nextFloat() * 0.2f, 24);
    }

    private void play(int buffer, double x, double y, double z, float gain, float pitch, float maxDistance) {
        int s = oneShots[nextShot];
        nextShot = (nextShot + 1) % oneShots.length;
        alSourceStop(s);
        alSourcei(s, AL_BUFFER, buffer);
        alSource3f(s, AL_POSITION, (float) x, (float) y, (float) z);
        alSourcef(s, AL_GAIN, gain);
        alSourcef(s, AL_PITCH, pitch);
        alSourcef(s, AL_REFERENCE_DISTANCE, Math.max(4, maxDistance / 8));
        alSourcef(s, AL_MAX_DISTANCE, maxDistance);
        alSourcePlay(s);
    }

    void dispose() {
        if (device == 0) return;
        if (context != 0) {
            alcMakeContextCurrent(0);
            alcDestroyContext(context);
        }
        alcCloseDevice(device);
    }
}
