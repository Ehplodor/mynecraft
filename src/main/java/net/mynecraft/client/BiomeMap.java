package net.mynecraft.client;

import net.mynecraft.resource.Colormap;
import net.mynecraft.world.Landscape;
import net.mynecraft.world.TerrainGenerator;
import net.mynecraft.world.life.Ecosystem;
import org.lwjgl.BufferUtils;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL33.*;

/**
 * Grass and foliage colour per block column for the loaded area, in a 512x512 texture array that
 * wraps (world x, z mod 512): render distance stays below 32 chunks so live columns never collide.
 * The vertex shader samples it with linear filtering at block corners, which blends neighbouring
 * columns for free. The colours turn with the seasons: fresh green in spring, dry grass and red,
 * orange and yellow leaves in autumn, bare brown woods and frost in winter (evergreen taiga keeps
 * its needles), snow on whatever is below freezing.
 */
final class BiomeMap {
    static final int SIZE = 512;
    final int id;
    private final TerrainGenerator generator;
    private final Colormap grass, foliage;
    private final ByteBuffer scratch = BufferUtils.createByteBuffer(16 * 16 * 4);
    /** Season shown (phase of the year 0..1, from the start of spring); -1 before the first. */
    private double phase = 0.18;

    /** Moves the colours to another moment of the year; true if they changed enough to be redrawn. */
    boolean setSeason(double newPhase) {
        double d = Math.abs(newPhase - phase);
        if (Math.min(d, 1 - d) < 0.004) return false; // about a third of a day
        phase = newPhase;
        return true;
    }

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
        int[] grassRgb = new int[256], leafRgb = new int[256];
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int wx = (cx << 4) + x, wz = (cz << 4) + z;
                float t = generator.temperature(wx, wz), rain = generator.downfall(wx, wz);
                Landscape.Column col = generator.column(wx, wz);
                double celsius = -14 + 40 * t - 0.0065 * Math.max(0, col.height() - TerrainGenerator.SEA_LEVEL)
                    + Ecosystem.temperatureOffset(phase);
                long h = (wx * 0x9E3779B97F4A7C15L) ^ (wz * 0xC2B2AE3D27D4EB4FL);
                double hue = ((h >>> 40) & 0xFFFF) / 65536.0;
                grassRgb[z * 16 + x] = seasonal(grass.color(t, rain), false, celsius, col.biome(), hue, phase);
                leafRgb[z * 16 + x] = seasonal(foliage.color(t, rain), true, celsius, col.biome(), hue, phase);
            }
        }
        for (int layer = 0; layer < 2; layer++) {
            int[] rgbs = layer == 0 ? grassRgb : leafRgb;
            scratch.clear();
            for (int rgb : rgbs) scratch.put((byte) (rgb >> 16)).put((byte) (rgb >> 8)).put((byte) rgb).put((byte) 255);
            scratch.flip();
            glTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0, tx, tz, layer, 16, 16, 1, GL_RGBA, GL_UNSIGNED_BYTE, scratch);
        }
    }

    /** How strongly a moment of the year belongs to a season centred on {@code centre} (0..1). */
    private static double bump(double phase, double centre, double width) {
        double d = Math.abs(phase - centre);
        d = Math.min(d, 1 - d);
        return Math.exp(-d * d / (2 * width * width));
    }

    /**
     * A biome colour at this time of year. {@code hue} 0..1 varies per column (which trees turn red,
     * orange or yellow); {@code celsius} is the air temperature with the season (snow below ~0).
     */
    static int seasonal(int rgb, boolean leaves, double celsius, byte biome, double hue, double phase) {
        double r = ((rgb >> 16) & 255) / 255.0, g = ((rgb >> 8) & 255) / 255.0, b = (rgb & 255) / 255.0;
        double spring = bump(phase, 0.08, 0.08), autumn = bump(phase, 0.62, 0.1), winter = bump(phase, 0.87, 0.1);
        double[] c = {r, g, b};
        if (!leaves) {
            scale(c, 1 + 0.08 * spring);
            mix(c, 0.74, 0.66, 0.38, 0.45 * autumn);   // drying grass
            mix(c, 0.55, 0.53, 0.38, 0.4 * winter);    // dormant grass
        } else {
            double deciduous = biome == Landscape.TAIGA || biome == Landscape.SNOWY_TAIGA ? 0
                : biome == Landscape.DARK_FOREST ? 0.8 : 1;
            if (hue < 0.4) mix(c, 0.86, 0.2, 0.08, 0.9 * autumn * deciduous);       // red
            else if (hue < 0.8) mix(c, 0.95, 0.5, 0.1, 0.9 * autumn * deciduous);   // orange
            else mix(c, 0.92, 0.8, 0.2, 0.9 * autumn * deciduous);                  // yellow
            mix(c, 0.47, 0.4, 0.32, 0.65 * winter * deciduous);                      // bare branches
            scale(c, 1 - 0.12 * winter * (1 - deciduous));                         // dark winter needles
        }
        double snow = Math.max(0, Math.min(1, (0.5 - celsius) / 4));
        mix(c, 0.92, 0.94, 0.97, (leaves ? 0.5 : 0.85) * snow);
        int ir = (int) Math.round(Math.min(1, c[0]) * 255), ig = (int) Math.round(Math.min(1, c[1]) * 255), ib = (int) Math.round(Math.min(1, c[2]) * 255);
        return ir << 16 | ig << 8 | ib;
    }

    private static void mix(double[] c, double r, double g, double b, double k) {
        c[0] += (r - c[0]) * k;
        c[1] += (g - c[1]) * k;
        c[2] += (b - c[2]) * k;
    }

    private static void scale(double[] c, double k) {
        for (int i = 0; i < 3; i++) c[i] *= k;
    }

    void dispose() {
        glDeleteTextures(id);
    }
}
