package fi.alavesa.terminal;

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

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

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

    /** Marks our inventories and remembers which screen + page they show. */
    private static final class Screen implements InventoryHolder {
        final String view; // login | list | admin
        final int page;
        Inventory inventory;
        Screen(String view, int page) { this.view = view; this.page = page; }
        @Override public Inventory getInventory() { return inventory; }
    }

    private final TerminalPlugin plugin;
    private final EntryStore store;

    public TerminalUi(TerminalPlugin plugin, EntryStore store) {
        this.plugin = plugin;
        this.store = store;
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
        try {
            User user = LuckPermsProvider.get().getUserManager().getUser(player.getUniqueId());
            if (user != null) {
                String value = user.getCachedData().getMetaData().getMetaValue("clearance");
                if (value != null) return Integer.parseInt(value.trim());
            }
        } catch (IllegalStateException | NoClassDefFoundError | NumberFormatException ignored) { }
        return 0;
    }

    // ------------------------------------------------------------- screens

    @EventHandler
    public void onUse(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Interaction box)) return;
        if (!box.getScoreboardTags().contains(TerminalManager.TAG_BOX)) return;
        event.setCancelled(true);
        openLogin(event.getPlayer());
    }

    public void openLogin(Player player) {
        Screen screen = new Screen("login", 0);
        Inventory inv = Bukkit.createInventory(screen, 27,
            Component.text("SCiPNET // LOGIN", NamedTextColor.DARK_GRAY));
        screen.inventory = inv;
        inv.setItem(11, named(Material.PLAYER_HEAD, Component.text(player.getName(), NamedTextColor.WHITE),
            List.of(line("Identity confirmed.", NamedTextColor.GRAY))));
        inv.setItem(13, named(Material.LIME_CONCRETE,
            Component.text("LOG IN", NamedTextColor.GREEN).decoration(TextDecoration.BOLD, true),
            List.of(line("Access the entry database.", NamedTextColor.GRAY))));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("Rank: ", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
            .append(rank(player)));
        lore.add(line("Clearance: Level " + clearance(player), NamedTextColor.WHITE));
        inv.setItem(15, named(Material.PAPER, Component.text("Credentials", NamedTextColor.AQUA), lore));
        player.openInventory(inv);
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BIT, 0.7f, 1.2f);
    }

    public void openList(Player player, int page) {
        List<EntryStore.Entry> entries = store.all();
        int pages = Math.max(1, (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        page = Math.max(0, Math.min(page, pages - 1));
        Screen screen = new Screen("list", page);
        Inventory inv = Bukkit.createInventory(screen, 54,
            Component.text("SCiPNET // ENTRIES " + (page + 1) + "/" + pages, NamedTextColor.DARK_GRAY));
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
        inv.setItem(45, named(Material.ARROW, Component.text("Previous", NamedTextColor.GRAY), List.of()));
        inv.setItem(49, mayWrite
            ? named(Material.WRITABLE_BOOK, Component.text("NEW ENTRY", NamedTextColor.GREEN),
                List.of(line("Take a draft book. Write it, SIGN it,", NamedTextColor.GRAY),
                        line("and it is filed at your clearance level.", NamedTextColor.GRAY)))
            : named(Material.GRAY_DYE, Component.text("WRITE ACCESS DENIED", NamedTextColor.DARK_GRAY),
                List.of(line("Level " + store.writeClearance() + " clearance required to write.",
                    NamedTextColor.DARK_RED))));
        inv.setItem(53, named(Material.ARROW, Component.text("Next", NamedTextColor.GRAY), List.of()));
        player.openInventory(inv);
    }

    public void openAdmin(Player player, int page) {
        List<EntryStore.Entry> entries = store.all();
        int pages = Math.max(1, (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        page = Math.max(0, Math.min(page, pages - 1));
        Screen screen = new Screen("admin", page);
        Inventory inv = Bukkit.createInventory(screen, 54,
            Component.text("SCiPNET // DATABASE ADMIN " + (page + 1) + "/" + pages, NamedTextColor.DARK_RED));
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
        inv.setItem(45, named(Material.ARROW, Component.text("Previous", NamedTextColor.GRAY), List.of()));
        inv.setItem(49, named(Material.REPEATER,
            Component.text("Write access: Level " + store.writeClearance(), NamedTextColor.AQUA),
            List.of(line("Minimum clearance to file new entries.", NamedTextColor.GRAY),
                    line("Left-click: +1  Right-click: -1", NamedTextColor.GRAY))));
        inv.setItem(53, named(Material.ARROW, Component.text("Next", NamedTextColor.GRAY), List.of()));
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
                if (slot == 13) openList(player, 0);
            }
            case "list" -> {
                if (slot == 45) { openList(player, screen.page - 1); return; }
                if (slot == 53) { openList(player, screen.page + 1); return; }
                if (slot == 49) { startDraft(player); return; }
                EntryStore.Entry entry = clickedEntry(event);
                if (entry == null) return;
                if (entry.clearance() > clearance(player)) {
                    player.sendActionBar(line("Access denied.", NamedTextColor.RED));
                    player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.8f, 0.8f);
                    return;
                }
                openBook(player, entry);
            }
            case "admin" -> {
                if (!player.hasPermission("terminal.admin")) { player.closeInventory(); return; }
                if (slot == 45) { openAdmin(player, screen.page - 1); return; }
                if (slot == 53) { openAdmin(player, screen.page + 1); return; }
                if (slot == 49) {
                    store.setWriteClearance(store.writeClearance() + (event.isRightClick() ? -1 : 1));
                    openAdmin(player, screen.page);
                    return;
                }
                EntryStore.Entry entry = clickedEntry(event);
                if (entry == null) return;
                if (event.isShiftClick() && event.isRightClick()) {
                    store.delete(entry.id());
                    player.sendActionBar(line("Entry expunged: " + entry.title(), NamedTextColor.RED));
                } else {
                    store.setClearance(entry.id(), entry.clearance() + (event.isRightClick() ? -1 : 1));
                }
                openAdmin(player, screen.page);
            }
        }
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

    // ------------------------------------------------------------- writing

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
            player.sendActionBar(line("No room for the draft book.", NamedTextColor.RED));
            return;
        }
        player.sendActionBar(line("Write the entry, then SIGN the book to file it.", NamedTextColor.GRAY));
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
        player.sendActionBar(line("Entry filed: " + entry.title()
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
        ItemStack icon = named(material, name, lore);
        ItemMeta meta = icon.getItemMeta();
        meta.getPersistentDataContainer().set(plugin.key("entry"), PersistentDataType.INTEGER, entry.id());
        icon.setItemMeta(meta);
        return icon;
    }

    private ItemStack named(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.itemName(name.decoration(TextDecoration.ITALIC, false));
        if (!lore.isEmpty()) meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private Component line(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }
}
