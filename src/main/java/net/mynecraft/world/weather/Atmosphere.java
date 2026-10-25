package net.mynecraft.world.weather;

import net.mynecraft.world.Noise;

/**
 * A simplified but physically motivated weather model on a grid of {@link #CELL}-block cells that
 * follows the player. Each cell holds air temperature (deg C), water vapour and cloud water
 * (g/kg) and is advanced once per {@link #step} (one game second):
 * <ol>
 *   <li>wind: a prevailing direction that slowly turns over time, plus eddies from a stream
 *       function (curl of noise, so it swirls without piling air up);</li>
 *   <li>pressure systems drifting with the wind: in lows the air rises over wide areas (fronts,
 *       widespread clouds and precipitation even over flat land), in highs it sinks (clear skies);</li>
 *   <li>advection: vapour, clouds and temperature anomalies are carried downwind (semi-Lagrangian);</li>
 *   <li>temperature relaxes towards the surface: climate, -6.5 deg C per km of altitude, a day/night
 *       cycle that clouds damp;</li>
 *   <li>evaporation over water (stronger when warm, dry and windy) and a little over forests;</li>
 *   <li>condensation above saturation (Clausius-Clapeyron-like curve); air forced up slopes by
 *       the wind cools (orographic lift: wet windward sides, dry rain shadows) and afternoon
 *       heating adds convection (showers, storms);</li>
 *   <li>precipitation when clouds are loaded: rain, sleet, snow or hail by temperature and
 *       storm strength;</li>
 *   <li>fog where air at the ground is near saturation and calm.</li>
 * </ol>
 * The terrain is read through {@link Terrain} so the model can be tested on synthetic worlds.
 */
public final class Atmosphere {
    public static final int CELL = 32, SIZE = 48;
    private static final float LAPSE = 0.0065f;      // deg C per block (6.5 per km)
    private static final float RAIN_THRESHOLD = 0.6f; // cloud water (g/kg) above which warm clouds precipitate

    /** What the model needs from the world for one cell (sampled once when the cell appears). */
    public interface Terrain {
        /** {height above sea level (blocks), water fraction 0..1, forest fraction 0..1, climate temperature deg C at sea level, climate humidity 0..1}. */
        float[] sample(int wx, int wz);
    }

    public enum Precipitation { NONE, RAIN, SLEET, SNOW, HAIL }

    /** Weather at a point, interpolated between cells. */
    public record Sample(float temperature, float humidity, float relativeHumidity, float cloud, float precipitation,
                         Precipitation type, float windX, float windZ, float fog, int cloudBase, float storm,
                         float pressure) {
        /** Sea-level pressure in hPa for display (lows ~990, highs ~1035). */
        public int hectopascals() { return Math.round(1013 + pressure * 22); }
    }

    private final Terrain terrain;
    private final Noise windNoise, eddies, pressureNoise;
    private final float[] pressure = new float[SIZE * SIZE]; // -1 deep low .. +1 strong high
    private double driftX, driftZ;                            // how far the pressure pattern has travelled
    private final int n = SIZE;
    // Cell fields (index z * SIZE + x), window origin in cell coordinates.
    private float[] temp = new float[n * n], vapour = new float[n * n], cloud = new float[n * n];
    private final float[] precip = new float[n * n], storm = new float[n * n], windX = new float[n * n], windZ = new float[n * n];
    private float[] elevation = new float[n * n], water = new float[n * n], forest = new float[n * n],
        climateT = new float[n * n], climateH = new float[n * n];
    private float[] scratch = new float[n * n];
    private int originX = Integer.MIN_VALUE, originZ;
    private long steps;
    private double time; // seconds of game time, for the slow turning of the wind

    public Atmosphere(Terrain terrain, long seed) {
        this.terrain = terrain;
        this.windNoise = new Noise(seed + 1001);
        this.eddies = new Noise(seed + 1002);
        this.pressureNoise = new Noise(seed + 1003);
    }

    /** Saturation vapour content (g/kg) at a temperature: about 3.8 at 0 deg C, doubling every ~10 deg C. */
    public static float saturation(float t) {
        return 3.8f * (float) Math.exp(0.0705f * t);
    }

