package fi.alavesa.terminal;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The CCTV body double's skin. Three tiers, best first:
 *
 *   1. FancyNpcs (github.com/FancyMcPlugins/FancyNpcs) - a real player-model
 *      NPC with the viewer's skin. FancyNpcs maintains the version-specific
 *      NMS layer (incl. MC 26.x), so this is the robust path. Reached by
 *      reflection, so FancyNpcs stays a pure soft-dependency.
 *   2. Replicas (our own packet NPC) - same idea, our own NMS.
 *   3. neither present -> caller keeps its armor-stand double.
 *
 * spawn() returns a handle string ("fn:<name>" or "rp:<uuid>"), or null for
 * the armor-stand fallback; remove() decodes it.
 */
final class NpcBridge {

    private static final boolean FANCY;
    private static final boolean REPLICAS;

    private static Object fnPlugin;
    private static java.lang.reflect.Method fnAdapter, fnManager, fnRegister, fnRemoveNpc,
        fnGetNpc, fnCreate, fnSpawnAll, fnRemoveAll, fnSetSkin, fnSetName, fnSetTab, fnSetTurn,
        fnSetEquip, fnSetSkinData;
    private static java.lang.reflect.Constructor<?> fnSkinCtor;
    private static Object fnSkinAuto;
    private static java.lang.reflect.Constructor<?> fnDataCtor;
    private static java.lang.reflect.Method fnGetAll, fnGetData, fnDataName;
    private static Class<?> fnSlotEnum;
    // handle -> the actual Npc object, so removal never depends on a name
    // lookup that can miss (the 'copy stayed after leaving' bug)
    private static final Map<String, Object> live = new java.util.concurrent.ConcurrentHashMap<>();

    static {
        boolean fancy = false;
        try {
            if (Bukkit.getPluginManager().getPlugin("FancyNpcs") != null) {
                Class<?> pluginClass = Class.forName("de.oliver.fancynpcs.api.FancyNpcsPlugin");
                fnPlugin = pluginClass.getMethod("get").invoke(null);
                fnAdapter = pluginClass.getMethod("getNpcAdapter");
                fnManager = pluginClass.getMethod("getNpcManager");
                Class<?> dataClass = Class.forName("de.oliver.fancynpcs.api.NpcData");
                fnDataCtor = dataClass.getConstructor(String.class, UUID.class, Location.class);
                fnSetSkin = dataClass.getMethod("setSkin", String.class);
                fnSetName = dataClass.getMethod("setDisplayName", String.class);
                Class<?> skinClass = Class.forName("de.oliver.fancynpcs.api.skins.SkinData");
                Class<?> variantClass = Class.forName("de.oliver.fancynpcs.api.skins.SkinData$SkinVariant");
                fnSkinCtor = skinClass.getConstructor(String.class, variantClass, String.class, String.class);
                fnSkinAuto = Enum.valueOf((Class<Enum>) variantClass, "AUTO");
                fnSetSkinData = dataClass.getMethod("setSkinData", skinClass);
                fnSetTab = dataClass.getMethod("setShowInTab", boolean.class);
                fnSetTurn = dataClass.getMethod("setTurnToPlayer", boolean.class);
                fnSlotEnum = Class.forName("de.oliver.fancynpcs.api.utils.NpcEquipmentSlot");
                fnSetEquip = dataClass.getMethod("setEquipment", Map.class);
                Class<?> npcClass = Class.forName("de.oliver.fancynpcs.api.Npc");
                fnCreate = npcClass.getMethod("create");
                fnSpawnAll = npcClass.getMethod("spawnForAll");
                fnRemoveAll = npcClass.getMethod("removeForAll");
                Class<?> mgrClass = Class.forName("de.oliver.fancynpcs.api.NpcManager");
                fnRegister = mgrClass.getMethod("registerNpc", npcClass);
                fnRemoveNpc = mgrClass.getMethod("removeNpc", npcClass);
                fnGetNpc = mgrClass.getMethod("getNpc", String.class);
                fnGetAll = mgrClass.getMethod("getAllNpcs");
                fnGetData = npcClass.getMethod("getData");
                fnDataName = dataClass.getMethod("getName");
                fancy = true;
                Bukkit.getLogger().info("[Terminal] FancyNpcs found - CCTV doubles are real player models.");
            }
        } catch (Throwable t) {
            fancy = false;
            Bukkit.getLogger().warning("[Terminal] FancyNpcs present but its API didn't bind: " + t);
        }
        FANCY = fancy;

        boolean replicas = false;
        try {
            Class.forName("fi.alavesa.replicas.ReplicasPlugin");
            replicas = Bukkit.getPluginManager().getPlugin("Replicas") != null
                && fi.alavesa.replicas.ReplicasPlugin.available();
        } catch (Throwable t) {
            replicas = false;
        }
        REPLICAS = replicas;
    }

    private NpcBridge() { }

