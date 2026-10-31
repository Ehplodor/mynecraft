package net.mynecraft.world.crafting;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.mynecraft.resource.PackSource;
import net.mynecraft.world.Blocks;
import net.mynecraft.world.Items;
import net.mynecraft.world.actor.Matter;
import net.mynecraft.world.actor.Skill;

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
 *
 * <p>One grammar for every way of making things (M27): a recipe has inputs, a station (the bare hands,
 * the crafting table, a campfire, a furnace...), maybe a tool, a skill and the level it needs, a
 * duration, an energy (heat from fuel, or work from a wheel, a windmill or the arms), outputs and
 * waste returned to the land. Grid recipes read the optional keys {@code mynecraft:skill},
 * {@code mynecraft:level}, {@code mynecraft:tool}; station recipes have their own type:
 * <pre>
 * {"type": "mynecraft:process", "station": "campfire", "ingredients": [{"item": "minecraft:venison", "count": 1}],
 *  "tool": "knife", "skill": "cooking", "level": 0, "seconds": 6, "energy": {"kind": "heat", "amount": 1},
 *  "results": [{"id": "minecraft:cooked_venison", "count": 1}], "waste": [{"matter": "ash", "amount": 0.2}]}
 * </pre>
 */
public final class RecipeBook {
    /** A grid cell requirement: the set of block ids it accepts. */
    public record Ingredient(Set<Short> accepts) {
        public boolean test(short b) { return accepts.contains(b); }
    }

    /** How and by whom a recipe is made: the shared grammar of grid and station recipes. */
    public record Rules(String station, byte tool, Skill skill, int level, double seconds, String energy, double energyAmount,
                        List<Waste> waste) {
        public static final Rules HAND = new Rules("hand", Items.TOOL_NONE, Skill.CRAFTING, 0, 0, "", 0, List.of());
        public static final Rules TABLE = new Rules("crafting_table", Items.TOOL_NONE, Skill.CRAFTING, 0, 0, "", 0, List.of());
    }

    /** Matter given back to the land when the recipe is made. */
    public record Waste(Matter matter, double amount) {}

    /** An item and how many. */
    public record Stack(short item, int count) {}

    /** A station recipe's input: what it accepts, how many. */
    public record Input(Ingredient ingredient, int count) {}

    /** Shaped when {@code width > 0} (cells row by row, null = empty), shapeless otherwise. */
    public record Recipe(String id, int width, int height, Ingredient[] cells, short result, int count, Rules rules) {
        public Recipe(String id, int width, int height, Ingredient[] cells, short result, int count) {
            this(id, width, height, cells, result, count, width > 2 || height > 2 || cells.length > 4 ? Rules.TABLE : Rules.HAND);
        }
    }

    /** A station recipe: counted inputs, several outputs. */
    public record Process(String id, List<Input> inputs, List<Stack> outputs, Rules rules) {}

    /** What a grid crafts right now. */
    public record Result(short block, int count) {}

    public final List<Recipe> recipes = new ArrayList<>();
    public final List<Process> processes = new ArrayList<>();
    public final List<String> warnings = new ArrayList<>();
    private final Map<String, Set<Short>> tags = new LinkedHashMap<>();

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

