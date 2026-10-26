package net.mynecraft.world.life;

import net.mynecraft.world.Blocks;
import net.mynecraft.world.Chunk;
import net.mynecraft.world.Landscape;
import net.mynecraft.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * The animals around the player: they appear where their habitat can feed them (up to what the
 * {@link Ecosystem} says a place holds), live by their daily rhythm, graze, hunt, flee, keep
 * together, raise young, and disappear far from the player. They are not saved: the land
 * repopulates the same way when it is visited again.
 */
public final class Life {
    /** Creatures are kept within this distance of the player, and appear beyond {@link #SPAWN_MIN}. */
    public static final double KEEP = 112, SPAWN_MIN = 24, SPAWN_MAX = 72;
    public static final int MAX_CREATURES = 90;

    public final Ecosystem eco = new Ecosystem();
    /** The community model behind the animals met (populations, plants, soil, interactions). */
    public final Ecology ecology;
    private final Flora flora;
    /** Blocks changed by living things (grass grown, withered or grazed), for the renderer to remesh. */
    public final java.util.ArrayDeque<int[]> edits = new java.util.ArrayDeque<>();
    private double ecologyClock;
    public final List<Creature> creatures = new ArrayList<>();
    private final World world;
    /** Biome and climate of a column (the terrain generator's; tests give a fixed one). */
    public java.util.function.BiFunction<Integer, Integer, Landscape.Column> columns;
    /** New animals arrive around the player (off in tests of behaviour). */
    public boolean spawning = true;
    private final SplittableRandom rng;
    private int nextId;
    private double spawnClock;
    /** Counters for the debug screen. */
    public int born, eaten, spawned;

    public Life(World world, long seed) {
        this.world = world;
        this.rng = new SplittableRandom(seed ^ 0x5EED_A11CEL);
        this.columns = world.generator::column;
        this.ecology = new Ecology(seed, eco, (x, z) -> columns.apply(x, z));
        this.flora = new Flora(world, ecology, seed);
    }

    // ------------------------------------------------------------------------------- spawning

    /**
     * One step of the whole living world: new arrivals around the player, behaviour, movement,
     * births and deaths. {@code sneaking} halves the distance at which shy animals notice the player.
     */
    public void tick(double dt, double px, double py, double pz, boolean sneaking) {
        dt = Math.min(dt, 0.1);
        spawnClock += dt;
        // The ecosystem follows the calendar (a long gap, like a jump to another season, is not replayed).
        double gap = eco.day() - ecology.day();
        if (gap < 0 || gap > Ecosystem.YEAR_DAYS * 2) ecology.setDay(eco.day());
        else if (gap > 0.01) ecology.advance(gap);
        ecologyClock -= dt;
        if (ecologyClock <= 0) { // the patches around the player exist (bootstrapped on first visit)
            ecologyClock = 1;
            int cx = Math.floorDiv((int) Math.floor(px), Ecology.PATCH), cz = Math.floorDiv((int) Math.floor(pz), Ecology.PATCH);
            for (int dz = -2; dz <= 2; dz++)
                for (int dx = -2; dx <= 2; dx++) ecology.patch(cx + dx, cz + dz);
        }
        if (spawning) flora.tick(dt, px, pz, eco.season(), edits::add);
        boolean first = creatures.isEmpty();
        for (int i = 0; spawning && spawnClock >= 0.2 && i < (first ? 40 : 1); i++) trySpawn(px, pz, first ? 8 : SPAWN_MIN);
        if (spawnClock >= 0.2) spawnClock = 0;
        for (int i = 0; i < creatures.size(); i++) {
            Creature c = creatures.get(i);
            double dx = c.x - px, dz = c.z - pz;
            if (dx * dx + dz * dz > KEEP * KEEP || c.y < Chunk.MIN_Y) c.dead = true;
            else if (world.chunk(Creature.fl(c.x) >> 4, Creature.fl(c.z) >> 4) == null) c.dead = true;
            if (!c.dead) live(c, dt, px, py, pz, sneaking);
        }
        creatures.removeIf(c -> c.dead);
    }

