package net.mynecraft.client;

import net.mynecraft.world.Blocks;
import net.mynecraft.world.Inventory;
import net.mynecraft.world.crafting.CraftingMenu;
import net.mynecraft.world.crafting.RecipeBook;
import net.mynecraft.world.crafting.RecipeIndex;

import java.util.ArrayList;
import java.util.List;

/**
 * Recipe viewer beside the inventory, in the spirit of JEI: on the right, every item with a
 * search field (name or id, accents and case ignored) and pages; left click on an item (or R over
 * any item) shows the recipes that make it, right click (or U) the recipes it goes into. The
 * recipe panel on the left shows the grid with each ingredient's alternatives cycling, the result,
 * whether it can be crafted now and how many times, and a "+" button that lays the ingredients in
 * the crafting grid (shift: as many as possible); a recipe too big for the 2x2 grid says that it
 * needs a crafting table. In creative mode, clicking an item in the list puts it in the hotbar.
 */
final class RecipeViewer {
    private static final int SLOT = 18, COLS = 9, ROWS = 7;

    private final RecipeIndex index;
    private final ItemNames names;
    private final short[] all;
    final StringBuilder search = new StringBuilder();
    boolean typing;
    private int page;
    private short shown = Blocks.AIR;    // item whose recipes are shown, AIR = none
    private boolean usages;             // showing what it is used for rather than how it is made
    private int recipe;                 // which of its recipes

    RecipeViewer(RecipeIndex index, ItemNames names, short[] items) {
        this.index = index;
        this.names = names;
        this.all = items;
    }

    private List<Short> filtered() {
        List<Short> out = new ArrayList<>();
        String q = search.toString();
        for (short b : all) if (names.matches(b, q)) out.add(b);
        return out;
    }

    private List<RecipeBook.Recipe> list() {
        if (shown == Blocks.AIR) return List.of();
        return usages ? index.usesOf(shown) : index.recipesFor(shown);
    }

    /** Shows the recipes of an item (or its uses). */
    void show(short item, boolean uses) {
        shown = item;
        usages = uses;
        recipe = 0;
    }

    // ---- layout (GUI pixels times the HUD scale), around the 176 x 166 inventory ----

    private static float invX(Hud hud) { return (hud.width() - 176 * hud.scale) / 2; }

    private static float invY(Hud hud) { return (hud.height() - 166 * hud.scale) / 2; }

    private static float listX(Hud hud) { return invX(hud) + 182 * hud.scale; }

    private static float panelX(Hud hud) { return invX(hud) - 142 * hud.scale; }

    /** Index in the filtered list of the item under the mouse, or -1. */
    private int itemAt(Hud hud, double mx, double my, List<Short> items) {
        float s = hud.scale, x0 = listX(hud) + 4 * s, y0 = invY(hud) + 22 * s;
        int col = (int) Math.floor((mx - x0) / (SLOT * s)), row = (int) Math.floor((my - y0) / (SLOT * s));
        if (col < 0 || col >= COLS || row < 0 || row >= ROWS) return -1;
        int i = page * COLS * ROWS + row * COLS + col;
        return i < items.size() ? i : -1;
    }

    /** The item under the mouse in the list (for R / U), or AIR. */
    short hovered(Hud hud, double mx, double my) {
        List<Short> items = filtered();
        int i = itemAt(hud, mx, my, items);
        return i < 0 ? Blocks.AIR : items.get(i);
    }