    /** Dew point (deg C) of air holding {@code q} g/kg. */
    public static float dewPoint(float q) {
        return (float) Math.log(Math.max(1e-3f, q) / 3.8f) / 0.0705f;
    }

    // ---- window ----

    /** Keeps the grid centred on the player; new cells start from their climate (and are spun up). */
    public void recenter(double px, double pz) {
        int cx = Math.floorDiv((int) Math.floor(px), CELL) - n / 2, cz = Math.floorDiv((int) Math.floor(pz), CELL) - n / 2;
        if (originX == Integer.MIN_VALUE) {
            originX = cx;
            originZ = cz;
            for (int i = 0; i < n * n; i++) initCell(i);
            for (int i = 0; i < 300; i++) step(0.5); // spin-up: let clouds and fronts form
            return;
        }
        int dx = cx - originX, dz = cz - originZ;
        if (Math.abs(dx) < n / 6 && Math.abs(dz) < n / 6) return; // move the window in jumps, not every cell
        float[][] fields = {temp, vapour, cloud, elevation, water, forest, climateT, climateH};
        boolean[] keep = new boolean[n * n];
        for (int k = 0; k < fields.length; k++) {
            float[] f = fields[k], moved = new float[n * n];
            for (int z = 0; z < n; z++)
                for (int x = 0; x < n; x++) {
                    int sx = x + dx, sz = z + dz;
                    if (sx >= 0 && sx < n && sz >= 0 && sz < n) {
                        moved[z * n + x] = f[sz * n + sx];
                        keep[z * n + x] = true;
                    }
                }
            System.arraycopy(moved, 0, f, 0, n * n);
        }
        originX = cx;
        originZ = cz;
        for (int i = 0; i < n * n; i++) if (!keep[i]) initCell(i);
    }

    private void initCell(int i) {
        int wx = (originX + i % n) * CELL + CELL / 2, wz = (originZ + i / n) * CELL + CELL / 2;
        float[] s = terrain.sample(wx, wz);
        elevation[i] = s[0];
        water[i] = s[1];
        forest[i] = s[2];
        climateT[i] = s[3];
        climateH[i] = s[4];
        temp[i] = surfaceTemperature(i, 0.5f);
        vapour[i] = saturation(temp[i]) * (0.45f + 0.4f * climateH[i]);
        cloud[i] = 0;
    }

    /** Temperature the ground pulls the air towards: climate, altitude, and the sun (damped by clouds). */
    private float surfaceTemperature(int i, float sun) {
        float cover = Math.min(1, cloud[i] / 1.2f);
        float diurnal = (sun - 0.5f) * 10 * (1 - 0.6f * cover); // about -5..+5 deg C, less under clouds
        float t = climateT[i] - LAPSE * Math.max(0, elevation[i]) + diurnal;
        return water[i] > 0.5f ? 0.7f * t + 0.3f * climateT[i] : t; // water damps the daily swing
    }

    // ---- dynamics ----

    /** Prevailing wind (blocks per second) turning slowly with time, plus swirling eddies. */
    private void computeWind() {
        float angle = (float) (windNoise.noise((float) (time / 900), 0.5f) * Math.PI * 2.2);
        float speed = 3 + 4 * (0.5f + 0.5f * windNoise.noise((float) (time / 600), 7.3f));
        float bx = (float) Math.cos(angle) * speed, bz = (float) Math.sin(angle) * speed;
        float tt = (float) (time / 400);
        float e = 0.02f; // finite difference step (in noise units) for the stream function's curl
        for (int z = 0; z < n; z++)
            for (int x = 0; x < n; x++) {
                float wx = (originX + x) / 12f, wz = (originZ + z) / 12f;
                // Curl of a stream function psi: (dpsi/dz, -dpsi/dx) has no divergence.
                float dpz = (eddies.noise3(wx, tt, wz + e) - eddies.noise3(wx, tt, wz - e)) / (2 * e);
                float dpx = (eddies.noise3(wx + e, tt, wz) - eddies.noise3(wx - e, tt, wz)) / (2 * e);
                windX[z * n + x] = bx + dpz * 1.2f;
                windZ[z * n + x] = bz - dpx * 1.2f;
            }
    }

    /**
     * Advances one step of {@code seconds} game seconds. {@code sun} is 0 at night, 1 at noon.
     */
    public void step(double seconds) {
        step(seconds, 0.5f);
    }

