package de.bauplan;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class BauPlanPlugin extends JavaPlugin implements Listener, TabExecutor {
    private static final Pattern NAME = Pattern.compile("[a-zA-Z0-9_\\-]{1,40}");
    private static final List<String> SUB = List.of("help", "pos1", "pos2", "save", "import", "list", "delete",
            "show", "hide", "info", "layer", "rotate", "move", "here", "materials", "refresh", "paste", "undo", "download");

    private File planDir, importDir;
    private final Map<UUID, Location[]> selections = new HashMap<>();
    private final Map<UUID, GhostView> views = new HashMap<>();
    private final Map<UUID, List<Snap>> undoStacks = new HashMap<>();
    private static final int MAX_PASTE = 300_000;
    private static final int MAX_DOWNLOAD = 16 * 1024 * 1024;

    private record Snap(Location loc, BlockData old) {}

    private int maxGhosts, ghostRadius, maxVolume;
    private float ghostScale;

    int maxGhosts() { return maxGhosts; }
    int ghostRadius() { return ghostRadius; }
    float ghostScale() { return ghostScale; }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        maxGhosts = getConfig().getInt("max-ghosts", 6000);
        ghostRadius = getConfig().getInt("ghost-radius", 48);
        ghostScale = (float) Math.max(0.1, Math.min(1.0, getConfig().getDouble("ghost-scale", 0.6)));
        maxVolume = getConfig().getInt("max-capture-volume", 1_000_000);
        int refreshTicks = Math.max(10, getConfig().getInt("refresh-ticks", 40));

        planDir = new File(getDataFolder(), "plans");
        importDir = new File(getDataFolder(), "import");
        planDir.mkdirs();
        importDir.mkdirs();
        for (Map.Entry<String, Plan> e : FarmPlans.all().entrySet()) {
            File f = planFile(e.getKey());
            if (!f.exists()) {
                try {
                    e.getValue().save(f);
                } catch (IOException ex) {
                    getLogger().warning("Konnte eingebauten Plan nicht anlegen: " + e.getKey());
                }
            }
        }

        Objects.requireNonNull(getCommand("bauplan")).setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);

        getServer().getScheduler().runTaskTimer(this, () -> {
            Iterator<Map.Entry<UUID, GhostView>> it = views.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<UUID, GhostView> e = it.next();
                Player p = getServer().getPlayer(e.getKey());
                if (p == null || !p.isOnline()) {
                    e.getValue().clear();
                    it.remove();
                } else {
                    e.getValue().refresh();
                }
            }
        }, refreshTicks, refreshTicks);
    }

    @Override
    public void onDisable() {
        for (GhostView v : views.values()) v.clear();
        views.clear();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        GhostView v = views.remove(e.getPlayer().getUniqueId());
        if (v != null) v.clear();
        selections.remove(e.getPlayer().getUniqueId());
        undoStacks.remove(e.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ Hilfsfunktionen

    private void ok(CommandSender s, String msg) { s.sendMessage(Component.text("[BauPlan] ", NamedTextColor.GOLD).append(Component.text(msg, NamedTextColor.GREEN))); }
    private void err(CommandSender s, String msg) { s.sendMessage(Component.text("[BauPlan] ", NamedTextColor.GOLD).append(Component.text(msg, NamedTextColor.RED))); }
    private void info(CommandSender s, String msg) { s.sendMessage(Component.text(msg, NamedTextColor.GRAY)); }

    private File planFile(String name) { return new File(planDir, name + ".plan"); }

    private List<String> planNames() {
        File[] files = planDir.listFiles((d, n) -> n.endsWith(".plan"));
        if (files == null) return List.of();
        return Arrays.stream(files).map(f -> f.getName().substring(0, f.getName().length() - 5)).sorted().collect(Collectors.toList());
    }

    private Location target(Player p) {
        Block b = p.getTargetBlockExact(8);
        return (b != null ? b.getLocation() : p.getLocation().getBlock().getLocation());
    }

    private String prettify(Material m) {
        String s = m.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ------------------------------------------------------------------ Befehle

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Dieser Befehl geht nur im Spiel.");
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            help(p);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "pos1", "pos2" -> {
                Location loc = target(p);
                Location[] sel = selections.computeIfAbsent(p.getUniqueId(), k -> new Location[2]);
                sel[sub.equals("pos1") ? 0 : 1] = loc;
                ok(p, (sub.equals("pos1") ? "Position 1" : "Position 2") + " gesetzt: "
                        + loc.getBlockX() + " " + loc.getBlockY() + " " + loc.getBlockZ());
            }
            case "save" -> {
                if (args.length < 2 || !NAME.matcher(args[1]).matches()) {
                    err(p, "Benutzung: /bauplan save <name>  (nur Buchstaben, Zahlen, _ und -)");
                    return true;
                }
                Location[] sel = selections.get(p.getUniqueId());
                if (sel == null || sel[0] == null || sel[1] == null) {
                    err(p, "Setze zuerst /bauplan pos1 und /bauplan pos2.");
                    return true;
                }
                if (!Objects.equals(sel[0].getWorld(), sel[1].getWorld())) {
                    err(p, "Beide Positionen muessen in derselben Welt sein.");
                    return true;
                }
                try {
                    Plan plan = Plan.capture(args[1], sel[0], sel[1], maxVolume);
                    plan.save(planFile(args[1]));
                    ok(p, "Plan '" + args[1] + "' gespeichert (" + plan.sx + "x" + plan.sy + "x" + plan.sz
                            + ", " + plan.solidCount() + " Bloecke).");
                } catch (IllegalArgumentException | IOException ex) {
                    err(p, "Fehler: " + ex.getMessage());
                }
            }
            case "import" -> {
                if (args.length < 2) {
                    err(p, "Benutzung: /bauplan import <datei.schem|datei.litematic> [name]");
                    info(p, "Lege die Datei vorher in plugins/BauPlan/import/ auf dem Server ab.");
                    return true;
                }
                File f = new File(importDir, args[1]);
                if (!f.getName().equals(args[1]) || !f.isFile()) {
                    err(p, "Datei nicht gefunden in plugins/BauPlan/import/: " + args[1]);
                    return true;
                }
                String name = args.length >= 3 ? args[2] : args[1].replaceFirst("\\.[^.]*$", "");
                name = name.replaceAll("[^a-zA-Z0-9_\\-]", "_");
                if (name.length() > 40) name = name.substring(0, 40);
                try {
                    String lower = f.getName().toLowerCase(Locale.ROOT);
                    Plan plan;
                    if (lower.endsWith(".litematic")) plan = Plan.fromLitematic(name, f);
                    else if (lower.endsWith(".schem")) plan = Plan.fromSchematic(name, f);
                    else throw new IOException("Nur .schem und .litematic werden unterstuetzt");
                    plan.save(planFile(name));
                    ok(p, "Importiert als '" + name + "' (" + plan.sx + "x" + plan.sy + "x" + plan.sz
                            + ", " + plan.solidCount() + " Bloecke).");
                } catch (IOException ex) {
                    err(p, "Import fehlgeschlagen: " + ex.getMessage());
                }
            }
            case "list" -> {
                List<String> names = planNames();
                if (names.isEmpty()) info(p, "Noch keine Plaene gespeichert.");
                else ok(p, "Plaene: " + String.join(", ", names));
            }
            case "delete" -> {
                if (args.length < 2 || !NAME.matcher(args[1]).matches()) {
                    err(p, "Benutzung: /bauplan delete <name>");
                    return true;
                }
                File f = planFile(args[1]);
                if (f.isFile() && f.delete()) ok(p, "Plan '" + args[1] + "' geloescht.");
                else err(p, "Plan nicht gefunden.");
            }
            case "show" -> {
                if (args.length < 2 || !NAME.matcher(args[1]).matches() || !planFile(args[1]).isFile()) {
                    err(p, "Benutzung: /bauplan show <name>   (siehe /bauplan list)");
                    return true;
                }
                try {
                    Plan plan = Plan.load(args[1], planFile(args[1]));
                    GhostView old = views.remove(p.getUniqueId());
                    if (old != null) old.clear();
                    GhostView v = new GhostView(this, p, plan, p.getLocation().getBlock().getLocation());
                    views.put(p.getUniqueId(), v);
                    v.refresh();
                    ok(p, "Plan '" + plan.name + "' wird angezeigt (" + plan.sx + "x" + plan.sy + "x" + plan.sz + ").");
                    info(p, "Die Ecke unten-links des Plans liegt bei dir. Mit /bauplan move <x> <y> <z> verschieben,");
                    info(p, "mit /bauplan here an deine jetzige Position setzen, mit /bauplan rotate drehen.");
                } catch (IOException ex) {
                    err(p, "Konnte Plan nicht laden: " + ex.getMessage());
                }
            }
            case "hide" -> {
                GhostView v = views.remove(p.getUniqueId());
                if (v != null) v.clear();
                ok(p, "Plan ausgeblendet.");
            }
            case "refresh" -> withView(p, v -> {
                v.clear();
                v.refresh();
                ok(p, "Aktualisiert.");
            });
            case "here" -> withView(p, v -> {
                v.setOrigin(p.getLocation().getBlock().getLocation());
                ok(p, "Plan verschoben nach " + v.originString());
            });
            case "move" -> withView(p, v -> {
                if (args.length < 4) {
                    err(p, "Benutzung: /bauplan move <dx> <dy> <dz>   (z.B. /bauplan move 0 1 0 = 1 hoch)");
                    return;
                }
                try {
                    v.move(Integer.parseInt(args[1]), Integer.parseInt(args[2]), Integer.parseInt(args[3]));
                    ok(p, "Ursprung jetzt: " + v.originString());
                } catch (NumberFormatException ex) {
                    err(p, "Bitte ganze Zahlen angeben.");
                }
            });
            case "rotate" -> withView(p, v -> {
                v.rotate();
                ok(p, "Plan um 90 Grad gedreht.");
            });
            case "layer" -> withView(p, v -> {
                if (args.length < 2) {
                    err(p, "Benutzung: /bauplan layer <nummer|all|up|down>  (0 = unterste Ebene)");
                    return;
                }
                String a = args[1].toLowerCase(Locale.ROOT);
                int target;
                try {
                    switch (a) {
                        case "all" -> target = -1;
                        case "up" -> target = v.layer() + 1;
                        case "down" -> target = v.layer() < 0 ? v.plan().sy - 1 : v.layer() - 1;
                        default -> target = Integer.parseInt(a);
                    }
                } catch (NumberFormatException ex) {
                    err(p, "Ungueltige Ebene.");
                    return;
                }
                v.setLayer(target);
                ok(p, v.layer() < 0 ? "Alle Ebenen werden angezeigt."
                        : "Ebene " + v.layer() + " von " + (v.plan().sy - 1) + " wird angezeigt.");
            });
            case "info" -> withView(p, v -> {
                Plan pl = v.plan();
                ok(p, "Plan '" + pl.name + "' - " + pl.sx + "x" + pl.sy + "x" + pl.sz);
                info(p, "Ursprung: " + v.originString());
                info(p, "Ebene: " + (v.layer() < 0 ? "alle" : v.layer() + " / " + (pl.sy - 1)));
                info(p, "Noch zu bauen: " + v.missing() + " Bloecke (angezeigt: " + v.shown() + ")"
                        + (v.truncated() ? " - Limit erreicht, nutze /bauplan layer" : ""));
            });
            case "materials" -> {
                Map<Material, Integer> mats;
                String title;
                if (args.length >= 2) {
                    if (!NAME.matcher(args[1]).matches() || !planFile(args[1]).isFile()) {
                        err(p, "Plan nicht gefunden.");
                        return true;
                    }
                    try {
                        mats = Plan.load(args[1], planFile(args[1])).countMaterials();
                    } catch (IOException ex) {
                        err(p, "Konnte Plan nicht laden: " + ex.getMessage());
                        return true;
                    }
                    title = "Material fuer den ganzen Plan '" + args[1] + "'";
                } else {
                    GhostView v = views.get(p.getUniqueId());
                    if (v == null) {
                        err(p, "Kein Plan aktiv. Nutze /bauplan show <name> oder /bauplan materials <name>.");
                        return true;
                    }
                    v.refresh();
                    mats = v.missingMaterials();
                    title = "Noch fehlendes Material";
                }
                if (mats.isEmpty()) {
                    ok(p, "Nichts mehr noetig!");
                    return true;
                }
                ok(p, title + ":");
                mats.entrySet().stream()
                        .sorted((a, b) -> b.getValue() - a.getValue())
                        .limit(40)
                        .forEach(e -> info(p, " - " + prettify(e.getKey()) + ": " + e.getValue()
                                + " (" + (e.getValue() / 64) + " Stacks + " + (e.getValue() % 64) + ")"));
            }
            case "paste" -> {
                if (!p.hasPermission("bauplan.build")) {
                    err(p, "Dafuer fehlt dir die Berechtigung bauplan.build.");
                    return true;
                }
                withView(p, v -> {
                    Plan pl = v.plan();
                    Location o = v.origin();
                    World w = o.getWorld();
                    if (pl.solidCount() > MAX_PASTE) {
                        err(p, "Plan zu gross zum direkten Einbauen (Maximum " + MAX_PASTE + " Bloecke).");
                        return;
                    }
                    List<Snap> snaps = new ArrayList<>();
                    for (int y = 0; y < pl.sy; y++) {
                        for (int z = 0; z < pl.sz; z++) {
                            for (int x = 0; x < pl.sx; x++) {
                                BlockData d = pl.get(x, y, z);
                                if (d.getMaterial().isAir()) continue;
                                Block b = w.getBlockAt(o.getBlockX() + x, o.getBlockY() + y, o.getBlockZ() + z);
                                BlockData cur = b.getBlockData();
                                if (cur.equals(d)) continue;
                                snaps.add(new Snap(b.getLocation(), cur));
                                b.setBlockData(d, false);
                            }
                        }
                    }
                    undoStacks.put(p.getUniqueId(), snaps);
                    v.refresh();
                    ok(p, snaps.size() + " Bloecke eingebaut. Rueckgaengig: /bauplan undo");
                });
            }
            case "undo" -> {
                if (!p.hasPermission("bauplan.build")) {
                    err(p, "Dafuer fehlt dir die Berechtigung bauplan.build.");
                    return true;
                }
                List<Snap> snaps = undoStacks.remove(p.getUniqueId());
                if (snaps == null || snaps.isEmpty()) {
                    err(p, "Nichts zum Rueckgaengigmachen.");
                    return true;
                }
                for (int i = snaps.size() - 1; i >= 0; i--) {
                    Snap sn = snaps.get(i);
                    sn.loc().getBlock().setBlockData(sn.old(), false);
                }
                GhostView v = views.get(p.getUniqueId());
                if (v != null) v.refresh();
                ok(p, snaps.size() + " Bloecke wiederhergestellt (Kisteninhalte etc. gehen dabei verloren).");
            }
            case "download" -> {
                if (!p.hasPermission("bauplan.build")) {
                    err(p, "Dafuer fehlt dir die Berechtigung bauplan.build.");
                    return true;
                }
                if (args.length < 3 || !NAME.matcher(args[2]).matches() || !args[1].startsWith("https://")) {
                    err(p, "Benutzung: /bauplan download <https-link-zur-.schem-datei> <name>");
                    return true;
                }
                String url = args[1];
                String name = args[2];
                UUID id = p.getUniqueId();
                ok(p, "Lade herunter ...");
                getServer().getScheduler().runTaskAsynchronously(this, () -> {
                    String result;
                    try {
                        HttpClient client = HttpClient.newBuilder()
                                .followRedirects(HttpClient.Redirect.NORMAL)
                                .connectTimeout(Duration.ofSeconds(10)).build();
                        HttpResponse<InputStream> r = client.send(
                                HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).GET().build(),
                                HttpResponse.BodyHandlers.ofInputStream());
                        if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode());
                        ByteArrayOutputStream bos = new ByteArrayOutputStream();
                        try (InputStream in = r.body()) {
                            byte[] buf = new byte[8192];
                            int n;
                            while ((n = in.read(buf)) > 0) {
                                bos.write(buf, 0, n);
                                if (bos.size() > MAX_DOWNLOAD) throw new IOException("Datei zu gross (>16 MB)");
                            }
                        }
                        Files.write(new File(importDir, name + ".schem").toPath(), bos.toByteArray());
                        result = "OK";
                    } catch (Exception ex) {
                        result = "Download fehlgeschlagen: " + ex.getMessage();
                    }
                    final String res = result;
                    getServer().getScheduler().runTask(this, () -> {
                        Player pl = getServer().getPlayer(id);
                        if (pl == null) return;
                        if (res.equals("OK")) ok(pl, "Gespeichert. Jetzt: /bauplan import " + name + ".schem");
                        else err(pl, res);
                    });
                });
            }
            default -> help(p);
        }
        return true;
    }

    private void withView(Player p, java.util.function.Consumer<GhostView> action) {
        GhostView v = views.get(p.getUniqueId());
        if (v == null) {
            err(p, "Kein Plan aktiv. Zeige zuerst einen an: /bauplan show <name>");
            return;
        }
        action.accept(v);
    }

    private void help(Player p) {
        ok(p, "Befehle:");
        info(p, "/bauplan pos1 | pos2  - Bereich markieren (Block, den du ansiehst)");
        info(p, "/bauplan save <name>  - markierten Bereich als Plan speichern");
        info(p, "/bauplan import <datei> [name]  - .schem/.litematic aus plugins/BauPlan/import/");
        info(p, "/bauplan list | delete <name>");
        info(p, "/bauplan show <name>  - Plan als Hologramm anzeigen");
        info(p, "/bauplan hide | refresh | info");
        info(p, "/bauplan here | move <dx> <dy> <dz> | rotate");
        info(p, "/bauplan layer <nr|all|up|down>  - Ebene fuer Ebene bauen");
        info(p, "/bauplan materials [name]  - Materialliste");
        info(p, "/bauplan paste | undo  - Plan direkt in die Welt bauen / zuruecknehmen");
        info(p, "/bauplan download <https-link> <name>  - .schem aus dem Internet laden");
    }

    // ------------------------------------------------------------------ Tab-Vervollstaendigung

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length == 1) {
            return SUB.stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).collect(Collectors.toList());
        }
        if (args.length == 2) {
            String a = args[1].toLowerCase(Locale.ROOT);
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "show", "delete", "materials" -> {
                    return planNames().stream().filter(n -> n.toLowerCase(Locale.ROOT).startsWith(a)).collect(Collectors.toList());
                }
                case "import" -> {
                    String[] files = importDir.list((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".schem") || n.toLowerCase(Locale.ROOT).endsWith(".litematic"));
                    if (files == null) return List.of();
                    return Arrays.stream(files).filter(n -> n.toLowerCase(Locale.ROOT).startsWith(a)).collect(Collectors.toList());
                }
                case "layer" -> {
                    return List.of("all", "up", "down").stream().filter(s -> s.startsWith(a)).collect(Collectors.toList());
                }
                default -> { return List.of(); }
            }
        }
        return List.of();
    }
}
