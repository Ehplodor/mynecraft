package net.mynecraft.world.life;

import net.mynecraft.world.Landscape;

import java.util.ArrayList;
import java.util.List;

/**
 * The creatures of the world: six real animals of temperate and mountain Europe and four
 * original fantastic ones, each with a habitat, a diet, a daily rhythm, a temperament and an
 * animated box model. Models face +z with the feet at the origin, in blocks.
 */
public enum Species {
    // ---------------------------------------------------------------- real animals
    DEER("Cerf", Kind.GROUND, Diet.GRASS, Rhythm.TWILIGHT, Temper.SHY,
        0.4, 1.7, 1.6, 5.5, 18, 2, 5, 10, 300,
        Habitat.of(0.25f, 0.75f, Landscape.FOREST, Landscape.BIRCH_FOREST, Landscape.PLAINS, Landscape.TAIGA)),
    RABBIT("Lapin", Kind.GROUND, Diet.GRASS, Rhythm.TWILIGHT, Temper.SHY,
        0.2, 0.45, 1.2, 6, 10, 1, 3, 3, 150,
        Habitat.of(0.0f, 0.8f, Landscape.PLAINS, Landscape.DRY_PLAINS, Landscape.BIRCH_FOREST, Landscape.SNOWY_PLAINS,
            Landscape.SNOWY_TAIGA, Landscape.FOREST)),
    FOX("Renard", Kind.GROUND, Diet.MEAT, Rhythm.NIGHT, Temper.WARY,
        0.25, 0.65, 1.5, 6.5, 9, 1, 1, 6, 240,
        Habitat.of(0.0f, 0.75f, Landscape.FOREST, Landscape.TAIGA, Landscape.SNOWY_TAIGA, Landscape.BIRCH_FOREST,
            Landscape.SNOWY_PLAINS)),
    TROUT("Truite", Kind.SWIM, Diet.NONE, Rhythm.ALWAYS, Temper.SHY,
        0.15, 0.25, 1.4, 4, 4, 2, 5, 2, 120,
        Habitat.of(0.0f, 0.8f, Landscape.RIVER, Landscape.LAKE)),
    HERON("Héron cendré", Kind.WADER, Diet.FISH, Rhythm.DAY, Temper.SHY,
        0.25, 1.2, 1.0, 7, 14, 1, 1, 5, 300,
        Habitat.of(0.2f, 0.8f, Landscape.RIVER, Landscape.LAKE, Landscape.BEACH)),
    IBEX("Bouquetin", Kind.GROUND, Diet.GRASS, Rhythm.DAY, Temper.WARY,
        0.4, 1.4, 1.4, 5, 10, 2, 4, 10, 300,
        Habitat.of(0.0f, 0.6f, Landscape.STONY_PEAKS, Landscape.PLATEAU, Landscape.SNOWY_PEAKS)),
    // ---------------------------------------------------------------- fantastic, original
    /** Lanterne des brumes: a glowing bell drifting over still water on calm nights. */
    LANTERN("Lanterne des brumes", Kind.FLOAT, Diet.NONE, Rhythm.NIGHT, Temper.CALM,
        0.35, 0.9, 0.7, 1.2, 0, 2, 6, 4, 0,
        Habitat.of(0.15f, 1.0f, Landscape.LAKE, Landscape.RIVER, Landscape.DARK_FOREST, Landscape.FOREST)),
    /** Moussard: a slow giant tortoise of the deep woods, a small tree growing on its shell. */
    MOSSBACK("Moussard", Kind.GROUND, Diet.GRASS, Rhythm.DAY, Temper.CURIOUS,
        0.7, 1.2, 0.6, 0.9, 0, 1, 1, 30, 600,
        Habitat.of(0.3f, 1.0f, Landscape.DARK_FOREST, Landscape.FOREST)),
    /** Givrelin: a small hopping creature of living ice, frost crystals along its back. */
    FROSTLING("Givrelin", Kind.GROUND, Diet.NONE, Rhythm.ALWAYS, Temper.PLAYFUL,
        0.22, 0.6, 2.2, 7, 0, 1, 3, 4, 200,
        Habitat.of(0.0f, 0.3f, Landscape.SNOWY_PLAINS, Landscape.SNOWY_TAIGA, Landscape.SNOWY_PEAKS, Landscape.GLACIER,
            Landscape.FROZEN_RIVER)),
    /** Voilier des cimes: a huge manta of the sky circling slowly above the peaks. */
    SKY_MANTA("Voilier des cimes", Kind.FLY, Diet.NONE, Rhythm.DAY, Temper.CALM,
        1.2, 0.5, 6, 9, 0, 1, 1, 20, 0,
        Habitat.of(0.0f, 0.8f, Landscape.STONY_PEAKS, Landscape.SNOWY_PEAKS, Landscape.PLATEAU, Landscape.GLACIER));

