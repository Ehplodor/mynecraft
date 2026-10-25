package net.mynecraft.client;

import net.mynecraft.world.Landscape;
import net.mynecraft.world.TerrainGenerator;
import net.mynecraft.world.weather.Atmosphere;
import net.mynecraft.world.weather.Weather;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.lwjgl.opengl.GL33.*;

/**
 * Minimap in the top right corner, cycled with M: relief, biomes, waters (rivers by flow), clouds
 * and precipitation, weather (temperature at the ground). The ground layer comes from the
 * generator's columns (so it shows unexplored land too) and is computed on a background thread
 * when the player has moved far enough; the colours, with the live weather, are redone twice a
 * second on the main thread from that layer.
 */
final class Minimap {
    enum Mode {
        OFF(""), RELIEF("Relief"), BIOMES("Biomes"), WATERS("Eaux"), CLOUDS("Nuages, pluie"), WEATHER("Température");

        final String label;

        Mode(String label) { this.label = label; }
    }

    static final int SIZE = 160, SCALE = 4; // pixels, blocks per pixel: 640 blocks across
    private static final int[] BIOME_COLORS = {0x1E3C8C, 0x96B4DC, 0xE6D796, 0x3264C8, 0xAAC8E6, 0x3C78D2,
        0x78BE50, 0xEBF0F5, 0x328232, 0x6EAA5A, 0x19501E, 0x3C6E50, 0xC8DCD7, 0x828282, 0xFAFAFF, 0xAAD2F0,
        0xA0965A, 0xBEB45A};

    /** The ground under the map: computed in the background, read by the colouring. */
    private record Ground(int cx, int cz, short[] height, byte[] biome, short[] water, float[] flow, float[] temp) {}

