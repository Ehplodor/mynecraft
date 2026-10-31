package net.mynecraft.world.actor;

import net.mynecraft.world.life.Ecology;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * The ledger of material flows, patch by patch (the ecology's 64 x 64 patches): everything an actor
 * takes from the land (wood, stone, plants, game, fish...) and everything returned to it (ash,
 * compost, slag, waste water). Taking and returning also act on the patch's living state: picking
 * thins its plants, compost and ash feed its soil, waste water clouds its water life. Nothing comes
 * from nowhere: every gesture that yields matter goes through here, for the player as for the
 * inhabitants. The naturalist's notebook and the harmony compass read the ledger.
 */
public final class Flows {
    private static final int M = Matter.values().length;

    /** One patch's account. */
    public static final class Account {
        public final double[] taken = new double[M], returned = new double[M];
        /** Animals killed in their breeding season (counted against the harmony with nature). */
        public double offSeasonKills;
    }

    private final Map<Long, Account> accounts = new HashMap<>();
    /** Applies taking and returning to the living patches (null in tests of the ledger alone). */
    public Ecology ecology;

    /** Where the rivers run (null: wastewater stays where it is poured). */
    public RiverLevel river;

    /** River surface at a column, or NaN where there is no river. */
    public interface RiverLevel {
        double at(int x, int z);
    }

    public Flows(Ecology ecology) {
        this.ecology = ecology;
    }

    public Account at(double x, double z) {
        return accounts.computeIfAbsent(key(x, z), k -> new Account());
    }

    public Account existing(int px, int pz) {
        return accounts.get(Ecology.key(px, pz));
    }

    private static long key(double x, double z) {
        return Ecology.key(Math.floorDiv((int) Math.floor(x), Ecology.PATCH), Math.floorDiv((int) Math.floor(z), Ecology.PATCH));
    }

    /** Matter taken from the land at (x, z). */
    public void take(Matter m, double amount, double x, double z) {
        if (amount <= 0) return;
        at(x, z).taken[m.ordinal()] += amount;
        Ecology.Patch p = ecology == null ? null : ecology.existing(x, z);
        if (p == null) return;
        switch (m) {
            case PLANTS -> p.plants = Math.max(0, p.plants - 0.002 * amount);
            case FRUIT -> p.flowers = Math.max(0, p.flowers - 0.001 * amount);
            case WOOD -> p.plants = Math.max(0, p.plants - 0.004 * amount); // a felled tree: less canopy
            case SOIL -> p.soil = Math.max(0, p.soil - 0.001 * amount);
            default -> { }
        }
    }

    /** Matter returned to the land at (x, z). */
    public void give(Matter m, double amount, double x, double z) {
        if (amount <= 0) return;
        at(x, z).returned[m.ordinal()] += amount;
        Ecology.Patch p = ecology == null ? null : ecology.existing(x, z);
        if (p == null) return;
        switch (m) {
            case COMPOST -> p.soil = Math.min(1, p.soil + 0.02 * amount);
            case ASH -> p.soil = Math.min(1, p.soil + 0.005 * amount);
            case PLANTS -> p.detritus += 0.002 * amount;
            case SLAG -> p.soil = Math.max(0, p.soil - 0.002 * amount);
            case WASTEWATER -> { // a tannery upstream clouds the trout's water, and downstream too, less and less
                p.aquatic = Math.max(0, p.aquatic - 0.03 * amount);
                double cx = x, cz = z, k = 0.5;
                for (int hop = 0; hop < 3 && river != null; hop++, k /= 2) {
                    double[] next = downstream(cx, cz);
                    if (next == null) break;
                    cx = next[0];
                    cz = next[1];
                    Ecology.Patch d = ecology.existing(cx, cz);
                    if (d != null && d != p) d.aquatic = Math.max(0, d.aquatic - 0.03 * k * amount);
                }
            }
            default -> { }
        }
    }

    /** The river's lowest point about a patch away from (x, z), lower than the river there; null if none. */
    private double[] downstream(double x, double z) {
        double here = river.at((int) Math.floor(x), (int) Math.floor(z)), best = Double.isNaN(here) ? Double.MAX_VALUE : here;
        double[] at = null;
        for (int a = 0; a < 16; a++)
            for (int r = 32; r <= 64; r += 16) {
                double px = x + r * Math.cos(a * Math.PI / 8), pz = z + r * Math.sin(a * Math.PI / 8);
                double l = river.at((int) Math.floor(px), (int) Math.floor(pz));
                if (!Double.isNaN(l) && l < best) { best = l; at = new double[] {px, pz}; }
            }
        return at;
    }

    public void offSeasonKill(double x, double z) {
        at(x, z).offSeasonKills++;
    }

    public java.util.Collection<Account> accounts() {
        return accounts.values();
    }

    public void save(Path file) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new java.io.BufferedOutputStream(Files.newOutputStream(tmp)))) {
            out.writeInt(1);
            out.writeInt(M);
            out.writeInt(accounts.size());
            for (var e : accounts.entrySet()) {
                out.writeLong(e.getKey());
                for (double v : e.getValue().taken) out.writeDouble(v);
                for (double v : e.getValue().returned) out.writeDouble(v);
                out.writeDouble(e.getValue().offSeasonKills);
            }
        }
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    public void load(Path file) throws IOException {
        if (!Files.exists(file)) return;
        try (DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(Files.newInputStream(file)))) {
            if (in.readInt() != 1) return;
            int m = in.readInt(), n = in.readInt();
            for (int i = 0; i < n; i++) {
                Account a = new Account();
                long k = in.readLong();
                for (int j = 0; j < m; j++) { double v = in.readDouble(); if (j < M) a.taken[j] = v; }
                for (int j = 0; j < m; j++) { double v = in.readDouble(); if (j < M) a.returned[j] = v; }
                a.offSeasonKills = in.readDouble();
                accounts.put(k, a);
            }
        }
    }
}
