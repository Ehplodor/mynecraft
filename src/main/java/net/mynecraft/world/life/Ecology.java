package net.mynecraft.world.life;

import net.mynecraft.world.Landscape;
import net.mynecraft.world.TerrainGenerator;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.function.BiFunction;

/**
 * The ecosystem behind the animals one meets: the land is divided in patches of 64 x 64 blocks,
 * each holding a small community model integrated day after day (also far from the player, for
 * every patch ever visited):
 * <ul>
 *   <li><b>producers</b>: plant biomass growing logistically toward what the biome, the rain and the
 *   season allow, faster on rich soil and where pollinators work; flowers in the warm season;
 *   algae and invertebrates in the water, fed by the nutrients washed off the land;</li>
 *   <li><b>consumers</b>: every species' numbers, with births in its breeding season, deaths,
 *   crowding (intraspecific competition), and how well it eats (a saturating, "type II" intake);</li>
 *   <li><b>interactions</b> (see {@link Interaction}): predation (fox on rabbit, heron on trout),
 *   herbivory and the exploitative competition it creates between grazers, mutualism (mist lanterns
 *   pollinate flowers, which feed them), commensalism (frostlings feed on the lichen uncovered by
 *   hoofed animals), a pathogen spreading with crowding (parasitism), decomposers (the mossback's
 *   mushrooms) closing the nutrient cycle;</li>
 *   <li><b>nutrients</b>: dead animals and dung become detritus, decomposed into soil (slower in the
 *   cold), taken up by the plants, washed into the water; sky mantas carry nutrients to the peaks;</li>
 *   <li><b>space and seasons</b>: animals disperse between neighbouring patches, herons and mantas
 *   migrate away in winter and come back in spring, mossbacks and mist lanterns spend the winter
 *   dormant, frostlings the summer.</li>
 * </ul>
 * A new patch is bootstrapped by running a few years from a sparse seeding, so the community has
 * settled into its own balance (or its cycles) before it is ever seen.
 */
public final class Ecology {
    public static final int PATCH = 64;
    /** Years simulated when a patch is first created. */
    static final int BOOTSTRAP_YEARS = 6;
    private static final double STEP = 0.1; // days per integration step
    private static final Species[] S = Species.values();
    private static final int N = S.length;

    /** One patch: its fixed nature (sampled once from the terrain) and its living state. */
    public static final class Patch {
        public final int px, pz;
        public final byte biome;
        /** Climate temperature (deg C at the patch's height, before the season), water and forest fractions, rain 0..1. */
        public final float climate, water, forest, rain, height;
        /** Plant biomass, flowers, soil nutrients, detritus, aquatic food (algae, insects): all 0..~1. */
        public double plants, flowers, soil, detritus, aquatic;
        /** Animals in the patch per species (active ones), and those dormant (hibernating, estivating). */
        public final double[] pop = new double[N], dormant = new double[N];
        /** Fraction of each species carrying the disease. */
        public final double[] sick = new double[N];
        /** Numbers a day ago, for trends. */
        public final double[] yesterday = new double[N];
        double sinceSnapshot;

        Patch(int px, int pz, byte biome, float climate, float water, float forest, float rain, float height) {
            this.px = px;
            this.pz = pz;
            this.biome = biome;
            this.climate = climate;
            this.water = water;
            this.forest = forest;
            this.rain = rain;
            this.height = height;
        }
    }

    /** The kinds of interaction between species (Wikipedia: "Biological interaction"). */
    public enum Interaction { PREDATION, HERBIVORY, COMPETITION, MUTUALISM, COMMENSALISM, PARASITISM, DECOMPOSITION }

    /** One interaction at work, for the naturalist's notebook. */
    public record Link(Interaction kind, String text, double strength) {}

    private final Map<Long, Patch> patches = new HashMap<>();
    private final long seed;
    private final Ecosystem eco;
    /** Terrain lookup (the generator's columns; tests give their own). */
    public BiFunction<Integer, Integer, Landscape.Column> columns;
    /** Days simulated so far (the world's calendar). */
    private double day;

