package net.mynecraft.world;

import java.util.concurrent.ConcurrentHashMap;

public final class World {
    private final ConcurrentHashMap<Long, Chunk> chunks = new ConcurrentHashMap<>();
    public final TerrainGenerator generator;

    public World(long seed) {
        this.generator = new TerrainGenerator(seed);
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

    public byte get(int x, int y, int z) {
        if (y < 0 || y >= Chunk.HEIGHT) return Blocks.AIR;
        Chunk c = chunk(x >> 4, z >> 4);
        return c == null ? Blocks.AIR : c.get(x & 15, y, z & 15);
    }
}
