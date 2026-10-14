package de.bauplan;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.structure.StructureRotation;

import java.io.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Ein Bauplan: ein Quader aus Bloecken. Index = (y * sz + z) * sx + x */
public final class Plan {
    private static final int MAGIC = 0x42504C4E; // "BPLN"

    public final String name;
    public final int sx, sy, sz;
    public final List<BlockData> palette;
    public final int[] blocks;

    public Plan(String name, int sx, int sy, int sz, List<BlockData> palette, int[] blocks) {
        this.name = name;
        this.sx = sx;
        this.sy = sy;
        this.sz = sz;
        this.palette = palette;
        this.blocks = blocks;
    }

    public int index(int x, int y, int z) {
        return (y * sz + z) * sx + x;
    }

    public BlockData get(int x, int y, int z) {
        return palette.get(blocks[index(x, y, z)]);
    }

    /** Anzahl der Nicht-Luft-Bloecke. */
    public int solidCount() {
        boolean[] air = new boolean[palette.size()];
        for (int i = 0; i < air.length; i++) air[i] = palette.get(i).getMaterial().isAir();
        int n = 0;
        for (int b : blocks) if (!air[b]) n++;
        return n;
    }

    /** Alle benoetigten Materialien (als Item) mit Anzahl. */
    public Map<Material, Integer> countMaterials() {
        Map<Material, Integer> map = new HashMap<>();
        for (int b : blocks) {
            BlockData d = palette.get(b);
            if (d.getMaterial().isAir()) continue;
            map.merge(d.getPlacementMaterial(), 1, Integer::sum);
        }
        return map;
    }

