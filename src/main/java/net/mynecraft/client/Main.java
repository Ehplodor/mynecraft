package net.mynecraft.client;

import net.mynecraft.resource.BlockAppearance;
import net.mynecraft.resource.PackSource;
import net.mynecraft.resource.ResourceManager;
import net.mynecraft.world.Blocks;
import net.mynecraft.world.Chunk;
import net.mynecraft.world.ChunkStreamer;
import net.mynecraft.world.storage.WorldStorage;
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
    private static final float[] SKY = {0.55f, 0.75f, 0.95f};

    private static final long AUTOSAVE_NANOS = 30_000_000_000L;

    private static String option(String[] args, String name, String def) {
        for (int i = 0; i < args.length - 1; i++) if (args[i].equals(name)) return args[i + 1];
        return def;
    }

    public static void main(String[] args) {
        java.util.List<String> argv = java.util.Arrays.asList(args);
        boolean vsync = !argv.contains("--no-vsync");
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
        glClearColor(SKY[0], SKY[1], SKY[2], 1);

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
        WorldRenderer renderer = new WorldRenderer(world, appearance.faceInfo, textures);
        ChunkStreamer streamer = new ChunkStreamer(world, storage, RENDER_RADIUS,
            Math.max(1, Runtime.getRuntime().availableProcessors() / 2), new ChunkStreamer.Listener() {
                public void chunkVisible(int cx, int cz) { renderer.chunkVisible(cx, cz); }
                public void chunkHidden(int cx, int cz) { renderer.chunkHidden(cx, cz); }
            });

        boolean editDemo = argv.contains("--edit-demo");
        Overlay overlay = new Overlay();
        Camera cam = new Camera();
        Player player = new Player();
        if (storage.playerPos != null) {
            player.x = storage.playerPos[0];
            player.y = Math.max(1, Math.min(Chunk.HEIGHT - 2, storage.playerPos[1]));
            player.z = storage.playerPos[2];
        } else {
            player.x = 8.5;
            player.z = 8.5;
            player.y = world.generator.height(8, 8) + 1;
        }
        long lastAutosave = System.nanoTime();
        if (screenshot != null) cam.pitch = -0.35f;
        Input input = new Input(window);

        int[] fbW = new int[1], fbH = new int[1];
        long last = System.nanoTime(), fpsTimer = last;
        int frames = 0;
        double accumulator = 0;
        float far = RENDER_RADIUS * 16f;

        while (!glfwWindowShouldClose(window)) {
            long now = System.nanoTime();
            double dt = Math.min(0.25, (now - last) / 1e9);
            last = now;

            glfwPollEvents();
            streamer.update((int) Math.floor(player.x) >> 4, (int) Math.floor(player.z) >> 4);
            if (now - lastAutosave > AUTOSAVE_NANOS) {
                streamer.autosave();
                lastAutosave = now;
            }
            input.frame(cam, player, dt);
            // Fixed physics step: frame-rate independent and stable collisions.
            accumulator += dt;
            while (accumulator >= PHYSICS_STEP) {
                input.move(cam, player);
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
                int px = hit.x() + hit.nx(), py = hit.y() + hit.ny(), pz = hit.z() + hit.nz();
                if (!player.intersects(px, py, pz) && world.set(px, py, pz, input.selected)) {
                    renderer.blockChanged(px, py, pz);
                }
            }
            if (editDemo && streamer.settled()) {
                // Self-test for incremental remeshing: dig in front, build a pillar crossing a section border.
                for (int y = 0; y < 20; y++) {
                    int bx = (int) Math.floor(player.x) - 3, by = (int) player.y + y, bz = (int) Math.floor(player.z) - 6;
                    if (world.set(bx, by, bz, Blocks.STONE)) renderer.blockChanged(bx, by, bz);
                }
                editDemo = false;
            }

            glfwGetFramebufferSize(window, fbW, fbH);
            glViewport(0, 0, fbW[0], fbH[0]);
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
            renderer.render(cam, fbW[0] / (float) Math.max(1, fbH[0]), far, SKY);
            if (hit != null) {
                overlay.outline(renderer.viewProj(), (float) (hit.x() - cam.pos.x), (float) (hit.y() - cam.pos.y), (float) (hit.z() - cam.pos.z));
            }
            overlay.crosshair(fbW[0], fbH[0]);
            if (screenshot != null && streamer.settled() && renderer.idle()) {
                Screenshot.save(screenshot, fbW[0], fbH[0]);
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
            storage.saveLevel(new double[] {player.x, player.y, player.z});
            storage.close();
        } catch (IOException | InterruptedException e) {
            System.err.println("[storage] save failed: " + e.getMessage());
        }
        streamer.close();
        overlay.dispose();
        renderer.dispose();
        glfwDestroyWindow(window);
        glfwTerminate();
    }
}
