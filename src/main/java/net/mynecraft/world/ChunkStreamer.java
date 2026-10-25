package net.mynecraft.world;

import net.mynecraft.world.storage.WorldStorage;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Keeps the chunks around the player loaded: nearest first, a bounded number of jobs in flight
 * (so priorities follow the player instead of a stale backlog), with hysteresis between the
 * render radius, the data radius and the unload radius to avoid thrashing at borders.
 *
 * <p>{@link #update} runs on the main thread; loading (disk or generation) runs on workers and
 * saving on a single IO thread, so writes to a region file are ordered.
 */
public final class ChunkStreamer implements AutoCloseable {
    public interface Listener {
        /** The chunk and its 4 neighbours are loaded and it is within render distance. */
        void chunkVisible(int cx, int cz);

        /** The chunk left render distance (its meshes can be freed). */
        void chunkHidden(int cx, int cz);
    }

    private final World world;
    private final WorldStorage storage;
    private final Listener listener;
    private final int renderRadius;
    private final int[] offsets; // packed (dx, dz) sorted by distance, within renderRadius + 1
    private final ExecutorService loaders;
    private final ExecutorService io;
    private final int maxInFlight;

    private final Set<Long> inFlight = new HashSet<>();
    private final Set<Long> visible = new HashSet<>();
    private final ConcurrentLinkedQueue<Chunk> completed = new ConcurrentLinkedQueue<>();

    public ChunkStreamer(World world, WorldStorage storage, int renderRadius, int threads, Listener listener) {
        this.world = world;
        this.storage = storage;
        this.listener = listener;
        this.renderRadius = renderRadius;
        int r = renderRadius + 1;
        List<int[]> list = new ArrayList<>();
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) list.add(new int[] {dx, dz});
        list.sort((a, b) -> Integer.compare(a[0] * a[0] + a[1] * a[1], b[0] * b[0] + b[1] * b[1]));
        offsets = new int[list.size() * 2];
        for (int i = 0; i < list.size(); i++) { offsets[2 * i] = list.get(i)[0]; offsets[2 * i + 1] = list.get(i)[1]; }
        this.maxInFlight = threads * 2;
        loaders = Executors.newFixedThreadPool(threads, r2 -> daemon(r2, "chunk-loader"));
        io = Executors.newSingleThreadExecutor(r2 -> daemon(r2, "chunk-io"));
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    public int loadingCount() {
        return inFlight.size();
    }

    /** True when nothing is left to load around (pcx, pcz). */
    public boolean settled() {
        return inFlight.isEmpty() && completed.isEmpty();
    }

    public void update(int pcx, int pcz) {
        for (Chunk c; (c = completed.poll()) != null; ) {
            inFlight.remove(World.key(c.cx, c.cz));
            world.put(c);
        }

        // Visibility: needs all 4 neighbours so border faces are culled correctly.
        for (int i = 0; i < offsets.length; i += 2) {
            int dx = offsets[i], dz = offsets[i + 1];
            if (Math.max(Math.abs(dx), Math.abs(dz)) > renderRadius) continue;
            int cx = pcx + dx, cz = pcz + dz;
            long k = World.key(cx, cz);
            if (visible.contains(k)) continue;
            if (world.chunk(cx, cz) != null && world.chunk(cx - 1, cz) != null && world.chunk(cx + 1, cz) != null
                && world.chunk(cx, cz - 1) != null && world.chunk(cx, cz + 1) != null) {
                visible.add(k);
                listener.chunkVisible(cx, cz);
            }
        }

        // Hide far meshes; unload far data (with hysteresis) and save it if modified.
        visible.removeIf(k -> {
            int cx = (int) (k >> 32), cz = (int) (long) k;
            if (Math.max(Math.abs(cx - pcx), Math.abs(cz - pcz)) <= renderRadius + 1) return false;
            listener.chunkHidden(cx, cz);
            return true;
        });
        List<Chunk> far = null;
        for (Chunk c : world.loadedChunks()) {
            if (Math.max(Math.abs(c.cx - pcx), Math.abs(c.cz - pcz)) > renderRadius + 3) {
                if (far == null) far = new ArrayList<>();
                far.add(c);
            }
        }
        if (far != null) for (Chunk c : far) {
            world.remove(c.cx, c.cz);
            save(c);
        }

        // Schedule the nearest missing chunks.
        for (int i = 0; i < offsets.length && inFlight.size() < maxInFlight; i += 2) {
            int cx = pcx + offsets[i], cz = pcz + offsets[i + 1];
            long k = World.key(cx, cz);
            if (world.chunk(cx, cz) != null || inFlight.contains(k)) continue;
            inFlight.add(k);
            loaders.execute(() -> {
                Chunk c = storage == null ? null : storage.load(cx, cz);
                completed.add(c != null ? c : world.generator.generate(cx, cz));
            });
        }
    }

    private void save(Chunk c) {
        if (storage != null && storage.enqueueSave(c)) io.execute(() -> storage.flush(c));
    }

    /** Queues every modified loaded chunk for saving without blocking (periodic autosave). */
    public void autosave() {
        for (Chunk c : world.loadedChunks()) save(c);
    }

    /** Saves every modified chunk and waits for the IO thread to finish. */
    public void saveAll() throws InterruptedException {
        if (storage == null) return;
        for (Chunk c : world.loadedChunks()) save(c);
        io.shutdown();
        io.awaitTermination(1, TimeUnit.MINUTES);
    }

    @Override
    public void close() {
        loaders.shutdownNow();
        io.shutdown();
    }

}
