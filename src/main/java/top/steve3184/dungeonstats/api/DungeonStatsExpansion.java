package top.steve3184.dungeonstats.api;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import top.steve3184.dungeonstats.DungeonStats;
import top.steve3184.dungeonstats.model.PlayerStats;
import top.steve3184.dungeonstats.utils.DataManager;

import java.util.List;

/**
 * PlaceholderAPI expansion for DungeonStats
 *
 * Provided placeholders (identifier: dungeonstats):
 * - %dungeonstats_kills%
 * - %dungeonstats_playtime%
 * - %dungeonstats_maxlevel%
 *
 * Top placeholders (by metric: kills|playtime|maxlevel, index starts from 1):
 * - %dungeonstats_top_<metric>_<n>%           -> player name at rank n
 * - %dungeonstats_top_<metric>_<n>_name%      -> player name at rank n
 * - %dungeonstats_top_<metric>_<n>_value%     -> value at rank n
 */
public class DungeonStatsExpansion extends PlaceholderExpansion {

    private final DungeonStats plugin;
    private final DataManager dataManager;

    public DungeonStatsExpansion(DungeonStats plugin, DataManager dataManager) {
        this.plugin = plugin;
        this.dataManager = dataManager;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "dungeonstats";
    }

    @Override
    public @NotNull String getAuthor() {
        return "Steve3184";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true; // Keep registered across /papi reload
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
        // Player-centric placeholders
        switch (params.toLowerCase()) {
            case "kills": {
                PlayerStats stats = getStatsFor(player);
                return stats == null ? "0" : String.valueOf(stats.kills());
            }
            case "playtime": {
                PlayerStats stats = getStatsFor(player);
                return stats == null ? "0" : String.valueOf(stats.playtimeSeconds());
            }
            case "maxlevel": {
                PlayerStats stats = getStatsFor(player);
                return stats == null ? "0" : String.valueOf(stats.maxLevel());
            }
        }

        // Top placeholders: top_<metric>_<n>[_name|_value]
        if (params.toLowerCase().startsWith("top_")) {
            String[] parts = params.split("_");
            // Expect: [top, metric, n] or [top, metric, n, name|value]
            if (parts.length >= 3) {
                String metric = parts[1].toLowerCase();
                int index;
                try {
                    index = Integer.parseInt(parts[2]);
                } catch (NumberFormatException e) {
                    return "";
                }
                String field = parts.length >= 4 ? parts[3].toLowerCase() : "name";

                String key = switch (metric) {
                    case "kills" -> "kills";
                    case "playtime" -> "playtime";
                    case "maxlevel" -> "maxLevel";
                    default -> null;
                };
                if (key == null) return "";

                // getTopPlayers is 1-based for display, convert to 0-based index
                List<PlayerStats> top = dataManager.getTopPlayers(key, Math.max(index, 1));
                if (top.isEmpty() || index < 1 || index > top.size()) return "";
                PlayerStats ps = top.get(index - 1);

                if ("value".equals(field)) {
                    return switch (key) {
                        case "kills" -> String.valueOf(ps.kills());
                        case "playtime" -> String.valueOf(ps.playtimeSeconds());
                        case "maxLevel" -> String.valueOf(ps.maxLevel());
                        default -> "";
                    };
                } else { // default to name
                    return ps.playerName();
                }
            }
        }

        return null; // Unknown placeholder
    }

    private @Nullable PlayerStats getStatsFor(@Nullable OfflinePlayer player) {
        if (player == null) return null;
        String name = player.getName();
        if (name == null || name.isEmpty()) return null;
        return dataManager.getPlayerStats(name);
    }
}
