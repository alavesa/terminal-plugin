package fi.alavesa.terminal;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Marker;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.List;

/**
 * The physical terminal: the house spawner-model method - a Marker anchor,
 * an ItemDisplay wearing the model (observer + custom_model_data string
 * "scp_terminal") and an Interaction box to click. Placement is DIRECTIONAL:
 * the screen turns to face whoever placed it, snapped to 90 degrees.
 *
 * The terminal item is a real placeable block item, so a creative-mode player
 * places it like any block (BlockPlaceEvent is intercepted and the block swap
 * becomes a machine spawn). Ops get it with /terminal give; creative players
 * may give it to themselves too - the creative menu itself cannot be extended
 * by a plugin, this is the closest Paper allows.
 */
public final class TerminalManager implements Listener {

    public static final String TAG_ANCHOR = "terminal.anchor";
    public static final String TAG_PART = "terminal.part";
    public static final String TAG_BOX = "terminal.box";
    public static final String CMD = "scp_terminal";

    private final TerminalPlugin plugin;

    public TerminalManager(TerminalPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------- the item

    public ItemStack buildItem() {
        ItemStack item = new ItemStack(Material.OBSERVER);
        ItemMeta meta = item.getItemMeta();
        meta.itemName(Component.text("SCiPNET Terminal", NamedTextColor.AQUA)
            .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(Component.text("Place to install. Faces you.", NamedTextColor.GRAY)
            .decoration(TextDecoration.ITALIC, false)));
        CustomModelDataComponent cmd = meta.getCustomModelDataComponent();
        cmd.setStrings(List.of(CMD));
        meta.setCustomModelDataComponent(cmd);
        meta.getPersistentDataContainer().set(plugin.key("terminal"), PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public boolean isTerminalItem(ItemStack item) {
        return item != null && item.getType() == Material.OBSERVER && item.hasItemMeta()
            && item.getItemMeta().getPersistentDataContainer()
                .has(plugin.key("terminal"), PersistentDataType.BYTE);
    }

    // ------------------------------------------------------------- placement

    @EventHandler
    public void onPlace(BlockPlaceEvent event) {
        if (!isTerminalItem(event.getItemInHand())) return;
        event.setCancelled(true); // no observer block - a machine instead
        Player player = event.getPlayer();
        place(event.getBlockPlaced().getLocation(), player);
        if (player.getGameMode() != org.bukkit.GameMode.CREATIVE) {
            event.getItemInHand().setAmount(event.getItemInHand().getAmount() - 1);
        }
        player.sendActionBar(Component.text("Terminal installed.", NamedTextColor.GRAY, TextDecoration.ITALIC));
    }

    /** Spawn the machine at the given block, screen turned toward the placer. */
    public void place(Location block, Player placer) {
        float yaw = snap(placer.getLocation().getYaw());
        Location base = block.clone().add(0.5, 0, 0.5);
        base.setYaw(yaw);
        base.setPitch(0); // displays inherit pitch - never let them tilt

        Marker anchor = base.getWorld().spawn(base, Marker.class, m -> {
            m.setPersistent(true);
            m.addScoreboardTag(TAG_ANCHOR);
        });
        Location at = base.clone().add(0, 0.5, 0);
        at.setPitch(0);
        base.getWorld().spawn(at, ItemDisplay.class, display -> {
            display.setPersistent(true);
            display.setShadowRadius(0.6f);
            display.setShadowStrength(0.6f);
            display.setBrightness(new Display.Brightness(15, 15)); // the CRT glows in the dark
            display.setTransformation(new Transformation(
                new Vector3f(0, 0, 0), new AxisAngle4f(0, 0, 0, 1),
                new Vector3f(1, 1, 1), new AxisAngle4f(0, 0, 0, 1)));
            display.setItemStack(modelItem());
            display.addScoreboardTag(TAG_PART);
            display.getPersistentDataContainer().set(plugin.key("anchor"),
                PersistentDataType.STRING, anchor.getUniqueId().toString());
        });
        Location box = block.clone().add(0.5, 0, 0.5);
        box.setPitch(0);
        base.getWorld().spawn(box, Interaction.class, i -> {
            i.setInteractionWidth(1.0f);
            i.setInteractionHeight(1.05f);
            i.setPersistent(true);
            i.addScoreboardTag(TAG_PART);
            i.addScoreboardTag(TAG_BOX);
            i.getPersistentDataContainer().set(plugin.key("anchor"),
                PersistentDataType.STRING, anchor.getUniqueId().toString());
        });
        base.getWorld().playSound(base, Sound.BLOCK_ANVIL_PLACE, 0.5f, 1.6f);
    }

    /** Remove the nearest terminal within 4 blocks. Returns true if one died. */
    public boolean removeNearest(Player player) {
        Marker anchor = player.getLocation().getNearbyEntitiesByType(Marker.class, 4).stream()
            .filter(m -> m.getScoreboardTags().contains(TAG_ANCHOR))
            .min((a, b) -> Double.compare(
                a.getLocation().distanceSquared(player.getLocation()),
                b.getLocation().distanceSquared(player.getLocation())))
            .orElse(null);
        if (anchor == null) return false;
        String id = anchor.getUniqueId().toString();
        for (Entity part : anchor.getLocation().getNearbyEntities(2, 2, 2)) {
            if (id.equals(part.getPersistentDataContainer()
                    .get(plugin.key("anchor"), PersistentDataType.STRING))) {
                part.remove();
            }
        }
        anchor.remove();
        return true;
    }

    private ItemStack modelItem() {
        ItemStack item = new ItemStack(Material.OBSERVER);
        ItemMeta meta = item.getItemMeta();
        CustomModelDataComponent cmd = meta.getCustomModelDataComponent();
        cmd.setStrings(List.of(CMD));
        meta.setCustomModelDataComponent(cmd);
        item.setItemMeta(meta);
        return item;
    }

    /** Snap a yaw to the nearest 90 degrees so terminals sit square. */
    private float snap(float yaw) {
        return Math.round(yaw / 90f) * 90f;
    }
}
