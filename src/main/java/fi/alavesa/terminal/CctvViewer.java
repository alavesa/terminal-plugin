package fi.alavesa.terminal;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Jacking in. Using the CCTV monitor item leaves a BODY behind - an armor
 * stand double wearing the player's own skin on its head, their armor and
 * the monitor in its hand - and puts the player in spectator mode locked to
 * a camera's eye. The feed is the real, live world from the lens, washed
 * with scanlines; scroll the hotbar to hop between cameras; sneak to snap
 * back to the body.
 *
 * The body is not safe: any hit on it lands ON THE VIEWER and rips them out
 * of the grid. Redacted cameras (redact level above the viewer's LuckPerms
 * clearance; terminal.admin sees all) connect but show nothing - a black
 * frame with hazard slashes, "FEED REDACTED".
 *
 * Every exit path restores gamemode and position; a PDC backup restores
 * them on join even after a crash mid-view.
 */
public final class CctvViewer implements Listener {

    private record Session(Location back, GameMode mode, UUID body, int index) { }

    public static final String TAG_BODY = "terminal.cctv.body";

    private final TerminalPlugin plugin;
    private final CctvManager cameras;
    private final Map<UUID, Session> sessions = new HashMap<>();

    private static final class CamScreen implements InventoryHolder {
        Inventory inventory;
        @Override public Inventory getInventory() { return inventory; }
    }

    public CctvViewer(TerminalPlugin plugin, CctvManager cameras) {
        this.plugin = plugin;
        this.cameras = cameras;
    }

    // ------------------------------------------------------------ the list

    @EventHandler
    public void onMonitorUse(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR
            && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (!cameras.isMonitor(event.getItem())) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (!player.hasPermission("terminal.cctv")) return;
        List<CctvManager.Camera> all = cameras.cameras();
        if (all.isEmpty()) {
            player.sendActionBar(line("No cameras on the grid.", NamedTextColor.GRAY));
            return;
        }
        CamScreen screen = new CamScreen();
        Inventory inv = Bukkit.createInventory(screen, 27,
            Component.text("CCTV GRID", NamedTextColor.DARK_GRAY));
        screen.inventory = inv;
        for (int i = 0; i < Math.min(27, all.size()); i++) {
            CctvManager.Camera camera = all.get(i);
            boolean sealed = redactedFor(player, camera);
            ItemStack icon = new ItemStack(sealed ? Material.BLACK_STAINED_GLASS_PANE : Material.ENDER_EYE);
            ItemMeta meta = icon.getItemMeta();
            meta.itemName(Component.text(camera.name(), sealed ? NamedTextColor.DARK_GRAY : NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(line(sealed ? "Feed classified." : "Connect.", NamedTextColor.DARK_GRAY)));
            meta.getPersistentDataContainer().set(plugin.key("cam_index"), PersistentDataType.INTEGER, i);
            icon.setItemMeta(meta);
            inv.setItem(i, icon);
        }
        player.openInventory(inv);
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BIT, 0.6f, 1.4f);
    }

