package net.mynecraft.world.weather;

import net.mynecraft.world.Blocks;
import net.mynecraft.world.Chunk;
import net.mynecraft.world.Landscape;
import net.mynecraft.world.TerrainGenerator;
import net.mynecraft.world.World;

import java.util.SplittableRandom;

/**
 * The atmosphere of a world, fed by its terrain, and its effects on the ground: snow settling on
 * grass, still water freezing, both thawing when it warms up. One {@link #second} per game second.
 */
public final class Weather {
    /** Called for every block the weather changes (the caller re-meshes and wakes the fluids). */
    public interface Listener {
        void changed(int x, int y, int z);
    }

    private static final int REACH = 64, COLUMNS = 48; // columns visited per second around the player

    public final Atmosphere atmosphere;
    private final World world;
    private final Listener listener;
    private final SplittableRandom random;
    private Atmosphere.Sample here;
    private Atmosphere.Precipitation forcedType; // scripted screenshots: fixed weather
    private float forcedStorm;

    public Weather(World world, Listener listener) {
        this.world = world;
        this.listener = listener;
        this.random = new SplittableRandom(world.seed ^ 0x5EA7_4E5L);
        TerrainGenerator gen = world.generator;
        atmosphere = new Atmosphere((wx, wz) -> {
            // Four columns per cell: mean height, how much is open water or forest, climate.
            float h = 0, water = 0, forest = 0;
            for (int k = 0; k < 4; k++) {
                int x = wx - 8 + (k & 1) * 16, z = wz - 8 + (k >> 1) * 16;
                Landscape.Column c = gen.column(x, z);
                h += Math.max(0, c.height() - TerrainGenerator.SEA_LEVEL);
                if (c.height() < TerrainGenerator.SEA_LEVEL) water += 1;
                byte b = c.biome();
                if (b == Landscape.FOREST || b == Landscape.BIRCH_FOREST || b == Landscape.DARK_FOREST
                    || b == Landscape.TAIGA || b == Landscape.SNOWY_TAIGA) forest += 1;
            }
            return new float[] {h / 4, water / 4, forest / 4, -14 + 40 * gen.temperature(wx, wz), gen.downfall(wx, wz)};
        }, world.seed);
    }

    /** Weather where the player stands (refreshed every second). */
    public Atmosphere.Sample here() {
        return here;
    }

    /** Weather at any point (height of the ground there for the altitude correction). */
    public Atmosphere.Sample at(double x, double z, int ground) {
        Atmosphere.Sample s = atmosphere.sample(x, z, ground, TerrainGenerator.SEA_LEVEL);
        if (forcedType == null) return s;
        if (forcedType == Atmosphere.Precipitation.NONE) // clear sky
            return new Atmosphere.Sample(s.temperature(), s.humidity(), 0.5f, 0, 0, forcedType, s.windX(), s.windZ(), 0,
                s.cloudBase(), 0, 0.5f);
        return new Atmosphere.Sample(s.temperature(), s.humidity(), 1, Math.max(1.3f, s.cloud()), 0.05f, forcedType,
            s.windX(), s.windZ(), Math.max(s.fog(), 0.3f), Math.min(s.cloudBase(), TerrainGenerator.SEA_LEVEL + 70),
            forcedStorm, -0.6f);
    }

    /** Fixes the weather for reproducible shots: clear, rain, sleet, snow, hail or storm. */
    public void force(String name) {
        switch (name) {
            case "clear" -> forcedType = Atmosphere.Precipitation.NONE;
            case "rain" -> forcedType = Atmosphere.Precipitation.RAIN;
            case "sleet" -> forcedType = Atmosphere.Precipitation.SLEET;
            case "snow" -> forcedType = Atmosphere.Precipitation.SNOW;
            case "hail" -> { forcedType = Atmosphere.Precipitation.HAIL; forcedStorm = 0.8f; }
            case "storm" -> { forcedType = Atmosphere.Precipitation.RAIN; forcedStorm = 0.9f; }
            default -> throw new IllegalArgumentException("unknown weather: " + name);
        }
    }

    /** Re-centres on the player, advances the air one second and touches a few surface blocks. */
    public void second(double px, double py, double pz, float sun, boolean effects) {
        atmosphere.recenter(px, pz);
        atmosphere.step(1, sun);
        here = at(px, pz, (int) Math.floor(py));
        if (!effects) return;
        for (int k = 0; k < COLUMNS; k++) {
            int x = (int) Math.floor(px) + random.nextInt(-REACH, REACH + 1);
            int z = (int) Math.floor(pz) + random.nextInt(-REACH, REACH + 1);
            surface(x, z);
        }
    }

    private void surface(int x, int z) {
        if (world.chunk(x >> 4, z >> 4) == null) return;
        int y = top(x, z);
        if (y == Integer.MIN_VALUE) return;
        byte b = world.get(x, y, z);
        Atmosphere.Sample s = at(x + 0.5, z + 0.5, y);
        boolean snowing = s.type() == Atmosphere.Precipitation.SNOW;
        byte to = b;
        if (b == Blocks.GRASS && snowing && s.temperature() < 0) to = Blocks.SNOW_BLOCK;
        else if (b == Blocks.WATER && s.temperature() < -3 && world.get(x, y + 1, z) == Blocks.AIR) to = Blocks.ICE;
        else if (b == Blocks.ICE && s.temperature() > 2) to = Blocks.WATER;
        else if (b == Blocks.SNOW_BLOCK && s.temperature() > 3 && world.get(x, y - 1, z) == Blocks.DIRT) to = Blocks.GRASS;
        if (to != b && world.set(x, y, z, to)) listener.changed(x, y, z);
    }

    /** Highest non-air block of a column (what the sky sees), searched down from above the terrain. */
    private int top(int x, int z) {
        int from = Math.min(Chunk.MAX_Y - 1, world.generator.height(x, z) + 40);
        for (int y = from; y > Chunk.MIN_Y; y--) {
            byte b = world.get(x, y, z);
            if (Blocks.isSolid(b) || Blocks.isFluid(b)) return y; // plants let snow through
        }
        return Integer.MIN_VALUE;
    }
}
