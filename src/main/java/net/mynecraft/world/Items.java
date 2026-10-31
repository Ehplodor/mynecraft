package net.mynecraft.world;

/**
 * Item registry: what can be held, stacked and crafted, separate from block states (what sits in the
 * world). Every block's default state is also an item (a "block item") and keeps the same id, so
 * {@code id < Blocks.COUNT} means "places that block"; pure items (tools, food, materials, art)
 * come after. Ids are runtime only: inventories and recipes are saved and read by name. A pure item
 * may share its name with a block that is never held (the "wheat" crop gives "wheat"): the item wins.
 *
 * <p>Tools have a kind, a tier (1 wood, 2 stone or flint, 3 iron), a durability (uses before they
 * break) and a repair material; food has a nourishment (hunger points), a thirst relief and a food
 * group (variety makes meals more nourishing, see actor.Needs).
 */
public final class Items {
    public static final byte CATEGORY_BLOCK = 0, CATEGORY_MATERIAL = 1, CATEGORY_FOOD = 2, CATEGORY_TOOL = 3,
        CATEGORY_ART = 4;
    public static final byte TOOL_NONE = 0, TOOL_PICKAXE = Blocks.TOOL_PICKAXE, TOOL_AXE = Blocks.TOOL_AXE,
        TOOL_SHOVEL = Blocks.TOOL_SHOVEL, TOOL_HOE = 4, TOOL_KNIFE = 5, TOOL_ROD = 6, TOOL_BOW = 7, TOOL_HAMMER = 8,
        TOOL_CHISEL = 9, TOOL_FLUTE = 10;
    public static final String[] TOOL_NAMES = {"", "pickaxe", "axe", "shovel", "hoe", "knife", "rod", "bow", "hammer",
        "chisel", "flute"};
    public static final byte GROUP_NONE = 0, GROUP_FRUIT = 1, GROUP_VEGETABLE = 2, GROUP_GRAIN = 3, GROUP_MEAT = 4,
        GROUP_FISH = 5;
    public static final String[] GROUP_NAMES = {"", "fruits", "légumes et herbes", "céréales", "viande", "poisson"};

    private static final Registry REGISTRY = new Registry("item");
    public static final short STICK, FLINT, COAL, APPLE,
        WOODEN_PICKAXE, STONE_PICKAXE, IRON_PICKAXE, WOODEN_AXE, STONE_AXE, IRON_AXE, WOODEN_SHOVEL, STONE_SHOVEL,
        IRON_SHOVEL, WOODEN_HOE, STONE_HOE, IRON_HOE, FLINT_KNIFE, IRON_KNIFE, FISHING_ROD, BOW, ARROW, HAMMER, CHISEL,
        FLUTE,
        SWEET_BERRIES, WILD_HERBS, WHEAT, WHEAT_SEEDS, BREAD, FLOUR, VENISON, COOKED_VENISON, RABBIT, COOKED_RABBIT,
        TROUT, COOKED_TROUT, SMOKED_MEAT, ROASTED_MUSHROOM, HERB_TROUT, BERRY_TART,
        CHARCOAL, IRON_INGOT, HIDE, LEATHER, PLANT_FIBER, STRING, CLOTH, CLAY_BALL, ASH, COMPOST, SLAG,
        RED_DYE, BLUE_DYE, WHITE_DYE, YELLOW_DYE, CANVAS;
    public static final int COUNT;
    /** Largest stack per item (1 for tools, 64 for most). */
    public static final byte[] MAX_STACK;
    public static final byte[] CATEGORY;
    public static final byte[] TOOL, TIER, FOOD_GROUP;
    public static final short[] DURABILITY, REPAIR;
    /** Hunger points restored (0..20 scale), thirst relieved. */
    public static final byte[] NOURISHMENT, THIRST;
    /** Heat a fuel gives (one unit cooks one simple dish). */
    public static final float[] FUEL;

