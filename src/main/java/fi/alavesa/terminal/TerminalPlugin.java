package fi.alavesa.terminal;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.stream.Stream;

/**
 * SCiPNET terminals: computer blocks placed with the house spawner-model
 * method, a LuckPerms-backed login screen, and a clearance-gated entry
 * database written by the players themselves. /terminal give and place are
 * for ops - or for ANY player in creative mode, which is as close to "in the
 * creative menu" as a Paper plugin can put a custom block.
 */
public final class TerminalPlugin extends JavaPlugin {

    private EntryStore store;
    private TerminalManager machines;
    private TerminalUi ui;
    private CctvManager cctv;
    private CctvViewer viewer;

    @Override
    public void onEnable() {
        store = new EntryStore(this);
        machines = new TerminalManager(this);
        // the CCTV pair is built first: the terminal UI carries a CCTV GRID
        // button (and the admin console a camera wing), so it needs both
        cctv = new CctvManager(this);
        viewer = new CctvViewer(this, cctv);
        ui = new TerminalUi(this, store, machines, cctv, viewer);
        getServer().getPluginManager().registerEvents(machines, this);
        getServer().getPluginManager().registerEvents(ui, this);
        getServer().getPluginManager().registerEvents(viewer, this);
        getServer().getScheduler().runTaskTimer(this, cctv::panTick, 40L, 4L);
        getServer().getScheduler().runTaskTimer(this, viewer::feedTick, 40L, 15L);
        getLogger().info("Terminal enabled - LuckPerms "
            + (Bukkit.getPluginManager().getPlugin("LuckPerms") != null
                ? "found" : "NOT found (everyone reads as Personnel, Level 0)"));
    }

    @Override
    public void onDisable() {
        if (viewer != null) viewer.shutdown();
    }

    /** Ops always; anyone else only while in creative mode. */
    private boolean mayBuild(CommandSender sender) {
        if (sender.hasPermission("terminal.admin")) return true;
        return sender instanceof Player p && p.getGameMode() == GameMode.CREATIVE;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) return usage(sender);
        switch (args[0].toLowerCase()) {
            case "give" -> {
                if (!mayBuild(sender)) return error(sender, "Ops or creative mode only.");
                Player target = args.length >= 2 ? Bukkit.getPlayerExact(args[1])
                    : (sender instanceof Player p ? p : null);
                if (target == null) return error(sender, "Player not found.");
                target.getInventory().addItem(machines.buildItem()).values().forEach(left ->
                    target.getWorld().dropItemNaturally(target.getLocation(), left));
                sender.sendMessage(Component.text("Terminal item given to " + target.getName()
                    + " - place it like a block, it faces the placer.", NamedTextColor.AQUA));
                return true;
            }
            case "place" -> {
                if (!mayBuild(sender)) return error(sender, "Ops or creative mode only.");
                if (!(sender instanceof Player player)) return error(sender, "Players only.");
                var block = player.getTargetBlockExact(6);
                if (block == null) return error(sender, "Look at a floor block within 6 blocks.");
                machines.place(block.getLocation().add(0, 1, 0), player);
                return true;
            }
            case "remove" -> {
                if (!sender.hasPermission("terminal.admin")) return error(sender, "No permission.");
                if (!(sender instanceof Player player)) return error(sender, "Players only.");
                return machines.removeNearest(player)
                    ? true : error(sender, "No terminal within 4 blocks.");
            }
            case "cctv" -> {
                if (!sender.hasPermission("terminal.cctv")) return error(sender, "No permission.");
                if (!(sender instanceof Player player)) return error(sender, "Players only.");
                if (args.length < 2) return error(sender,
                    "/terminal cctv place | monitor | remove | list | redact <0-5> | pan");
                switch (args[1].toLowerCase()) {
                    case "place" -> cctv.place(player);
                    case "monitor" -> {
                        player.getInventory().addItem(cctv.buildMonitor()).values().forEach(left ->
                            player.getWorld().dropItemNaturally(player.getLocation(), left));
                        sender.sendMessage(Component.text(
                            "CCTV monitor issued. Use it to jack into the grid; sneak to come back.",
                            NamedTextColor.AQUA));
                    }
                    case "remove" -> {
                        if (!cctv.removeNearest(player)) return error(sender, "No camera within 5 blocks.");
                    }
                    case "list" -> {
                        var all = cctv.cameras();
                        if (all.isEmpty()) {
                            sender.sendMessage(Component.text("No cameras. /terminal cctv place",
                                NamedTextColor.GRAY));
                        } else {
                            all.forEach(c -> sender.sendMessage(Component.text(c.name()
                                + (c.redact() > 0 ? " [L" + c.redact() + "]" : "")
                                + (c.pan() ? " (panning)" : "")
                                + " at " + c.anchor().getLocation().getBlockX() + " "
                                + c.anchor().getLocation().getBlockY() + " "
                                + c.anchor().getLocation().getBlockZ(), NamedTextColor.AQUA)));
                        }
                    }
                    case "redact" -> {
                        var camera = cctv.nearest(player, 5);
                        if (camera == null) return error(sender, "No camera within 5 blocks.");
                        int level;
                        try { level = Integer.parseInt(args.length >= 3 ? args[2] : "0"); }
                        catch (NumberFormatException e) { return error(sender, "Level 0-5."); }
                        cctv.setRedact(camera, level);
                        sender.sendMessage(Component.text(camera.name() + " redaction: Level " + level
                            + (level == 0 ? " (open feed)" : " clearance required"), NamedTextColor.AQUA));
                    }
                    case "pan" -> {
                        var camera = cctv.nearest(player, 5);
                        if (camera == null) return error(sender, "No camera within 5 blocks.");
                        cctv.togglePan(camera);
                        sender.sendMessage(Component.text(camera.name() + " panning toggled.",
                            NamedTextColor.AQUA));
                    }
                    default -> { return error(sender,
                        "/terminal cctv place | monitor | remove | list | redact <0-5> | pan"); }
                }
                return true;
            }
            case "admin" -> {
                if (!sender.hasPermission("terminal.admin")) return error(sender, "No permission.");
                if (!(sender instanceof Player player)) return error(sender, "Players only.");
                ui.openAdmin(player, 0);
                return true;
            }
            default -> { return usage(sender); }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return switch (args.length) {
            case 1 -> Stream.of("give", "place", "remove", "admin", "cctv")
                .filter(o -> o.startsWith(args[0].toLowerCase())).toList();
            case 2 -> args[0].equalsIgnoreCase("cctv")
                ? Stream.of("place", "monitor", "remove", "list", "redact", "pan")
                    .filter(o -> o.startsWith(args[1].toLowerCase())).toList()
                : List.of();
            case 3 -> args[0].equalsIgnoreCase("cctv") && args[1].equalsIgnoreCase("redact")
                ? List.of("0", "1", "2", "3", "4", "5") : List.of();
            default -> List.of();
        };
    }

    public NamespacedKey key(String name) { return new NamespacedKey(this, name); }

    private boolean usage(CommandSender sender) {
        sender.sendMessage(Component.text(
            "/terminal give [player] | place | remove | admin", NamedTextColor.AQUA));
        return true;
    }

    private boolean error(CommandSender sender, String message) {
        sender.sendMessage(Component.text(message, NamedTextColor.RED));
        return true;
    }
}