    private void trySpawn(double px, double pz, double minDist) {
        if (creatures.size() >= MAX_CREATURES) return;
        double a = rng.nextDouble() * Math.PI * 2, r = minDist + rng.nextDouble() * (SPAWN_MAX - minDist);
        int wx = (int) Math.floor(px + Math.cos(a) * r), wz = (int) Math.floor(pz + Math.sin(a) * r);
        if (world.chunk(wx >> 4, wz >> 4) == null) return;
        Landscape.Column col = columns.apply(wx, wz);
        byte biome = col.biome();
        float temp = col.temperature();
        // The species this place lacks most, by what it can feed against who already lives nearby.
        Species pick = null;
        double total = 0;
        double[] want = new double[Species.values().length];
        for (Species s : Species.values()) {
            if (!eco.active(s) && (s.kind == Species.Kind.FLY || s.kind == Species.Kind.FLOAT)) continue;
            double lack = ecology.expected(s, wx, wz) - count(s, wx, wz, 64);
            if (lack <= 0) continue;
            want[s.ordinal()] = lack;
            total += lack;
        }
        if (total <= 0) return;
        double roll = rng.nextDouble() * total;
        for (Species s : Species.values()) {
            roll -= want[s.ordinal()];
            if (want[s.ordinal()] > 0 && roll <= 0) { pick = s; break; }
        }
        if (pick == null) return;
        int n = pick.groupMin + rng.nextInt(pick.groupMax - pick.groupMin + 1);
        Creature leader = null;
        for (int i = 0; i < n; i++) {
            double sx = wx + 0.5 + (i == 0 ? 0 : rng.nextDouble() * 6 - 3), sz = wz + 0.5 + (i == 0 ? 0 : rng.nextDouble() * 6 - 3);
            double[] at = place(pick, sx, sz, col);
            if (at == null) continue;
            Creature c = add(pick, at[0], at[1], at[2], biome, temp);
            c.age = pick.adultAge > 0 && rng.nextDouble() < 0.25 ? rng.nextDouble() * pick.adultAge : pick.adultAge + rng.nextDouble() * 600;
            c.hunger = rng.nextDouble() * 0.4;
            if (leader == null) leader = c;
            spawned++;
        }
    }

    /** Where a creature of this species can stand at (x, z): feet position, or null if the place does not suit it. */
    private double[] place(Species s, double x, double z, Landscape.Column col) {
        int bx = Creature.fl(x), bz = Creature.fl(z);
        int top = topAt(bx, col.height() + 24, bz, 48);
        if (top == Integer.MIN_VALUE) return null;
        byte surface = world.get(bx, top, bz);
        boolean water = Blocks.FLUID[surface & 0xFF] == Blocks.FLUID_WATER;
        switch (s.kind) {
            case SWIM -> {
                if (!water || Blocks.FLUID[world.get(bx, top - 1, bz) & 0xFF] != Blocks.FLUID_WATER) return null;
                return new double[] {x, top - 1.2 + rng.nextDouble() * 0.6, z};
            }
            case FLY -> {
                return new double[] {x, top + 22 + rng.nextDouble() * 10, z};
            }
            case FLOAT -> {
                if (s == Species.LANTERN && !water && rng.nextDouble() < 0.7) return null; // mostly over water
                return new double[] {x, top + 1.5 + rng.nextDouble() * 2, z};
            }
            case WADER -> {
                if (water && Blocks.FLUID[world.get(bx, top - 1, bz) & 0xFF] == Blocks.FLUID_NONE) return new double[] {x, top + 0.0, z}; // shallow
                if (water || !nearWater(bx, top, bz, 3)) return null;
                return new double[] {x, top + 1.0, z};
            }
            default -> {
                if (water || !Blocks.isSolid(surface) || Blocks.isFluid(world.get(bx, top + 1, bz))) return null;
                if (world.collisionBox(bx, top + 1, bz) >= 0 || world.collisionBox(bx, top + 2, bz) >= 0) return null;
                return new double[] {x, top + 1.0, z};
            }
        }
    }

    private boolean nearWater(int x, int y, int z, int r) {
        for (int dz = -r; dz <= r; dz++)
            for (int dx = -r; dx <= r; dx++)
                for (int dy = -1; dy <= 0; dy++)
                    if (Blocks.FLUID[world.get(x + dx, y + dy, z + dz) & 0xFF] == Blocks.FLUID_WATER) return true;
        return false;
    }

    /** Highest block that is solid or fluid at or below {@code from}, searching {@code depth} blocks down. */
    private int topAt(int x, int from, int z, int depth) {
        for (int y = Math.min(from, Chunk.MAX_Y - 1); y > from - depth && y >= Chunk.MIN_Y; y--) {
            byte b = world.get(x, y, z);
            if (Blocks.isFluid(b) || world.collisionBox(x, y, z) >= 0) return y;
        }
        return Integer.MIN_VALUE;
    }

    /** Height of the ground (or water surface) under a point: the y feet would stand at. */
    private double groundUnder(double x, double y, double z) {
        int top = topAt(Creature.fl(x), Creature.fl(y), Creature.fl(z), 64);
        return top == Integer.MIN_VALUE ? y - 64 : top + 1;
    }

    /** An adult of a species at (x, y, z), its coat from the local climate. */
    public Creature spawn(Species s, double x, double y, double z) {
        Landscape.Column col = columns.apply(Creature.fl(x), Creature.fl(z));
        Creature c = add(s, x, y, z, col.biome(), col.temperature());
        c.age = s.adultAge + 1;
        return c;
    }

    private Creature add(Species s, double x, double y, double z, byte biome, float temp) {
        Creature c = new Creature(s, nextId++, x, y, z);
        c.coat = s.coat(temp, eco.snowCover(temp, biome), rng.nextDouble());
        Ecology.Patch patch = ecology.existing(x, z);
        c.sick = patch != null && rng.nextDouble() < patch.sick[s.ordinal()];
        c.size = (float) (0.9 + rng.nextDouble() * 0.2);
        c.scale = c.size;
        c.yaw = c.targetYaw = (float) (rng.nextDouble() * Math.PI * 2);
        c.clock = (float) (rng.nextDouble() * 100);
        c.breedCooldown = 60 + rng.nextDouble() * 240;
        c.goalX = x;
        c.goalY = y;
        c.goalZ = z;
        creatures.add(c);
        return c;
    }

