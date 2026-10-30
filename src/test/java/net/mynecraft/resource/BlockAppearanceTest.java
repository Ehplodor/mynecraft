package net.mynecraft.resource;

import net.mynecraft.world.Blocks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class BlockAppearanceTest {
    private static final int WEST = 0, DOWN = 2, UP = 3;

    private static PackSource memory(Map<String, String> files) {
        return new PackSource() {
            public byte[] read(String p) {
                String s = files.get(p);
                return s == null ? null : s.getBytes(StandardCharsets.UTF_8);
            }
            public String name() { return "memory"; }
        };
    }

    private static String tex(BlockAppearance a, short block, int face) {
        return a.textures.get(a.faceInfo[block * 6 + face] & BlockAppearance.LAYER_MASK);
    }

    private static boolean tinted(BlockAppearance a, short block, int face) {
        return (a.faceInfo[block * 6 + face] >> BlockAppearance.TINT_SHIFT & 3) != 0;
    }

    @Test
    void defaultPackResolvesEveryBlock() {
        BlockAppearance a = new BlockAppearance(new ResourceManager().add(PackSource.classpath("defaultpack")));
        assertEquals(List.of(), a.warnings);
        assertEquals("minecraft:block/stone", tex(a, Blocks.STONE, UP));
        assertEquals("minecraft:block/grass_block_top", tex(a, Blocks.GRASS, UP));
        assertEquals("minecraft:block/grass_block_side", tex(a, Blocks.GRASS, WEST));
        assertEquals("minecraft:block/dirt", tex(a, Blocks.GRASS, DOWN));
        assertTrue(tinted(a, Blocks.GRASS, UP));
        // Grass sides: untinted base + tinted overlay stored in the next layer.
        int side = a.faceInfo[Blocks.GRASS * 6 + WEST];
        assertNotEquals(0, side & BlockAppearance.OVERLAY_BIT);
        assertEquals("minecraft:block/grass_block_side_overlay", a.textures.get((side & BlockAppearance.LAYER_MASK) + 1));
        assertEquals(0, a.faceInfo[Blocks.GRASS * 6 + UP] & BlockAppearance.OVERLAY_BIT);
        assertEquals("minecraft:block/oak_log_top", tex(a, Blocks.OAK_LOG, UP));
        assertEquals("minecraft:block/oak_log", tex(a, Blocks.OAK_LOG, WEST));
        assertEquals("minecraft:block/short_grass", tex(a, Blocks.SHORT_GRASS, 4));
        assertTrue(tinted(a, Blocks.SHORT_GRASS, 4));
        assertFalse(tinted(a, Blocks.POPPY, 4));
        assertEquals(Blocks.TINT_FOLIAGE, a.faceInfo[Blocks.OAK_LEAVES * 6 + UP] >> BlockAppearance.TINT_SHIFT & 3);
        assertEquals("minecraft:block/water_still", tex(a, Blocks.WATER, UP));

        // Rotated logs: the end texture moves to the axis faces, side textures get turned.
        assertEquals("minecraft:block/oak_log_top", tex(a, Blocks.OAK_LOG_X, WEST).split("#")[0]);
        assertEquals("minecraft:block/oak_log_top", tex(a, Blocks.OAK_LOG_Z, 4).split("#")[0]);
        assertTrue(tex(a, Blocks.OAK_LOG_X, UP).startsWith("minecraft:block/oak_log#"), tex(a, Blocks.OAK_LOG_X, UP));
        assertTrue(tex(a, Blocks.OAK_LOG_Z, WEST).startsWith("minecraft:block/oak_log#"));
        // Slabs and the double slab.
        assertEquals("minecraft:block/oak_planks", tex(a, Blocks.OAK_SLAB, UP));
        assertEquals("minecraft:block/oak_planks", tex(a, Blocks.OAK_SLAB_TOP, DOWN));
        assertEquals("minecraft:block/oak_planks", tex(a, Blocks.OAK_SLAB_DOUBLE, WEST));
        for (int i = 0; i < a.faceInfo.length; i++) {
            if (i >= 6) assertNotEquals(0, a.faceInfo[i] & BlockAppearance.LAYER_MASK, "missing texture at " + i);
        }
    }

    @Test
    void laterPackOverridesModelAndTextures() {
        Map<String, String> files = new HashMap<>();
        // A pack turning stone into a column block via the standard cube_column-like parent.
        files.put("assets/minecraft/models/block/stone.json",
            "{\"parent\":\"minecraft:block/cube_bottom_top\",\"textures\":{\"top\":\"block/my_top\",\"bottom\":\"block/my_top\",\"side\":\"custom:block/my_side\"}}");
        BlockAppearance a = new BlockAppearance(new ResourceManager()
            .add(PackSource.classpath("defaultpack")).add(memory(files)));
        assertEquals("minecraft:block/my_top", tex(a, Blocks.STONE, UP));
        assertEquals("custom:block/my_side", tex(a, Blocks.STONE, WEST));
        assertEquals("minecraft:block/dirt", tex(a, Blocks.DIRT, UP));
    }

    @Test
    void weightedVariantsAndMissingFilesFallBack() {
        Map<String, String> files = new HashMap<>();
        files.put("assets/minecraft/blockstates/stone.json",
            "{\"variants\":{\"\":[{\"model\":\"block/stone\"},{\"model\":\"block/stone_mirrored\"}]}}");
        files.put("assets/minecraft/blockstates/dirt.json", "{\"variants\":{\"\":{\"model\":\"block/nope\"}}}");
        BlockAppearance a = new BlockAppearance(new ResourceManager()
            .add(PackSource.classpath("defaultpack")).add(memory(files)));
        assertEquals("minecraft:block/stone", tex(a, Blocks.STONE, UP));
        assertEquals(BlockAppearance.MISSING, tex(a, Blocks.DIRT, UP));
        assertTrue(a.warnings.stream().anyMatch(w -> w.contains("block/nope")));
    }

    @Test
    void zipWithWrappingFolderIsReadable(@TempDir Path dir) throws IOException {
        Path zip = dir.resolve("pack.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("MyPack/pack.mcmeta"));
            out.write("{}".getBytes());
            out.putNextEntry(new ZipEntry("MyPack/assets/minecraft/textures/block/stone.png"));
            out.write(new byte[] {1, 2, 3});
        }
        try (PackSource p = PackSource.open(zip)) {
            assertArrayEquals(new byte[] {1, 2, 3}, p.read("assets/minecraft/textures/block/stone.png"));
            assertNull(p.read("assets/minecraft/textures/block/dirt.png"));
        }
    }
}
