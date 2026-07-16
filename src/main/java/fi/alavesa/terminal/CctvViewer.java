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
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
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
 * The body is not safe - and it is REAL: the viewer's whole inventory
 * moves onto the double when they jack in (armor worn, weapon in hand, the
 * rest inside). Hits on the body hurt the distant viewer, who is warned but
 * NOT pulled back - defending the body means choosing to unplug. If the
 * body's owner dies while wired in, the double spills every item where it
 * stands and the owner dies for real, respawning at spawn like any corpse:
 * there is no walking back to the same spot. Redacted cameras (redact level above the viewer's LuckPerms
 * clearance; terminal.admin sees all) connect but show nothing - a black
 * frame with hazard slashes, "FEED REDACTED".
 *
 * Every exit path restores gamemode and position; a PDC backup restores
 * them on join even after a crash mid-view.
 */
public final class CctvViewer implements Listener {

    private record Session(Location back, GameMode mode, UUID body, String npc, int index) { }

    public static final String TAG_BODY = "terminal.cctv.body";

    private final TerminalPlugin plugin;
    private final CctvManager cameras;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<UUID, Long> lastHit = new HashMap<>();     // epoch ms of the last blow
    private final Map<UUID, String> lastAttacker = new HashMap<>();

    private static final class CamScreen implements InventoryHolder {
        Inventory inventory;
        @Override public Inventory getInventory() { return inventory; }
    }

    public CctvViewer(TerminalPlugin plugin, CctvManager cameras) {
        this.plugin = plugin;
        this.cameras = cameras;
        NpcBridge.onAttack = this::onDoubleAttacked;
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
        openGrid(player);
    }

