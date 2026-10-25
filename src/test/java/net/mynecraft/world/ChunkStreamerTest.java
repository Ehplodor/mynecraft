package net.mynecraft.world;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ChunkStreamerTest {
    @Test
    void loadsAroundPlayerAndUnloadsBehind() throws Exception {
        World w = new World(5);
        Set<Long> visible = new HashSet<>();
        int r = 4;
        try (ChunkStreamer s = new ChunkStreamer(w, null, r, 2, new ChunkStreamer.Listener() {
            public void chunkVisible(int cx, int cz) { assertTrue(visible.add(World.key(cx, cz))); }
            public void chunkHidden(int cx, int cz) { assertTrue(visible.remove(World.key(cx, cz))); }
        })) {
            settle(s, 0, 0);
            assertEquals((2 * r + 1) * (2 * r + 1), visible.size());
            assertNotNull(w.chunk(r + 1, 0));

            settle(s, 100, 0);
            assertEquals((2 * r + 1) * (2 * r + 1), visible.size());
            assertTrue(visible.contains(World.key(100, 0)));
            assertNull(w.chunk(0, 0), "old area must be unloaded");
            for (Chunk c : w.loadedChunks()) assertTrue(Math.abs(c.cx - 100) <= r + 3);
        }
    }

    private static void settle(ChunkStreamer s, int cx, int cz) throws InterruptedException {
        long deadline = System.nanoTime() + 10_000_000_000L;
        do {
            s.update(cx, cz);
            Thread.sleep(1);
        } while (!s.settled() && System.nanoTime() < deadline);
        s.update(cx, cz); // final pass publishes visibility for the last completions
    }
}
