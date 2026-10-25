package net.mynecraft.client;

import net.mynecraft.world.Blocks;
import net.mynecraft.world.Player;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Keyboard/mouse controls. Movement keys use physical positions (WASD on QWERTY = ZQSD on AZERTY).
 * Space jump (fly: up), Shift fly down, Ctrl sprint, F toggle flying, 1-9 pick a hotbar slot, mouse
 * wheel changes the block in the current slot (cycling through every placeable block),
 * left click break, right click place, Esc pause (Q quits while paused), F3 debug screen.
 */
final class Input {
    private static final float SENSITIVITY = 0.0025f;
    private static final double CLICK_REPEAT = 0.25;
    private static final byte[] DEFAULT_HOTBAR = {Blocks.STONE, Blocks.OAK_SLAB, Blocks.GRASS, Blocks.OAK_PLANKS,
        Blocks.COBBLESTONE, Blocks.GLASS, Blocks.OAK_LOG, Blocks.OAK_LEAVES, Blocks.GLOWSTONE};

    private final long window;
    private final double[] mx = new double[1], my = new double[1];
    private double lastX, lastY;
    private boolean first = true, flyKeyWasDown;
    private final boolean[] wasDown = new boolean[GLFW_KEY_LAST + 1];
    private double breakCooldown, placeCooldown;

    /** Default state of every block name except air and fluids, in registry order. */
    private static final byte[] PLACEABLE = placeable();
    private double scroll;

    /** Hotbar contents (editable with the wheel) and the selected slot. */
    final byte[] hotbar = DEFAULT_HOTBAR.clone();
    int slot;
    byte selected = hotbar[0];
    boolean breakRequested, placeRequested;
    /** Left button held (survival mining is continuous). */
    boolean breakHeld;
    /** Creative: the wheel changes the block of the current slot; survival: it moves between slots. */
    boolean creative = true;

    Input(long window) {
        this.window = window;
        glfwSetScrollCallback(window, (w, dx, dy) -> scroll += dy);
    }

    private static byte[] placeable() {
        java.util.List<Byte> ids = new java.util.ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 1; i < Blocks.COUNT; i++) {
            if (!seen.add(Blocks.NAMES[i])) continue; // first state of each name: sources for fluids
            ids.add((byte) i);
        }
        byte[] out = new byte[ids.size()];
        for (int i = 0; i < out.length; i++) out[i] = ids.get(i);
        return out;
    }

    private boolean down(int key) {
        return glfwGetKey(window, key) == GLFW_PRESS;
    }

    private boolean mouse(int button) {
        return glfwGetMouseButton(window, button) == GLFW_PRESS;
    }

    /** True once when the key goes down (edge), for toggles; call once per frame per key. */
    boolean pressed(int key) {
        boolean d = down(key), edge = d && !wasDown[key];
        wasDown[key] = d;
        return edge;
    }

    /** Grabs the mouse for looking around, or releases it (pause). */
    void capture(boolean captured) {
        glfwSetInputMode(window, GLFW_CURSOR, captured ? GLFW_CURSOR_DISABLED : GLFW_CURSOR_NORMAL);
        first = true; // avoid a camera jump from the cursor position change
        scroll = 0;
    }

    /** Once per frame while playing: look, toggles and click actions. */
    void frame(Camera cam, Player player, double dt) {
        glfwGetCursorPos(window, mx, my);
        if (!first) {
            cam.yaw -= (float) (mx[0] - lastX) * SENSITIVITY;
            cam.pitch -= (float) (my[0] - lastY) * SENSITIVITY;
            cam.pitch = Math.max(-1.55f, Math.min(1.55f, cam.pitch));
        }
        first = false;
        lastX = mx[0];
        lastY = my[0];

        boolean fly = down(GLFW_KEY_F) && creative;
        if (fly && !flyKeyWasDown) {
            player.flying = !player.flying;
            player.vy = 0;
        }
        flyKeyWasDown = fly;

        for (int i = 0; i < hotbar.length; i++) if (down(GLFW_KEY_1 + i)) slot = i;
        if (scroll != 0 && !creative) {
            slot = Math.floorMod(slot - (int) Math.signum(scroll), hotbar.length);
            scroll = 0;
        }
        if (scroll != 0) {
            int current = 0;
            for (int i = 0; i < PLACEABLE.length; i++) if (PLACEABLE[i] == hotbar[slot]) current = i;
            hotbar[slot] = PLACEABLE[Math.floorMod(current - (int) Math.signum(scroll), PLACEABLE.length)];
            scroll = 0;
        }
        selected = hotbar[slot];

        breakCooldown = mouse(GLFW_MOUSE_BUTTON_LEFT) ? breakCooldown - dt : 0;
        placeCooldown = mouse(GLFW_MOUSE_BUTTON_RIGHT) ? placeCooldown - dt : 0;
        breakHeld = mouse(GLFW_MOUSE_BUTTON_LEFT);
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
        if (!player.flying && player.fluid != Blocks.FLUID_NONE) speed *= player.fluid == Blocks.FLUID_LAVA ? 0.3 : 0.5;
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