    /** True if EITHER backend can give a real player-model double. */
    static boolean available() {
        return FANCY || REPLICAS;
    }

    @SuppressWarnings("unchecked")
    static String spawn(Player source, Location at, Map<EquipmentSlot, ItemStack> gear) {
        if (FANCY) {
            try {
                String name = "cctv_" + source.getName() + "_" + System.nanoTime();
                Object data = fnDataCtor.newInstance(name, source.getUniqueId(), at);
                // read the CURRENT profile textures - this reflects a live
                // SCP-914 disguise, not just the account's real skin
                String[] tex = currentSkin(source);
                if (tex != null) {
                    Object skin = fnSkinCtor.newInstance(source.getName(), fnSkinAuto, tex[0], tex[1]);
                    fnSetSkinData.invoke(data, skin);
                } else {
                    fnSetSkin.invoke(data, source.getName()); // no textures - by name
                }
                fnSetName.invoke(data, source.getName());  // their name floats above it
                fnSetTab.invoke(data, false);
                fnSetTurn.invoke(data, false);
                if (gear != null && !gear.isEmpty()) {
                    Map<Object, ItemStack> mapped = new HashMap<>();
                    for (var e : gear.entrySet()) {
                        if (e.getValue() == null || e.getValue().getType().isAir()) continue;
                        mapped.put(fnSlot(e.getKey()), e.getValue());
                    }
                    fnSetEquip.invoke(data, mapped);
                }
                Object adapter = fnAdapter.invoke(fnPlugin);
                Object npc = ((java.util.function.Function<Object, Object>) adapter).apply(data);
                fnCreate.invoke(npc);
                fnRegister.invoke(fnManager.invoke(fnPlugin), npc);
                fnSpawnAll.invoke(npc);
                live.put(name, npc);
                return "fn:" + name;
            } catch (Throwable t) {
                Bukkit.getLogger().warning("[Terminal] FancyNpcs double failed, falling back: " + t);
            }
        }
        if (REPLICAS) {
            UUID id = fi.alavesa.replicas.ReplicasPlugin.spawn(source, at, gear);
            if (id != null) return "rp:" + id;
        }
        return null;
    }

    static void remove(String handle) {
        if (handle == null) return;
        try {
            if (handle.startsWith("fn:") && FANCY) {
                String name = handle.substring(3);
                Object manager = fnManager.invoke(fnPlugin);
                Object npc = live.remove(name);
                if (npc == null) npc = fnGetNpc.invoke(manager, name); // fallback lookup
                if (npc != null) {
                    fnRemoveAll.invoke(npc);
                    fnRemoveNpc.invoke(manager, npc);
                }
            } else if (handle.startsWith("rp:") && REPLICAS) {
                fi.alavesa.replicas.ReplicasPlugin.remove(UUID.fromString(handle.substring(3)));
            }
        } catch (Throwable t) {
            Bukkit.getLogger().warning("[Terminal] NPC double removal failed: " + t);
        }
    }

    /** Remove every leftover CCTV double (names start with cctv_) - run at
     *  startup so a crash mid-session never leaves a permanent copy, and as
     *  a safety net. Only cctv_ NPCs are touched; real NPCs are untouched. */
    static void purgeOrphans() {
        if (!FANCY) return;
        try {
            Object manager = fnManager.invoke(fnPlugin);
            var all = (java.util.Collection<?>) fnGetAll.invoke(manager);
            for (Object npc : new java.util.ArrayList<>(all)) {
                Object data = fnGetData.invoke(npc);
                Object name = data == null ? null : fnDataName.invoke(data);
                if (name instanceof String s && s.startsWith("cctv_")) {
                    fnRemoveAll.invoke(npc);
                    fnRemoveNpc.invoke(manager, npc);
                    live.remove(s);
                }
            }
        } catch (Throwable t) {
            Bukkit.getLogger().warning("[Terminal] CCTV orphan purge failed: " + t);
        }
    }

    /** value+signature of the player's current skin, or null if the profile
     *  has no textures property (default Steve/Alex). Picks up 914 disguises,
     *  which set the profile textures. */
    private static String[] currentSkin(Player player) {
        for (var prop : player.getPlayerProfile().getProperties()) {
            if (prop.getName().equals("textures")) {
                return new String[]{prop.getValue(), prop.getSignature()};
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Object fnSlot(EquipmentSlot slot) {
        String name = switch (slot) {
            case HEAD -> "HELMET";
            case CHEST -> "CHEST";
            case LEGS -> "LEGGINGS";
            case FEET -> "BOOTS";
            case OFF_HAND -> "OFFHAND";
            default -> "MAINHAND";
        };
        try {
            return Enum.valueOf((Class<Enum>) fnSlotEnum, name);
        } catch (IllegalArgumentException e) {
            return Enum.valueOf((Class<Enum>) fnSlotEnum, "MAINHAND");
        }
    }
}
