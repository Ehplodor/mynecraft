package net.mynecraft.world.storage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.BitSet;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Anvil region file (r.X.Z.mca): 32x32 chunks. Header = 1024 location entries
 * (3-byte sector offset, 1-byte sector count) + 1024 timestamps; payload in 4 KiB sectors as
 * [int length][byte compression][data]. Compression: 1 gzip, 2 zlib (written), 3 none.
 * Methods are synchronized: one file is shared by loader and saver threads.
 */
public final class RegionFile implements AutoCloseable {
    private static final int SECTOR = 4096;
    private static final int GZIP = 1, ZLIB = 2, NONE = 3;

    private final RandomAccessFile file;
    private final int[] locations = new int[1024];
    private final BitSet used = new BitSet();

    public RegionFile(Path path, boolean readOnly) throws IOException {
        file = new RandomAccessFile(path.toFile(), readOnly ? "r" : "rw");
        if (file.length() < 2 * SECTOR) {
            if (readOnly) throw new IOException("Truncated region " + path);
            file.setLength(2 * SECTOR);
        }
        used.set(0, 2);
        file.seek(0);
        for (int i = 0; i < 1024; i++) {
            int loc = file.readInt();
            locations[i] = loc;
            int off = loc >>> 8, count = loc & 0xFF;
            if (off >= 2 && count > 0) used.set(off, off + count);
        }
    }

    private static int index(int lx, int lz) {
        return (lx & 31) | ((lz & 31) << 5);
    }

    /** Returns an uncompressed NBT stream, or null if the chunk is absent. */
    public synchronized InputStream read(int lx, int lz) throws IOException {
        int loc = locations[index(lx, lz)];
        int off = loc >>> 8, count = loc & 0xFF;
        if (off < 2 || count == 0 || (long) off * SECTOR >= file.length()) return null;
        file.seek((long) off * SECTOR);
        int length = file.readInt();
        if (length <= 1 || length > count * SECTOR) return null;
        int compression = file.readByte();
        byte[] data = new byte[length - 1];
        file.readFully(data);
        InputStream raw = new ByteArrayInputStream(data);
        return switch (compression) {
            case GZIP -> new GZIPInputStream(raw);
            case ZLIB -> new InflaterInputStream(raw);
            case NONE -> raw;
            default -> throw new IOException("Unsupported chunk compression " + compression
                + " (external .mcc or LZ4 chunks not supported yet)");
        };
    }

    public synchronized void write(int lx, int lz, byte[] nbt) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(nbt.length / 4 + 64);
        try (DeflaterOutputStream z = new DeflaterOutputStream(bos, new Deflater(Deflater.BEST_SPEED))) {
            z.write(nbt);
        }
        byte[] data = bos.toByteArray();
        int sectors = (data.length + 5 + SECTOR - 1) / SECTOR;
        if (sectors > 255) throw new IOException("Chunk too large for region file");

        int i = index(lx, lz);
        int oldOff = locations[i] >>> 8, oldCount = locations[i] & 0xFF;
        if (oldOff >= 2 && oldCount > 0) used.clear(oldOff, oldOff + oldCount);
        int off = findFree(sectors);
        used.set(off, off + sectors);

        file.seek((long) off * SECTOR);
        file.writeInt(data.length + 1);
        file.writeByte(ZLIB);
        file.write(data);
        int pad = sectors * SECTOR - (data.length + 5);
        if (pad > 0) file.write(new byte[pad]);

        locations[i] = (off << 8) | sectors;
        file.seek(i * 4L);
        file.writeInt(locations[i]);
        file.seek(SECTOR + i * 4L);
        file.writeInt((int) (System.currentTimeMillis() / 1000));
    }

    private int findFree(int sectors) {
        for (int start = used.nextClearBit(2); ; start = used.nextClearBit(start + 1)) {
            int end = used.nextSetBit(start);
            if (end < 0 || end - start >= sectors) return start;
            start = end;
        }
    }

    @Override
    public synchronized void close() throws IOException {
        file.close();
    }
}