    public enum Kind { GROUND, SWIM, WADER, FLY, FLOAT }
    public enum Diet { GRASS, MEAT, FISH, NONE }
    /** When the species is out and about (the rest of the day it sleeps or stays away). */
    public enum Rhythm { DAY, NIGHT, TWILIGHT, ALWAYS }
    public enum Temper { SHY, WARY, CALM, CURIOUS, PLAYFUL }

    /** Climate range (temperature 0..1) and biomes where the species lives. */
    public record Habitat(float minTemp, float maxTemp, byte[] biomes) {
        static Habitat of(float min, float max, byte... biomes) {
            return new Habitat(min, max, biomes);
        }

        public boolean has(byte biome) {
            for (byte b : biomes) if (b == biome) return true;
            return false;
        }
    }

    public final String displayName;
    public final Kind kind;
    public final Diet diet;
    public final Rhythm rhythm;
    public final Temper temper;
    public final double halfWidth, height, walkSpeed, runSpeed;
    /** Distance at which the player is noticed and fled (0: never flees). */
    public final double fleeRange;
    public final int groupMin, groupMax;
    public final float health;
    /** Seconds from birth to adulthood (0: never breeds). */
    public final double adultAge;
    public final Habitat habitat;
    /** Box model; parts with {@link Part#coat} take the coat colour of the individual. */
    public final List<Part> parts;

    Species(String name, Kind kind, Diet diet, Rhythm rhythm, Temper temper, double halfWidth, double height,
            double walkSpeed, double runSpeed, double fleeRange, int groupMin, int groupMax, float health, double adultAge,
            Habitat habitat) {
        this.displayName = name;
        this.kind = kind;
        this.diet = diet;
        this.rhythm = rhythm;
        this.temper = temper;
        this.halfWidth = halfWidth;
        this.height = height;
        this.walkSpeed = walkSpeed;
        this.runSpeed = runSpeed;
        this.fleeRange = fleeRange;
        this.groupMin = groupMin;
        this.groupMax = groupMax;
        this.health = health;
        this.adultAge = adultAge;
        this.habitat = habitat;
        this.parts = List.copyOf(model(this));
    }

    /** Who this species hunts. */
    public boolean hunts(Species prey) {
        return switch (this) {
            case FOX -> prey == RABBIT;
            case HERON -> prey == TROUT;
            default -> false;
        };
    }

    /**
     * Coat colour of an individual: some animals turn white where it snows (mountain hare,
     * arctic fox), the rest vary slightly around the species' colour.
     */
    public int coat(float temperature, float snowCover, double variation) {
        int base = switch (this) {
            case DEER -> 0x8A5A30;
            case RABBIT -> 0x8C7458;
            case FOX -> 0xC9601E;
            case IBEX -> 0x7E6E5A;
            case TROUT -> 0x6F7F58;
            case HERON -> 0x9AA1A8;
            case MOSSBACK -> 0x4E6B32;
            case FROSTLING -> 0xBFE6FF;
            case LANTERN -> 0x7FF0E0;
            case SKY_MANTA -> 0x3D4F7A;
        };
        if ((this == RABBIT || this == FOX) && snowCover > 0.5f) base = 0xEDEFF2;
        float k = (float) (1 + (variation - 0.5) * 0.18);
        int r = Math.min(255, (int) (((base >> 16) & 255) * k)), g = Math.min(255, (int) (((base >> 8) & 255) * k)),
            b = Math.min(255, (int) ((base & 255) * k));
        return r << 16 | g << 8 | b;
    }

    // ------------------------------------------------------------------------------- models

    /** How a part moves. */
    public enum Anim { NONE, BODY, LEG, HEAD, TAIL, WING, FIN, TENTACLE, EAR, BOB }