    private boolean inside(double mx, double my, float x, float y, float w, float h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    /**
     * A click; returns true if the viewer took it. {@code menu} is null in creative mode, where a
     * click on an item puts it in {@code hotbar[slot]}.
     */
    boolean click(Hud hud, double mx, double my, boolean right, boolean shift, CraftingMenu menu, short[] hotbar, int slot) {
        float s = hud.scale, lx = listX(hud), y0 = invY(hud);
        List<Short> items = filtered();
        int pages = Math.max(1, (items.size() + COLS * ROWS - 1) / (COLS * ROWS));
        typing = inside(mx, my, lx, y0, 170 * s, 16 * s);
        if (typing) return true;
        if (inside(mx, my, lx, y0 + 152 * s, 20 * s, 14 * s)) { page = (page + pages - 1) % pages; return true; }
        if (inside(mx, my, lx + 150 * s, y0 + 152 * s, 20 * s, 14 * s)) { page = (page + 1) % pages; return true; }
        int i = itemAt(hud, mx, my, items);
        if (i >= 0) {
            if (menu == null && !right) hotbar[slot] = items.get(i);
            else show(items.get(i), right);
            return true;
        }
        if (shown == Blocks.AIR) return false;
        float px = panelX(hud);
        if (!inside(mx, my, px, y0, 136 * s, 166 * s)) return false;
        List<RecipeBook.Recipe> list = list();
        if (inside(mx, my, px + 120 * s, y0 + 2 * s, 14 * s, 12 * s)) { shown = Blocks.AIR; return true; } // close
        if (!list.isEmpty()) {
            if (inside(mx, my, px + 4 * s, y0 + 148 * s, 20 * s, 14 * s)) recipe = (recipe + list.size() - 1) % list.size();
            if (inside(mx, my, px + 112 * s, y0 + 148 * s, 20 * s, 14 * s)) recipe = (recipe + 1) % list.size();
            RecipeBook.Recipe r = list.get(recipe % list.size());
            if (menu != null && inside(mx, my, px + 100 * s, y0 + 118 * s, 30 * s, 16 * s))
                RecipeIndex.fill(menu, r, shift ? 64 : 1);
            // A click on an ingredient or the result: follow it.
            short under = ingredientAt(hud, mx, my, r);
            if (under != Blocks.AIR) show(under, right);
        }
        return true;
    }

    /** Wheel over the list turns its pages; over the recipe panel, the recipes. */
    void scroll(Hud hud, double mx, double my, double wheel) {
        float s = hud.scale;
        if (wheel == 0) return;
        int dir = wheel > 0 ? -1 : 1;
        if (inside(mx, my, listX(hud), invY(hud), 170 * s, 166 * s)) {
            int pages = Math.max(1, (filtered().size() + COLS * ROWS - 1) / (COLS * ROWS));
            page = Math.floorMod(page + dir, pages);
        } else if (shown != Blocks.AIR && !list().isEmpty()) {
            recipe = Math.floorMod(recipe + dir, list().size());
        }
    }

    void type(String chars, boolean backspace) {
        if (!typing) return;
        for (char c : chars.toCharArray()) if (c >= ' ' && search.length() < 24) search.append(c);
        if (backspace && search.length() > 0) search.setLength(search.length() - 1);
        page = 0;
    }

    // ---- drawing ----

    private static int gridSize(RecipeBook.Recipe r) {
        return RecipeIndex.gridNeeded(r);
    }

    private float[] cellPos(Hud hud, int size, int c) {
        float s = hud.scale, gx = panelX(hud) + 10 * s, gy = invY(hud) + 40 * s;
        return new float[] {gx + (c % size) * SLOT * s, gy + (c / size) * SLOT * s};
    }

    private short ingredientAt(Hud hud, double mx, double my, RecipeBook.Recipe r) {
        int size = gridSize(r);
        RecipeBook.Ingredient[] cells = RecipeIndex.layout(r, size);
        float s = hud.scale;
        for (int c = 0; c < cells.length; c++) {
            float[] p = cellPos(hud, size, c);
            if (cells[c] != null && inside(mx, my, p[0], p[1], SLOT * s, SLOT * s)) return cycle(cells[c]);
        }
        float[] res = resultPos(hud);
        return inside(mx, my, res[0], res[1], SLOT * s, SLOT * s) ? r.result() : Blocks.AIR;
    }

    private float[] resultPos(Hud hud) {
        float s = hud.scale;
        return new float[] {panelX(hud) + 104 * s, invY(hud) + 58 * s};
    }

    /** An ingredient accepting several items shows them in turn, one per second. */
    private static short cycle(RecipeBook.Ingredient ing) {
        Short[] options = ing.accepts().stream().sorted().toArray(Short[]::new);
        return options[(int) ((System.currentTimeMillis() / 1000) % options.length)];
    }

    private void slot(Hud hud, float x, float y, short id, int count) {
        float s = hud.scale, size = SLOT * s;
        hud.rect(x - s, y - s, size, size, 0.22f, 0.22f, 0.24f, 1);
        hud.rect(x, y, size - 2 * s, size - 2 * s, 0.52f, 0.52f, 0.55f, 1);
        if (id != Blocks.AIR) {
            hud.item(id, x + 7 * s, y + 7 * s, 13 * s);
            if (count > 1) hud.count(count, x - s, y - s, size);
        }
    }

    void draw(Hud hud, CraftingMenu menu, Inventory inventory, double mx, double my) {
        float s = hud.scale, lx = listX(hud), y0 = invY(hud);
        // Item list with its search field.
        hud.rect(lx, y0, 170 * s, 166 * s, 0.78f, 0.78f, 0.78f, 1);
        hud.rect(lx + s, y0 + s, 168 * s, 164 * s, 0.62f, 0.62f, 0.64f, 1);
        hud.rect(lx + 3 * s, y0 + 3 * s, 164 * s, 13 * s, typing ? 0.1f : 0.2f, typing ? 0.1f : 0.2f, typing ? 0.12f : 0.22f, 1);
        String q = search.length() == 0 && !typing ? "Rechercher..." : search + (typing && System.currentTimeMillis() / 500 % 2 == 0 ? "_" : "");
        hud.text(q, lx + 5 * s, y0 + 5 * s, search.length() == 0 && !typing ? 0.6f : 1, 1, 1);
        List<Short> items = filtered();
        int pages = Math.max(1, (items.size() + COLS * ROWS - 1) / (COLS * ROWS));
        page = Math.min(page, pages - 1);
        int hover = itemAt(hud, mx, my, items);
        for (int k = 0; k < COLS * ROWS; k++) {
            int i = page * COLS * ROWS + k;
            if (i >= items.size()) break;
            float x = lx + 4 * s + (k % COLS) * SLOT * s, y = y0 + 22 * s + (k / COLS) * SLOT * s;
            slot(hud, x + s, y + s, items.get(i), 1);
            if (i == hover) hud.rect(x + s, y + s, (SLOT - 2) * s, (SLOT - 2) * s, 1, 1, 1, 0.35f);
        }
        hud.text("<", lx + 8 * s, y0 + 154 * s, 1, 1, 1);
        hud.textCentered((page + 1) + " / " + pages + "   (" + items.size() + ")", lx + 85 * s, y0 + 154 * s, 0.9f, 0.9f, 0.9f);
        hud.text(">", lx + 158 * s, y0 + 154 * s, 1, 1, 1);

        if (shown != Blocks.AIR) drawRecipe(hud, menu, inventory);

        // Tooltip of the hovered list item, with the keys.
        if (hover >= 0) {
            String tip = names.name(items.get(hover)) + "   (clic : recettes, clic droit : utilisations)";
            hud.rect((float) mx + 8 * s, (float) my - 14 * s, hud.textWidth(tip) + 4 * s, hud.lineHeight(), 0.1f, 0.05f, 0.15f, 0.9f);
            hud.text(tip, (float) mx + 10 * s, (float) my - 13 * s, 1, 1, 1);
        }
    }

    private void drawRecipe(Hud hud, CraftingMenu menu, Inventory inventory) {
        float s = hud.scale, px = panelX(hud), y0 = invY(hud);
        hud.rect(px, y0, 136 * s, 166 * s, 0.78f, 0.78f, 0.78f, 1);
        hud.rect(px + s, y0 + s, 134 * s, 164 * s, 0.62f, 0.62f, 0.64f, 1);
        hud.text("x", px + 125 * s, y0 + 4 * s, 0.2f, 0.2f, 0.2f);
        hud.text(usages ? "Utilisations de" : "Recettes de", px + 5 * s, y0 + 5 * s, 0.15f, 0.15f, 0.15f);
        hud.text(names.name(shown), px + 5 * s, y0 + 17 * s, 0.05f, 0.05f, 0.35f);
        List<RecipeBook.Recipe> list = list();
        if (list.isEmpty()) {
            hud.text(usages ? "N'entre dans aucune" : "Aucune recette :", px + 5 * s, y0 + 50 * s, 0.2f, 0.2f, 0.2f);
            hud.text(usages ? "recette connue." : "se trouve dans le monde.", px + 5 * s, y0 + 62 * s, 0.2f, 0.2f, 0.2f);
            return;
        }
        recipe = Math.floorMod(recipe, list.size());
        RecipeBook.Recipe r = list.get(recipe);
        int size = gridSize(r);
        RecipeBook.Ingredient[] cells = RecipeIndex.layout(r, size);
        for (int c = 0; c < cells.length; c++) {
            float[] p = cellPos(hud, size, c);
            slot(hud, p[0], p[1], cells[c] == null ? Blocks.AIR : cycle(cells[c]), 1);
        }
        float[] res = resultPos(hud);
        hud.text("=>", res[0] - 20 * s, res[1] + 4 * s, 0.25f, 0.25f, 0.25f);
        slot(hud, res[0], res[1], r.result(), r.count());
        if (r.width() == 0) hud.text("sans forme", px + 10 * s, y0 + 98 * s, 0.3f, 0.3f, 0.3f);

        // Can it be made here and now?
        int times = 0;
        if (inventory != null) {
            short[] ids = inventory.ids.clone();
            int[] counts = inventory.counts.clone();
            if (menu != null) { // what lies in the grid counts too
                Inventory pool = new Inventory();
                System.arraycopy(ids, 0, pool.ids, 0, Inventory.SIZE);
                System.arraycopy(counts, 0, pool.counts, 0, Inventory.SIZE);
                for (int i = 0; i < menu.gridIds.length; i++) pool.add(menu.gridIds[i], menu.gridCounts[i]);
                ids = pool.ids;
                counts = pool.counts;
            }
            times = RecipeIndex.craftable(r, ids, counts);
        }
        String status;
        float sr = 0.6f, sg = 0.1f, sb = 0.1f;
        if (menu == null) { status = "(mode créatif)"; sr = sg = sb = 0.3f; }
        else if (size > menu.gridSize) status = "Établi requis";
        else if (times == 0) status = "Ingrédients manquants";
        else { status = "Fabricable x" + times; sr = 0.05f; sg = 0.4f; sb = 0.05f; }
        hud.text(status, px + 6 * s, y0 + 108 * s, sr, sg, sb);
        if (menu != null && size <= menu.gridSize && times > 0) { // fill the grid
            hud.rect(px + 100 * s, y0 + 118 * s, 30 * s, 16 * s, 0.25f, 0.5f, 0.25f, 1);
            hud.textCentered("+", px + 115 * s, y0 + 121 * s, 1, 1, 1);
            hud.text("maj : max", px + 6 * s, y0 + 122 * s, 0.3f, 0.3f, 0.3f);
        }
        hud.text("<", px + 9 * s, y0 + 151 * s, 1, 1, 1);
        hud.textCentered((recipe + 1) + " / " + list.size(), px + 68 * s, y0 + 151 * s, 0.15f, 0.15f, 0.15f);
        hud.text(">", px + 120 * s, y0 + 151 * s, 1, 1, 1);
    }
}