    public Ecology(long seed, Ecosystem eco, BiFunction<Integer, Integer, Landscape.Column> columns) {
        this.seed = seed;
        this.eco = eco;
        this.columns = columns;
    }

    public static long key(int px, int pz) {
        return ((long) px << 32) | (pz & 0xFFFFFFFFL);
    }

    public Patch at(double x, double z) {
        return patch(Math.floorDiv((int) Math.floor(x), PATCH), Math.floorDiv((int) Math.floor(z), PATCH));
    }

    public Patch existing(double x, double z) {
        return patches.get(key(Math.floorDiv((int) Math.floor(x), PATCH), Math.floorDiv((int) Math.floor(z), PATCH)));
    }

    public int size() {
        return patches.size();
    }

    public java.util.Collection<Patch> patches() {
        return patches.values();
    }

    /** The patch (px, pz), created and bootstrapped on first use. */
    public Patch patch(int px, int pz) {
        Patch p = patches.get(key(px, pz));
        if (p != null) return p;
        p = create(px, pz);
        bootstrap(p);
        patches.put(key(px, pz), p);
        return p;
    }

    private Patch create(int px, int pz) {
        // Sample a 4x4 grid of columns: mean height, open water, forest, the dominant biome.
        int[] votes = new int[64];
        float h = 0, water = 0, forest = 0, temp = 0;
        for (int i = 0; i < 16; i++) {
            int x = px * PATCH + 8 + (i & 3) * 16, z = pz * PATCH + 8 + (i >> 2) * 16;
            Landscape.Column c = columns.apply(x, z);
            h += c.height();
            temp += c.temperature();
            if (c.water() > c.height()) water++;
            byte b = c.biome();
            votes[b & 63]++;
            if (b == Landscape.FOREST || b == Landscape.BIRCH_FOREST || b == Landscape.DARK_FOREST || b == Landscape.TAIGA
                || b == Landscape.SNOWY_TAIGA) forest++;
        }
        int best = 0;
        for (int b = 1; b < votes.length; b++) if (votes[b] > votes[best]) best = b;
        // Water-loving species need some water in the patch even when land wins the vote.
        h /= 16;
        float t01 = temp / 16;
        float climate = -14 + 40 * t01 - 0.0065f * Math.max(0, h - TerrainGenerator.SEA_LEVEL);
        float rain = Math.min(1, Math.max(0, 0.3f + 0.7f * (forest / 16) + 0.3f * (water / 16)));
        return new Patch(px, pz, (byte) best, climate, water / 16, forest / 16, rain, h);
    }

    /** Sparse seeding of every species the patch could hold, then a few years of life. */
    private void bootstrap(Patch p) {
        SplittableRandom r = new SplittableRandom(seed ^ key(p.px, p.pz) * 0x9E3779B97F4A7C15L);
        p.plants = 0.3 + 0.3 * r.nextDouble();
        p.soil = 0.4 + 0.2 * r.nextDouble();
        p.aquatic = p.water > 0 ? 0.3 : 0;
        for (int s = 0; s < N; s++) {
            if (suitable(p, S[s])) p.pop[s] = 0.5 + r.nextDouble() * 1.5;
        }
        double start = day - BOOTSTRAP_YEARS * Ecosystem.YEAR_DAYS;
        for (double d = start; d < day; d += STEP) step(p, Ecosystem.seasonAt(d), STEP);
        System.arraycopy(p.pop, 0, p.yesterday, 0, N);
    }

    /** True if the species can live in this patch at all (habitat biome and climate, water for the water-bound). */
    public boolean suitable(Patch p, Species s) {
        float t01 = (p.climate + 14) / 40;
        boolean biome = s.habitat.has(p.biome)
            || (s.kind == Species.Kind.SWIM || s == Species.HERON || s == Species.LANTERN) && p.water > 0.1f;
        if (!biome || t01 < s.habitat.minTemp() - 0.1f || t01 > s.habitat.maxTemp() + 0.1f) return false;
        return s.kind != Species.Kind.SWIM || p.water > 0.05f;
    }

