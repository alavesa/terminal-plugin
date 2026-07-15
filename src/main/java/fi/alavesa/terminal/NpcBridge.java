package fi.alavesa.terminal;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.UUID;

/**
 * Soft bridge to the Replicas plugin (packet-level fake players). When it is
 * present and its NMS layer probed fine, the CCTV body double is a REAL
 * player model with the viewer's actual skin; without it, the armor-stand
 * double carries on exactly as before. Checked once at class-init.
 */
final class NpcBridge {

    private static final boolean UP;

    static {
        boolean up;
        try {
            Class.forName("fi.alavesa.replicas.ReplicasPlugin");
            up = org.bukkit.Bukkit.getPluginManager().getPlugin("Replicas") != null
                && fi.alavesa.replicas.ReplicasPlugin.available();
        } catch (Throwable t) {
            up = false;
        }
        UP = up;
    }

    private NpcBridge() { }

    static boolean available() { return UP; }

    static UUID spawn(Player source, Location at, Map<EquipmentSlot, ItemStack> gear) {
        return UP ? fi.alavesa.replicas.ReplicasPlugin.spawn(source, at, gear) : null;
    }

    static void remove(UUID id) {
        if (UP && id != null) fi.alavesa.replicas.ReplicasPlugin.remove(id);
    }
}
