package net.mynecraft.client;

import net.mynecraft.resource.BlockAppearance;
import net.mynecraft.resource.Colormap;
import net.mynecraft.resource.PackSource;
import net.mynecraft.resource.ResourceManager;
import net.mynecraft.world.Blocks;
import net.mynecraft.world.Chunk;
import net.mynecraft.world.ChunkStreamer;
import net.mynecraft.world.storage.WorldStorage;
import net.mynecraft.world.Placement;
import net.mynecraft.world.Player;
import net.mynecraft.world.Raycast;
import net.mynecraft.world.World;

import java.io.IOException;
import java.nio.file.Path;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33.*;

public final class Main {
    private static final int RENDER_RADIUS = 12; // chunks
    private static final double PHYSICS_STEP = 1 / 120.0;
    private static final double REACH = 5;

    private static final long AUTOSAVE_NANOS = 30_000_000_000L;
    private static final double BENCH_SPEED = 20; // blocks per second, about elytra cruising

    private static String option(String[] args, String name, String def) {
        for (int i = 0; i < args.length - 1; i++) if (args[i].equals(name)) return args[i + 1];
        return def;
    }

    /** Height (0..1) within the hit block of the point where the ray enters it through a side face. */
    private static double hitHeight(Raycast.Hit hit, double ox, double oy, double oz, double dx, double dy, double dz) {
        double t;
        if (hit.nx() != 0) t = (hit.x() + (hit.nx() > 0 ? 1 : 0) - ox) / dx;
        else if (hit.nz() != 0) t = (hit.z() + (hit.nz() > 0 ? 1 : 0) - oz) / dz;
        else return hit.ny() > 0 ? 1 : 0;
        return oy + t * dy - hit.y();
    }

    private static void demoSet(World world, WorldRenderer renderer, int x, int y, int z, byte b) {
        if (world.set(x, y, z, b)) renderer.blockChanged(x, y, z);
    }

    public static void main(String[] args) {
        java.util.List<String> argv = java.util.Arrays.asList(args);
        boolean vsync = !argv.contains("--no-vsync") && !argv.contains("--benchmark");
        int shotIdx = argv.indexOf("--screenshot");
        String screenshot = shotIdx >= 0 && shotIdx + 1 < args.length ? args[shotIdx + 1] : null;
        GLFWErrorCallback.createPrint(System.err).set();
        if (!glfwInit()) throw new IllegalStateException("GLFW init failed");
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);

        long window = glfwCreateWindow(1280, 720, "Mynecraft", 0, 0);
        if (window == 0) throw new IllegalStateException("Window creation failed");
        glfwMakeContextCurrent(window);
        glfwSwapInterval(vsync ? 1 : 0);
        GL.createCapabilities();
        glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
        if (glfwRawMouseMotionSupported()) glfwSetInputMode(window, GLFW_RAW_MOUSE_MOTION, GLFW_TRUE);

        glEnable(GL_DEPTH_TEST);
        glEnable(GL_CULL_FACE);