    static {
        // Block items first, at their block ids (non-default states register nothing new).
        for (int i = 0; i < Blocks.COUNT; i++) REGISTRY.register(i == 0 ? "air" : "#" + i);
        STICK = pure("stick");
        FLINT = pure("flint");
        COAL = pure("coal");
        APPLE = pure("apple");
        WOODEN_PICKAXE = pure("wooden_pickaxe");
        STONE_PICKAXE = pure("stone_pickaxe");
        IRON_PICKAXE = pure("iron_pickaxe");
        WOODEN_AXE = pure("wooden_axe");
        STONE_AXE = pure("stone_axe");
        IRON_AXE = pure("iron_axe");
        WOODEN_SHOVEL = pure("wooden_shovel");
        STONE_SHOVEL = pure("stone_shovel");
        IRON_SHOVEL = pure("iron_shovel");
        WOODEN_HOE = pure("wooden_hoe");
        STONE_HOE = pure("stone_hoe");
        IRON_HOE = pure("iron_hoe");
        FLINT_KNIFE = pure("flint_knife");
        IRON_KNIFE = pure("iron_knife");
        FISHING_ROD = pure("fishing_rod");
        BOW = pure("bow");
        ARROW = pure("arrow");
        HAMMER = pure("hammer");
        CHISEL = pure("chisel");
        FLUTE = pure("flute");
        SWEET_BERRIES = pure("sweet_berries");
        WILD_HERBS = pure("wild_herbs");
        WHEAT = pure("wheat");
        WHEAT_SEEDS = pure("wheat_seeds");
        BREAD = pure("bread");
        FLOUR = pure("flour");
        VENISON = pure("venison");
        COOKED_VENISON = pure("cooked_venison");
        RABBIT = pure("rabbit");
        COOKED_RABBIT = pure("cooked_rabbit");
        TROUT = pure("trout");
        COOKED_TROUT = pure("cooked_trout");
        SMOKED_MEAT = pure("smoked_meat");
        ROASTED_MUSHROOM = pure("roasted_mushroom");
        HERB_TROUT = pure("herb_trout");
        BERRY_TART = pure("berry_tart");
        CHARCOAL = pure("charcoal");
        IRON_INGOT = pure("iron_ingot");
        HIDE = pure("hide");
        LEATHER = pure("leather");
        PLANT_FIBER = pure("plant_fiber");
        STRING = pure("string");
        CLOTH = pure("cloth");
        CLAY_BALL = pure("clay_ball");
        ASH = pure("ash");
        COMPOST = pure("compost");
        SLAG = pure("slag");
        RED_DYE = pure("red_dye");
        BLUE_DYE = pure("blue_dye");
        WHITE_DYE = pure("white_dye");
        YELLOW_DYE = pure("yellow_dye");
        CANVAS = pure("canvas");
        REGISTRY.freeze();
        COUNT = REGISTRY.size();
        MAX_STACK = new byte[COUNT];
        CATEGORY = new byte[COUNT];
        TOOL = new byte[COUNT];
        TIER = new byte[COUNT];
        FOOD_GROUP = new byte[COUNT];
        DURABILITY = new short[COUNT];
        REPAIR = new short[COUNT];
        NOURISHMENT = new byte[COUNT];
        THIRST = new byte[COUNT];
        FUEL = new float[COUNT];
        java.util.Arrays.fill(MAX_STACK, (byte) 64);
        for (int i = Blocks.COUNT; i < COUNT; i++) CATEGORY[i] = CATEGORY_MATERIAL;
        short[][] tools = {{WOODEN_PICKAXE, STONE_PICKAXE, IRON_PICKAXE}, {WOODEN_AXE, STONE_AXE, IRON_AXE},
            {WOODEN_SHOVEL, STONE_SHOVEL, IRON_SHOVEL}, {WOODEN_HOE, STONE_HOE, IRON_HOE}};
        short[] uses = {60, 130, 250}, repair = {Blocks.OAK_PLANKS, Blocks.COBBLESTONE, IRON_INGOT};
        for (int k = 0; k < tools.length; k++)
            for (int t = 0; t < 3; t++) tool(tools[k][t], (byte) (k + 1), t + 1, uses[t], repair[t]);
        tool(FLINT_KNIFE, TOOL_KNIFE, 2, 100, FLINT);
        tool(IRON_KNIFE, TOOL_KNIFE, 3, 250, IRON_INGOT);
        tool(FISHING_ROD, TOOL_ROD, 1, 64, STRING);
        tool(BOW, TOOL_BOW, 1, 200, STRING);
        tool(HAMMER, TOOL_HAMMER, 3, 250, IRON_INGOT);
        tool(CHISEL, TOOL_CHISEL, 3, 150, IRON_INGOT);
        tool(FLUTE, TOOL_FLUTE, 1, 0, Blocks.AIR);
        CATEGORY[FLUTE] = CATEGORY_ART;
        food(APPLE, GROUP_FRUIT, 4, 1);
        food(SWEET_BERRIES, GROUP_FRUIT, 2, 1);
        food(Blocks.BROWN_MUSHROOM, GROUP_VEGETABLE, 1, 0);
        food(WILD_HERBS, GROUP_VEGETABLE, 1, 0);
        food(BREAD, GROUP_GRAIN, 5, 0);
        food(VENISON, GROUP_MEAT, 3, 0);
        food(COOKED_VENISON, GROUP_MEAT, 8, 0);
        food(RABBIT, GROUP_MEAT, 2, 0);
        food(COOKED_RABBIT, GROUP_MEAT, 5, 0);
        food(TROUT, GROUP_FISH, 2, 0);
        food(COOKED_TROUT, GROUP_FISH, 5, 0);
        food(SMOKED_MEAT, GROUP_MEAT, 6, 0);
        food(ROASTED_MUSHROOM, GROUP_VEGETABLE, 3, 0);
        food(HERB_TROUT, GROUP_FISH, 7, 0);
        food(BERRY_TART, GROUP_GRAIN, 7, 1);
        CATEGORY[Blocks.BROWN_MUSHROOM] = CATEGORY_FOOD;
        FUEL[COAL] = FUEL[CHARCOAL] = 8;
        for (short log : new short[] {Blocks.OAK_LOG, Blocks.BIRCH_LOG, Blocks.SPRUCE_LOG}) FUEL[log] = 4;
        for (short p : new short[] {Blocks.OAK_PLANKS, Blocks.BIRCH_PLANKS, Blocks.SPRUCE_PLANKS}) FUEL[p] = 1;
        FUEL[STICK] = 0.5f;
        FUEL[PLANT_FIBER] = 0.25f;
        for (short art : new short[] {Blocks.PAINTING, Blocks.STATUE, Blocks.DECORATED_POT, CANVAS}) CATEGORY[art] = CATEGORY_ART;
    }

