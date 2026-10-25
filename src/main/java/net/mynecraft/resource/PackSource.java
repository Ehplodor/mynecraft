package net.mynecraft.resource;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** One resource pack: a directory, a .zip, or a classpath folder, using the Java Edition layout. */
public interface PackSource extends Closeable {
    /** Returns the file at a pack-relative path (e.g. "assets/minecraft/textures/block/stone.png"), or null. */
    byte[] read(String path) throws IOException;

    String name();

    @Override
    default void close() throws IOException {}

    static PackSource open(Path path) throws IOException {
        if (Files.isDirectory(path)) return directory(path);
        return zip(path);
    }

    static PackSource directory(Path root) {
        return new PackSource() {
            public byte[] read(String p) throws IOException {
                Path f = root.resolve(p);
                return Files.isRegularFile(f) ? Files.readAllBytes(f) : null;
            }
            public String name() { return root.getFileName().toString(); }
        };
    }

    static PackSource zip(Path file) throws IOException {
        ZipFile zip = new ZipFile(file.toFile());
        // Many downloaded packs wrap everything in one top-level folder: find where pack.mcmeta lives.
        String prefix = "";
        if (zip.getEntry("pack.mcmeta") == null) {
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                String n = e.nextElement().getName();
                if (n.endsWith("/pack.mcmeta") && n.indexOf('/') == n.length() - "/pack.mcmeta".length()) {
                    prefix = n.substring(0, n.length() - "pack.mcmeta".length());
                    break;
                }
            }
        }
        String root = prefix;
        return new PackSource() {
            public byte[] read(String p) throws IOException {
                ZipEntry e = zip.getEntry(root + p);
                if (e == null) return null;
                try (InputStream in = zip.getInputStream(e)) { return in.readAllBytes(); }
            }
            public String name() { return file.getFileName().toString(); }
            public void close() throws IOException { zip.close(); }
        };
    }

    static PackSource classpath(String folder) {
        return new PackSource() {
            public byte[] read(String p) throws IOException {
                try (InputStream in = PackSource.class.getResourceAsStream("/" + folder + "/" + p)) {
                    return in == null ? null : in.readAllBytes();
                }
            }
            public String name() { return folder; }
        };
    }
}
