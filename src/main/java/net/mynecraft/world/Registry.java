package net.mynecraft.world;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Names to dense runtime ids, in registration order. Ids are never saved (saves use names), so they may
 * change between versions; a registry is frozen once loading is over, before ids are handed out to
 * hot paths. Data packs and mods register through the same calls before the freeze.
 */
public final class Registry {
    private final String kind;
    private final List<String> names = new ArrayList<>();
    private final Map<String, Integer> ids = new HashMap<>();
    private boolean frozen;

    public Registry(String kind) {
        this.kind = kind;
    }

    /** Registers a name and returns its id; registering an existing name returns the existing id. */
    public int register(String name) {
        Integer known = ids.get(name);
        if (known != null) return known;
        if (frozen) throw new IllegalStateException(kind + " registry is frozen, cannot add " + name);
        int id = names.size();
        names.add(name);
        ids.put(name, id);
        return id;
    }

    /** Id of a name, or -1. */
    public int id(String name) {
        Integer id = ids.get(name);
        return id == null ? -1 : id;
    }

    public String name(int id) {
        return names.get(id);
    }

    public int size() {
        return names.size();
    }

    public void freeze() {
        frozen = true;
    }

    public boolean frozen() {
        return frozen;
    }
}
