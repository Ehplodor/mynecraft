package net.mynecraft.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Named waypoints of a world, kept in {@code waypoints.json} next to it. Added with B (at the
 * player) or a right click on the world map, shown on both maps and in the world (name and
 * distance over the spot).
 */
final class Waypoints {
    static final int[] COLORS = {0xFF5050, 0x50C8FF, 0xFFD23C, 0x78E650, 0xD278FF, 0xFF9632};

    static final class Waypoint {
        String name;
        int x, y, z, color;

        Waypoint(String name, int x, int y, int z, int color) {
            this.name = name;
            this.x = x;
            this.y = y;
            this.z = z;
            this.color = color;
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path file;
    final List<Waypoint> list = new ArrayList<>();
    private int created;

    Waypoints(Path worldDir) {
        file = worldDir.resolve("waypoints.json");
        if (Files.exists(file)) {
            try {
                List<Waypoint> read = GSON.fromJson(Files.readString(file), new TypeToken<List<Waypoint>>() {}.getType());
                if (read != null) list.addAll(read);
            } catch (IOException | RuntimeException e) {
                System.err.println("[waypoints] cannot read " + file + ": " + e.getMessage());
            }
        }
        created = list.size();
    }

    Waypoint add(int x, int y, int z) {
        created++;
        Waypoint w = new Waypoint("Repère " + created, x, y, z, COLORS[(created - 1) % COLORS.length]);
        list.add(w);
        save();
        return w;
    }

    void remove(Waypoint w) {
        list.remove(w);
        save();
    }

    void save() {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(list));
        } catch (IOException e) {
            System.err.println("[waypoints] cannot save " + file + ": " + e.getMessage());
        }
    }
}
