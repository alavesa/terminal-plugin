package fi.alavesa.terminal;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Personal folders: private drafts owned by ONE player, kept apart from the
 * public entry database until their author RELEASES them. Persists to
 * personal.yml keyed by player UUID; each personal document has the same shape
 * as an {@link EntryStore.Entry} (id, title, author, clearance, created,
 * pages) so a release is a straight hand-off into {@link EntryStore#add}.
 *
 * personal.yml layout:
 *   players:
 *     &lt;uuid&gt;:
 *       next-id: 4
 *       docs:
 *         1: { title, author, clearance, created, pages: [ ... ] }
 *         2: { ... }
 */
public final class PersonalStore {

    private final Plugin plugin;
    private final EntryStore store;
    private File file;
    private YamlConfiguration yaml;

    public PersonalStore(Plugin plugin, EntryStore store) {
        this.plugin = plugin;
        this.store = store;
        load();
    }

    public void load() {
        plugin.getDataFolder().mkdirs();
        file = new File(plugin.getDataFolder(), "personal.yml");
        yaml = YamlConfiguration.loadConfiguration(file);
    }

    private void save() {
        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save personal.yml: " + e.getMessage());
        }
    }

    private String base(UUID owner) {
        return "players." + owner + ".";
    }

    /** Every personal document owned by this player, newest first. */
    public List<EntryStore.Entry> list(UUID owner) {
        List<EntryStore.Entry> docs = new ArrayList<>();
        ConfigurationSection root = yaml.getConfigurationSection(base(owner) + "docs");
        if (root != null) {
            for (String key : root.getKeys(false)) {
                ConfigurationSection s = root.getConfigurationSection(key);
                if (s == null) continue;
                docs.add(new EntryStore.Entry(Integer.parseInt(key), s.getString("title", "Untitled"),
                    s.getString("author", "?"), s.getInt("clearance", 0),
                    s.getLong("created", 0), s.getStringList("pages")));
            }
        }
        docs.sort(Comparator.comparingInt(EntryStore.Entry::id).reversed()); // newest first
        return docs;
    }

    public EntryStore.Entry get(UUID owner, int id) {
        return list(owner).stream().filter(e -> e.id() == id).findFirst().orElse(null);
    }

    public EntryStore.Entry add(UUID owner, String title, String author, int clearance, List<String> pages) {
        int id = yaml.getInt(base(owner) + "next-id", 1);
        yaml.set(base(owner) + "next-id", id + 1);
        String p = base(owner) + "docs." + id + ".";
        yaml.set(p + "title", title);
        yaml.set(p + "author", author);
        yaml.set(p + "clearance", clearance);
        yaml.set(p + "created", System.currentTimeMillis());
        yaml.set(p + "pages", pages);
        save();
        return get(owner, id);
    }

    public boolean delete(UUID owner, int id) {
        if (yaml.getConfigurationSection(base(owner) + "docs." + id) == null) return false;
        yaml.set(base(owner) + "docs." + id, null);
        save();
        return true;
    }

    /**
     * MOVES a personal document into the public database: files it via
     * {@link EntryStore#add} (keeping its title/author/clearance/pages) and
     * removes it from the personal store. Returns the freshly-filed public
     * entry, or null if the document did not exist.
     */
    public EntryStore.Entry release(UUID owner, int id) {
        EntryStore.Entry doc = get(owner, id);
        if (doc == null) return null;
        EntryStore.Entry filed = store.add(doc.title(), doc.author(), doc.clearance(), doc.pages());
        delete(owner, id);
        return filed;
    }
}
