package net.mynecraft.world;

/** Seeded 2D gradient (Perlin-style) noise with fBm. Allocation-free. */
public final class Noise {
    private final int[] perm = new int[512];

    public Noise(long seed) {
        int[] p = new int[256];
        for (int i = 0; i < 256; i++) p[i] = i;
        long s = seed;
        for (int i = 255; i > 0; i--) {
            s = s * 6364136223846793005L + 1442695040888963407L;
            int j = (int) ((s >>> 33) % (i + 1));
            int t = p[i]; p[i] = p[j]; p[j] = t;
        }
        for (int i = 0; i < 512; i++) perm[i] = p[i & 255];
    }

    private static float fade(float t) {
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    private static float grad(int h, float x, float y) {
        return switch (h & 7) {
            case 0 -> x + y;
            case 1 -> x - y;
            case 2 -> -x + y;
            case 3 -> -x - y;
            case 4 -> x;
            case 5 -> -x;
            case 6 -> y;
            default -> -y;
        };
    }

    /** Roughly in [-1, 1]. */
    public float noise(float x, float y) {
        int xf = (int) Math.floor(x), yf = (int) Math.floor(y);
        float dx = x - xf, dy = y - yf;
        int xi = xf & 255, yi = yf & 255;
        float u = fade(dx), v = fade(dy);
        int aa = perm[perm[xi] + yi], ab = perm[perm[xi] + yi + 1];
        int ba = perm[perm[xi + 1] + yi], bb = perm[perm[xi + 1] + yi + 1];
        float x1 = lerp(u, grad(aa, dx, dy), grad(ba, dx - 1, dy));
        float x2 = lerp(u, grad(ab, dx, dy - 1), grad(bb, dx - 1, dy - 1));
        return lerp(v, x1, x2);
    }

    private static float grad3(int h, float x, float y, float z) {
        // The 12 edge directions of a cube (Perlin's improved noise), 4 duplicated to fill 16 cases.
        switch (h & 15) {
            case 0: case 12: return x + y;
            case 1: case 13: return -x + y;
            case 2: return x - y;
            case 3: return -x - y;
            case 4: return x + z;
            case 5: return -x + z;
            case 6: return x - z;
            case 7: return -x - z;
            case 8: return y + z;
            case 9: case 14: return -y + z;
            case 10: return y - z;
            default: return -y - z;
        }
    }

    /** 3D gradient noise, roughly in [-1, 1]. */
    public float noise3(float x, float y, float z) {
        int xf = (int) Math.floor(x), yf = (int) Math.floor(y), zf = (int) Math.floor(z);
        float dx = x - xf, dy = y - yf, dz = z - zf;
        int xi = xf & 255, yi = yf & 255, zi = zf & 255;
        float u = fade(dx), v = fade(dy), w = fade(dz);
        int a = perm[xi] + yi, aa = perm[a & 255] + zi, ab = perm[(a + 1) & 255] + zi;
        int b = perm[xi + 1] + yi, ba = perm[b & 255] + zi, bb = perm[(b + 1) & 255] + zi;
        float x1 = lerp(u, grad3(perm[aa & 511], dx, dy, dz), grad3(perm[ba & 511], dx - 1, dy, dz));
        float x2 = lerp(u, grad3(perm[ab & 511], dx, dy - 1, dz), grad3(perm[bb & 511], dx - 1, dy - 1, dz));
        float x3 = lerp(u, grad3(perm[(aa + 1) & 511], dx, dy, dz - 1), grad3(perm[(ba + 1) & 511], dx - 1, dy, dz - 1));
        float x4 = lerp(u, grad3(perm[(ab + 1) & 511], dx, dy - 1, dz - 1), grad3(perm[(bb + 1) & 511], dx - 1, dy - 1, dz - 1));
        return lerp(w, lerp(v, x1, x2), lerp(v, x3, x4));
    }

    public float fbm(float x, float y, int octaves) {
        float sum = 0, amp = 1, norm = 0;
        for (int i = 0; i < octaves; i++) {
            sum += noise(x, y) * amp;
            norm += amp;
            amp *= 0.5f;
            x *= 2; y *= 2;
        }
        return sum / norm;
    }

    private static float lerp(float t, float a, float b) {
        return a + t * (b - a);
    }
}
