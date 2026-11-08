package net.mynecraft.client;

import net.mynecraft.world.life.Ecology;
import org.lwjgl.BufferUtils;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL33.*;

/**
 * The water's pollution per ecology patch (64 x 64 blocks) for the loaded area, in an 8 x 8 texture
 * that wraps every 512 blocks like the biome map: one texel per patch, linear filtering blends the
 * patches' edges. The water shader reads it to cloud and brown a fouled river.
 */
final class PollutionMap {
    static final int SIZE = 8;
    final int id;
    private final ByteBuffer texels = BufferUtils.createByteBuffer(SIZE * SIZE);
    private final byte[] shown = new byte[SIZE * SIZE];
    private boolean uploaded;

    PollutionMap() {
        id = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, id);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_R8, SIZE, SIZE, 0, GL_RED, GL_UNSIGNED_BYTE, (ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
    }

    /** The 8 x 8 patches around (x, z), each at its place mod 8; uploaded only when a value moved. */
    void update(Ecology ecology, double x, double z) {
        int cpx = Math.floorDiv((int) Math.floor(x), Ecology.PATCH) - SIZE / 2, cpz = Math.floorDiv((int) Math.floor(z), Ecology.PATCH) - SIZE / 2;
        boolean changed = !uploaded;
        byte[] next = new byte[SIZE * SIZE];
        for (int dz = 0; dz < SIZE; dz++)
            for (int dx = 0; dx < SIZE; dx++) {
                int px = cpx + dx, pz = cpz + dz;
                Ecology.Patch p = ecology.existing(px * (double) Ecology.PATCH + 1, pz * (double) Ecology.PATCH + 1);
                int i = (pz & (SIZE - 1)) * SIZE + (px & (SIZE - 1));
                next[i] = (byte) Math.round(255 * (p == null ? 0 : Math.max(0, Math.min(1, p.pollution))));
                if (next[i] != shown[i]) changed = true;
            }
        if (!changed) return;
        System.arraycopy(next, 0, shown, 0, next.length);
        texels.clear();
        texels.put(next).flip();
        glBindTexture(GL_TEXTURE_2D, id);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, SIZE, SIZE, GL_RED, GL_UNSIGNED_BYTE, texels);
        uploaded = true;
    }

    void dispose() {
        glDeleteTextures(id);
    }
}