    /** One of every species in an arc in front of (x, z), standing on the real ground, for screenshots. */
    public void showcase(double x, double y, double z, float yaw) {
        Species[] all = Species.values();
        for (int i = 0; i < all.length; i++) {
            Species s = all[i];
            double a = yaw + Math.PI + (i - (all.length - 1) / 2.0) * 0.17; // forward is (-sin yaw, -cos yaw)
            double r = s.kind == Species.Kind.FLY ? 22 : 7 + (i % 3) * 2.5;
            double sx = x + Math.sin(a) * r, sz = z + Math.cos(a) * r;
            Landscape.Column col = columns.apply(Creature.fl(sx), Creature.fl(sz));
            boolean water = col.water() > col.height();
            if (s.kind == Species.Kind.SWIM && !water) continue;
            double sy = switch (s.kind) {
                case FLY -> y + 9;
                case FLOAT -> Math.max(col.height(), col.water()) + 2.2;
                case SWIM -> col.water() - 0.6;
                default -> water ? col.water() + 1.0 : col.height() + 1.0;
            };
            Creature c = add(s, sx, sy, sz, s.habitat.biomes()[0], (s.habitat.minTemp() + s.habitat.maxTemp()) / 2);
            c.age = s.adultAge + 1;
            c.state = Creature.State.IDLE;
            c.stateTime = 6;
            c.targetYaw = c.yaw = (float) Math.atan2(x - sx, z - sz) + 1.2f; // three-quarter view
            c.goalX = sx;
            c.goalY = sy;
            c.goalZ = sz;
        }
    }

    public int count(Species s, double x, double z, double radius) {
        int n = 0;
        for (Creature c : creatures) {
            if (c.species != s || c.dead) continue;
            double dx = c.x - x, dz = c.z - z;
            if (dx * dx + dz * dz <= radius * radius) n++;
        }
        return n;
    }

    // ------------------------------------------------------------------------------- behaviour

    private void live(Creature c, double dt, double px, double py, double pz, boolean sneaking) {
        Species s = c.species;
        c.age += dt;
        c.clock += (float) dt;
        c.hunger = Math.min(1, c.hunger + eco.hungerRate(s) * dt);
        c.breedCooldown -= dt;
        c.hurt = Math.max(0, c.hurt - (float) dt);
        c.stateTime -= dt;
        // Growing up: young animals are smaller.
        float grown = s.adultAge > 0 ? (float) Math.min(1, 0.45 + 0.55 * c.age / s.adultAge) : 1;
        c.scale = c.size * grown;
        if (c.dying > 0) {
            c.dying -= (float) dt;
            c.vx *= 0.8;
            c.vz *= 0.8;
            c.move(world, dt, s.kind == Species.Kind.GROUND || s.kind == Species.Kind.WADER);
            if (c.dying <= 0) c.dead = true;
            return;
        }
        // Night and day species fade away out of their hours; the others rest.
        if (!eco.active(s) && (s.kind == Species.Kind.FLY || s.kind == Species.Kind.FLOAT)) {
            c.stranded += dt;
            if (c.stranded > 4) { c.dying = 1.5f; return; }
        }
        double dpx = px - c.x, dpz = pz - c.z, dp = Math.hypot(dpx, dpz) + Math.abs(py - c.y) * 0.5;
        switch (s.kind) {
            case SWIM -> swim(c, dt, dp, px, py, pz);
            case FLY -> fly(c, dt);
            case FLOAT -> drift(c, dt, dp, px, pz);
            default -> walk(c, dt, dp, px, py, pz, sneaking);
        }
        // Head: look at the player when near and at ease, nod down to graze or fish.
        float wantYaw = 0, wantPitch = 0;
        if (c.state == Creature.State.GRAZE) wantPitch = 1.1f;
        else if (c.state == Creature.State.FISH) wantPitch = 0.55f;
        else if (c.state == Creature.State.SLEEP) wantPitch = 0.35f;
        else if (dp < 9 && c.state != Creature.State.FLEE && c.state != Creature.State.CHASE && s.kind != Species.Kind.SWIM) {
            float rel = wrap((float) Math.atan2(dpx, dpz) - c.yaw);
            wantYaw = Math.max(-1.1f, Math.min(1.1f, rel));
            wantPitch = (float) Math.max(-0.5, Math.min(0.5, -(py + 1.5 - (c.y + c.height())) / Math.max(1, dp)));
        }
        float k = (float) Math.min(1, dt * 5);
        c.headYaw += (wantYaw - c.headYaw) * k;
        c.headPitch += (wantPitch - c.headPitch) * k;
        // Turning toward where it goes, and the walk cycle from the ground covered.
        float turn = s == Species.SKY_MANTA ? 0.6f : s == Species.MOSSBACK ? 1.2f : 5f;
        float err = wrap(c.targetYaw - c.yaw);
        float step = (float) Math.max(-turn * dt, Math.min(turn * dt, err));
        c.yaw = wrap(c.yaw + step);
        boolean airborne = s.kind == Species.Kind.FLY || c.state == Creature.State.FLY;
        float wantBank = airborne ? (float) Math.max(-0.6, Math.min(0.6, -step / dt * 0.8)) : 0; // flyers lean into turns
        c.bank += (wantBank - c.bank) * k;
        double stride = Math.max(0.5, s.height * 0.9 * c.scale);
        c.gait += (float) (c.speed * dt / stride * Math.PI);
        if (c.health <= 0 && c.dying <= 0) c.dying = 1.2f;
    }

