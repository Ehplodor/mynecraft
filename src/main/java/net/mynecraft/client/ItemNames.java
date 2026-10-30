package net.mynecraft.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.mynecraft.resource.ResourceManager;
import net.mynecraft.world.Blocks;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;

/**
 * Display names of blocks, from the packs' language file ({@code assets/minecraft/lang/fr_fr.json},
 * then {@code en_us.json}: a resource pack can rename or translate), else made from the id
 * ("oak_planks" gives "Oak planks"). Also a folded form for searching (no case, no accents).
 */
final class ItemNames {
    private final String[] names = new String[net.mynecraft.world.Items.COUNT];
    private final String[] folded = new String[net.mynecraft.world.Items.COUNT];

    ItemNames(ResourceManager resources) {
        JsonObject lang = load(resources, "fr_fr"), english = load(resources, "en_us");
        for (int i = 0; i < names.length; i++) {
            String id = net.mynecraft.world.Items.name((short) i);
            String key = (i < Blocks.COUNT ? "block.minecraft." : "item.minecraft.") + id, name = null;
            if (lang != null && lang.has(key)) name = lang.get(key).getAsString();
            else if (english != null && english.has(key)) name = english.get(key).getAsString();
            if (name == null) {
                String t = id.replace('_', ' ');
                name = t.isEmpty() ? t : Character.toUpperCase(t.charAt(0)) + t.substring(1);
            }
            names[i] = name;
            folded[i] = fold(name) + " " + fold(id);
        }
    }

    private static JsonObject load(ResourceManager resources, String locale) {
        byte[] data = resources.read("assets/minecraft/lang/" + locale + ".json");
        if (data == null) return null;
        try {
            return JsonParser.parseString(new String(data, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException e) {
            System.err.println("[resources] unreadable lang/" + locale + ".json: " + e.getMessage());
            return null;
        }
    }

    /** Lower case without accents: "Établi" and "etabli" match. */
    static String fold(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(java.util.Locale.ROOT);
    }

    String name(short id) {
        return names[id & 0xFFFF];
    }

    boolean matches(short id, String query) {
        return query.isEmpty() || folded[id & 0xFFFF].contains(fold(query.trim()));
    }
}
