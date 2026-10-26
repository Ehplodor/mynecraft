package net.mynecraft.client;

import net.mynecraft.world.Landscape;
import net.mynecraft.world.life.Ecology;
import net.mynecraft.world.life.Ecosystem;
import net.mynecraft.world.life.Life;
import net.mynecraft.world.life.Species;

/**
 * The naturalist's notebook (N): the season and the calendar, then the patch of land the player
 * stands in as the ecosystem model sees it: plants, flowers, soil, detritus and water life as
 * gauges, every species living there with its numbers, trend, dormant and sick ones, and the
 * interactions at work, strongest first.
 */
final class Notebook {
    boolean open;

    private static final String[] INTERACTION = {"prédation", "herbivorie", "compétition", "mutualisme", "commensalisme",
        "parasitisme", "décomposition"};

    void draw(Hud hud, Life life, double x, double z) {
        Ecology.Patch p = life.ecology.at(x, z);
        Ecosystem.Season season = life.eco.season();
        float s = hud.scale, line = hud.lineHeight() + s;
        float w = Math.min(hud.width() * 0.46f, 250 * s), left = 6 * s, top = 6 * s;
        java.util.List<Ecology.Link> links = life.ecology.links(p);
        int listed = 0;
        for (Species sp : Species.values())
            if (p.pop[sp.ordinal()] >= 0.05 || p.dormant[sp.ordinal()] >= 0.05 || life.ecology.suitable(p, sp)) listed++;
        int rows = 9 + listed + Math.min(6, links.size());
        hud.rect(left - 3 * s, top - 3 * s, w + 6 * s, rows * line + 8 * s, 0.05f, 0.06f, 0.04f, 0.78f);
        float y = top;
        int day = (int) Math.floor(life.eco.day());
        int dayInSeason = (int) Math.floor((season.phase() * Ecosystem.YEAR_DAYS) % (Ecosystem.YEAR_DAYS / 4.0)) + 1;
        hud.text(String.format("Carnet du naturaliste - %s, jour %d/%d (année %d)", season.name(), dayInSeason,
            Ecosystem.YEAR_DAYS / 4, day / Ecosystem.YEAR_DAYS + 1), left, y, 1, 0.95f, 0.7f);
        y += line;
        hud.text(String.format("Parcelle %d, %d : %s, %.0f degres", p.px, p.pz, Landscape.BIOME_NAMES[p.biome & 0xFF],
            Ecology.temperature(p, season)), left, y, 0.85f, 0.85f, 0.85f);
        y += line * 1.3f;
        y = gauge(hud, "Végétation", p.plants, 0.35f, 0.75f, 0.3f, left, y, w);
        y = gauge(hud, "Fleurs", p.flowers, 0.95f, 0.45f, 0.5f, left, y, w);
        y = gauge(hud, "Sol (nutriments)", p.soil, 0.6f, 0.45f, 0.3f, left, y, w);
        y = gauge(hud, "Détritus", Math.min(1, p.detritus * 5), 0.5f, 0.42f, 0.35f, left, y, w);
        y = gauge(hud, "Vie aquatique", p.aquatic, 0.35f, 0.6f, 0.9f, left, y, w);
        y += line * 0.3f;
        for (Species sp : Species.values()) {
            int i = sp.ordinal();
            double n = p.pop[i], d = p.dormant[i];
            if (n < 0.05 && d < 0.05 && !life.ecology.suitable(p, sp)) continue;
            double trend = n - p.yesterday[i];
            String arrow = trend > 0.03 ? " (+)" : trend < -0.03 ? " (-)" : ""; // the font has no arrows
            String extra = (d >= 0.05 ? String.format(", %.1f endormis", d) : "")
                + (p.sick[i] > 0.02 ? String.format(", %.0f %% malades", p.sick[i] * 100) : "");
            float c = n < 0.05 ? 0.5f : 0.95f;
            hud.text(String.format("%-20s %5.1f%s%s", sp.displayName, n, arrow, extra), left, y, c, c, c);
            y += line;
        }
        y += line * 0.3f;
        int shown = 0;
        for (Ecology.Link l : links) {
            if (shown++ == 6) break;
            hud.text(INTERACTION[l.kind().ordinal()] + " : " + l.text(), left, y, 0.75f, 0.9f, 1);
            y += line;
        }
        hud.text("N : fermer", left, y + line * 0.2f, 0.6f, 0.6f, 0.6f);
    }

    private static float gauge(Hud hud, String label, double v, float r, float g, float b, float x, float y, float w) {
        float s = hud.scale, bw = w * 0.45f, bx = x + w - bw;
        hud.text(label, x, y, 0.9f, 0.9f, 0.9f);
        hud.rect(bx, y + 2 * s, bw, hud.lineHeight() - 4 * s, 0.15f, 0.15f, 0.15f, 0.9f);
        hud.rect(bx, y + 2 * s, (float) (bw * Math.max(0, Math.min(1, v))), hud.lineHeight() - 4 * s, r, g, b, 0.95f);
        return y + hud.lineHeight() + s;
    }
}
