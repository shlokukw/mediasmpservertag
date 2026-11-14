package dev.shlok.mediasmpservertag;

import io.papermc.paper.event.player.AsyncChatEvent;
import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
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
        if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI"))
            getLogger().severe("PlaceholderAPI not found - shard balances can't be read, purchases will fail!");
        Bukkit.getPluginManager().registerEvents(this, this);
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) new TagExpansion(this).register();
        // players can have their own scoreboards (sidebar plugins), so keep every viewer's teams in sync
        Bukkit.getScheduler().runTaskTimer(this, this::syncAll, 40L, 100L);
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

    /** Puts the target's tag into one scoreboard (every viewer can have their own scoreboard). */
    private void applyTo(Scoreboard sb, Player target, TagDef t) {
        Team team = sb.getTeam(teamName(target));
        if (t == null) {
            if (team != null) team.unregister();
            return;
        }
        if (team == null) team = sb.registerNewTeam(teamName(target));
        Component tagComp = MM.deserialize(t.tag());
        boolean right = tagOnRight();
        Component prefix = right ? Component.empty() : tagComp.append(Component.space());
        Component suffix = right ? Component.space().append(tagComp) : Component.empty();
        if (!prefix.equals(team.prefix())) team.prefix(prefix);
        if (!suffix.equals(team.suffix())) team.suffix(suffix);
        if (!team.hasEntry(target.getName())) team.addEntry(target.getName());
    }

    private void applyNameTag(Player target) {
        TagDef t = find(data.selected(target.getUniqueId()));
        applyTo(Bukkit.getScoreboardManager().getMainScoreboard(), target, t);
        for (Player viewer : Bukkit.getOnlinePlayers()) applyTo(viewer.getScoreboard(), target, t);
    }

    private void removeNameTag(Player target) {
        Team main = Bukkit.getScoreboardManager().getMainScoreboard().getTeam(teamName(target));
        if (main != null) main.unregister();
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            Team team = viewer.getScoreboard().getTeam(teamName(target));
            if (team != null) team.unregister();
        }
    }

    private void syncAll() {
        for (Player p : Bukkit.getOnlinePlayers()) applyNameTag(p);
    }

    // ---------- PlaceholderAPI helpers (used by TagExpansion) ----------

    String tagLegacy(UUID id) {
        TagDef t = find(data.selected(id));
        if (t == null) return "";
        return LegacyComponentSerializer.builder().character('&').hexColors().build().serialize(MM.deserialize(t.tag()));
    }

    String tagPlain(UUID id) {
        TagDef t = find(data.selected(id));
        if (t == null) return "";
        return PlainTextComponentSerializer.plainText().serialize(MM.deserialize(t.tag()));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Bukkit.getScheduler().runTaskLater(this, this::syncAll, 20L);
        Bukkit.getScheduler().runTaskLater(this, this::syncAll, 100L);
    }

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

    // ---------- Shards (read/take through FrozenShards) ----------

    /** Raw text of the balance placeholder (for debugging). */
    private String rawBalance(Player p) {
        if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) return "(PlaceholderAPI not enabled)";
        return PlaceholderAPI.setPlaceholders(p, getConfig().getString("shards.balance-placeholder", "%frozenshards_value%"));
    }

    /** Turns text like "5000", "5,000.00", "5k" or "1.2M" into a number. Returns -1 if it can't be read. */
    private long parseShards(String raw) {
        String s = raw.replaceAll("(?i)[§&][0-9a-fk-or]", "").replace(",", "").trim().toLowerCase();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("([0-9]+(?:\\.[0-9]+)?)\\s*([kmb])?").matcher(s);
        if (!m.find()) return -1;
        double v = Double.parseDouble(m.group(1));
        String suf = m.group(2);
        if ("k".equals(suf)) v *= 1_000;
        else if ("m".equals(suf)) v *= 1_000_000;
        else if ("b".equals(suf)) v *= 1_000_000_000;
        return (long) v;
    }

    private long shardBalance(Player p) { return parseShards(rawBalance(p)); }

    /** Runs the take command from console and checks the balance really dropped. */
    private boolean takeShards(Player p, long amount) {
        long before = shardBalance(p);
        if (before < 0) return false;
        String cmd = getConfig().getString("shards.take-command", "shards take %player% %amount%")
                .replace("%player%", p.getName()).replace("%amount%", String.valueOf(amount));
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
        long after = shardBalance(p);
        if (after < 0 || after > before - amount) {
            getLogger().warning("Shard take check failed for " + p.getName() + ": before=" + before + " after=" + after + " cmd=" + cmd);
            return false;
        }
        return true;
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
            String raw = rawBalance(p);
            long bal = parseShards(raw);
            if (bal < 0) {
                getLogger().warning("Can't read shard balance for " + p.getName() + ", placeholder returned: \"" + raw + "\"");
                p.sendMessage(line("<red>Couldn't read your shards (got: " + raw.replace("<", "") + "). Tell an admin."));
                return;
            }
            if (bal < t.price()) {
                p.sendMessage(line("<red>You need <light_purple>" + (t.price() - bal) + " <red>more shards."));
                return;
            }
            if (!takeShards(p, t.price())) {
                p.sendMessage(line("<red>Couldn't take your shards, nothing was bought. Tell an admin."));
                return;
            }
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
        if (!(s instanceof Player p)) return true;
        if (a.length > 0 && a[0].equalsIgnoreCase("debug") && p.isOp()) {
            String raw = rawBalance(p);
            p.sendMessage(line("<gray>Placeholder returned: <white>\"" + raw.replace("<", "") + "\" <gray>-> read as <white>" + parseShards(raw)));
            return true;
        }
        if (a.length > 0 && a[0].equalsIgnoreCase("remove")) {
            data.setSelected(p.getUniqueId(), null);
            applyNameTag(p);
            p.sendMessage(line("<yellow>Tag removed."));
        } else open(p);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        if (a.length == 1) return List.of("remove", "debug");
        return null;
    }
}
