package ru.moscow.foxkiss.placeholders;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.moscow.foxkiss.auction.AuctionCurrency;
import ru.moscow.foxkiss.auction.AuctionRepository;
import ru.moscow.foxkiss.economy.VaultChatApi;
import ru.moscow.foxkiss.economy.VaultPermissionApi;
import ru.moscow.foxkiss.scheduler.SchedulerService;
import ru.moscow.foxkiss.utils.PriceFormatter;

import java.util.*;

public final class FMAuctionExpansion extends PlaceholderExpansion {

    private final JavaPlugin plugin;
    private final AuctionRepository repository;
    private final SchedulerService scheduler;

    private final VaultChatApi vaultChat = new VaultChatApi();
    private final VaultPermissionApi vaultPerm = new VaultPermissionApi();

    private volatile List<TopPlayerInfo> topByMoney = List.of();
    private volatile List<TopPlayerInfo> topBySold = List.of();
    private volatile boolean disabled;

    private final Object2ObjectOpenHashMap<UUID, CachedPlayerStats> statsCache = new Object2ObjectOpenHashMap<>();
    private final ObjectOpenHashSet<UUID> loadingStats = new ObjectOpenHashSet<>();
    private final Object2ObjectOpenHashMap<String, String> prefixCache = new Object2ObjectOpenHashMap<>();

    public FMAuctionExpansion(JavaPlugin plugin, AuctionRepository repository, SchedulerService scheduler) {
        this.plugin = plugin;
        this.repository = repository;
        this.scheduler = scheduler;
        new BukkitRunnable() {
            @Override
            public void run() {
                updateCache();
            }
        }.runTaskTimerAsynchronously(plugin, 20L, 6000L);
    }

    @Override
    @NotNull
    public String getIdentifier() {
        return "fmauction";
    }

    @Override
    @NotNull
    public String getAuthor() {
        return "FrostMine";
    }

    @Override
    @NotNull
    public String getVersion() {
        return "1.0";
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    @Nullable
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        String[] parts = params.toLowerCase(Locale.ROOT).split("_");

        if (parts.length == 3 && "my".equals(parts[0]) && "top".equals(parts[1])) {
            if (player == null) return "0";
            return getMyTop(player, parts[2]);
        }

        if (parts.length == 4 && "top".equals(parts[0])) {
            return getTop(parts[1], parts[2], parts[3]);
        }

        return null;
    }

    private String getMyTop(OfflinePlayer player, String field) {
        String name = player.getName();
        if (name == null) return "0";

        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();

        synchronized (statsCache) {
            CachedPlayerStats cached = statsCache.get(uuid);
            if (cached != null && !cached.isExpired(now, 60000)) {
                return "sold".equals(field) ? String.valueOf(cached.sold()) : formatMoney(cached.money());
            }
        }

        synchronized (loadingStats) {
            if (!disabled && !loadingStats.contains(uuid)) {
                loadingStats.add(uuid);
                scheduler.runAsync(() -> loadStatsAsync(uuid, name));
            }
        }

        return "0";
    }

    private void loadStatsAsync(UUID uuid, String name) {
        try {
            AuctionRepository.PlayerStats stats = repository.getPlayerStats(name, AuctionCurrency.VAULT);
            synchronized (statsCache) {
                if (!disabled) {
                    statsCache.put(uuid, new CachedPlayerStats(stats.soldCount(), stats.totalEarned(), System.currentTimeMillis()));
                }
            }
        } finally {
            synchronized (loadingStats) {
                loadingStats.remove(uuid);
            }
        }
    }

    private String getTop(String mode, String posStr, String field) {
        List<TopPlayerInfo> top;
        if ("money".equals(mode)) {
            top = topByMoney;
        } else if ("sold".equals(mode)) {
            top = topBySold;
        } else {
            return null;
        }

        int pos;
        try {
            pos = Integer.parseInt(posStr);
        } catch (NumberFormatException e) {
            return null;
        }
        if (pos < 1 || pos > 5) return null;

        if (pos > top.size()) {
            if ("nick".equals(field) || "prefix".equals(field)) return "&7[&c-&7]";
            if ("sold".equals(field) || "money".equals(field)) return "0";
            return null;
        }

        TopPlayerInfo info = top.get(pos - 1);
        if ("nick".equals(field)) return info.name();
        if ("sold".equals(field)) return String.valueOf(info.soldCount());
        if ("money".equals(field)) return formatMoney(info.money());
        if ("prefix".equals(field)) return info.prefix();
        return null;
    }

