package net.mynecraft.client;

import net.mynecraft.resource.BlockAppearance;
import net.mynecraft.resource.PackSource;
import net.mynecraft.resource.ResourceManager;
import net.mynecraft.world.Blocks;
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

        World world = new World(1337L);
        WorldRenderer renderer = new WorldRenderer(world, appearance.faceInfo, textures);
        renderer.loadArea(RENDER_RADIUS);

        boolean editDemo = argv.contains("--edit-demo");
        Overlay overlay = new Overlay();
        Camera cam = new Camera();
        Player player = new Player();
        player.x = 8.5;
        player.z = 8.5;
        player.y = world.generator.height(8, 8) + 1;
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
            if (hit != null && (input.breakRequested || editDemo)) {
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
            if (editDemo) {
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
            if (screenshot != null && renderer.idle()) {
                Screenshot.save(screenshot, fbW[0], fbH[0]);
                glfwSetWindowShouldClose(window, true);
            }
            glfwSwapBuffers(window);

            frames++;
            if (now - fpsTimer >= 1_000_000_000L) {
                glfwSetWindowTitle(window, String.format("Mynecraft | %d FPS | %d/%d sections | %d quads | %s%s | %.1f %.1f %.1f",
                    frames, renderer.drawnSections, renderer.meshCount(), renderer.totalQuads,
                    Blocks.NAMES[input.selected], player.flying ? " | vol" : "", player.x, player.y, player.z));
                frames = 0;
                fpsTimer = now;
            }
        }

        overlay.dispose();
        renderer.dispose();
        glfwDestroyWindow(window);
        glfwTerminate();
    }
}
