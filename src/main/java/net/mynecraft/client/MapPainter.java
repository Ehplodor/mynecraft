package net.mynecraft.client;

import net.mynecraft.world.ExploredMap;
import net.mynecraft.world.Hydrology;
import net.mynecraft.world.Landscape;
import net.mynecraft.world.TerrainGenerator;
import net.mynecraft.world.weather.Atmosphere;
import net.mynecraft.world.weather.Weather;

import java.nio.ByteBuffer;
import java.util.function.BooleanSupplier;

/**
 * Map pictures for the minimap and the world map: the ground under a rectangle is read from the
 * generator's columns (so it covers unexplored land too, computed on a background thread), then
 * coloured for one layer, with the live weather, on the main thread. Land never seen is dimmed.
 */
final class MapPainter {
    enum Layer {
        RELIEF("Relief"), BIOMES("Biomes"), WATERS("Eaux"), CLOUDS("Nuages, pluie"), WEATHER("Température");

        final String label;

        Layer(String label) { this.label = label; }

        Layer next() { return values()[(ordinal() + 1) % values().length]; }
    }

    private static final int[] BIOME_COLORS = {0x1E3C8C, 0x96B4DC, 0xE6D796, 0x3264C8, 0xAAC8E6, 0x3C78D2,
        0x78BE50, 0xEBF0F5, 0x328232, 0x6EAA5A, 0x19501E, 0x3C6E50, 0xC8DCD7, 0x828282, 0xFAFAFF, 0xAAD2F0,
        0xA0965A, 0xBEB45A};

    /** The ground of a w x h picture centred on (cx, cz), {@code scale} blocks per pixel. */
    record Ground(int cx, int cz, int w, int h, int scale, short[] height, byte[] biome, short[] water, float[] flow,
                  float[] temp) {
        int worldX(int i) { return cx + (i - w / 2) * scale; }

        int worldZ(int j) { return cz + (j - h / 2) * scale; }
    }

    private MapPainter() {}

    /** Reads the ground (slow: generator columns); returns null if cancelled on the way. */
    static Ground compute(TerrainGenerator generator, int cx, int cz, int w, int h, int scale, BooleanSupplier cancelled) {
        int n = w * h;
        short[] height = new short[n], water = new short[n];
        byte[] biome = new byte[n];
        float[] flow = new float[n], temp = new float[n];
        Hydrology hydrology = generator.landscape().hydrology();
        for (int j = 0; j < h; j++) {
            if (cancelled.getAsBoolean()) return null;
            for (int i = 0; i < w; i++) {
                int wx = cx + (i - w / 2) * scale, wz = cz + (j - h / 2) * scale, k = j * w + i;
                Landscape.Column c = generator.column(wx, wz);
                height[k] = (short) c.height();
                biome[k] = c.biome();
                water[k] = (short) Math.max(c.water(), TerrainGenerator.SEA_LEVEL);
                flow[k] = c.river() > 0 ? hydrology.flow(wx, wz) : 0;
                temp[k] = c.temperature();
            }
        }
        return new Ground(cx, cz, w, h, scale, height, biome, water, flow, temp);
    }

    /** Colours the ground into RGBA pixels for a layer; land outside {@code explored} is dimmed. */
    static void colour(Ground g, Layer layer, Weather weather, ExploredMap explored, ByteBuffer out) {
        out.clear();
        for (int j = 0; j < g.h; j++)
            for (int i = 0; i < g.w; i++) {
                int k = j * g.w + i, h = g.height[k];
                boolean wet = g.water[k] > h;
                // Hill shading: light from the north-west, stronger when zoomed in.
                int hw = g.height[j * g.w + Math.max(0, i - 1)], hn = g.height[Math.max(0, j - 1) * g.w + i];
                float shade = Math.max(0.55f, Math.min(1.35f, 1 + ((h - hw) + (h - hn)) * 0.42f / (g.scale + 3)));
                int rgb = switch (layer) {
                    case BIOMES -> wet && g.flow[k] > 0 ? 0x3C8CFF : BIOME_COLORS[g.biome[k]];
                    case WATERS -> waters(g, k, h, wet);
                    case WEATHER -> mix(relief(g, k, h, wet), temperature(weather, g, i, j, h), 0.65f);
                    default -> relief(g, k, h, wet);
                };
                if (layer != Layer.BIOMES && !wet) rgb = scale(rgb, shade);
                if (layer == Layer.CLOUDS) rgb = clouds(weather, g, i, j, h, scale(rgb, 0.8f));
                if (explored != null && !explored.seen(g.worldX(i), g.worldZ(j))) {
                    // Never seen: greyed and darkened, the shape still readable.
                    int lum = (((rgb >> 16) & 255) * 3 + ((rgb >> 8) & 255) * 5 + (rgb & 255) * 2) / 10;
                    rgb = mix(lum << 16 | lum << 8 | lum, 0x1C2026, 0.55f);
                }
                out.put((byte) (rgb >> 16)).put((byte) (rgb >> 8)).put((byte) rgb).put((byte) 255);
            }
        out.flip();
    }

