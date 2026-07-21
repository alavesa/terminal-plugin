package fi.alavesa.terminal;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.MenuType;
import org.bukkit.inventory.view.AnvilView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Everything on the screen. Right-clicking a terminal opens the SCiPNET
 * LOGIN: who you are, what your LuckPerms rank is, what your clearance
 * unlocks. Logging in lists the entry database - entries above your level
 * show as ACCESS DENIED, entries at or below it open as books. New entries
 * are written the RP way: the terminal hands you a draft book, you write and
 * SIGN it, and the signed title/pages become the entry (filed at your own
 * clearance level).
 *
 * /terminal admin opens the op-only database console on the same screen:
 * click entries to reclassify or expunge them, and adjust the minimum
 * clearance required to write.
 */
public final class TerminalUi implements Listener {

    private static final int PAGE_SIZE = 45;
    private static final SimpleDateFormat DATE = new SimpleDateFormat("yyyy-MM-dd");

    /**
     * The terminal look. Each screen's title is a font glyph that repaints
     * the whole vanilla GUI as a SCiPNET panel (font terminal:gui, drawn in
     * white so the bitmap keeps its own colors; a negative space advance
     * first rewinds the title cursor to the GUI's top-left corner). Only the
     * terminal's own screens carry these titles - every other chest and
     * anvil on the server stays vanilla.
     */
    private static final String GLYPH_CHEST54 = "";
    private static final String GLYPH_CHEST27 = "";
    private static final String GLYPH_ANVIL = "";
    private static final String GLYPH_DESKTOP = "";

    private static Component overlay(String glyph) {
        return Component.text(glyph)
            .font(Key.key("terminal", "gui"))
            .color(NamedTextColor.WHITE);
    }

    /** Marks our inventories and remembers which screen + page they show. */
    private static final class Screen implements InventoryHolder {
        final String view; // login | desktop | list | myfiles | editor | admin | cctvadmin
        final int page;
        Inventory inventory;
        Screen(String view, int page) { this.view = view; this.page = page; }
        @Override public Inventory getInventory() { return inventory; }
    }

    /** Where a saved draft is filed: the public database or a private folder. */
    private enum Target { PUBLIC, PERSONAL }

    /** An entry being composed at the terminal (the custom draft editor). The
     *  target routes SAVE to either the public {@link EntryStore} or the
     *  player's private {@link PersonalStore}. */
    private static final class Draft {
        String title = "Untitled";
        Target target = Target.PUBLIC;
        final List<String> lines = new ArrayList<>();
    }

    private final TerminalPlugin plugin;
    private final EntryStore store;
    private final PersonalStore personal;
    private final TerminalManager machines;
    private final CctvManager cctv;
    private final CctvViewer cctvViewer;
    private final Map<UUID, Draft> drafts = new HashMap<>();
    /** Open anvil prompt per player: -1 = editing the title, otherwise a line
     *  index (== lines.size() means "append a new line"). */
    private final Map<UUID, Integer> prompts = new HashMap<>();
    /** Who is sitting at which terminal - drives the screen's on/off state. */
    private final Map<UUID, UUID> sessions = new HashMap<>();

    public TerminalUi(TerminalPlugin plugin, EntryStore store, PersonalStore personal,
                      TerminalManager machines, CctvManager cctv, CctvViewer cctvViewer) {
        this.plugin = plugin;
        this.store = store;
        this.personal = personal;
        this.machines = machines;
        this.cctv = cctv;
        this.cctvViewer = cctvViewer;
    }

    // ------------------------------------------------------------ luckperms

    private Component rank(Player player) {
        try {
            User user = LuckPermsProvider.get().getUserManager().getUser(player.getUniqueId());
            if (user != null) {
                String prefix = user.getCachedData().getMetaData().getPrefix();
                if (prefix != null && !prefix.isBlank()) {
                    return LegacyComponentSerializer.legacyAmpersand()
                        .deserialize(prefix.replace('§', '&'));
                }
                return Component.text(user.getPrimaryGroup(), NamedTextColor.WHITE);
            }
        } catch (IllegalStateException | NoClassDefFoundError ignored) { }
        return Component.text("Personnel", NamedTextColor.WHITE);
    }

    public int clearance(Player player) {
        if (hasSkeletonKey(player)) return 999; // SCP-005 opens every file
        try {
            User user = LuckPermsProvider.get().getUserManager().getUser(player.getUniqueId());
            if (user != null) {
                String value = user.getCachedData().getMetaData().getMetaValue("clearance");
                if (value != null) return Integer.parseInt(value.trim());
            }
        } catch (IllegalStateException | NoClassDefFoundError | NumberFormatException ignored) { }
        return 0;
    }