    /**
     * Soft ceiling of a species in a patch (territories, dens, shelter): crowding deaths rise as
     * numbers approach it. Prey are many, predators few.
     */
    double ceiling(Patch p, Species s) {
        float base = switch (s) {
            case RABBIT -> 9;
            case DEER -> 4;
            case TROUT -> 12 * p.water;
            case IBEX -> 4;
            case FOX -> 1.5f;
            case HERON -> 1.5f;
            case LANTERN -> 6;
            case FROSTLING -> 4;
            case MOSSBACK -> 0.8f;
            case SKY_MANTA -> 0.6f;
        };
        return Math.max(0.05, base);
    }

    // ------------------------------------------------------------------------------- time

    /** Advances every patch by {@code days} (any length: integrated in small steps), with dispersal between neighbours. */
    public void advance(double days) {
        while (days > 1e-9) {
            double dt = Math.min(STEP, days);
            Ecosystem.Season season = Ecosystem.seasonAt(day);
            for (Patch p : patches.values()) step(p, season, dt);
            disperse(dt);
            day += dt;
            days -= dt;
            for (Patch p : patches.values()) {
                p.sinceSnapshot += dt;
                if (p.sinceSnapshot >= 1) {
                    p.sinceSnapshot = 0;
                    System.arraycopy(p.pop, 0, p.yesterday, 0, N);
                }
            }
        }
    }

    public double day() {
        return day;
    }

    /** Sets the calendar (from the world's time) without simulating the gap: used when loading. */
    public void setDay(double d) {
        day = d;
    }

    /** Temperature of a patch now (deg C): climate plus the season's swing. */
    public static double temperature(Patch p, Ecosystem.Season s) {
        return p.climate + Ecosystem.temperatureOffset(s.phase());
    }