    // Ground animals: deer, rabbit, fox, ibex, mossback, frostling, and the heron (which also flies).
    private void walk(Creature c, double dt, double dp, double px, double py, double pz, boolean sneaking) {
        Species s = c.species;
        if (s.kind == Species.Kind.WADER && c.state == Creature.State.FLY) {
            heronFlight(c, dt);
            return;
        }
        double notice = s.fleeRange * (sneaking ? 0.5 : 1) * (s.temper == Species.Temper.WARY ? 0.65 : 1);
        Creature predator = nearest(c, 12, o -> o.species.hunts(s) && o.dying <= 0);
        boolean threatened = (dp < notice && s.fleeRange > 0) || predator != null || c.hurt > 0;
        double speed = 0;
        if (threatened && s == Species.MOSSBACK) {
            c.state = Creature.State.SLEEP; // withdraws into its shell
            c.stateTime = 4;
        } else if (threatened && s.kind == Species.Kind.WADER) {
            takeOff(c);
            return;
        } else if (threatened) {
            boolean fromPredator = predator != null && dp >= notice && c.hurt <= 0;
            double fx = fromPredator ? predator.x : px, fz = fromPredator ? predator.z : pz;
            if (c.state != Creature.State.FLEE || c.stateTime <= 0) {
                double away = Math.atan2(c.x - fx, c.z - fz) + (rng.nextDouble() - 0.5) * 0.8;
                c.goalX = c.x + Math.sin(away) * 16;
                c.goalZ = c.z + Math.cos(away) * 16;
                c.state = Creature.State.FLEE;
                c.stateTime = 2 + rng.nextDouble() * 2;
            }
            speed = s.runSpeed;
        } else if (c.state == Creature.State.FLEE && c.stateTime > 0) {
            speed = s.runSpeed;
        } else if (!eco.active(s)) {
            if (c.state != Creature.State.SLEEP) { c.state = Creature.State.SLEEP; c.stateTime = 30; }
        } else if (c.state == Creature.State.SLEEP && c.stateTime > 0 && s == Species.MOSSBACK) {
            // still hiding in its shell
        } else {
            speed = decide(c, dt, dp, px, py, pz);
        }
        // Into the goal direction, avoiding water, cliffs and walls (a jump for single steps).
        double gx = c.goalX - c.x, gz = c.goalZ - c.z, gd = Math.hypot(gx, gz);
        if (speed > 0 && gd > 0.4) {
            c.targetYaw = (float) Math.atan2(gx, gz);
            float face = (float) Math.cos(wrap(c.targetYaw - c.yaw));
            double v = speed * Math.max(0, face);
            int hazard = hazardAhead(c);
            if (hazard != 0 && !(hazard == 1 && s.kind == Species.Kind.WADER)) {
                double turn = (rng.nextBoolean() ? 1 : -1) * (1.2 + rng.nextDouble());
                c.goalX = c.x + Math.sin(c.yaw + turn) * 6;
                c.goalZ = c.z + Math.cos(c.yaw + turn) * 6;
                v = 0;
            }
            if (c.sick) v *= 0.65; // the ill lag behind (and are caught first)
            c.vx = Math.sin(c.yaw) * v;
            c.vz = Math.cos(c.yaw) * v;
            // Rabbits and frostlings bound along; everyone jumps a one-block step.
            boolean hopper = s == Species.RABBIT || s == Species.FROSTLING;
            if (c.onGround && (c.blocked || hopper && v > 0.3)) c.vy = c.blocked ? 8 : 4.5;
        } else {
            c.vx *= 0.6;
            c.vz *= 0.6;
            if (speed > 0) { c.state = Creature.State.IDLE; c.stateTime = 1 + rng.nextDouble() * 3; }
        }
        if (c.inWater) { // fell in: paddle up and toward the bank
            c.vy = Math.max(c.vy, 2.5);
            if (c.blocked) c.vy = 5.5;
        }
        c.move(world, dt, true);
    }

