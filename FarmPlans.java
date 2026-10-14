package de.bauplan;

import org.bukkit.Bukkit;
import org.bukkit.block.data.BlockData;

import java.util.*;

/** Eingebaute Pläne, die beim ersten Start automatisch angelegt werden. */
final class FarmPlans {
    private FarmPlans() {}

    static Map<String, Plan> all() {
        Map<String, Plan> m = new LinkedHashMap<>();
        m.put("huehnerfarm", chickenFarm());
        return m;
    }

    /** Kleiner Baukasten fuer Pläne im Code. */
    private static final class B {
        final String name;
        final int sx, sy, sz;
        final List<BlockData> palette = new ArrayList<>();
        final Map<String, Integer> lookup = new HashMap<>();
        final int[] blocks;

        B(String name, int sx, int sy, int sz) {
            this.name = name;
            this.sx = sx;
            this.sy = sy;
            this.sz = sz;
            this.blocks = new int[sx * sy * sz];
            id("minecraft:air"); // Index 0 = Luft
        }

        int id(String s) {
            String full = s.contains(":") ? s : "minecraft:" + s;
            Integer i = lookup.get(full);
            if (i == null) {
                i = palette.size();
                palette.add(Bukkit.createBlockData(full));
                lookup.put(full, i);
            }
            return i;
        }

        void set(int x, int y, int z, String s) {
            blocks[(y * sz + z) * sx + x] = id(s);
        }

        void fill(int x1, int y1, int z1, int x2, int y2, int z2, String s) {
            for (int y = y1; y <= y2; y++)
                for (int z = z1; z <= z2; z++)
                    for (int x = x1; x <= x2; x++) set(x, y, z, s);
        }

        Plan build() { return new Plan(name, sx, sy, sz, palette, blocks); }
    }

    /**
     * Eier-/Hühnerfarm: Der ganze Boden besteht aus Trichtern, die alle Eier in eine Kiste leiten.
     * Hühner einsetzen (ca. 8-12), Eier landen automatisch in der Kiste an der Aussenwand.
     */
    private static Plan chickenFarm() {
        B b = new B("huehnerfarm", 7, 5, 7);
        b.fill(0, 0, 0, 6, 0, 6, "stone_bricks");          // Fundament

        // Aussenwand: unten Holz, darueber Glas
        for (int x = 0; x <= 6; x++) {
            for (int z = 0; z <= 6; z++) {
                boolean ring = x == 0 || x == 6 || z == 0 || z == 6;
                if (!ring) continue;
                b.set(x, 1, z, "oak_planks");
                b.set(x, 2, z, "glass");
                b.set(x, 3, z, "glass");
            }
        }
        b.fill(0, 4, 0, 6, 4, 6, "glass");                  // Dach

        // Boden aus Trichtern (innen 5x5): Reihen fliessen nach Osten, Spalte x=5 nach Sueden
        for (int z = 1; z <= 5; z++) {
            for (int x = 1; x <= 4; x++) b.set(x, 1, z, "hopper[facing=east]");
        }
        for (int z = 1; z <= 4; z++) b.set(5, 1, z, "hopper[facing=south]");
        b.set(5, 1, 5, "hopper[facing=east]");              // letzter Trichter -> Kiste
        b.set(6, 1, 5, "chest[facing=east]");               // Kiste in der Ostwand

        // Tuer (Nordseite)
        b.set(3, 1, 0, "oak_door[facing=north,half=lower,hinge=left,open=false]");
        b.set(3, 2, 0, "oak_door[facing=north,half=upper,hinge=left,open=false]");
        return b.build();
    }
}
