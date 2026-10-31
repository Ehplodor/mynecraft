package net.mynecraft.client;

import net.mynecraft.world.Landscape;
import net.mynecraft.world.life.Ecology;
import net.mynecraft.world.life.Ecosystem;
import net.mynecraft.world.life.Life;
import net.mynecraft.world.life.Species;
import net.mynecraft.world.life.Vegetation;

/**
 * The naturalist's notebook (N): the season and the calendar, then the patch of land the player
 * stands in as the ecosystem model sees it: plants, flowers, soil, detritus and water life as
 * gauges, every species living there with its numbers, trend, dormant and sick ones, and the
 * interactions at work, strongest first.
 */
final class Notebook {
    boolean open;
    /** 0 the patch, 1 the harmony compass and the flows, 2 skills and trades (N turns the pages, then closes). */
    int page;

    /** N: next page, closing after the last. */
    void turn() {
        if (!open) { open = true; page = 0; }
        else if (++page > 2) { open = false; page = 0; }
    }

    void draw(Hud hud, Life life, double x, double z, Livelihood me) {
        if (page == 1) compass(hud, life, x, z, me);
        else if (page == 2) skills(hud, me);
        else draw(hud, life, x, z);
    }

    private void compass(Hud hud, Life life, double x, double z, Livelihood me) {
        float s = hud.scale, line = hud.lineHeight() + s;
        float w = Math.min(hud.width() * 0.6f, 330 * s), left = 6 * s, top = 6 * s;
        hud.rect(left - 3 * s, top - 3 * s, w + 6 * s, 34 * line + 8 * s, 0.05f, 0.06f, 0.04f, 0.78f);
        float y = top;
        var n = me.nature;
        var self = me.self;
        hud.text("Boussole d'harmonie" + (me.actor.hasHome() ? String.format(" - foyer %.0f, %.0f", me.actor.homeX, me.actor.homeZ)
            : " - pas de foyer (H : le fixer ici)"), left, y, 1, 0.95f, 0.7f);
        y += line * 1.3f;
        y = gauge(hud, "Avec la nature", n.score(), 0.35f, 0.75f, 0.35f, left, y, w);
        y = gauge(hud, "  végétation", n.plants(), 0.3f, 0.6f, 0.3f, left, y, w);
        y = gauge(hud, "  sol", n.soil(), 0.6f, 0.45f, 0.3f, left, y, w);
        y = gauge(hud, "  eaux", n.water(), 0.35f, 0.6f, 0.9f, left, y, w);
        y = gauge(hud, "  biodiversité", n.biodiversity(), 0.9f, 0.6f, 0.3f, left, y, w);
        y = gauge(hud, "  pression (prélèvements)", n.pressure(), 0.85f, 0.3f, 0.25f, left, y, w);
        y += line * 0.3f;
        y = gauge(hud, "Avec soi", self.score(), 0.95f, 0.75f, 0.35f, left, y, w);
        y = gauge(hud, "  besoins du corps", self.needs(), 0.8f, 0.6f, 0.4f, left, y, w);
        y = gauge(hud, "  le lieu (abri, confort, beauté)", self.place(), 0.7f, 0.6f, 0.8f, left, y, w);
        y = gauge(hud, "  variété (repas, activités)", self.variety(), 0.5f, 0.8f, 0.7f, left, y, w);
        y = gauge(hud, "  compagnie", self.company(), 0.9f, 0.5f, 0.6f, left, y, w);
        y += line * 0.3f;
        var a = me.actor.needs;
        hud.text(String.format("Faim %.0f/20  Soif %.0f/20  Repos %.0f %%  Chaleur %s", a.food, a.water, 100 * a.rest,
            a.warmth < 0.3 ? "froid" : a.warmth > 0.7 ? "chaud" : "bien"), left, y, 0.85f, 0.85f, 0.85f);
        y += line;
        var p = me.place;
        hud.text(String.format("Ici : toit %.0f %%, murs %.0f %%, feu %.0f %%, beauté %.0f %%%s", 100 * p.roof(), 100 * p.walls(),
            100 * p.fire(), 100 * p.beauty(), p.bed() ? ", un lit" : ""), left, y, 0.8f, 0.8f, 0.9f);
        y += line * 1.3f;
        hud.text("Flux de matières de cette parcelle (pris / rendu)", left, y, 1, 0.95f, 0.7f);
        y += line;
        var acc = me.flows.at(x, z);
        for (var m : net.mynecraft.world.actor.Matter.values()) {
            double t = acc.taken[m.ordinal()], g = acc.returned[m.ordinal()];
            if (t < 0.01 && g < 0.01) continue;
            hud.text(String.format("%-18s %7.1f / %.1f", m.displayName, t, g), left, y, 0.8f, 0.85f, 0.8f);
            y += line;
        }
        if (acc.offSeasonKills > 0) {
            hud.text(String.format("Animaux tués en saison des petits : %.0f", acc.offSeasonKills), left, y, 0.95f, 0.5f, 0.45f);
            y += line;
        }
        hud.text("N : page suivante", left, y + line * 0.2f, 0.6f, 0.6f, 0.6f);
    }

