package net.mynecraft.resource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/** Ordered pack stack: packs added later override earlier ones, like the in-game pack list. */
public final class ResourceManager implements AutoCloseable {
    private final List<PackSource> packs = new ArrayList<>();

    public ResourceManager add(PackSource pack) {
        packs.add(pack);
        return this;
    }

    public byte[] read(String path) {
        try {
            for (int i = packs.size() - 1; i >= 0; i--) {
                byte[] data = packs.get(i).read(path);
                if (data != null) return data;
            }
            return null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** "minecraft:block/stone" + ("textures", ".png") -> "assets/minecraft/textures/block/stone.png". */
    public static String path(String id, String kind, String ext) {
        int c = id.indexOf(':');
        String ns = c < 0 ? "minecraft" : id.substring(0, c);
        return "assets/" + ns + "/" + kind + "/" + id.substring(c + 1) + ext;
    }

    public static String normalize(String id) {
        return id.indexOf(':') < 0 ? "minecraft:" + id : id;
    }

    @Override
    public void close() throws IOException {
        for (PackSource p : packs) p.close();
    }
}