    private static int relief(Ground g, int k, int h, boolean wet) {
        if (wet) {
            int depth = g.water[k] - h;
            if (g.flow[k] > 0) return 0x3C82E6;
            return mix(0x3C78D2, 0x0F2864, Math.min(1, depth / 30f));
        }
        byte b = g.biome[k];
        if (b == Landscape.BEACH || b == Landscape.DRY_PLAINS) return b == Landscape.BEACH ? 0xE6D796 : 0xC8BE6E;
        if (h > TerrainGenerator.ROCK_LINE || b == Landscape.SNOWY_PEAKS || b == Landscape.GLACIER || b == Landscape.SNOWY_PLAINS)
            return b == Landscape.STONY_PEAKS ? 0x8C8C8C : 0xF0F4F8;
        float t = g.temp[k];
        int grass = mix(0x5A9650, 0x82B446, Math.max(0, Math.min(1, t)));
        if (b == Landscape.FOREST || b == Landscape.BIRCH_FOREST || b == Landscape.DARK_FOREST || b == Landscape.TAIGA)
            grass = scale(grass, 0.72f);
        return mix(grass, 0xB4AA82, Math.max(0, Math.min(1, (h - 100) / 40f)));
    }

    private static int waters(Ground g, int k, int h, boolean wet) {
        int grey = 0x50 + Math.max(0, Math.min(0x70, (h - 50)));
        int land = grey << 16 | grey << 8 | grey;
        if (!wet) return land;
        if (g.flow[k] > 0) { // rivers: the more flow, the deeper the blue
            float f = (float) Math.min(1, Math.log10(g.flow[k] / 400f + 1) / 2);
            return mix(0x82C8FF, 0x0A3CC8, f);
        }
        return g.water[k] > TerrainGenerator.SEA_LEVEL ? 0x2896DC : 0x14326E; // lakes above the sea
    }

    private static int temperature(Weather weather, Ground g, int i, int j, int h) {
        Atmosphere.Sample s = weather.at(g.worldX(i), g.worldZ(j), Math.max(h, TerrainGenerator.SEA_LEVEL));
        float t = Math.max(0, Math.min(1, (s.temperature() + 15) / 50f)); // -15 .. 35 deg C
        int[] ramp = {0x3050C8, 0x50B4E6, 0x64C864, 0xF0DC50, 0xE65028};
        float x = t * (ramp.length - 1);
        int a = Math.min(ramp.length - 2, (int) x);
        return mix(ramp[a], ramp[a + 1], x - a);
    }

    private static int clouds(Weather weather, Ground g, int i, int j, int h, int under) {
        Atmosphere.Sample s = weather.at(g.worldX(i), g.worldZ(j), Math.max(h, TerrainGenerator.SEA_LEVEL));
        int c = under;
        if (s.type() != Atmosphere.Precipitation.NONE) {
            int tint = switch (s.type()) {
                case SNOW -> 0xFFFFFF;
                case SLEET -> 0x96E6F0;
                case HAIL -> 0xE650E6;
                default -> 0x2864FF;
            };
            c = mix(c, tint, Math.min(0.75f, 0.25f + s.precipitation() * 8));
        }
        float cover = Math.min(0.85f, s.cloud() / 1.3f);
        int cloud = s.storm() > 0.4f ? 0x5A5A64 : 0xF0F0F0;
        if (s.type() == Atmosphere.Precipitation.NONE || cover < 0.3f) c = mix(c, cloud, cover);
        return c;
    }

    static int mix(int a, int b, float t) {
        int r = (int) (((a >> 16) & 255) + (((b >> 16) & 255) - ((a >> 16) & 255)) * t);
        int g = (int) (((a >> 8) & 255) + (((b >> 8) & 255) - ((a >> 8) & 255)) * t);
        int bl = (int) ((a & 255) + ((b & 255) - (a & 255)) * t);
        return r << 16 | g << 8 | bl;
    }

    static int scale(int c, float f) {
        int r = Math.min(255, (int) (((c >> 16) & 255) * f)), g = Math.min(255, (int) (((c >> 8) & 255) * f)),
            b = Math.min(255, (int) ((c & 255) * f));
        return r << 16 | g << 8 | b;
    }
}
