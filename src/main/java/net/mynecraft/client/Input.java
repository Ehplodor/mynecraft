package net.mynecraft.client;

import net.mynecraft.world.Blocks;
import net.mynecraft.world.Player;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Keyboard/mouse controls. Movement keys use physical positions (WASD on QWERTY = ZQSD on AZERTY).
 * Space jump (fly: up), Shift fly down, Ctrl sprint, F toggle flying, 1-9 pick a hotbar slot, mouse
 * wheel changes the block in the current slot (cycling through every placeable block),
 * left click break, right click place, Esc pause menu (clicked with the mouse), F3 debug screen.
 */
final class Input {
    private static final float SENSITIVITY = 0.0025f;
    private static final double CLICK_REPEAT = 0.25;
    private static final short[] DEFAULT_HOTBAR = {Blocks.STONE, Blocks.OAK_SLAB, Blocks.GRASS, Blocks.OAK_PLANKS,
        Blocks.COBBLESTONE, Blocks.GLASS, Blocks.OAK_LOG, Blocks.OAK_LEAVES, Blocks.GLOWSTONE};

    private final long window;
    private final double[] mx = new double[1], my = new double[1];
    private double lastX, lastY;
    private boolean first = true, flyKeyWasDown;
    private final boolean[] wasDown = new boolean[GLFW_KEY_LAST + 1];
    private double breakCooldown, placeCooldown;

    /** Default state of every block name except air and fluids, in registry order. */
    private static final short[] PLACEABLE = placeable();
    private double scroll;

    /** Hotbar contents (editable with the wheel) and the selected slot. */
    final short[] hotbar = DEFAULT_HOTBAR.clone();
    int slot;
    short selected = hotbar[0];
    boolean breakRequested, placeRequested;
    /** Left button held (survival mining is continuous). */
    boolean breakHeld;
    /** Creative: the wheel changes the block of the current slot; survival: it moves between slots. */
    boolean creative = true;

    Input(long window) {
        this.window = window;
        glfwSetScrollCallback(window, (w, dx, dy) -> scroll += dy);
        glfwSetCharCallback(window, (w, codepoint) -> typed.appendCodePoint(codepoint));
    }

    /** Text typed since the last call (layout-aware characters, for text fields). */
    private final StringBuilder typed = new StringBuilder();

    String takeTyped() {
        String s = typed.toString();
        typed.setLength(0);
        return s;
    }

    private static short[] placeable() {
        java.util.List<Short> ids = new java.util.ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 1; i < Blocks.COUNT; i++) {
            if (!seen.add(Blocks.NAMES[i])) continue; // first state of each name: sources for fluids
            ids.add((short) i);
        }
        short[] out = new short[ids.size()];
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

    private final boolean[] mouseWasDown = new boolean[8];
    private final double[] wx = new double[1], wy = new double[1];
    private final int[] ww = new int[1], wh = new int[1], fw = new int[1], fh = new int[1];

    /** True once when a mouse button goes down (screens use clicks, not held buttons). */
    boolean clicked(int button) {
        boolean d = mouse(button), edge = d && !mouseWasDown[button];
        mouseWasDown[button] = d;
        return edge;
    }

    /** Mouse button held down. */
    boolean held(int button) {
        return mouse(button);
    }

    /** Wheel movement since the last call (screens that zoom), taken away from the hotbar. */
    double takeScroll() {
        double s = scroll;
        scroll = 0;
        return s;
    }

    private final java.util.Map<Character, Integer> keyOfChar = new java.util.HashMap<>();

    /**
     * True once when the key printing this letter goes down, whatever the keyboard layout (GLFW key
     * codes are positions on a US keyboard: M is not where the M is on an AZERTY keyboard).
     */
    boolean pressedChar(char c) {
        Integer key = keyOfChar.computeIfAbsent(c, ch -> {
            for (int k = GLFW_KEY_SPACE; k <= GLFW_KEY_LAST; k++) {
                String name = glfwGetKeyName(k, 0);
                if (name != null && name.equalsIgnoreCase(String.valueOf(ch))) return k;
            }
            return GLFW_KEY_A + Character.toLowerCase(ch) - 'a'; // no layout information: US position
        });
        return key >= 0 && key <= GLFW_KEY_LAST && pressed(key);
    }

    boolean shiftDown() {
        return down(GLFW_KEY_LEFT_SHIFT) || down(GLFW_KEY_RIGHT_SHIFT);
    }

    /** Mouse position in framebuffer pixels (window coordinates scaled for HiDPI screens). */
    double[] mouseInFramebuffer() {
        glfwGetCursorPos(window, wx, wy);
        glfwGetWindowSize(window, ww, wh);
        glfwGetFramebufferSize(window, fw, fh);
        double sx = ww[0] > 0 ? fw[0] / (double) ww[0] : 1, sy = wh[0] > 0 ? fh[0] / (double) wh[0] : 1;
        return new double[] {wx[0] * sx, wy[0] * sy};
    }

    static short[] placeableBlocks() {
        return PLACEABLE.clone();
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