    @EventHandler
    public void onPick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof CamScreen)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        ItemStack item = event.getCurrentItem();
        if (item == null || !item.hasItemMeta()) return;
        Integer index = item.getItemMeta().getPersistentDataContainer()
            .get(plugin.key("cam_index"), PersistentDataType.INTEGER);
        if (index == null) return;
        player.closeInventory();
        jackIn(player, index);
    }

    // ------------------------------------------------------------- sessions

    private void jackIn(Player player, int index) {
        if (sessions.containsKey(player.getUniqueId())) return;
        List<CctvManager.Camera> all = cameras.cameras();
        if (all.isEmpty()) return;
        index = Math.floorMod(index, all.size());

        // the body double: their skin on its head, their armor, the monitor
        Location at = player.getLocation();
        ArmorStand body = at.getWorld().spawn(at, ArmorStand.class, stand -> {
            stand.setPersistent(true);
            stand.setArms(true);
            stand.setBasePlate(false);
            stand.addScoreboardTag(TAG_BODY);
            stand.getPersistentDataContainer().set(plugin.key("body_owner"),
                PersistentDataType.STRING, player.getUniqueId().toString());
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta skull = (SkullMeta) head.getItemMeta();
            skull.setOwningPlayer(player);
            head.setItemMeta(skull);
            stand.getEquipment().setHelmet(head);
            var inv = player.getInventory();
            if (inv.getChestplate() != null) stand.getEquipment().setChestplate(inv.getChestplate().clone());
            if (inv.getLeggings() != null) stand.getEquipment().setLeggings(inv.getLeggings().clone());
            if (inv.getBoots() != null) stand.getEquipment().setBoots(inv.getBoots().clone());
            stand.getEquipment().setItemInMainHand(cameras.buildMonitor());
        });

        // crash-safe backup BEFORE the gamemode flips
        player.getPersistentDataContainer().set(plugin.key("cctv_back"), PersistentDataType.STRING,
            String.format(Locale.ROOT, "%s;%f;%f;%f;%f;%f;%s", at.getWorld().getName(),
                at.getX(), at.getY(), at.getZ(), at.getYaw(), at.getPitch(), player.getGameMode().name()));
        sessions.put(player.getUniqueId(),
            new Session(at.clone(), player.getGameMode(), body.getUniqueId(), index));
        player.setGameMode(GameMode.SPECTATOR);
        connect(player, index);
    }

    private void connect(Player player, int index) {
        List<CctvManager.Camera> all = cameras.cameras();
        if (all.isEmpty()) { jackOut(player, "The grid is dark."); return; }
        index = Math.floorMod(index, all.size());
        CctvManager.Camera camera = all.get(index);
        ArmorStand eye = cameras.eyeOf(camera);
        if (eye == null) { jackOut(player, "Signal lost."); return; }
        Session old = sessions.get(player.getUniqueId());
        sessions.put(player.getUniqueId(), new Session(old.back(), old.mode(), old.body(), index));
        player.setSpectatorTarget(null);
        player.teleport(eye.getLocation());
        player.setSpectatorTarget(eye);
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.7f, 0.6f);
    }

    /** Every 15 ticks (registered by the plugin): overlays + watchdog. */
    public void feedTick() {
        for (UUID id : sessions.keySet().toArray(new UUID[0])) {
            Player player = plugin.getServer().getPlayer(id);
            if (player == null) continue;
            Session session = sessions.get(id);
            List<CctvManager.Camera> all = cameras.cameras();
            if (all.isEmpty()) { jackOut(player, "The grid is dark."); continue; }
            CctvManager.Camera camera = all.get(Math.floorMod(session.index(), all.size()));
            boolean sealed = redactedFor(player, camera);
            String glyph = sealed ? "" : "";
            player.showTitle(Title.title(
                Component.text(glyph).font(Key.key("terminal", "cctv")).color(NamedTextColor.WHITE),
                Component.empty(),
                Title.Times.times(Duration.ZERO, Duration.ofMillis(1500), Duration.ofMillis(250))));
            player.sendActionBar(sealed
                ? Component.text("[ FEED REDACTED - LEVEL " + camera.redact() + " ]",
                    NamedTextColor.DARK_RED, TextDecoration.BOLD)
                : Component.text(camera.name() + " // LIVE", NamedTextColor.GRAY, TextDecoration.ITALIC));
            if (player.getSpectatorTarget() == null) {
                connect(player, session.index()); // re-lock if the client wiggled free
            }
        }
    }

    private boolean redactedFor(Player player, CctvManager.Camera camera) {
        if (camera.redact() <= 0 || player.hasPermission("terminal.admin")) return false;
        return clearanceOf(player) < camera.redact();
    }

    private int clearanceOf(Player player) {
        try {
            var user = net.luckperms.api.LuckPermsProvider.get().getUserManager()
                .getUser(player.getUniqueId());
            if (user != null) {
                String value = user.getCachedData().getMetaData().getMetaValue("clearance");
                if (value != null) return Integer.parseInt(value.trim());
            }
        } catch (IllegalStateException | NoClassDefFoundError | NumberFormatException ignored) { }
        return 0;
    }

    // ---------------------------------------------------------- exit paths

    public void jackOut(Player player, String reason) {
        Session session = sessions.remove(player.getUniqueId());
        if (session == null) return;
        player.setSpectatorTarget(null);
        player.showTitle(Title.title(Component.empty(), Component.empty(),
            Title.Times.times(Duration.ZERO, Duration.ZERO, Duration.ZERO)));
        Entity body = plugin.getServer().getEntity(session.body());
        Location back = body != null ? body.getLocation() : session.back();
        if (body != null) body.remove();
        player.teleport(back);
        player.setGameMode(session.mode());
        player.getPersistentDataContainer().remove(plugin.key("cctv_back"));
        if (reason != null) {
            player.sendActionBar(line(reason, NamedTextColor.GRAY));
        }
        player.playSound(player.getLocation(), Sound.BLOCK_LEVER_CLICK, 0.7f, 0.5f);
    }

    /** Sneak = unplug. */
    @EventHandler
    public void onSneak(PlayerToggleSneakEvent event) {
        if (event.isSneaking() && sessions.containsKey(event.getPlayer().getUniqueId())) {
            jackOut(event.getPlayer(), "Disconnected.");
        }
    }

    /** Hotbar scroll = next/previous camera. */
    @EventHandler
    public void onScroll(PlayerItemHeldEvent event) {
        Session session = sessions.get(event.getPlayer().getUniqueId());
        if (session == null) return;
        event.setCancelled(true);
        int delta = event.getNewSlot() - event.getPreviousSlot();
        if (delta == 0) return;
        // forward scroll = +1 (a 8->0 wrap reads as -8), backward = -1
        int step = (delta == 1 || delta < -4) ? 1 : -1;
        connect(event.getPlayer(), session.index() + step);
    }

    /** The body is not safe: hits land on the viewer and end the feed. */
    @EventHandler(ignoreCancelled = true)
    public void onBodyHit(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof ArmorStand stand)) return;
        if (!stand.getScoreboardTags().contains(TAG_BODY)) return;
        event.setCancelled(true); // the stand never breaks like furniture
        String owner = stand.getPersistentDataContainer()
            .get(plugin.key("body_owner"), PersistentDataType.STRING);
        if (owner == null) { stand.remove(); return; }
        Player player = plugin.getServer().getPlayer(UUID.fromString(owner));
        if (player == null || !sessions.containsKey(player.getUniqueId())) {
            stand.remove(); // orphan
            return;
        }
        jackOut(player, "Someone found your body.");
        player.damage(Math.max(1.0, event.getDamage()));
    }

    /** Quit mid-feed: clean the body now, restore the player on join. */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Session session = sessions.remove(event.getPlayer().getUniqueId());
        if (session != null) {
            Entity body = plugin.getServer().getEntity(session.body());
            if (body != null) body.remove();
        }
    }

    /** The crash net: a PDC backup means they never came back properly. */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String stored = player.getPersistentDataContainer()
            .get(plugin.key("cctv_back"), PersistentDataType.STRING);
        if (stored == null) return;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            String[] p = stored.split(";");
            World world = p.length == 7 ? Bukkit.getWorld(p[0]) : null;
            if (world != null) {
                player.teleport(new Location(world, Double.parseDouble(p[1]), Double.parseDouble(p[2]),
                    Double.parseDouble(p[3]), Float.parseFloat(p[4]), Float.parseFloat(p[5])));
                player.setGameMode(GameMode.valueOf(p[6]));
            }
            player.getPersistentDataContainer().remove(plugin.key("cctv_back"));
        });
    }

    /** onDisable: nobody stays in the walls. */
    public void shutdown() {
        for (UUID id : sessions.keySet().toArray(new UUID[0])) {
            Player player = plugin.getServer().getPlayer(id);
            if (player != null) jackOut(player, null);
        }
    }

    private Component line(String text, NamedTextColor color) {
        return Component.text(text, color, TextDecoration.ITALIC);
    }
}
