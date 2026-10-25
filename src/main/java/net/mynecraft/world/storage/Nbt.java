package net.mynecraft.world.storage;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Named Binary Tag codec (Java Edition, big-endian). Tags map to plain Java values:
 * Byte, Short, Integer, Long, Float, Double, byte[], String, {@link NbtList}, {@code Map<String,Object>}
 * (compound, insertion-ordered), int[], long[].
 */
public final class Nbt {
    public static final byte END = 0, BYTE = 1, SHORT = 2, INT = 3, LONG = 4, FLOAT = 5, DOUBLE = 6,
        BYTE_ARRAY = 7, STRING = 8, LIST = 9, COMPOUND = 10, INT_ARRAY = 11, LONG_ARRAY = 12;

    /** List tag: keeps its element type so empty lists round-trip. */
    public record NbtList(byte elementType, List<Object> values) {
        public NbtList(byte elementType) { this(elementType, new ArrayList<>()); }
    }

    private Nbt() {}

    public static Map<String, Object> compound() {
        return new LinkedHashMap<>();
    }

    // ---- reading ----

    /** Reads a root compound (its name is ignored). */
    public static Map<String, Object> read(InputStream in) throws IOException {
        DataInputStream d = new DataInputStream(new BufferedInputStream(in));
        byte type = d.readByte();
        if (type != COMPOUND) throw new IOException("Root tag is not a compound: " + type);
        d.readUTF();
        return readCompound(d);
    }

    public static Map<String, Object> readGzip(InputStream in) throws IOException {
        return read(new GZIPInputStream(in));
    }

    public static Map<String, Object> read(byte[] data) throws IOException {
        return read(new ByteArrayInputStream(data));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> readCompound(DataInput d) throws IOException {
        Map<String, Object> m = compound();
        for (byte type; (type = d.readByte()) != END; ) {
            String name = d.readUTF();
            m.put(name, readPayload(d, type, 0));
        }
        return m;
    }

    private static Object readPayload(DataInput d, byte type, int depth) throws IOException {
        if (depth > 512) throw new IOException("NBT nesting too deep");
        switch (type) {
            case BYTE: return d.readByte();
            case SHORT: return d.readShort();
            case INT: return d.readInt();
            case LONG: return d.readLong();
            case FLOAT: return d.readFloat();
            case DOUBLE: return d.readDouble();
            case BYTE_ARRAY: { byte[] a = new byte[length(d)]; d.readFully(a); return a; }
            case STRING: return d.readUTF();
            case LIST: {
                byte et = d.readByte();
                int n = length(d);
                NbtList l = new NbtList(et, new ArrayList<>(n));
                for (int i = 0; i < n; i++) l.values().add(readPayload(d, et, depth + 1));
                return l;
            }
            case COMPOUND: {
                Map<String, Object> m = compound();
                for (byte t; (t = d.readByte()) != END; ) m.put(d.readUTF(), readPayload(d, t, depth + 1));
                return m;
            }
            case INT_ARRAY: { int[] a = new int[length(d)]; for (int i = 0; i < a.length; i++) a[i] = d.readInt(); return a; }
            case LONG_ARRAY: { long[] a = new long[length(d)]; for (int i = 0; i < a.length; i++) a[i] = d.readLong(); return a; }
            default: throw new IOException("Unknown NBT tag type " + type);
        }
    }

    private static int length(DataInput d) throws IOException {
        int n = d.readInt();
        if (n < 0 || n > (64 << 20)) throw new IOException("Bad NBT length " + n);
        return n;
    }

    // ---- writing ----

    public static void write(OutputStream out, Map<String, Object> root) throws IOException {
        DataOutputStream d = new DataOutputStream(new BufferedOutputStream(out));
        d.writeByte(COMPOUND);
        d.writeUTF("");
        writePayload(d, root);
        d.flush();
    }

    public static void writeGzip(OutputStream out, Map<String, Object> root) throws IOException {
        GZIPOutputStream gz = new GZIPOutputStream(out);
        write(gz, root);
        gz.finish();
    }

    public static byte[] toBytes(Map<String, Object> root) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(8192);
        write(bos, root);
        return bos.toByteArray();
    }

    static byte typeOf(Object v) {
        if (v instanceof Byte) return BYTE;
        if (v instanceof Short) return SHORT;
        if (v instanceof Integer) return INT;
        if (v instanceof Long) return LONG;
        if (v instanceof Float) return FLOAT;
        if (v instanceof Double) return DOUBLE;
        if (v instanceof byte[]) return BYTE_ARRAY;
        if (v instanceof String) return STRING;
        if (v instanceof NbtList) return LIST;
        if (v instanceof Map) return COMPOUND;
        if (v instanceof int[]) return INT_ARRAY;
        if (v instanceof long[]) return LONG_ARRAY;
        throw new IllegalArgumentException("Not an NBT value: " + v.getClass());
    }

    @SuppressWarnings("unchecked")
    private static void writePayload(DataOutput d, Object v) throws IOException {
        switch (typeOf(v)) {
            case BYTE -> d.writeByte((Byte) v);
            case SHORT -> d.writeShort((Short) v);
            case INT -> d.writeInt((Integer) v);
            case LONG -> d.writeLong((Long) v);
            case FLOAT -> d.writeFloat((Float) v);
            case DOUBLE -> d.writeDouble((Double) v);
            case BYTE_ARRAY -> { byte[] a = (byte[]) v; d.writeInt(a.length); d.write(a); }
            case STRING -> d.writeUTF((String) v);
            case LIST -> {
                NbtList l = (NbtList) v;
                d.writeByte(l.values().isEmpty() ? l.elementType() : typeOf(l.values().get(0)));
                d.writeInt(l.values().size());
                for (Object e : l.values()) writePayload(d, e);
            }
            case COMPOUND -> {
                for (var e : ((Map<String, Object>) v).entrySet()) {
                    d.writeByte(typeOf(e.getValue()));
                    d.writeUTF(e.getKey());
                    writePayload(d, e.getValue());
                }
                d.writeByte(END);
            }
            case INT_ARRAY -> { int[] a = (int[]) v; d.writeInt(a.length); for (int x : a) d.writeInt(x); }
            case LONG_ARRAY -> { long[] a = (long[]) v; d.writeInt(a.length); for (long x : a) d.writeLong(x); }
            default -> throw new IllegalStateException();
        }
    }

    // ---- typed access helpers ----

    @SuppressWarnings("unchecked")
    public static Map<String, Object> getCompound(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof Map ? (Map<String, Object>) v : null;
    }

    public static List<Object> getList(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof NbtList l ? l.values() : List.of();
    }

    public static int getInt(Map<String, Object> m, String key, int def) {
        return m.get(key) instanceof Number n ? n.intValue() : def;
    }

    public static long getLong(Map<String, Object> m, String key, long def) {
        return m.get(key) instanceof Number n ? n.longValue() : def;
    }
}