        ResourceManager resources = new ResourceManager().add(PackSource.classpath("defaultpack"));
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals("--pack")) {
                try {
                    resources.add(PackSource.open(Path.of(args[i + 1])));
                } catch (IOException e) {
                    System.err.println("Cannot open pack " + args[i + 1] + ": " + e.getMessage());
                }
            }
        }
        BlockAppearance appearance = new BlockAppearance(resources);
        TextureArray textures = new TextureArray(appearance, resources);
        appearance.warnings.forEach(w -> System.err.println("[resources] " + w));
        textures.warnings.forEach(w -> System.err.println("[resources] " + w));

        Path worldDir = Path.of(option(args, "--world", "saves/world"));
        long newSeed = Long.parseLong(option(args, "--seed", Long.toString(new java.util.Random().nextLong())));
        WorldStorage storage;
        try {
            storage = WorldStorage.open(worldDir, newSeed, argv.contains("--allow-foreign-writes"));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot open world " + worldDir, e);
        }
        if (storage.readOnly) {
            System.err.println("[storage] " + worldDir + " was not created by Mynecraft: opened READ-ONLY."
                + " Use --allow-foreign-writes to save into it (blocks outside y 0..127, entities and"
                + " block properties would be lost).");
        }
        World world = new World(storage.seed);
        BiomeMap biomes = new BiomeMap(world.generator,
            Colormap.load(resources, "grass", 0x91BD59), Colormap.load(resources, "foliage", 0x77AB2F));
        WorldRenderer renderer = new WorldRenderer(world, appearance.faceInfo, textures, biomes, RENDER_RADIUS + 1);
        renderer.occlusion = !argv.contains("--no-occlusion");
        renderer.measureFrustum = screenshot != null;
        if (argv.contains("--fullbright")) renderer.minLight = 1;
        ChunkStreamer streamer = new ChunkStreamer(world, storage, RENDER_RADIUS,
            Math.max(1, Runtime.getRuntime().availableProcessors() / 2), new ChunkStreamer.Listener() {
                public void chunkVisible(int cx, int cz) { renderer.chunkVisible(cx, cz); }
                public void chunkHidden(int cx, int cz) { renderer.chunkHidden(cx, cz); }
            });

        boolean editDemo = argv.contains("--edit-demo");
        Overlay overlay = new Overlay();
        Hud hud = new Hud(appearance.faceInfo);
        SkyRenderer skyRenderer = new SkyRenderer(resources);
        org.joml.Matrix4f skyViewProj = new org.joml.Matrix4f();
        Camera cam = new Camera();
        Player player = new Player();
        if (storage.playerPos != null) {
            player.x = storage.playerPos[0];
            player.y = Math.max(Chunk.MIN_Y + 1, Math.min(Chunk.MAX_Y - 2, storage.playerPos[1]));
            player.z = storage.playerPos[2];
        } else {
            player.x = 8.5;
            player.z = 8.5;
            player.y = world.generator.height(8, 8) + 1;
        }
        String pos = option(args, "--pos", null); // "x,y,z": start there, flying (scripted views)
        if (pos != null) {
            String[] xyz = pos.split(",");
            player.x = Double.parseDouble(xyz[0]);
            player.y = Double.parseDouble(xyz[1]);
            player.z = Double.parseDouble(xyz[2]);
            player.flying = true;
        }
        double ticks = Double.parseDouble(option(args, "--time", Long.toString(storage.dayTime)));
        boolean timeFrozen = screenshot != null; // scripted shots must be reproducible
        float[] sky = new float[3];
        // Benchmark: fly east at a fixed speed and height through fresh terrain, then report timings.
        double benchSeconds = Double.parseDouble(option(args, "--benchmark", "0"));
        FrameStats stats = benchSeconds > 0 ? new FrameStats() : null;
        if (stats != null) {
            player.flying = true;
            player.y = world.generator.height((int) player.x, (int) player.z) + 30;
            cam.yaw = (float) Math.toRadians(-90);
            cam.pitch = (float) Math.toRadians(-15);
            timeFrozen = true;
        }
        long benchStart = System.nanoTime();
        long lastAutosave = System.nanoTime();
        if (screenshot != null) cam.pitch = -0.35f;
        String view = option(args, "--view", null); // "yawDegrees,pitchDegrees", for scripted screenshots
        if (view != null) {
            String[] yp = view.split(",");
            cam.yaw = (float) Math.toRadians(Double.parseDouble(yp[0]));
            cam.pitch = (float) Math.toRadians(Double.parseDouble(yp[1]));
        }
        Input input = new Input(window);

        int[] fbW = new int[1], fbH = new int[1];
        long last = System.nanoTime(), fpsTimer = last;
        int frames = 0;
        double accumulator = 0;
        float far = RENDER_RADIUS * 16f;

        while (!glfwWindowShouldClose(window)) {
            long now = System.nanoTime();
            long frameNanos = now - last;
            double dt = Math.min(0.25, frameNanos / 1e9);
            last = now;

            glfwPollEvents();
            streamer.update((int) Math.floor(player.x) >> 4, (int) Math.floor(player.z) >> 4);
            world.light.drainTouched(k -> renderer.sectionChanged(k, false));
            if (now - lastAutosave > AUTOSAVE_NANOS) {
                streamer.autosave();
                lastAutosave = now;
            }
            if (stats == null) input.frame(cam, player, dt);
            // Fixed physics step: frame-rate independent and stable collisions.
            accumulator += dt;
            while (accumulator >= PHYSICS_STEP) {
                if (stats == null) input.move(cam, player);
                else { player.vx = BENCH_SPEED; player.vz = 0; player.vy = 0; }
                player.step(world, PHYSICS_STEP);
                accumulator -= PHYSICS_STEP;
            }
            cam.pos.set(player.x, player.y + Player.EYE_HEIGHT, player.z);

            double cp = Math.cos(cam.pitch);
            Raycast.Hit hit = Raycast.cast(world, cam.pos.x, cam.pos.y, cam.pos.z,
                -Math.sin(cam.yaw) * cp, Math.sin(cam.pitch), -Math.cos(cam.yaw) * cp, REACH);
            if (hit != null && (input.breakRequested || (editDemo && streamer.settled()))) {
                if (world.get(hit.x(), hit.y(), hit.z()) != Blocks.BEDROCK && world.set(hit.x(), hit.y(), hit.z(), Blocks.AIR)) {
                    renderer.blockChanged(hit.x(), hit.y(), hit.z());
                }
            }
            if (hit != null && input.placeRequested) {
                double dirX = -Math.sin(cam.yaw) * cp, dirY = Math.sin(cam.pitch), dirZ = -Math.cos(cam.yaw) * cp;
                int[] placed = Placement.place(world, player, hit, hitHeight(hit, cam.pos.x, cam.pos.y, cam.pos.z, dirX, dirY, dirZ), input.selected);
                if (placed != null) renderer.blockChanged(placed[0], placed[1], placed[2]);
            }
            if (editDemo && streamer.settled()) {
                // Self-test for incremental remeshing and light: a pillar crossing a section border,
                // and a plank roof casting a shadow with a glowstone lamp and glass underneath.
                int ox = (int) Math.floor(player.x), oy = (int) player.y, oz = (int) Math.floor(player.z);
                for (int y = 0; y < 20; y++) demoSet(world, renderer, ox - 3, oy + y, oz - 6, Blocks.STONE);
                for (int dx = 0; dx < 7; dx++)
                    for (int dz = 0; dz < 7; dz++) demoSet(world, renderer, ox + 1 + dx, oy + 4, oz - 9 + dz, Blocks.OAK_PLANKS);
                demoSet(world, renderer, ox + 4, oy + 3, oz - 6, Blocks.GLOWSTONE);
                demoSet(world, renderer, ox + 2, oy, oz - 4, Blocks.GLASS);
                demoSet(world, renderer, ox + 2, oy + 1, oz - 4, Blocks.GLASS);
                // Horizontal logs along x and z, and a slab staircase.
                for (int i = 0; i < 4; i++) demoSet(world, renderer, ox - 2 + i, oy, oz - 3, Blocks.OAK_LOG_X);
                for (int i = 0; i < 4; i++) demoSet(world, renderer, ox - 3, oy, oz - 3 + i, Blocks.OAK_LOG_Z);
                demoSet(world, renderer, ox + 6, oy, oz - 3, Blocks.OAK_SLAB);
                demoSet(world, renderer, ox + 7, oy, oz - 3, Blocks.OAK_SLAB_DOUBLE);
                demoSet(world, renderer, ox + 8, oy + 1, oz - 3, Blocks.OAK_SLAB);
                demoSet(world, renderer, ox + 6, oy + 2, oz - 2, Blocks.OAK_SLAB_TOP);
                editDemo = false;
            }

            world.light.drainTouched(k -> renderer.sectionChanged(k, true));

            if (!timeFrozen) ticks += dt * 20;
            DayCycle.skyColor(ticks, sky);
            renderer.daylight = DayCycle.daylight(ticks);
            glClearColor(sky[0], sky[1], sky[2], 1);

            glfwGetFramebufferSize(window, fbW, fbH);
            if (stats != null) stats.beginGpu();
            glViewport(0, 0, fbW[0], fbH[0]);
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
            float aspect = fbW[0] / (float) Math.max(1, fbH[0]);
            cam.viewProj(skyViewProj, aspect, far);
            skyRenderer.drawCelestial(skyViewProj, ticks);
            renderer.render(cam, aspect, far, sky);
            skyRenderer.drawClouds(skyViewProj, cam.pos.x, cam.pos.y, cam.pos.z, ticks, renderer.daylight, far);
            if (hit != null) {
                byte target = world.get(hit.x(), hit.y(), hit.z());
                float lo = Blocks.isSlab(target) ? Blocks.BOX_MIN[target & 0xFF] / 16f : 0;
                float hi = Blocks.isSlab(target) ? Blocks.BOX_MAX[target & 0xFF] / 16f : 1;
                overlay.outline(renderer.viewProj(), (float) (hit.x() - cam.pos.x), (float) (hit.y() - cam.pos.y),
                    (float) (hit.z() - cam.pos.z), lo, hi);
            }
            overlay.crosshair(fbW[0], fbH[0]);
            hud.draw(fbW[0], fbH[0], input.hotbar, input.slot, textures.id);
            if (stats != null) {
                stats.endGpu();
                if (frames > 0 || now - fpsTimer > 0) stats.cpuFrame(frameNanos);
                double elapsed = (System.nanoTime() - benchStart) / 1e9;
                if (elapsed >= benchSeconds) {
                    long[] buf = renderer.bufferUsage();
                    System.out.println(stats.report(elapsed) + String.format(
                        " | cull+sort %.3f ms | sections drawn %d/%d meshed | %d quads | blocks+light %.1f MiB (%d chunks) | GPU buffer %.1f MiB",
                        renderer.cullNanos / 1e6 / Math.max(1, renderer.cullFrames),
                        renderer.drawnSections, renderer.meshCount(), renderer.totalQuads,
                        world.storageBytes() / 1048576.0, world.loadedChunks().size(), buf[0] * 32 / 1048576.0));
                    glfwSetWindowShouldClose(window, true);
                }
            }
            if (screenshot != null && streamer.settled() && renderer.idle()) {
                Screenshot.save(screenshot, fbW[0], fbH[0]);
                long[] buf = renderer.bufferUsage();
                System.out.printf("[stats] sections: %d drawn (%d in frustum, %d meshed) | quads: %d | GPU buffer %.1f/%.1f MiB%n",
                    renderer.drawnSections, renderer.frustumSections, renderer.meshCount(), renderer.totalQuads,
                    buf[0] * 32 / 1048576.0, buf[1] * 32 / 1048576.0);
                glfwSetWindowShouldClose(window, true);
            }
            glfwSwapBuffers(window);

            frames++;
            if (now - fpsTimer >= 1_000_000_000L) {
                glfwSetWindowTitle(window, String.format("Mynecraft | %d FPS | %d/%d sections | %d quads | chargement %d | %s%s | %.1f %.1f %.1f",
                    frames, renderer.drawnSections, renderer.meshCount(), renderer.totalQuads, streamer.loadingCount(),
                    Blocks.NAMES[input.selected], player.flying ? " | vol" : "", player.x, player.y, player.z));
                frames = 0;
                fpsTimer = now;
            }
        }

        try {
            streamer.saveAll();
            storage.saveLevel(new double[] {player.x, player.y, player.z}, (long) ticks % DayCycle.DAY_TICKS);
            storage.close();
        } catch (IOException | InterruptedException e) {
            System.err.println("[storage] save failed: " + e.getMessage());
        }
        streamer.close();
        overlay.dispose();
        hud.dispose();
        skyRenderer.dispose();
        renderer.dispose();
        glfwDestroyWindow(window);
        glfwTerminate();
    }
}
