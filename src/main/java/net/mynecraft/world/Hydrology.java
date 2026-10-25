package net.mynecraft.world;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Rivers carved by rolling water: many droplets ("boulets") start at springs placed procedurally
 * in high, wet ground and roll down the terrain with inertia. Fast droplets on steep slopes pick up
 * sediment and dig; slow ones drop it (fans at the foot of mountains, deltas at the mouth); a
 * droplet caught in a hollow fills it until it can spill out, so none stops before the sea or a
 * lake. Where many droplets pass, their paths add up to a flow: streams join into rivers, and the
 * bed is cut wider and deeper with the flow, narrower and deeper where the slope is steep.
 * <p>
 * The world is infinite, so the work is done per tile of {@link #TILE} blocks on a {@link #CELL}-block
 * grid, simulated with a wide margin around the tile (rivers coming from outside are mostly there)
 * and cached. Springs are tied to world positions, so neighbouring tiles agree in their overlap.
 * Results are read per column through {@link #carve} and {@link #water}.
 */
public final class Hydrology {
    public static final int CELL = 4, TILE = 1024, MARGIN = 512;
    static final int N = (TILE + 2 * MARGIN) / CELL;   // grid cells per side, margin included
    private static final int CORE0 = MARGIN / CELL, CORE = TILE / CELL;
    /** Width (blocks) of the band along tile borders where the hillside erosion of both tiles is blended. */
    static final int BAND = 128;

    // Droplet physics (heights in blocks, distances in cells).
    private static final float INERTIA = 0.35f, CAPACITY = 6, MIN_SLOPE = 0.02f, ERODE = 0.25f, DEPOSIT = 0.2f,
        GRAVITY = 4, EVAPORATE = 0.0015f, MAX_DIG = 30, MAX_FILL = 12;
    private static final int MAX_STEPS = 3000;
    private static final float NEAR_CAP = 64;
    /** Flow (drained cells, weighted by rain) from which a cell is a stream. */
    static final float STREAM_FLOW = 400;

    /** What the simulation needs from the landscape: the height before rivers, and the climate. */
    public interface Ground {
        float height(int wx, int wz);

        float downfall(int wx, int wz);
    }

    /** The result for one tile (core cells plus one cell of border for interpolation). */
    static final class Tile {
        final int x0, z0, n;         // world position of grid cell (0, 0), cells per side
        final float[] carve;         // terrain change in blocks (negative = dug)
        final float[] flow;          // accumulated flow
        final float[] level;         // water surface where there is a river, NaN elsewhere
        final float[] depth;         // bed depth below the surface
        final float[] bank;          // highest the ground may be here: gentle banks up from the nearest water
        final float[] near;          // distance to the nearest river or lake above the sea (blocks, capped)

        Tile(int x0, int z0, int n) {
            this.x0 = x0;
            this.z0 = z0;
            this.n = n;
            carve = new float[n * n];
            flow = new float[n * n];
            level = new float[n * n];
            depth = new float[n * n];
            bank = new float[n * n];
            near = new float[n * n];
        }

        /** The tile's core plus one cell all round: what columns of the tile read (1 MB, not 4). */
        // Erosion is also kept a band beyond the core, to blend with the neighbouring tiles.
        float[] carveWide;
        int wx0, wz0, wn;

        Tile crop() {
            int m = CORE + 2, o = CORE0 - 1;
            Tile c = new Tile(x0 + o * CELL, z0 + o * CELL, m);
            int b = BAND / CELL + 1, mw = CORE + 2 * b, ow = CORE0 - b;
            c.carveWide = new float[mw * mw];
            c.wn = mw;
            c.wx0 = x0 + ow * CELL;
            c.wz0 = z0 + ow * CELL;
            for (int j = 0; j < mw; j++) System.arraycopy(carve, (j + ow) * n + ow, c.carveWide, j * mw, mw);
            for (int j = 0; j < m; j++) {
                int from = (j + o) * n + o;
                System.arraycopy(carve, from, c.carve, j * m, m);
                System.arraycopy(flow, from, c.flow, j * m, m);
                System.arraycopy(level, from, c.level, j * m, m);
                System.arraycopy(depth, from, c.depth, j * m, m);
                System.arraycopy(bank, from, c.bank, j * m, m);
                System.arraycopy(near, from, c.near, j * m, m);
            }
            return c;
        }
    }

    private final Ground ground;
    private final long seed;
    private final int sea;
    private final ConcurrentHashMap<Long, Tile> tiles = new ConcurrentHashMap<>();
    /** Progress, for the loading screen: tiles being computed and droplets rolled so far. */
    public final AtomicInteger computing = new AtomicInteger();
    public final AtomicLong droplets = new AtomicLong(), dropletsPlanned = new AtomicLong();

    public Hydrology(Ground ground, long seed, int seaLevel) {
        this.ground = ground;
        this.seed = seed;
        this.sea = seaLevel;
    }

    private Tile tile(int wx, int wz) {
        int tx = Math.floorDiv(wx, TILE), tz = Math.floorDiv(wz, TILE);
        Tile t = tiles.get(World.key(tx, tz));
        if (t != null) return t;
        if (tiles.size() > 48) tiles.clear(); // far tiles: recomputed (deterministically) if ever needed
        return tiles.computeIfAbsent(World.key(tx, tz), k -> compute(tx, tz));
    }

    /** Terrain change (blocks) at a column. */
    public float carve(int wx, int wz) {
        // Each tile eroded its hillsides on its own: near a tile border, the erosion of the tiles on
        // both sides is cross-faded (half and half on the border), so the ground has no seam.
        int tx = Math.floorDiv(wx, TILE), tz = Math.floorDiv(wz, TILE);
        float lx = wx - tx * TILE + 0.5f, lz = wz - tz * TILE + 0.5f;
        int nx = lx < BAND ? -1 : lx > TILE - BAND ? 1 : 0, nz = lz < BAND ? -1 : lz > TILE - BAND ? 1 : 0;
        float ax = nx == 0 ? 0 : 0.5f * (1 - smooth(Math.min(lx, TILE - lx) / BAND));
        float az = nz == 0 ? 0 : 0.5f * (1 - smooth(Math.min(lz, TILE - lz) / BAND));
        float sum = 0;
        for (int k = 0; k < 4; k++) {
            int ox = (k & 1) == 0 ? 0 : nx, oz = (k >> 1) == 0 ? 0 : nz;
            float w = (ox == 0 ? 1 - ax : ax) * (oz == 0 ? 1 - az : az);
            if (w <= 0) continue;
            Tile t = tile((tx + ox) * TILE, (tz + oz) * TILE);
            sum += w * wide(t, wx, wz);
        }
        return sum;
    }

    private static float smooth(float v) {
        v = Math.max(0, Math.min(1, v));
        return v * v * (3 - 2 * v);
    }

    private static float wide(Tile t, int wx, int wz) {
        float gx = (wx - t.wx0) / (float) CELL - 0.5f, gz = (wz - t.wz0) / (float) CELL - 0.5f;
        int i = (int) Math.floor(gx), j = (int) Math.floor(gz), n = t.wn;
        float fx = gx - i, fz = gz - j;
        float[] f = t.carveWide;
        float a = f[j * n + i] + (f[j * n + i + 1] - f[j * n + i]) * fx;
        float b = f[(j + 1) * n + i] + (f[(j + 1) * n + i + 1] - f[(j + 1) * n + i]) * fx;
        return a + (b - a) * fz;
    }

    /**
     * Highest the ground may rise at a column near water: one block above the nearest river or
     * lake, then a gentle slope away from it (a valley floor you can climb out of), wider along big
     * rivers. Very high far from any water.
     */
    public float waterDistance(int wx, int wz) {
        Tile t = tile(wx, wz);
        return bilinear(t, t.near, wx, wz);
    }

    public float bank(int wx, int wz) {
        Tile t = tile(wx, wz);
        return bilinear(t, t.bank, wx, wz);
    }

    /** True if a river or lake above the sea has a cell in this block rectangle (cheap: cell lookups). */
    public boolean riverNear(int x0, int z0, int x1, int z1) {
        for (int z = Math.floorDiv(z0, CELL) * CELL - CELL; z <= z1 + CELL; z += CELL)
            for (int x = Math.floorDiv(x0, CELL) * CELL - CELL; x <= x1 + CELL; x += CELL) {
                Tile t = tile(x, z);
                int i = Math.floorDiv(x - t.x0, CELL), j = Math.floorDiv(z - t.z0, CELL);
                if (i >= 0 && j >= 0 && i < t.n && j < t.n && !Float.isNaN(t.level[j * t.n + i])) return true;
            }
        return false;
    }

    /** Flow at a column (0 on dry hillsides). */
    public float flow(int wx, int wz) {
        Tile t = tile(wx, wz);
        return bilinear(t, t.flow, wx, wz);
    }

    /**
     * River at a column: {water surface, bed depth} or null. The surface comes from the nearest
     * river cells, so it is flat across the river and only steps down along it.
     */
    public float[] water(int wx, int wz) {
        Tile t = tile(wx, wz);
        float gx = (wx - t.x0) / (float) CELL - 0.5f, gz = (wz - t.z0) / (float) CELL - 0.5f;
        int i = (int) Math.floor(gx), j = (int) Math.floor(gz);
        float fx = gx - i, fz = gz - j;
        float wsum = 0, d = 0, lvl = Float.MAX_VALUE;
        for (int k = 0; k < 4; k++) {
            int ii = i + (k & 1), jj = j + (k >> 1);
            float w = ((k & 1) == 0 ? 1 - fx : fx) * ((k >> 1) == 0 ? 1 - fz : fz);
            float l = t.level[jj * t.n + ii];
            if (Float.isNaN(l)) continue;
            wsum += w;
            d += w * t.depth[jj * t.n + ii];
            lvl = Math.min(lvl, l);
        }
        if (wsum < 0.5f) return null; // outside the river's edge
        return new float[] {lvl, d / wsum};
    }

    private static float bilinear(Tile t, float[] f, int wx, int wz) {
        float gx = (wx - t.x0) / (float) CELL - 0.5f, gz = (wz - t.z0) / (float) CELL - 0.5f;
        int i = (int) Math.floor(gx), j = (int) Math.floor(gz);
        float fx = gx - i, fz = gz - j;
        int n = t.n;
        float a = f[j * n + i] + (f[j * n + i + 1] - f[j * n + i]) * fx;
        float b = f[(j + 1) * n + i] + (f[(j + 1) * n + i + 1] - f[(j + 1) * n + i]) * fx;
        return a + (b - a) * fz;
    }

    private long hash(long x, long z) {
        long h = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL) ^ 0x4B1D0L;
        h ^= h >>> 31;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 29;
        return h;
    }

    // ---- simulation ----

    Tile compute(int tx, int tz) {
        computing.incrementAndGet();
        try {
            int x0 = tx * TILE - MARGIN, z0 = tz * TILE - MARGIN;
            Tile t = new Tile(x0, z0, N);
            float[] h = new float[N * N];
            for (int j = 0; j < N; j++)
                for (int i = 0; i < N; i++) h[j * N + i] = ground.height(x0 + i * CELL + CELL / 2, z0 + j * CELL + CELL / 2);
            float[] base = h.clone();
            // Springs: tied to world cells, denser high up and where it rains.
            int[] springs = new int[N * N];
            int count = 0;
            for (int j = 1; j < N - 1; j++)
                for (int i = 1; i < N - 1; i++) {
                    float above = h[j * N + i] - sea;
                    if (above < 6) continue;
                    long r = hash(Math.floorDiv(x0, CELL) + i, Math.floorDiv(z0, CELL) + j);
                    float p = Math.min(1, above / 90f) * (0.3f + ground.downfall(x0 + i * CELL, z0 + j * CELL)) * 0.12f;
                    if ((r >>> 40) / (float) (1L << 24) < p) springs[count++] = j * N + i;
                }
            dropletsPlanned.addAndGet(count);
            for (int s = 0; s < count; s++) {
                long r = hash(springs[s], x0 ^ (z0 * 31L));
                roll(h, t.flow, springs[s] % N + 0.5f + ((r & 255) / 256f - 0.5f), springs[s] / N + 0.5f
                    + (((r >> 8) & 255) / 256f - 0.5f));
                droplets.incrementAndGet();
            }
            for (int k = 0; k < N * N; k++) {
                t.carve[k] = Math.max(-MAX_DIG, Math.min(MAX_FILL, h[k] - base[k]));
                // Sediment dropped in the sea or a lake builds shoals, not land: it stays under water.
                if (base[k] <= sea) t.carve[k] = Math.min(t.carve[k], Math.max(0, sea - 0.5f - base[k]));
                h[k] = base[k] + t.carve[k];
            }
            rivers(t, base.clone(), base); // the network comes from the base relief: the same in every tile
            return t.crop();
        } finally {
            computing.decrementAndGet();
        }
    }

    /** One droplet from (px, pz) in cell units, down to the sea or out of the grid. */
    private void roll(float[] h, float[] flow, float px, float pz) {
        float dx = 0, dz = 0, speed = 1, water = 1, sediment = 0;
        for (int step = 0; step < MAX_STEPS; step++) {
            int i = (int) px, j = (int) pz;
            if (i < 1 || j < 1 || i >= N - 2 || j >= N - 2) return;
            float fx = px - i, fz = pz - j;
            int k = j * N + i;
            float h00 = h[k], h10 = h[k + 1], h01 = h[k + N], h11 = h[k + N + 1];
            float height = h00 * (1 - fx) * (1 - fz) + h10 * fx * (1 - fz) + h01 * (1 - fx) * fz + h11 * fx * fz;
            if (height <= sea) { // reached the sea or a lake: what is left settles there
                deposit(h, i, j, fx, fz, sediment);
                return;
            }
            float gx = (h10 - h00) * (1 - fz) + (h11 - h01) * fz, gz = (h01 - h00) * (1 - fx) + (h11 - h10) * fx;
            dx = dx * INERTIA - gx * (1 - INERTIA);
            dz = dz * INERTIA - gz * (1 - INERTIA);
            float len = (float) Math.sqrt(dx * dx + dz * dz);
            if (len < 1e-6f) { // dead flat: pick a direction from the position
                double a = ((Float.floatToIntBits(px * 7.1f + pz * 3.3f) * 0x9E3779B9) >>> 8) / (double) (1 << 24) * Math.PI * 2;
                dx = (float) Math.cos(a);
                dz = (float) Math.sin(a);
            } else {
                dx /= len;
                dz /= len;
            }
            float nx = px + dx, nz = pz + dz;
            int ni = (int) nx, nj = (int) nz;
            if (ni < 1 || nj < 1 || ni >= N - 2 || nj >= N - 2) return;
            float nfx = nx - ni, nfz = nz - nj;
            int nk = nj * N + ni;
            float nh = h[nk] * (1 - nfx) * (1 - nfz) + h[nk + 1] * nfx * (1 - nfz) + h[nk + N] * (1 - nfx) * nfz
                + h[nk + N + 1] * nfx * nfz;
            float dh = nh - height;
            if (dh > 0) {
                // Uphill: a hollow. Fill it with what we carry, and if that is not enough, the pool
                // rises (the hollow fills like a lake) so the next droplets spill over its rim.
                float fill = Math.min(sediment, dh + 0.05f);
                sediment -= fill;
                deposit(h, i, j, fx, fz, fill);
                if (fill < dh) deposit(h, i, j, fx, fz, Math.min(dh - fill + 0.05f, 2));
                speed = 0.5f;
            } else {
                float capacity = Math.max(-dh, MIN_SLOPE) * speed * water * CAPACITY;
                if (sediment > capacity) {
                    float d = (sediment - capacity) * DEPOSIT;
                    sediment -= d;
                    deposit(h, i, j, fx, fz, d);
                } else {
                    float e = Math.min((capacity - sediment) * ERODE, -dh);
                    sediment += erode(h, i, j, e);
                }
                speed = (float) Math.sqrt(Math.max(0.01f, speed * speed - dh * GRAVITY));
            }
            water *= 1 - EVAPORATE;
            px = nx;
            pz = nz;
        }
    }

    private static void deposit(float[] h, int i, int j, float fx, float fz, float amount) {
        int k = j * N + i;
        h[k] += amount * (1 - fx) * (1 - fz);
        h[k + 1] += amount * fx * (1 - fz);
        h[k + N] += amount * (1 - fx) * fz;
        h[k + N + 1] += amount * fx * fz;
    }

    /** Digs {@code amount} from a small brush around the cell; returns what was taken. */
    private static float erode(float[] h, int i, int j, float amount) {
        float taken = 0;
        for (int dj = -1; dj <= 1; dj++)
            for (int di = -1; di <= 1; di++) {
                float w = di == 0 && dj == 0 ? 0.36f : (di == 0 || dj == 0) ? 0.1f : 0.06f;
                int k = (j + dj) * N + i + di;
                float d = amount * w;
                h[k] -= d;
                taken += d;
            }
        return taken;
    }

    /**
     * Where the water goes on the carved ground. A priority flood from the sea and the grid edge
     * fills every hollow up to its spill point (those become lakes) and gives each cell the
     * neighbour it drains into, so every drop has a way to the sea. Rain (wetter climate, more of
     * it) is then summed down these links: the drained area is the flow. Cells with enough flow are
     * streams; their surface follows the ground one block down, never rising downstream (the bed is
     * cut through bumps: gorges), and the bed is a disc whose width grows with the flow.
     */
    private void rivers(Tile t, float[] h, float[] base) {
        int n = N * N;
        float[] fill = new float[n];
        int[] to = new int[n], order = new int[n];
        java.util.Arrays.fill(fill, Float.NaN);
        java.util.PriorityQueue<long[]> open = new java.util.PriorityQueue<>((p, q) -> Float.compare(Float.intBitsToFloat((int) p[0]), Float.intBitsToFloat((int) q[0])));
        for (int k = 0; k < n; k++) {
            int i = k % N, j = k / N;
            if (i == 0 || j == 0 || i == N - 1 || j == N - 1 || h[k] <= sea) {
                fill[k] = h[k];
                to[k] = -1;
                open.add(new long[] {Float.floatToIntBits(h[k]), k});
            }
        }
        int count = 0;
        int[] di = {1, -1, 0, 0, 1, 1, -1, -1}, dj = {0, 0, 1, -1, 1, -1, 1, -1};
        while (!open.isEmpty()) {
            long[] e = open.poll();
            int k = (int) e[1];
            order[count++] = k;
            int i = k % N, j = k / N;
            for (int d = 0; d < 8; d++) {
                int ii = i + di[d], jj = j + dj[d];
                if (ii < 0 || jj < 0 || ii >= N || jj >= N) continue;
                int q = jj * N + ii;
                if (!Float.isNaN(fill[q])) continue;
                fill[q] = Math.max(h[q], fill[k] + 0.001f); // flats drain, gently, towards the outlet
                to[q] = k;
                open.add(new long[] {Float.floatToIntBits(fill[q]), q});
            }
        }
        // Flow: rain summed from the far upstream cells down to the outlets.
        for (int c = n - 1; c >= 0; c--) {
            int k = order[c];
            if (h[k] > sea) t.flow[k] += 0.4f + ground.downfall(t.x0 + (k % N) * CELL, t.z0 + (k / N) * CELL);
            if (to[k] >= 0) t.flow[to[k]] += t.flow[k];
        }
        // Water surface: lakes at their spill level, streams one block under the ground, never
        // higher than upstream.
        float[] surface = new float[n];
        java.util.Arrays.fill(surface, Float.NaN);
        for (int c = n - 1; c >= 0; c--) {
            int k = order[c];
            if (h[k] <= sea) continue;
            boolean lake = fill[k] - h[k] > 1.2f;
            if (!lake && t.flow[k] < STREAM_FLOW) continue;
            float s0 = lake ? (float) Math.floor(fill[k]) : (float) Math.floor(Math.min(h[k], base[k])) - 1; // channels never rise on their own sediment
            surface[k] = Float.isNaN(surface[k]) ? s0 : Math.min(surface[k], s0);
            int r = to[k];
            if (r >= 0 && h[r] > sea) surface[r] = Float.isNaN(surface[r]) ? surface[k] : Math.min(surface[r], surface[k]);
        }
        // Beds: each cell of a disc takes the level of the nearest river centre (not the lowest disc
        // around, which would cut steps into the water everywhere discs overlap).
        java.util.Arrays.fill(t.level, Float.NaN);
        float[] near = new float[n];
        java.util.Arrays.fill(near, Float.MAX_VALUE);
        for (int k = 0; k < n; k++) {
            if (Float.isNaN(surface[k]) || surface[k] <= sea) continue;
            int i = k % N, j = k / N;
            float f = t.flow[k];
            boolean lake = fill[k] - h[k] > 1.2f;
            float slope = i > 0 && j > 0 && i < N - 1 && j < N - 1
                ? Math.abs(h[k + 1] - h[k - 1]) + Math.abs(h[k + N] - h[k - N]) : 0;
            float width = lake ? 0.6f : (float) Math.min(6, 0.3 + 0.28 * Math.sqrt(f / STREAM_FLOW - 1)) / (1 + slope * 0.05f);
            float depth = lake ? Math.max(1, surface[k] - h[k] + 1)
                : (float) Math.min(5, 1 + 0.6 * Math.log(f / STREAM_FLOW + 1)) + Math.min(2, slope * 0.1f);
            int r = (int) Math.ceil(width);
            for (int dj2 = -r; dj2 <= r; dj2++)
                for (int di2 = -r; di2 <= r; di2++) {
                    float d2 = di2 * di2 + dj2 * dj2;
                    if (d2 > width * width + 0.25f) continue;
                    int ii = i + di2, jj = j + dj2;
                    if (ii < 0 || jj < 0 || ii >= N || jj >= N) continue;
                    int q = jj * N + ii;
                    if (h[q] < surface[k] - 1) continue; // ground falling away (down a slope): no water standing over it
                    if (d2 < near[q] || (d2 == near[q] && surface[k] < t.level[q])) {
                        near[q] = d2;
                        t.level[q] = surface[k];
                        t.depth[q] = depth;
                    }
                }
        }
        // The water spreads over any lower ground next to it (a few cells at most): no dry holes
        // below the surface beside a river, which would otherwise need walls of earth.
        int[] queue = new int[n * 4], hops = new int[n];
        float[] slope = new float[n];
        int head = 0, tail = 0;
        for (int k = 0; k < n; k++) if (!Float.isNaN(t.level[k])) queue[tail++] = k;
        while (head < tail) {
            int k = queue[head++];
            if (hops[k] >= 6) continue;
            int i = k % N, j = k / N;
            for (int d = 0; d < 4; d++) {
                int ii = i + di[d], jj = j + dj[d];
                if (ii < 0 || jj < 0 || ii >= N || jj >= N) continue;
                int q = jj * N + ii;
                if (!Float.isNaN(t.level[q]) || h[q] > t.level[k] - 0.5f || h[q] < t.level[k] - 2 || h[q] <= sea) continue; // shallow hollows only
                t.level[q] = t.level[k];
                t.depth[q] = Math.max(1, t.level[k] - h[q]);
                hops[q] = hops[k] + 1;
                queue[tail++] = q;
            }
        }
        // Banks: from every water cell (and the sea) outwards, the ground may rise one block above
        // the water, then BANK_SLOPE blocks per block; big rivers get a flatter, wider floor.
        // Distance to rivers and lakes (for features that must keep clear of them, like ravines).
        java.util.Arrays.fill(t.near, NEAR_CAP);
        int[] dq = new int[n];
        int dh = 0, dt = 0;
        for (int k = 0; k < n; k++) if (!Float.isNaN(t.level[k])) { t.near[k] = 0; dq[dt++] = k; }
        while (dh < dt) {
            int k = dq[dh++];
            if (t.near[k] + CELL >= NEAR_CAP) continue;
            int i = k % N, j = k / N;
            for (int d = 0; d < 4; d++) {
                int ii = i + di[d], jj = j + dj[d];
                if (ii < 0 || jj < 0 || ii >= N || jj >= N) continue;
                int q = jj * N + ii;
                if (t.near[q] <= t.near[k] + CELL) continue;
                t.near[q] = t.near[k] + CELL;
                dq[dt++] = q;
            }
        }
        // Banks follow the NEAREST water (breadth-first, each cell once): one block above it at the
        // edge, then a gentle slope; a lower river or the sea further away does not drag the land
        // beside a higher river down (which left rivers perched on spurs).
        java.util.Arrays.fill(t.bank, 400);
        float[] source = new float[n];
        int[] ring = new int[n];
        boolean[] seen = new boolean[n];
        head = 0;
        tail = 0;
        for (int k = 0; k < n; k++) {
            boolean water = !Float.isNaN(t.level[k]);
            if (!water && h[k] > sea) continue;
            source[k] = water ? t.level[k] : sea;
            t.bank[k] = source[k] + 1;
            float f = water ? Math.max(t.flow[k], STREAM_FLOW) : STREAM_FLOW * 50;
            slope[k] = CELL * Math.max(0.35f, Math.min(1.2f, 1.6f / (float) Math.log10(f / STREAM_FLOW * 10 + 1)));
            seen[k] = true;
            queue[tail++] = k;
        }
        while (head < tail) {
            int k = queue[head++];
            int i = k % N, j = k / N;
            for (int d = 0; d < 4; d++) {
                int ii = i + di[d], jj = j + dj[d];
                if (ii < 0 || jj < 0 || ii >= N || jj >= N) continue;
                int q = jj * N + ii;
                if (seen[q]) continue;
                seen[q] = true;
                ring[q] = ring[k] + 1;
                source[q] = source[k];
                slope[q] = slope[k];
                float cap = source[q] + 1 + (ring[q] - 1) * slope[q]; // the first ring stays at the water's edge
                if (cap > h[q] + 40) continue; // far up a mountain side: no limit there
                t.bank[q] = cap;
                queue[tail++] = q;
            }
        }
        // A bank never drops below the water right beside it (lower water nearby must not leave a
        // river perched between walls).
        float[] floor = new float[n];
        java.util.Arrays.fill(floor, 10000);
        for (int k = 0; k < n; k++) {
            if (Float.isNaN(t.level[k])) continue;
            int i = k % N, j = k / N;
            for (int dj2 = -1; dj2 <= 1; dj2++)
                for (int di2 = -1; di2 <= 1; di2++) {
                    int ii = i + di2, jj = j + dj2;
                    if (ii >= 0 && jj >= 0 && ii < N && jj < N) floor[jj * N + ii] = Math.min(floor[jj * N + ii], t.level[k] + 1);
                }
        }
        for (int k = 0; k < n; k++) if (floor[k] < 10000) t.bank[k] = Math.max(t.bank[k], floor[k]);
    }
}
