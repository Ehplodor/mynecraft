package net.mynecraft.world;

import java.util.Map;
import java.util.TreeMap;

/**
 * First-fit allocator over a linear range of quads, with coalescing of adjacent free blocks.
 * Pure bookkeeping (no GL), so it is unit-testable; the renderer maps offsets into one big VBO.
 */
public final class QuadAllocator {
    private final TreeMap<Integer, Integer> free = new TreeMap<>(); // offset -> size
    private int capacity;
    private int used;

    public QuadAllocator(int capacity) {
        this.capacity = capacity;
        free.put(0, capacity);
    }

    public int capacity() {
        return capacity;
    }

    public int used() {
        return used;
    }

    /** @return offset, or -1 if no free block is large enough (caller grows, then retries). */
    public int allocate(int size) {
        for (Map.Entry<Integer, Integer> e : free.entrySet()) {
            if (e.getValue() >= size) {
                int off = e.getKey(), rest = e.getValue() - size;
                free.remove(off);
                if (rest > 0) free.put(off + size, rest);
                used += size;
                return off;
            }
        }
        return -1;
    }

    public void release(int offset, int size) {
        used -= size;
        Map.Entry<Integer, Integer> next = free.ceilingEntry(offset);
        if (next != null && next.getKey() == offset + size) {
            size += next.getValue();
            free.remove(next.getKey());
        }
        Map.Entry<Integer, Integer> prev = free.lowerEntry(offset);
        if (prev != null && prev.getKey() + prev.getValue() == offset) {
            offset = prev.getKey();
            size += prev.getValue();
        }
        free.put(offset, size);
    }

    /** Extends the range; the new space joins the trailing free block if there is one. */
    public void grow(int newCapacity) {
        int extra = newCapacity - capacity;
        int oldCapacity = capacity;
        capacity = newCapacity;
        used += extra; // release() subtracts it back
        release(oldCapacity, extra);
    }
}
