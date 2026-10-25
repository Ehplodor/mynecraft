package net.mynecraft.client;

import net.mynecraft.resource.BlockAppearance;
import net.mynecraft.resource.ResourceManager;
import org.lwjgl.BufferUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.opengl.GL33.*;

/**
 * All block textures in one GL_TEXTURE_2D_ARRAY (one layer each), so a section is a single draw
 * with no texture switches, and greedy quads can tile with GL_REPEAT (impossible with an atlas).
 */
final class TextureArray {
    private static final int MAX_SIZE = 512;
    final int id;
    final List<String> warnings = new ArrayList<>();

    TextureArray(BlockAppearance appearance, ResourceManager resources) {
        List<String> names = appearance.textures;
        BufferedImage[] images = new BufferedImage[names.size()];
        int[][] transforms = new int[names.size()][];
        int size = 16;
        for (int i = 1; i < images.length; i++) {
            String name = names.get(i);
            int hash = name.indexOf('#');
            if (hash >= 0) {
                // Rotated variant: same image, sampled through a 2x2 signed permutation.
                String[] m = name.substring(hash + 1).split(",");
                transforms[i] = new int[] {Integer.parseInt(m[0]), Integer.parseInt(m[1]), Integer.parseInt(m[2]), Integer.parseInt(m[3])};
                name = name.substring(0, hash);
            }
            images[i] = load(resources, name);
            if (images[i] != null) size = Math.max(size, Math.min(MAX_SIZE, images[i].getWidth()));
        }
        // Mipmapping a non power of two size is wasteful and blurry: round up.
        size = Integer.highestOneBit(size - 1) << 1;

        ByteBuffer pixels = BufferUtils.createByteBuffer(size * size * 4 * images.length);
        for (int i = 0; i < images.length; i++) writeLayer(pixels, images[i], transforms[i], size);
        pixels.flip();

        id = glGenTextures();
        glBindTexture(GL_TEXTURE_2D_ARRAY, id);
        glTexImage3D(GL_TEXTURE_2D_ARRAY, 0, GL_RGBA8, size, size, images.length, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_NEAREST_MIPMAP_LINEAR);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_S, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_T, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAX_LEVEL, Integer.numberOfTrailingZeros(size));
        glGenerateMipmap(GL_TEXTURE_2D_ARRAY);
    }

    private BufferedImage load(ResourceManager resources, String texture) {
        if (texture.equals(BlockAppearance.MISSING)) return null;
        byte[] data = resources.read(ResourceManager.path(texture, "textures", ".png"));
        if (data == null) { warnings.add("missing texture " + texture); return null; }
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(data));
            if (img == null) warnings.add("unreadable texture " + texture);
            return img;
        } catch (IOException e) {
            warnings.add("unreadable texture " + texture + ": " + e.getMessage());
            return null;
        }
    }

    /** Writes the first frame (top square, for animated strips) scaled nearest-neighbour; null = missing. */
    private static void writeLayer(ByteBuffer out, BufferedImage img, int[] m, int size) {
        int w = img == null ? 0 : img.getWidth();
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int sx = x, sy = y;
                if (m != null) {
                    // (s, t) = M (u, v) on texel centres, wrapped into [0, size).
                    sx = Math.floorMod(m[0] * (2 * x + 1) + m[1] * (2 * y + 1), 2 * size) / 2;
                    sy = Math.floorMod(m[2] * (2 * x + 1) + m[3] * (2 * y + 1), 2 * size) / 2;
                }
                int argb;
                if (img == null) argb = ((x * 2 / size) ^ (y * 2 / size)) == 0 ? 0xFFF800F8 : 0xFF000000;
                else argb = img.getRGB(sx * w / size, sy * w / size);
                out.put((byte) (argb >> 16)).put((byte) (argb >> 8)).put((byte) argb).put((byte) (argb >>> 24));
            }
        }
    }
}
