package de.bauplan;

import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** Minimaler NBT-Leser (reicht fuer Sponge-.schem-Dateien). */
final class NbtReader {
    private NbtReader() {}

    static Map<String, Object> read(File file) throws IOException {
        byte[] raw = Files.readAllBytes(file.toPath());
        InputStream in = new ByteArrayInputStream(raw);
        if (raw.length > 2 && (raw[0] & 0xFF) == 0x1f && (raw[1] & 0xFF) == 0x8b) {
            in = new GZIPInputStream(in);
        }
        try (DataInputStream d = new DataInputStream(new BufferedInputStream(in))) {
            int type = d.readUnsignedByte();
            if (type != 10) throw new IOException("Keine gueltige NBT-Datei");
            d.readUTF();
            return readCompound(d);
        }
    }

    private static Map<String, Object> readCompound(DataInputStream d) throws IOException {
        Map<String, Object> map = new HashMap<>();
        while (true) {
            int type = d.readUnsignedByte();
            if (type == 0) break;
            String name = d.readUTF();
            map.put(name, readPayload(d, type));
        }
        return map;
    }

    private static Object readPayload(DataInputStream d, int type) throws IOException {
        switch (type) {
            case 1: return d.readByte();
            case 2: return d.readShort();
            case 3: return d.readInt();
            case 4: return d.readLong();
            case 5: return d.readFloat();
            case 6: return d.readDouble();
            case 7: {
                byte[] arr = new byte[d.readInt()];
                d.readFully(arr);
                return arr;
            }
            case 8: return d.readUTF();
            case 9: {
                int elemType = d.readUnsignedByte();
                int len = d.readInt();
                List<Object> list = new ArrayList<>(Math.max(len, 0));
                for (int i = 0; i < len; i++) list.add(readPayload(d, elemType));
                return list;
            }
            case 10: return readCompound(d);
            case 11: {
                int[] arr = new int[d.readInt()];
                for (int i = 0; i < arr.length; i++) arr[i] = d.readInt();
                return arr;
            }
            case 12: {
                long[] arr = new long[d.readInt()];
                for (int i = 0; i < arr.length; i++) arr[i] = d.readLong();
                return arr;
            }
            default: throw new IOException("Unbekannter NBT-Typ: " + type);
        }
    }
}
