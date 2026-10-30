package net.mynecraft.client;

import net.mynecraft.world.Blocks;
import net.mynecraft.world.Chunk;
import net.mynecraft.world.TerrainGenerator;
import net.mynecraft.world.World;
import net.mynecraft.world.weather.Atmosphere;
import net.mynecraft.world.weather.Weather;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL33.*;

/**
 * Weather in the sky and around the camera, from the atmosphere model:
 * <ul>
 *   <li>clouds as boxes (like vanilla's fancy clouds) where the model has cloud water, their base at
 *       the condensation level, thicker and darker in storms; rebuilt once per weather step and slid
 *       with the wind in between;</li>
 *   <li>rain, sleet, snow and hail as short lines falling around the camera, world-anchored so they
 *       do not follow the head, and stopped by roofs (a small cached height map);</li>
 *   <li>lightning bolts and flashes in thunderstorms.</li>
 * </ul>
 * Vertices are 16 bytes: position relative to an anchor (float precision stays small) and RGBA8.
 */
final class WeatherRenderer {
    private static final int PUFF = 12;          // cloud box size in blocks, like vanilla's cloud texels
    private static final int CLOUD_RANGE = 384;  // blocks around the camera with clouds
    private static final int RAIN_RADIUS = 20;   // columns of falling drops around the camera
    private static final int DROP_SPAN = 40;     // vertical span of the drop pattern

    private final Shader shader;
    private final int uViewProj, uOffset, uTint, uFadeEnd;
    private final int cloudVao, cloudVbo, dropVao, dropVbo;
    private ByteBuffer cloudData = BufferUtils.createByteBuffer(1 << 20);
    private ByteBuffer dropData = BufferUtils.createByteBuffer(1 << 16);
    private int cloudVertices;
    private long builtStep = -1;
    // Clouds live in a frame that travels with the wind (shift = wind integrated over time): the
    // puffs keep their shape and glide, and each rebuild only changes which ones the field fills.
    private double anchorX, anchorZ, shiftX, shiftZ, lastNow = Double.NaN;
    private final float[] matrix = new float[16];
    // Roof cache: highest blocking block per column around the camera.
    private final int[] roof = new int[(2 * RAIN_RADIUS + 1) * (2 * RAIN_RADIUS + 1)];
    private int roofX = Integer.MIN_VALUE, roofZ, roofAge;
    // Lightning.
    private double boltUntil, nextBolt;
    private double[] strike; // the last bolt's position, until taken (thunder)

    /** Position of a lightning strike since the last call, or null. */
    double[] takeStrike() {
        double[] s = strike;
        strike = null;
        return s;
    }
    private float boltX, boltZ, boltTop, boltBottom;
    private long boltSeed;
    /** 0..1 brightness of the current lightning flash (the caller brightens sky and light with it). */
    float flash;

    WeatherRenderer() {
        shader = new Shader("/shaders/weather.vert", "/shaders/weather.frag");
        uViewProj = shader.uniform("uViewProj");
        uOffset = shader.uniform("uOffset");
        uTint = shader.uniform("uTint");
        uFadeEnd = shader.uniform("uFadeEnd");
        cloudVao = glGenVertexArrays();
        cloudVbo = glGenBuffers();
        setup(cloudVao, cloudVbo);
        dropVao = glGenVertexArrays();
        dropVbo = glGenBuffers();
        setup(dropVao, dropVbo);
    }

