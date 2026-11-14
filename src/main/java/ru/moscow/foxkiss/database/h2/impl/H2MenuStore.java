package ru.moscow.foxkiss.database.h2.impl;

import ru.moscow.foxkiss.database.h2.SqlRuntime;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import ru.moscow.foxkiss.gui.AuctionViewType;
import ru.moscow.foxkiss.utils.ItemUtils;
import ru.moscow.foxkiss.utils.TextUtils;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

import ru.moscow.foxkiss.auction.*;
import ru.moscow.foxkiss.auction.AuctionRepository.*;
import static ru.moscow.foxkiss.database.h2.impl.H2ItemCodec.*;

public final class H2MenuStore {
    private static final int active = 0;
    private final JavaPlugin plugin;
    private final DataSource dataSource;
    private final SqlRuntime sqlRuntime;
    private final boolean separatePageCount;
    private volatile Map<String, List<String>> categoryMaterials;

    public H2MenuStore(JavaPlugin plugin, DataSource dataSource, SqlRuntime sqlRuntime, boolean separatePageCount) {
        this.plugin = plugin;
        this.dataSource = dataSource;
        this.sqlRuntime = sqlRuntime;
        this.separatePageCount = separatePageCount;
        this.categoryMaterials = readCategoryMaterials();
    }

    private final Map<PageKey, CachedPage> pages = new HashMap<>();
    private final Map<PageKey, CompletableFuture<MenuData>> pageFlights = new HashMap<>();
    private final Map<PageKey, CachedRows> rowPages = new HashMap<>();
    private final Map<PageKey, CompletableFuture<MenuQueryResult>> rowFlights = new HashMap<>();
    private record CachedRows(MenuQueryResult data, long expiresAt) {}
    private record PageKey(AuctionCurrency currency, String player, int days, int page, int size, AuctionSort sort, String category, String seller, String search, AuctionViewType view, long revision, Object translations) {}
    private record CachedPage(MenuData data, long expiresAt) {}

    public void clearReadCache() {
        categoryMaterials = readCategoryMaterials();
        synchronized (pages) {
            sqlRuntime.invalidateCache();
            pages.clear(); rowPages.clear();
        }
    }

    public MenuData loadMenuData(AuctionCurrency currency, String playerName, int maxDays, int page, int pageSize, AuctionSort sort, String category, String sellerFilter, String searchFilter, AuctionViewType viewType) {
        long revision = sqlRuntime.cacheRevision();
        PageKey key = new PageKey(currency, playerName, maxDays, page, pageSize, sort, category, sellerFilter, searchFilter, viewType, revision < 0 ? -System.nanoTime() : revision, ItemUtils.getSearchIndex());
        CompletableFuture<MenuData> flight;
        boolean owner;
        synchronized (pages) {
            CachedPage cached = pages.get(key);
            if (cached != null && cached.expiresAt > System.currentTimeMillis() && revision == sqlRuntime.cacheRevision()) return copyMenuData(cached.data);
            flight = pageFlights.get(key);
            owner = flight == null;
            if (owner) {
                flight = new CompletableFuture<>();
                pageFlights.put(key, flight);
            }
        }
        if (!owner) return copyMenuData(flight.join());
        try {
            LoadedMenu loaded = loadMenuDataUncached(currency, playerName, maxDays, page, pageSize, sort, category, sellerFilter, searchFilter, viewType);
            MenuData data = loaded.data;
            long expires = loaded.expiresAt;
            synchronized (pages) {
                if (revision >= 0 && revision == sqlRuntime.cacheRevision() && expires > System.currentTimeMillis()) {
                    if (pages.size() >= 128) {
                        Iterator<PageKey> iterator = pages.keySet().iterator();
                        iterator.next(); iterator.remove();
                    }
                    pages.put(key, new CachedPage(data, expires));
                }
            }
            flight.complete(data);
            return copyMenuData(data);
        } catch (Throwable e) {
            flight.completeExceptionally(e);
            throw e;
        } finally {
            synchronized (pages) { pageFlights.remove(key, flight); }
        }
    }