    public void step(double seconds, float sun) {
        time += seconds;
        steps++;
        computeWind();
        float dt = (float) seconds;
        computePressure(dt);
        temp = advect(temp, dt);
        vapour = advect(vapour, dt);
        cloud = advect(cloud, dt);
        float rate = Math.min(1, dt / 60f); // physics rates are "per minute"
        for (int z = 0; z < n; z++) {
            for (int x = 0; x < n; x++) {
                int i = z * n + x;
                float ts = surfaceTemperature(i, sun);
                temp[i] += (ts - temp[i]) * 0.15f * rate;
                // Evaporation: water surfaces, warmer, drier and windier = faster; forests transpire a little.
                float qs = saturation(temp[i]);
                float wind = (float) Math.hypot(windX[i], windZ[i]);
                float deficit = Math.max(0, qs - vapour[i]);
                vapour[i] += deficit * (water[i] * 0.06f * (1 + wind * 0.08f) + forest[i] * 0.015f) * rate;
                // Lift: wind blowing up the terrain slope, plus afternoon convection over warm land.
                float slope = (windX[i] * gradX(elevation, x, z) + windZ[i] * gradZ(elevation, x, z)) / CELL;
                float lift = Math.max(0, slope) * 60;                                   // deg C of cooling
                float convection = Math.max(0, temp[i] - 14) * Math.max(0, sun - 0.6f) * (1 - water[i]) * 1.5f;
                storm[i] = Math.max(0, Math.min(1, convection / 6));
                // Large-scale ascent in lows cools the air; subsidence in highs warms and dries it.
                float synoptic = pressure[i] < 0 ? -pressure[i] * 7 : -pressure[i] * 3;
                float tEff = temp[i] - Math.min(14, lift + convection + synoptic);
                float qsEff = saturation(tEff);
                if (vapour[i] > qsEff) {
                    float c = (vapour[i] - qsEff) * 0.5f * rate * 4;
                    vapour[i] -= c;
                    cloud[i] += c;
                } else if (cloud[i] > 0) { // clouds evaporate in dry air
                    float e = Math.min(cloud[i], (qsEff - vapour[i]) * 0.08f * rate);
                    cloud[i] -= e;
                    vapour[i] += e;
                }
                // Precipitation from loaded clouds, faster in storms.
                // Cold clouds precipitate with less water (ice crystals grow at the droplets' expense).
                float threshold = RAIN_THRESHOLD * (0.25f + 0.75f * smooth(-10, 12, tEff));
                float p = Math.max(0, cloud[i] - threshold) * (0.25f + storm[i]) * rate * 4;
                cloud[i] -= p;
                precip[i] = p / Math.max(rate, 1e-3f);
                // Slow pull back towards the local climate keeps the model from drifting.
                float target = saturation(climateT[i] - LAPSE * Math.max(0, elevation[i])) * (0.4f + 0.45f * climateH[i]);
                vapour[i] += (target - vapour[i]) * 0.01f * rate;
                cloud[i] *= 1 - 0.002f * rate;
            }
        }
        diffuse(vapour);
        diffuse(cloud);
    }

    /** Pressure pattern: slow noise carried along by the mean wind, evolving over tens of minutes. */
    private void computePressure(float dt) {
        float mx = 0, mz = 0;
        for (int i = 0; i < n * n; i++) { mx += windX[i]; mz += windZ[i]; }
        driftX += mx / (n * n) * dt;
        driftZ += mz / (n * n) * dt;
        float tt = (float) (time / 1500);
        for (int z = 0; z < n; z++)
            for (int x = 0; x < n; x++) {
                double wx = (originX + x) * CELL - driftX, wz = (originZ + z) * CELL - driftZ;
                pressure[z * n + x] = Math.max(-1, Math.min(1, pressureNoise.noise3((float) (wx / 900), tt, (float) (wz / 900)) * 1.8f));
            }
    }

    private float gradX(float[] f, int x, int z) {
        int a = Math.max(0, x - 1), b = Math.min(n - 1, x + 1);
        return (f[z * n + b] - f[z * n + a]) / (b - a);
    }

