package dev.shlok.mediasmpservertag;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Stores owned tags and the equipped tag. Shards come from FrozenShards (see MediaSMPServerTag). */
public class Data {
    private final File file;
    private final YamlConfiguration yml;

    public Data(File folder) {
        folder.mkdirs();
        file = new File(folder, "data.yml");
        yml = YamlConfiguration.loadConfiguration(file);
    }

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
