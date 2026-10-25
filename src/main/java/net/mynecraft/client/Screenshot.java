package net.mynecraft.client;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

import org.lwjgl.BufferUtils;

import static org.lwjgl.opengl.GL11.*;

final class Screenshot {
    private Screenshot() {}

    static void save(String path, int w, int h) {
        ByteBuffer buf = BufferUtils.createByteBuffer(w * h * 3);
        glPixelStorei(GL_PACK_ALIGNMENT, 1);
        glReadPixels(0, 0, w, h, GL_RGB, GL_UNSIGNED_BYTE, buf);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = ((h - 1 - y) * w + x) * 3;
                img.setRGB(x, y, (buf.get(i) & 0xFF) << 16 | (buf.get(i + 1) & 0xFF) << 8 | (buf.get(i + 2) & 0xFF));
            }
        }
        try {
            ImageIO.write(img, "png", new File(path));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
