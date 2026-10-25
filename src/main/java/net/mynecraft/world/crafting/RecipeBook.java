package net.mynecraft.world.crafting;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.mynecraft.resource.PackSource;
import net.mynecraft.world.Blocks;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Crafting recipes from data packs, vanilla layout: data/&lt;ns&gt;/recipe/*.json (recipes/ before 1.21)
 * and item tags in data/&lt;ns&gt;/tags/item/*.json (tags/items/ before 1.21). Later packs override
 * earlier ones file by file, like vanilla. Supports crafting_shaped (mirrored matches allowed) and
 * crafting_shapeless; ingredients may be item ids, "#tags", or the older {"item"}/{"tag"} objects;
 * results may use "id" (1.20.5+) or "item". Recipes involving items that are not blocks here are
 * skipped and reported.
 */
public final class RecipeBook {
    /** A grid cell requirement: the set of block ids it accepts. */
    public record Ingredient(Set<Byte> accepts) {
        boolean test(byte b) { return accepts.contains(b); }
    }

    /** Shaped when {@code width > 0} (cells row by row, null = empty), shapeless otherwise. */
    public record Recipe(String id, int width, int height, Ingredient[] cells, byte result, int count) {}

    /** What a grid crafts right now. */
    public record Result(byte block, int count) {}

    public final List<Recipe> recipes = new ArrayList<>();
    public final List<String> warnings = new ArrayList<>();
    private final Map<String, Set<Byte>> tags = new LinkedHashMap<>();

    public static RecipeBook load(List<PackSource> packs) {
        RecipeBook book = new RecipeBook();
        Map<String, JsonObject> tagFiles = new LinkedHashMap<>(), recipeFiles = new LinkedHashMap<>();
        for (PackSource pack : packs) {
            try {
                for (String path : pack.list("data/")) {
                    String[] parts = path.split("/", 4); // data, ns, kind..., rest
                    if (parts.length < 4 || !path.endsWith(".json")) continue;
                    String ns = parts[1], rest = parts[2] + "/" + parts[3];
                    String key;
                    Map<String, JsonObject> target;
                    if (rest.startsWith("recipe/") || rest.startsWith("recipes/")) {
                        key = ns + ":" + rest.substring(rest.indexOf('/') + 1, rest.length() - 5);
                        target = recipeFiles;
                    } else if (rest.startsWith("tags/item/") || rest.startsWith("tags/items/")) {
                        String t = rest.substring(rest.indexOf('/', 5) + 1);
                        key = ns + ":" + t.substring(0, t.length() - 5);
                        target = tagFiles;
                    } else {
                        continue;
                    }
                    byte[] data = pack.read(path);
                    if (data == null) continue;
                    try {
                        target.put(key, JsonParser.parseString(new String(data, StandardCharsets.UTF_8)).getAsJsonObject());
                    } catch (RuntimeException e) {
                        book.warnings.add("bad JSON in " + pack.name() + "/" + path);
                    }
                }
            } catch (IOException e) {
                book.warnings.add("cannot list " + pack.name() + ": " + e.getMessage());
            }
        }
        for (String tag : tagFiles.keySet()) book.resolveTag(tag, tagFiles, new HashSet<>());
        for (var e : recipeFiles.entrySet()) book.parse(e.getKey(), e.getValue());
        return book;
    }

    // ---- tags ----

    private Set<Byte> resolveTag(String tag, Map<String, JsonObject> files, Set<String> visiting) {
        Set<Byte> done = tags.get(tag);
        if (done != null) return done;
        Set<Byte> out = new HashSet<>();
        JsonObject f = files.get(tag);
        if (f != null && visiting.add(tag)) {
            for (JsonElement v : f.getAsJsonArray("values")) {
                String s = v.isJsonObject() ? v.getAsJsonObject().get("id").getAsString() : v.getAsString();
                if (s.startsWith("#")) out.addAll(resolveTag(normalize(s.substring(1)), files, visiting));
                else {
                    byte b = block(s);
                    if (b > 0) out.add(b);
                }
            }
        }
        tags.put(tag, out);
        return out;
    }

    private static String normalize(String id) {
        return id.indexOf(':') < 0 ? "minecraft:" + id : id;
    }

    /** Block for an item id, or -1 if the item is not a block here. */
    private static byte block(String id) {
        String n = normalize(id);
        if (!n.startsWith("minecraft:")) return -1;
        return Blocks.fromState(n.substring(10), Map.of());
    }

    // ---- recipes ----

