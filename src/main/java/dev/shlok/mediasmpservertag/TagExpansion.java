package dev.shlok.mediasmpservertag;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

/**
 * %mediasmpservertag_tag%        -> equipped tag with & color codes (empty if none)
 * %mediasmpservertag_tag_space%  -> same, plus a trailing space (empty if none)
 * %mediasmpservertag_tag_plain%  -> equipped tag without colors
 */
public class TagExpansion extends PlaceholderExpansion {
    private final MediaSMPServerTag plugin;

    TagExpansion(MediaSMPServerTag plugin) { this.plugin = plugin; }

    @Override public @NotNull String getIdentifier() { return "mediasmpservertag"; }
    @Override public @NotNull String getAuthor() { return "Shlok"; }
    @Override public @NotNull String getVersion() { return plugin.getDescription().getVersion(); }
    @Override public boolean persist() { return true; }

    @Override
    public String onRequest(OfflinePlayer p, @NotNull String params) {
        if (p == null) return "";
        String tag = plugin.tagLegacy(p.getUniqueId());
        return switch (params.toLowerCase()) {
            case "tag" -> tag;
            case "tag_space" -> tag.isEmpty() ? "" : tag + " ";
            case "tag_plain" -> plugin.tagPlain(p.getUniqueId());
            default -> null;
        };
    }
}
