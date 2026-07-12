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

    @Override
    public void onEnable() {
        store = new EntryStore(this);
        machines = new TerminalManager(this);
        ui = new TerminalUi(this, store);
        getServer().getPluginManager().registerEvents(machines, this);
        getServer().getPluginManager().registerEvents(ui, this);
        getLogger().info("Terminal enabled - LuckPerms "
            + (Bukkit.getPluginManager().getPlugin("LuckPerms") != null
                ? "found" : "NOT found (everyone reads as Personnel, Level 0)"));
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
        return args.length == 1
            ? Stream.of("give", "place", "remove", "admin")
                .filter(o -> o.startsWith(args[0].toLowerCase())).toList()
            : List.of();
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