    /** What a calm ground animal does next: graze, hunt, follow its herd, play, wander or rest. Returns its speed. */
    private double decide(Creature c, double dt, double dp, double px, double py, double pz) {
        Species s = c.species;
        // Hunting.
        if (s.diet == Species.Diet.MEAT && c.hunger > 0.4 && c.state != Creature.State.CHASE) {
            Creature prey = nearest(c, 24, o -> s.hunts(o.species) && o.dying <= 0);
            if (prey != null) { c.state = Creature.State.CHASE; c.other = prey; c.stateTime = 9; }
        }
        if (c.state == Creature.State.CHASE) {
            Creature prey = c.other;
            if (prey == null || prey.dead || prey.dying > 0 || c.stateTime <= 0) {
                c.state = Creature.State.IDLE;
                c.stateTime = 5;
                c.other = null;
                return 0;
            }
            c.goalX = prey.x;
            c.goalZ = prey.z;
            if (Math.hypot(prey.x - c.x, prey.z - c.z) < c.halfWidth() + prey.halfWidth() + 0.4) {
                prey.dying = 1.2f;
                ecology.died(prey.species, prey.x, prey.z);
                c.hunger = 0;
                eaten++;
                c.state = Creature.State.IDLE;
                c.stateTime = 20; // a rest after the meal
            }
            return s.runSpeed;
        }
        // Wading birds fish standing in the shallows.
        if (s.kind == Species.Kind.WADER) {
            boolean wet = Blocks.FLUID[world.get(Creature.fl(c.x), Creature.fl(c.y), Creature.fl(c.z)) & 0xFF] == Blocks.FLUID_WATER;
            if (wet && c.state != Creature.State.WANDER) {
                if (c.state != Creature.State.FISH) { c.state = Creature.State.FISH; c.stateTime = 8 + rng.nextDouble() * 12; }
                Creature fish = nearest(c, 2.5, o -> o.species == Species.TROUT && o.dying <= 0);
                if (fish != null && rng.nextDouble() < dt * 0.5) {
                    fish.dying = 0.6f;
                    ecology.died(fish.species, fish.x, fish.z);
                    c.hunger = 0;
                    eaten++;
                    c.headPitch = 1.2f; // the strike
                }
                if (c.stateTime > 0) return 0;
                c.state = Creature.State.WANDER;
                c.stateTime = 4;
                pickGoal(c, 6, true);
                return s.walkSpeed;
            }
        }
        // Grazing on grass when hungry.
        if (s.diet == Species.Diet.GRASS && (c.hunger > 0.3 || c.state == Creature.State.GRAZE)) {
            byte under = world.get(Creature.fl(c.x), Creature.fl(c.y - 0.5), Creature.fl(c.z));
            byte at = world.get(Creature.fl(c.x), Creature.fl(c.y), Creature.fl(c.z));
            boolean food = under == Blocks.GRASS || at == Blocks.SHORT_GRASS || under == Blocks.SNOW_BLOCK && s == Species.IBEX
                || under == Blocks.STONE && s == Species.IBEX || under == Blocks.DIRT;
            if (food) {
                if (c.state != Creature.State.GRAZE) { c.state = Creature.State.GRAZE; c.stateTime = 4 + rng.nextDouble() * 6; }
                c.hunger = Math.max(0, c.hunger - dt / 25);
                if (c.stateTime > 0 && c.hunger > 0.02) return 0;
                if (at == Blocks.SHORT_GRASS && s != Species.RABBIT) { // the tuft is eaten
                    int bx = Creature.fl(c.x), by = Creature.fl(c.y), bz = Creature.fl(c.z);
                    if (world.set(bx, by, bz, Blocks.AIR)) edits.add(new int[] {bx, by, bz});
                    ecology.grazed(c.x, c.z);
                }
                c.state = Creature.State.IDLE;
                c.stateTime = 1;
            }
        }
        // Breeding: a fed adult with a fed mate nearby, where the land can feed one more.
        if (s.adultAge > 0 && Ecology.breeding(s, eco.season().phase()) > 0.3 && c.adult() && c.hunger < 0.3 && c.breedCooldown <= 0) {
            Creature mate = nearest(c, 6, o -> o.species == s && o != c && o.adult() && o.breedCooldown <= 0 && o.hunger < 0.4);
            Landscape.Column col = columns.apply(Creature.fl(c.x), Creature.fl(c.z));
            if (mate != null && count(s, c.x, c.z, 64) < ecology.expected(s, c.x, c.z) + 1) {
                Creature young = add(s, (c.x + mate.x) / 2, Math.max(c.y, mate.y), (c.z + mate.z) / 2, col.biome(), col.temperature());
                young.coat = c.coat;
                young.age = 0;
                young.hunger = 0.1;
                c.breedCooldown = mate.breedCooldown = s.adultAge * 1.5;
                born++;
            }
        }
        // Curious ones come to look at the player; playful ones dance around them.
        if (s.temper == Species.Temper.CURIOUS && dp < 12 && dp > 3.5) {
            c.state = Creature.State.FOLLOW;
            c.goalX = px;
            c.goalZ = pz;
            return s.walkSpeed;
        }
        if (s.temper == Species.Temper.PLAYFUL && dp < 14) {
            c.state = Creature.State.FOLLOW;
            double a = Math.atan2(c.x - px, c.z - pz) + 0.8;
            c.goalX = px + Math.sin(a) * 4;
            c.goalZ = pz + Math.cos(a) * 4;
            return dp < 3 ? s.runSpeed : s.walkSpeed * 1.4;
        }
        // Keeping with the herd, then wandering and resting in turns.
        double[] herd = herdStray(c);
        if (herd != null) {
            c.goalX = herd[0];
            c.goalZ = herd[1];
            c.state = Creature.State.WANDER;
            return s.walkSpeed * 1.3;
        }
        if (c.stateTime <= 0) {
            if (c.state == Creature.State.WANDER || rng.nextDouble() < 0.4) {
                c.state = Creature.State.IDLE;
                c.stateTime = 2 + rng.nextDouble() * 6;
            } else {
                c.state = Creature.State.WANDER;
                c.stateTime = 4 + rng.nextDouble() * 6;
                pickGoal(c, 10, s.kind == Species.Kind.WADER);
            }
        }
        if (!c.adult() && c.state == Creature.State.IDLE) { // the young stay by an adult
            Creature parent = nearest(c, 16, o -> o.species == s && o.adult());
            if (parent != null && Math.hypot(parent.x - c.x, parent.z - c.z) > 2.5) {
                c.goalX = parent.x;
                c.goalZ = parent.z;
                return s.walkSpeed * 1.2;
            }
        }
        return c.state == Creature.State.WANDER ? s.walkSpeed : 0;
    }