    private Set<Short> resolveTag(String tag, Map<String, JsonObject> files, Set<String> visiting) {
        Set<Short> done = tags.get(tag);
        if (done != null) return done;
        Set<Short> out = new HashSet<>();
        JsonObject f = files.get(tag);
        if (f != null && visiting.add(tag)) {
            for (JsonElement v : f.getAsJsonArray("values")) {
                String s = v.isJsonObject() ? v.getAsJsonObject().get("id").getAsString() : v.getAsString();
                if (s.startsWith("#")) out.addAll(resolveTag(normalize(s.substring(1)), files, visiting));
                else {
                    short b = block(s);
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

    /** Item for an item id, or -1 if the item is unknown here. */
    private static short block(String id) {
        return net.mynecraft.world.Items.fromName(normalize(id));
    }

    // ---- recipes ----

    private void parse(String id, JsonObject r) {
        String type = r.has("type") ? normalize(r.get("type").getAsString()) : "";
        if (type.equals("mynecraft:process")) { parseProcess(id, r); return; }
        if (!type.equals("minecraft:crafting_shaped") && !type.equals("minecraft:crafting_shapeless")) return;
        try {
            JsonElement res = r.get("result");
            JsonObject result = res.isJsonObject() ? res.getAsJsonObject() : null;
            String resultId = result == null ? res.getAsString()
                : (result.has("id") ? result.get("id") : result.get("item")).getAsString();
            int count = result != null && result.has("count") ? result.get("count").getAsInt() : 1;
            short out = block(resultId);
            if (out <= 0) { warnings.add(id + ": result " + resultId + " is not a known item"); return; }
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
                recipes.add(withRules(new Recipe(id, w, h, cells, out, count), r));
            } else {
                JsonArray list = r.getAsJsonArray("ingredients");
                Ingredient[] cells = new Ingredient[list.size()];
                for (int i = 0; i < cells.length; i++) {
                    cells[i] = ingredient(list.get(i));
                    if (cells[i] == null) { warnings.add(id + ": ingredient is not a block"); return; }
                }
                recipes.add(withRules(new Recipe(id, 0, 0, cells, out, count), r));
            }
        } catch (RuntimeException e) {
            warnings.add(id + ": malformed recipe (" + e.getMessage() + ")");
        }
    }

    /** Grid recipe with its optional grammar keys. */
    private static Recipe withRules(Recipe base, JsonObject r) {
        if (!r.has("mynecraft:skill") && !r.has("mynecraft:level") && !r.has("mynecraft:tool")) return base;
        Rules d = base.rules();
        Skill skill = r.has("mynecraft:skill") ? Skill.byName(r.get("mynecraft:skill").getAsString()) : d.skill();
        Rules rules = new Rules(d.station(), r.has("mynecraft:tool") ? tool(r.get("mynecraft:tool").getAsString()) : d.tool(),
            skill == null ? d.skill() : skill, r.has("mynecraft:level") ? r.get("mynecraft:level").getAsInt() : 0, 0, "", 0, List.of());
        return new Recipe(base.id(), base.width(), base.height(), base.cells(), base.result(), base.count(), rules);
    }

    private static byte tool(String name) {
        for (byte i = 0; i < Items.TOOL_NAMES.length; i++) if (Items.TOOL_NAMES[i].equals(name)) return i;
        return Items.TOOL_NONE;
    }

    private void parseProcess(String id, JsonObject r) {
        try {
            List<Input> inputs = new ArrayList<>();
            for (JsonElement e : r.getAsJsonArray("ingredients")) {
                int n = e.isJsonObject() && e.getAsJsonObject().has("count") ? e.getAsJsonObject().get("count").getAsInt() : 1;
                Ingredient ing = ingredient(e);
                if (ing == null) { warnings.add(id + ": unknown ingredient " + e); return; }
                inputs.add(new Input(ing, n));
            }
            List<Stack> outputs = new ArrayList<>();
            for (JsonElement e : r.getAsJsonArray("results")) {
                JsonObject o = e.getAsJsonObject();
                short item = block(o.get("id").getAsString());
                if (item <= 0) { warnings.add(id + ": result " + o.get("id") + " is not a known item"); return; }
                outputs.add(new Stack(item, o.has("count") ? o.get("count").getAsInt() : 1));
            }
            List<Waste> waste = new ArrayList<>();
            if (r.has("waste"))
                for (JsonElement e : r.getAsJsonArray("waste")) {
                    Matter m = Matter.byName(e.getAsJsonObject().get("matter").getAsString());
                    if (m != null) waste.add(new Waste(m, e.getAsJsonObject().get("amount").getAsDouble()));
                }
            Skill skill = r.has("skill") ? Skill.byName(r.get("skill").getAsString()) : null;
            JsonObject energy = r.has("energy") ? r.getAsJsonObject("energy") : null;
            Rules rules = new Rules(r.get("station").getAsString(), r.has("tool") ? tool(r.get("tool").getAsString()) : Items.TOOL_NONE,
                skill == null ? Skill.CRAFTING : skill, r.has("level") ? r.get("level").getAsInt() : 0,
                r.has("seconds") ? r.get("seconds").getAsDouble() : 2, energy == null ? "" : energy.get("kind").getAsString(),
                energy == null ? 0 : energy.get("amount").getAsDouble(), List.copyOf(waste));
            processes.add(new Process(id, List.copyOf(inputs), List.copyOf(outputs), rules));
        } catch (RuntimeException e) {
            warnings.add(id + ": malformed process (" + e.getMessage() + ")");
        }
    }

    /** Station recipes made at a station, in file order. */
    public List<Process> at(String station) {
        List<Process> out = new ArrayList<>();
        for (Process p : processes) if (p.rules().station().equals(station)) out.add(p);
        return out;
    }

    /** "minecraft:x", "#minecraft:tag", {"item": ...}, {"tag": ...} or a list of those. */
    private Ingredient ingredient(JsonElement e) {
        if (e == null) return null;
        Set<Short> accepts = new HashSet<>();
        if (e.isJsonArray()) {
            for (JsonElement a : e.getAsJsonArray()) {
                Ingredient i = ingredient(a);
                if (i != null) accepts.addAll(i.accepts());
            }
        } else if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            if (o.has("tag")) accepts.addAll(tags.getOrDefault(normalize(o.get("tag").getAsString()), Set.of()));
            else if (o.has("item")) { short b = block(o.get("item").getAsString()); if (b > 0) accepts.add(b); }
        } else {
            String s = e.getAsString();
            if (s.startsWith("#")) accepts.addAll(tags.getOrDefault(normalize(s.substring(1)), Set.of()));
            else { short b = block(s); if (b > 0) accepts.add(b); }
        }
        return accepts.isEmpty() ? null : new Ingredient(accepts);
    }

    // ---- matching ----

    /** What a size x size grid (row by row, AIR = empty) crafts, or null. */
    public Result match(short[] grid, int size) {
        Recipe r = matchRecipe(grid, size);
        return r == null ? null : new Result(r.result(), r.count());
    }

    /** The recipe a size x size grid matches, or null. */
    public Recipe matchRecipe(short[] grid, int size) {
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
            if (ok) return r;
        }
        return null;
    }

    private static boolean shaped(Recipe r, short[] grid, int size, int ox, int oy, boolean mirror) {
        for (int y = 0; y < r.height(); y++)
            for (int x = 0; x < r.width(); x++) {
                Ingredient ing = r.cells()[y * r.width() + (mirror ? r.width() - 1 - x : x)];
                short b = grid[(oy + y) * size + ox + x];
                if (ing == null ? b != Blocks.AIR : !ing.test(b)) return false;
            }
        return true;
    }

    private static boolean shapeless(Recipe r, short[] grid, int filled) {
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