    /** SCP-005, the Skeleton Key: anywhere in the inventory, it reads every
     *  file regardless of clearance (and, via the other plugins, opens any
     *  door). Matched by its custom_model_data string. */
    static boolean hasSkeletonKey(Player player) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && item.hasItemMeta()
                && item.getItemMeta().getCustomModelDataComponent().getStrings().contains("scp005")) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------- screens

    @EventHandler
    public void onUse(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Interaction box)) return;
        if (!box.getScoreboardTags().contains(TerminalManager.TAG_BOX)) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        String anchorId = box.getPersistentDataContainer()
            .get(plugin.key("anchor"), PersistentDataType.STRING);
        if (anchorId != null) {
            UUID anchor = UUID.fromString(anchorId);
            boolean first = !sessions.containsValue(anchor);
            sessions.put(player.getUniqueId(), anchor);
            if (first) machines.setScreen(anchor, true); // the CRT wakes up
        }
        openLogin(player);
    }

    /**
     * The screen stays lit while ANY terminal window (chest screens or a
     * typing prompt) is open, and powers down when the last user walks away.
     * Screen-to-screen hops close one inventory and open the next within a
     * couple of ticks, so the check runs delayed.
     */
    @EventHandler
    public void onScreenClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        if (!(event.getInventory().getHolder() instanceof Screen)) return;
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof Screen) return;
            if (prompts.containsKey(player.getUniqueId())) return;
            endSession(player);
        }, 2L);
    }

    private void endSession(Player player) {
        UUID anchor = sessions.remove(player.getUniqueId());
        if (anchor != null && !sessions.containsValue(anchor)) {
            machines.setScreen(anchor, false); // last one out turns off the light
        }
    }

    public void openLogin(Player player) {
        Screen screen = new Screen("login", 0);
        Inventory inv = Bukkit.createInventory(screen, 27, overlay(GLYPH_CHEST27));
        screen.inventory = inv;
        // pre-login: a generic terminal/credentials icon - the player's OWN
        // head only appears once they've logged into the desktop (feature 2)
        inv.setItem(11, named(Material.OBSERVER, Component.text("SCiPNET Terminal", NamedTextColor.WHITE),
            List.of(line("Awaiting authentication.", NamedTextColor.GRAY))));
        inv.setItem(13, button(Material.LIME_CONCRETE, "btn_login",
            Component.text("LOG IN", NamedTextColor.GREEN).decoration(TextDecoration.BOLD, true),
            List.of(line("Access the entry database.", NamedTextColor.GRAY))));
        inv.setItem(15, named(Material.OBSERVER,
            Component.text("SCiPNET Terminal", NamedTextColor.AQUA),
            List.of(line("Log in to access the desktop.", NamedTextColor.GRAY))));
        player.openInventory(inv);
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BIT, 0.7f, 1.2f);
    }

    // ------------------------------------------------------------ the desktop

    /** The two SCiPNET desktop apps: their PDC id, custom item_model, base
     *  item and default slot. The live slot is read from / written to config
     *  (desktop-apps.<id>.slot) so a rearrange by anyone sticks server-wide. */
    private enum App {
        CCTV("cctv", "app_cctv", Material.PAPER, "CCTV Feeds", "Live camera grid.", 0),
        RECORDS("records", "app_records", Material.PAPER, "Records", "The entry database.", 9),
        MYFILES("myfiles", "app_myfiles", Material.PAPER, "My Files", "Your personal folder.", 18),
        // model == null: renders as its vanilla material (no custom pack icon needed)
        STATS("stats", null, Material.KNOWLEDGE_BOOK, "Stats", "Your combat record.", 27);
        final String id, model, title, blurb;
        final Material material;
        final int defaultSlot;
        App(String id, String model, Material material, String title, String blurb, int defaultSlot) {
            this.id = id; this.model = model; this.material = material; this.title = title;
            this.blurb = blurb; this.defaultSlot = defaultSlot;
        }
    }

    private int appSlot(App app) {
        int slot = plugin.getConfig().getInt("desktop-apps." + app.id + ".slot", app.defaultSlot);
        return (slot < 0 || slot >= 54) ? app.defaultSlot : slot;
    }

    private void setAppSlot(App app, int slot) {
        plugin.getConfig().set("desktop-apps." + app.id + ".slot", slot);
        plugin.saveConfig();
    }

    /** Builds one app icon: a custom-textured tile (item_model dispatched on
     *  PAPER) tagged with the app id so clicks can resolve it. In "picked up"
     *  move mode the tile is highlighted. */
    private ItemStack appIcon(App app, boolean picked) {
        List<Component> lore = new ArrayList<>();
        lore.add(line(app.blurb, NamedTextColor.GRAY));
        lore.add(line(picked ? "Click a slot to move it here." : "Double-click to open.",
            picked ? NamedTextColor.YELLOW : NamedTextColor.DARK_GRAY));
        lore.add(line("Shift-click: " + (picked ? "cancel move" : "pick up to move"),
            NamedTextColor.DARK_GRAY));
        ItemStack icon = named(app.material,
            Component.text(app.title, picked ? NamedTextColor.YELLOW : NamedTextColor.AQUA), lore);
        ItemMeta meta = icon.getItemMeta();
        if (app.model != null) meta.setItemModel(new org.bukkit.NamespacedKey("terminal", app.model));
        meta.getPersistentDataContainer().set(plugin.key("app"), PersistentDataType.STRING, app.id);
        icon.setItemMeta(meta);
        return icon;
    }

    /** Who is currently moving which app (shift-click pick-up), per player. */
    private final Map<UUID, String> moving = new HashMap<>();

    /**
     * The post-login DESKTOP: a computer desktop with the two app tiles
     * (CCTV Feeds, Records) and the player's OWN head as the account
     * indicator. Apps open on double-click; shift-click picks one up to move
     * it, and the next slot click drops it there (layout saved server-wide).
     */
    public void openDesktop(Player player) {
        Screen screen = new Screen("desktop", 0);
        Inventory inv = Bukkit.createInventory(screen, 54, overlay(GLYPH_DESKTOP));
        screen.inventory = inv;
        String held = moving.get(player.getUniqueId());
        for (App app : App.values()) {
            inv.setItem(appSlot(app), appIcon(app, app.id.equals(held)));
        }
        // the account indicator: the player's real skin head, only here AFTER login
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        org.bukkit.inventory.meta.SkullMeta skull =
            (org.bukkit.inventory.meta.SkullMeta) head.getItemMeta();
        skull.setOwningPlayer(player);
        skull.itemName(Component.text(player.getName(), NamedTextColor.WHITE)
            .decoration(TextDecoration.ITALIC, false));
        List<Component> hlore = new ArrayList<>();
        hlore.add(Component.text("Rank: ", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
            .append(rank(player)));
        hlore.add(line("Clearance: Level " + clearance(player), NamedTextColor.WHITE));
        hlore.add(line("Signed in.", NamedTextColor.DARK_GRAY));
        skull.lore(hlore);
        head.setItemMeta(skull);
        inv.setItem(8, head); // top-right of the desktop
        player.openInventory(inv);
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BIT, 0.6f, 1.5f);
    }

    private App appAt(int slot) {
        for (App app : App.values()) if (appSlot(app) == slot) return app;
        return null;
    }

    public void openList(Player player, int page) {
        List<EntryStore.Entry> entries = store.all();
        int pages = Math.max(1, (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        page = Math.max(0, Math.min(page, pages - 1));
        Screen screen = new Screen("list", page);
        Inventory inv = Bukkit.createInventory(screen, 54, overlay(GLYPH_CHEST54));
        screen.inventory = inv;
        int level = clearance(player);
        for (int i = 0; i < PAGE_SIZE; i++) {
            int index = page * PAGE_SIZE + i;
            if (index >= entries.size()) break;
            EntryStore.Entry entry = entries.get(index);
            if (entry.clearance() <= level) {
                inv.setItem(i, entryIcon(entry, Material.PAPER,
                    Component.text(entry.title(), NamedTextColor.WHITE),
                    line("Click to read.", NamedTextColor.DARK_GRAY)));
            } else {
                inv.setItem(i, entryIcon(entry, Material.BARRIER,
                    Component.text("ACCESS DENIED", NamedTextColor.RED),
                    line("Level " + entry.clearance() + " clearance required.", NamedTextColor.DARK_RED)));
            }
        }
        boolean mayWrite = level >= store.writeClearance();
        inv.setItem(45, button(Material.ARROW, "btn_prev", Component.text("Previous", NamedTextColor.GRAY),
            List.of(line("Page " + (page + 1) + "/" + pages, NamedTextColor.DARK_GRAY))));
        inv.setItem(46, button(Material.BARRIER, "btn_back",
            Component.text("< DESKTOP", NamedTextColor.GRAY), List.of()));
        if (player.hasPermission("terminal.cctv")) {
            inv.setItem(47, button(Material.ENDER_EYE, "btn_cameras",
                Component.text("CCTV GRID", NamedTextColor.AQUA),
                List.of(line("Jack into the camera grid.", NamedTextColor.GRAY),
                        line("Your body stays behind.", NamedTextColor.DARK_GRAY))));
        }
        inv.setItem(49, mayWrite
            ? button(Material.WRITABLE_BOOK, "btn_new", Component.text("NEW ENTRY", NamedTextColor.GREEN),
                List.of(line("Click: write here on the terminal.", NamedTextColor.GRAY),
                        line("Shift-click: take a physical draft book", NamedTextColor.GRAY),
                        line("to write anywhere and file by signing.", NamedTextColor.GRAY)))
            : named(Material.GRAY_DYE, Component.text("WRITE ACCESS DENIED", NamedTextColor.DARK_GRAY),
                List.of(line("Level " + store.writeClearance() + " clearance required to write.",
                    NamedTextColor.DARK_RED))));
        inv.setItem(53, button(Material.ARROW, "btn_next", Component.text("Next", NamedTextColor.GRAY),
            List.of(line("Page " + (page + 1) + "/" + pages, NamedTextColor.DARK_GRAY))));
        player.openInventory(inv);
    }

    // -------------------------------------------------------- personal folders

    /**
     * MY FILES: the player's OWN private folder. Personal documents are drafts
     * kept apart from the public database until released. Click a doc to READ
     * it (redactions render as usual); shift-right-click RELEASES it into the
     * public Records list. NEW PERSONAL DOC opens the same terminal draft
     * editor, but SAVE files into the personal store instead of the public one.
     */
    public void openMyFiles(Player player, int page) {
        List<EntryStore.Entry> docs = personal.list(player.getUniqueId());
        int pages = Math.max(1, (docs.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        page = Math.max(0, Math.min(page, pages - 1));
        Screen screen = new Screen("myfiles", page);
        Inventory inv = Bukkit.createInventory(screen, 54, overlay(GLYPH_CHEST54));
        screen.inventory = inv;
        for (int i = 0; i < PAGE_SIZE; i++) {
            int index = page * PAGE_SIZE + i;
            if (index >= docs.size()) break;
            EntryStore.Entry doc = docs.get(index);
            inv.setItem(i, personalIcon(doc,
                Component.text(doc.title(), NamedTextColor.WHITE),
                line("Click: read.", NamedTextColor.DARK_GRAY),
                line("Shift+Right-click: RELEASE to public database.", NamedTextColor.GREEN)));
        }
        inv.setItem(45, button(Material.ARROW, "btn_prev", Component.text("Previous", NamedTextColor.GRAY),
            List.of(line("Page " + (page + 1) + "/" + pages, NamedTextColor.DARK_GRAY))));
        inv.setItem(46, button(Material.BARRIER, "btn_back", Component.text("< DESKTOP", NamedTextColor.AQUA),
            List.of(line("Back to the desktop.", NamedTextColor.GRAY))));
        inv.setItem(49, button(Material.WRITABLE_BOOK, "btn_new",
            Component.text("NEW PERSONAL DOC", NamedTextColor.GREEN),
            List.of(line("Write a private document here on the terminal.", NamedTextColor.GRAY),
                    line("Saved to your folder; RELEASE later to go public.", NamedTextColor.DARK_GRAY))));
        inv.setItem(53, button(Material.ARROW, "btn_next", Component.text("Next", NamedTextColor.GRAY),
            List.of(line("Page " + (page + 1) + "/" + pages, NamedTextColor.DARK_GRAY))));
        player.openInventory(inv);
    }

    /** The STATS app: the player's combat record - the same numbers the Facility
     *  main menu shows, read straight from Facility's per-player data file so no
     *  compile dependency is needed. Read-only; one button back to the desktop. */
    public void openStats(Player player) {
        org.bukkit.configuration.file.YamlConfiguration cfg = facilityStats(player.getUniqueId());
        int kills = cfg.getInt("stats.kills", 0);
        int deaths = cfg.getInt("stats.deaths", 0);
        double kd = deaths == 0 ? kills : Math.round(kills / (double) deaths * 100.0) / 100.0;
        String area = cfg.getString("last-area", null);
        Component team = teamDisplay(cfg.getString("team", null));

        Screen screen = new Screen("stats", 0);
        Inventory inv = Bukkit.createInventory(screen, 27, overlay(GLYPH_CHEST27));
        screen.inventory = inv;
        inv.setItem(10, named(Material.DIAMOND_SWORD,
            Component.text("Kills: " + kills, NamedTextColor.GREEN),
            List.of(line("Confirmed kills (guns included).", NamedTextColor.DARK_GRAY))));
        inv.setItem(11, named(Material.WITHER_SKELETON_SKULL,
            Component.text("Deaths: " + deaths, NamedTextColor.RED),
            List.of(line("Times you fell.", NamedTextColor.DARK_GRAY))));
        inv.setItem(12, named(Material.NETHER_STAR,
            Component.text("K/D: " + kd, NamedTextColor.AQUA),
            List.of(line("Kill / death ratio.", NamedTextColor.DARK_GRAY))));
        inv.setItem(14, named(Material.LEATHER_CHESTPLATE,
            Component.text("Last played as: ", NamedTextColor.GRAY).append(team),
            List.of(line("The team you were on last.", NamedTextColor.DARK_GRAY))));
        inv.setItem(15, named(Material.FILLED_MAP,
            Component.text("Last area: " + (area == null ? "-" : area), NamedTextColor.AQUA),
            List.of(line("Where you last stood.", NamedTextColor.DARK_GRAY))));
        inv.setItem(22, button(Material.BARRIER, "btn_back",
            Component.text("< DESKTOP", NamedTextColor.AQUA),
            List.of(line("Back to the desktop.", NamedTextColor.GRAY))));
        player.openInventory(inv);
    }

    /** Facility's per-player stats file (plugins/Facility/players/&lt;uuid&gt;.yml),
     *  read straight off disk. Empty config if Facility isn't installed / no record. */
    private org.bukkit.configuration.file.YamlConfiguration facilityStats(java.util.UUID id) {
        java.io.File f = new java.io.File(plugin.getDataFolder().getParentFile(),
            "Facility" + java.io.File.separator + "players" + java.io.File.separator + id + ".yml");
        return org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(f);
    }

    /** The coloured team display name from Facility's config, or a dash. */
    private Component teamDisplay(String teamId) {
        if (teamId == null || teamId.isBlank()) return Component.text("-", NamedTextColor.DARK_GRAY);
        java.io.File cfgFile = new java.io.File(plugin.getDataFolder().getParentFile(),
            "Facility" + java.io.File.separator + "config.yml");
        var fac = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(cfgFile);
        String disp = fac.getString("teams." + teamId + ".display", teamId);
        return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand()
            .deserialize(disp).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false);
    }

    public void openAdmin(Player player, int page) {
        List<EntryStore.Entry> entries = store.all();
        int pages = Math.max(1, (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        page = Math.max(0, Math.min(page, pages - 1));
        Screen screen = new Screen("admin", page);
        Inventory inv = Bukkit.createInventory(screen, 54, overlay(GLYPH_CHEST54));
        screen.inventory = inv;
        for (int i = 0; i < PAGE_SIZE; i++) {
            int index = page * PAGE_SIZE + i;
            if (index >= entries.size()) break;
            EntryStore.Entry entry = entries.get(index);
            inv.setItem(i, entryIcon(entry, Material.PAPER,
                Component.text(entry.title(), NamedTextColor.WHITE),
                line("Left-click: clearance +1  Right-click: -1", NamedTextColor.GRAY),
                line("Shift+Right-click: EXPUNGE", NamedTextColor.RED)));
        }
        inv.setItem(45, button(Material.ARROW, "btn_prev", Component.text("Previous", NamedTextColor.GRAY),
            List.of(line("Page " + (page + 1) + "/" + pages, NamedTextColor.DARK_GRAY))));
        inv.setItem(46, button(Material.OBSERVER, "btn_cameras", Component.text("CAMERAS >", NamedTextColor.AQUA),
            List.of(line("Manage the CCTV grid.", NamedTextColor.GRAY))));
        inv.setItem(49, named(Material.REPEATER,
            Component.text("Write access: Level " + store.writeClearance(), NamedTextColor.AQUA),
            List.of(line("Minimum clearance to file new entries.", NamedTextColor.GRAY),
                    line("Left-click: +1  Right-click: -1", NamedTextColor.GRAY))));
        inv.setItem(53, button(Material.ARROW, "btn_next", Component.text("Next", NamedTextColor.GRAY),
            List.of(line("Page " + (page + 1) + "/" + pages, NamedTextColor.DARK_GRAY))));
        player.openInventory(inv);
    }

    /** The CCTV wing of the admin console: every camera on the grid, with
     *  redaction, panning and deletion a click away. */
    public void openCctvAdmin(Player player, int page) {
        List<CctvManager.Camera> cams = cctv.cameras();
        int pages = Math.max(1, (cams.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        page = Math.max(0, Math.min(page, pages - 1));
        Screen screen = new Screen("cctvadmin", page);
        Inventory inv = Bukkit.createInventory(screen, 54, overlay(GLYPH_CHEST54));
        screen.inventory = inv;
        for (int i = 0; i < PAGE_SIZE; i++) {
            int index = page * PAGE_SIZE + i;
            if (index >= cams.size()) break;
            CctvManager.Camera camera = cams.get(index);
            var at = camera.anchor().getLocation();
            List<Component> lore = new ArrayList<>();
            lore.add(line("Redaction: Level " + camera.redact(), NamedTextColor.DARK_AQUA));
            lore.add(line("Panning: " + (camera.pan() ? "on" : "off"), NamedTextColor.DARK_AQUA));
            lore.add(line(at.getWorld().getName() + " " + at.getBlockX() + " "
                + at.getBlockY() + " " + at.getBlockZ(), NamedTextColor.GRAY));
            lore.add(line("Left-click: redact +1 (wraps)  Right-click: pan", NamedTextColor.GRAY));
            lore.add(line("Shift+Right-click: DELETE", NamedTextColor.RED));
            ItemStack icon = named(Material.ENDER_EYE,
                Component.text(camera.name(), NamedTextColor.WHITE), lore);
            ItemMeta meta = icon.getItemMeta();
            meta.getPersistentDataContainer().set(plugin.key("cam_id"),
                PersistentDataType.STRING, camera.anchor().getUniqueId().toString());
            icon.setItemMeta(meta);
            inv.setItem(i, icon);
        }
        inv.setItem(45, button(Material.ARROW, "btn_prev", Component.text("Previous", NamedTextColor.GRAY),
            List.of(line("Page " + (page + 1) + "/" + pages, NamedTextColor.DARK_GRAY))));
        inv.setItem(46, button(Material.OBSERVER, "btn_back", Component.text("< ENTRIES", NamedTextColor.AQUA),
            List.of(line("Back to the entry database.", NamedTextColor.GRAY))));
        inv.setItem(53, button(Material.ARROW, "btn_next", Component.text("Next", NamedTextColor.GRAY),
            List.of(line("Page " + (page + 1) + "/" + pages, NamedTextColor.DARK_GRAY))));
        player.openInventory(inv);
    }

    // ------------------------------------------------------------- clicking

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Screen screen)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= event.getInventory().getSize()) return;

        switch (screen.view) {
            case "login" -> {
                if (slot == 13) openDesktop(player); // to the desktop, not straight to the list
            }
            case "desktop" -> {
                String held = moving.get(player.getUniqueId());
                if (held != null) {
                    // in MOVE mode: this click relocates the picked-up app here
                    // (dropping it onto another app just swaps their slots)
                    App picked = null;
                    for (App a : App.values()) if (a.id.equals(held)) picked = a;
                    if (picked != null) {
                        App occupant = appAt(slot);
                        if (occupant != null && occupant != picked) {
                            setAppSlot(occupant, appSlot(picked)); // swap
                        }
                        setAppSlot(picked, slot);
                        Msg.actionbar(player, line(picked.title + " moved.", NamedTextColor.GRAY));
                    }
                    moving.remove(player.getUniqueId());
                    openDesktop(player);
                    return;
                }
                App app = appAt(slot);
                if (app == null) return;
                if (event.isShiftClick()) {
                    // pick the app up: the next slot click drops it there
                    moving.put(player.getUniqueId(), app.id);
                    Msg.actionbar(player, line("Moving " + app.title
                        + " - click a slot to place it.", NamedTextColor.GRAY));
                    openDesktop(player);
                    return;
                }
                if (event.getClick() == org.bukkit.event.inventory.ClickType.DOUBLE_CLICK) {
                    switch (app) {
                        case CCTV -> {
                            if (!player.hasPermission("terminal.cctv")) {
                                Msg.actionbar(player, line("No clearance for the camera grid.",
                                    NamedTextColor.RED));
                                return;
                            }
                            cctvViewer.openGrid(player);
                        }
                        case MYFILES -> openMyFiles(player, 0);
                        case STATS -> openStats(player);
                        default -> openList(player, 0);
                    }
                    return;
                }
                // a single click just "selects" the app (feedback, no open)
                Msg.actionbar(player, line(app.title + " - double-click to open.",
                    NamedTextColor.DARK_GRAY));
            }
            case "list" -> {
                if (slot == 45) { openList(player, screen.page - 1); return; }
                if (slot == 46) { openDesktop(player); return; }
                if (slot == 53) { openList(player, screen.page + 1); return; }
                if (slot == 47) {
                    if (!player.hasPermission("terminal.cctv")) return;
                    cctvViewer.openGrid(player); // same grid as the handheld monitor
                    return;
                }
                if (slot == 49) {
                    if (clearance(player) < store.writeClearance()) return;
                    // normal click: the terminal's own draft editor, right here.
                    // shift-click: a physical draft book for writing on the go
                    // (signed anywhere, it files through the normal book GUI).
                    if (event.isShiftClick()) startDraft(player);
                    else openEditor(player, Target.PUBLIC);
                    return;
                }
                EntryStore.Entry entry = clickedEntry(event);
                if (entry == null) return;
                if (entry.clearance() > clearance(player)) {
                    Msg.actionbar(player, line("Access denied.", NamedTextColor.RED));
                    player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.8f, 0.8f);
                    return;
                }
                openBook(player, entry);
            }
            case "myfiles" -> {
                if (slot == 45) { openMyFiles(player, screen.page - 1); return; }
                if (slot == 53) { openMyFiles(player, screen.page + 1); return; }
                if (slot == 46) { openDesktop(player); return; }
                if (slot == 49) { openEditor(player, Target.PERSONAL); return; }
                EntryStore.Entry doc = clickedPersonal(player, event);
                if (doc == null) return;
                if (event.isShiftClick() && event.isRightClick()) {
                    EntryStore.Entry filed = personal.release(player.getUniqueId(), doc.id());
                    if (filed != null) {
                        Msg.actionbar(player, line("Filed to the public database.", NamedTextColor.GRAY));
                        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BIT, 0.8f, 1.6f);
                    }
                    openMyFiles(player, screen.page);
                    return;
                }
                openBook(player, doc); // read it, redactions render as usual
            }
            case "stats" -> {
                if (slot == 22) openDesktop(player);   // < DESKTOP
            }
            case "editor" -> {
                Draft draft = drafts.computeIfAbsent(player.getUniqueId(), k -> new Draft());
                if (slot == 4) { openPrompt(player, -1); return; }
                if (slot == 45) {
                    Target t = draft.target;
                    drafts.remove(player.getUniqueId());
                    Msg.actionbar(player, line("Draft discarded.", NamedTextColor.GRAY));
                    if (t == Target.PERSONAL) openMyFiles(player, 0); else openList(player, 0);
                    return;
                }
                if (slot == 49) { saveDraft(player, draft); return; }
                if (slot >= 9 && slot < 45) {
                    int index = slot - 9;
                    if (index < draft.lines.size()) {
                        if (event.isShiftClick() && event.isRightClick()) {
                            draft.lines.remove(index);
                            openEditor(player);
                        } else {
                            openPrompt(player, index);
                        }
                    } else if (index == draft.lines.size()) {
                        openPrompt(player, index); // the ADD LINE slot
                    }
                }
            }
            case "admin" -> {
                if (!player.hasPermission("terminal.admin")) { player.closeInventory(); return; }
                if (slot == 45) { openAdmin(player, screen.page - 1); return; }
                if (slot == 53) { openAdmin(player, screen.page + 1); return; }
                if (slot == 46) { openCctvAdmin(player, 0); return; }
                if (slot == 49) {
                    store.setWriteClearance(store.writeClearance() + (event.isRightClick() ? -1 : 1));
                    openAdmin(player, screen.page);
                    return;
                }
                EntryStore.Entry entry = clickedEntry(event);
                if (entry == null) return;
                if (event.isShiftClick() && event.isRightClick()) {
                    store.delete(entry.id());
                    Msg.actionbar(player, line("Entry expunged: " + entry.title(), NamedTextColor.RED));
                } else {
                    store.setClearance(entry.id(), entry.clearance() + (event.isRightClick() ? -1 : 1));
                }
                openAdmin(player, screen.page);
            }
            case "cctvadmin" -> {
                if (!player.hasPermission("terminal.admin")) { player.closeInventory(); return; }
                if (slot == 45) { openCctvAdmin(player, screen.page - 1); return; }
                if (slot == 53) { openCctvAdmin(player, screen.page + 1); return; }
                if (slot == 46) { openAdmin(player, 0); return; }
                CctvManager.Camera camera = clickedCamera(event);
                if (camera == null) return;
                if (event.isShiftClick() && event.isRightClick()) {
                    cctv.remove(camera);
                    Msg.actionbar(player, line("Camera deleted: " + camera.name(), NamedTextColor.RED));
                } else if (event.isRightClick()) {
                    cctv.togglePan(camera);
                } else {
                    cctv.setRedact(camera, camera.redact() >= 5 ? 0 : camera.redact() + 1);
                }
                openCctvAdmin(player, screen.page);
            }
        }
    }

    private CctvManager.Camera clickedCamera(InventoryClickEvent event) {
        ItemStack item = event.getCurrentItem();
        if (item == null || !item.hasItemMeta()) return null;
        String id = item.getItemMeta().getPersistentDataContainer()
            .get(plugin.key("cam_id"), PersistentDataType.STRING);
        if (id == null) return null;
        for (CctvManager.Camera camera : cctv.cameras()) {
            if (camera.anchor().getUniqueId().toString().equals(id)) return camera;
        }
        return null;
    }

    private EntryStore.Entry clickedEntry(InventoryClickEvent event) {
        ItemStack item = event.getCurrentItem();
        if (item == null || !item.hasItemMeta()) return null;
        Integer id = item.getItemMeta().getPersistentDataContainer()
            .get(plugin.key("entry"), PersistentDataType.INTEGER);
        return id == null ? null : store.get(id);
    }

    private void openBook(Player player, EntryStore.Entry entry) {
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();
        meta.setTitle(entry.title());
        meta.setAuthor(entry.author());
        List<Component> pages = new ArrayList<>();
        for (String page : entry.pages()) pages.add(renderPage(player, entry, page));
        if (pages.isEmpty()) pages.add(Component.text("(empty)"));
        meta.pages(pages);
        book.setItemMeta(meta);
        player.openBook(book);
        player.playSound(player.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 0.8f, 1.0f);
    }

    /**
     * REDACTIONS. Authors mark passages [[like this]] (author's eyes only) or
     * [[3:like this]] (author + Level 3 and up). Everyone else gets a black
     * bar of the same length, SCP document style. Ops see through everything.
     */
    private static final java.util.regex.Pattern REDACTION =
        java.util.regex.Pattern.compile("\\[\\[(?:(\\d)\\s*:)?(.*?)\\]\\]", java.util.regex.Pattern.DOTALL);

    private Component renderPage(Player reader, EntryStore.Entry entry, String raw) {
        var out = Component.text();
        var m = REDACTION.matcher(raw);
        int last = 0;
        while (m.find()) {
            out.append(Component.text(raw.substring(last, m.start())));
            String levelPart = m.group(1);
            String secret = m.group(2);
            boolean mayRead = reader.getName().equals(entry.author())
                || reader.hasPermission("terminal.admin")
                || (levelPart != null && clearance(reader) >= Integer.parseInt(levelPart));
            out.append(mayRead
                ? Component.text(secret, NamedTextColor.DARK_RED)
                : Component.text("█".repeat(Math.max(2, secret.length())), NamedTextColor.BLACK));
            last = m.end();
        }
        out.append(Component.text(raw.substring(last)));
        return out.build();
    }

    // ----------------------------------------------- the terminal's editor

    /**
     * The SCiPNET draft editor: composing happens ON the terminal, no book
     * item involved. The title and every line are typed through anvil
     * prompts (the SCP-294 pattern); SAVE files the entry at the author's
     * clearance. A physical draft book (shift-click NEW ENTRY) still uses
     * the normal vanilla book GUI instead - this editor is only for writing
     * at the terminal itself.
     */
    /** Open the editor for a fresh draft aimed at a specific target (public
     *  database vs. the player's personal folder). */
    public void openEditor(Player player, Target target) {
        Draft draft = drafts.computeIfAbsent(player.getUniqueId(), k -> new Draft());
        draft.target = target;
        openEditor(player);
    }

    public void openEditor(Player player) {
        Draft draft = drafts.computeIfAbsent(player.getUniqueId(), k -> new Draft());
        Screen screen = new Screen("editor", 0);
        Inventory inv = Bukkit.createInventory(screen, 54, overlay(GLYPH_CHEST54));
        screen.inventory = inv;
        inv.setItem(4, named(Material.NAME_TAG,
            Component.text("Title: ", NamedTextColor.GRAY)
                .append(Component.text(draft.title, NamedTextColor.WHITE)),
            List.of(line("Click to retitle.", NamedTextColor.DARK_GRAY))));
        for (int i = 0; i < draft.lines.size() && i < 36; i++) {
            inv.setItem(9 + i, named(Material.PAPER,
                Component.text(draft.lines.get(i), NamedTextColor.WHITE),
                List.of(line("Click: rewrite", NamedTextColor.DARK_GRAY),
                        line("Shift+Right-click: remove", NamedTextColor.DARK_GRAY))));
        }
        if (draft.lines.size() < 36) {
            inv.setItem(9 + draft.lines.size(), named(Material.LIME_DYE,
                Component.text("ADD LINE", NamedTextColor.GREEN), List.of()));
        }
        inv.setItem(45, button(Material.RED_CONCRETE, "btn_back",
            Component.text("DISCARD", NamedTextColor.RED), List.of()));
        boolean personalDraft = draft.target == Target.PERSONAL;
        inv.setItem(49, button(Material.LIME_CONCRETE, "btn_new",
            Component.text(personalDraft ? "SAVE TO MY FILES" : "SAVE ENTRY", NamedTextColor.GREEN)
                .decoration(TextDecoration.BOLD, true),
            List.of(line(personalDraft
                    ? "Saved privately at Level " + clearance(player) + "."
                    : "Filed at Level " + clearance(player) + ".", NamedTextColor.GRAY),
                line(personalDraft ? "RELEASE later to go public." : "Public database.",
                    NamedTextColor.DARK_GRAY))));
        inv.setItem(53, named(Material.BOOK, Component.text("Redactions", NamedTextColor.AQUA),
            List.of(line("[[text]] - your eyes only", NamedTextColor.GRAY),
                    line("[[3:text]] - Level 3 and up", NamedTextColor.GRAY),
                    line("Others see a black bar.", NamedTextColor.DARK_GRAY))));
        player.openInventory(inv);
    }

    /**
     * One anvil prompt: type, then take the paper from the result slot.
     * Editing something that already has text PREFILLS the field with it
     * (the anvil's rename box starts from the input item's name), so a
     * misclick never wipes a written line - Esc backs out unchanged.
     */
    private void openPrompt(Player player, int index) {
        Draft draft = drafts.computeIfAbsent(player.getUniqueId(), k -> new Draft());
        String current = index == -1 ? draft.title
            : index < draft.lines.size() ? draft.lines.get(index) : "";
        InventoryView view = MenuType.ANVIL.create(player, overlay(GLYPH_ANVIL));
        player.openInventory(view);
        prompts.put(player.getUniqueId(), index);
        Component name = current.isEmpty()
            ? Component.text(index == -1 ? "Type the title..." : "Type the line...", NamedTextColor.GRAY)
            : Component.text(current, NamedTextColor.WHITE);
        view.getTopInventory().setItem(0, named(Material.PAPER, name,
            List.of(line("Then take the paper from the right.", NamedTextColor.DARK_GRAY))));
    }

    @EventHandler
    public void onPreparePrompt(PrepareAnvilEvent event) {
        if (!(event.getView().getPlayer() instanceof Player player)) return;
        if (!prompts.containsKey(player.getUniqueId())) return;
        AnvilView view = event.getView();
        view.setRepairCost(0);
        String text = view.getRenameText();
        if (text == null || text.isBlank()) { event.setResult(null); return; }
        event.setResult(named(Material.PAPER, Component.text(text, NamedTextColor.WHITE),
            List.of(line("Take me to confirm.", NamedTextColor.DARK_GRAY))));
    }

    @EventHandler
    public void onPromptClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Integer index = prompts.get(player.getUniqueId());
        if (index == null || !(event.getView().getTopInventory() instanceof AnvilInventory)) return;
        event.setCancelled(true);
        if (event.getRawSlot() != 2 || event.getCurrentItem() == null
            || event.getCurrentItem().getType() != Material.PAPER) return;
        String text = event.getView() instanceof AnvilView anvil ? anvil.getRenameText() : null;
        if (text == null || text.isBlank()) return;
        prompts.remove(player.getUniqueId());
        // empty the anvil BEFORE the screen changes - whatever is left in it
        // when it closes would be handed to the player (the ghost-paper bug)
        event.getView().getTopInventory().clear();
        Draft draft = drafts.computeIfAbsent(player.getUniqueId(), k -> new Draft());
        if (index == -1) draft.title = text;
        else if (index >= draft.lines.size()) draft.lines.add(text);
        else draft.lines.set(index, text);
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BIT, 0.7f, 1.4f);
        openEditor(player);
    }

    /** Esc out of a prompt: back to the editor, nothing lost. */
    @EventHandler
    public void onPromptClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        if (!(event.getInventory() instanceof AnvilInventory anvil)) return;
        if (!prompts.containsKey(player.getUniqueId())) return;
        anvil.clear(); // the prompt paper is a ghost item, never dropped
        prompts.remove(player.getUniqueId());
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && drafts.containsKey(player.getUniqueId())) openEditor(player);
        });
    }

    private void saveDraft(Player player, Draft draft) {
        if (draft.lines.isEmpty()) {
            Msg.actionbar(player, line("Nothing written yet.", NamedTextColor.RED));
            return;
        }
        // lines -> book pages, greedily, so long entries read naturally
        List<String> pages = new ArrayList<>();
        StringBuilder page = new StringBuilder();
        int linesOnPage = 0;
        for (String text : draft.lines) {
            if (linesOnPage >= 13 || page.length() + text.length() > 230) {
                pages.add(page.toString());
                page.setLength(0);
                linesOnPage = 0;
            }
            if (page.length() > 0) page.append('\n');
            page.append(text);
            linesOnPage++;
        }
        if (page.length() > 0) pages.add(page.toString());
        Target target = draft.target;
        EntryStore.Entry entry = target == Target.PERSONAL
            ? personal.add(player.getUniqueId(), draft.title, player.getName(), clearance(player), pages)
            : store.add(draft.title, player.getName(), clearance(player), pages);
        drafts.remove(player.getUniqueId());
        Msg.actionbar(player, line((target == Target.PERSONAL ? "Saved to My Files: " : "Entry filed: ")
            + entry.title() + " (Level " + entry.clearance() + ")", NamedTextColor.GRAY));
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BIT, 0.8f, 1.6f);
        if (target == Target.PERSONAL) openMyFiles(player, 0); else openList(player, 0);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        endSession(event.getPlayer());
        drafts.remove(event.getPlayer().getUniqueId());
        prompts.remove(event.getPlayer().getUniqueId());
        moving.remove(event.getPlayer().getUniqueId());
    }

    // ------------------------------------- physical drafts (vanilla book GUI)

    private void startDraft(Player player) {
        player.closeInventory();
        ItemStack draft = new ItemStack(Material.WRITABLE_BOOK);
        ItemMeta meta = draft.getItemMeta();
        meta.itemName(Component.text("Terminal Draft", NamedTextColor.AQUA)
            .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
            line("[[text]] - redacted, your eyes only", NamedTextColor.GRAY),
            line("[[3:text]] - visible to Level 3 and up", NamedTextColor.GRAY),
            line("Others see a black bar instead.", NamedTextColor.DARK_GRAY)));
        meta.getPersistentDataContainer().set(plugin.key("draft"), PersistentDataType.BYTE, (byte) 1);
        draft.setItemMeta(meta);
        var leftover = player.getInventory().addItem(draft);
        if (!leftover.isEmpty()) {
            Msg.actionbar(player, line("No room for the draft book.", NamedTextColor.RED));
            return;
        }
        Msg.actionbar(player, line("Write the entry, then SIGN the book to file it.", NamedTextColor.GRAY));
        player.playSound(player.getLocation(), Sound.ITEM_BOOK_PUT, 0.8f, 1.2f);
    }

    /** Signing a draft book files it as an entry and the book vanishes. */
    @EventHandler
    public void onSign(PlayerEditBookEvent event) {
        if (!event.isSigning()) return;
        Player player = event.getPlayer();
        ItemStack held = player.getInventory().getItem(event.getSlot());
        if (held == null || !held.hasItemMeta() || !held.getItemMeta().getPersistentDataContainer()
                .has(plugin.key("draft"), PersistentDataType.BYTE)) {
            return;
        }
        event.setCancelled(true);
        BookMeta meta = event.getNewBookMeta();
        String title = meta.hasTitle() ? meta.getTitle() : "Untitled";
        List<String> pages = new ArrayList<>();
        for (Component page : meta.pages()) {
            pages.add(PlainTextComponentSerializer.plainText().serialize(page));
        }
        EntryStore.Entry entry = store.add(title, player.getName(), clearance(player), pages);
        player.getInventory().setItem(event.getSlot(), null);
        Msg.actionbar(player, line("Entry filed: " + entry.title()
            + " (Level " + entry.clearance() + ")", NamedTextColor.GRAY));
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BIT, 0.8f, 1.6f);
    }

    // ------------------------------------------------------------- helpers

    private ItemStack entryIcon(EntryStore.Entry entry, Material material,
                                Component name, Component... extraLore) {
        List<Component> lore = new ArrayList<>();
        lore.add(line("by " + entry.author() + ", " + DATE.format(new Date(entry.created())),
            NamedTextColor.GRAY));
        lore.add(line("Level " + entry.clearance(), NamedTextColor.DARK_AQUA));
        lore.addAll(List.of(extraLore));
        // readable records get the custom doc texture; ACCESS DENIED barriers
        // stay vanilla so the deny state reads unmistakably
        ItemStack icon = material == Material.PAPER
            ? button(material, "doc", name, lore) : named(material, name, lore);
        ItemMeta meta = icon.getItemMeta();
        meta.getPersistentDataContainer().set(plugin.key("entry"), PersistentDataType.INTEGER, entry.id());
        icon.setItemMeta(meta);
        return icon;
    }

    /** A personal-document icon: like {@link #entryIcon} but tagged with the
     *  "personal" PDC key and textured with the custom doc model. */
    private ItemStack personalIcon(EntryStore.Entry doc, Component name, Component... extraLore) {
        List<Component> lore = new ArrayList<>();
        lore.add(line("by " + doc.author() + ", " + DATE.format(new Date(doc.created())),
            NamedTextColor.GRAY));
        lore.add(line("Level " + doc.clearance(), NamedTextColor.DARK_AQUA));
        lore.addAll(List.of(extraLore));
        ItemStack icon = button(Material.PAPER, "doc", name, lore);
        ItemMeta meta = icon.getItemMeta();
        meta.getPersistentDataContainer().set(plugin.key("personal"), PersistentDataType.INTEGER, doc.id());
        icon.setItemMeta(meta);
        return icon;
    }

    private EntryStore.Entry clickedPersonal(Player player, InventoryClickEvent event) {
        ItemStack item = event.getCurrentItem();
        if (item == null || !item.hasItemMeta()) return null;
        Integer id = item.getItemMeta().getPersistentDataContainer()
            .get(plugin.key("personal"), PersistentDataType.INTEGER);
        return id == null ? null : personal.get(player.getUniqueId(), id);
    }

    private ItemStack named(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.itemName(name.decoration(TextDecoration.ITALIC, false));
        if (!lore.isEmpty()) meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    /**
     * A named GUI item that ALSO carries a custom terminal item_model, so the
     * button/folder/doc renders as bespoke SCiPNET art (feature 2). If the
     * resource pack is absent the item simply falls back to its base material,
     * so everything still works unpacked. modelId maps to terminal:&lt;modelId&gt;
     * (see tools/gen_buttons.py for the texture set).
     */
    private ItemStack button(Material base, String modelId, Component name, List<Component> lore) {
        ItemStack item = named(base, name, lore);
        ItemMeta meta = item.getItemMeta();
        meta.setItemModel(new org.bukkit.NamespacedKey("terminal", modelId));
        item.setItemMeta(meta);
        return item;
    }

    private Component line(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }
}
