package net.mynecraft.world.actor;

import net.mynecraft.world.life.Ecology;
import net.mynecraft.world.life.Species;

/**
 * The harmony compass (M28): two measures, 0..1, shown in the notebook.
 * <ul>
 *   <li><b>with nature</b>, over the territory (the 3 x 3 patches around the hearth): plants, soil,
 *   water life and biodiversity (species present where they could live), less the pressure of what
 *   was taken and not given back, and of animals killed in their breeding season;</li>
 *   <li><b>with oneself</b>: needs met, shelter, comfort, beauty, rest, varied meals and activities,
 *   and the company of others.</li>
 * </ul>
 */
public final class Harmony {
    private Harmony() {}

    public record Nature(double plants, double soil, double water, double biodiversity, double pressure, double score) {}

    public record Self(double needs, double place, double variety, double company, double score) {}

    public static Nature nature(Ecology ecology, Flows flows, double cx, double cz) {
        int px = Math.floorDiv((int) Math.floor(cx), Ecology.PATCH), pz = Math.floorDiv((int) Math.floor(cz), Ecology.PATCH);
        double plants = 0, soil = 0, water = 0, bio = 0, taken = 0, given = 0, kills = 0;
        int n = 0, wet = 0;
        for (int dz = -1; dz <= 1; dz++)
            for (int dx = -1; dx <= 1; dx++) {
                Ecology.Patch p = ecology.existing((px + dx) * (double) Ecology.PATCH + 1, (pz + dz) * (double) Ecology.PATCH + 1);
                if (p == null) continue;
                n++;
                plants += Math.min(1, p.plants);
                soil += Math.min(1, p.soil);
                if (p.water > 0.05) { water += Math.min(1, p.aquatic); wet++; }
                int could = 0, present = 0;
                for (Species s : Species.values())
                    if (ecology.suitable(p, s)) { could++; if (p.pop[s.ordinal()] + p.dormant[s.ordinal()] >= 0.5) present++; }
                bio += could == 0 ? 1 : present / (double) could;
                Flows.Account a = flows.existing(px + dx, pz + dz);
                if (a != null) {
                    for (Matter m : Matter.values()) { taken += a.taken[m.ordinal()]; given += a.returned[m.ordinal()]; }
                    kills += a.offSeasonKills;
                }
            }
        if (n == 0) return new Nature(0, 0, 0, 0, 0, 0.5);
        plants /= n;
        soil /= n;
        water = wet == 0 ? 1 : water / wet;
        bio /= n;
        // Taking is not wrong in itself: what weighs is taking much without giving back.
        double pressure = Math.min(1, Math.max(0, taken - 2 * given) / (400.0 * n) + 0.05 * kills);
        double score = clamp(0.3 * plants + 0.2 * soil + 0.15 * water + 0.35 * bio - 0.4 * pressure);
        return new Nature(plants, soil, water, bio, pressure, score);
    }

    /** {@code company}: 0..1, time spent recently near or talking with others. */
    public static Self self(Actor a, Wellbeing place, double company) {
        double needs = a.needs.satisfaction();
        double placeScore = (place.shelter() + place.comfort() + place.beauty()) / 3;
        double variety = (a.needs.variety() + Math.min(1, a.skills.recentSkills() / 4.0)) / 2;
        double score = clamp(0.35 * needs + 0.25 * placeScore + 0.25 * variety + 0.15 * company);
        return new Self(needs, placeScore, variety, company, score);
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }
}