    /** The camera grid screen - the same one whether it was summoned from
     *  the handheld monitor or a SCiPNET terminal. */
    public void openGrid(Player player) {
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
        ItemStack back = new ItemStack(Material.BARRIER);
        var bmeta = back.getItemMeta();
        bmeta.itemName(Component.text("< DESKTOP", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        bmeta.setItemModel(new org.bukkit.NamespacedKey("terminal", "btn_back"));
        bmeta.getPersistentDataContainer().set(plugin.key("grid_back"), PersistentDataType.BYTE, (byte) 1);
        back.setItemMeta(bmeta);
        inv.setItem(22, back);
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
        if (item.getItemMeta().getPersistentDataContainer()
                .has(plugin.key("grid_back"), PersistentDataType.BYTE)) {
            plugin.ui().openDesktop(player); // return to the SCiPNET desktop
            return;
        }
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

        // the whole inventory rides the body: snapshot it (crash-safe, in
        // PDC as bytes), then strip the player - a spectator carries nothing
        Location at = player.getLocation();
        ItemStack[] carried = player.getInventory().getContents();
        player.getPersistentDataContainer().set(plugin.key("cctv_inv"),
            PersistentDataType.BYTE_ARRAY, ItemStack.serializeItemsAsBytes(carried));
        var inv = player.getInventory();
        ItemStack chest = inv.getChestplate(), legs = inv.getLeggings(), boots = inv.getBoots();
        ItemStack hand = inv.getItemInMainHand();
        inv.clear();

        // with the Replicas plugin up, the visible double is a REAL player
        // model with the viewer's skin - the stand shrinks to an invisible
        // hitbox underneath it (all damage/loot logic rides the stand)
        boolean npcMode = NpcBridge.available();
        ArmorStand body = at.getWorld().spawn(at, ArmorStand.class, stand -> {
            stand.setPersistent(true);
            stand.setArms(true);
            stand.setBasePlate(false);
            stand.setInvisible(npcMode);
            stand.addScoreboardTag(TAG_BODY);
            stand.getPersistentDataContainer().set(plugin.key("body_owner"),
                PersistentDataType.STRING, player.getUniqueId().toString());
            if (!npcMode) {
                ItemStack head = new ItemStack(Material.PLAYER_HEAD);
                SkullMeta skull = (SkullMeta) head.getItemMeta();
                skull.setOwningPlayer(player);
                head.setItemMeta(skull);
                stand.getEquipment().setHelmet(head);
                // the REAL gear, worn where everyone can see what's for taking
                if (chest != null) stand.getEquipment().setChestplate(chest);
                if (legs != null) stand.getEquipment().setLeggings(legs);
                if (boots != null) stand.getEquipment().setBoots(boots);
                stand.getEquipment().setItemInMainHand(hand);
            }
            // no pickpocketing the mannequin piece by piece - loot comes
            // from killing it, not clicking it
            for (var slot : org.bukkit.inventory.EquipmentSlot.values()) {
                stand.addEquipmentLock(slot, ArmorStand.LockType.REMOVING_OR_CHANGING);
            }
        });

        // crash-safe backup BEFORE the gamemode flips
        player.getPersistentDataContainer().set(plugin.key("cctv_back"), PersistentDataType.STRING,
            String.format(Locale.ROOT, "%s;%f;%f;%f;%f;%f;%s", at.getWorld().getName(),
                at.getX(), at.getY(), at.getZ(), at.getYaw(), at.getPitch(), player.getGameMode().name()));
        String npcId = npcMode ? NpcBridge.spawn(player, at, java.util.Map.of(
            org.bukkit.inventory.EquipmentSlot.CHEST, chest == null ? new ItemStack(Material.AIR) : chest,
            org.bukkit.inventory.EquipmentSlot.LEGS, legs == null ? new ItemStack(Material.AIR) : legs,
            org.bukkit.inventory.EquipmentSlot.FEET, boots == null ? new ItemStack(Material.AIR) : boots,
            org.bukkit.inventory.EquipmentSlot.HAND, hand)) : null;
        sessions.put(player.getUniqueId(),
            new Session(at.clone(), player.getGameMode(), body.getUniqueId(), npcId, index));
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
        sessions.put(player.getUniqueId(),
            new Session(old.back(), old.mode(), old.body(), old.npc(), index));
        player.setSpectatorTarget(null);
        player.teleport(eye.getLocation());
        player.setSpectatorTarget(eye);
        cctvSound(player, "connect", "minecraft:block.note_block.hat", 0.7f, 0.6f);
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
            if (player.getTicksLived() % 60 < 15) {
                cctvSound(player, "ambient", "minecraft:block.beacon.ambient", 0.25f, 2.0f);
            }
            if (player.getSpectatorTarget() == null) {
                connect(player, session.index()); // re-lock if the client wiggled free
            }
        }
    }

    private boolean redactedFor(Player player, CctvManager.Camera camera) {
        if (camera.redact() <= 0 || player.hasPermission("terminal.admin")
            || TerminalUi.hasSkeletonKey(player)) return false;
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
        clearVitals(player);
        player.setSpectatorTarget(null);
        player.showTitle(Title.title(Component.empty(), Component.empty(),
            Title.Times.times(Duration.ZERO, Duration.ZERO, Duration.ZERO)));
        Entity body = plugin.getServer().getEntity(session.body());
        Location back = body != null ? body.getLocation() : session.back();
        if (body != null) body.remove();
        NpcBridge.remove(session.npc());
        player.teleport(back);
        player.setGameMode(session.mode());
        byte[] stored = player.getPersistentDataContainer()
            .get(plugin.key("cctv_inv"), PersistentDataType.BYTE_ARRAY);
        if (stored != null) {
            player.getInventory().setContents(ItemStack.deserializeItemsFromBytes(stored));
        }
        player.getPersistentDataContainer().remove(plugin.key("cctv_inv"));
        player.getPersistentDataContainer().remove(plugin.key("cctv_back"));
        if (reason != null) {
            player.sendActionBar(line(reason, NamedTextColor.GRAY));
        }
        player.playSound(player.getLocation(), Sound.BLOCK_LEVER_CLICK, 0.7f, 0.5f);
    }

    /** A player left-clicked someone's CCTV double (FancyNpcs melee). Route
     *  the attacker's weapon damage to the distant owner. */
    public void onDoubleAttacked(Player attacker, String npcName) {
        String handle = "fn:" + npcName;
        for (var entry : sessions.entrySet()) {
            if (!handle.equals(entry.getValue().npc())) continue;
            Player owner = plugin.getServer().getPlayer(entry.getKey());
            if (owner == null) return;
            double dmg = 1.0;
            var attr = attacker.getAttribute(org.bukkit.attribute.Attribute.ATTACK_DAMAGE);
            if (attr != null) dmg = Math.max(1.0, attr.getValue());
            Entity body = plugin.getServer().getEntity(entry.getValue().body());
            Location at = body != null ? body.getLocation() : owner.getLocation();
            if (owner.getHealth() - dmg <= 0.5) {
                killWiredIn(owner, at);
            } else {
                owner.setHealth(owner.getHealth() - dmg);
                struck(owner, attacker.getName());
            }
            return;
        }
    }

    /** Sneak = unplug. */
    @EventHandler
    public void onSneak(PlayerToggleSneakEvent event) {
        Player player = event.getPlayer();
        if (event.isSneaking() && sessions.containsKey(player.getUniqueId())) {
            jackOut(player, "Disconnected.");
            // land back in the camera grid instead of being dumped to the world
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (player.isOnline() && !player.isDead()) openGrid(player);
            });
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

    /** Hits on the body hurt the DISTANT viewer - warned, not rescued.
     *  A killing blow spills everything the body carries where it stands,
     *  and the owner dies for real. */
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
        String attacker = event.getDamager() instanceof Player p ? p.getName()
            : event.getDamager().getName();
        double damage = Math.max(1.0, event.getDamage());
        stand.getWorld().playSound(stand.getLocation(), Sound.ENTITY_PLAYER_HURT, 0.9f, 1.0f);
        if (player.getHealth() - damage <= 0.5) {
            killWiredIn(player, stand.getLocation()); // a real death, guaranteed
            return;
        }
        player.setHealth(player.getHealth() - damage);
        struck(player, attacker);
    }