    public void disable() {
        disabled = true;
        synchronized (statsCache) { statsCache.clear(); }
        synchronized (prefixCache) { prefixCache.clear(); }
        topByMoney = List.of();
        topBySold = List.of();
        synchronized (loadingStats) { loadingStats.clear(); }
    }

    private void updateCache() {
        if (disabled) return;

        long now = System.currentTimeMillis();
        synchronized (statsCache) {
            Iterator<CachedPlayerStats> it = statsCache.values().iterator();
            while (it.hasNext()) {
                if (it.next().isExpired(now, 3_600_000)) {
                    it.remove();
                }
            }
        }

        scheduler.runAsync(() -> {
            // Codex start - Независимые топы по выручке и продажам
            List<AuctionRepository.TopSeller> byMoney = repository.getTopSellersByRevenue(AuctionCurrency.VAULT, 5);
            List<AuctionRepository.TopSeller> bySold = repository.getTopSellers(AuctionCurrency.VAULT, 5);
            // Codex end

            Set<String> names = new LinkedHashSet<>();
            for (AuctionRepository.TopSeller seller : byMoney) {
                names.add(seller.name());
            }
            for (AuctionRepository.TopSeller seller : bySold) {
                names.add(seller.name());
            }

            scheduler.runSync(() -> prepareTop(names, byMoney, bySold));
        });
    }

    private record PrefixTarget(OfflinePlayer player, World world) {}

    private void prepareTop(Set<String> names, List<AuctionRepository.TopSeller> byMoney, List<AuctionRepository.TopSeller> bySold) {
        if (disabled) return;

        Map<String, PrefixTarget> targets = new HashMap<>();
        Map<String, String> prefixes = new HashMap<>();
        Map<String, String> needed = new HashMap<>();

        for (String name : names) {
            String prefix = prefixCache.isEmpty() ? null : prefixCache.get(name);
            if (prefix != null) {
                prefixes.put(name, prefix);
                continue;
            }
            Player online = Bukkit.getPlayerExact(name);
            if (online == null) {
                needed.put(name.toLowerCase(Locale.ROOT), name);
            } else {
                targets.put(name, new PrefixTarget(online, online.getWorld()));
            }
        }

        if (!needed.isEmpty()) {
            for (OfflinePlayer offline : Bukkit.getOfflinePlayers()) {
                String name = offline.getName();
                if (name == null) continue;
                String sellerName = needed.remove(name.toLowerCase(Locale.ROOT));
                if (sellerName != null) {
                    targets.put(sellerName, new PrefixTarget(offline, null));
                    if (needed.isEmpty()) break;
                }
            }
        }

        scheduler.runAsync(() -> resolveTop(byMoney, bySold, targets, prefixes));
    }

    private void resolveTop(List<AuctionRepository.TopSeller> byMoney, List<AuctionRepository.TopSeller> bySold, Map<String, PrefixTarget> targets, Map<String, String> prefixes) {
        if (disabled) return;

        for (Map.Entry<String, PrefixTarget> entry : targets.entrySet()) {
            String name = entry.getKey();
            if (prefixes.containsKey(name)) continue;
            try {
                prefixes.put(name, resolvePrefix(entry.getValue()));
            } catch (RuntimeException e) {
                plugin.getLogger().warning("Не удалось получить префикс продавца " + name + ": " + e.getMessage());
            }
        }

        List<TopPlayerInfo> moneyTop = toTop(byMoney, prefixes);
        List<TopPlayerInfo> soldTop = toTop(bySold, prefixes);

        scheduler.runSync(() -> {
            if (disabled) return;
            prefixCache.putAll(prefixes);
            topByMoney = moneyTop;
            topBySold = soldTop;
        });
    }

    private List<TopPlayerInfo> toTop(List<AuctionRepository.TopSeller> sellers, Map<String, String> prefixes) {
        List<TopPlayerInfo> result = new ArrayList<>(sellers.size());
        for (AuctionRepository.TopSeller seller : sellers) {
            String name = seller.name();
            String prefix = prefixes.getOrDefault(name, "");
            result.add(new TopPlayerInfo(name, seller.soldCount(), seller.totalEarned(), prefix));
        }
        return List.copyOf(result);
    }

    private String resolvePrefix(PrefixTarget target) {
        OfflinePlayer player = target.player();
        String group = player instanceof Player online
                ? vaultPerm.getPrimaryGroup(online)
                : vaultPerm.getPrimaryGroup(player);
        return Objects.toString(vaultChat.getGroupPrefix(target.world(), group), "");
    }
    private String formatMoney(double amount) {
        return PriceFormatter.format(amount);
    }
}