    private static short pure(String name) {
        return (short) REGISTRY.register(name);
    }

    private static void tool(short id, byte kind, int tier, int uses, short repair) {
        CATEGORY[id] = CATEGORY_TOOL;
        MAX_STACK[id] = 1;
        TOOL[id] = kind;
        TIER[id] = (byte) tier;
        DURABILITY[id] = (short) uses;
        REPAIR[id] = repair;
    }

    private static void food(short id, byte group, int nourishment, int thirst) {
        CATEGORY[id] = CATEGORY_FOOD;
        FOOD_GROUP[id] = group;
        NOURISHMENT[id] = (byte) nourishment;
        THIRST[id] = (byte) thirst;
    }

    private Items() {}

    /** True if the item places a block (its id is that block's state). */
    public static boolean isBlock(short id) {
        return id > 0 && id < Blocks.COUNT;
    }

    public static boolean isFood(short id) {
        return id > 0 && NOURISHMENT[id] > 0;
    }

    /** Resource name without namespace ("stone", "stick"). */
    public static String name(short id) {
        return isBlock(id) || id == 0 ? Blocks.NAMES[id] : REGISTRY.name(id);
    }

    /** Item for a resource name (namespace optional), or -1 if unknown. */
    public static short fromName(String id) {
        String n = id.startsWith("minecraft:") ? id.substring(10) : id.startsWith("mynecraft:") ? id.substring(10) : id;
        if (n.indexOf(':') >= 0) return -1;
        int pure = REGISTRY.id(n);
        if (pure >= Blocks.COUNT) return (short) pure;
        return Blocks.fromState(n, java.util.Map.of());
    }

    /** Speed of a tool on a block: its tier's speed with the right kind (2, 4, 6), else 1. */
    public static double speed(short tool, short block) {
        byte kind = TOOL[tool & 0xFFFF];
        return kind != TOOL_NONE && kind == Blocks.TOOL[block & 0xFFFF] ? 2 * TIER[tool & 0xFFFF] : 1;
    }

    /** Whether breaking the block with this tool (AIR: the bare hand) yields its drop. */
    public static boolean canHarvest(short tool, short block) {
        int need = Blocks.TIER[block & 0xFFFF];
        if (need == 0) return true;
        return TOOL[tool & 0xFFFF] == Blocks.TOOL[block & 0xFFFF] && TIER[tool & 0xFFFF] >= need;
    }
}
