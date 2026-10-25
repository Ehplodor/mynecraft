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
        if (Blocks.isFluid((byte) id)) {
            // Fluids have no block model in Java packs: the game draws them from *_still textures.
            boolean water = Blocks.FLUID[id] == Blocks.FLUID_WATER;
            java.util.Arrays.fill(tex, water ? "minecraft:block/water_still" : "minecraft:block/lava_still");
            java.util.Arrays.fill(tint, water);
        } else {
            JsonObject variant = variantFromBlockstate(name, Blocks.STATE[id]);
            if (variant == null) {
                warnings.add("no blockstate for " + name);
            } else {
                String[] mTex = new String[6], mOverlay = new String[6];
                boolean[] mTint = new boolean[6];
                resolveModel(ResourceManager.normalize(variant.get("model").getAsString()), mTex, mTint, mOverlay);
                int rx = variant.has("x") ? variant.get("x").getAsInt() : 0;
                int ry = variant.has("y") ? variant.get("y").getAsInt() : 0;
                rotate(rx, ry, mTex, mTint, mOverlay, tex, tint, overlay);
            }
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

    /** The variant whose property key matches the state (subset match, "" matches all), else the first. */
    private JsonObject variantFromBlockstate(String block, String state) {
        JsonObject bs = json(ResourceManager.path(block, "blockstates", ".json"));
        if (bs == null) return null;
        java.util.Map<String, String> props = new java.util.HashMap<>();
        if (!state.isEmpty()) for (String kv : state.split(",")) {
            int eq = kv.indexOf('=');
            props.put(kv.substring(0, eq), kv.substring(eq + 1));
        }
        JsonElement variant = null;
        if (bs.has("variants")) {
            for (var e : bs.getAsJsonObject("variants").entrySet()) {
                if (variant == null) variant = e.getValue();
                if (Blocks.matches(e.getKey(), props)) { variant = e.getValue(); break; }
            }
        } else if (bs.has("multipart")) {
            variant = bs.getAsJsonArray("multipart").get(0).getAsJsonObject().get("apply");
        }
        if (variant == null) return null;
        // A variant may be a weighted list of models: take the first.
        if (variant.isJsonArray()) variant = variant.getAsJsonArray().get(0);
        return variant.getAsJsonObject();
    }

    // ---- variant rotation ----

    /** Outward normals of our faces: -x, +x, -y, +y, -z, +z. */
    private static final int[][] NORMAL = {{-1, 0, 0}, {1, 0, 0}, {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}};
    /** Directions of increasing u and v on each face, matching the chunk vertex shader. */
    private static final int[][] U = {{0, 0, 1}, {0, 0, -1}, {1, 0, 0}, {1, 0, 0}, {-1, 0, 0}, {1, 0, 0}};
    private static final int[][] V = {{0, -1, 0}, {0, -1, 0}, {0, 0, -1}, {0, 0, 1}, {0, -1, 0}, {0, -1, 0}};

    /** Vanilla variant rotation: x first (up turns towards north), then y (north turns towards east). */
    private static int[] rotate(int[] p, int rx, int ry) {
        int x = p[0], y = p[1], z = p[2];
        for (int i = 0; i < ((rx / 90) & 3); i++) { int t = y; y = z; z = -t; }
        for (int i = 0; i < ((ry / 90) & 3); i++) { int t = x; x = -z; z = t; }
        return new int[] {x, y, z};
    }

    private static int dot(int[] a, int[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static int faceOf(int[] n) {
        for (int f = 0; f < 6; f++) if (dot(NORMAL[f], n) == 1) return f;
        throw new IllegalStateException();
    }

    /**
     * Moves model faces to the world faces they end up on, and records how each texture must be
     * turned so it keeps its orientation on the rotated block (as a "#a,b,c,d" suffix).
     */
    private static void rotate(int rx, int ry, String[] mTex, boolean[] mTint, String[] mOverlay,
                               String[] tex, boolean[] tint, String[] overlay) {
        for (int m = 0; m < 6; m++) {
            int f = faceOf(rotate(NORMAL[m], rx, ry));
            int[] ru = rotate(U[m], rx, ry), rv = rotate(V[m], rx, ry);
            // Texture coordinates (s, t) sampled at world-face coordinates (u, v): s = a*u + b*v, t = c*u + d*v.
            int a = dot(U[f], ru), b = dot(V[f], ru), c = dot(U[f], rv), d = dot(V[f], rv);
            String suffix = a == 1 && d == 1 ? "" : "#" + a + "," + b + "," + c + "," + d;
            tex[f] = mTex[m] == null ? null : mTex[m] + suffix;
            overlay[f] = mOverlay[m] == null ? null : mOverlay[m] + suffix;
            tint[f] = mTint[m];
        }
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
