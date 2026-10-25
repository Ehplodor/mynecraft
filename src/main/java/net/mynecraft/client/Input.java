package net.mynecraft.client;

import static org.lwjgl.glfw.GLFW.*;

/** Free-fly controls: WASD, Space/Shift up/down, Ctrl to sprint, Esc to quit. */
final class Input {
    private static final float SENSITIVITY = 0.0025f;
    private final long window;
    private final double[] mx = new double[1], my = new double[1];
    private final double[] fwd = new double[3];
    private double lastX, lastY;
    private boolean first = true;

    Input(long window) {
        this.window = window;
    }

    private boolean down(int key) {
        return glfwGetKey(window, key) == GLFW_PRESS;
    }

    void update(Camera cam, float dt) {
        if (down(GLFW_KEY_ESCAPE)) glfwSetWindowShouldClose(window, true);

        glfwGetCursorPos(window, mx, my);
        if (!first) {
            cam.yaw -= (float) (mx[0] - lastX) * SENSITIVITY;
            cam.pitch -= (float) (my[0] - lastY) * SENSITIVITY;
            cam.pitch = Math.max(-1.55f, Math.min(1.55f, cam.pitch));
        }
        first = false;
        lastX = mx[0];
        lastY = my[0];

        cam.forward(fwd);
        double speed = (down(GLFW_KEY_LEFT_CONTROL) ? 60 : 15) * dt;
        double dx = 0, dy = 0, dz = 0;
        if (down(GLFW_KEY_W)) { dx += fwd[0]; dz += fwd[2]; }
        if (down(GLFW_KEY_S)) { dx -= fwd[0]; dz -= fwd[2]; }
        if (down(GLFW_KEY_A)) { dx += fwd[2]; dz -= fwd[0]; }
        if (down(GLFW_KEY_D)) { dx -= fwd[2]; dz += fwd[0]; }
        if (down(GLFW_KEY_SPACE)) dy += 1;
        if (down(GLFW_KEY_LEFT_SHIFT)) dy -= 1;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len > 0) cam.pos.add(dx / len * speed, dy / len * speed, dz / len * speed);
    }
}