    private float gradZ(float[] f, int x, int z) {
        int a = Math.max(0, z - 1), b = Math.min(n - 1, z + 1);
        return (f[b * n + x] - f[a * n + x]) / (b - a);
    }

    /** Semi-Lagrangian advection: each cell takes the value found upwind (bilinear). */
    private float[] advect(float[] f, float dt) {
        float[] out = scratch;
        for (int z = 0; z < n; z++)
            for (int x = 0; x < n; x++) {
                int i = z * n + x;
                float sx = x - windX[i] * dt / CELL, sz = z - windZ[i] * dt / CELL;
                out[i] = bilinear(f, sx, sz);
            }
        scratch = f;
        return out;
    }

    private float bilinear(float[] f, float x, float z) {
        x = Math.max(0, Math.min(n - 1.001f, x));
        z = Math.max(0, Math.min(n - 1.001f, z));
        int x0 = (int) x, z0 = (int) z;
        float fx = x - x0, fz = z - z0;
        float a = f[z0 * n + x0] + (f[z0 * n + x0 + 1] - f[z0 * n + x0]) * fx;
        float b = f[(z0 + 1) * n + x0] + (f[(z0 + 1) * n + x0 + 1] - f[(z0 + 1) * n + x0]) * fx;
        return a + (b - a) * fz;
    }

    private void diffuse(float[] f) {
        float[] out = scratch;
        for (int z = 0; z < n; z++)
            for (int x = 0; x < n; x++) {
                int i = z * n + x;
                float sum = f[z * n + Math.max(0, x - 1)] + f[z * n + Math.min(n - 1, x + 1)]
                    + f[Math.max(0, z - 1) * n + x] + f[Math.min(n - 1, z + 1) * n + x];
                out[i] = f[i] * 0.9f + sum * 0.025f;
            }
        System.arraycopy(out, 0, f, 0, n * n);
    }

    // ---- queries ----

    /** Weather at a world position (blocks); {@code groundHeight} is the terrain height there. */
    public Sample sample(double wx, double wz, int groundHeight, int seaLevel) {
        float x = (float) (wx / CELL - originX - 0.5), z = (float) (wz / CELL - originZ - 0.5);
        float t = bilinear(temp, x, z) - LAPSE * Math.max(0, groundHeight - seaLevel - bilinear(elevation, x, z));
        float q = bilinear(vapour, x, z), c = bilinear(cloud, x, z), p = bilinear(precip, x, z), s = bilinear(storm, x, z);
        float u = bilinear(windX, x, z), v = bilinear(windZ, x, z);
        float rh = Math.min(1.2f, q / saturation(t));
        float calm = 1 - Math.min(1, (float) Math.hypot(u, v) / 8);
        float fog = smooth(0.9f, 1.05f, rh) * (0.4f + 0.6f * calm);
        // Cloud base: about 125 m (blocks) above ground per degree between air and dew point.
        int base = seaLevel + Math.round(Math.max(20, Math.min(160, 18 * (t - dewPoint(q)))))
            + Math.round(Math.max(0, bilinear(elevation, x, z)));
        Precipitation type = Precipitation.NONE;
        if (p > 0.004f) {
            if (s > 0.6f && t > 10 && p > 0.05f) type = Precipitation.HAIL;
            else if (t < -1) type = Precipitation.SNOW;
            else if (t < 2) type = Precipitation.SLEET;
            else type = Precipitation.RAIN;
        }
        if (groundHeight >= base - 4 && c > 0.15f) fog = Math.max(fog, Math.min(1, c)); // standing inside the cloud
        return new Sample(t, q, rh, c, p, type, u, v, fog, base, s, bilinear(pressure, x, z));
    }

    private static float smooth(float e0, float e1, float v) {
        float k = Math.max(0, Math.min(1, (v - e0) / (e1 - e0)));
        return k * k * (3 - 2 * k);
    }

    /** Cloud water of cell (i, j) of the window (for drawing clouds) and the window origin. */
    public float cloudAt(int i, int j) { return cloud[j * n + i]; }

    public float precipitationAt(int i, int j) { return precip[j * n + i]; }

    public float stormAt(int i, int j) { return storm[j * n + i]; }

    public int originX() { return originX; }

    public int originZ() { return originZ; }

    public long steps() { return steps; }
}
