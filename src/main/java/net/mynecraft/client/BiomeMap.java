package net.mynecraft.client;

import net.mynecraft.resource.Colormap;
import net.mynecraft.world.TerrainGenerator;
import org.lwjgl.BufferUtils;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL33.*;

/**
 * Grass and foliage colour per block column for the loaded area, in a 512x512 texture array that
 * wraps (world x, z mod 512): render distance stays below 32 chunks so live columns never collide.
 * The vertex shader samples it with linear filtering at block corners, which blends neighbouring
 * columns for free.
 */
final class BiomeMap {
    static final int SIZE = 512;
    final int id;
    private final TerrainGenerator generator;
    private final Colormap grass, foliage;
    private final ByteBuffer scratch = BufferUtils.createByteBuffer(16 * 16 * 4);

    BiomeMap(TerrainGenerator generator, Colormap grass, Colormap foliage) {
        this.generator = generator;
        this.grass = grass;
        this.foliage = foliage;
        id = glGenTextures();
        glBindTexture(GL_TEXTURE_2D_ARRAY, id);
        glTexImage3D(GL_TEXTURE_2D_ARRAY, 0, GL_RGBA8, SIZE, SIZE, 2, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_S, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_T, GL_REPEAT);
    }

    /** Fills the 16x16 columns of a chunk (both layers). */
    void chunkVisible(int cx, int cz) {
        int tx = (cx << 4) & (SIZE - 1), tz = (cz << 4) & (SIZE - 1);
        glBindTexture(GL_TEXTURE_2D_ARRAY, id);
        for (int layer = 0; layer < 2; layer++) {
            Colormap map = layer == 0 ? grass : foliage;
            scratch.clear();
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int wx = (cx << 4) + x, wz = (cz << 4) + z;
                    int rgb = map.color(generator.temperature(wx, wz), generator.downfall(wx, wz));
                    scratch.put((byte) (rgb >> 16)).put((byte) (rgb >> 8)).put((byte) rgb).put((byte) 255);
                }
            }
            scratch.flip();
            glTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0, tx, tz, layer, 16, 16, 1, GL_RGBA, GL_UNSIGNED_BYTE, scratch);
        }
    }

    void dispose() {
        glDeleteTextures(id);
    }
}
