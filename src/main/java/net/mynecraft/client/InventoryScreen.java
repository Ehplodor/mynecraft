package net.mynecraft.client;

import net.mynecraft.world.Blocks;
import net.mynecraft.world.Inventory;
import net.mynecraft.world.crafting.CraftingMenu;

/**
 * Inventory and crafting screens, laid out like vanilla's (176 x 166 GUI pixels): crafting grid and
 * result at the top, 3 rows of inventory, the hotbar at the bottom. In creative mode the top area is
 * a palette of every block instead. The same layout serves drawing and mouse hit tests.
 */
final class InventoryScreen {
    private static final int SLOT = 18, COLS = 9;
    private final short[] palette;
    /** Display names (set by the game; ids are shown without it). */
    ItemNames names;

    InventoryScreen(short[] palette) {
        this.palette = palette;
    }

    /** Screen rectangle of a slot: {x, y}; size is SLOT x scale. Menu slots first, then palette entries. */
    private float[] slotPos(Hud hud, CraftingMenu menu, int slot) {
        float s = hud.scale, x0 = (hud.width() - 176 * s) / 2, y0 = (hud.height() - 166 * s) / 2;
        if (slot < Inventory.HOTBAR) return new float[] {x0 + (8 + slot * SLOT) * s, y0 + 142 * s};
        if (slot < Inventory.SIZE) {
            int i = slot - Inventory.HOTBAR;
            return new float[] {x0 + (8 + (i % COLS) * SLOT) * s, y0 + (84 + (i / COLS) * SLOT) * s};
        }
        if (menu == null) { // creative palette, 9 per row in the top area
            int i = slot - Inventory.SIZE;
            return new float[] {x0 + (8 + (i % COLS) * SLOT) * s, y0 + (8 + (i / COLS) * SLOT) * s};
        }
        int n = menu.gridSize;
        float gx = x0 + (n == 2 ? 88 : 30) * s, gy = y0 + (n == 2 ? 18 : 17) * s;
        if (slot < menu.resultSlot()) {
            int i = slot - menu.gridStart();
            return new float[] {gx + (i % n) * SLOT * s, gy + (i / n) * SLOT * s};
        }
        return new float[] {gx + (n * SLOT + 28) * s, gy + (n * SLOT / 2f - SLOT / 2f) * s};
    }

    private int slots(CraftingMenu menu) {
        return menu != null ? menu.slotCount() : Inventory.SIZE + palette.length;
    }

    /** Creative mode only shows the hotbar and the palette (the main inventory is unused there). */
    private static boolean hidden(CraftingMenu menu, int slot) {
        return menu == null && slot >= Inventory.HOTBAR && slot < Inventory.SIZE;
    }

    /** Slot under the mouse (framebuffer pixels), or -1. */
    int slotAt(Hud hud, CraftingMenu menu, double mx, double my) {
        float size = SLOT * hud.scale;
        for (int i = 0; i < slots(menu); i++) {
            if (hidden(menu, i)) continue;
            float[] p = slotPos(hud, menu, i);
            if (mx >= p[0] - hud.scale && mx < p[0] + size - hud.scale && my >= p[1] - hud.scale && my < p[1] + size - hud.scale) return i;
        }
        return -1;
    }

    /** True if the slot index is a creative palette entry; returns its block via {@link #paletteBlock}. */
    boolean isPalette(CraftingMenu menu, int slot) {
        return menu == null && slot >= Inventory.SIZE;
    }

    short paletteBlock(int slot) {
        return palette[slot - Inventory.SIZE];
    }

    /** The item in a slot (menu, palette or creative hotbar). */
    short idAt(CraftingMenu menu, int slot, short[] creativeHotbar) {
        if (menu != null) return menu.id(slot);
        if (slot >= Inventory.SIZE) return palette[slot - Inventory.SIZE];
        return slot < Inventory.HOTBAR ? creativeHotbar[slot] : Blocks.AIR;
    }

    void draw(Hud hud, CraftingMenu menu, Inventory inventory, short[] creativeHotbar, int selectedSlot,
              double mx, double my) {
        float s = hud.scale, x0 = (hud.width() - 176 * s) / 2, y0 = (hud.height() - 166 * s) / 2;
        hud.rect(0, 0, hud.width(), hud.height(), 0, 0, 0, 0.45f);
        hud.rect(x0, y0, 176 * s, 166 * s, 0.78f, 0.78f, 0.78f, 1);
        hud.rect(x0 + s, y0 + s, 174 * s, 164 * s, 0.62f, 0.62f, 0.64f, 1);
        String title = menu == null ? "Blocs" : menu.gridSize == 3 ? "Fabrication" : "Inventaire";
        hud.text(title, x0 + 8 * s, y0 + (menu == null ? -10 : 5) * s, 1, 1, 1);
        int hover = slotAt(hud, menu, mx, my);
        float size = SLOT * s;
        for (int i = 0; i < slots(menu); i++) {
            if (hidden(menu, i)) continue;
            float[] p = slotPos(hud, menu, i);
            boolean result = menu != null && i == menu.resultSlot();
            float pad = result ? 4 * s : 0;
            // Vanilla-style slot: dark frame, lighter well (bigger for the result).
            hud.rect(p[0] - s - pad, p[1] - s - pad, size + 2 * pad, size + 2 * pad, 0.22f, 0.22f, 0.24f, 1);
            hud.rect(p[0] - pad, p[1] - pad, size - 2 * s + 2 * pad, size - 2 * s + 2 * pad, 0.52f, 0.52f, 0.55f, 1);
            if (i == hover) hud.rect(p[0], p[1], size - 2 * s, size - 2 * s, 1, 1, 1, 0.35f);
            short id;
            int n;
            if (menu != null) { id = menu.id(i); n = menu.count(i); }
            else if (i >= Inventory.SIZE) { id = palette[i - Inventory.SIZE]; n = 1; }
            else if (i < Inventory.HOTBAR) { id = creativeHotbar[i]; n = 1; }
            else { id = Blocks.AIR; n = 0; }
            hud.item(id, p[0] + 8 * s - s, p[1] + 8 * s - s, 13 * s);
            hud.count(n, p[0] - s, p[1] - s, size);
        }
        if (menu != null) {
            float[] r = slotPos(hud, menu, menu.resultSlot());
            hud.text("=>", r[0] - 20 * s, r[1] + 4 * s, 0.25f, 0.25f, 0.25f);
        } else {
            float[] sel = slotPos(hud, null, selectedSlot);
            hud.rect(sel[0] - s, sel[1] + size - 3 * s, size, 2 * s, 1, 1, 1, 1); // underline the slot being filled
        }
        if (menu != null && menu.cursorCount > 0) { // the held stack follows the mouse
            hud.item(menu.cursorId, (float) mx, (float) my, 13 * s);
            hud.count(menu.cursorCount, (float) mx - 8 * s, (float) my - 8 * s, size);
        }
        if (hover >= 0) {
            short id = menu != null ? menu.id(hover) : hover >= Inventory.SIZE ? palette[hover - Inventory.SIZE]
                : hover < Inventory.HOTBAR ? creativeHotbar[hover] : Blocks.AIR;
            if (id != Blocks.AIR && (menu == null || menu.cursorCount == 0)) {
                hud.text(names != null ? names.name(id) : net.mynecraft.world.Items.name(id), (float) mx + 10 * s, (float) my - 12 * s, 1, 1, 0.6f);
            }
        }
    }
}
