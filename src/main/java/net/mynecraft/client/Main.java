package net.mynecraft.client;

import net.mynecraft.world.World;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33.*;

public final class Main {
    private static final int RENDER_RADIUS = 12; // chunks
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

        World world = new World(1337L);
        WorldRenderer renderer = new WorldRenderer(world);
        renderer.loadArea(RENDER_RADIUS);

        Camera cam = new Camera();
        cam.pos.set(8, world.generator.height(8, 8) + 20, 8);
        cam.pitch = -0.35f;
        Input input = new Input(window);

        int[] fbW = new int[1], fbH = new int[1];
        long last = System.nanoTime(), fpsTimer = last;
        int frames = 0;
        float far = RENDER_RADIUS * 16f;

        while (!glfwWindowShouldClose(window)) {
            long now = System.nanoTime();
            float dt = (now - last) / 1e9f;
            last = now;

            glfwPollEvents();
            input.update(cam, dt);

            glfwGetFramebufferSize(window, fbW, fbH);
            glViewport(0, 0, fbW[0], fbH[0]);
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
            renderer.render(cam, fbW[0] / (float) Math.max(1, fbH[0]), far, SKY);
            if (screenshot != null && renderer.idle()) {
                Screenshot.save(screenshot, fbW[0], fbH[0]);
                glfwSetWindowShouldClose(window, true);
            }
            glfwSwapBuffers(window);

            frames++;
            if (now - fpsTimer >= 1_000_000_000L) {
                glfwSetWindowTitle(window, String.format("Mynecraft | %d FPS | %d/%d sections | %d quads | %.0f %.0f %.0f",
                    frames, renderer.drawnSections, renderer.meshCount(), renderer.totalQuads, cam.pos.x, cam.pos.y, cam.pos.z));
                frames = 0;
                fpsTimer = now;
            }
        }

        renderer.dispose();
        glfwDestroyWindow(window);
        glfwTerminate();
    }
}
