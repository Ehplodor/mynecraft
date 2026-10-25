package net.mynecraft.world;

public final class TerrainGenerator {
    public static final int SEA_LEVEL = 40;
    private final Noise noise;

    public TerrainGenerator(long seed) {
        this.noise = new Noise(seed);
    }

    public int height(int wx, int wz) {
        float n = noise.fbm(wx / 128f, wz / 128f, 5);
        int h = SEA_LEVEL + 10 + (int) (n * 45);
        return Math.max(1, Math.min(Chunk.HEIGHT - 1, h));
    }

    public Chunk generate(int cx, int cz) {
        Chunk c = new Chunk(cx, cz);
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int h = height((cx << 4) + x, (cz << 4) + z);
                byte top = h <= SEA_LEVEL + 1 ? Blocks.SAND : Blocks.GRASS;
                byte under = h <= SEA_LEVEL + 1 ? Blocks.SAND : Blocks.DIRT;
                c.set(x, 0, z, Blocks.BEDROCK);
                for (int y = 1; y < h - 3; y++) c.set(x, y, z, Blocks.STONE);
                for (int y = Math.max(1, h - 3); y < h; y++) c.set(x, y, z, under);
                c.set(x, h, z, top);
            }
        }
        c.compact();
        return c;
    }
}
