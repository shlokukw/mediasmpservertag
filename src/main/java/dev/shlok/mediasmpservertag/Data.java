package dev.shlok.mediasmpservertag;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Stores shard balances, owned tags and equipped tag. Swap balance methods if you already have a shard plugin. */
public class Data {
    private final File file;
    private final YamlConfiguration yml;

    public Data(File folder) {
        folder.mkdirs();
        file = new File(folder, "data.yml");
        yml = YamlConfiguration.loadConfiguration(file);
    }

    public long balance(UUID id) { return yml.getLong("p." + id + ".shards", 0); }

    public void setBalance(UUID id, long v) { yml.set("p." + id + ".shards", Math.max(0, v)); save(); }

    public Set<String> owned(UUID id) { return new HashSet<>(yml.getStringList("p." + id + ".owned")); }

    public void addOwned(UUID id, String tag) {
        List<String> l = yml.getStringList("p." + id + ".owned");
        if (!l.contains(tag)) l.add(tag);
        yml.set("p." + id + ".owned", l);
        save();
    }

    public String selected(UUID id) { return yml.getString("p." + id + ".selected"); }

    public void setSelected(UUID id, String tag) { yml.set("p." + id + ".selected", tag); save(); }

    private void save() {
        try { yml.save(file); } catch (IOException ex) { ex.printStackTrace(); }
    }
}