    /** The centre of its herd (x, z), if this creature has strayed more than 10 blocks from it. */
    private double[] herdStray(Creature c) {
        if (c.species.groupMax < 2) return null;
        double sx = 0, sz = 0;
        int n = 0;
        for (Creature o : creatures) {
            if (o == c || o.species != c.species || o.dying > 0) continue;
            double d = Math.hypot(o.x - c.x, o.z - c.z);
            if (d > 32) continue;
            sx += o.x;
            sz += o.z;
            n++;
        }
        if (n == 0) return null;
        sx /= n;
        sz /= n;
        return Math.hypot(sx - c.x, sz - c.z) < 10 ? null : new double[] {sx, sz};
    }

    private void pickGoal(Creature c, double r, boolean nearWater) {
        for (int i = 0; i < 6; i++) {
            double a = rng.nextDouble() * Math.PI * 2, d = 2 + rng.nextDouble() * r;
            double gx = c.x + Math.sin(a) * d, gz = c.z + Math.cos(a) * d;
            if (nearWater && !nearWater(Creature.fl(gx), Creature.fl(c.y), Creature.fl(gz), 2)) continue;
            c.goalX = gx;
            c.goalZ = gz;
            return;
        }
        c.goalX = c.x + Math.sin(c.yaw) * 4;
        c.goalZ = c.z + Math.cos(c.yaw) * 4;
    }

    /**
     * What lies one step ahead: 0 nothing, 1 water, 2 a drop of more than 3 blocks, 3 a wall
     * of two blocks or more.
     */
    private int hazardAhead(Creature c) {
        double reach = c.halfWidth() + 0.7;
        int ax = Creature.fl(c.x + Math.sin(c.yaw) * reach), az = Creature.fl(c.z + Math.cos(c.yaw) * reach);
        int fy = Creature.fl(c.y + 0.01);
        if (world.collisionBox(ax, fy, az) >= 0 && world.collisionBox(ax, fy + 1, az) >= 0) return 3;
        if (world.collisionBox(ax, fy, az) >= 0) return 0; // a single step: jumped
        for (int y = fy; y >= fy - 3; y--) {
            byte b = world.get(ax, y, az);
            if (Blocks.FLUID[b & 0xFF] == Blocks.FLUID_WATER || Blocks.FLUID[b & 0xFF] == Blocks.FLUID_LAVA) return 1;
            if (world.collisionBox(ax, y, az) >= 0) return 0;
        }
        return 2;
    }

    private void takeOff(Creature c) {
        c.state = Creature.State.FLY;
        double a = rng.nextDouble() * Math.PI * 2;
        for (int i = 0; i < 8; i++, a += 0.8) { // another stretch of bank 20-35 blocks away
            double gx = c.x + Math.sin(a) * (20 + rng.nextDouble() * 15), gz = c.z + Math.cos(a) * (20 + rng.nextDouble() * 15);
            if (world.chunk(Creature.fl(gx) >> 4, Creature.fl(gz) >> 4) == null) continue;
            c.goalX = gx;
            c.goalZ = gz;
            if (nearWater(Creature.fl(gx), Creature.fl(groundUnder(gx, c.y + 20, gz)) - 1, Creature.fl(gz), 3)) break;
        }
        c.goalY = groundUnder(c.goalX, c.y + 20, c.goalZ);
        c.stateTime = 20;
        c.vy = 4;
    }

