package ru.moscow.foxkiss.database.h2.impl;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

import ru.moscow.foxkiss.auction.*;
import ru.moscow.foxkiss.auction.AuctionRepository.*;
import static ru.moscow.foxkiss.database.h2.impl.H2ItemCodec.*;
import static ru.moscow.foxkiss.database.h2.impl.H2Storage.*;

public final class H2SalesStore {
    private final H2Storage storage;

    public H2SalesStore(H2Storage storage) {
        this.storage = storage;
    }

    void recordSale(Connection conn, String operationId, AuctionItem lot, String buyerName, ItemStack purchased, int amount, double price) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO sales_history(seller_name,buyer_name,currency,item_type,amount,price,sold_at,item,operation_id) " + "SELECT ?,?,?,?,?,?,?,?,? WHERE NOT EXISTS (SELECT 1 FROM sales_history WHERE operation_id=?)")) {
            ps.setString(1, lot.sellerName());
            ps.setString(2, buyerName);
            ps.setString(3, lot.currency().name());
            ps.setString(4, lot.type() == AuctionLotType.INVENTORY ? "INVENTORY" : purchased.getType().name());
            ps.setInt(5, amount);
            ps.setBigDecimal(6, BigDecimal.valueOf(price).setScale(2, RoundingMode.HALF_UP));
            ps.setLong(7, System.currentTimeMillis());
            ps.setString(8, Base64.getEncoder().encodeToString(purchased.serializeAsBytes()));
            ps.setString(9, operationId);
            ps.setString(10, operationId);
            ps.executeUpdate();
        }
    }

    public SalesHistoryPage loadSalesHistory(String sellerName, AuctionCurrency currency, int requestedPage, int pageSize) {
        if (pageSize < 1 || pageSize > 54) throw new IllegalArgumentException("Недопустимый размер страницы истории");
        try (Connection conn = storage.open()) {
            conn.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            conn.setAutoCommit(false);
            int total;
            try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM sales_history WHERE seller_name=? AND currency=?")) {
                ps.setString(1, sellerName);
                ps.setString(2, currency.name());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    total = rs.getInt(1);
                }
            }
            int pages = (int) Math.max(1L, (total + (long) pageSize - 1) / pageSize);
            int page = Math.max(0, Math.min(requestedPage, pages - 1));
            List<SaleHistoryEntry> entries = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement("SELECT id,seller_name,buyer_name,item_type,amount,price,sold_at,item FROM sales_history " + "WHERE seller_name=? AND currency=? ORDER BY sold_at DESC,id DESC LIMIT ? OFFSET ?")) {
                ps.setString(1, sellerName);
                ps.setString(2, currency.name());
                ps.setInt(3, pageSize);
                ps.setLong(4, (long) page * pageSize);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ItemStack item = null;
                        String data = rs.getString("item");
                        if (data != null && !data.isEmpty()) {
                            try {
                                item = deserialize(data);
                            } catch (Exception e) {
                                storage.plugin.getLogger().log(Level.WARNING, "Ошибка чтения предмета истории #" + rs.getLong("id"), e);
                            }
                        }
                        entries.add(new SaleHistoryEntry(rs.getLong("id"), rs.getString("seller_name"), rs.getString("buyer_name"), currency, rs.getString("item_type"), item, rs.getInt("amount"), rs.getBigDecimal("price").doubleValue(), rs.getLong("sold_at")));
                    }
                }
            }
            conn.commit();
            return new SalesHistoryPage(List.copyOf(entries), total, page, pages);
        } catch (SQLException e) {
            throw new IllegalStateException("Не удалось загрузить историю продаж " + sellerName, e);
        }
    }

    public CompletableFuture<SalesHistoryPage> loadSalesHistoryAsync(String sellerName, AuctionCurrency currency, int requestedPage, int pageSize) {
        return CompletableFuture.supplyAsync(() -> loadSalesHistory(sellerName, currency, requestedPage, pageSize), storage.asyncExecutor);
    }

    public List<TopSeller> getTopSellers(AuctionCurrency currency, int limit) {
        return loadTopSellers(currency, limit, "sold");
    }

    public List<TopSeller> getTopSellersByRevenue(AuctionCurrency currency, int limit) {
        return loadTopSellers(currency, limit, "earned");
    }

    private List<TopSeller> loadTopSellers(AuctionCurrency currency, int limit, String orderBy) {
        ObjectList<TopSeller> sellers = new ObjectArrayList<>();
        try (Connection conn = storage.open();
             PreparedStatement ps = conn.prepareStatement("SELECT seller_name, COUNT(*) AS sold, COALESCE(SUM(price),0) AS earned " + "FROM sales_history WHERE currency=? GROUP BY seller_name ORDER BY " + orderBy + " DESC, seller_name ASC LIMIT ?")) {
            ps.setString(1, currency.name());
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    BigDecimal earnedBd = rs.getBigDecimal("earned");
                    double earned = earnedBd != null ? earnedBd.doubleValue() : 0.0;
                    sellers.add(new TopSeller(rs.getString("seller_name"), rs.getInt("sold"), earned));
                }
            }
        } catch (SQLException e) {
            storage.plugin.getLogger().log(Level.WARNING, "Ошибка получения топ-продавцов", e);
        }
        return sellers;
    }

    public PlayerStats getPlayerStats(String playerName, AuctionCurrency currency) {
        try (Connection conn = storage.open();
             PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) AS sold, COALESCE(SUM(price),0) AS earned " + "FROM sales_history WHERE seller_name=? AND currency=?")) {
            ps.setString(1, playerName);
            ps.setString(2, currency.name());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    BigDecimal earnedBd = rs.getBigDecimal("earned");
                    double earned = earnedBd != null ? earnedBd.doubleValue() : 0.0;
                    return new PlayerStats(rs.getInt("sold"), earned);
                }
            }
        } catch (SQLException e) {
            storage.plugin.getLogger().log(Level.WARNING, "Ошибка получения статистики игрока", e);
        }
        return new PlayerStats(0, 0);
    }
}
