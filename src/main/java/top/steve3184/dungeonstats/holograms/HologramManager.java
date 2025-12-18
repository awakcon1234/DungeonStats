package top.steve3184.dungeonstats.holograms;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.bukkit.scheduler.BukkitTask;
import top.steve3184.dungeonstats.DungeonStats;
import top.steve3184.dungeonstats.model.PlayerStats;
import top.steve3184.dungeonstats.utils.DataManager;

import java.util.*;
import java.util.concurrent.TimeUnit;

public class HologramManager {

    private final DungeonStats plugin;
    private final DataManager dataManager;
    private final List<TextDisplay> activeHolograms = new ArrayList<>();
    private final List<String> leaderboardKeys = Arrays.asList("kills", "playtime", "maxLevel");
    private int currentRotationIndex = 0;

    private BukkitTask refreshTask;
    private BukkitTask rotationTask;

    public HologramManager(DungeonStats plugin, DataManager dataManager) {
        this.plugin = plugin;
        this.dataManager = dataManager;
    }

    public void initialize() {
        plugin.getLogger().info("HologramManager: initialize() called");

        boolean enabled = plugin.getConfig().getBoolean("holograms.enabled", false);
        plugin.getLogger().info("HologramManager: holograms.enabled=" + enabled);
        if (!enabled) {
            plugin.getLogger().info("HologramManager: Holograms are disabled in config; skipping initialization.");
            return;
        }

        // 先清理旧的全息图，以防插件重载
        plugin.getLogger().info("HologramManager: Cleaning up existing holograms before (re)initialization");
        cleanup();

        String mode = plugin.getConfig().getString("holograms.display-mode", "SINGLE").toUpperCase();
        plugin.getLogger().info("HologramManager: display-mode=" + mode);

        // Extra diagnostics for configuration layout
        boolean hasHologramsSection = plugin.getConfig().isConfigurationSection("holograms");
        boolean hasMultipleDisplays = plugin.getConfig().isConfigurationSection("holograms.multiple-displays");
        boolean hasSingleDisplay = plugin.getConfig().isConfigurationSection("holograms.single-display");
        plugin.getLogger().info("HologramManager: config sections -> holograms=" + hasHologramsSection
                + ", single-display=" + hasSingleDisplay + ", multiple-displays=" + hasMultipleDisplays);
        if (hasMultipleDisplays) {
            ConfigurationSection md = plugin.getConfig().getConfigurationSection("holograms.multiple-displays");
            logSectionDetails("holograms.multiple-displays", md, true);
        }

        if ("SINGLE".equals(mode)) {
            setupSingleMode();
        } else {
            setupMultipleMode();
        }

        long refreshIntervalSeconds = plugin.getConfig().getLong("holograms.refresh-interval-seconds", 10);
        plugin.getLogger().info("HologramManager: refresh-interval-seconds=" + refreshIntervalSeconds);
        long refreshInterval = refreshIntervalSeconds * 20L;
        this.refreshTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::updateAllHolograms, 0L, refreshInterval);
        plugin.getLogger().info("HologramManager: Scheduled refresh task with interval ticks=" + refreshInterval);
    }

    private void setupSingleMode() {
        Location loc = parseLocation(plugin.getConfig().getString("holograms.single-display.location"));
        if (loc == null) {
            plugin.getLogger().severe("Single hologram location is invalid! Check 'holograms.single-display.location' in config.");
            return;
        }
        plugin.getLogger().info("HologramManager: Creating single hologram at " + loc);
        TextDisplay hologram = createHologram(loc);
        activeHolograms.add(hologram);
        long rotationIntervalSeconds = plugin.getConfig().getLong("holograms.single-display.rotation-interval-seconds", 5);
        long rotationInterval = rotationIntervalSeconds * 20L;
        plugin.getLogger().info("HologramManager: Scheduling rotation task for single hologram, interval-seconds=" + rotationIntervalSeconds + ", ticks=" + rotationInterval);
        this.rotationTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::rotateSingleHologram, rotationInterval, rotationInterval * 2);
    }

    private void setupMultipleMode() {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("holograms.multiple-displays");
        if (section == null) {
            plugin.getLogger().warning("HologramManager: 'holograms.multiple-displays' section is NULL. Config contains?="
                    + plugin.getConfig().contains("holograms.multiple-displays")
                    + ", isSection?=" + plugin.getConfig().isConfigurationSection("holograms.multiple-displays"));
            // Dump top-level holograms keys for context
            ConfigurationSection holo = plugin.getConfig().getConfigurationSection("holograms");
            if (holo != null) logSectionDetails("holograms", holo, true);
            return;
        }
        plugin.getLogger().info("HologramManager: Setting up multiple holograms for leaderboard keys: " + leaderboardKeys);
        for (String key : leaderboardKeys) {
            String keyPath = key + ".location";
            boolean containsInSection = section.contains(keyPath);
            String absolutePath = "holograms.multiple-displays." + keyPath;
            boolean containsAbsolute = plugin.getConfig().contains(absolutePath);
            Object raw = plugin.getConfig().get(absolutePath);
            String type = (raw == null ? "null" : raw.getClass().getSimpleName());
            String locStr = section.getString(keyPath);
            plugin.getLogger().info("HologramManager: Lookup paths -> section.contains('" + keyPath + "')=" + containsInSection
                    + ", config.contains('" + absolutePath + "')=" + containsAbsolute + ", rawType=" + type);
            plugin.getLogger().info("HologramManager: multiple-displays." + keyPath + " = " + locStr);
            if (!containsInSection) {
                plugin.getLogger().warning("HologramManager: Expected key missing under 'holograms.multiple-displays': '" + key
                        + "'. Available keys here: " + section.getKeys(false));
            }
            Location loc = parseLocation(locStr);
            if (loc != null) {
                plugin.getLogger().info("HologramManager: Creating hologram for key='" + key + "' at " + loc);
                TextDisplay hologram = createHologram(loc);
                hologram.setMetadata("leaderboard_key", new org.bukkit.metadata.FixedMetadataValue(plugin, key));
                activeHolograms.add(hologram);
            } else {
                plugin.getLogger().warning("HologramManager: Could not parse location for key='" + key + "' (value=" + locStr + ")");
                // Suggest the closest matching keys from config to aid debugging
                suggestClosestKeys(section, key);
            }
        }
    }

    private void rotateSingleHologram() {
        if (activeHolograms.isEmpty()) return;
        // use index 0 for the single hologram
        TextDisplay hologram = activeHolograms.get(0);
        String key = leaderboardKeys.get(currentRotationIndex);
        plugin.getLogger().info("HologramManager: rotateSingleHologram: rotating to index=" + currentRotationIndex + " key=" + key);
        updateHologramContent(hologram, key);

        currentRotationIndex = (currentRotationIndex + 1) % leaderboardKeys.size();
    }

    private void updateAllHolograms() {
        if (activeHolograms.isEmpty()) {
            plugin.getLogger().fine("HologramManager: updateAllHolograms called but no active holograms present");
            return;
        }

        String mode = plugin.getConfig().getString("holograms.display-mode", "SINGLE").toUpperCase();
        plugin.getLogger().info("HologramManager: updateAllHolograms called, mode=" + mode + ", activeCount=" + activeHolograms.size());

        if ("SINGLE".equals(mode)) {
            // 在单模式下，刷新任务会触发一次轮换，以确保数据最新
            plugin.getLogger().fine("HologramManager: SINGLE mode - rotating single hologram during refresh");
            rotateSingleHologram();
        } else {
            for (TextDisplay hologram : activeHolograms) {
                if (hologram.hasMetadata("leaderboard_key")) {
                    String key = hologram.getMetadata("leaderboard_key").get(0).asString();
                    plugin.getLogger().fine("HologramManager: Updating hologram for key=" + key + " at entityId=" + hologram.getEntityId());
                    updateHologramContent(hologram, key);
                } else {
                    plugin.getLogger().warning("HologramManager: Found hologram without 'leaderboard_key' metadata, entityId=" + hologram.getEntityId());
                }
            }
        }
    }

    private void updateHologramContent(TextDisplay hologram, String key) {
        plugin.getLogger().fine("HologramManager: updateHologramContent called for key='" + key + "'");
        List<PlayerStats> topPlayers = dataManager.getTopPlayers(key, 10);
        plugin.getLogger().info("HologramManager: Found topPlayers.size()=" + topPlayers.size() + " for key='" + key + "'");
        String title = plugin.getConfig().getString("messages.title-" + key.toLowerCase(), "Leaderboard");
        StringBuilder contentBuilder = new StringBuilder();
        contentBuilder.append(format(title + "\n\n&r"));

        for (int i = 0; i < 10; i++) {
            if (i < topPlayers.size()) {
                PlayerStats stats = topPlayers.get(i);
                contentBuilder.append(formatRankEntry(i + 1, stats, key));
            } else {
                contentBuilder.append("\n"); // 补充空行
            }
        }

        hologram.setText(contentBuilder.toString());
        plugin.getLogger().fine("HologramManager: Hologram text updated for key='" + key + "'");
    }

    private String formatRankEntry(int rank, PlayerStats stats, String key) {
        String rankColor;
        switch (rank) {
            case 1 -> rankColor = plugin.getConfig().getString("messages.rank-color-1", "&6");
            case 2 -> rankColor = plugin.getConfig().getString("messages.rank-color-2", "&7");
            case 3 -> rankColor = plugin.getConfig().getString("messages.rank-color-3", "&c");
            default -> rankColor = plugin.getConfig().getString("messages.rank-color-default", "&7");
        }

        String valueStr;
        if (key.equals("playtime")) {
            valueStr = formatSeconds(stats.playtimeSeconds());
        } else {
            valueStr = String.valueOf(switch(key) {
                case "kills" -> stats.kills();
                case "maxLevel" -> stats.maxLevel();
                default -> 0;
            });
        }

        String template = plugin.getConfig().getString("messages.rank-entry", "#{rank} &b{player_name}: &f{value}");
        return format(rankColor + template
                .replace("{rank}", String.valueOf(rank))
                .replace("{player_name}", stats.playerName())
                .replace("{value}", valueStr)) + "\n";
    }

    private TextDisplay createHologram(Location location) {
        if (location == null) {
            plugin.getLogger().severe("HologramManager: createHologram called with null location");
            throw new IllegalArgumentException("location cannot be null");
        }
        World world = location.getWorld();
        if (world == null) {
            plugin.getLogger().severe("HologramManager: createHologram: world not found for location=" + location);
            throw new IllegalArgumentException("World not found for location");
        }

        plugin.getLogger().info("HologramManager: Spawning TextDisplay at " + location + " in world=" + world.getName());
        return world.spawn(location, TextDisplay.class, holo -> {
            holo.setBillboard(Display.Billboard.VERTICAL);
            holo.setText("");
            holo.setAlignment(TextDisplay.TextAlignment.CENTER);
            holo.setPersistent(false); // 不保存到区块数据中
        });
    }

    public void cleanup() {
        // 取消任务
        if (refreshTask != null) refreshTask.cancel();
        if (rotationTask != null) rotationTask.cancel();
        plugin.getLogger().info("HologramManager: cleanup() - cancelling tasks and removing " + activeHolograms.size() + " holograms");

        // 移除实体
        activeHolograms.forEach(hologram -> {
            try {
                if (hologram.isValid()) {
                    plugin.getLogger().fine("HologramManager: Removing hologram entityId=" + hologram.getEntityId());
                    hologram.remove();
                }
            } catch (Exception e) {
                plugin.getLogger().warning("HologramManager: Exception while removing hologram: " + e.getMessage());
            }
        });
        activeHolograms.clear();
    }

    private Location parseLocation(String locString) {
        if (locString == null || locString.isEmpty()) return null;
        try {
            String[] parts = locString.split(",");
            if (parts.length < 4) {
                plugin.getLogger().warning("HologramManager: parseLocation - invalid location string (not enough parts): " + locString);
                return null;
            }
            World world = Bukkit.getWorld(parts[0]);
            double x = Double.parseDouble(parts[1]);
            double y = Double.parseDouble(parts[2]);
            double z = Double.parseDouble(parts[3]);
            plugin.getLogger().fine("HologramManager: parseLocation parts -> world='" + parts[0] + "', x=" + parts[1] + ", y=" + parts[2] + ", z=" + parts[3]);
            if (world == null) {
                plugin.getLogger().warning("HologramManager: parseLocation - world not found: " + parts[0] + " for location string: " + locString);
                return null;
            }
            return new Location(world, x, y, z);
        } catch (Exception e) {
            plugin.getLogger().warning("HologramManager: Failed to parse location string '" + locString + "' : " + e.getMessage());
            return null;
        }
    }

    /**
     * Dump keys and immediate values of a configuration section to help diagnose path issues.
     */
    private void logSectionDetails(String path, ConfigurationSection section, boolean includeChildren) {
        try {
            Set<String> keys = section.getKeys(includeChildren);
            plugin.getLogger().info("HologramManager: Section dump for '" + path + "' (includeChildren=" + includeChildren + ") keys=" + keys);
            // Print known location nodes if present
            for (String k : keys) {
                if (k.endsWith("location") || k.equals("location")) {
                    String absolute = (section.getCurrentPath() == null || section.getCurrentPath().isEmpty())
                            ? k : section.getCurrentPath() + "." + k;
                    Object val = plugin.getConfig().get(absolute);
                    plugin.getLogger().info("HologramManager:   node '" + absolute + "' => '" + val + "'");
                }
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("HologramManager: Failed to dump section '" + path + "': " + ex.getMessage());
        }
    }

    /**
     * Suggest closest keys in a section to the expected leaderboard key to catch typos/mismatches.
     */
    private void suggestClosestKeys(ConfigurationSection section, String expectedKey) {
        try {
            Set<String> available = section.getKeys(false);
            String suggestion = available.stream()
                    .min(Comparator.comparingInt(a -> levenshtein(a.toLowerCase(Locale.ROOT), expectedKey.toLowerCase(Locale.ROOT))))
                    .orElse(null);
            if (suggestion != null) {
                plugin.getLogger().warning("HologramManager: Did you mean '" + suggestion + "'? (expected '" + expectedKey + "')");
            }
        } catch (Exception ignored) {
        }
    }

    // Simple Levenshtein distance for debugging suggestions only (small strings, infrequent)
    private int levenshtein(String a, String b) {
        int[] costs = new int[b.length() + 1];
        for (int j = 0; j < costs.length; j++) costs[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            costs[0] = i;
            int nw = i - 1;
            for (int j = 1; j <= b.length(); j++) {
                int cj = Math.min(1 + Math.min(costs[j], costs[j - 1]), a.charAt(i - 1) == b.charAt(j - 1) ? nw : nw + 1);
                nw = costs[j];
                costs[j] = cj;
            }
        }
        return costs[b.length()];
    }

    private String format(String message) {
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    private String formatSeconds(long totalSeconds) {
        long hours = TimeUnit.SECONDS.toHours(totalSeconds);
        long minutes = TimeUnit.SECONDS.toMinutes(totalSeconds) % 60;
        long seconds = totalSeconds % 60;
        return String.format("%02d:%02d:%02d", hours, minutes, seconds);
    }
}