    /** The felt hit: a red screen flash, the hurt sound, and the attacker
     *  logged so the vitals bar can name them. */
    private void struck(Player player, String attacker) {
        lastHit.put(player.getUniqueId(), System.currentTimeMillis());
        lastAttacker.put(player.getUniqueId(), attacker);
        player.playHurtAnimation(0);
        player.showTitle(Title.title(
            Component.text("", NamedTextColor.RED)
                .font(Key.key("terminal", "cctv")).color(NamedTextColor.RED),
            Component.text("YOUR BODY IS UNDER ATTACK", NamedTextColor.RED, TextDecoration.BOLD),
            Title.Times.times(Duration.ZERO, Duration.ofMillis(600), Duration.ofMillis(200))));
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_HURT, 1f, 1f);
        player.playSound(player.getLocation(), Sound.ENTITY_WARDEN_HEARTBEAT, 1f, 1.4f);
    }

    /** The guaranteed kill: unhook the spectator, stand the player at the
     *  body, THEN zero the health - killing a player mid-spectate could
     *  silently fail, which was how copies died without their owners. The
     *  death handler below does the spilling and cleanup. */
    private void killWiredIn(Player player, org.bukkit.Location at) {
        clearVitals(player);
        player.setSpectatorTarget(null);
        player.teleport(at);
        player.setHealth(0.0);
        if (!player.isDead() && player.getHealth() > 0.5) {
            // a totem or another plugin argued - take the session down anyway
            jackOut(player, "Your body is gone.");
        }
    }

    /** Environmental harm - explosions, arrows from dispensers, fire - used
     *  to DESTROY the stand outright: body gone, owner alive, loot deleted.
     *  Now every kind of damage routes to the owner. */
    @EventHandler(ignoreCancelled = true)
    public void onBodyHarm(EntityDamageEvent event) {
        if (event instanceof EntityDamageByEntityEvent) return; // handled above
        if (!(event.getEntity() instanceof ArmorStand stand)) return;
        if (!stand.getScoreboardTags().contains(TAG_BODY)) return;
        event.setCancelled(true);
        String owner = stand.getPersistentDataContainer()
            .get(plugin.key("body_owner"), PersistentDataType.STRING);
        if (owner == null) { stand.remove(); return; }
        Player player = plugin.getServer().getPlayer(UUID.fromString(owner));
        if (player == null || !sessions.containsKey(player.getUniqueId())) {
            stand.remove();
            return;
        }
        double damage = Math.max(0.5, event.getDamage());
        if (player.getHealth() - damage <= 0.5) {
            killWiredIn(player, stand.getLocation());
            return;
        }
        player.setHealth(player.getHealth() - damage);
        struck(player, "the facility");
    }

    /** Death while wired in - by the body's wounds or anything else: the
     *  double spills the whole inventory where IT stands, and the player
     *  respawns like any other corpse. No walking back. */
    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getPlayer();
        Session session = sessions.remove(player.getUniqueId());
        if (session == null) return;
        clearVitals(player);
        player.setSpectatorTarget(null);
        Entity body = plugin.getServer().getEntity(session.body());
        NpcBridge.remove(session.npc());
        Location spill = body != null ? body.getLocation() : session.back();
        byte[] stored = player.getPersistentDataContainer()
            .get(plugin.key("cctv_inv"), PersistentDataType.BYTE_ARRAY);
        if (stored != null) {
            for (ItemStack item : ItemStack.deserializeItemsFromBytes(stored)) {
                if (item != null && !item.getType().isAir()) {
                    spill.getWorld().dropItemNaturally(spill, item);
                }
            }
        }
        if (body != null) body.remove();
        player.getPersistentDataContainer().remove(plugin.key("cctv_inv"));
        player.getPersistentDataContainer().remove(plugin.key("cctv_back"));
        // restore only the GAMEMODE on respawn - never the location
        player.getPersistentDataContainer().set(plugin.key("cctv_gm"),
            PersistentDataType.STRING, session.mode().name());
        event.getDrops().clear(); // the loot is at the body, not at the lens
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        String mode = player.getPersistentDataContainer()
            .get(plugin.key("cctv_gm"), PersistentDataType.STRING);
        if (mode == null) return;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            player.setGameMode(GameMode.valueOf(mode));
            player.getPersistentDataContainer().remove(plugin.key("cctv_gm"));
        });
    }

    /** Quit mid-feed: the body is cleaned up, the inventory waits in PDC
     *  and comes home on the next join (the crash path handles it). */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Session session = sessions.remove(event.getPlayer().getUniqueId());
        if (session != null) {
            Entity body = plugin.getServer().getEntity(session.body());
            if (body != null) body.remove();
            NpcBridge.remove(session.npc());
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
            byte[] invBytes = player.getPersistentDataContainer()
                .get(plugin.key("cctv_inv"), PersistentDataType.BYTE_ARRAY);
            if (invBytes != null) {
                player.getInventory().setContents(ItemStack.deserializeItemsFromBytes(invBytes));
            }
            player.getPersistentDataContainer().remove(plugin.key("cctv_inv"));
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

    boolean isWiredIn(UUID id) { return sessions.containsKey(id); }

    private void clearVitals(Player player) {
        lastHit.remove(player.getUniqueId());
        lastAttacker.remove(player.getUniqueId());
    }

    /** A camera sound, configurable in config.yml (cctv-sounds.<key>) so it
     *  can point at a custom scp: event; falls back to a vanilla default. */
    private void cctvSound(Player player, String key, String def, float vol, float pitch) {
        String sound = plugin.getConfig().getString("cctv-sounds." + key, def);
        if (sound == null || sound.isEmpty()) return;
        player.playSound(player.getLocation(), sound, vol, pitch);
    }

    private Component line(String text, NamedTextColor color) {
        return Component.text(text, color, TextDecoration.ITALIC);
    }
}
