package net.mynecraft.resource;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * One resource pack or data pack: a directory, a .zip, or a classpath folder, using the Java
 * Edition layout (assets/... for resource packs, data/... for data packs).
 */
public interface PackSource extends Closeable {
    /** Returns the file at a pack-relative path (e.g. "assets/minecraft/textures/block/stone.png"), or null. */
    byte[] read(String path) throws IOException;

    /** Pack-relative paths of all files under a folder prefix (e.g. "data/"), for data packs. */
    default java.util.List<String> list(String prefix) throws IOException {
        return java.util.List.of();
    }

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
            public java.util.List<String> list(String prefix) throws IOException {
                Path dir = root.resolve(prefix);
                if (!Files.isDirectory(dir)) return java.util.List.of();
                try (var walk = Files.walk(dir)) {
                    return walk.filter(Files::isRegularFile)
                        .map(f -> root.relativize(f).toString().replace('\\', '/')).sorted().toList();
                }
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
            public java.util.List<String> list(String prefix) {
                java.util.List<String> out = new java.util.ArrayList<>();
                for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                    String n = e.nextElement().getName();
                    if (!n.endsWith("/") && n.startsWith(root + prefix)) out.add(n.substring(root.length()));
                }
                java.util.Collections.sort(out);
                return out;
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
            /** Classpath folders cannot be listed portably (jar or directory): they ship an index.txt. */
            public java.util.List<String> list(String prefix) throws IOException {
                byte[] index = read("index.txt");
                if (index == null) return java.util.List.of();
                return new String(index, java.nio.charset.StandardCharsets.UTF_8).lines()
                    .filter(l -> l.startsWith(prefix)).toList();
            }
        };
    }
}
