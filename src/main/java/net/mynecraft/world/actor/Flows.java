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

    /** Pollution added to a patch's water by one unit of waste water (a tanned hide, a dyed cloth). */
    public static final double POLLUTION_PER_POUR = 0.08;

    /** Who last fouled a patch's water, and from where (the place the waste water was poured). */
    public record Source(String who, double x, double z, double day) {}

    private final Map<Long, Source> sources = new HashMap<>();
    /** The calendar, for dating the sources (set by the world's clock; 0 in tests). */
    public double day;

    /** Who last fouled the water of the patch at (x, z) and from where; null if nobody ever did. */
    public Source pollutedBy(double x, double z) {
        return sources.get(key(x, z));
    }

    private void source(Ecology.Patch p, String who, double x, double z) {
        if (who != null) sources.put(Ecology.key(p.px, p.pz), new Source(who, x, z, day));
    }

    /** Matter returned to the land at (x, z). */
    public void give(Matter m, double amount, double x, double z) {
        give(m, amount, x, z, null);
    }

    /** Matter returned to the land at (x, z) by someone (named, for the waste water's trail). */
    public void give(Matter m, double amount, double x, double z, String who) {
        if (amount <= 0) return;
        at(x, z).returned[m.ordinal()] += amount;
        Ecology.Patch p = ecology == null ? null : ecology.existing(x, z);
        if (p == null) return;
        switch (m) {
            case COMPOST -> p.soil = Math.min(1, p.soil + 0.02 * amount);
            case ASH -> p.soil = Math.min(1, p.soil + 0.005 * amount);
            case PLANTS -> p.detritus += 0.002 * amount;
            case SLAG -> p.soil = Math.max(0, p.soil - 0.002 * amount);
            case WASTEWATER -> { // a tannery upstream fouls the trout's water, and downstream too, less and less
                double left = Ecology.pollute(p, POLLUTION_PER_POUR * amount);
                source(p, who, x, z);
                double cx = x, cz = z;
                for (int hop = 0; hop < 3 && river != null && left > 1e-4; hop++) {
                    double[] next = downstream(cx, cz);
                    if (next == null) break;
                    cx = next[0];
                    cz = next[1];
                    Ecology.Patch d = ecology.existing(cx, cz);
                    if (d == null || d == p) continue;
                    left = Ecology.pollute(d, left * 0.5);
                    source(d, who, x, z);
                }
            }
            default -> { }
        }
    }

    /** The river's lowest point about a patch away from (x, z), lower than the river there; null if none (or no river). */
    public double[] downstream(double x, double z) {
        if (river == null) return null;
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
            out.writeInt(2);
            out.writeInt(M);
            out.writeInt(accounts.size());
            for (var e : accounts.entrySet()) {
                out.writeLong(e.getKey());
                for (double v : e.getValue().taken) out.writeDouble(v);
                for (double v : e.getValue().returned) out.writeDouble(v);
                out.writeDouble(e.getValue().offSeasonKills);
            }
            out.writeInt(sources.size());
            for (var e : sources.entrySet()) {
                out.writeLong(e.getKey());
                out.writeUTF(e.getValue().who());
                out.writeDouble(e.getValue().x());
                out.writeDouble(e.getValue().z());
                out.writeDouble(e.getValue().day());
            }
        }
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    public void load(Path file) throws IOException {
        if (!Files.exists(file)) return;
        try (DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(Files.newInputStream(file)))) {
            int version = in.readInt();
            if (version < 1 || version > 2) return;
            int m = in.readInt(), n = in.readInt();
            for (int i = 0; i < n; i++) {
                Account a = new Account();
                long k = in.readLong();
                for (int j = 0; j < m; j++) { double v = in.readDouble(); if (j < M) a.taken[j] = v; }
                for (int j = 0; j < m; j++) { double v = in.readDouble(); if (j < M) a.returned[j] = v; }
                a.offSeasonKills = in.readDouble();
                accounts.put(k, a);
            }
            if (version >= 2) {
                int ns = in.readInt();
                for (int i = 0; i < ns; i++)
                    sources.put(in.readLong(), new Source(in.readUTF(), in.readDouble(), in.readDouble(), in.readDouble()));
            }
        }
    }
}