    Mode mode = Mode.OFF;
    private final TerrainGenerator generator;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "minimap");
        t.setDaemon(true);
        return t;
    });
    private Future<Ground> pending;
    private Ground ground;
    private long recoloredAt;
    private final Shader shader;
    private final int texture, vao, vbo, uViewProj, uColor, uFadeEnd;
    private final ByteBuffer pixels = BufferUtils.createByteBuffer(SIZE * SIZE * 4);
    private final FloatBuffer quad = BufferUtils.createFloatBuffer(4 * 5);
    private final float[] matrix = new float[16];

    Minimap(TerrainGenerator generator) {
        this.generator = generator;
        shader = new Shader("/shaders/sky.vert", "/shaders/sky.frag"); // textured quad, colour multiplier
        uViewProj = shader.uniform("uViewProj");
        uColor = shader.uniform("uColor");
        uFadeEnd = shader.uniform("uFadeEnd");
        texture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, texture);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, SIZE, SIZE, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        vao = glGenVertexArrays();
        glBindVertexArray(vao);
        vbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, 4 * 5 * 4, GL_STREAM_DRAW);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 20, 0);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 2, GL_FLOAT, false, 20, 12);
        glEnableVertexAttribArray(1);
        glBindVertexArray(0);
    }

    void cycle() {
        mode = Mode.values()[(mode.ordinal() + 1) % Mode.values().length];
        recoloredAt = 0;
    }

    private Ground compute(int cx, int cz) {
        int n = SIZE * SIZE;
        short[] h = new short[n], water = new short[n];
        byte[] biome = new byte[n];
        float[] flow = new float[n], temp = new float[n];
        net.mynecraft.world.Hydrology hydrology = generator.landscape().hydrology();
        for (int j = 0; j < SIZE; j++)
            for (int i = 0; i < SIZE; i++) {
                int wx = cx + (i - SIZE / 2) * SCALE, wz = cz + (j - SIZE / 2) * SCALE, k = j * SIZE + i;
                Landscape.Column c = generator.column(wx, wz);
                h[k] = (short) c.height();
                biome[k] = c.biome();
                water[k] = (short) Math.max(c.water(), TerrainGenerator.SEA_LEVEL);
                flow[k] = c.river() > 0 ? hydrology.flow(wx, wz) : 0;
                temp[k] = c.temperature();
            }
        return new Ground(cx, cz, h, biome, water, flow, temp);
    }

    /** Keeps the ground layer around the player and recolours; call once per frame. */
    void update(double px, double pz, Weather weather, long now) {
        if (mode == Mode.OFF) return;
        int cx = (int) Math.floor(px / 16) * 16, cz = (int) Math.floor(pz / 16) * 16;
        if (pending != null && pending.isDone()) {
            try {
                ground = pending.get();
                recoloredAt = 0;
            } catch (Exception e) {
                System.err.println("[minimap] " + e);
            }
            pending = null;
        }
        if (pending == null && (ground == null || Math.abs(ground.cx - cx) > 48 || Math.abs(ground.cz - cz) > 48)) {
            pending = worker.submit(() -> compute(cx, cz));
        }
        if (ground == null || now - recoloredAt < 500_000_000L) return;
        recoloredAt = now;
        colour(weather);
        glBindTexture(GL_TEXTURE_2D, texture);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, SIZE, SIZE, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
    }

    /** True once a first ground layer exists (the map can be drawn). */
    boolean ready() {
        return ground != null && mode != Mode.OFF;
    }

    private void colour(Weather weather) {
        Ground g = ground;
        pixels.clear();
        for (int j = 0; j < SIZE; j++)
            for (int i = 0; i < SIZE; i++) {
                int k = j * SIZE + i, h = g.height[k];
                boolean wet = g.water[k] > h;
                // Hill shading: light from the north-west.
                int hw = g.height[j * SIZE + Math.max(0, i - 1)], hn = g.height[Math.max(0, j - 1) * SIZE + i];
                float shade = Math.max(0.55f, Math.min(1.35f, 1 + ((h - hw) + (h - hn)) * 0.06f));
                int rgb = switch (mode) {
                    case BIOMES -> wet && g.flow[k] > 0 ? 0x3C8CFF : BIOME_COLORS[g.biome[k]];
                    case WATERS -> waters(g, k, h, wet);
                    case WEATHER -> mix(relief(g, k, h, wet), temperature(weather, g, i, j, h), 0.65f);
                    default -> relief(g, k, h, wet);
                };
                if (mode != Mode.BIOMES && !wet) rgb = scale(rgb, shade);
                if (mode == Mode.CLOUDS) rgb = clouds(weather, g, i, j, h, scale(rgb, 0.8f));
                pixels.put((byte) (rgb >> 16)).put((byte) (rgb >> 8)).put((byte) rgb).put((byte) 255);
            }
        pixels.flip();
    }

    private static int relief(Ground g, int k, int h, boolean wet) {
        if (wet) {
            int depth = g.water[k] - h;
            if (g.flow[k] > 0) return 0x3C82E6;
            return mix(0x3C78D2, 0x0F2864, Math.min(1, depth / 30f));
        }
        byte b = g.biome[k];
        if (b == Landscape.BEACH || b == Landscape.DRY_PLAINS) return b == Landscape.BEACH ? 0xE6D796 : 0xC8BE6E;
        if (h > TerrainGenerator.ROCK_LINE || b == Landscape.SNOWY_PEAKS || b == Landscape.GLACIER || b == Landscape.SNOWY_PLAINS)
            return b == Landscape.STONY_PEAKS ? 0x8C8C8C : 0xF0F4F8;
        float t = g.temp[k];
        int grass = mix(0x5A9650, 0x82B446, Math.max(0, Math.min(1, t)));
        if (b == Landscape.FOREST || b == Landscape.BIRCH_FOREST || b == Landscape.DARK_FOREST || b == Landscape.TAIGA)
            grass = scale(grass, 0.72f);
        return mix(grass, 0xB4AA82, Math.max(0, Math.min(1, (h - 100) / 40f)));
    }

    private static int waters(Ground g, int k, int h, boolean wet) {
        int grey = 0x50 + Math.max(0, Math.min(0x70, (h - 50)));
        int land = grey << 16 | grey << 8 | grey;
        if (!wet) return land;
        if (g.flow[k] > 0) { // rivers: the more flow, the deeper the blue
            float f = (float) Math.min(1, Math.log10(g.flow[k] / 400f + 1) / 2);
            return mix(0x82C8FF, 0x0A3CC8, f);
        }
        return g.water[k] > TerrainGenerator.SEA_LEVEL ? 0x2896DC : 0x14326E; // lakes above the sea
    }

    private static int temperature(Weather weather, Ground g, int i, int j, int h) {
        Atmosphere.Sample s = weather.at(g.cx + (i - SIZE / 2) * SCALE, g.cz + (j - SIZE / 2) * SCALE, Math.max(h, TerrainGenerator.SEA_LEVEL));
        float t = Math.max(0, Math.min(1, (s.temperature() + 15) / 50f)); // -15 .. 35 deg C
        int[] ramp = {0x3050C8, 0x50B4E6, 0x64C864, 0xF0DC50, 0xE65028};
        float x = t * (ramp.length - 1);
        int a = Math.min(ramp.length - 2, (int) x);
        return mix(ramp[a], ramp[a + 1], x - a);
    }

    private static int clouds(Weather weather, Ground g, int i, int j, int h, int under) {
        Atmosphere.Sample s = weather.at(g.cx + (i - SIZE / 2) * SCALE, g.cz + (j - SIZE / 2) * SCALE, Math.max(h, TerrainGenerator.SEA_LEVEL));
        int c = under;
        if (s.type() != Atmosphere.Precipitation.NONE) {
            int tint = switch (s.type()) {
                case SNOW -> 0xFFFFFF;
                case SLEET -> 0x96E6F0;
                case HAIL -> 0xE650E6;
                default -> 0x2864FF;
            };
            c = mix(c, tint, Math.min(0.75f, 0.25f + s.precipitation() * 8));
        }
        float cover = Math.min(0.85f, s.cloud() / 1.3f);
        int cloud = s.storm() > 0.4f ? 0x5A5A64 : 0xF0F0F0;
        if (s.type() == Atmosphere.Precipitation.NONE || cover < 0.3f) c = mix(c, cloud, cover);
        return c;
    }

    private static int mix(int a, int b, float t) {
        int r = (int) (((a >> 16) & 255) + (((b >> 16) & 255) - ((a >> 16) & 255)) * t);
        int g = (int) (((a >> 8) & 255) + (((b >> 8) & 255) - ((a >> 8) & 255)) * t);
        int bl = (int) ((a & 255) + ((b & 255) - (a & 255)) * t);
        return r << 16 | g << 8 | bl;
    }

    private static int scale(int c, float f) {
        int r = Math.min(255, (int) (((c >> 16) & 255) * f)), g = Math.min(255, (int) (((c >> 8) & 255) * f)),
            b = Math.min(255, (int) ((c & 255) * f));
        return r << 16 | g << 8 | b;
    }

    /** Screen rectangle of the map {x, y, size} in framebuffer pixels (top right corner). */
    float[] rect(Hud hud) {
        float size = SIZE * Math.max(1, hud.scale * 0.75f), m = 4 * hud.scale;
        return new float[] {hud.width() - size - m, m, size};
    }

    /** Draws the map texture; the frame, player marker and label go through the HUD afterwards. */
    void draw(Hud hud) {
        float[] r = rect(hud);
        glUseProgram(shader.program);
        glUniformMatrix4fv(uViewProj, false, new Matrix4f().setOrtho2D(0, hud.width(), hud.height(), 0).get(matrix));
        glUniform4f(uColor, 1, 1, 1, 0.92f);
        glUniform1f(uFadeEnd, 0);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, texture);
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        float x0 = r[0], y0 = r[1], x1 = r[0] + r[2], y1 = r[1] + r[2];
        quad.clear();
        quad.put(x0).put(y0).put(0).put(0).put(0);
        quad.put(x1).put(y0).put(0).put(1).put(0);
        quad.put(x1).put(y1).put(0).put(1).put(1);
        quad.put(x0).put(y1).put(0).put(0).put(1);
        quad.flip();
        glBufferSubData(GL_ARRAY_BUFFER, 0, quad);
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDrawArrays(GL_TRIANGLE_FAN, 0, 4);
        glBindVertexArray(0);
    }

    /** Frame, player position and heading, north mark and the layer's name (HUD pass). */
    void overlay(Hud hud, double px, double pz, double yaw) {
        float[] r = rect(hud);
        float s = hud.scale, size = r[2];
        hud.rect(r[0] - s, r[1] - s, size + 2 * s, s, 0, 0, 0, 0.8f);
        hud.rect(r[0] - s, r[1] + size, size + 2 * s, s, 0, 0, 0, 0.8f);
        hud.rect(r[0] - s, r[1], s, size, 0, 0, 0, 0.8f);
        hud.rect(r[0] + size, r[1], s, size, 0, 0, 0, 0.8f);
        float k = size / SIZE / SCALE; // screen pixels per block
        float mx = r[0] + size / 2 + (float) (px - ground.cx) * k, my = r[1] + size / 2 + (float) (pz - ground.cz) * k;
        float hx = (float) -Math.sin(yaw), hz = (float) -Math.cos(yaw);
        for (int t = 1; t <= 4; t++) // heading: a short line of dots in front of the marker
            hud.rect(mx + hx * t * 2 * s - s / 2, my + hz * t * 2 * s - s / 2, s, s, 1, 1, 1, 1);
        hud.rect(mx - 1.5f * s, my - 1.5f * s, 3 * s, 3 * s, 1, 0.15f, 0.15f, 1);
        hud.textCentered("N", r[0] + size / 2, r[1] + s, 1, 1, 1);
        hud.textCentered(mode.label + " (M)", r[0] + size / 2, r[1] + size + 3 * s, 1, 1, 1);
    }

    void dispose() {
        worker.shutdownNow();
        glDeleteTextures(texture);
        glDeleteBuffers(vbo);
        glDeleteVertexArrays(vao);
        glDeleteProgram(shader.program);
    }
}
