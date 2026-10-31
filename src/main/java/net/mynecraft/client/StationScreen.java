package net.mynecraft.client;

import net.mynecraft.world.Items;
import net.mynecraft.world.crafting.RecipeBook;
import net.mynecraft.world.crafting.Workshop;

import java.util.List;

/**
 * A workstation's recipes (M27): every recipe of the station as a line, outputs from inputs, with the
 * time this actor would take, the energy, the skill and level, and what is missing. Click a line to
 * make it; the bar shows the work in progress.
 */
final class StationScreen {
    private static final String[] NAMES = {"crafting_table", "Établi", "campfire", "Feu de camp", "furnace", "Four",
        "composter", "Composteur", "millstone", "Meule", "anvil", "Enclume", "tanning_vat", "Cuve de tannage", "loom",
        "Métier à tisser", "potters_wheel", "Tour de potier", "sawmill", "Scierie", "dye_vat", "Cuve de teinture"};

    static String title(String station) {
        for (int i = 0; i < NAMES.length; i += 2) if (NAMES[i].equals(station)) return NAMES[i + 1];
        return station;
    }

    private float top, left, width, row;
    private List<RecipeBook.Process> shown = List.of();

    void draw(Hud hud, Livelihood life, RecipeBook recipes, ItemNames names, double mx, double my) {
        shown = recipes.at(life.station);
        float s = hud.scale, line = hud.lineHeight() + 2 * s;
        row = line;
        width = Math.min(hud.width() - 20 * s, 420 * s);
        left = (hud.width() - width) / 2;
        top = 30 * s;
        float h = (shown.size() + 4) * line;
        hud.rect(left - 4 * s, top - 4 * s, width + 8 * s, h + 8 * s, 0.07f, 0.06f, 0.05f, 0.88f);
        String energy = life.power > 0 ? String.format(" | force mécanique %.1f", life.power) : "";
        hud.text(title(life.station) + String.format(" | combustible %.1f", Workshop.fuel(life.actor.inventory)) + energy,
            left, top, 1, 0.95f, 0.7f);
        float y = top + line * 1.3f;
        for (RecipeBook.Process p : shown) {
            boolean hover = mx >= left && mx < left + width && my >= y && my < y + line;
            if (hover) hud.rect(left - 2 * s, y - s, width + 4 * s, line, 1, 1, 1, 0.08f);
            StringBuilder t = new StringBuilder();
            for (RecipeBook.Stack o : p.outputs()) t.append(o.count()).append(' ').append(names.name(o.item())).append(' ');
            t.append("<- ");
            for (RecipeBook.Input in : p.inputs()) {
                short any = in.ingredient().accepts().iterator().next();
                t.append(in.count()).append(' ').append(names.name(any)).append(in.ingredient().accepts().size() > 1 ? "*" : "").append(", ");
            }
            t.setLength(t.length() - 2);
            RecipeBook.Rules r = p.rules();
            t.append(String.format("  (%.0f s", Workshop.seconds(life.actor, p, life.power)));
            if (r.energy().equals("heat")) t.append(String.format(", chaleur %.1f", r.energyAmount()));
            if (r.energy().equals("work")) t.append(life.power >= r.energyAmount() ? ", roue" : ", à bras");
            if (r.tool() != Items.TOOL_NONE) t.append(", ").append(Items.TOOL_NAMES[r.tool()]);
            t.append(", ").append(r.skill().displayName).append(r.level() > 0 ? " " + r.level() : "").append(")");
            String missing = Workshop.missing(life.actor, p, life.power);
            float c = missing == null ? 0.95f : 0.55f;
            hud.text(t.toString(), left, y, c, c, missing == null ? 0.85f : 0.55f);
            y += line;
        }
        if (life.making != null) {
            float f = (float) (1 - life.makingLeft / Math.max(0.01, life.makingTotal));
            hud.rect(left, y + 4 * s, width, 4 * s, 0, 0, 0, 0.6f);
            hud.rect(left, y + 4 * s, width * f, 4 * s, 0.9f, 0.7f, 0.3f, 0.95f);
        }
        hud.text("clic : fabriquer | Échap ou E : fermer", left, y + line * 1.2f, 0.6f, 0.6f, 0.6f);
    }

    /** The recipe line under the mouse, or null. */
    RecipeBook.Process at(double mx, double my) {
        if (mx < left || mx >= left + width) return null;
        int i = (int) Math.floor((my - (top + row * 1.3f)) / row);
        return i >= 0 && i < shown.size() ? shown.get(i) : null;
    }
}