    /** One integration step of a patch's community. */
    void step(Patch p, Ecosystem.Season season, double dt) {
        double t = temperature(p, season);
        double warm = clamp((t + 2) / 18), cold = clamp((2 - t) / 12);
        double[] n = p.pop;
        int rabbit = Species.RABBIT.ordinal(), deer = Species.DEER.ordinal(), ibex = Species.IBEX.ordinal(),
            fox = Species.FOX.ordinal(), trout = Species.TROUT.ordinal(), heron = Species.HERON.ordinal(),
            lantern = Species.LANTERN.ordinal(), moss = Species.MOSSBACK.ordinal(), frost = Species.FROSTLING.ordinal(),
            manta = Species.SKY_MANTA.ordinal();

        // Producers. Plants: logistic toward the season's ceiling, on soil, helped by pollinators.
        // The patch's own calendar (Vegetation): an alpine pasture greens weeks after the valleys, a dry plain browns in summer.
        double phase = season.phase(), local = 0.1 + 0.9 * Vegetation.growth(p.climate, p.rain, phase);
        double k = eco.forageBase(p.biome) * (0.5 + 0.5 * p.rain) * local * (0.25 + 0.75 * warm) + 0.02;
        double pollination = n[lantern] / (n[lantern] + 2);
        double growth = 0.5 * (0.3 + 0.7 * p.soil) * (1 + 0.3 * pollination) * p.plants * (1 - p.plants / k);
        if (growth < 0) growth *= 0.5; // dieback is slower than growth
        // Type II functional response of grazers; twigs and bark tide them over when the grass is gone.
        // Snow hides most of the grass, except from the ibex, which paws it away.
        double snow = Math.min(1, SnowCover.pack(p.climate, phase) / 4.0), reach = p.plants * (1 - 0.7 * snow);
        double satiety = (reach + 0.06 + 0.06 * p.forest) / (reach + 0.3);
        double pawing = (p.plants + 0.06 + 0.06 * p.forest) / (p.plants + 0.3);
        double[] intake = new double[N];
        double eat = reach / (reach + 0.25); // what they actually take from the grass
        intake[rabbit] = 0.002 * eat;
        intake[deer] = 0.006 * eat;
        intake[ibex] = 0.005 * p.plants / (p.plants + 0.25);
        intake[moss] = 0.008 * eat;
        double grazed = 0;
        for (int s = 0; s < N; s++) grazed += intake[s] * n[s];
        p.plants = Math.max(0.01, p.plants + (growth - grazed) * dt);
        p.soil = clamp(p.soil - Math.max(0, growth) * 0.1 * dt);
        // Flowers follow the plants through the local growing season and bloom, more where they are pollinated.
        double bloom = Math.max(0.1, Math.max(0.6 * Vegetation.growth(p.climate, p.rain, phase),
            Math.min(1, 2.5 * Vegetation.bloom(p.climate, phase))));
        double flowerTarget = p.plants * bloom * warm * (0.4 + 0.6 * pollination); // flowers set seed where pollinated
        p.flowers += (flowerTarget - p.flowers) * Math.min(1, 0.25 * dt);
        p.flowers = Math.max(0, p.flowers - 0.002 * n[lantern] * dt); // nectar drunk
        // Water: algae and insect larvae grow on the nutrients washed off the land.
        double runoff = 0.01 * p.soil * p.rain * p.water * dt;
        p.soil = clamp(p.soil - runoff * 0.5 - 0.015 * p.soil * p.rain * dt); // and some lost deep down
        double troutFood = p.aquatic / (p.aquatic + 0.2);
        p.aquatic = clamp(p.aquatic + (runoff * 20 + 0.02 * warm * p.water) - (0.05 * p.aquatic + 0.01 * n[trout] * troutFood) * dt);

        // Consumers: births (in season, as well fed as they are), deaths, crowding.
        double[] births = new double[N], deaths = new double[N];
        for (int s = 0; s < N; s++) {
            if (n[s] <= 0 && p.dormant[s] <= 0) continue;
            Species sp = S[s];
            double food = switch (sp) {
                case RABBIT, DEER, MOSSBACK -> satiety;
                case IBEX -> pawing;
                case TROUT -> troutFood;
                case LANTERN -> p.flowers / (p.flowers + 0.15);
                case FROSTLING -> cold * (0.3 + 0.7 * (n[deer] + n[ibex]) / (n[deer] + n[ibex] + 3)); // commensal
                case SKY_MANTA -> clamp(p.plants * p.flowers * warm * 8); // insects swarming over flowers
                default -> 1; // predators: see below
            };
            double b = switch (sp) { // births per animal per day at the height of the breeding season
                case RABBIT -> 0.3;
                case DEER, IBEX -> 0.12;
                case TROUT -> 0.3;
                case LANTERN -> 0.5;
                case MOSSBACK -> 0.02;
                case FROSTLING -> 0.2;
                case SKY_MANTA -> 0.1;
                default -> 0; // predators: from what they catch, below
            };
            double m = switch (sp) { // deaths per animal per day, well fed
                case RABBIT -> 0.02;
                case DEER, IBEX -> 0.008;
                case TROUT -> 0.02;
                case LANTERN -> 0.02;
                case MOSSBACK -> 0.001;
                case FROSTLING -> 0.02 + 0.12 * (1 - cold); // they melt in the warm
                case SKY_MANTA -> 0.01;
                case FOX -> 0.012;
                case HERON -> 0.012;
            };
            // Logistic: births slow down as the patch fills up (territories, dens: intraspecific competition).
            births[s] = n[s] * b * food * breeding(sp, season.phase()) * Math.max(0, 1 - n[s] / ceiling(p, sp));
            deaths[s] = n[s] * m * (1 + 1.5 * (1 - food));
            if (!suitable(p, sp)) { births[s] = 0; deaths[s] += n[s] * 0.1; }
        }
        // Predation (type II): each predator kills up to a handful a day, fewer when prey is scarce.
        double foxFed = n[rabbit] / (n[rabbit] + 3), kills = Math.min(n[fox] * 0.35 * foxFed, n[rabbit] * 0.5);
        deaths[rabbit] += kills;
        births[fox] += n[fox] * 0.2 * foxFed * breeding(Species.FOX, season.phase()) * Math.max(0, 1 - n[fox] / ceiling(p, Species.FOX));
        deaths[fox] += n[fox] * 0.03 * (1 - foxFed); // hunger
        double heronFed = n[trout] / (n[trout] + 4), fished = Math.min(n[heron] * 0.15 * heronFed, n[trout] * 0.5);
        deaths[trout] += fished;
        births[heron] += n[heron] * 0.2 * heronFed * breeding(Species.HERON, season.phase()) * Math.max(0, 1 - n[heron] / ceiling(p, Species.HERON));
        deaths[heron] += n[heron] * 0.03 * (1 - heronFed);
        // Disease (a pathogen: parasitism), spreading faster in crowded herds.
        for (int s : new int[] {rabbit, deer, ibex}) {
            double i = p.sick[s];
            double di = 0.06 * n[s] * i * (1 - i) - 0.15 * i + 0.0005 * (1 - i);
            p.sick[s] = clamp(i + di * dt);
            deaths[s] += n[s] * 0.06 * p.sick[s];
        }
        // Migration and dormancy.
        boolean winter = phase >= 0.75, spring = phase < 0.2, summer = phase >= 0.25 && phase < 0.5;
        for (int s : new int[] {heron, manta}) {
            if (winter) deaths[s] += n[s] * 0.25;                                 // leaving south (counted out)
            else if (spring && suitable(p, S[s])) births[s] += 0.04 * ceiling(p, S[s]) * (1 - n[s] / ceiling(p, S[s])); // back
        }
        for (int s : new int[] {moss, lantern, frost}) {
            boolean asleep = s == frost ? summer && t > 4 : winter;
            double move = (asleep ? n[s] : -p.dormant[s]) * Math.min(1, 0.5 * dt);
            n[s] -= move;
            p.dormant[s] += move;
            p.dormant[s] *= 1 - 0.003 * dt; // a few never wake
        }
        // Dead animals and dung go to the detritus; decomposers turn it into soil.
        double fall = 0;
        for (int s = 0; s < N; s++) {
            fall += deaths[s] * mass(S[s]);
            n[s] = Math.max(0, n[s] + (births[s] - deaths[s]) * dt);
            if (n[s] < 0.01) n[s] = 0;
        }
        fall += grazed * 0.4;
        if (n[manta] > 0 && p.height > 120) p.soil = clamp(p.soil + 0.002 * n[manta] * dt); // guano on the peaks
        p.detritus += fall * 0.2 * dt;
        double decomposers = 1 + 0.6 * n[moss] / (n[moss] + 0.3) + 0.3 * p.forest; // mushrooms, fungi of the forest floor
        double decay = p.detritus * 0.12 * (0.15 + 0.85 * warm) * decomposers * dt;
        p.detritus = Math.max(0, p.detritus - decay);
        p.soil = clamp(p.soil + decay * 0.5 + 0.002 * dt); // and a little from the weathering rock
    }

