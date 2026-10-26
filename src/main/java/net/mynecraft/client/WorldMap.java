package net.mynecraft.client;

import net.mynecraft.world.ExploredMap;
import net.mynecraft.world.Landscape;
import net.mynecraft.world.TerrainGenerator;
import net.mynecraft.world.weather.Weather;
import org.lwjgl.BufferUtils;

import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Full-screen world map (J): drag to pan, wheel to zoom (1 to 16 blocks per pixel), M for the
 * layer, C to centre on the player, right click to add a waypoint or remove the one under the
 * cursor. Land never seen is dimmed. The picture is computed in the background for the view (with
 * a margin); while it comes, the previous one is shown moved and scaled to the new view.
 */
final class WorldMap {
    private static final int[] SCALES = {1, 2, 4, 8, 16};

    boolean open;
    private double centerX, centerZ;
    private int zoom = 2;
    private MapPainter.Layer layer = MapPainter.Layer.RELIEF;
    private final TerrainGenerator generator;
    private final ExploredMap explored;
    private final Waypoints waypoints;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "world-map");
        t.setDaemon(true);
        return t;
    });
    private Future<MapPainter.Ground> pending;
    private AtomicBoolean cancel = new AtomicBoolean();
    private int[] target = new int[5]; // view of the picture being computed: cx, cz, scale, w, h
    private MapPainter.Ground ground;
    private boolean recolour;
    private long recolouredAt;
    private final TexturedQuad quad = new TexturedQuad();
    private ByteBuffer pixels = BufferUtils.createByteBuffer(4);
    private double dragX = Double.NaN, dragZ;

    WorldMap(TerrainGenerator generator, ExploredMap explored, Waypoints waypoints) {
        this.generator = generator;
        this.explored = explored;
        this.waypoints = waypoints;
    }

    void open(double px, double pz) {
        open = true;
        centerX = px;
        centerZ = pz;
    }

    private int scale() {
        return SCALES[zoom];
    }

    /** Screen pixels per map pixel. */
    private static float cell(Hud hud) {
        return 2 * hud.scale;
    }

    private double toWorldX(Hud hud, double sx) {
        return centerX + (sx - hud.width() / 2.0) / cell(hud) * scale();
    }

    private double toWorldZ(Hud hud, double sy) {
        return centerZ + (sy - hud.height() / 2.0) / cell(hud) * scale();
    }

    private float toScreenX(Hud hud, double wx) {
        return (float) (hud.width() / 2.0 + (wx - centerX) / scale() * cell(hud));
    }

    private float toScreenY(Hud hud, double wz) {
        return (float) (hud.height() / 2.0 + (wz - centerZ) / scale() * cell(hud));
    }

    /** Mouse and keys while open; returns false when the map closes. */
    boolean input(Input input, Hud hud, double px, double pz, int groundY) {
        double[] m = input.mouseInFramebuffer();
        double wheel = input.takeScroll();
        if (wheel != 0) { // zoom around the cursor
            double wx = toWorldX(hud, m[0]), wz = toWorldZ(hud, m[1]);
            zoom = Math.max(0, Math.min(SCALES.length - 1, zoom - (int) Math.signum(wheel)));
            centerX = wx - (m[0] - hud.width() / 2.0) / cell(hud) * scale();
            centerZ = wz - (m[1] - hud.height() / 2.0) / cell(hud) * scale();
        }
        if (input.held(org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT)) {
            if (!Double.isNaN(dragX)) {
                centerX -= (m[0] - dragX) / cell(hud) * scale();
                centerZ -= (m[1] - dragZ) / cell(hud) * scale();
            }
            dragX = m[0];
            dragZ = m[1];
        } else {
            dragX = Double.NaN;
        }
        if (input.clicked(org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_RIGHT)) {
            Waypoints.Waypoint hit = null;
            for (Waypoints.Waypoint w : waypoints.list)
                if (Math.abs(toScreenX(hud, w.x) - m[0]) < 4 * hud.scale && Math.abs(toScreenY(hud, w.z) - m[1]) < 4 * hud.scale) hit = w;
            if (hit != null) waypoints.remove(hit);
            else {
                int wx = (int) Math.floor(toWorldX(hud, m[0])), wz = (int) Math.floor(toWorldZ(hud, m[1]));
                waypoints.add(wx, generator.column(wx, wz).height() + 1, wz);
            }
        }
        if (input.pressedChar('m')) {
            layer = layer.next();
            recolour = true;
        }
        if (input.pressedChar('c')) {
            centerX = px;
            centerZ = pz;
        }
        if (input.pressedChar('j')) open = false; // Esc is handled with the other screens
        return open;
    }

    /** Keeps the picture matching the view; call each frame while open. */
    void update(Hud hud, Weather weather, long now) {
        int w = (int) Math.ceil(hud.width() / cell(hud)) + 16, h = (int) Math.ceil(hud.height() / cell(hud)) + 16;
        int s = scale();
        int cx = (int) Math.round(centerX / s) * s, cz = (int) Math.round(centerZ / s) * s;
        if (pending != null && pending.isDone()) {
            try {
                MapPainter.Ground g = pending.get();
                if (g != null) {
                    ground = g;
                    recolour = true;
                }
            } catch (Exception e) {
                System.err.println("[map] " + e);
            }
            pending = null;
        }
        boolean stale = ground == null || ground.scale() != s || ground.w() != w || ground.h() != h
            || Math.abs(ground.cx() - cx) > w / 8 * s || Math.abs(ground.cz() - cz) > h / 8 * s;
        // A picture being computed for a view left far behind (zoom, long drag) is abandoned.
        if (pending != null && (target[2] != s || target[3] != w || target[4] != h
            || Math.abs(target[0] - cx) > w / 4 * s || Math.abs(target[1] - cz) > h / 4 * s)) cancel.set(true);
        if (stale && pending == null) {
            target = new int[] {cx, cz, s, w, h};
            cancel = new AtomicBoolean();
            AtomicBoolean c = cancel;
            pending = worker.submit(() -> MapPainter.compute(generator, cx, cz, w, h, s, c::get));
        }
        if (ground != null && (recolour || now - recolouredAt > 1_000_000_000L)) {
            if (pixels.capacity() < ground.w() * ground.h() * 4) pixels = BufferUtils.createByteBuffer(ground.w() * ground.h() * 4);
            MapPainter.colour(ground, layer, weather, explored, pixels);
            quad.upload(pixels, ground.w(), ground.h());
            recolour = false;
            recolouredAt = now;
        }
    }

    /** Draws the picture (the HUD overlay follows, see {@link #overlay}). */
    void draw(Hud hud) {
        if (ground == null) return;
        MapPainter.Ground g = ground;
        // The picture's world rectangle, wherever the view is now (it may be an older picture).
        double x0 = g.cx() - (g.w() / 2.0) * g.scale(), z0 = g.cz() - (g.h() / 2.0) * g.scale();
        quad.draw(hud.width(), hud.height(), toScreenX(hud, x0), toScreenY(hud, z0),
            toScreenX(hud, x0 + g.w() * g.scale()), toScreenY(hud, z0 + g.h() * g.scale()), 0, 0, 1, 1, 1);
    }

    /** Markers, cursor information, scale bar and help (HUD pass). */
    void overlay(Hud hud, Input input, double px, double pz, double yaw) {
        float s = hud.scale;
        for (Waypoints.Waypoint w : waypoints.list) {
            float x = toScreenX(hud, w.x), y = toScreenY(hud, w.z);
            int c = w.color;
            hud.rect(x - 2 * s, y - 2 * s, 4 * s, 4 * s, 0, 0, 0, 0.8f);
            hud.rect(x - 1.5f * s, y - 1.5f * s, 3 * s, 3 * s, (c >> 16 & 255) / 255f, (c >> 8 & 255) / 255f, (c & 255) / 255f, 1);
            hud.textCentered(w.name, x, y + 3 * s, 1, 1, 1);
        }
        float mx = toScreenX(hud, px), my = toScreenY(hud, pz);
        float hx = (float) -Math.sin(yaw), hz = (float) -Math.cos(yaw);
        for (int t = 1; t <= 5; t++) hud.rect(mx + hx * t * 2 * s - s / 2, my + hz * t * 2 * s - s / 2, s, s, 1, 1, 1, 1);
        hud.rect(mx - 2 * s, my - 2 * s, 4 * s, 4 * s, 1, 0.15f, 0.15f, 1);

        // Title and cursor information.
        double[] m = input.mouseInFramebuffer();
        int cwx = (int) Math.floor(toWorldX(hud, m[0])), cwz = (int) Math.floor(toWorldZ(hud, m[1]));
        String cursor = "";
        if (ground != null) {
            Landscape.Column c = generator.column(cwx, cwz);
            cursor = String.format("   |   curseur : %d, %d  hauteur %d  %s%s", cwx, cwz, c.height(), Landscape.BIOME_NAMES[c.biome()],
                explored.seen(cwx, cwz) ? "" : " (inexploré)");
        }
        String title = "Carte du monde : " + layer.label + "  (" + scale() + " bloc" + (scale() > 1 ? "s" : "") + "/pixel)" + cursor;
        hud.rect(0, 0, hud.width(), hud.lineHeight() + 4 * s, 0, 0, 0, 0.6f);
        hud.text(title, 3 * s, 2 * s, 1, 1, 1);
        if (pending != null) hud.text("calcul de la carte...", 3 * s, hud.lineHeight() + 6 * s, 1, 0.9f, 0.5f);

        // Scale bar: a round number of blocks about 80 screen pixels long.
        int[] steps = {10, 20, 50, 100, 200, 500, 1000, 2000};
        int blocks = steps[0];
        for (int b : steps) if (b / (double) scale() * cell(hud) <= 90 * s) blocks = b;
        float len = (float) (blocks / (double) scale() * cell(hud)), by = hud.height() - 3 * hud.lineHeight();
        hud.rect(6 * s, by, len, 2 * s, 1, 1, 1, 0.9f);
        hud.text(blocks + " blocs", 6 * s, by + 3 * s, 1, 1, 1);

        String help = "molette : zoom | glisser : déplacer | clic droit : ajouter / retirer un repère | M : couche | C : centrer | J ou Échap : fermer";
        hud.rect(0, hud.height() - hud.lineHeight() - 4 * s, hud.width(), hud.lineHeight() + 4 * s, 0, 0, 0, 0.6f);
        hud.textCentered(help, hud.width() / 2f, hud.height() - hud.lineHeight() - 2 * s, 0.85f, 0.85f, 0.85f);
    }

    /** True once a picture is shown (scripted shots wait for it). */
    boolean ready() {
        return ground != null && pending == null;
    }

    void dispose() {
        worker.shutdownNow();
        quad.dispose();
    }
}
