package net.mynecraft.world;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The chunks the player has seen (loaded within render distance), kept with the world so the maps
 * can show explored land in full colour and the rest dimmed. One long per chunk in
 * {@code explored.bin}; a few kilobytes for hours of travel.
 */
public final class ExploredMap {
    private static final int MAGIC = 0x4D594558; // "MYEX"
    private final Path file;
    private final Set<Long> chunks = ConcurrentHashMap.newKeySet();
    private volatile boolean dirty;

    public ExploredMap(Path worldDir) {
        this.file = worldDir.resolve("explored.bin");
        if (Files.exists(file)) {
            try (DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(Files.newInputStream(file)))) {
                if (in.readInt() == MAGIC) {
                    int n = in.readInt();
                    for (int i = 0; i < n; i++) chunks.add(in.readLong());
                }
            } catch (IOException e) {
                System.err.println("[map] cannot read " + file + ": " + e.getMessage());
            }
        }
    }

    public void see(int cx, int cz) {
        if (chunks.add(World.key(cx, cz))) dirty = true;
    }

    /** True if the chunk holding this block column has been seen. */
    public boolean seen(int wx, int wz) {
        return chunks.contains(World.key(wx >> 4, wz >> 4));
    }

    public int size() {
        return chunks.size();
    }

    public void save() {
        if (!dirty) return;
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling("explored.bin.tmp");
            try (DataOutputStream out = new DataOutputStream(new java.io.BufferedOutputStream(Files.newOutputStream(tmp)))) {
                Long[] all = chunks.toArray(new Long[0]);
                out.writeInt(MAGIC);
                out.writeInt(all.length);
                for (Long k : all) out.writeLong(k);
            }
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            dirty = false;
        } catch (IOException e) {
            System.err.println("[map] cannot save " + file + ": " + e.getMessage());
        }
    }
}