    private void heronFlight(Creature c, double dt) {
        double gx = c.goalX - c.x, gz = c.goalZ - c.z, gd = Math.hypot(gx, gz);
        double ground = groundUnder(c.x, c.y + 2, c.z);
        double cruise = Math.max(ground, c.goalY) + 7;
        double wantY = gd < 6 ? c.goalY : cruise;
        c.targetYaw = (float) Math.atan2(gx, gz);
        double v = c.species.runSpeed * (gd < 6 ? 0.5 : 1);
        c.vx = Math.sin(c.yaw) * v;
        c.vz = Math.cos(c.yaw) * v;
        c.vy += ((wantY - c.y) * 1.5 - c.vy) * Math.min(1, dt * 2);
        c.pitch = (float) Math.atan2(-c.vy, v) * 0.5f;
        c.move(world, dt, false);
        if ((gd < 1.5 && c.y - c.goalY < 1.2) || c.stateTime <= 0 || (c.onGround && gd < 6)) {
            c.state = Creature.State.IDLE;
            c.stateTime = 3;
            c.pitch = 0;
        }
    }

    // Fish: schooling in the water, darting away, flopping when stranded.
    private void swim(Creature c, double dt, double dp, double px, double py, double pz) {
        if (!c.inWater) {
            c.stranded += dt;
            c.state = Creature.State.FLEE;
            if (c.onGround && rng.nextDouble() < dt * 3) { // flopping
                c.vy = 4;
                double a = rng.nextDouble() * Math.PI * 2;
                c.vx = Math.sin(a) * 1.5;
                c.vz = Math.cos(a) * 1.5;
                c.targetYaw = (float) a;
            }
            c.vx *= 0.95;
            c.vz *= 0.95;
            c.move(world, dt, true);
            if (c.stranded > 12) c.dying = 1;
            return;
        }
        c.stranded = 0;
        Creature heron = nearest(c, 3, o -> o.species == Species.HERON);
        boolean scared = dp < 5 || heron != null || c.hurt > 0;
        if (scared && c.state != Creature.State.FLEE) {
            double fx = heron != null ? heron.x : px, fz = heron != null ? heron.z : pz;
            double a = Math.atan2(c.x - fx, c.z - fz) + (rng.nextDouble() - 0.5);
            waterGoal(c, a, 5);
            c.state = Creature.State.FLEE;
            c.stateTime = 1.5;
        } else if (c.stateTime <= 0 || Math.hypot(c.goalX - c.x, c.goalZ - c.z) < 0.6) {
            c.state = Creature.State.WANDER;
            c.stateTime = 3 + rng.nextDouble() * 4;
            waterGoal(c, rng.nextDouble() * Math.PI * 2, 4);
        }
        double speed = c.state == Creature.State.FLEE ? c.species.runSpeed : c.species.walkSpeed;
        double gx = c.goalX - c.x, gy = c.goalY - c.y, gz = c.goalZ - c.z, gd = Math.sqrt(gx * gx + gy * gy + gz * gz);
        // Schooling: match the heading of nearby schoolmates.
        double ax = 0, az = 0;
        for (Creature o : creatures)
            if (o != c && o.species == c.species && Math.abs(o.x - c.x) < 4 && Math.abs(o.z - c.z) < 4) { ax += o.vx; az += o.vz; }
        double tx = gx / Math.max(gd, 1e-3) * speed + ax * 0.08, tz = gz / Math.max(gd, 1e-3) * speed + az * 0.08;
        c.targetYaw = (float) Math.atan2(tx, tz);
        double k = Math.min(1, dt * 3);
        c.vx += (tx - c.vx) * k;
        c.vz += (tz - c.vz) * k;
        c.vy += (gy / Math.max(gd, 1e-3) * speed * 0.6 - c.vy) * k;
        // Stay under the surface.
        byte above = world.get(Creature.fl(c.x), Creature.fl(c.y + c.height() + 0.2), Creature.fl(c.z));
        if (!Blocks.isFluid(above) && c.vy > 0) c.vy = -0.3;
        c.pitch = (float) Math.atan2(-c.vy, Math.hypot(c.vx, c.vz) + 0.1) * 0.7f;
        c.move(world, dt, false);
        if (c.blocked) { c.stateTime = 0; }
    }

    /** A goal in water in direction {@code a}, up to {@code r} blocks away (or here). */
    private void waterGoal(Creature c, double a, double r) {
        for (int i = 0; i < 6; i++) {
            double d = 1.5 + rng.nextDouble() * r, aa = a + (rng.nextDouble() - 0.5) * 1.2 * i / 3;
            double gx = c.x + Math.sin(aa) * d, gz = c.z + Math.cos(aa) * d, gy = c.y + (rng.nextDouble() - 0.5) * 1.6;
            if (Blocks.FLUID[world.get(Creature.fl(gx), Creature.fl(gy), Creature.fl(gz)) & 0xFF] == Blocks.FLUID_WATER
                && Blocks.FLUID[world.get(Creature.fl(gx), Creature.fl(gy + 0.3), Creature.fl(gz)) & 0xFF] == Blocks.FLUID_WATER) {
                c.goalX = gx;
                c.goalY = gy;
                c.goalZ = gz;
                return;
            }
        }
        c.goalX = c.x;
        c.goalY = c.y;
        c.goalZ = c.z;
    }

