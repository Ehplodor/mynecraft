package net.mynecraft.world;

import java.util.concurrent.ConcurrentHashMap;

public final class World {
    private final ConcurrentHashMap<Long, Chunk> chunks = new ConcurrentHashMap<>();
    public final TerrainGenerator generator;
    public final long seed;

    public World(long seed) {
        this.seed = seed;
        this.generator = new TerrainGenerator(seed);
    }

    /** Publishes a loaded or generated chunk; it becomes visible to collisions and meshing. */
    public void put(Chunk c) {
        chunks.put(key(c.cx, c.cz), c);
    }

    public Chunk remove(int cx, int cz) {
        return chunks.remove(key(cx, cz));
    }

    public java.util.Collection<Chunk> loadedChunks() {
        return chunks.values();
    }

    public static long key(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    public Chunk chunk(int cx, int cz) {
        return chunks.get(key(cx, cz));
    }

    public Chunk getOrGenerate(int cx, int cz) {
        return chunks.computeIfAbsent(key(cx, cz), k -> generator.generate(cx, cz));
    }

    /** Inserts an empty chunk (used by tools and tests). */
    public Chunk getOrGenerateEmpty(int cx, int cz) {
        return chunks.computeIfAbsent(key(cx, cz), k -> new Chunk(cx, cz));
    }

    /** Collision view: below the world and unloaded chunks count as solid so nobody falls out. */
    public boolean isSolid(int x, int y, int z) {
        if (y >= Chunk.HEIGHT) return false;
        if (y < 0) return true;
        Chunk c = chunk(x >> 4, z >> 4);
        return c == null || Blocks.isOpaque(c.get(x & 15, y, z & 15));
    }

    /** Sets a block in a loaded chunk; returns false if out of range or unchanged. */
    public boolean set(int x, int y, int z, byte id) {
        if (y < 0 || y >= Chunk.HEIGHT) return false;
        Chunk c = chunk(x >> 4, z >> 4);
        if (c == null || c.get(x & 15, y, z & 15) == id) return false;
        c.set(x & 15, y, z & 15, id);
        c.markDirty();
        return true;
    }

    public byte get(int x, int y, int z) {
        if (y < 0 || y >= Chunk.HEIGHT) return Blocks.AIR;
        Chunk c = chunk(x >> 4, z >> 4);
        return c == null ? Blocks.AIR : c.get(x & 15, y, z & 15);
    }
}