    /** How much of the year's breeding a species does at this phase (0..1): spring young, autumn trout spawning... */
    public static double breeding(Species s, double phase) {
        double center = switch (s) {
            case TROUT -> 0.65;        // autumn spawning
            case FROSTLING -> 0.85;    // breeds in deep winter
            case DEER -> 0.2;
            case IBEX -> 0.28;
            case LANTERN, MOSSBACK, SKY_MANTA -> 0.35;
            default -> 0.15;           // rabbits, foxes, herons: spring
        };
        double width = s == Species.RABBIT ? 0.22 : 0.12;
        double d = Math.abs(phase - center);
        d = Math.min(d, 1 - d);
        return Math.exp(-(d * d) / (2 * width * width));
    }

    private static double mass(Species s) {
        return switch (s) {
            case DEER, IBEX -> 1;
            case MOSSBACK -> 2;
            case SKY_MANTA -> 1.5;
            case FOX, HERON -> 0.3;
            default -> 0.1;
        };
    }

    /** Animals wander to neighbouring patches (from crowded to empty), faster for the mobile ones. */
    private void disperse(double dt) {
        double[] rate = new double[N];
        for (int s = 0; s < N; s++) {
            rate[s] = switch (S[s]) {
                case DEER, FOX, LANTERN, FROSTLING -> 0.06;
                case RABBIT -> 0.03;
                case IBEX -> 0.04;
                case TROUT -> 0.03;
                case HERON -> 0.2;
                case SKY_MANTA -> 0.3;
                case MOSSBACK -> 0.005;
            };
        }
        int[][] dirs = {{1, 0}, {0, 1}};
        for (Patch p : patches.values()) {
            for (int[] d : dirs) {
                Patch q = patches.get(key(p.px + d[0], p.pz + d[1]));
                if (q == null) continue;
                for (int s = 0; s < N; s++) {
                    boolean pOk = suitable(p, S[s]), qOk = suitable(q, S[s]);
                    if (!pOk && !qOk) continue;
                    // Toward the patch with more room: compare crowding, not raw numbers.
                    double cp = pOk ? p.pop[s] / ceiling(p, S[s]) : 10, cq = qOk ? q.pop[s] / ceiling(q, S[s]) : 10;
                    double flow = rate[s] * (cp - cq) * dt * 0.5;
                    flow = flow > 0 ? Math.min(flow * ceiling(p, S[s]), p.pop[s] * 0.25) : Math.max(flow * ceiling(q, S[s]), -q.pop[s] * 0.25);
                    if (!qOk && flow > 0 || !pOk && flow < 0) continue;
                    p.pop[s] -= flow;
                    q.pop[s] += flow;
                }
            }
        }
    }

