package net.mynecraft.world;

import java.util.concurrent.ConcurrentHashMap;

public final class World {
    private final ConcurrentHashMap<Long, Chunk> chunks = new ConcurrentHashMap<>();
    public final TerrainGenerator generator;
    public final long seed;
    public final LightEngine light = new LightEngine(this);

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

    /** Unique key of a 16^3 section (cz on 27 bits, section index on 5). */
    public static long sectionKey(int cx, int sy, int cz) {
        return ((long) cx << 32) | ((cz & 0x7FFFFFFL) << 5) | sy;
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
        if (y >= Chunk.MAX_Y) return false;
        if (y < Chunk.MIN_Y) return true;
        Chunk c = chunk(x >> 4, z >> 4);
        return c == null || Blocks.isSolid(c.get(x & 15, y, z & 15));
    }

    /**
     * Collision box of a block as (minY | maxY &lt;&lt; 8) in sixteenths, or -1 if nothing collides.
     * Unloaded chunks and below the world are full cubes.
     */
    public int collisionBox(int x, int y, int z) {
        if (y >= Chunk.MAX_Y) return -1;
        if (y < Chunk.MIN_Y) return 16 << 8;
        Chunk c = chunk(x >> 4, z >> 4);
        if (c == null) return 16 << 8;
        byte b = c.get(x & 15, y, z & 15);
        return Blocks.isSolid(b) ? Blocks.BOX_MIN[b & 0xFF] | Blocks.BOX_MAX[b & 0xFF] << 8 : -1;
    }

    /** Sets a block in a loaded chunk; returns false if out of range or unchanged. */
    public boolean set(int x, int y, int z, byte id) {
        if (y < Chunk.MIN_Y || y >= Chunk.MAX_Y) return false;
        Chunk c = chunk(x >> 4, z >> 4);
        if (c == null || c.get(x & 15, y, z & 15) == id) return false;
        c.set(x & 15, y, z & 15, id);
        c.markDirty();
        light.blockChanged(x, y, z);
        return true;
    }

    /** sky &lt;&lt; 4 | block light; full sky above the world, darkness below or in unloaded chunks. */
    public int getLight(int x, int y, int z) {
        if (y >= Chunk.MAX_Y) return Chunk.DEFAULT_LIGHT;
        if (y < Chunk.MIN_Y) return 0;
        Chunk c = chunk(x >> 4, z >> 4);
        return c == null ? Chunk.DEFAULT_LIGHT : c.getLight(x & 15, y, z & 15);
    }

    public byte get(int x, int y, int z) {
        if (y < Chunk.MIN_Y || y >= Chunk.MAX_Y) return Blocks.AIR;
        Chunk c = chunk(x >> 4, z >> 4);
        return c == null ? Blocks.AIR : c.get(x & 15, y, z & 15);
    }
}
