package net.mynecraft.client;

import net.mynecraft.client.sound.Synth;
import org.lwjgl.BufferUtils;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALCCapabilities;
import org.lwjgl.openal.EXTEfx;

import java.nio.ShortBuffer;
import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.CompletableFuture;

import static org.lwjgl.openal.AL10.*;
import static org.lwjgl.openal.ALC10.*;

/**
 * The game's sound, through OpenAL. Every sound is synthesised at start-up on a background thread
 * ({@link Synth}, several variants each), then uploaded. Ambiences are loops whose levels glide
 * towards targets set by the place, the hour and the weather: rain (light, heavy, on a roof),
 * hail, wind and leaves, crickets at night, the hum of caves; running water and surf are placed
 * in 3D at the nearest brook, river, waterfall or shore. Birds sing now and then by day, drops
 * fall in caves; thunder follows each flash after the time sound takes to travel. With the EFX
 * extension, a reverb follows the surroundings (open, forest, shelter, cave) and outside sounds
 * are muffled under a roof. Without an audio device, everything is silent and nothing breaks.
 */
final class SoundEngine {
    private static final int RATE = 44100, VARIANTS = 3;

    /** What the ambience depends on, filled by the game each frame. */
    static final class Ambience {
        float rain;            // 0..1 intensity of rain or hail where the player is
        boolean hail, snow;
        boolean sheltered;     // something solid overhead
        float wind;            // blocks per second
        float forest;          // 0..1 trees around
        float grass;           // 0..1 open grassland around (crickets)
        float underground;     // 0..1 in a cave
        float daylight;        // 0 night .. 1 day
        double[] coast;        // nearest shore point {x, y, z} or null
        double[][] water = new double[0][]; // {x, y, z, loudness, kind 0 brook / 1 river / 2 fall}
    }

