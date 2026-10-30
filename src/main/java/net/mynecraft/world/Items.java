package net.mynecraft.world;

/**
 * Item registry: what can be held, stacked and crafted, separate from block states (what sits in the
 * world). Every block's default state is also an item (a "block item") and keeps the same id, so
 * {@code id < Blocks.COUNT} means "places that block"; pure items (tools, food, materials, art)
 * come after. Ids are runtime only: inventories and recipes are saved and read by name.
 */
public final class Items {
    public static final byte CATEGORY_BLOCK = 0, CATEGORY_MATERIAL = 1, CATEGORY_FOOD = 2, CATEGORY_TOOL = 3,
        CATEGORY_ART = 4;

    private static final Registry REGISTRY = new Registry("item");
    public static final short STICK, FLINT, COAL, APPLE;
    public static final int COUNT;
    /** Largest stack per item (1 for tools, 64 for most). */
    public static final byte[] MAX_STACK;
    public static final byte[] CATEGORY;

    static {
        // Block items first, at their block ids (non-default states register nothing new).
        for (int i = 0; i < Blocks.COUNT; i++) REGISTRY.register(i == 0 ? "air" : "#" + i);
        STICK = pure("stick");
        FLINT = pure("flint");
        COAL = pure("coal");
        APPLE = pure("apple");
        REGISTRY.freeze();
        COUNT = REGISTRY.size();
        MAX_STACK = new byte[COUNT];
        CATEGORY = new byte[COUNT];
        java.util.Arrays.fill(MAX_STACK, (byte) 64);
        CATEGORY[STICK] = CATEGORY[FLINT] = CATEGORY[COAL] = CATEGORY_MATERIAL;
        CATEGORY[APPLE] = CATEGORY_FOOD;
    }

    private static short pure(String name) {
        return (short) REGISTRY.register(name);
    }

    private Items() {}

    /** True if the item places a block (its id is that block's state). */
    public static boolean isBlock(short id) {
        return id > 0 && id < Blocks.COUNT;
    }

    /** Resource name without namespace ("stone", "stick"). */
    public static String name(short id) {
        return isBlock(id) || id == 0 ? Blocks.NAMES[id] : REGISTRY.name(id);
    }

    /** Item for a resource name (namespace optional), or -1 if unknown. */
    public static short fromName(String id) {
        String n = id.startsWith("minecraft:") ? id.substring(10) : id;
        if (n.indexOf(':') >= 0) return -1;
        short block = Blocks.fromState(n, java.util.Map.of());
        if (block >= 0) return block;
        int pure = REGISTRY.id(n);
        return pure >= Blocks.COUNT ? (short) pure : -1;
    }
}
