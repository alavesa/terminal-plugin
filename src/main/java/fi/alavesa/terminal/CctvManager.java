package fi.alavesa.terminal;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Marker;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * The cameras. Each one is the house machine trio - Marker anchor (carries
 * name, redaction level and pan flag in PDC), an ItemDisplay wearing the
 * camera model through the item_model component, and an invisible marker
 * ArmorStand floating just past the lens: the EYE. Spectating the eye IS
 * the live feed (CctvViewer). Panning cameras swing display + eye together
 * on a sine, so the feed sweeps the room.
 *
 * Cameras mount on the wall face you're looking at, at the height you hit,
 * angled slightly downward like every security camera ever bolted up.
 */
public final class CctvManager {

    public static final String TAG_ANCHOR = "terminal.cctv.anchor";
    public static final String TAG_PART = "terminal.cctv.part";
    public static final String TAG_EYE = "terminal.cctv.eye";
    private static final float DOWN_TILT = 18f;

    /** One camera, resolved live from its anchor. */
    public record Camera(Marker anchor, String name, int redact, boolean pan) { }

    private final TerminalPlugin plugin;

    public CctvManager(TerminalPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------ placement

    /** Mounts a camera on the wall block face the player is looking at. */
    public boolean place(Player player) {
        var ray = player.rayTraceBlocks(6.0);
        if (ray == null || ray.getHitBlock() == null || ray.getHitBlockFace() == null) {
            error(player, "Look at a wall within 6 blocks.");
            return false;
        }
        Block wall = ray.getHitBlock();
        BlockFace facing = ray.getHitBlockFace();
        if (facing == BlockFace.UP || facing == BlockFace.DOWN
            || Math.abs(facing.getModX()) + Math.abs(facing.getModZ()) != 1) {
            error(player, "On a WALL - not a floor or ceiling.");
            return false;
        }
        float yaw = switch (facing) { // model faces SOUTH at yaw 0
            case NORTH -> 180f;
            case EAST -> -90f;
            case WEST -> 90f;
            default -> 0f;
        };
        int number = plugin.getConfig().getInt("cctv-next-number", 1);
        plugin.getConfig().set("cctv-next-number", number + 1);
        plugin.saveConfig();
        String name = String.format("CAM-%02d", number);

        Location base = wall.getRelative(facing).getLocation().add(0.5, 0.2, 0.5);
        base.setYaw(yaw);
        base.setPitch(0); // displays inherit pitch - never let them tilt

        Marker anchor = base.getWorld().spawn(base, Marker.class, m -> {
            m.setPersistent(true);
            m.addScoreboardTag(TAG_ANCHOR);
            m.getPersistentDataContainer().set(plugin.key("cam_name"), PersistentDataType.STRING, name);
            m.getPersistentDataContainer().set(plugin.key("cam_redact"), PersistentDataType.INTEGER, 0);
            m.getPersistentDataContainer().set(plugin.key("cam_pan"), PersistentDataType.BYTE, (byte) 0);
            m.getPersistentDataContainer().set(plugin.key("cam_yaw"), PersistentDataType.FLOAT, yaw);
        });
        base.getWorld().spawn(base, ItemDisplay.class, d -> {
            d.setPersistent(true);
            d.setBrightness(new Display.Brightness(15, 15)); // the REC light never sleeps
            ItemStack item = new ItemStack(Material.PAPER);
            ItemMeta meta = item.getItemMeta();
            meta.setItemModel(new NamespacedKey("terminal", "cctv_camera"));
            item.setItemMeta(meta);
            d.setItemStack(item);
            d.addScoreboardTag(TAG_PART);
            link(d, anchor);
        });
        // the eye: past the lens, looking out and slightly down
        Location eyeAt = base.clone().add(base.getDirection().multiply(0.55)).add(0, 0.5, 0);
        eyeAt.setYaw(yaw);
        eyeAt.setPitch(DOWN_TILT);
        base.getWorld().spawn(eyeAt, ArmorStand.class, eye -> {
            eye.setPersistent(true);
            eye.setInvisible(true);
            eye.setMarker(true);
            eye.setSmall(true);
            eye.setGravity(false);
            eye.addScoreboardTag(TAG_PART);
            eye.addScoreboardTag(TAG_EYE);
            link(eye, anchor);
        });
        base.getWorld().playSound(base, Sound.BLOCK_IRON_TRAPDOOR_CLOSE, 0.6f, 1.7f);
        player.sendMessage(Component.text("Camera " + name + " mounted (facing "
            + facing.getOppositeFace().name().toLowerCase() + "). /terminal cctv redact <0-5> to classify it.",
            NamedTextColor.AQUA));
        return true;
    }

    private void link(Entity part, Marker anchor) {
        part.getPersistentDataContainer().set(plugin.key("anchor"),
            PersistentDataType.STRING, anchor.getUniqueId().toString());
    }

    public boolean removeNearest(Player player) {
        Camera camera = nearest(player, 5);
        if (camera == null) return false;
        String id = camera.anchor().getUniqueId().toString();
        for (Entity part : camera.anchor().getLocation().getNearbyEntities(2, 2, 2)) {
            if (id.equals(part.getPersistentDataContainer()
                    .get(plugin.key("anchor"), PersistentDataType.STRING))) {
                part.remove();
            }
        }
        camera.anchor().remove();
        player.sendMessage(Component.text("Camera " + camera.name() + " removed.", NamedTextColor.AQUA));
        return true;
    }

    // -------------------------------------------------------------- lookups

    public List<Camera> cameras() {
        List<Camera> list = new ArrayList<>();
        for (World world : plugin.getServer().getWorlds()) {
            for (Marker marker : world.getEntitiesByClass(Marker.class)) {
                if (!marker.getScoreboardTags().contains(TAG_ANCHOR)) continue;
                var pdc = marker.getPersistentDataContainer();
                list.add(new Camera(marker,
                    pdc.getOrDefault(plugin.key("cam_name"), PersistentDataType.STRING, "CAM-??"),
                    pdc.getOrDefault(plugin.key("cam_redact"), PersistentDataType.INTEGER, 0),
                    pdc.getOrDefault(plugin.key("cam_pan"), PersistentDataType.BYTE, (byte) 0) != 0));
            }
        }
        list.sort(Comparator.comparing(Camera::name));
        return list;
    }

    public Camera nearest(Player player, double radius) {
        return cameras().stream()
            .filter(c -> c.anchor().getWorld().equals(player.getWorld()))
            .filter(c -> c.anchor().getLocation().distanceSquared(player.getLocation()) <= radius * radius)
            .min(Comparator.comparingDouble(c ->
                c.anchor().getLocation().distanceSquared(player.getLocation())))
            .orElse(null);
    }

    /** The eye stand of a camera - what viewers actually spectate. */
    public ArmorStand eyeOf(Camera camera) {
        String id = camera.anchor().getUniqueId().toString();
        for (Entity part : camera.anchor().getLocation().getNearbyEntities(2, 2, 2)) {
            if (part instanceof ArmorStand stand
                && part.getScoreboardTags().contains(TAG_EYE)
                && id.equals(part.getPersistentDataContainer()
                    .get(plugin.key("anchor"), PersistentDataType.STRING))) {
                return stand;
            }
        }
        return null;
    }

    public void setRedact(Camera camera, int level) {
        camera.anchor().getPersistentDataContainer().set(plugin.key("cam_redact"),
            PersistentDataType.INTEGER, Math.max(0, Math.min(5, level)));
    }

    public void togglePan(Camera camera) {
        var pdc = camera.anchor().getPersistentDataContainer();
        boolean now = pdc.getOrDefault(plugin.key("cam_pan"), PersistentDataType.BYTE, (byte) 0) == 0;
        pdc.set(plugin.key("cam_pan"), PersistentDataType.BYTE, (byte) (now ? 1 : 0));
    }

    // ------------------------------------------------------------- panning

    private int panClock = 0;

    /** Every 4 ticks: panning cameras sweep +-35 degrees around their base yaw. */
    public void panTick() {
        panClock += 4;
        for (Camera camera : cameras()) {
            if (!camera.pan()) continue;
            var pdc = camera.anchor().getPersistentDataContainer();
            Float baseYaw = pdc.get(plugin.key("cam_yaw"), PersistentDataType.FLOAT);
            if (baseYaw == null) continue;
            float yaw = (float) (baseYaw + 35 * Math.sin(panClock / 60.0));
            String id = camera.anchor().getUniqueId().toString();
            for (Entity part : camera.anchor().getLocation().getNearbyEntities(2, 2, 2)) {
                if (!id.equals(part.getPersistentDataContainer()
                        .get(plugin.key("anchor"), PersistentDataType.STRING))) continue;
                if (part instanceof ItemDisplay display) {
                    display.setRotation(yaw, 0);
                } else if (part instanceof ArmorStand eye) {
                    eye.setRotation(yaw, DOWN_TILT);
                }
            }
        }
    }

    // ------------------------------------------------------------- the item

    public ItemStack buildMonitor() {
        ItemStack item = new ItemStack(Material.RECOVERY_COMPASS);
        ItemMeta meta = item.getItemMeta();
        meta.itemName(Component.text("CCTV Monitor", NamedTextColor.AQUA)
            .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(Component.text("Use to jack into the camera grid.", NamedTextColor.GRAY)
            .decoration(TextDecoration.ITALIC, false),
            Component.text("Your body stays behind.", NamedTextColor.DARK_GRAY)
            .decoration(TextDecoration.ITALIC, false)));
        meta.setItemModel(new NamespacedKey("terminal", "cctv_monitor"));
        meta.setMaxStackSize(1);
        meta.getPersistentDataContainer().set(plugin.key("cctv_monitor"), PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public boolean isMonitor(ItemStack item) {
        return item != null && item.getType() == Material.RECOVERY_COMPASS && item.hasItemMeta()
            && item.getItemMeta().getPersistentDataContainer()
                .has(plugin.key("cctv_monitor"), PersistentDataType.BYTE);
    }

    private void error(Player player, String message) {
        player.sendMessage(Component.text(message, NamedTextColor.RED));
    }
}