    /** Plan um 90 Grad im Uhrzeigersinn drehen (von oben gesehen). */
    public Plan rotated90() {
        int nsx = sz, nsz = sx;
        List<BlockData> np = new ArrayList<>(palette.size());
        for (BlockData d : palette) {
            BlockData c = d.clone();
            c.rotate(StructureRotation.CLOCKWISE_90);
            np.add(c);
        }
        int[] nb = new int[blocks.length];
        for (int y = 0; y < sy; y++) {
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    int nx = sz - 1 - z;
                    int nz = x;
                    nb[(y * nsz + nz) * nsx + nx] = blocks[index(x, y, z)];
                }
            }
        }
        return new Plan(name, nsx, sy, nsz, np, nb);
    }

    // ------------------------------------------------------------------ Aufnehmen

    public static Plan capture(String name, Location a, Location b, int maxVolume) {
        World w = a.getWorld();
        int x1 = Math.min(a.getBlockX(), b.getBlockX()), x2 = Math.max(a.getBlockX(), b.getBlockX());
        int y1 = Math.min(a.getBlockY(), b.getBlockY()), y2 = Math.max(a.getBlockY(), b.getBlockY());
        int z1 = Math.min(a.getBlockZ(), b.getBlockZ()), z2 = Math.max(a.getBlockZ(), b.getBlockZ());
        int sx = x2 - x1 + 1, sy = y2 - y1 + 1, sz = z2 - z1 + 1;
        long volume = (long) sx * sy * sz;
        if (volume > maxVolume) {
            throw new IllegalArgumentException("Bereich zu gross (" + volume + " Bloecke, Maximum " + maxVolume + ")");
        }
        List<BlockData> palette = new ArrayList<>();
        Map<String, Integer> lookup = new HashMap<>();
        int[] blocks = new int[(int) volume];
        for (int y = 0; y < sy; y++) {
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    BlockData d = w.getBlockAt(x1 + x, y1 + y, z1 + z).getBlockData();
                    String key = d.getAsString();
                    Integer idx = lookup.get(key);
                    if (idx == null) {
                        idx = palette.size();
                        palette.add(d);
                        lookup.put(key, idx);
                    }
                    blocks[(y * sz + z) * sx + x] = idx;
                }
            }
        }
        return new Plan(name, sx, sy, sz, palette, blocks);
    }

    // ------------------------------------------------------------------ Speichern / Laden

    public void save(File file) throws IOException {
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
                new GZIPOutputStream(new FileOutputStream(file))))) {
            out.writeInt(MAGIC);
            out.writeInt(1);
            out.writeInt(sx);
            out.writeInt(sy);
            out.writeInt(sz);
            out.writeInt(palette.size());
            for (BlockData d : palette) out.writeUTF(d.getAsString());
            for (int b : blocks) out.writeInt(b);
        }
    }

    public static Plan load(String name, File file) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                new GZIPInputStream(new FileInputStream(file))))) {
            if (in.readInt() != MAGIC) throw new IOException("Keine BauPlan-Datei");
            in.readInt(); // Version
            int sx = in.readInt(), sy = in.readInt(), sz = in.readInt();
            int pSize = in.readInt();
            List<BlockData> palette = new ArrayList<>(pSize);
            for (int i = 0; i < pSize; i++) palette.add(parse(in.readUTF()));
            int[] blocks = new int[sx * sy * sz];
            for (int i = 0; i < blocks.length; i++) blocks[i] = in.readInt();
            return new Plan(name, sx, sy, sz, palette, blocks);
        }
    }

    private static BlockData parse(String s) {
        try {
            return Bukkit.createBlockData(s);
        } catch (IllegalArgumentException e) {
            return Material.AIR.createBlockData();
        }
    }

    // ------------------------------------------------------------------ .litematic Import

    @SuppressWarnings("unchecked")
    public static Plan fromLitematic(String name, File file) throws IOException {
        Map<String, Object> root = NbtReader.read(file);
        if (!(root.get("Regions") instanceof Map)) throw new IOException("Keine gueltige .litematic-Datei");
        Map<String, Object> regions = (Map<String, Object>) root.get("Regions");
        if (regions.isEmpty()) throw new IOException("Die Datei enthaelt keine Bereiche");

        final class Region {
            int x0, y0, z0, ax, ay, az;
            List<String> pal = new ArrayList<>();
            long[] states;
        }
        List<Region> list = new ArrayList<>();
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

        for (Object ro : regions.values()) {
            Map<String, Object> r = (Map<String, Object>) ro;
            Map<String, Object> pos = (Map<String, Object>) r.get("Position");
            Map<String, Object> size = (Map<String, Object>) r.get("Size");
            List<Object> palTag = (List<Object>) r.get("BlockStatePalette");
            if (pos == null || size == null || palTag == null || !(r.get("BlockStates") instanceof long[])) {
                throw new IOException("Unerwartetes .litematic-Format");
            }
            Region reg = new Region();
            int px = ((Number) pos.get("x")).intValue(), py = ((Number) pos.get("y")).intValue(), pz = ((Number) pos.get("z")).intValue();
            int sx = ((Number) size.get("x")).intValue(), sy = ((Number) size.get("y")).intValue(), sz = ((Number) size.get("z")).intValue();
            reg.ax = Math.abs(sx);
            reg.ay = Math.abs(sy);
            reg.az = Math.abs(sz);
            reg.x0 = sx < 0 ? px + sx + 1 : px;
            reg.y0 = sy < 0 ? py + sy + 1 : py;
            reg.z0 = sz < 0 ? pz + sz + 1 : pz;
            for (Object po : palTag) {
                Map<String, Object> e = (Map<String, Object>) po;
                StringBuilder sb = new StringBuilder((String) e.get("Name"));
                Object props = e.get("Properties");
                if (props instanceof Map && !((Map<?, ?>) props).isEmpty()) {
                    sb.append('[');
                    boolean first = true;
                    for (Map.Entry<String, Object> pe : ((Map<String, Object>) props).entrySet()) {
                        if (!first) sb.append(',');
                        first = false;
                        sb.append(pe.getKey()).append('=').append(pe.getValue());
                    }
                    sb.append(']');
                }
                reg.pal.add(sb.toString());
            }
            reg.states = (long[]) r.get("BlockStates");
            list.add(reg);
            minX = Math.min(minX, reg.x0);
            minY = Math.min(minY, reg.y0);
            minZ = Math.min(minZ, reg.z0);
            maxX = Math.max(maxX, reg.x0 + reg.ax - 1);
            maxY = Math.max(maxY, reg.y0 + reg.ay - 1);
            maxZ = Math.max(maxZ, reg.z0 + reg.az - 1);
        }

        long w = (long) maxX - minX + 1, h = (long) maxY - minY + 1, l = (long) maxZ - minZ + 1;
        if (w * h * l > 20_000_000L) throw new IOException("Schematic ist zu gross");
        int W = (int) w, H = (int) h, L = (int) l;

        List<BlockData> palette = new ArrayList<>();
        Map<String, Integer> lookup = new HashMap<>();
        palette.add(Material.AIR.createBlockData());
        lookup.put("minecraft:air", 0);
        int[] blocks = new int[W * H * L];

        for (Region reg : list) {
            int n = reg.pal.size();
            int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(Math.max(n - 1, 1)));
            long mask = (1L << bits) - 1;
            int[] map = new int[n];
            for (int i = 0; i < n; i++) {
                String str = reg.pal.get(i);
                Integer id = lookup.get(str);
                if (id == null) {
                    id = palette.size();
                    palette.add(parse(str));
                    lookup.put(str, id);
                }
                map[i] = id;
            }
            for (int y = 0; y < reg.ay; y++) {
                for (int z = 0; z < reg.az; z++) {
                    for (int x = 0; x < reg.ax; x++) {
                        long index = ((long) y * reg.az + z) * reg.ax + x;
                        long bitPos = index * bits;
                        int li = (int) (bitPos >>> 6);
                        int off = (int) (bitPos & 63);
                        if (li >= reg.states.length) continue;
                        long v = (reg.states[li] >>> off) & mask;
                        if (off + bits > 64 && li + 1 < reg.states.length) {
                            v |= (reg.states[li + 1] << (64 - off)) & mask;
                        }
                        if (v >= n) continue;
                        int gid = map[(int) v];
                        if (palette.get(gid).getMaterial().isAir()) continue;
                        int gx = reg.x0 + x - minX, gy = reg.y0 + y - minY, gz = reg.z0 + z - minZ;
                        blocks[(gy * L + gz) * W + gx] = gid;
                    }
                }
            }
        }
        return new Plan(name, W, H, L, palette, blocks);
    }

    // ------------------------------------------------------------------ .schem Import

    @SuppressWarnings("unchecked")
    public static Plan fromSchematic(String name, File file) throws IOException {
        Map<String, Object> root = NbtReader.read(file);
        Map<String, Object> schem = root;
        if (root.get("Schematic") instanceof Map) schem = (Map<String, Object>) root.get("Schematic");

        if (!schem.containsKey("Width") || !schem.containsKey("Height") || !schem.containsKey("Length")) {
            throw new IOException("Format nicht unterstuetzt (nur Sponge .schem, z.B. von WorldEdit)");
        }
        int w = ((Number) schem.get("Width")).intValue() & 0xFFFF;
        int h = ((Number) schem.get("Height")).intValue() & 0xFFFF;
        int l = ((Number) schem.get("Length")).intValue() & 0xFFFF;

        Map<String, Object> pal;
        byte[] data;
        if (schem.get("Blocks") instanceof Map) { // Sponge v3
            Map<String, Object> blocksTag = (Map<String, Object>) schem.get("Blocks");
            pal = (Map<String, Object>) blocksTag.get("Palette");
            data = (byte[]) blocksTag.get("Data");
        } else { // Sponge v2
            pal = (Map<String, Object>) schem.get("Palette");
            data = (byte[]) schem.get("BlockData");
        }
        if (pal == null || data == null) throw new IOException("Schematic enthaelt keine Blockdaten");

        int maxId = 0;
        for (Object o : pal.values()) maxId = Math.max(maxId, ((Number) o).intValue());
        List<BlockData> palette = new ArrayList<>(Collections.nCopies(maxId + 1, Material.AIR.createBlockData()));
        for (Map.Entry<String, Object> e : pal.entrySet()) {
            palette.set(((Number) e.getValue()).intValue(), parse(e.getKey()));
        }

        int total = w * h * l;
        int[] blocks = new int[total];
        int i = 0, idx = 0;
        while (i < data.length && idx < total) {
            int value = 0, shift = 0;
            while (true) {
                int b = data[i++] & 0xFF;
                value |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) break;
                shift += 7;
            }
            blocks[idx++] = value < palette.size() ? value : 0;
        }
        // Sponge-Index = (y * length + z) * width + x  -> identisch zu unserem Format
        return new Plan(name, w, h, l, palette, blocks);
    }
}