    private long device, context;
    private boolean enabled, efx;
    private CompletableFuture<Map<String, float[][]>> synthesis;
    private final Map<String, int[]> buffers = new HashMap<>();
    private final Map<String, Integer> loops = new HashMap<>();
    private final Map<String, Float> levels = new HashMap<>();
    private final int[] water = new int[9]; // 3 spots x (brook, river, fall)
    private int surf;
    private final int[] shots = new int[16];
    private int nextShot;
    private final SplittableRandom random = new SplittableRandom();
    private final ArrayDeque<double[]> thunder = new ArrayDeque<>(); // {time, x, y, z, gain, near}
    private double nextBird = 5, nextDrip = 2, lastTime;
    private int reverbSlot, reverb, muffle, preset = -1;
    private boolean checked;

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
            efx = caps.ALC_EXT_EFX;
            enabled = true;
            synthesis = CompletableFuture.supplyAsync(SoundEngine::synthesise);
        } catch (Throwable e) {
            System.err.println("[sound] disabled: " + e);
        }
    }

    boolean enabled() {
        return enabled;
    }

    /** All sounds, rendered off the main thread (about a second). */
    private static Map<String, float[][]> synthesise() {
        Map<String, float[][]> m = new HashMap<>();
        m.put("rain_light", new float[][] {Synth.rain(6, 35, 0.25, 1)});
        m.put("rain_heavy", new float[][] {Synth.rain(6, 260, 0.7, 2)});
        m.put("rain_roof", new float[][] {Synth.rainRoof(5, 3)});
        m.put("hail", new float[][] {Synth.hail(5, 4)});
        m.put("wind", new float[][] {Synth.wind(12, 5)});
        m.put("leaves", new float[][] {Synth.leaves(8, 6)});
        m.put("crickets", new float[][] {Synth.crickets(6, 12)});
        m.put("cave", new float[][] {Synth.cave(8, 11)});
        m.put("brook", new float[][] {Synth.brook(6, 7)});
        m.put("river", new float[][] {Synth.river(6, 8)});
        m.put("fall", new float[][] {Synth.waterfall(5, 9)});
        m.put("surf", new float[][] {Synth.surf(12, 10)});
        float[][] birds = new float[18][];
        for (int i = 0; i < birds.length; i++) birds[i] = Synth.bird(i % 6, 100 + i);
        m.put("bird", birds);
        m.put("drip", variants(s -> Synth.drip(s)));
        m.put("thunder_near", variants(s -> Synth.thunder(true, s)));
        m.put("thunder_far", variants(s -> Synth.thunder(false, s)));
        m.put("splash", variants(Synth::splash));
        m.put("click", new float[][] {Synth.click(1)});
        for (Synth.Material mat : Synth.Material.values()) {
            String n = mat.name().toLowerCase();
            m.put("step_" + n, variants(4, s -> Synth.step(mat, s)));
            m.put("break_" + n, variants(s -> Synth.breakBlock(mat, s)));
            m.put("place_" + n, variants(s -> Synth.placeBlock(mat, s)));
            m.put("hit_" + n, variants(s -> Synth.hit(mat, s)));
        }
        return m;
    }

    private static float[][] variants(java.util.function.LongFunction<float[]> make) {
        return variants(VARIANTS, make);
    }

    private static float[][] variants(int count, java.util.function.LongFunction<float[]> make) {
        float[][] v = new float[count][];
        for (int i = 0; i < count; i++) v[i] = make.apply(1000 + i * 7919L);
        return v;
    }

    /** Uploads the synthesised sounds and creates the sources (main thread, once ready). */
    private void upload(Map<String, float[][]> sounds) {
        for (var e : sounds.entrySet()) {
            int[] ids = new int[e.getValue().length];
            for (int i = 0; i < ids.length; i++) {
                float[] s = e.getValue()[i];
                ShortBuffer pcm = BufferUtils.createShortBuffer(s.length);
                for (float v : s) pcm.put((short) Math.round(Math.max(-1, Math.min(1, v)) * 32767));
                pcm.flip();
                ids[i] = alGenBuffers();
                alBufferData(ids[i], AL_FORMAT_MONO16, pcm, RATE);
            }
            buffers.put(e.getKey(), ids);
        }
        for (String n : new String[] {"rain_light", "rain_heavy", "rain_roof", "hail", "wind", "leaves", "crickets", "cave"}) {
            int s = loopSource(buffers.get(n)[0], true);
            loops.put(n, s);
            levels.put(n, 0f);
        }
        String[] kinds = {"brook", "river", "fall"};
        for (int i = 0; i < water.length; i++) water[i] = loopSource(buffers.get(kinds[i % 3])[0], false);
        surf = loopSource(buffers.get("surf")[0], false);
        alSourcef(surf, AL_REFERENCE_DISTANCE, 10);
        alSourcef(surf, AL_MAX_DISTANCE, 90);
        for (int i = 0; i < shots.length; i++) shots[i] = alGenSources();
        if (efx) setUpEfx();
        int count = 0;
        for (int[] v : buffers.values()) count += v.length;
        System.out.println("[sound] " + count + " sounds ready" + (efx ? ", reverb on" : ", no reverb (EFX missing)"));
    }

    private static int loopSource(int buffer, boolean ambient) {
        int s = alGenSources();
        alSourcei(s, AL_BUFFER, buffer);
        alSourcei(s, AL_LOOPING, AL_TRUE);
        alSourcef(s, AL_GAIN, 0);
        if (ambient) { // around the listener rather than at a place
            alSourcei(s, AL_SOURCE_RELATIVE, AL_TRUE);
            alSource3f(s, AL_POSITION, 0, 0, 0);
        } else {
            alSourcef(s, AL_REFERENCE_DISTANCE, 5);
            alSourcef(s, AL_ROLLOFF_FACTOR, 1.1f);
            alSourcef(s, AL_MAX_DISTANCE, 60);
        }
        // Start each loop at a random point so loops sharing a buffer never phase together.
        alSourcef(s, org.lwjgl.openal.AL11.AL_SEC_OFFSET, (float) Math.random() * 3);
        alSourcePlay(s);
        return s;
    }

    // ---- reverb and muffling (EFX) ----

    private void setUpEfx() {
        try {
            reverbSlot = EXTEfx.alGenAuxiliaryEffectSlots();
            reverb = EXTEfx.alGenEffects();
            EXTEfx.alEffecti(reverb, EXTEfx.AL_EFFECT_TYPE, EXTEfx.AL_EFFECT_REVERB);
            muffle = EXTEfx.alGenFilters();
            EXTEfx.alFilteri(muffle, EXTEfx.AL_FILTER_TYPE, EXTEfx.AL_FILTER_LOWPASS);
            for (int s : shots) org.lwjgl.openal.AL11.alSource3i(s, EXTEfx.AL_AUXILIARY_SEND_FILTER, reverbSlot, 0, EXTEfx.AL_FILTER_NULL);
            for (int s : water) org.lwjgl.openal.AL11.alSource3i(s, EXTEfx.AL_AUXILIARY_SEND_FILTER, reverbSlot, 0, EXTEfx.AL_FILTER_NULL);
            org.lwjgl.openal.AL11.alSource3i(surf, EXTEfx.AL_AUXILIARY_SEND_FILTER, reverbSlot, 0, EXTEfx.AL_FILTER_NULL);
            for (int s : shots) alSourcef(s, EXTEfx.AL_AIR_ABSORPTION_FACTOR, 1); // far thunder loses its highs
        } catch (Throwable e) {
            System.err.println("[sound] no reverb: " + e);
            efx = false;
        }
    }

    /** Reverb preset: 0 open air, 1 forest, 2 shelter, 3 cave. */
    private void reverb(int p) {
        if (!efx || p == preset) return;
        preset = p;
        float[][] presets = { // decay s, gain, reflections, late, diffusion
            {0.5f, 0.06f, 0.05f, 0.4f, 0.7f}, {1.1f, 0.14f, 0.1f, 0.6f, 0.9f}, {0.7f, 0.22f, 0.4f, 0.8f, 1f}, {3.4f, 0.5f, 0.3f, 1.4f, 1f}};
        float[] v = presets[p];
        EXTEfx.alEffectf(reverb, EXTEfx.AL_REVERB_DECAY_TIME, v[0]);
        EXTEfx.alEffectf(reverb, EXTEfx.AL_REVERB_GAIN, v[1]);
        EXTEfx.alEffectf(reverb, EXTEfx.AL_REVERB_REFLECTIONS_GAIN, v[2]);
        EXTEfx.alEffectf(reverb, EXTEfx.AL_REVERB_LATE_REVERB_GAIN, v[3]);
        EXTEfx.alEffectf(reverb, EXTEfx.AL_REVERB_DIFFUSION, v[4]);
        EXTEfx.alAuxiliaryEffectSloti(reverbSlot, EXTEfx.AL_EFFECTSLOT_EFFECT, reverb); // reload the changed effect
    }

    // ---- per frame ----

    void update(double x, double y, double z, double yaw, double pitch, Ambience a, double now) {
        if (!enabled) return;
        if (buffers.isEmpty()) {
            if (synthesis == null || !synthesis.isDone()) return;
            try {
                upload(synthesis.join());
            } catch (Throwable e) {
                System.err.println("[sound] disabled: " + e);
                enabled = false;
                return;
            }
        }
        double dt = Math.max(0, Math.min(0.2, now - lastTime));
        lastTime = now;
        alListener3f(AL_POSITION, (float) x, (float) y, (float) z);
        float fx = (float) (-Math.sin(yaw) * Math.cos(pitch)), fy = (float) Math.sin(pitch), fz = (float) (-Math.cos(yaw) * Math.cos(pitch));
        alListenerfv(AL_ORIENTATION, new float[] {fx, fy, fz, 0, 1, 0});

        // Targets for the ambient loops.
        float outside = 1 - a.underground;
        float rain = a.snow ? 0 : a.rain * outside;
        float heavy = smooth(0.35f, 0.8f, a.rain);
        float out = a.sheltered ? 0.25f : 1; // outside sounds through walls
        target("rain_light", a.hail ? 0 : rain * (1 - heavy) * 0.8f * out, dt);
        target("rain_heavy", a.hail ? 0 : rain * heavy * 0.9f * out, dt);
        target("rain_roof", a.sheltered && !a.hail ? rain * 0.9f : 0, dt);
        target("hail", a.hail ? a.rain * outside * (a.sheltered ? 0.5f : 1) : 0, dt);
        float wind = Math.min(1, Math.max(0, (a.wind - 1.5f) / 12)) * (0.15f + 0.85f * outside);
        target("wind", wind * 0.7f * out, dt);
        target("leaves", wind * a.forest * 0.8f * out, dt);
        float night = 1 - smooth(0.2f, 0.6f, a.daylight);
        target("crickets", night * a.grass * outside * (1 - Math.min(1, a.rain * 2)) * 0.5f * out, dt);
        target("cave", a.underground * 0.7f, dt);
        if (efx) {
            reverb(a.underground > 0.5f ? 3 : a.sheltered ? 2 : a.forest > 0.4f ? 1 : 0);
            EXTEfx.alFilterf(muffle, EXTEfx.AL_LOWPASS_GAINHF, a.sheltered ? 0.15f : 1f);
            for (String n : new String[] {"rain_light", "rain_heavy", "wind", "leaves", "crickets"})
                alSourcei(loops.get(n), EXTEfx.AL_DIRECT_FILTER, muffle); // re-attached: the filter's settings are copied
        }

        // Running water: each spot plays the loop of its kind.
        for (int i = 0; i < 3; i++) {
            double[] w = i < a.water.length ? a.water[i] : null;
            for (int k = 0; k < 3; k++) {
                int s = water[i * 3 + k];
                boolean on = w != null && (int) w[4] == k;
                if (on) alSource3f(s, AL_POSITION, (float) w[0], (float) w[1], (float) w[2]);
                alSourcef(s, AL_GAIN, on ? (float) Math.min(1, w[3]) * (k == 2 ? 1.2f : 0.9f) : 0);
            }
        }
        if (a.coast != null) alSource3f(surf, AL_POSITION, (float) a.coast[0], (float) a.coast[1], (float) a.coast[2]);
        alSourcef(surf, AL_GAIN, a.coast != null ? 0.8f : 0);

        // Birds by day (more in woods), not in rain, not underground.
        if (now >= nextBird) {
            if (a.daylight > 0.45f && a.underground < 0.3f && a.rain < 0.15f && !a.sheltered) {
                double angle = random.nextDouble() * Math.PI * 2, d = 10 + random.nextDouble() * 22;
                play("bird", x + Math.cos(angle) * d, y + 4 + random.nextDouble() * 8, z + Math.sin(angle) * d,
                    0.45f, 0.93f + random.nextFloat() * 0.15f, 60);
            }
            nextBird = now + 2 + random.nextDouble() * 9 / (0.35 + a.forest);
        }
        // Drops in caves, here and there around.
        if (now >= nextDrip) {
            if (a.underground > 0.5f)
                play("drip", x + random.nextDouble() * 16 - 8, y + random.nextDouble() * 4, z + random.nextDouble() * 16 - 8,
                    0.5f, 0.85f + random.nextFloat() * 0.3f, 24);
            nextDrip = now + 1.5 + random.nextDouble() * 5;
        }
        if (!checked) { // report a misuse of OpenAL once, rather than failing silently
            checked = true;
            int err = alGetError();
            if (err != AL_NO_ERROR) System.err.println("[sound] OpenAL error 0x" + Integer.toHexString(err));
        }
        while (!thunder.isEmpty() && thunder.peekFirst()[0] <= now) {
            double[] t = thunder.pollFirst();
            play(t[5] > 0 ? "thunder_near" : "thunder_far", t[1], t[2], t[3], (float) t[4], 0.9f + random.nextFloat() * 0.2f, 2000);
        }
    }

    private void target(String loop, float level, double dt) {
        float cur = levels.get(loop);
        cur += (float) ((level - cur) * Math.min(1, dt / 0.6)); // glide, never jump
        levels.put(loop, cur);
        alSourcef(loops.get(loop), AL_GAIN, cur);
    }

    private static float smooth(float e0, float e1, float v) {
        float t = Math.max(0, Math.min(1, (v - e0) / (e1 - e0)));
        return t * t * (3 - 2 * t);
    }

    // ---- events ----

    /** A strike at (x, y, z) seen now: its thunder comes after distance / 343 m/s. */
    void lightning(double x, double y, double z, double lx, double ly, double lz, double now) {
        if (!enabled) return;
        double d = Math.sqrt((x - lx) * (x - lx) + (y - ly) * (y - ly) + (z - lz) * (z - lz));
        thunder.add(new double[] {now + d / 343, x, y, z, Math.max(0.35, 1 - d / 1500), d < 350 ? 1 : 0});
    }

    void step(Synth.Material m, double x, double y, double z) {
        play("step_" + name(m), x, y, z, m == Synth.Material.WATER ? 0.35f : 0.4f, 0.93f + random.nextFloat() * 0.14f, 16);
    }

    void hit(Synth.Material m, double x, double y, double z) {
        play("hit_" + name(m), x, y, z, 0.5f, 0.9f + random.nextFloat() * 0.2f, 20);
    }

    void blockBroken(Synth.Material m, double x, double y, double z) {
        play("break_" + name(m), x, y, z, 0.8f, 0.92f + random.nextFloat() * 0.16f, 28);
    }

    void blockPlaced(Synth.Material m, double x, double y, double z) {
        play("place_" + name(m), x, y, z, 0.7f, 0.92f + random.nextFloat() * 0.16f, 24);
    }

    void splash(double x, double y, double z) {
        play("splash", x, y, z, 0.8f, 0.9f + random.nextFloat() * 0.2f, 24);
    }

    void click() {
        if (!enabled || buffers.isEmpty()) return;
        int s = shot(buffers.get("click")[0]);
        alSourcei(s, AL_SOURCE_RELATIVE, AL_TRUE);
        alSource3f(s, AL_POSITION, 0, 0, 0);
        alSourcef(s, AL_GAIN, 0.5f);
        alSourcef(s, AL_PITCH, 1);
        alSourcePlay(s);
    }

    private static String name(Synth.Material m) {
        return m.name().toLowerCase();
    }

    private void play(String sound, double x, double y, double z, float gain, float pitch, float maxDistance) {
        if (!enabled || buffers.isEmpty()) return;
        int[] v = buffers.get(sound);
        int s = shot(v[random.nextInt(v.length)]);
        alSourcei(s, AL_SOURCE_RELATIVE, AL_FALSE);
        alSource3f(s, AL_POSITION, (float) x, (float) y, (float) z);
        alSourcef(s, AL_GAIN, gain);
        alSourcef(s, AL_PITCH, pitch);
        alSourcef(s, AL_REFERENCE_DISTANCE, Math.max(3, maxDistance / 10));
        alSourcef(s, AL_MAX_DISTANCE, maxDistance);
        alSourcePlay(s);
    }

    /** The next source of the pool, stopped and loaded with a buffer. */
    private int shot(int buffer) {
        int s = shots[nextShot];
        nextShot = (nextShot + 1) % shots.length;
        alSourceStop(s);
        alSourcei(s, AL_BUFFER, buffer);
        return s;
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
