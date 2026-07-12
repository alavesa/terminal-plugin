package fi.alavesa.terminal;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The SCiPNET entry database: entries.yml. Every entry carries a clearance
 * level; terminals only show what the reader's LuckPerms "clearance" meta
 * unlocks. Entries are written in-game (signed books) and curated in-game
 * (/terminal admin) - the file is the storage, not the interface.
 */
public final class EntryStore {

    public record Entry(int id, String title, String author, int clearance,
                        long created, List<String> pages) { }

    private final Plugin plugin;
    private File file;
    private YamlConfiguration yaml;

    public EntryStore(Plugin plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        plugin.getDataFolder().mkdirs();
        file = new File(plugin.getDataFolder(), "entries.yml");
        yaml = YamlConfiguration.loadConfiguration(file);
    }

    private void save() {
        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save entries.yml: " + e.getMessage());
        }
    }

    /** Minimum clearance required to file new entries (adjustable in-game). */
    public int writeClearance() {
        return yaml.getInt("write-clearance", 0);
    }

    public void setWriteClearance(int level) {
        yaml.set("write-clearance", Math.max(0, Math.min(5, level)));
        save();
    }

    public List<Entry> all() {
        List<Entry> entries = new ArrayList<>();
        ConfigurationSection root = yaml.getConfigurationSection("entries");
        if (root != null) {
            for (String key : root.getKeys(false)) {
                ConfigurationSection s = root.getConfigurationSection(key);
                if (s == null) continue;
                entries.add(new Entry(Integer.parseInt(key), s.getString("title", "Untitled"),
                    s.getString("author", "?"), s.getInt("clearance", 0),
                    s.getLong("created", 0), s.getStringList("pages")));
            }
        }
        entries.sort(Comparator.comparingInt(Entry::id).reversed()); // newest first
        return entries;
    }

    public Entry get(int id) {
        return all().stream().filter(e -> e.id() == id).findFirst().orElse(null);
    }

    public Entry add(String title, String author, int clearance, List<String> pages) {
        int id = yaml.getInt("next-id", 1);
        yaml.set("next-id", id + 1);
        String base = "entries." + id + ".";
        yaml.set(base + "title", title);
        yaml.set(base + "author", author);
        yaml.set(base + "clearance", clearance);
        yaml.set(base + "created", System.currentTimeMillis());
        yaml.set(base + "pages", pages);
        save();
        return get(id);
    }

    public boolean delete(int id) {
        if (yaml.getConfigurationSection("entries." + id) == null) return false;
        yaml.set("entries." + id, null);
        save();
        return true;
    }

    public boolean setClearance(int id, int clearance) {
        if (yaml.getConfigurationSection("entries." + id) == null) return false;
        yaml.set("entries." + id + ".clearance", Math.max(0, Math.min(5, clearance)));
        save();
        return true;
    }
}