    private long pageExpiry(AuctionCurrency currency, int maxDays, long cutoff) {
        long now = System.currentTimeMillis();
        if (maxDays <= 0) return now;
        long storage = maxDays * 86_400_000L;
        try (Connection conn = open(); PreparedStatement ps = conn.prepareStatement("SELECT MIN(created_at) FROM auction_items WHERE currency=? AND status=0 AND created_at>=?")) {
            ps.setString(1, currency.name()); ps.setLong(2, cutoff);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next(); long created = rs.getLong(1);
                return rs.wasNull() ? Long.MAX_VALUE : created + storage;
            }
        } catch (SQLException e) { return now; }
    }

    private static MenuData copyMenuData(MenuData source) {
        List<AuctionItem> items = new ArrayList<>(source.items().size());
        for (AuctionItem item : source.items()) {
            List<ItemStack> contents = new ArrayList<>();
            if (item.inventoryContents() != null) {
                for (ItemStack content : item.inventoryContents()) contents.add(content == null ? null : content.clone());
            }
            items.add(new AuctionItem(
                    item.id(),
                    item.sellerName(),
                    item.currency(),
                    item.itemStack().clone(),
                    item.price(),
                    item.createdAt(),
                    item.status(),
                    item.type(),
                    Collections.unmodifiableList(contents),
                    item.operationId(),
                    item.processingStartedAt
                            ()));
        }
        return new MenuData(List.copyOf(items), source.totalCount(), source.sellingCount(), source.expiredCount(), Map.copyOf(source.profiles()), source.materialLots(), source.itemGroups());
    }

    private MenuQueryResult sharedRows(AuctionCurrency currency, String player, int days, int page, int size, AuctionSort sort, String category, String seller, String search, AuctionViewType view, long cutoff) {
        long revision = sqlRuntime.cacheRevision();
        PageKey key = new PageKey(currency, view == AuctionViewType.MAIN || view == AuctionViewType.OFFERS ? "" : player, days, page, size, sort, category, seller, search, view, revision < 0 ? -System.nanoTime() : revision, ItemUtils.getSearchIndex());
        CompletableFuture<MenuQueryResult> flight;
        boolean owner;
        synchronized (pages) {
            CachedRows cached = rowPages.get(key);
            if (cached != null && cached.expiresAt > System.currentTimeMillis() && revision == sqlRuntime.cacheRevision()) return cached.data;
            flight = rowFlights.get(key);
            owner = flight == null;
            if (owner) { flight = new CompletableFuture<>(); rowFlights.put(key, flight); }
        }
        if (!owner) return flight.join();
        try {
            MenuQueryResult result;
            try (Connection conn = open()) {
                conn.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                conn.setAutoCommit(false);
                result = findPageAndCount(conn, currency, page, size, sort, category, seller, search, view, cutoff, player);
                conn.commit();
            }
            long expiry = pageExpiry(currency, days, cutoff);
            result = new MenuQueryResult(result.items(), result.totalCount(), expiry, result.materialLots(), result.itemGroups());
            synchronized (pages) {
                if (revision >= 0 && revision == sqlRuntime.cacheRevision() && expiry > System.currentTimeMillis()) {
                    if (rowPages.size() >= 128) { Iterator<PageKey> keys = rowPages.keySet().iterator(); keys.next(); keys.remove(); }
                    rowPages.put(key, new CachedRows(result, expiry));
                }
            }
            flight.complete(result);
            return result;
        } catch (Throwable e) {
            flight.completeExceptionally(e);
            if (e instanceof RuntimeException failure) throw failure;
            if (e instanceof Error failure) throw failure;
            throw new IllegalStateException("Не удалось загрузить лоты аукциона", e);
        } finally { synchronized (pages) { rowFlights.remove(key, flight); } }
    }

    private record LoadedMenu(MenuData data, long expiresAt) {}

    private LoadedMenu loadMenuDataUncached(AuctionCurrency currency, String playerName, int maxDays, int page, int pageSize, AuctionSort sort, String category, String sellerFilter, String searchFilter, AuctionViewType viewType) {
        long cutoff = System.currentTimeMillis() - maxDays * 86_400_000L;
        MenuQueryResult result = sharedRows(currency, playerName, maxDays, page, pageSize, sort, category, sellerFilter, searchFilter, viewType, cutoff);
        try (Connection conn = open()) {
            conn.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            conn.setAutoCommit(false);
            List<AuctionItem> items = result.items();
            int totalCount = result.totalCount();

            int sellingCount = 0, expiredCount = 0;
            if (viewType == AuctionViewType.MAIN || viewType == AuctionViewType.OFFERS) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT COUNT(CASE WHEN status=? THEN 1 END) as selling, " + "COUNT(CASE WHEN status=? AND created_at<? THEN 1 END) as expired " + "FROM auction_items WHERE currency=? AND seller_name=?")) {
                    ps.setInt(1, active);
                    ps.setInt(2, active);
                    ps.setLong(3, cutoff);
                    ps.setString(4, currency.name());
                    ps.setString(5, playerName);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            sellingCount = rs.getInt("selling");
                            expiredCount = rs.getInt("expired");
                        }
                    }
                }
            }
            MenuData data = new MenuData(items, totalCount, sellingCount, expiredCount, loadProfiles(conn, items, playerName), result.materialLots(), result.itemGroups());
            conn.commit();
            return new LoadedMenu(data, result.validUntil());
        } catch (SQLException e) {
            throw new IllegalStateException("Не удалось загрузить страницу аукциона", e);
        }
    }


    public PlayerProfile getPlayerProfile(String playerName) {
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement("SELECT COALESCE(p.discord,'' ) AS discord,COALESCE(p.store_name,'' ) AS store_name,(SELECT COUNT(*) FROM sales_history WHERE seller_name=?) AS sales FROM (VALUES(1)) x LEFT JOIN auction_player_profiles p ON p.player_name=?")) {
            ps.setString(1, playerName);
            ps.setString(2, playerName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new PlayerProfile(playerName, rs.getString("discord"), rs.getString("store_name"), rs.getInt("sales"));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Не удалось загрузить профиль игрока " + playerName, e);
        }
        return PlayerProfile.empty(playerName);
    }

    public void savePlayerProfile(String playerName, String discord, String storeName) {
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement("MERGE INTO auction_player_profiles(player_name,discord,store_name,discord_plain,store_name_plain) KEY(player_name) VALUES(?,?,?,?,?)")) {
            ps.setString(1, playerName);
            ps.setString(2, discord);
            ps.setString(3, storeName);
            ps.setString(4, TextUtils.colorize(discord));
            ps.setString(5, TextUtils.colorize(storeName));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Не удалось сохранить профиль игрока " + playerName, e);
        }
    }

    public Optional<String> findProfileOwner(String value) {
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement("SELECT player_name FROM auction_player_profiles WHERE LOWER(discord_plain)=? OR LOWER(store_name_plain)=? LIMIT 1")) {
            String plain = TextUtils.colorize(value).toLowerCase();
            ps.setString(1, plain);
            ps.setString(2, plain);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(rs.getString("player_name"));
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Ошибка поиска профиля " + value, e);
        }
        return Optional.empty();
    }

    private Map<String, PlayerProfile> loadProfiles(Connection conn, List<AuctionItem> items, String playerName) throws SQLException {
        Map<String, PlayerProfile> profiles = new HashMap<>();

        for (AuctionItem item : items) {
            String name = item.sellerName();
            profiles.putIfAbsent(name, PlayerProfile.empty(name));
        }
        profiles.putIfAbsent(playerName, PlayerProfile.empty(playerName));

        if (profiles.isEmpty()) return profiles;

        String placeholders = "?,".repeat(profiles.size() - 1) + "?";
        String sql = "SELECT player_name,discord,store_name FROM auction_player_profiles WHERE player_name IN (" + placeholders + ")";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int index = 1;
            for (String name : profiles.keySet()) {
                ps.setString(index++, name);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString("player_name");
                    PlayerProfile profile = profiles.get(name);
                    profiles.put(name, new PlayerProfile(name, rs.getString("discord"), rs.getString("store_name"), profile.sales()));
                }
            }
        }

        String salesSql = "SELECT seller_name,COUNT(*) AS sales FROM sales_history WHERE seller_name IN (" + placeholders + ") GROUP BY seller_name";

        try (PreparedStatement ps = conn.prepareStatement(salesSql)) {
            int index = 1;
            for (String name : profiles.keySet()) {
                ps.setString(index++, name);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString("seller_name");
                    PlayerProfile profile = profiles.get(name);
                    profiles.put(name, new PlayerProfile(name, profile.discord(), profile.storeName(), rs.getInt("sales")));
                }
            }
        }
        return profiles;
    }

    private record MenuQueryResult(List<AuctionItem> items, int totalCount, long validUntil, Map<String, Integer> materialLots, Long2ObjectMap<String> itemGroups) {
        MenuQueryResult(List<AuctionItem> items, int totalCount, long validUntil) { this(items, totalCount, validUntil, Map.of(), Long2ObjectMaps.emptyMap()); }
    }

    private MenuQueryResult findPageAndCount(Connection conn, AuctionCurrency currency, int page, int pageSize, AuctionSort sort, String category, String sellerFilter, String searchFilter, AuctionViewType viewType, long cutoff, String playerName) throws SQLException {
        List<Object> params = new ArrayList<>();
        params.add(currency.name());
        params.add(active);

        StringBuilder baseWhere = new StringBuilder("FROM auction_items WHERE currency=? AND status=?");
        if (viewType == AuctionViewType.MAIN || viewType == AuctionViewType.OFFERS) {
            baseWhere.append(" AND group_key IS NOT NULL");
        }

        if (viewType == AuctionViewType.MAIN || viewType == AuctionViewType.OFFERS) {
            baseWhere.append(" AND created_at >= ?");
            params.add(cutoff);
            if (viewType == AuctionViewType.OFFERS && TextUtils.isNotBlank(category) && category.startsWith("group:")) {
                baseWhere.append(" AND group_key=?");
                params.add(category.substring(6));
            } else if (TextUtils.isNotBlank(category) && !category.equalsIgnoreCase("all")) {
                List<String> materials = viewType == AuctionViewType.OFFERS ? null : categoryMaterials.get(category.toLowerCase(Locale.ENGLISH));
                if (materials == null) {
                    baseWhere.append(" AND material=?");
                    params.add(category.toUpperCase(Locale.ENGLISH));
                } else if (materials.isEmpty()) {
                    baseWhere.append(" AND 1=0");
                } else {
                    baseWhere.append(" AND material IN (");
                    for (int i = 0; i < materials.size(); i++) {
                        if (i > 0) baseWhere.append(',');
                        baseWhere.append('?');
                        params.add(materials.get(i));
                    }
                    baseWhere.append(')');
                }
            }
            if (TextUtils.isNotBlank(sellerFilter)) {
                baseWhere.append(" AND seller_name LIKE ?");
                params.add("%" + sellerFilter + "%");
            }
            if (TextUtils.isNotBlank(searchFilter)) {
                String pattern = "%" + searchFilter + "%";
                List<String> materials = ItemUtils.getSearchIndex().findMaterials(searchFilter);
                baseWhere.append(" AND (");
                if (!materials.isEmpty()) {
                    baseWhere.append("material IN (?").append(",?".repeat(materials.size() - 1)).append(") OR ");
                    params.addAll(materials);
                }
                baseWhere.append("material LIKE ? OR seller_name LIKE ? OR seller_name IN (SELECT player_name FROM auction_player_profiles WHERE discord_plain LIKE ? OR store_name_plain LIKE ?))");
                params.add(pattern);
                params.add(pattern);
                params.add(pattern);
                params.add(pattern);
            }
        } else if (viewType == AuctionViewType.SELLING) {
            baseWhere.append(" AND seller_name = ?");
            params.add(playerName);
        } else if (viewType == AuctionViewType.EXPIRED) {
            baseWhere.append(" AND seller_name = ?");
            params.add(playerName);
            baseWhere.append(" AND created_at < ?");
            params.add(cutoff);
        }

        if (currency == AuctionCurrency.VAULT && viewType == AuctionViewType.MAIN && !TextUtils.isNotBlank(searchFilter)) {
            return findMaterialGroups(conn, baseWhere.toString(), params, sort, page, pageSize);
        }

        String columns = "id,seller_name,currency,item,price,created_at,status,material,amount,price_per_item,lot_type,operation_id,processing_started_at,NULL AS inventory_data";

        String orderBy = switch (sort) {
            case NEWEST -> "created_at DESC";
            case OLDEST -> "created_at ASC";
            case EXPENSIVE -> "price DESC";
            case CHEAP -> "price ASC";
            case EXPENSIVE_PER_ITEM -> "price_per_item DESC, id DESC";
            case CHEAP_PER_ITEM -> "price_per_item ASC, id ASC";
        };
        String select = "SELECT " + columns + (separatePageCount ? " " : ", COUNT(*) OVER() AS total_count ");
        String sql;
        if ((viewType == AuctionViewType.MAIN || viewType == AuctionViewType.OFFERS) && (!TextUtils.isNotBlank(category) || category.equalsIgnoreCase("all")) && !TextUtils.isNotBlank(sellerFilter) && !TextUtils.isNotBlank(searchFilter)) {
            sql = new StringJoiner("").add(select).add(baseWhere).add(" ORDER BY ").add(orderBy).add(" LIMIT ? OFFSET ?").toString();
        } else {
            sql = new StringBuilder(select).append(baseWhere).append(" ORDER BY ").append(orderBy).append(" LIMIT ? OFFSET ?").toString();
        }

        int safePage = Math.max(0, page);
        int safePageSize = Math.max(1, pageSize);
        params.add(safePageSize);
        params.add(safePage * safePageSize);

        ObjectList<AuctionItem> items = new ObjectArrayList<>();
        int totalCount = 0;
        if (separatePageCount) {
            try (PreparedStatement count = conn.prepareStatement("SELECT COUNT(*) " + baseWhere)) {
                for (int i = 0; i < params.size() - 2; i++) count.setObject(i + 1, params.get(i));
                try (ResultSet rs = count.executeQuery()) { rs.next(); totalCount = rs.getInt(1); }
            }
        }
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                boolean first = true;
                while (rs.next()) {
                    if (first && !separatePageCount) {
                        try {
                            totalCount = rs.getInt("total_count");
                        } catch (SQLException ignored) {}
                        first = false;
                    }
                    items.add(readItem(rs));
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        if (items.isEmpty() && !separatePageCount) {
            String countSql = "SELECT COUNT(*) " + baseWhere;
            try (PreparedStatement ps = conn.prepareStatement(countSql)) {
                int filterCount = params.size() - 2;
                for (int i = 0; i < filterCount; i++) {
                    ps.setObject(i + 1, params.get(i));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        totalCount = rs.getInt(1);
                    }
                }
            }
        }

        return new MenuQueryResult(items, totalCount, Long.MAX_VALUE);
    }
    private MenuQueryResult findMaterialGroups(Connection conn, String where, List<Object> params, AuctionSort sort, int page, int pageSize) throws SQLException {
        String aggregate = switch (sort) {
            case NEWEST -> "MAX(created_at)";
            case OLDEST -> "MIN(created_at)";
            case EXPENSIVE -> "MAX(price)";
            case CHEAP -> "MIN(price)";
            case EXPENSIVE_PER_ITEM -> "MAX(price_per_item)";
            case CHEAP_PER_ITEM -> "MIN(price_per_item)";
        };
        String direction = switch (sort) {
            case NEWEST, EXPENSIVE, EXPENSIVE_PER_ITEM -> "DESC";
            default -> "ASC";
        };
        int total;
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM (SELECT group_key " + where + " GROUP BY group_key) groups")) {
            for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
            try (ResultSet rs = ps.executeQuery()) { rs.next(); total = rs.getInt(1); }
        }
        String sql = "SELECT a.id,a.seller_name,a.currency,a.item,a.price,a.created_at,a.status,a.material,a.amount,a.price_per_item," + "a.lot_type,a.operation_id,a.processing_started_at,NULL AS inventory_data,g.group_key,g.lots FROM auction_items a JOIN " + "(SELECT group_key,MIN(id) representative_id,COUNT(*) lots," + aggregate + " sort_value " + where + " GROUP BY group_key) g ON a.id=g.representative_id ORDER BY g.sort_value " + direction + ",g.group_key ASC LIMIT ? OFFSET ?";
        List<AuctionItem> items = new ArrayList<>();
        Map<String, Integer> lots = new HashMap<>();
        Long2ObjectMap<String> itemGroups = new Long2ObjectOpenHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int index = 1;
            for (Object param : params) ps.setObject(index++, param);
            int size = Math.max(1, pageSize);
            ps.setInt(index++, size);
            ps.setLong(index, Math.max(0L, page) * size);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    items.add(readItem(rs));
                    String group = rs.getString("group_key");
                    lots.put(group, rs.getInt("lots"));
                    itemGroups.put(rs.getLong("id"), group);
                }
            }
        } catch (SQLException e) { throw e; }
        catch (Exception e) { throw new SQLException("Не удалось прочитать карточки материалов", e); }
        return new MenuQueryResult(items, total, Long.MAX_VALUE, Map.copyOf(lots), Long2ObjectMaps.unmodifiable(itemGroups));
    }

    private Connection open() throws SQLException { return sqlRuntime.open(dataSource); }

    private Map<String, List<String>> readCategoryMaterials() {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("categories");
        if (section == null) return Map.of();
        Map<String, List<String>> categories = new HashMap<>();
        for (String category : section.getKeys(false)) {
            List<String> materials = new ArrayList<>();
            for (String name : section.getStringList(category)) {
                Material material = Material.matchMaterial(name.trim());
                if (material != null) materials.add(material.name());
            }
            categories.put(category.toLowerCase(Locale.ENGLISH), List.copyOf(materials));
        }
        return Map.copyOf(categories);
    }
}