    /**
     * A box of the model turning around its pivot (px, py, pz): the box spans from the pivot plus
     * (ox, oy, oz), size (w, h, d). {@code phase} offsets legs and tentacles; {@code glow} 0..1 is
     * self-lit; {@code coat} parts take the individual's coat colour (times {@code color} as a shade).
     */
    public record Part(Anim anim, float phase, float px, float py, float pz, float ox, float oy, float oz,
                       float w, float h, float d, int color, float glow, boolean coat) {}

    private static final class B {
        final List<Part> out = new ArrayList<>();

        B box(Anim a, float phase, float px, float py, float pz, float ox, float oy, float oz, float w, float h, float d, int color) {
            out.add(new Part(a, phase, px, py, pz, ox, oy, oz, w, h, d, color, 0, false));
            return this;
        }

        /** A coat-coloured box, {@code shade} 0xRRGGBB multiplying the coat (0xFFFFFF: as is). */
        B coat(Anim a, float phase, float px, float py, float pz, float ox, float oy, float oz, float w, float h, float d, int shade) {
            out.add(new Part(a, phase, px, py, pz, ox, oy, oz, w, h, d, shade, 0, true));
            return this;
        }

        B glow(Anim a, float phase, float px, float py, float pz, float ox, float oy, float oz, float w, float h, float d, int color, float glow) {
            out.add(new Part(a, phase, px, py, pz, ox, oy, oz, w, h, d, color, glow, false));
            return this;
        }

        /** Four legs in a diagonal gait (front-left with back-right). */
        B legs(float x, float front, float back, float len, float thick, int shade, boolean coat) {
            float t = thick / 2;
            float[][] at = {{-x, front, 0}, {x, back, 0}, {x, front, (float) Math.PI}, {-x, back, (float) Math.PI}};
            for (float[] l : at) {
                if (coat) coat(Anim.LEG, l[2], l[0], len, l[1], -t, -len, -t, thick, len, thick, shade);
                else box(Anim.LEG, l[2], l[0], len, l[1], -t, -len, -t, thick, len, thick, shade);
            }
            return this;
        }
    }