    // The sky manta: wide slow circles high above the land.
    private void fly(Creature c, double dt) {
        c.state = Creature.State.FLY;
        double cx = c.goalX, cz = c.goalZ;
        double a = Math.atan2(c.x - cx, c.z - cz) + 0.35; // a point ahead on the circle
        double r = 22 + 6 * Math.sin(c.clock * 0.05 + c.id);
        double tx = cx + Math.sin(a) * r, tz = cz + Math.cos(a) * r;
        if (c.stateTime <= 0) { // now and then the circle drifts somewhere else
            c.stateTime = 40 + rng.nextDouble() * 40;
            c.goalX += (rng.nextDouble() - 0.5) * 40;
            c.goalZ += (rng.nextDouble() - 0.5) * 40;
            c.goalY = groundUnder(c.goalX, Chunk.MAX_Y - 1, c.goalZ) + 26 + rng.nextDouble() * 10;
        }
        double below = groundUnder(c.x, Math.min(Chunk.MAX_Y - 1, c.y + 30), c.z);
        double wantY = Math.max(c.goalY, below + 16) + Math.sin(c.clock * 0.3) * 2;
        c.targetYaw = (float) Math.atan2(tx - c.x, tz - c.z);
        double v = c.species.walkSpeed;
        c.vx = Math.sin(c.yaw) * v;
        c.vz = Math.cos(c.yaw) * v;
        c.vy += ((wantY - c.y) * 0.4 - c.vy) * Math.min(1, dt);
        c.pitch = (float) Math.atan2(-c.vy, v) * 0.6f;
        c.move(world, dt, false);
        if (c.blocked) c.goalY += 6;
    }

    // The mist lantern: hovering and bobbing low over the water, drifting slowly.
    private void drift(Creature c, double dt, double dp, double px, double pz) {
        if (c.stateTime <= 0 || Math.hypot(c.goalX - c.x, c.goalZ - c.z) < 0.5) {
            c.stateTime = 5 + rng.nextDouble() * 8;
            double a = rng.nextDouble() * Math.PI * 2;
            c.goalX = c.x + Math.sin(a) * 5;
            c.goalZ = c.z + Math.cos(a) * 5;
        }
        if (dp < 3) { // drifts aside, unhurried
            double a = Math.atan2(c.x - px, c.z - pz);
            c.goalX = c.x + Math.sin(a) * 4;
            c.goalZ = c.z + Math.cos(a) * 4;
        }
        double gx = c.goalX - c.x, gz = c.goalZ - c.z, gd = Math.max(1e-3, Math.hypot(gx, gz));
        double v = c.species.walkSpeed * Math.min(1, gd);
        double k = Math.min(1, dt * 1.5);
        c.vx += (gx / gd * v - c.vx) * k;
        c.vz += (gz / gd * v - c.vz) * k;
        double ground = groundUnder(c.x, c.y + 1, c.z);
        double wantY = ground + 2.2 + Math.sin(c.clock * 0.7 + c.id) * 0.5;
        c.vy += ((wantY - c.y) * 1.2 - c.vy) * k;
        c.targetYaw += (float) (dt * 0.3);
        c.move(world, dt, false);
    }

    private Creature nearest(Creature c, double r, java.util.function.Predicate<Creature> test) {
        Creature best = null;
        double bestD = r * r;
        for (Creature o : creatures) {
            if (o == c || o.dead) continue;
            double dx = o.x - c.x, dy = o.y - c.y, dz = o.z - c.z, d = dx * dx + dy * dy + dz * dz;
            if (d < bestD && test.test(o)) { bestD = d; best = o; }
        }
        return best;
    }

    // ------------------------------------------------------------------------------- the player

    /** The creature the ray hits first within {@code maxDist}, or null. */
    public Creature raycast(double ox, double oy, double oz, double dx, double dy, double dz, double maxDist) {
        Creature best = null;
        double bestT = maxDist;
        for (Creature c : creatures) {
            if (c.dying > 0) continue;
            double t = c.rayHit(ox, oy, oz, dx, dy, dz, bestT);
            if (t >= 0 && t < bestT) { bestT = t; best = c; }
        }
        return best;
    }

    /** The player strikes: knocked back away from (fromX, fromZ), hurt, and frightened. */
    public void strike(Creature c, float damage, double fromX, double fromZ) {
        double a = Math.atan2(c.x - fromX, c.z - fromZ);
        c.vx = Math.sin(a) * 6;
        c.vz = Math.cos(a) * 6;
        c.vy = Math.max(c.vy, 4);
        c.health -= damage;
        c.hurt = 0.5f;
        c.state = Creature.State.IDLE; // re-decides: flees
        c.stateTime = 0;
        if (c.health <= 0) {
            c.dying = 1.2f;
            ecology.died(c.species, c.x, c.z);
        }
    }

    static float wrap(float a) {
        a %= (float) (Math.PI * 2);
        if (a > Math.PI) a -= (float) (Math.PI * 2);
        if (a < -Math.PI) a += (float) (Math.PI * 2);
        return a;
    }
}
