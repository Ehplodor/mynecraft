package net.mynecraft.world.life;

import net.mynecraft.world.Landscape;

/** Prints a patch's trajectory (for tuning; run with: gradle test --tests '*EcologyProbe*' -i). */
class EcologyProbe {
    @org.junit.jupiter.api.Test
    void print() {
        for (byte biome : new byte[] {Landscape.BIRCH_FOREST, Landscape.FOREST}) {
            Landscape.Column col = new Landscape.Column(70, biome, biome == Landscape.FOREST ? 75 : 0, 0, 0.5f);
            Ecology e = new Ecology(42, new Ecosystem(), (x, z) -> col);
            Ecology.Patch p = new Ecology.Patch(0, 0, biome, 6, biome == Landscape.FOREST ? 1 : 0, 1, 1, 70);
            p.plants = 0.5; p.soil = 0.5; p.aquatic = 0.3;
            for (int s = 0; s < Species.values().length; s++) if (e.suitable(p, Species.values()[s])) p.pop[s] = 1;
            StringBuilder sb = new StringBuilder("biome " + biome + "\n");
            for (int d = 0; d < 6 * 32; d++) {
                for (int k = 0; k < 10; k++) e.step(p, Ecosystem.seasonAt(d + k * 0.1), 0.1);
                if (d % 4 == 0) {
                    sb.append(String.format("d%3d %-9s pl %.2f fl %.2f so %.2f de %.2f aq %.2f |", d, Ecosystem.seasonAt(d).name(), p.plants, p.flowers, p.soil, p.detritus, p.aquatic));
                    for (Species s : Species.values()) sb.append(String.format(" %s %.2f/%.2f", s.name().substring(0, 3), p.pop[s.ordinal()], p.dormant[s.ordinal()]));
                    sb.append('\n');
                }
            }
            System.out.println(sb);
        }
    }
}