    private void skills(Hud hud, Livelihood me) {
        float s = hud.scale, line = hud.lineHeight() + s;
        float w = Math.min(hud.width() * 0.6f, 330 * s), left = 6 * s, top = 6 * s;
        var sk = me.actor.skills;
        hud.rect(left - 3 * s, top - 3 * s, w + 6 * s, 34 * line + 8 * s, 0.05f, 0.06f, 0.04f, 0.78f);
        float y = top;
        String role = sk.roleTitle();
        hud.text("Savoir-faire" + (role.isEmpty() ? " - novice en tout" : " - " + role), left, y, 1, 0.95f, 0.7f);
        y += line * 1.3f;
        for (var a : net.mynecraft.world.actor.Ability.values())
            y = gauge(hud, a.displayName, sk.ability[a.ordinal()], 0.6f, 0.7f, 0.95f, left, y, w);
        y += line * 0.3f;
        for (var k : net.mynecraft.world.actor.Skill.values()) {
            double xp = sk.xp[k.ordinal()];
            if (xp < 0.5) continue;
            int lvl = sk.level(k);
            double into = (xp - 5.0 * lvl * lvl) / Math.max(1, 5.0 * ((lvl + 1) * (lvl + 1) - lvl * lvl));
            boolean taught = sk.today < sk.taughtUntil[k.ordinal()];
            y = gauge(hud, String.format("%s %d%s", k.displayName, lvl, taught ? " (leçon)" : ""), Math.min(1, into), 0.85f, 0.7f, 0.35f, left, y, w);
        }
        y += line * 0.3f;
        hud.text("Plans connus : " + String.join(", ", me.actor.plans), left, y, 0.8f, 0.85f, 0.8f);
        y += line;
        hud.text("N : fermer", left, y + line * 0.2f, 0.6f, 0.6f, 0.6f);
    }

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
        int rows = 10 + listed + Math.min(6, links.size());
        hud.rect(left - 3 * s, top - 3 * s, w + 6 * s, rows * line + 8 * s, 0.05f, 0.06f, 0.04f, 0.78f);
        float y = top;
        int day = (int) Math.floor(life.eco.day());
        int dayInSeason = (int) Math.floor((season.phase() * Ecosystem.YEAR_DAYS) % (Ecosystem.YEAR_DAYS / 4.0)) + 1;
        hud.text(String.format("Carnet du naturaliste - %s, jour %d/%d (année %d)", season.name(), dayInSeason,
            Ecosystem.YEAR_DAYS / 4, day / Ecosystem.YEAR_DAYS + 1), left, y, 1, 0.95f, 0.7f);
        y += line;
        hud.text(String.format("Parcelle %d, %d : %s, %.0f degres", p.px, p.pz, Landscape.BIOME_NAMES[p.biome & 0xFF],
            Ecology.temperature(p, season)), left, y, 0.85f, 0.85f, 0.85f);
        y += line;
        // The plants' own calendar where the player stands (late on the heights, parched in a dry summer).
        Landscape.Column here = life.columns.apply((int) Math.floor(x), (int) Math.floor(z));
        double climate = Vegetation.climate(here.temperature(), here.height());
        hud.text(String.format("Ici : pousse %.0f %%, floraison %.0f %%, sécheresse %.0f %%",
            100 * Vegetation.growth(climate, p.rain, season.phase()), 100 * Vegetation.bloom(climate, season.phase()),
            100 * Vegetation.drought(climate, p.rain, season.phase())), left, y, 0.75f, 0.85f, 0.7f);
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
        hud.text("N : page suivante", left, y + line * 0.2f, 0.6f, 0.6f, 0.6f);
    }

    private static float gauge(Hud hud, String label, double v, float r, float g, float b, float x, float y, float w) {
        float s = hud.scale, bw = w * 0.45f, bx = x + w - bw;
        hud.text(label, x, y, 0.9f, 0.9f, 0.9f);
        hud.rect(bx, y + 2 * s, bw, hud.lineHeight() - 4 * s, 0.15f, 0.15f, 0.15f, 0.9f);
        hud.rect(bx, y + 2 * s, (float) (bw * Math.max(0, Math.min(1, v))), hud.lineHeight() - 4 * s, r, g, b, 0.95f);
        return y + hud.lineHeight() + s;
    }
}