    private static List<Part> model(Species s) {
        B b = new B();
        final float PI = (float) Math.PI;
        switch (s) {
            case DEER -> {
                b.legs(0.16f, 0.36f, -0.36f, 0.8f, 0.11f, 0xB0B0B0, true);
                b.coat(Anim.BODY, 0, 0, 0.8f, 0, -0.24f, 0, -0.52f, 0.48f, 0.46f, 1.04f, 0xFFFFFF);
                b.coat(Anim.BODY, 0, 0, 0.8f, 0, -0.2f, -0.02f, -0.4f, 0.4f, 0.1f, 0.8f, 0xE8D8C8); // pale belly
                // Neck and head turn together around the base of the neck.
                b.coat(Anim.HEAD, 0, 0, 1.12f, 0.42f, -0.1f, -0.04f, -0.08f, 0.2f, 0.42f, 0.22f, 0xFFFFFF);
                b.coat(Anim.HEAD, 0, 0, 1.12f, 0.42f, -0.13f, 0.32f, -0.04f, 0.26f, 0.24f, 0.4f, 0xFFFFFF);
                b.box(Anim.HEAD, 0, 0, 1.12f, 0.42f, -0.08f, 0.33f, 0.34f, 0.16f, 0.13f, 0.12f, 0x2E2018);
                b.coat(Anim.HEAD, 0, 0, 1.12f, 0.42f, -0.24f, 0.5f, 0.02f, 0.12f, 0.08f, 0.05f, 0xD0C0B0); // ears
                b.coat(Anim.HEAD, 0, 0, 1.12f, 0.42f, 0.12f, 0.5f, 0.02f, 0.12f, 0.08f, 0.05f, 0xD0C0B0);
                int antler = 0xD8C8A0; // antlers: two beams with tines
                b.box(Anim.HEAD, 0, 0, 1.12f, 0.42f, -0.12f, 0.55f, 0.06f, 0.04f, 0.32f, 0.04f, antler);
                b.box(Anim.HEAD, 0, 0, 1.12f, 0.42f, 0.08f, 0.55f, 0.06f, 0.04f, 0.32f, 0.04f, antler);
                b.box(Anim.HEAD, 0, 0, 1.12f, 0.42f, -0.26f, 0.78f, 0.04f, 0.16f, 0.04f, 0.04f, antler);
                b.box(Anim.HEAD, 0, 0, 1.12f, 0.42f, 0.1f, 0.78f, 0.04f, 0.16f, 0.04f, 0.04f, antler);
                b.box(Anim.HEAD, 0, 0, 1.12f, 0.42f, -0.12f, 0.68f, 0.1f, 0.04f, 0.04f, 0.14f, antler);
                b.box(Anim.HEAD, 0, 0, 1.12f, 0.42f, 0.08f, 0.68f, 0.1f, 0.04f, 0.04f, 0.14f, antler);
                b.box(Anim.TAIL, 0, 0, 1.2f, -0.52f, -0.06f, -0.16f, -0.06f, 0.12f, 0.16f, 0.07f, 0xF2EEE6);
            }
            case IBEX -> {
                b.legs(0.17f, 0.3f, -0.32f, 0.62f, 0.13f, 0xA0A0A0, true);
                b.coat(Anim.BODY, 0, 0, 0.62f, 0, -0.27f, 0, -0.48f, 0.54f, 0.52f, 0.96f, 0xFFFFFF);
                b.box(Anim.BODY, 0, 0, 0.62f, 0, -0.25f, -0.02f, -0.3f, 0.5f, 0.12f, 0.6f, 0x4A3E32); // dark flank stripe
                b.coat(Anim.HEAD, 0, 0, 1.0f, 0.42f, -0.11f, -0.04f, -0.08f, 0.22f, 0.32f, 0.22f, 0xFFFFFF);
                b.coat(Anim.HEAD, 0, 0, 1.0f, 0.42f, -0.12f, 0.2f, -0.02f, 0.24f, 0.24f, 0.36f, 0xFFFFFF);
                b.box(Anim.HEAD, 0, 0, 1.0f, 0.42f, -0.05f, 0.04f, 0.22f, 0.1f, 0.2f, 0.08f, 0x3A3028); // beard
                int horn = 0x6A5E4E; // long ridged horns sweeping back
                for (int i = 0; i < 5; i++) {
                    float y = 0.42f + i * 0.1f, z = 0.06f - i * 0.11f;
                    b.box(Anim.HEAD, 0, 0, 1.0f, 0.42f, -0.12f, y, z, 0.07f, 0.12f, 0.1f, horn);
                    b.box(Anim.HEAD, 0, 0, 1.0f, 0.42f, 0.05f, y, z, 0.07f, 0.12f, 0.1f, horn);
                }
                b.box(Anim.TAIL, 0, 0, 1.08f, -0.48f, -0.05f, -0.12f, -0.05f, 0.1f, 0.12f, 0.06f, 0x3A3028);
            }
            case RABBIT -> {
                b.coat(Anim.LEG, 0, -0.08f, 0.14f, -0.12f, -0.05f, -0.14f, -0.1f, 0.1f, 0.14f, 0.22f, 0xE0E0E0);
                b.coat(Anim.LEG, 0, 0.08f, 0.14f, -0.12f, -0.05f, -0.14f, -0.1f, 0.1f, 0.14f, 0.22f, 0xE0E0E0);
                b.coat(Anim.LEG, PI, -0.07f, 0.12f, 0.12f, -0.03f, -0.12f, -0.03f, 0.06f, 0.12f, 0.06f, 0xE0E0E0);
                b.coat(Anim.LEG, PI, 0.07f, 0.12f, 0.12f, -0.03f, -0.12f, -0.03f, 0.06f, 0.12f, 0.06f, 0xE0E0E0);
                b.coat(Anim.BODY, 0, 0, 0.1f, 0, -0.13f, 0, -0.2f, 0.26f, 0.24f, 0.38f, 0xFFFFFF);
                b.coat(Anim.HEAD, 0, 0, 0.26f, 0.15f, -0.09f, 0, -0.04f, 0.18f, 0.16f, 0.18f, 0xFFFFFF);
                b.box(Anim.HEAD, 0, 0, 0.26f, 0.15f, -0.03f, 0.04f, 0.13f, 0.06f, 0.04f, 0.02f, 0x5A3A3A);
                b.coat(Anim.EAR, 0, -0.05f, 0.42f, 0.17f, -0.03f, 0, -0.02f, 0.05f, 0.22f, 0.04f, 0xF0F0F0);
                b.coat(Anim.EAR, PI, 0.05f, 0.42f, 0.17f, -0.02f, 0, -0.02f, 0.05f, 0.22f, 0.04f, 0xF0F0F0);
                b.box(Anim.TAIL, 0, 0, 0.24f, -0.2f, -0.05f, -0.05f, -0.06f, 0.1f, 0.1f, 0.08f, 0xF4F4F4);
            }
            case FOX -> {
                b.legs(0.1f, 0.2f, -0.2f, 0.32f, 0.08f, 0x2A2420, false);
                b.coat(Anim.BODY, 0, 0, 0.32f, 0, -0.14f, 0, -0.3f, 0.28f, 0.26f, 0.6f, 0xFFFFFF);
                b.box(Anim.BODY, 0, 0, 0.32f, 0, -0.1f, -0.01f, 0.05f, 0.2f, 0.08f, 0.24f, 0xF0EAE0); // white chest
                b.coat(Anim.HEAD, 0, 0, 0.5f, 0.3f, -0.12f, -0.06f, -0.04f, 0.24f, 0.2f, 0.22f, 0xFFFFFF);
                b.box(Anim.HEAD, 0, 0, 0.5f, 0.3f, -0.06f, -0.06f, 0.18f, 0.12f, 0.09f, 0.14f, 0xF0EAE0); // muzzle
                b.box(Anim.HEAD, 0, 0, 0.5f, 0.3f, -0.02f, 0.0f, 0.31f, 0.04f, 0.04f, 0.02f, 0x111111);
                b.coat(Anim.EAR, 0, -0.08f, 0.64f, 0.3f, -0.04f, 0, -0.02f, 0.08f, 0.1f, 0.04f, 0xC0C0C0);
                b.coat(Anim.EAR, PI, 0.08f, 0.64f, 0.3f, -0.04f, 0, -0.02f, 0.08f, 0.1f, 0.04f, 0xC0C0C0);
                b.coat(Anim.TAIL, 0, 0, 0.5f, -0.3f, -0.08f, -0.14f, -0.42f, 0.16f, 0.16f, 0.42f, 0xFFFFFF);
                b.box(Anim.TAIL, 0, 0, 0.5f, -0.3f, -0.07f, -0.13f, -0.54f, 0.14f, 0.14f, 0.13f, 0xF4F0EA); // white tip
            }
            case TROUT -> {
                b.coat(Anim.BODY, 0, 0, 0.06f, 0, -0.06f, 0, -0.2f, 0.12f, 0.15f, 0.42f, 0xFFFFFF);
                b.box(Anim.BODY, 0, 0, 0.06f, 0, -0.065f, 0.05f, -0.18f, 0.13f, 0.035f, 0.38f, 0xD08A8A); // pink lateral band
                b.box(Anim.BODY, 0, 0, 0.06f, 0, -0.055f, 0, -0.16f, 0.11f, 0.04f, 0.34f, 0xE6E6D8); // pale belly
                b.coat(Anim.FIN, 0, 0, 0.13f, -0.2f, -0.015f, -0.08f, -0.16f, 0.03f, 0.16f, 0.16f, 0xC0C0C0);
                b.coat(Anim.BODY, 0, 0, 0.06f, 0, -0.01f, 0.15f, -0.06f, 0.02f, 0.06f, 0.1f, 0xB0B0B0); // dorsal fin
            }
            case HERON -> {
                int grey = 0xFFFFFF, dark = 0x2C2E33, beak = 0xD9A33A;
                b.box(Anim.LEG, 0, -0.07f, 0.75f, 0, -0.02f, -0.75f, -0.02f, 0.04f, 0.75f, 0.04f, 0x8A7A5A);
                b.box(Anim.LEG, PI, 0.07f, 0.75f, 0, -0.02f, -0.75f, -0.02f, 0.04f, 0.75f, 0.04f, 0x8A7A5A);
                b.coat(Anim.BODY, 0, 0, 0.72f, 0, -0.14f, 0, -0.26f, 0.28f, 0.3f, 0.48f, grey);
                b.coat(Anim.WING, 0, -0.14f, 0.96f, 0, -0.62f, -0.02f, -0.22f, 0.62f, 0.04f, 0.42f, 0xC8C8C8);
                b.coat(Anim.WING, PI, 0.14f, 0.96f, 0, 0, -0.02f, -0.22f, 0.62f, 0.04f, 0.42f, 0xC8C8C8);
                b.box(Anim.TAIL, 0, 0, 0.82f, -0.26f, -0.1f, -0.04f, -0.14f, 0.2f, 0.06f, 0.16f, dark);
                // S-shaped neck folded on the shoulders, stretched forward when it strikes.
                b.box(Anim.HEAD, 0, 0, 0.96f, 0.18f, -0.05f, 0, -0.04f, 0.1f, 0.34f, 0.09f, 0xE8E8E8);
                b.coat(Anim.HEAD, 0, 0, 0.96f, 0.18f, -0.07f, 0.32f, -0.06f, 0.14f, 0.12f, 0.18f, grey);
                b.box(Anim.HEAD, 0, 0, 0.96f, 0.18f, -0.075f, 0.38f, -0.1f, 0.15f, 0.04f, 0.16f, dark); // black crest
                b.box(Anim.HEAD, 0, 0, 0.96f, 0.18f, -0.025f, 0.34f, 0.12f, 0.05f, 0.05f, 0.28f, beak);
            }
            case LANTERN -> {
                // A glowing bell over rings of trailing threads.
                b.glow(Anim.BOB, 0, 0, 0.5f, 0, -0.3f, 0, -0.3f, 0.6f, 0.34f, 0.6f, 0x8FFFEA, 1f);
                b.glow(Anim.BOB, 0, 0, 0.5f, 0, -0.22f, 0.34f, -0.22f, 0.44f, 0.1f, 0.44f, 0xC8FFF6, 1f);
                b.glow(Anim.BOB, 0, 0, 0.5f, 0, -0.08f, 0.1f, -0.08f, 0.16f, 0.16f, 0.16f, 0xFFF2B0, 1f); // heart
                for (int i = 0; i < 6; i++) {
                    double a = i * Math.PI / 3;
                    float x = (float) Math.cos(a) * 0.2f, z = (float) Math.sin(a) * 0.2f;
                    b.glow(Anim.TENTACLE, i * 1.1f, x, 0.5f, z, -0.02f, -0.5f, -0.02f, 0.04f, 0.5f, 0.04f, 0x5FD8FF, 0.7f);
                }
            }
            case MOSSBACK -> {
                b.legs(0.42f, 0.4f, -0.4f, 0.32f, 0.22f, 0x5C5A48, false);
                b.box(Anim.BODY, 0, 0, 0.26f, 0, -0.5f, 0, -0.6f, 1.0f, 0.12f, 1.2f, 0x5A5444); // plastron
                b.coat(Anim.BODY, 0, 0, 0.26f, 0, -0.6f, 0.12f, -0.7f, 1.2f, 0.42f, 1.4f, 0xFFFFFF); // mossy shell
                b.coat(Anim.BODY, 0, 0, 0.26f, 0, -0.45f, 0.54f, -0.5f, 0.9f, 0.14f, 1.0f, 0xD8E8C0);
                b.box(Anim.BODY, 0, 0, 0.26f, 0, -0.07f, 0.68f, -0.07f, 0.14f, 0.5f, 0.14f, 0x5C4028); // its little tree
                b.box(Anim.BODY, 0, 0, 0.26f, 0, -0.32f, 1.06f, -0.32f, 0.64f, 0.42f, 0.64f, 0x3F7A2E);
                b.box(Anim.BODY, 0, 0, 0.26f, 0, -0.2f, 1.42f, -0.2f, 0.4f, 0.24f, 0.4f, 0x4C8E36);
                b.glow(Anim.BODY, 0, 0, 0.26f, 0, 0.32f, 0.6f, 0.2f, 0.08f, 0.08f, 0.08f, 0xF0E070, 0.8f); // glowing mushrooms
                b.glow(Anim.BODY, 0, 0, 0.26f, 0, -0.42f, 0.56f, -0.3f, 0.08f, 0.08f, 0.08f, 0xF0E070, 0.8f);
                b.box(Anim.HEAD, 0, 0, 0.4f, 0.7f, -0.14f, -0.1f, 0, 0.28f, 0.24f, 0.34f, 0x6A6852);
                b.box(Anim.HEAD, 0, 0, 0.4f, 0.7f, -0.12f, 0.05f, 0.3f, 0.05f, 0.05f, 0.02f, 0x101010);
                b.box(Anim.HEAD, 0, 0, 0.4f, 0.7f, 0.07f, 0.05f, 0.3f, 0.05f, 0.05f, 0.02f, 0x101010);
            }
            case FROSTLING -> {
                b.legs(0.09f, 0.14f, -0.14f, 0.24f, 0.07f, 0xD8F0FF, true);
                b.coat(Anim.BODY, 0, 0, 0.24f, 0, -0.12f, 0, -0.22f, 0.24f, 0.22f, 0.44f, 0xFFFFFF);
                for (int i = 0; i < 4; i++) // ice crystals along the spine
                    b.glow(Anim.BODY, 0, 0, 0.24f, 0, -0.04f, 0.2f, -0.16f + i * 0.1f, 0.08f, 0.12f + (i % 2) * 0.08f, 0.06f, 0xE0FAFF, 0.5f);
                b.coat(Anim.HEAD, 0, 0, 0.42f, 0.2f, -0.11f, -0.04f, -0.04f, 0.22f, 0.2f, 0.2f, 0xFFFFFF);
                b.glow(Anim.HEAD, 0, 0, 0.42f, 0.2f, -0.08f, 0.06f, 0.16f, 0.05f, 0.05f, 0.02f, 0x50D0FF, 1f); // eyes
                b.glow(Anim.HEAD, 0, 0, 0.42f, 0.2f, 0.03f, 0.06f, 0.16f, 0.05f, 0.05f, 0.02f, 0x50D0FF, 1f);
                b.coat(Anim.EAR, 0, -0.07f, 0.56f, 0.2f, -0.03f, 0, -0.02f, 0.06f, 0.16f, 0.04f, 0xE0F4FF);
                b.coat(Anim.EAR, PI, 0.07f, 0.56f, 0.2f, -0.03f, 0, -0.02f, 0.06f, 0.16f, 0.04f, 0xE0F4FF);
                b.glow(Anim.TAIL, 0, 0, 0.36f, -0.22f, -0.04f, -0.04f, -0.5f, 0.08f, 0.08f, 0.5f, 0xC8F0FF, 0.4f);
            }
            case SKY_MANTA -> {
                b.coat(Anim.BODY, 0, 0, 0.2f, 0, -0.5f, 0, -0.9f, 1.0f, 0.3f, 1.8f, 0xFFFFFF);
                b.box(Anim.BODY, 0, 0, 0.2f, 0, -0.45f, -0.02f, -0.8f, 0.9f, 0.04f, 1.6f, 0xD8DCE8); // pale belly
                b.coat(Anim.WING, 0, -0.5f, 0.32f, 0, -1.9f, -0.04f, -0.6f, 1.9f, 0.08f, 1.2f, 0xFFFFFF);
                b.coat(Anim.WING, PI, 0.5f, 0.32f, 0, 0, -0.04f, -0.6f, 1.9f, 0.08f, 1.2f, 0xFFFFFF);
                b.glow(Anim.WING, 0, -0.5f, 0.32f, 0, -1.7f, 0.04f, -0.2f, 1.4f, 0.02f, 0.12f, 0x9FE8FF, 0.6f); // luminous veins
                b.glow(Anim.WING, PI, 0.5f, 0.32f, 0, 0.3f, 0.04f, -0.2f, 1.4f, 0.02f, 0.12f, 0x9FE8FF, 0.6f);
                b.coat(Anim.HEAD, 0, 0, 0.2f, 0.9f, -0.32f, 0, 0, 0.64f, 0.22f, 0.3f, 0xE0E0E0);
                b.box(Anim.HEAD, 0, 0, 0.2f, 0.9f, -0.4f, 0, 0.18f, 0.12f, 0.14f, 0.22f, 0x2A3550); // cephalic lobes
                b.box(Anim.HEAD, 0, 0, 0.2f, 0.9f, 0.28f, 0, 0.18f, 0.12f, 0.14f, 0.22f, 0x2A3550);
                b.coat(Anim.TAIL, 0, 0, 0.3f, -0.9f, -0.04f, -0.04f, -2.2f, 0.08f, 0.08f, 2.2f, 0xC0C0C0);
            }
        }
        return b.out;
    }
}
