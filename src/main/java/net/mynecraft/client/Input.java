package net.mynecraft.client;

import net.mynecraft.world.Blocks;
import net.mynecraft.world.Player;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Keyboard/mouse controls. Movement keys use physical positions (WASD on QWERTY = ZQSD on AZERTY).
 * Space jump (fly: up), Shift fly down, Ctrl sprint, F toggle flying, 1-9 pick block,
 * left click break, right click place, Esc quit.
 */
final class Input {
    private static final float SENSITIVITY = 0.0025f;
    private static final double CLICK_REPEAT = 0.25;
    private static final byte[] HOTBAR = {Blocks.STONE, Blocks.DIRT, Blocks.GRASS, Blocks.OAK_PLANKS,
        Blocks.COBBLESTONE, Blocks.GLASS, Blocks.OAK_LOG, Blocks.OAK_LEAVES, Blocks.GLOWSTONE};

    private final long window;
    private final double[] mx = new double[1], my = new double[1];
    private double lastX, lastY;
    private boolean first = true, flyKeyWasDown;
    private double breakCooldown, placeCooldown;

    byte selected = HOTBAR[0];
    boolean breakRequested, placeRequested;

    Input(long window) {
        this.window = window;
    }

    private boolean down(int key) {
        return glfwGetKey(window, key) == GLFW_PRESS;
    }

    private boolean mouse(int button) {
        return glfwGetMouseButton(window, button) == GLFW_PRESS;
    }

    /** Once per frame: look, toggles and click actions. */
    void frame(Camera cam, Player player, double dt) {
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

        boolean fly = down(GLFW_KEY_F);
        if (fly && !flyKeyWasDown) {
            player.flying = !player.flying;
            player.vy = 0;
        }
        flyKeyWasDown = fly;

        for (int i = 0; i < HOTBAR.length; i++) if (down(GLFW_KEY_1 + i)) selected = HOTBAR[i];

        breakCooldown = mouse(GLFW_MOUSE_BUTTON_LEFT) ? breakCooldown - dt : 0;
        placeCooldown = mouse(GLFW_MOUSE_BUTTON_RIGHT) ? placeCooldown - dt : 0;
        breakRequested = mouse(GLFW_MOUSE_BUTTON_LEFT) && breakCooldown <= 0;
        placeRequested = mouse(GLFW_MOUSE_BUTTON_RIGHT) && placeCooldown <= 0;
        if (breakRequested) breakCooldown = CLICK_REPEAT;
        if (placeRequested) placeCooldown = CLICK_REPEAT;
    }

    /** Once per physics step: sets the player's wished velocity. */
    void move(Camera cam, Player player) {
        double fx = -Math.sin(cam.yaw), fz = -Math.cos(cam.yaw);
        double dx = 0, dz = 0;
        if (down(GLFW_KEY_W)) { dx += fx; dz += fz; }
        if (down(GLFW_KEY_S)) { dx -= fx; dz -= fz; }
        if (down(GLFW_KEY_A)) { dx += fz; dz -= fx; }
        if (down(GLFW_KEY_D)) { dx -= fz; dz += fx; }
        double len = Math.sqrt(dx * dx + dz * dz);
        boolean sprint = down(GLFW_KEY_LEFT_CONTROL);
        double speed = player.flying ? (sprint ? 30 : 11) : (sprint ? 5.6 : 4.3);
        if (len > 0) { dx = dx / len * speed; dz = dz / len * speed; }
        player.vx = dx;
        player.vz = dz;
        if (player.flying) {
            player.vy = (down(GLFW_KEY_SPACE) ? speed : 0) - (down(GLFW_KEY_LEFT_SHIFT) ? speed : 0);
        } else if (down(GLFW_KEY_SPACE)) {
            player.jump();
        }
    }
}
