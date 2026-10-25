package net.mynecraft.world.storage;

import net.mynecraft.world.Chunk;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A world folder in Java Edition layout: level.dat + region/r.X.Z.mca.
 *
 * <p>Worlds not created by Mynecraft are opened read-only unless explicitly allowed: we cannot yet
 * represent everything they contain (entities, block states, heights outside 0..127), so writing
 * would destroy data.
 */
public final class WorldStorage implements AutoCloseable {
    public static final String MARKER = "MynecraftVersion";

    public final Path dir;
    public final boolean readOnly;
    public final boolean foreign;
    public final long seed;
    /** Saved player position, or null for a new world. */
    public final double[] playerPos;

    private final Map<Long, RegionFile> regions = new HashMap<>();
    /** Chunks queued for writing; loads check here first so an unload/reload never sees stale data. */
    private final ConcurrentHashMap<Long, Chunk> pendingSaves = new ConcurrentHashMap<>();

    private WorldStorage(Path dir, boolean readOnly, boolean foreign, long seed, double[] playerPos) {
        this.dir = dir;
        this.readOnly = readOnly;
        this.foreign = foreign;
        this.seed = seed;
        this.playerPos = playerPos;
    }

    /** Opens an existing world or creates a new one with the given seed. */
    public static WorldStorage open(Path dir, long newSeed, boolean allowForeignWrites) throws IOException {
        Path levelDat = dir.resolve("level.dat");
        if (!Files.exists(levelDat)) {
            Files.createDirectories(dir.resolve("region"));
            WorldStorage s = new WorldStorage(dir, false, false, newSeed, null);
            s.saveLevel(null);
            return s;
        }
        Map<String, Object> data;
        try (InputStream in = Files.newInputStream(levelDat)) {
            data = Nbt.getCompound(Nbt.readGzip(in), "Data");
        }
        if (data == null) throw new IOException("level.dat has no Data compound");
        boolean foreign = !data.containsKey(MARKER);
        Map<String, Object> wgs = Nbt.getCompound(data, "WorldGenSettings");
        long seed = wgs != null ? Nbt.getLong(wgs, "seed", newSeed) : Nbt.getLong(data, "RandomSeed", newSeed);
        double[] pos = null;
        Map<String, Object> player = Nbt.getCompound(data, "Player");
        if (player != null) {
            var p = Nbt.getList(player, "Pos");
            if (p.size() == 3) pos = new double[] {((Number) p.get(0)).doubleValue(),
                ((Number) p.get(1)).doubleValue(), ((Number) p.get(2)).doubleValue()};
        } else if (data.containsKey("SpawnX")) {
            pos = new double[] {Nbt.getInt(data, "SpawnX", 0) + 0.5, Nbt.getInt(data, "SpawnY", 64),
                Nbt.getInt(data, "SpawnZ", 0) + 0.5};
        }
        return new WorldStorage(dir, foreign && !allowForeignWrites, foreign, seed, pos);
    }

    private static long regionKey(int rx, int rz) {
        return ((long) rx << 32) | (rz & 0xFFFFFFFFL);
    }

    private synchronized RegionFile region(int cx, int cz, boolean create) throws IOException {
        int rx = cx >> 5, rz = cz >> 5;
        long k = regionKey(rx, rz);
        RegionFile r = regions.get(k);
        if (r == null) {
            Path p = dir.resolve("region").resolve("r." + rx + "." + rz + ".mca");
            if (!Files.exists(p) && !create) return null;
            r = new RegionFile(p, readOnly);
            regions.put(k, r);
        }
        return r;
    }

    /** Loads a chunk, or returns null if it was never saved (caller generates it). */
    public Chunk load(int cx, int cz) {
        Chunk pending = pendingSaves.get(net.mynecraft.world.World.key(cx, cz));
        if (pending != null) return pending;
        try {
            RegionFile r = region(cx, cz, false);
            if (r == null) return null;
            try (InputStream in = r.read(cx & 31, cz & 31)) {
                return in == null ? null : ChunkCodec.decode(Nbt.read(in), cx, cz);
            }
        } catch (IOException e) {
            System.err.println("[storage] cannot read chunk " + cx + "," + cz + ": " + e.getMessage());
            return null;
        }
    }

    /** Marks a chunk for saving; call {@link #flush(Chunk)} (typically on an IO thread) to write it. */
    public boolean enqueueSave(Chunk c) {
        if (readOnly || !c.isDirty()) return false;
        pendingSaves.put(net.mynecraft.world.World.key(c.cx, c.cz), c);
        return true;
    }

    public void flush(Chunk c) {
        // Clean first: an edit racing with encoding marks it dirty again, so it is never lost.
        c.markClean();
        try {
            byte[] nbt = Nbt.toBytes(ChunkCodec.encode(c));
            region(c.cx, c.cz, true).write(c.cx & 31, c.cz & 31, nbt);
        } catch (IOException e) {
            c.markDirty();
            System.err.println("[storage] cannot save chunk " + c.cx + "," + c.cz + ": " + e.getMessage());
        } finally {
            pendingSaves.remove(net.mynecraft.world.World.key(c.cx, c.cz), c);
        }
    }

    /** Writes level.dat atomically (temp file + move), like vanilla does. */
    public void saveLevel(double[] player) throws IOException {
        if (readOnly) return;
        Map<String, Object> data = Nbt.compound();
        Path levelDat = dir.resolve("level.dat");
        if (Files.exists(levelDat)) {
            try (InputStream in = Files.newInputStream(levelDat)) {
                Map<String, Object> old = Nbt.getCompound(Nbt.readGzip(in), "Data");
                if (old != null) data = old;
            }
        }
        data.putIfAbsent("LevelName", dir.getFileName().toString());
        data.put("RandomSeed", seed);
        data.put("version", 19133);
        data.put("DataVersion", ChunkCodec.DATA_VERSION);
        if (!foreign) data.put(MARKER, 1);
        if (player != null) {
            Map<String, Object> p = Nbt.getCompound(data, "Player");
            if (p == null) { p = Nbt.compound(); data.put("Player", p); }
            Nbt.NbtList pos = new Nbt.NbtList(Nbt.DOUBLE);
            for (double v : player) pos.values().add(v);
            p.put("Pos", pos);
        }
        Map<String, Object> root = Nbt.compound();
        root.put("Data", data);
        Path tmp = dir.resolve("level.dat_new");
        try (OutputStream out = Files.newOutputStream(tmp)) {
            Nbt.writeGzip(out, root);
        }
        Files.move(tmp, levelDat, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    @Override
    public synchronized void close() throws IOException {
        for (RegionFile r : regions.values()) r.close();
        regions.clear();
    }
}
