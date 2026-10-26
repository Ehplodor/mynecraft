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
    private int size;

    /** An animated layer (vanilla .png.mcmeta): frames are squares stacked in the strip. */
    private record Animation(int layer, BufferedImage img, int[] transform, int[] frames, int[] times, boolean interpolate, int total) {}
    private final List<Animation> animations = new ArrayList<>();
    private final int[] shown;
    private ByteBuffer frameBuf;

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
            if (images[i] != null) animation(resources, name, i, images[i], transforms[i]);
            if (images[i] != null) size = Math.max(size, Math.min(MAX_SIZE, images[i].getWidth()));
        }
        // Mipmapping a non power of two size is wasteful and blurry: round up.
        size = Integer.highestOneBit(size - 1) << 1;
        this.size = size;
        shown = new int[animations.size()];

        ByteBuffer pixels = BufferUtils.createByteBuffer(size * size * 4 * images.length);
        for (int i = 0; i < images.length; i++) writeLayer(pixels, images[i], transforms[i], size, 0, null, 0);
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

    /** Reads {@code <texture>.png.mcmeta}: frametime (ticks), frames (indices or {index, time}), interpolate. */
    private void animation(ResourceManager resources, String texture, int layer, BufferedImage img, int[] transform) {
        int w = img.getWidth(), count = img.getHeight() / w;
        byte[] meta = resources.read(ResourceManager.path(texture, "textures", ".png.mcmeta"));
        if (meta == null || count < 2) return;
        try {
            var anim = com.google.gson.JsonParser.parseString(new String(meta, java.nio.charset.StandardCharsets.UTF_8))
                .getAsJsonObject().getAsJsonObject("animation");
            if (anim == null) return;
            int frametime = anim.has("frametime") ? Math.max(1, anim.get("frametime").getAsInt()) : 1;
            List<int[]> list = new ArrayList<>();
            if (anim.has("frames")) {
                for (var f : anim.getAsJsonArray("frames")) {
                    if (f.isJsonObject()) {
                        var o = f.getAsJsonObject();
                        list.add(new int[] {o.get("index").getAsInt(), o.has("time") ? Math.max(1, o.get("time").getAsInt()) : frametime});
                    } else list.add(new int[] {f.getAsInt(), frametime});
                }
            } else for (int i = 0; i < count; i++) list.add(new int[] {i, frametime});
            int[] frames = new int[list.size()], times = new int[list.size()];
            int total = 0;
            for (int i = 0; i < frames.length; i++) {
                frames[i] = Math.floorMod(list.get(i)[0], count);
                times[i] = list.get(i)[1];
                total += times[i];
            }
            boolean interpolate = anim.has("interpolate") && anim.get("interpolate").getAsBoolean();
            animations.add(new Animation(layer, img, transform, frames, times, interpolate, total));
        } catch (RuntimeException e) {
            warnings.add("bad animation " + texture + ": " + e.getMessage());
        }
    }

    /**
     * Advances animated textures to the game time (ticks, fractional for interpolation): a layer is
     * re-uploaded only when its frame changes (or every frame while interpolating), then the
     * mipmaps are rebuilt once.
     */
    void animate(double ticks) {
        if (animations.isEmpty()) return;
        boolean changed = false;
        for (int a = 0; a < animations.size(); a++) {
            Animation an = animations.get(a);
            double t = ticks % an.total;
            int i = 0;
            while (t >= an.times[i]) t -= an.times[i++];
            float blend = an.interpolate ? (float) (t / an.times[i]) : 0;
            int next = an.frames[(i + 1) % an.frames.length];
            int key = an.interpolate ? i * 16 + (int) (blend * 16) : i; // 16 interpolation steps
            if (key == shown[a]) continue;
            shown[a] = key;
            if (frameBuf == null) frameBuf = BufferUtils.createByteBuffer(size * size * 4);
            frameBuf.clear();
            writeLayer(frameBuf, an.img, an.transform, size, an.frames[i], an.interpolate ? an.img : null, an.interpolate ? next * 16 + (int) (blend * 16) : 0);
            frameBuf.flip();
            glBindTexture(GL_TEXTURE_2D_ARRAY, id);
            glTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0, 0, 0, an.layer, size, size, 1, GL_RGBA, GL_UNSIGNED_BYTE, frameBuf);
            changed = true;
        }
        if (changed) glGenerateMipmap(GL_TEXTURE_2D_ARRAY);
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

    /**
     * Writes square {@code frame} of the strip scaled nearest-neighbour (null = missing), blended
     * toward frame {@code mix / 16} by {@code (mix % 16) / 16} when {@code blendWith} is given.
     */
    private static void writeLayer(ByteBuffer out, BufferedImage img, int[] m, int size, int frame, BufferedImage blendWith, int mix) {
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
                else {
                    argb = img.getRGB(sx * w / size, frame * w + sy * w / size);
                    if (blendWith != null) argb = lerp(argb, img.getRGB(sx * w / size, (mix / 16) * w + sy * w / size), (mix % 16) / 16f);
                }
                out.put((byte) (argb >> 16)).put((byte) (argb >> 8)).put((byte) argb).put((byte) (argb >>> 24));
            }
        }
    }

    private static int lerp(int a, int b, float t) {
        int r = 0;
        for (int s = 0; s < 32; s += 8) {
            int ca = (a >>> s) & 255, cb = (b >>> s) & 255;
            r |= Math.round(ca + (cb - ca) * t) << s;
        }
        return r;
    }
}