    // ------------------------------------------------------------------------------- the visible animals

    /** Animals of a species one should meet within 64 blocks of (x, z): the patch's numbers over a disc of three patches. */
    public double expected(Species s, double x, double z) {
        return at(x, z).pop[s.ordinal()] * Math.PI;
    }

    /** A visible animal died (eaten, struck): one less in its patch, and a carcass for the decomposers. */
    public void died(Species s, double x, double z) {
        Patch p = existing(x, z);
        if (p == null) return;
        p.pop[s.ordinal()] = Math.max(0, p.pop[s.ordinal()] - 1);
        p.detritus += mass(s) * 0.05;
    }

    /** A visible grazer ate a tuft of grass: a little less plant biomass. */
    public void grazed(double x, double z) {
        Patch p = existing(x, z);
        if (p != null) p.plants = Math.max(0.01, p.plants - 0.002);
    }

    /** The interactions working in a patch now, strongest first, for the notebook. */
    public java.util.List<Link> links(Patch p) {
        java.util.List<Link> out = new java.util.ArrayList<>();
        double[] n = p.pop;
        int rabbit = Species.RABBIT.ordinal(), deer = Species.DEER.ordinal(), ibex = Species.IBEX.ordinal(), fox = Species.FOX.ordinal(),
            trout = Species.TROUT.ordinal(), heron = Species.HERON.ordinal(), lantern = Species.LANTERN.ordinal(),
            moss = Species.MOSSBACK.ordinal(), frost = Species.FROSTLING.ordinal(), manta = Species.SKY_MANTA.ordinal();
        if (n[fox] > 0.05 && n[rabbit] > 0.05) out.add(new Link(Interaction.PREDATION, "Renard -> lapin", n[fox] * n[rabbit]));
        if (n[heron] > 0.05 && n[trout] > 0.05) out.add(new Link(Interaction.PREDATION, "Héron -> truite", n[heron] * n[trout]));
        double grazers = n[rabbit] + n[deer] + n[ibex] + n[moss];
        if (grazers > 0.1) out.add(new Link(Interaction.HERBIVORY, "Brouteurs -> plantes", grazers * 0.3));
        int kinds = (n[rabbit] > 0.1 ? 1 : 0) + (n[deer] > 0.1 ? 1 : 0) + (n[ibex] > 0.1 ? 1 : 0) + (n[moss] > 0.1 ? 1 : 0);
        if (kinds >= 2) out.add(new Link(Interaction.COMPETITION, "Brouteurs entre eux (même herbe)", grazers * (1 - p.plants)));
        if (n[lantern] > 0.05) out.add(new Link(Interaction.MUTUALISM, "Lanterne <-> fleurs (pollinisation)", n[lantern] * p.flowers));
        if (n[frost] > 0.05 && n[deer] + n[ibex] > 0.05)
            out.add(new Link(Interaction.COMMENSALISM, "Givrelin <- sabots (lichen dégagé)", n[frost] * (n[deer] + n[ibex])));
        for (int s : new int[] {rabbit, deer, ibex})
            if (p.sick[s] > 0.02 && n[s] > 0.05)
                out.add(new Link(Interaction.PARASITISM, String.format("Maladie : %.0f %% des %s", p.sick[s] * 100,
                    s == rabbit ? "lapins" : s == deer ? "cerfs" : "bouquetins"), p.sick[s] * n[s] * 4));
        if (n[moss] > 0.05) out.add(new Link(Interaction.DECOMPOSITION, "Champignons du moussard -> sol", n[moss] * p.detritus * 10));
        if (n[manta] > 0.05 && p.height > 120) out.add(new Link(Interaction.DECOMPOSITION, "Voiliers : engrais des cimes", n[manta]));
        out.sort((a, b) -> Double.compare(b.strength(), a.strength()));
        return out;
    }

