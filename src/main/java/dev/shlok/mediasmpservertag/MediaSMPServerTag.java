package dev.shlok.mediasmpservertag;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class MediaSMPServerTag extends JavaPlugin implements Listener {

    static final MiniMessage MM = MiniMessage.miniMessage();
    static final int CLOSE_SLOT = 49;
    static final int PREV_SLOT = 48;
    static final int NEXT_SLOT = 50;
    static final int[] SLOTS = new int[28];

    static {
        int i = 0;
        for (int r = 1; r <= 4; r++) for (int c = 1; c <= 7; c++) SLOTS[i++] = r * 9 + c;
    }

    record TagDef(String id, String display, String tag, long price) {}

    static class Holder implements InventoryHolder {
        Inventory inv;
        int page = 0;
        @Override public Inventory getInventory() { return inv; }
    }

    private final List<TagDef> tags = new ArrayList<>();
    private Data data;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        data = new Data(getDataFolder());
        loadTags();
        Bukkit.getPluginManager().registerEvents(this, this);
        for (Player p : Bukkit.getOnlinePlayers()) applyNameTag(p);
    }

    @Override
    public void onDisable() {
        for (Player p : Bukkit.getOnlinePlayers()) removeNameTag(p);
    }

    // ---------- Name tag + tab list (scoreboard team prefix/suffix) ----------

    private String teamName(Player p) {
        return "mst_" + p.getUniqueId().toString().replace("-", "").substring(0, 12);
    }

    private boolean tagOnRight() {
        return !"left".equalsIgnoreCase(getConfig().getString("tag-position", "right"));
    }

    private void applyNameTag(Player p) {
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        Team team = sb.getTeam(teamName(p));
        TagDef t = find(data.selected(p.getUniqueId()));
        if (t == null) {
            if (team != null) team.unregister();
            return;
        }
        if (team == null) team = sb.registerNewTeam(teamName(p));
        Component tagComp = MM.deserialize(t.tag());
        if (tagOnRight()) {
            team.prefix(Component.empty());
            team.suffix(Component.space().append(tagComp));
        } else {
            team.prefix(tagComp.append(Component.space()));
            team.suffix(Component.empty());
        }
        if (!team.hasEntry(p.getName())) team.addEntry(p.getName());
    }

    private void removeNameTag(Player p) {
        Team team = Bukkit.getScoreboardManager().getMainScoreboard().getTeam(teamName(p));
        if (team != null) team.unregister();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) { applyNameTag(e.getPlayer()); }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { removeNameTag(e.getPlayer()); }

    private void loadTags() {
        tags.clear();
        ConfigurationSection sec = getConfig().getConfigurationSection("tags");
        if (sec == null) return;
        for (String id : sec.getKeys(false)) {
            ConfigurationSection t = sec.getConfigurationSection(id);
            if (t == null) continue;
            tags.add(new TagDef(id, t.getString("display", id), t.getString("tag", "[" + id + "]"), t.getLong("price", 5000)));
        }
    }

    private static Component line(String mm) {
        return MM.deserialize(mm).decoration(TextDecoration.ITALIC, false);
    }

    private TagDef find(String id) {
        for (TagDef t : tags) if (t.id().equals(id)) return t;
        return null;
    }

    // ---------- GUI ----------

    private void open(Player p) {
        Holder h = new Holder();
        h.inv = Bukkit.createInventory(h, 54, MM.deserialize(getConfig().getString("title", "SHARDSHOP - TAGS")));
        fill(p, h.inv);
        p.openInventory(h.inv);
    }

    private void fill(Player p, Inventory inv) {
        inv.clear();
        UUID id = p.getUniqueId();
        Set<String> owned = data.owned(id);
        String sel = data.selected(id);

        Holder h = (Holder) inv.getHolder();
        int pages = Math.max(1, (int) Math.ceil(tags.size() / (double) SLOTS.length));
        if (h.page >= pages) h.page = pages - 1;
        for (int i = 0; i < SLOTS.length && h.page * SLOTS.length + i < tags.size(); i++) {
            TagDef t = tags.get(h.page * SLOTS.length + i);
            ItemStack it = new ItemStack(Material.NAME_TAG);
            ItemMeta m = it.getItemMeta();
            m.displayName(line(t.display()));
            List<Component> lore = new ArrayList<>();
            lore.add(line("<gray>PLAYER TAG"));
            lore.add(Component.empty());
            lore.add(line("<white>Tag: " + t.tag()));
            lore.add(Component.empty());
            if (t.id().equals(sel)) {
                lore.add(line("<green>Equipped <gray>(click to unequip)"));
                m.setEnchantmentGlintOverride(true);
            } else if (owned.contains(t.id())) {
                lore.add(line("<yellow>Owned <gray>(click to equip)"));
            } else {
                lore.add(line("<white>Buy Price: <light_purple>x" + t.price() + " shards"));
            }
            m.lore(lore);
            it.setItemMeta(m);
            inv.setItem(SLOTS[i], it);
        }

        ItemStack close = new ItemStack(Material.RED_STAINED_GLASS_PANE);
        ItemMeta cm = close.getItemMeta();
        cm.displayName(line("<red><bold>Close"));
        close.setItemMeta(cm);
        inv.setItem(CLOSE_SLOT, close);
        if (h.page > 0) inv.setItem(PREV_SLOT, arrow("<yellow><bold>Previous Page", h.page, pages));
        if (h.page < pages - 1) inv.setItem(NEXT_SLOT, arrow("<yellow><bold>Next Page", h.page + 2, pages));
    }

    private ItemStack arrow(String name, int target, int pages) {
        ItemStack it = new ItemStack(Material.ARROW);
        ItemMeta m = it.getItemMeta();
        m.displayName(line(name));
        m.lore(List.of(line("<gray>Page " + target + "/" + pages)));
        it.setItemMeta(m);
        return it;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof Holder)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() != top) return;

        Player p = (Player) e.getWhoClicked();
        int slot = e.getSlot();
        if (slot == CLOSE_SLOT) { p.closeInventory(); return; }

        Holder h = (Holder) top.getHolder();
        if (slot == PREV_SLOT && h.page > 0) { h.page--; fill(p, top); return; }
        if (slot == NEXT_SLOT && (h.page + 1) * SLOTS.length < tags.size()) { h.page++; fill(p, top); return; }

        int idx = -1;
        for (int i = 0; i < SLOTS.length; i++) if (SLOTS[i] == slot) idx = h.page * SLOTS.length + i;
        if (idx < 0 || idx >= tags.size()) return;

        TagDef t = tags.get(idx);
        UUID id = p.getUniqueId();

        if (data.owned(id).contains(t.id())) {
            if (t.id().equals(data.selected(id))) {
                data.setSelected(id, null);
                p.sendMessage(line("<yellow>Tag unequipped."));
            } else {
                data.setSelected(id, t.id());
                p.sendMessage(line("<green>Equipped tag ").append(MM.deserialize(t.tag())));
            }
        } else {
            long bal = data.balance(id);
            if (bal < t.price()) {
                p.sendMessage(line("<red>You need <light_purple>" + (t.price() - bal) + " <red>more shards."));
                return;
            }
            data.setBalance(id, bal - t.price());
            data.addOwned(id, t.id());
            data.setSelected(id, t.id());
            p.sendMessage(line("<green>Bought and equipped ").append(MM.deserialize(t.tag())));
        }
        applyNameTag(p);
        fill(p, top);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Holder) e.setCancelled(true);
    }

    // ---------- Chat ----------

    @EventHandler
    public void onChat(AsyncChatEvent e) {
        TagDef t = find(data.selected(e.getPlayer().getUniqueId()));
        if (t == null) return;
        Component tag = MM.deserialize(t.tag());
        boolean right = tagOnRight();
        e.renderer((source, name, msg, viewer) -> right
                ? Component.empty().append(name).append(Component.space()).append(tag)
                        .append(Component.text(": ")).append(msg)
                : Component.empty().append(tag).append(Component.space()).append(name)
                        .append(Component.text(": ")).append(msg));
    }

    // ---------- Commands ----------

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (cmd.getName().equalsIgnoreCase("tags")) {
            if (!(s instanceof Player p)) return true;
            if (a.length > 0 && a[0].equalsIgnoreCase("remove")) {
                data.setSelected(p.getUniqueId(), null);
                applyNameTag(p);
                p.sendMessage(line("<yellow>Tag removed."));
            } else open(p);
            return true;
        }

        // /shards
        if (a.length == 0) {
            if (s instanceof Player p) p.sendMessage(line("<light_purple>Shards: " + data.balance(p.getUniqueId())));
            return true;
        }
        if (a[0].equalsIgnoreCase("give") && a.length == 3 && s.hasPermission("mediasmpservertag.admin")) {
            Player target = Bukkit.getPlayer(a[1]);
            if (target == null) { s.sendMessage(line("<red>Player not found.")); return true; }
            try {
                long amt = Long.parseLong(a[2]);
                data.setBalance(target.getUniqueId(), data.balance(target.getUniqueId()) + amt);
                s.sendMessage(line("<green>Gave " + amt + " shards to " + target.getName()));
            } catch (NumberFormatException ex) {
                s.sendMessage(line("<red>Invalid amount."));
            }
            return true;
        }
        s.sendMessage(line("<gray>/shards  |  /shards give <player> <amount>"));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        if (c.getName().equalsIgnoreCase("tags") && a.length == 1) return List.of("remove");
        if (c.getName().equalsIgnoreCase("shards") && a.length == 1 && s.hasPermission("mediasmpservertag.admin")) return List.of("give");
        return null;
    }
}