    private static void setup(int vao, int vbo) {
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 16, 0);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 4, GL_UNSIGNED_BYTE, true, 16, 12);
        glEnableVertexAttribArray(1);
        glBindVertexArray(0);
    }

    // ---- clouds ----

    private void buildClouds(Weather weather, double camX, double camZ) {
        Atmosphere a = weather.atmosphere;
        builtStep = a.steps();
        anchorX = Math.floor((camX - shiftX) / PUFF) * PUFF; // in the travelling frame
        anchorZ = Math.floor((camZ - shiftZ) / PUFF) * PUFF;
        cloudData.clear();
        cloudVertices = 0;
        int r = CLOUD_RANGE / PUFF;
        for (int j = -r; j <= r; j++)
            for (int i = -r; i <= r; i++) {
                if (i * i + j * j > r * r) continue;
                double wx = anchorX + shiftX + i * PUFF + PUFF / 2.0, wz = anchorZ + shiftZ + j * PUFF + PUFF / 2.0;
                Atmosphere.Sample s = weather.at(wx, wz, TerrainGenerator.SEA_LEVEL);
                // Puffy edges: a per-box random threshold, so thin cloud is broken and thick is solid.
                int gx = (int) Math.round(anchorX / PUFF) + i, gz = (int) Math.round(anchorZ / PUFF) + j;
                float noise = 0.6f * hash(gx >> 1, gz >> 1) + 0.4f * hash(gx, gz); // clumps, not a checkerboard
                float c = s.cloud();
                if (c * (0.55f + 0.9f * noise) < 0.22f) continue;
                float thick = 4 + 14 * Math.min(1, c) + 40 * s.storm() * Math.min(1, c);
                float base = Math.min(Chunk.MAX_Y - 70, Math.max(TerrainGenerator.SEA_LEVEL + 30, s.cloudBase())) + 3 * noise;
                float shade = 1 - 0.55f * Math.min(1, Math.max(0, c - 0.5f) / 1.5f + s.storm() * 0.7f);
                box(i * PUFF, base, j * PUFF, PUFF, thick, shade);
            }
        cloudData.flip();
        glBindBuffer(GL_ARRAY_BUFFER, cloudVbo);
        glBufferData(GL_ARRAY_BUFFER, cloudData, GL_DYNAMIC_DRAW);
    }

    private void box(float x, float y, float z, float s, float h, float shade) {
        ensureClouds(36 * 16);
        float x1 = x + s, y1 = y + h, z1 = z + s;
        int top = grey(shade), side = grey(shade * 0.85f), bottom = grey(shade * 0.7f);
        quad(cloudData, x, y1, z, x, y1, z1, x1, y1, z1, x1, y1, z, top);
        quad(cloudData, x, y, z, x1, y, z, x1, y, z1, x, y, z1, bottom);
        quad(cloudData, x, y, z, x, y1, z, x1, y1, z, x1, y, z, side);
        quad(cloudData, x, y, z1, x1, y, z1, x1, y1, z1, x, y1, z1, side);
        quad(cloudData, x, y, z, x, y, z1, x, y1, z1, x, y1, z, side);
        quad(cloudData, x1, y, z, x1, y1, z, x1, y1, z1, x1, y, z1, side);
        cloudVertices += 36;
    }

    private void ensureClouds(int bytes) {
        if (cloudData.remaining() >= bytes) return;
        ByteBuffer bigger = BufferUtils.createByteBuffer(cloudData.capacity() * 2);
        cloudData.flip();
        bigger.put(cloudData);
        cloudData = bigger;
    }

    private static int grey(float v) {
        int g = Math.max(0, Math.min(255, Math.round(v * 255)));
        return g | g << 8 | g << 16 | 0xE6 << 24;
    }

    private static void quad(ByteBuffer b, float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz, int rgba) {
        vertex(b, ax, ay, az, rgba);
        vertex(b, bx, by, bz, rgba);
        vertex(b, cx, cy, cz, rgba);
        vertex(b, ax, ay, az, rgba);
        vertex(b, cx, cy, cz, rgba);
        vertex(b, dx, dy, dz, rgba);
    }

    private static void vertex(ByteBuffer b, float x, float y, float z, int rgba) {
        b.putFloat(x).putFloat(y).putFloat(z).putInt(rgba);
    }

    /** Per-column pseudo-random in [0, 1). */
    private static float hash(int x, int z) {
        long h = x * 0x9E3779B97F4A7C15L ^ z * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 31;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 29;
        return (h >>> 40) / (float) (1L << 24);
    }

    // ---- drops ----

    private void updateRoofs(World world, int cx, int cy, int cz) {
        if (cx == roofX && cz == roofZ && roofAge++ < 20) return;
        roofX = cx;
        roofZ = cz;
        roofAge = 0;
        int w = 2 * RAIN_RADIUS + 1;
        for (int j = 0; j < w; j++)
            for (int i = 0; i < w; i++) {
                int x = cx - RAIN_RADIUS + i, z = cz - RAIN_RADIUS + j, y = Math.min(Chunk.MAX_Y - 1, cy + DROP_SPAN);
                int stop = cy - DROP_SPAN;
                while (y > stop) {
                    short b = world.get(x, y, z);
                    if (Blocks.isSolid(b) || Blocks.isFluid(b)) break;
                    y--;
                }
                roof[j * w + i] = y;
            }
    }

    // ---- frame ----

    /** Draws clouds, drops and lightning; {@code now} in seconds, {@code light} the sky brightness. */
    void draw(Matrix4f viewProj, Weather weather, World world, double camX, double camY, double camZ,
              double now, float light, float far, boolean frozen) {
        Atmosphere.Sample here = weather.here();
        if (here == null) return;
        if (!frozen && !Double.isNaN(lastNow)) {
            double dt = Math.max(0, Math.min(0.25, now - lastNow));
            shiftX += here.windX() * dt;
            shiftZ += here.windZ() * dt;
        }
        lastNow = now;
        if (weather.atmosphere.steps() != builtStep) buildClouds(weather, camX, camZ);
        glUseProgram(shader.program);
        glUniformMatrix4fv(uViewProj, false, viewProj.get(matrix));
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        float lit = Math.min(1, light + flash * 0.6f);
        if (cloudVertices > 0) {
            glUniform3f(uOffset, (float) (anchorX + shiftX - camX), (float) -camY, (float) (anchorZ + shiftZ - camZ));
            glUniform4f(uTint, lit, lit, lit, 1);
            glUniform1f(uFadeEnd, Math.min(CLOUD_RANGE, far * 1.6f));
            glBindVertexArray(cloudVao);
            glDrawArrays(GL_TRIANGLES, 0, cloudVertices);
        }
        glDepthMask(false);
        int drops = buildDrops(weather, world, here, camX, camY, camZ, now, lit);
        drops += lightning(weather, world, here, camX, camY, camZ, now, frozen);
        if (drops > 0) {
            dropData.flip();
            glBindBuffer(GL_ARRAY_BUFFER, dropVbo);
            glBufferData(GL_ARRAY_BUFFER, dropData, GL_STREAM_DRAW);
            glUniform3f(uOffset, (float) (Math.floor(camX) - camX), (float) (Math.floor(camY) - camY),
                (float) (Math.floor(camZ) - camZ));
            glUniform4f(uTint, 1, 1, 1, 1);
            glUniform1f(uFadeEnd, Math.max(RAIN_RADIUS, far));
            glBindVertexArray(dropVao);
            glDrawArrays(GL_LINES, 0, drops);
        }
        glDepthMask(true);
        glDisable(GL_BLEND);
        glBindVertexArray(0);
    }

    /** Falling drops as line segments around the camera (positions relative to the camera block). */
    private int buildDrops(Weather weather, World world, Atmosphere.Sample here, double camX, double camY, double camZ,
                           double now, float lit) {
        dropData.clear();
        Atmosphere.Precipitation type = here.type();
        if (type == Atmosphere.Precipitation.NONE) return 0;
        float intensity = Math.min(1, here.precipitation() / 0.04f);
        int cx = (int) Math.floor(camX), cy = (int) Math.floor(camY), cz = (int) Math.floor(camZ);
        updateRoofs(world, cx, cy, cz);
        float speed, length, alpha;
        int rgb;
        switch (type) {
            case SNOW -> { speed = 1.6f; length = 0.12f; alpha = 0.95f; rgb = 0xFFFFFF; }
            case SLEET -> { speed = 7; length = 0.35f; alpha = 0.7f; rgb = 0xE0E8F0; }
            case HAIL -> { speed = 22; length = 0.25f; alpha = 1; rgb = 0xF4F8FF; }
            default -> { speed = 16; length = 0.9f; alpha = 0.45f; rgb = 0xB0C0E0; }
        }
        int l = Math.round(Math.min(1, lit + 0.15f) * 255);
        int r = ((rgb >> 16) & 255) * l / 255, g = ((rgb >> 8) & 255) * l / 255, b = (rgb & 255) * l / 255;
        int color = r | g << 8 | b << 16 | Math.round(alpha * 255) << 24;
        float slantX = here.windX() / speed, slantZ = here.windZ() / speed; // drops lean with the wind
        if (type == Atmosphere.Precipitation.SNOW) { slantX *= 0.3f; slantZ *= 0.3f; }
        int w = 2 * RAIN_RADIUS + 1, count = 0;
        int perColumn = type == Atmosphere.Precipitation.SNOW ? 2 : 1;
        for (int j = 0; j < w; j++)
            for (int i = 0; i < w; i++) {
                int x = cx - RAIN_RADIUS + i, z = cz - RAIN_RADIUS + j;
                for (int k = 0; k < perColumn; k++) {
                    float h = hash(x * 7 + k, z * 13 - k);
                    if (h > intensity * 0.7f) continue;
                    float fx = hash(x + 31 * k, z - 17), fz = hash(x - 5, z + 91 * k);
                    float phase = hash(z * 3 + k, x * 11);
                    double fall = (phase * DROP_SPAN + now * speed) % DROP_SPAN;
                    double y = cy + DROP_SPAN / 2.0 - fall;
                    if (y <= roof[j * w + i] + 1) continue; // sheltered, or already landed
                    float wobble = type == Atmosphere.Precipitation.SNOW ? (float) Math.sin(now * 2 + phase * 40) * 0.3f : 0;
                    float px = i - RAIN_RADIUS + fx + wobble, pz = j - RAIN_RADIUS + fz, py = (float) (y - cy);
                    if (dropData.remaining() < 32) growDrops();
                    vertex(dropData, px, py, pz, color);
                    vertex(dropData, px + slantX * length, py + length, pz + slantZ * length, color);
                    count += 2;
                }
            }
        return count;
    }

    private void growDrops() {
        ByteBuffer bigger = BufferUtils.createByteBuffer(dropData.capacity() * 2);
        dropData.flip();
        bigger.put(dropData);
        dropData = bigger;
    }

    /** Thunderstorms: now and then a jagged bolt from the cloud base to the ground, and a flash. */
    private int lightning(Weather weather, World world, Atmosphere.Sample here, double camX, double camY, double camZ,
                          double now, boolean frozen) {
        flash = now < boltUntil ? (float) ((boltUntil - now) / 0.25) : 0;
        boolean thunder = here.storm() > 0.45f && here.precipitation() > 0.02f;
        if (!frozen && thunder && now >= nextBolt) {
            boltSeed = (long) (now * 1000);
            float angle = hash((int) boltSeed, 1) * 6.283f, dist = 20 + 140 * hash((int) boltSeed, 2);
            boltX = (float) (camX + Math.cos(angle) * dist);
            boltZ = (float) (camZ + Math.sin(angle) * dist);
            boltBottom = world.generator.height((int) boltX, (int) boltZ) + 1;
            boltTop = Math.max(boltBottom + 30, here.cloudBase());
            boltUntil = now + 0.25;
            strike = new double[] {boltX, (boltTop + boltBottom) / 2, boltZ};
            nextBolt = now + 2 + 12 * hash((int) boltSeed, 3) / Math.max(0.2f, here.storm());
        } else if (!thunder) {
            nextBolt = Math.max(nextBolt, now + 3);
        }
        if (now >= boltUntil) return 0;
        int count = 0, color = 0xFFF0F0 | Math.round(Math.min(1, flash * 1.5f) * 255) << 24; // bluish white
        float ox = (float) (boltX - Math.floor(camX)), oz = (float) (boltZ - Math.floor(camZ));
        float x = ox, z = oz, y = (float) (boltTop - Math.floor(camY));
        float bottom = (float) (boltBottom - Math.floor(camY));
        int seg = 0;
        while (y > bottom) {
            float ny = Math.max(bottom, y - 4 - 6 * hash((int) boltSeed, seg));
            float nx = x + (hash(seg, (int) boltSeed) - 0.5f) * 5, nz = z + (hash((int) boltSeed + 7, seg) - 0.5f) * 5;
            if (dropData.remaining() < 32) growDrops();
            vertex(dropData, x, y, z, color);
            vertex(dropData, nx, ny, nz, color);
            count += 2;
            x = nx;
            y = ny;
            z = nz;
            seg++;
        }
        return count;
    }

    void dispose() {
        glDeleteBuffers(new int[] {cloudVbo, dropVbo});
        glDeleteVertexArrays(new int[] {cloudVao, dropVao});
        glDeleteProgram(shader.program);
    }
}