    // ------------------------------------------------------------------------------- saving

    private static final int VERSION = 1;

    public void save(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new java.util.zip.GZIPOutputStream(Files.newOutputStream(tmp)))) {
            out.writeInt(VERSION);
            out.writeDouble(day);
            out.writeInt(N);
            out.writeInt(patches.size());
            for (Patch p : patches.values()) {
                out.writeInt(p.px);
                out.writeInt(p.pz);
                out.writeDouble(p.plants);
                out.writeDouble(p.flowers);
                out.writeDouble(p.soil);
                out.writeDouble(p.detritus);
                out.writeDouble(p.aquatic);
                for (int s = 0; s < N; s++) {
                    out.writeDouble(p.pop[s]);
                    out.writeDouble(p.dormant[s]);
                    out.writeDouble(p.sick[s]);
                }
            }
        }
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    /** Reads saved patches (their nature is sampled again from the terrain); false if there is no file. */
    public boolean load(Path file) throws IOException {
        if (!Files.exists(file)) return false;
        try (DataInputStream in = new DataInputStream(new java.util.zip.GZIPInputStream(Files.newInputStream(file)))) {
            if (in.readInt() != VERSION) return false;
            day = in.readDouble();
            int species = in.readInt(), count = in.readInt();
            for (int i = 0; i < count; i++) {
                Patch p = create(in.readInt(), in.readInt());
                p.plants = in.readDouble();
                p.flowers = in.readDouble();
                p.soil = in.readDouble();
                p.detritus = in.readDouble();
                p.aquatic = in.readDouble();
                for (int s = 0; s < species; s++) {
                    double a = in.readDouble(), b = in.readDouble(), c = in.readDouble();
                    if (s < N) { p.pop[s] = a; p.dormant[s] = b; p.sick[s] = c; }
                }
                System.arraycopy(p.pop, 0, p.yesterday, 0, N);
                patches.put(key(p.px, p.pz), p);
            }
        }
        return true;
    }

    static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }
}
