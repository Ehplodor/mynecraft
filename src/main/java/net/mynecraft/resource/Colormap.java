package net.mynecraft.resource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * Biome colour lookup from a pack's 256x256 colormap (textures/colormap/grass.png, foliage.png),
 * indexed the vanilla way: x = (1 - temperature), y = (1 - downfall * temperature).
 */
public final class Colormap {
    private final int[] pixels; // null: fixed fallback colour
    private final int fallback;

    private Colormap(int[] pixels, int fallback) {
        this.pixels = pixels;
        this.fallback = fallback;
    }

    public static Colormap load(ResourceManager resources, String name, int fallbackRgb) {
        byte[] data = resources.read("assets/minecraft/textures/colormap/" + name + ".png");
        if (data != null) {
            try {
                BufferedImage img = ImageIO.read(new ByteArrayInputStream(data));
                if (img != null && img.getWidth() == 256 && img.getHeight() == 256) {
                    return new Colormap(img.getRGB(0, 0, 256, 256, null, 0, 256), fallbackRgb);
                }
            } catch (IOException ignored) {
                // fall through to the fixed colour
            }
        }
        return new Colormap(null, fallbackRgb);
    }

    /** @return 0xRRGGBB */
    public int color(float temperature, float downfall) {
        if (pixels == null) return fallback;
        float t = Math.max(0, Math.min(1, temperature));
        float d = Math.max(0, Math.min(1, downfall)) * t;
        int x = (int) ((1 - t) * 255), y = (int) ((1 - d) * 255);
        return pixels[y * 256 + x] & 0xFFFFFF;
    }
}
