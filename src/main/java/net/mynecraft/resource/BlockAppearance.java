package net.mynecraft.resource;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.mynecraft.world.Blocks;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves every block's six face textures from the pack stack:
 * blockstates/&lt;block&gt;.json -> model (following "parent") -> element faces -> "#ref" textures.
 *
 * <p>Result is a flat {@link #faceInfo} table (blockId * 6 + face: layer | tint type &lt;&lt; 10) consumed by
 * the mesher, plus the
 * ordered list of texture ids whose index is the texture-array layer. Layer 0 is the missing texture.
 */
public final class BlockAppearance {
    /** Our face order (matches the mesher): -x, +x, -y, +y, -z, +z. */
    static final String[] FACE_NAMES = {"west", "east", "down", "up", "north", "south"};
    public static final int LAYER_MASK = (1 << 10) - 1;
    public static final int TINT_SHIFT = 10;
    /** Face draws a tinted overlay texture (stored in layer + 1) over an untinted base (e.g. grass sides). */
    public static final int OVERLAY_BIT = 1 << 12;
    public static final String MISSING = "mynecraft:missing";

    public final List<String> textures = new ArrayList<>();
    public final int[] faceInfo = new int[Blocks.COUNT * 6];
    public final List<String> warnings = new ArrayList<>();

    private final ResourceManager resources;
    private final Map<String, Integer> layers = new HashMap<>();
    private final Map<String, JsonObject> modelCache = new HashMap<>();

    public BlockAppearance(ResourceManager resources) {
        this.resources = resources;
        layer(MISSING);
        for (int id = 1; id < Blocks.COUNT; id++) resolveBlock(id);
    }

    private int layer(String texture) {
        return layers.computeIfAbsent(texture, t -> {
            textures.add(t);
            return textures.size() - 1;
        });
    }

    /** Two consecutive layers: base, then overlay (the shader reads layer + 1). */
    private int pairLayer(String base, String over) {
        return layers.computeIfAbsent(base + "|" + over, k -> {
            textures.add(base);
            textures.add(over);
            return textures.size() - 2;
        });
    }

    private JsonObject json(String path) {
        byte[] data = resources.read(path);
        if (data == null) return null;
        return JsonParser.parseString(new String(data, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private void resolveBlock(int id) {
        String name = "minecraft:" + Blocks.NAMES[id];
        String[] tex = new String[6], overlay = new String[6];
        boolean[] tint = new boolean[6];
        if (id == Blocks.WATER) {
            // Fluids have no block model in Java packs: the game draws them from *_still textures.
            java.util.Arrays.fill(tex, "minecraft:block/water_still");
            java.util.Arrays.fill(tint, true);
        } else {
            String modelId = modelFromBlockstate(name);
            if (modelId == null) warnings.add("no blockstate for " + name);
            else resolveModel(ResourceManager.normalize(modelId), tex, tint, overlay);
        }
        if (Blocks.SHAPE[id] == Blocks.SHAPE_CROSS) {
            // Cross models only texture the faces of their diagonal planes: reuse one for all.
            for (int f = 0; f < 6; f++) if (tex[f] != null) {
                String t = tex[f];
                boolean ti = tint[f];
                for (int g = 0; g < 6; g++) { tex[g] = t; tint[g] = ti; }
                break;
            }
        }
        for (int f = 0; f < 6; f++) {
            if (tex[f] == null) tex[f] = MISSING;
            if (overlay[f] != null) {
                faceInfo[id * 6 + f] = pairLayer(tex[f], overlay[f]) | Blocks.TINT[id] << TINT_SHIFT | OVERLAY_BIT;
            } else {
                faceInfo[id * 6 + f] = layer(tex[f]) | (tint[f] ? Blocks.TINT[id] << TINT_SHIFT : 0);
            }
        }
    }

    private String modelFromBlockstate(String block) {
        JsonObject bs = json(ResourceManager.path(block, "blockstates", ".json"));
        if (bs == null) return null;
        JsonElement variant = null;
        if (bs.has("variants")) {
            JsonObject variants = bs.getAsJsonObject("variants");
            variant = variants.has("") ? variants.get("") : variants.entrySet().iterator().next().getValue();
        } else if (bs.has("multipart")) {
            variant = bs.getAsJsonArray("multipart").get(0).getAsJsonObject().get("apply");
        }
        if (variant == null) return null;
        // A variant may be a weighted list of models: take the first.
        if (variant.isJsonArray()) variant = variant.getAsJsonArray().get(0);
        return variant.getAsJsonObject().get("model").getAsString();
    }

    private JsonObject model(String id) {
        return modelCache.computeIfAbsent(id, k -> json(ResourceManager.path(k, "models", ".json")));
    }

    private void resolveModel(String modelId, String[] tex, boolean[] tint, String[] overlay) {
        Map<String, String> textures = new LinkedHashMap<>();
        JsonArray elements = null;
        String id = modelId;
        for (int depth = 0; id != null && depth < 32; depth++) {
            JsonObject m = model(id);
            if (m == null) { warnings.add("missing model " + id); break; }
            if (m.has("textures")) {
                for (var e : m.getAsJsonObject("textures").entrySet()) {
                    textures.putIfAbsent(e.getKey(), e.getValue().getAsString()); // child wins
                }
            }
            if (elements == null && m.has("elements")) elements = m.getAsJsonArray("elements");
            id = m.has("parent") ? ResourceManager.normalize(m.get("parent").getAsString()) : null;
        }
        if (elements == null || elements.isEmpty()) { warnings.add("no elements in " + modelId); return; }

        JsonObject base = pickFullCube(elements);
        JsonObject faces = base.getAsJsonObject("faces");
        for (int f = 0; f < 6; f++) {
            JsonObject face = faces.getAsJsonObject(FACE_NAMES[f]);
            if (face == null) continue;
            tex[f] = deref(face.get("texture").getAsString(), textures);
            tint[f] = face.has("tintindex") && face.get("tintindex").getAsInt() >= 0;
        }
        // A second full-cube element with tinted faces is an overlay (vanilla grass block sides).
        for (JsonElement e : elements) {
            JsonObject o = e.getAsJsonObject();
            if (o == base || !isVec(o.getAsJsonArray("from"), 0) || !isVec(o.getAsJsonArray("to"), 16)) continue;
            JsonObject ofaces = o.getAsJsonObject("faces");
            for (int f = 0; f < 6; f++) {
                JsonObject face = ofaces.getAsJsonObject(FACE_NAMES[f]);
                if (face == null || !face.has("tintindex") || tex[f] == null || tint[f]) continue;
                overlay[f] = deref(face.get("texture").getAsString(), textures);
            }
            break;
        }
    }

    /** Prefer the element covering the whole block; overlays (e.g. grass side tint) come after it. */
    private static JsonObject pickFullCube(JsonArray elements) {
        for (JsonElement e : elements) {
            JsonObject o = e.getAsJsonObject();
            if (isVec(o.getAsJsonArray("from"), 0) && isVec(o.getAsJsonArray("to"), 16)) return o;
        }
        return elements.get(0).getAsJsonObject();
    }

    private static boolean isVec(JsonArray a, float v) {
        if (a == null) return false;
        for (JsonElement e : a) if (e.getAsFloat() != v) return false;
        return true;
    }

    private static String deref(String ref, Map<String, String> textures) {
        for (int i = 0; i < 16 && ref != null && ref.startsWith("#"); i++) ref = textures.get(ref.substring(1));
        return ref == null || ref.startsWith("#") ? null : ResourceManager.normalize(ref);
    }
}
