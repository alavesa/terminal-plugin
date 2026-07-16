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
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    public static final String TAG_HEAD = "terminal.cctv.head";
    private static final float DOWN_TILT = 18f;

    /** One camera, resolved live from its anchor. */
    public record Camera(Marker anchor, String name, int redact, boolean pan) { }

    private final TerminalPlugin plugin;

    public CctvManager(TerminalPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------ placement

    /** Mounts a camera on the WALL or CEILING face the player is looking
     *  at - floors are refused (nobody bolts a camera to the floor). */
    public boolean place(Player player) {
        var ray = player.rayTraceBlocks(6.0);
        if (ray == null || ray.getHitBlock() == null || ray.getHitBlockFace() == null) {
            error(player, "Look at a wall or ceiling within 6 blocks.");
            return false;
        }
        Block wall = ray.getHitBlock();
        BlockFace facing = ray.getHitBlockFace();
        if (facing == BlockFace.UP) {
            error(player, "Walls and ceilings only - not floors.");
            return false;
        }
        boolean ceiling = facing == BlockFace.DOWN;
        if (!ceiling && Math.abs(facing.getModX()) + Math.abs(facing.getModZ()) != 1) {
            error(player, "Walls and ceilings only.");
            return false;
        }
        // ceiling rigs look back toward whoever mounted them; wall rigs
        // look straight out of the wall
        float yaw = ceiling
            ? Math.round(player.getLocation().getYaw() / 90f) * 90f + 180f
            : switch (facing) { // model faces SOUTH at yaw 0
                case NORTH -> 180f;
                case EAST -> -90f;
                case WEST -> 90f;
                default -> 0f;
            };
        String name = nextName();

        Location base = wall.getRelative(facing).getLocation()
            .add(0.5, ceiling ? 0.0 : 0.2, 0.5);
        base.setYaw(yaw);
        base.setPitch(0); // displays inherit pitch - never let them tilt
        float eyeTilt = ceiling ? 38f : DOWN_TILT;

        Marker anchor = base.getWorld().spawn(base, Marker.class, m -> {
            m.setPersistent(true);
            m.addScoreboardTag(TAG_ANCHOR);
            m.getPersistentDataContainer().set(plugin.key("cam_name"), PersistentDataType.STRING, name);
            m.getPersistentDataContainer().set(plugin.key("cam_redact"), PersistentDataType.INTEGER, 0);
            m.getPersistentDataContainer().set(plugin.key("cam_pan"), PersistentDataType.BYTE, (byte) 0);
            m.getPersistentDataContainer().set(plugin.key("cam_yaw"), PersistentDataType.FLOAT, yaw);
            m.getPersistentDataContainer().set(plugin.key("cam_tilt"), PersistentDataType.FLOAT, eyeTilt);
        });
        // the parts (bracket + head ItemDisplays, eye ArmorStand) all hang
        // off the anchor at computed base positions; per-camera offsets in the
        // anchor PDC nudge each one - respawnParts re-reads them live
        Camera camera = new Camera(anchor, name, 0, false);
        spawnParts(camera, base, ceiling);
        base.getWorld().playSound(base, Sound.BLOCK_IRON_TRAPDOOR_CLOSE, 0.6f, 1.7f);
        player.sendMessage(Component.text("Camera " + name + " mounted (facing "
            + facing.getOppositeFace().name().toLowerCase() + "). /terminal cctv redact <0-5> to classify it.",
            NamedTextColor.AQUA));
        return true;
    }

    /** The three part names editable via /terminal cctv offset & scale. */
    public static final List<String> PARTS = List.of("bracket", "head", "eye");

    /** Where each part sits before its offset is applied - derived from the
     *  anchor's stored base yaw/tilt so respawnParts matches place() exactly. */
    private Location basePosition(Marker anchor, String part) {
        var pdc = anchor.getPersistentDataContainer();
        Location base = anchor.getLocation().clone();
        float yaw = pdc.getOrDefault(plugin.key("cam_yaw"), PersistentDataType.FLOAT, base.getYaw());
        float tilt = pdc.getOrDefault(plugin.key("cam_tilt"), PersistentDataType.FLOAT, DOWN_TILT);
        boolean ceiling = tilt > 30f; // ceiling rigs are stored with the steeper tilt
        base.setYaw(yaw);
        base.setPitch(0);
        return switch (part) {
            case "head" -> ceiling ? base.clone().subtract(0, 0.35, 0) : base;
            case "eye" -> {
                Location eyeAt = base.clone().add(base.getDirection().multiply(0.55))
                    .add(0, ceiling ? 0.15 : 0.5, 0);
                eyeAt.setYaw(yaw);
                eyeAt.setPitch(tilt);
                yield eyeAt;
            }
            default -> base; // bracket
        };
    }

    /** Reads "x,y,z" from the anchor PDC (off_<part>), 0,0,0 if unset. */
    private Vector3f offsetOf(Marker anchor, String part) {
        String raw = anchor.getPersistentDataContainer()
            .get(plugin.key("off_" + part), PersistentDataType.STRING);
        if (raw == null) return new Vector3f();
        String[] p = raw.split(",");
        try {
            return new Vector3f(Float.parseFloat(p[0]), Float.parseFloat(p[1]), Float.parseFloat(p[2]));
        } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
            return new Vector3f();
        }
    }

    private float scaleOf(Marker anchor, String part) {
        return anchor.getPersistentDataContainer()
            .getOrDefault(plugin.key("scale_" + part), PersistentDataType.FLOAT, 1f);
    }

    /** Spawns bracket + head ItemDisplays and the eye ArmorStand at their base
     *  positions plus stored offsets. Used by place() and respawnParts(). */
    private void spawnParts(Camera camera, Location base, boolean ceiling) {
        Marker anchor = camera.anchor();
        Vector3f ob = offsetOf(anchor, "bracket"), oh = offsetOf(anchor, "head"), oe = offsetOf(anchor, "eye");
        Location bracketAt = base.clone().add(ob.x, ob.y, ob.z);
        Location headBase = ceiling ? base.clone().subtract(0, 0.35, 0) : base.clone();
        Location headAt = headBase.add(oh.x, oh.y, oh.z);
        spawnDisplay(anchor, bracketAt, ceiling ? "cctv_bracket_ceiling" : "cctv_bracket_wall",
            false, scaleOf(anchor, "bracket"));
        spawnDisplay(anchor, headAt, "cctv_head", true, scaleOf(anchor, "head"));
        Location eyeAt = basePosition(anchor, "eye").add(oe.x, oe.y, oe.z);
        eyeAt.getWorld().spawn(eyeAt, ArmorStand.class, eye -> {
            eye.setPersistent(true);
            eye.setInvisible(true);
            eye.setMarker(true);
            eye.setSmall(true);
            eye.setGravity(false);
            eye.addScoreboardTag(TAG_PART);
            eye.addScoreboardTag(TAG_EYE);
            link(eye, anchor);
        });
    }

    private void spawnDisplay(Marker anchor, Location at, String model, boolean head, float scale) {
        at.getWorld().spawn(at, ItemDisplay.class, d -> {
            d.setPersistent(true);
            d.setBrightness(new Display.Brightness(15, 15)); // the REC light never sleeps
            ItemStack item = new ItemStack(Material.PAPER);
            ItemMeta meta = item.getItemMeta();
            meta.setItemModel(new NamespacedKey("terminal", model));
            item.setItemMeta(meta);
            d.setItemStack(item);
            if (scale != 1f) {
                d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(),
                    new Vector3f(scale, scale, scale), new AxisAngle4f()));
            }
            d.addScoreboardTag(TAG_PART);
            if (head) d.addScoreboardTag(TAG_HEAD);
            link(d, anchor);
        });
    }

    /** Re-reads the stored offsets/scales and re-spawns the bracket, head and
     *  eye at base+offset, so an /terminal cctv offset edit takes effect live
     *  without re-placing the whole camera. Panning resumes from panTick. */
    public void respawnParts(Camera camera) {
        Marker anchor = camera.anchor();
        String id = anchor.getUniqueId().toString();
        // clear the old parts (everything linked to this anchor but the anchor)
        for (Entity part : anchor.getLocation().getNearbyEntities(3, 3, 3)) {
            if (part.equals(anchor)) continue;
            if (id.equals(part.getPersistentDataContainer()
                    .get(plugin.key("anchor"), PersistentDataType.STRING))) {
                part.remove();
            }
        }
        var pdc = anchor.getPersistentDataContainer();
        float tilt = pdc.getOrDefault(plugin.key("cam_tilt"), PersistentDataType.FLOAT, DOWN_TILT);
        boolean ceiling = tilt > 30f;
        Location base = basePosition(anchor, "bracket");
        spawnParts(camera, base, ceiling);
    }

    /** Nudges one part's offset by (dx,dy,dz), persists it in the anchor PDC,
     *  and respawns the parts so the edit is visible immediately. */
    public void nudgeOffset(Camera camera, String part, float dx, float dy, float dz) {
        Vector3f cur = offsetOf(camera.anchor(), part);
        cur.add(dx, dy, dz);
        camera.anchor().getPersistentDataContainer().set(plugin.key("off_" + part),
            PersistentDataType.STRING, cur.x + "," + cur.y + "," + cur.z);
        respawnParts(camera);
    }

    public Vector3f offset(Camera camera, String part) {
        return offsetOf(camera.anchor(), part);
    }

    /** Sets a bracket/head display scale factor, persisted and applied live. */
    public void setScale(Camera camera, String part, float factor) {
        camera.anchor().getPersistentDataContainer().set(plugin.key("scale_" + part),
            PersistentDataType.FLOAT, Math.max(0.05f, Math.min(8f, factor)));
        respawnParts(camera);
    }

    private void link(Entity part, Marker anchor) {
        part.getPersistentDataContainer().set(plugin.key("anchor"),
            PersistentDataType.STRING, anchor.getUniqueId().toString());
    }

    private static final Pattern CAM_NAME = Pattern.compile("CAM-(\\d{1,6})");

    /** FIRST-FREE numbering: delete CAM-02 and the next camera IS CAM-02.
     *  The grid never shows gaps that a growing counter would leave. */
    private String nextName() {
        Set<Integer> used = new HashSet<>();
        for (Camera camera : cameras()) {
            Matcher m = CAM_NAME.matcher(camera.name());
            if (m.matches()) used.add(Integer.parseInt(m.group(1)));
        }
        int number = 1;
        while (used.contains(number)) number++;
        return String.format("CAM-%02d", number);
    }

    /** Takes down a camera whole: anchor plus every linked part. */
    public void remove(Camera camera) {
        String id = camera.anchor().getUniqueId().toString();
        for (Entity part : camera.anchor().getLocation().getNearbyEntities(2, 2, 2)) {
            if (id.equals(part.getPersistentDataContainer()
                    .get(plugin.key("anchor"), PersistentDataType.STRING))) {
                part.remove();
            }
        }
        camera.anchor().remove();
    }

    public boolean removeNearest(Player player) {
        Camera camera = nearest(player, 5);
        if (camera == null) return false;
        remove(camera);
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
            float tilt = pdc.getOrDefault(plugin.key("cam_tilt"), PersistentDataType.FLOAT, DOWN_TILT);
            String id = camera.anchor().getUniqueId().toString();
            for (Entity part : camera.anchor().getLocation().getNearbyEntities(2, 2, 2)) {
                if (!id.equals(part.getPersistentDataContainer()
                        .get(plugin.key("anchor"), PersistentDataType.STRING))) continue;
                if (part instanceof ItemDisplay display
                    && part.getScoreboardTags().contains(TAG_HEAD)) {
                    display.setRotation(yaw, 0); // the bracket never moves
                } else if (part instanceof ArmorStand eye) {
                    eye.setRotation(yaw, tilt);
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
