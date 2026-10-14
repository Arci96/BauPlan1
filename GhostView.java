package de.bauplan;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.*;

/**
 * Zeigt einem einzelnen Spieler einen Plan als Hologramm (BlockDisplay-Entities, nur fuer ihn sichtbar).
 * Die Hologramm-Bloecke haben keine Kollision und keine Hitbox - man kann also einfach
 * "durch" sie hindurch bauen. Sobald ein Block gebaut wurde, verschwindet das Hologramm dort.
 */
final class GhostView {
    private final BauPlanPlugin plugin;
    private final Player player;
    private final Plan base;
    private Plan plan;
    private int rotation = 0;

    private World world;
    private int ox, oy, oz;
    private int layer = -1; // -1 = alle

    private final Map<Long, BlockDisplay> ghosts = new HashMap<>();
    private final Map<Material, Integer> missingMaterials = new HashMap<>();
    private int missing = 0;
    private boolean truncated = false;

    GhostView(BauPlanPlugin plugin, Player player, Plan base, Location origin) {
        this.plugin = plugin;
        this.player = player;
        this.base = base;
        this.plan = base;
        this.world = origin.getWorld();
        this.ox = origin.getBlockX();
        this.oy = origin.getBlockY();
        this.oz = origin.getBlockZ();
    }

    Plan plan() { return plan; }
    int layer() { return layer; }
    int missing() { return missing; }
    boolean truncated() { return truncated; }
    int shown() { return ghosts.size(); }
    Map<Material, Integer> missingMaterials() { return missingMaterials; }
    Location origin() { return new Location(world, ox, oy, oz); }
    String originString() { return world.getName() + " " + ox + " " + oy + " " + oz; }

    void setOrigin(Location l) {
        this.world = l.getWorld();
        this.ox = l.getBlockX();
        this.oy = l.getBlockY();
        this.oz = l.getBlockZ();
        clear();
        refresh();
    }

    void move(int dx, int dy, int dz) {
        ox += dx;
        oy += dy;
        oz += dz;
        clear();
        refresh();
    }

    void rotate() {
        rotation = (rotation + 1) % 4;
        plan = base;
        for (int i = 0; i < rotation; i++) plan = plan.rotated90();
        clear();
        refresh();
    }

    void setLayer(int layer) {
        this.layer = Math.max(-1, Math.min(layer, plan.sy - 1));
        clear();
        refresh();
    }

    /** Wasser und Lava haben kein Block-Modell und waeren als Hologramm unsichtbar - daher Ersatzbloecke. */
    private static BlockData displayData(BlockData d) {
        Material m = d.getMaterial();
        if (m == Material.WATER) return Material.LIGHT_BLUE_STAINED_GLASS.createBlockData();
        if (m == Material.LAVA) return Material.ORANGE_STAINED_GLASS.createBlockData();
        return d;
    }

    private static long key(int x, int y, int z) {
        return (((long) x & 0x3FFFFFFL) << 38) | (((long) z & 0x3FFFFFFL) << 12) | ((long) (y + 2048) & 0xFFFL);
    }

    /** Berechnet, was noch fehlt, und gleicht die Hologramme damit ab. */
    void refresh() {
        if (!player.isOnline()) return;

        int maxGhosts = plugin.maxGhosts();
        int radius = plugin.ghostRadius();
        long radiusSq = (long) radius * radius;
        boolean sameWorld = player.getWorld().equals(world);
        int px = player.getLocation().getBlockX();
        int pz = player.getLocation().getBlockZ();

        missing = 0;
        truncated = false;
        missingMaterials.clear();
        Map<Long, BlockData> desired = new LinkedHashMap<>();

        int y0 = layer >= 0 ? layer : 0;
        int y1 = layer >= 0 ? layer : plan.sy - 1;
        for (int y = y0; y <= y1; y++) {
            for (int z = 0; z < plan.sz; z++) {
                for (int x = 0; x < plan.sx; x++) {
                    BlockData d = plan.get(x, y, z);
                    Material m = d.getMaterial();
                    if (m.isAir()) continue;
                    int wx = ox + x, wy = oy + y, wz = oz + z;
                    if (!world.isChunkLoaded(wx >> 4, wz >> 4)) continue;
                    Block b = world.getBlockAt(wx, wy, wz);
                    boolean fluid = m == Material.WATER || m == Material.LAVA;
                    if (fluid && d instanceof Levelled pl && pl.getLevel() != 0) continue; // nur Quellbloecke zeigen
                    if (b.getType() == m) {
                        // Fluessigkeit gilt nur als gebaut, wenn dort ein Quellblock steht
                        if (!fluid || !(b.getBlockData() instanceof Levelled wl) || wl.getLevel() == 0) continue;
                    }
                    if (!(b.isEmpty() || b.isReplaceable())) continue; // etwas anderes steht im Weg
                    missing++;
                    missingMaterials.merge(d.getPlacementMaterial(), 1, Integer::sum);
                    if (!sameWorld) continue;
                    long dx = wx - px, dz = wz - pz;
                    if (dx * dx + dz * dz > radiusSq) continue;
                    if (desired.size() >= maxGhosts) {
                        truncated = true;
                        continue;
                    }
                    desired.put(key(wx, wy, wz), displayData(d));
                }
            }
        }

        // Nicht mehr benoetigte oder ungueltige Hologramme entfernen
        Iterator<Map.Entry<Long, BlockDisplay>> it = ghosts.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, BlockDisplay> e = it.next();
            BlockDisplay ent = e.getValue();
            if (!desired.containsKey(e.getKey()) || !ent.isValid()) {
                if (ent.isValid()) ent.remove();
                it.remove();
            }
        }

        // Fehlende Hologramme erzeugen
        if (!sameWorld) return;
        float s = plugin.ghostScale();
        float off = (1f - s) / 2f;
        Transformation tf = new Transformation(
                new Vector3f(off, off, off), new AxisAngle4f(),
                new Vector3f(s, s, s), new AxisAngle4f());

        for (Map.Entry<Long, BlockData> e : desired.entrySet()) {
            if (ghosts.containsKey(e.getKey())) continue;
            // Position aus dem Plan zurueckrechnen ist unnoetig: wir suchen sie ueber den Key
            long k = e.getKey();
            int wx = (int) (k >> 38);
            if ((wx & 0x2000000) != 0) wx |= 0xFC000000; // Vorzeichen
            int wz = (int) ((k >> 12) & 0x3FFFFFFL);
            if ((wz & 0x2000000) != 0) wz |= 0xFC000000;
            int wy = (int) (k & 0xFFFL) - 2048;

            BlockData data = e.getValue();
            Location loc = new Location(world, wx, wy, wz);
            BlockDisplay display = world.spawn(loc, BlockDisplay.class, d -> {
                d.setBlock(data);
                d.setVisibleByDefault(false);
                d.setPersistent(false);
                d.setBrightness(new Display.Brightness(15, 15));
                d.setTransformation(tf);
                d.setViewRange(1.5f);
            });
            player.showEntity(plugin, display);
            ghosts.put(e.getKey(), display);
        }
    }

    void clear() {
        for (BlockDisplay d : ghosts.values()) {
            if (d.isValid()) d.remove();
        }
        ghosts.clear();
    }
}
