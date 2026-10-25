package net.mynecraft.resource;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Bitmap font from a pack, vanilla style: font/default.json lists providers; a "bitmap" provider
 * names a grid image and the characters of each grid row, a "space" provider sets advances.
 * Glyph widths are measured from the image (rightmost inked column), as the game does.
 */
public final class FontLoader {
    /** A glyph's cell in the atlas (pixels) and its width in pixels. */
    public record Glyph(int x, int y, int width) {}

    public final BufferedImage atlas;
    public final int cell;
    public final Map<Character, Glyph> glyphs = new HashMap<>();
    public final Map<Character, Integer> spaces = new HashMap<>();

    private FontLoader(BufferedImage atlas, int cell) {
        this.atlas = atlas;
        this.cell = cell;
    }

    /** Loads the first bitmap provider of minecraft:font/default.json; null if the pack has none. */
    public static FontLoader load(ResourceManager resources) {
        byte[] json = resources.read("assets/minecraft/font/default.json");
        if (json == null) return null;
        JsonObject root = JsonParser.parseString(new String(json, StandardCharsets.UTF_8)).getAsJsonObject();
        FontLoader font = null;
        Map<Character, Integer> spaces = new HashMap<>();
        for (JsonElement p : root.getAsJsonArray("providers")) {
            JsonObject provider = p.getAsJsonObject();
            String type = provider.get("type").getAsString();
            if (type.equals("space") && provider.has("advances")) {
                for (var e : provider.getAsJsonObject("advances").entrySet()) {
                    spaces.put(e.getKey().charAt(0), e.getValue().getAsInt());
                }
            } else if (type.equals("bitmap") && font == null) {
                font = bitmap(resources, provider);
            }
        }
        if (font != null) font.spaces.putAll(spaces);
        return font;
    }

    private static FontLoader bitmap(ResourceManager resources, JsonObject provider) {
        String file = provider.get("file").getAsString();
        byte[] png = resources.read(ResourceManager.path(file.replace(".png", ""), "textures", ".png"));
        if (png == null) return null;
        BufferedImage img;
        try {
            img = ImageIO.read(new ByteArrayInputStream(png));
        } catch (IOException e) {
            return null;
        }
        var rows = provider.getAsJsonArray("chars");
        int cols = rows.get(0).getAsString().length();
        int cell = img.getWidth() / cols;
        FontLoader font = new FontLoader(img, cell);
        for (int r = 0; r < rows.size(); r++) {
            String chars = rows.get(r).getAsString();
            for (int c = 0; c < chars.length(); c++) {
                char ch = chars.charAt(c);
                if (ch == 0 || font.glyphs.containsKey(ch)) continue;
                font.glyphs.put(ch, new Glyph(c * cell, r * cell, inkWidth(img, c * cell, r * cell, cell)));
            }
        }
        return font;
    }

    private static int inkWidth(BufferedImage img, int x0, int y0, int cell) {
        for (int x = cell - 1; x >= 0; x--)
            for (int y = 0; y < cell; y++)
                if ((img.getRGB(x0 + x, y0 + y) >>> 24) > 0) return x + 1;
        return 0;
    }

    /** Horizontal advance of a character in atlas pixels (glyph width + 1 pixel spacing). */
    public int advance(char ch) {
        Integer space = spaces.get(ch);
        if (space != null) return space;
        Glyph g = glyphs.get(ch);
        return g == null ? 0 : g.width() + 1;
    }
}