    private void parse(String id, JsonObject r) {
        String type = r.has("type") ? normalize(r.get("type").getAsString()) : "";
        if (!type.equals("minecraft:crafting_shaped") && !type.equals("minecraft:crafting_shapeless")) return;
        try {
            JsonElement res = r.get("result");
            JsonObject result = res.isJsonObject() ? res.getAsJsonObject() : null;
            String resultId = result == null ? res.getAsString()
                : (result.has("id") ? result.get("id") : result.get("item")).getAsString();
            int count = result != null && result.has("count") ? result.get("count").getAsInt() : 1;
            byte out = block(resultId);
            if (out <= 0) { warnings.add(id + ": result " + resultId + " is not a block"); return; }
            if (type.endsWith("shaped")) {
                JsonArray pattern = r.getAsJsonArray("pattern");
                int h = pattern.size(), w = 0;
                for (JsonElement row : pattern) w = Math.max(w, row.getAsString().length());
                Ingredient[] cells = new Ingredient[w * h];
                JsonObject key = r.getAsJsonObject("key");
                for (int y = 0; y < h; y++) {
                    String row = pattern.get(y).getAsString();
                    for (int x = 0; x < row.length(); x++) {
                        char c = row.charAt(x);
                        if (c == ' ') continue;
                        Ingredient ing = ingredient(key.get(String.valueOf(c)));
                        if (ing == null) { warnings.add(id + ": ingredient '" + c + "' is not a block"); return; }
                        cells[y * w + x] = ing;
                    }
                }
                recipes.add(new Recipe(id, w, h, cells, out, count));
            } else {
                JsonArray list = r.getAsJsonArray("ingredients");
                Ingredient[] cells = new Ingredient[list.size()];
                for (int i = 0; i < cells.length; i++) {
                    cells[i] = ingredient(list.get(i));
                    if (cells[i] == null) { warnings.add(id + ": ingredient is not a block"); return; }
                }
                recipes.add(new Recipe(id, 0, 0, cells, out, count));
            }
        } catch (RuntimeException e) {
            warnings.add(id + ": malformed recipe (" + e.getMessage() + ")");
        }
    }

    /** "minecraft:x", "#minecraft:tag", {"item": ...}, {"tag": ...} or a list of those. */
    private Ingredient ingredient(JsonElement e) {
        if (e == null) return null;
        Set<Byte> accepts = new HashSet<>();
        if (e.isJsonArray()) {
            for (JsonElement a : e.getAsJsonArray()) {
                Ingredient i = ingredient(a);
                if (i != null) accepts.addAll(i.accepts());
            }
        } else if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            if (o.has("tag")) accepts.addAll(tags.getOrDefault(normalize(o.get("tag").getAsString()), Set.of()));
            else if (o.has("item")) { byte b = block(o.get("item").getAsString()); if (b > 0) accepts.add(b); }
        } else {
            String s = e.getAsString();
            if (s.startsWith("#")) accepts.addAll(tags.getOrDefault(normalize(s.substring(1)), Set.of()));
            else { byte b = block(s); if (b > 0) accepts.add(b); }
        }
        return accepts.isEmpty() ? null : new Ingredient(accepts);
    }

    // ---- matching ----

    /** What a size x size grid (row by row, AIR = empty) crafts, or null. */
    public Result match(byte[] grid, int size) {
        int minX = size, minY = size, maxX = -1, maxY = -1, filled = 0;
        for (int y = 0; y < size; y++)
            for (int x = 0; x < size; x++)
                if (grid[y * size + x] != Blocks.AIR) {
                    filled++;
                    minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y); maxY = Math.max(maxY, y);
                }
        if (filled == 0) return null;
        int w = maxX - minX + 1, h = maxY - minY + 1;
        for (Recipe r : recipes) {
            boolean ok = r.width() > 0
                ? r.width() == w && r.height() == h && (shaped(r, grid, size, minX, minY, false) || shaped(r, grid, size, minX, minY, true))
                : shapeless(r, grid, filled);
            if (ok) return new Result(r.result(), r.count());
        }
        return null;
    }

    private static boolean shaped(Recipe r, byte[] grid, int size, int ox, int oy, boolean mirror) {
        for (int y = 0; y < r.height(); y++)
            for (int x = 0; x < r.width(); x++) {
                Ingredient ing = r.cells()[y * r.width() + (mirror ? r.width() - 1 - x : x)];
                byte b = grid[(oy + y) * size + ox + x];
                if (ing == null ? b != Blocks.AIR : !ing.test(b)) return false;
            }
        return true;
    }

    private static boolean shapeless(Recipe r, byte[] grid, int filled) {
        if (r.cells().length != filled) return false;
        boolean[] used = new boolean[grid.length];
        for (Ingredient ing : r.cells()) {
            boolean found = false;
            for (int i = 0; i < grid.length && !found; i++) {
                if (!used[i] && grid[i] != Blocks.AIR && ing.test(grid[i])) { used[i] = true; found = true; }
            }
            if (!found) return false;
        }
        return true;
    }
}
