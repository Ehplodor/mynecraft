package net.mynecraft.client;

import net.mynecraft.world.ExploredMap;
import net.mynecraft.world.TerrainGenerator;
import net.mynecraft.world.weather.Weather;
import org.lwjgl.BufferUtils;

import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Minimap in the top right corner, cycled with M: relief, biomes, waters (rivers by flow), clouds
 * and precipitation, temperature, hidden. The ground comes from the generator (see
 * {@link MapPainter}) on a background thread when the player has moved far enough; the colours,
 * with the live weather, are redone twice a second. Waypoints and the player's heading on top.
 */
final class Minimap {
    enum Mode {
        RELIEF, BIOMES, WATERS, CLOUDS, WEATHER, OFF;

        MapPainter.Layer layer() { return this == OFF ? MapPainter.Layer.RELIEF : MapPainter.Layer.values()[ordinal()]; }
    }

    static final int SIZE = 160, SCALE = 4; // pixels, blocks per pixel: 640 blocks across

    Mode mode = Mode.RELIEF;
    private final TerrainGenerator generator;
    private final ExploredMap explored;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "minimap");
        t.setDaemon(true);
        return t;
    });
    private Future<MapPainter.Ground> pending;
    private MapPainter.Ground ground;
    private long recoloredAt;
    private final TexturedQuad quad = new TexturedQuad();
    private final ByteBuffer pixels = BufferUtils.createByteBuffer(SIZE * SIZE * 4);

    Minimap(TerrainGenerator generator, ExploredMap explored) {
        this.generator = generator;
        this.explored = explored;
    }

    void cycle() {
        mode = Mode.values()[(mode.ordinal() + 1) % Mode.values().length];
        recoloredAt = 0;
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
        if (pending == null && (ground == null || Math.abs(ground.cx() - cx) > 48 || Math.abs(ground.cz() - cz) > 48)) {
            pending = worker.submit(() -> MapPainter.compute(generator, cx, cz, SIZE, SIZE, SCALE, () -> false));
        }
        if (ground == null || now - recoloredAt < 500_000_000L) return;
        recoloredAt = now;
        MapPainter.colour(ground, mode.layer(), weather, explored, pixels);
        quad.upload(pixels, SIZE, SIZE);
    }

    /** True once a first ground layer exists (the map can be drawn). */
    boolean ready() {
        return ground != null && mode != Mode.OFF;
    }

    /** Screen rectangle of the map {x, y, size} in framebuffer pixels (top right corner). */
    float[] rect(Hud hud) {
        float size = SIZE * Math.max(1, hud.scale * 0.75f), m = 4 * hud.scale;
        return new float[] {hud.width() - size - m, m, size};
    }

    /** Draws the map texture; the frame, markers and label go through the HUD afterwards. */
    void draw(Hud hud) {
        float[] r = rect(hud);
        quad.draw(hud.width(), hud.height(), r[0], r[1], r[0] + r[2], r[1] + r[2], 0, 0, 1, 1, 0.92f);
    }

    /** Frame, waypoints, player position and heading, north mark and the layer's name (HUD pass). */
    void overlay(Hud hud, double px, double pz, double yaw, Waypoints waypoints) {
        float[] r = rect(hud);
        float s = hud.scale, size = r[2];
        hud.rect(r[0] - s, r[1] - s, size + 2 * s, s, 0, 0, 0, 0.8f);
        hud.rect(r[0] - s, r[1] + size, size + 2 * s, s, 0, 0, 0, 0.8f);
        hud.rect(r[0] - s, r[1], s, size, 0, 0, 0, 0.8f);
        hud.rect(r[0] + size, r[1], s, size, 0, 0, 0, 0.8f);
        float k = size / SIZE / SCALE; // screen pixels per block
        for (Waypoints.Waypoint w : waypoints.list) {
            // Off the map: pinned to its edge, in the right direction.
            float wx = Math.max(0, Math.min(size, size / 2 + (w.x - ground.cx()) * k));
            float wz = Math.max(0, Math.min(size, size / 2 + (w.z - ground.cz()) * k));
            int c = w.color;
            hud.rect(r[0] + wx - 1.5f * s, r[1] + wz - 1.5f * s, 3 * s, 3 * s, (c >> 16 & 255) / 255f, (c >> 8 & 255) / 255f, (c & 255) / 255f, 1);
        }
        float mx = r[0] + size / 2 + (float) (px - ground.cx()) * k, my = r[1] + size / 2 + (float) (pz - ground.cz()) * k;
        float hx = (float) -Math.sin(yaw), hz = (float) -Math.cos(yaw);
        for (int t = 1; t <= 4; t++) // heading: a short line of dots in front of the marker
            hud.rect(mx + hx * t * 2 * s - s / 2, my + hz * t * 2 * s - s / 2, s, s, 1, 1, 1, 1);
        hud.rect(mx - 1.5f * s, my - 1.5f * s, 3 * s, 3 * s, 1, 0.15f, 0.15f, 1);
        hud.textCentered("N", r[0] + size / 2, r[1] + s, 1, 1, 1);
        hud.textCentered(mode.layer().label + " (M)  carte : J", r[0] + size / 2, r[1] + size + 3 * s, 1, 1, 1);
    }

    void dispose() {
        worker.shutdownNow();
        quad.dispose();
    }
}
