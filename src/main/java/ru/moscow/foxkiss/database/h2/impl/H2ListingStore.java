package ru.moscow.foxkiss.database.h2.impl;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;

import ru.moscow.foxkiss.auction.*;
import ru.moscow.foxkiss.auction.AuctionRepository.*;
import static ru.moscow.foxkiss.database.h2.impl.H2ItemCodec.*;
import static ru.moscow.foxkiss.database.h2.impl.H2Storage.*;
import static ru.moscow.foxkiss.auction.AuctionRepository.richi_limit;

public final class H2ListingStore {
    private final H2Storage storage;
    private final ReentrantLock[] listingLocks = new ReentrantLock[64];

    public H2ListingStore(H2Storage storage) {
        this.storage = storage;
        for (int i = 0; i < listingLocks.length; i++) listingLocks[i] = new ReentrantLock();
    }

    public long createWithinLimit(String sellerName, AuctionCurrency currency, ItemStack itemStack, double price, int limit) {
        return createListing(sellerName, currency, itemStack, null, price, limit);
    }

    public long createInventoryWithinLimit(String sellerName, AuctionCurrency currency, ItemStack displayItem, List<ItemStack> contents, double price, int limit) {
        return createListing(sellerName, currency, displayItem, contents, price, limit);
    }

    private long createListing(String sellerName, AuctionCurrency currency, ItemStack item, List<ItemStack> contents, double price, Integer limit) {
        ReentrantLock listingLock = listingLocks[Math.floorMod(Objects.hash(sellerName, currency), listingLocks.length)];
        listingLock.lock();
        try {
            String serializedItem = serialize(item);
            String serializedInventory = contents == null ? null : serializeInventory(contents);
            try (Connection conn = storage.open()) {
                conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                conn.setAutoCommit(false);
                try {
                    lockSeller(conn, sellerName, currency);
                    if (limit != null && countActiveBySellerSince(conn, sellerName, currency, 0) >= limit) {
                        conn.rollback();
                        return richi_limit;
                    }
                    long id;
                    try (PreparedStatement ps = conn.prepareStatement("INSERT INTO auction_items(seller_name,currency,item,price,created_at,status,material,amount,price_per_item,lot_type,inventory_data,group_key) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
                        int amount = contents == null ? item.getAmount() : Math.max(1, item.getAmount());
                        BigDecimal priceBd = BigDecimal.valueOf(price).setScale(2, RoundingMode.HALF_UP);
                        BigDecimal pricePerItemBd = amount > 0 ? priceBd.divide(BigDecimal.valueOf(amount), 2, RoundingMode.HALF_UP) : priceBd;
                        ps.setString(1, sellerName);
                        ps.setString(2, currency.name());
                        ps.setString(3, serializedItem);
                        ps.setBigDecimal(4, priceBd);
                        ps.setLong(5, System.currentTimeMillis());
                        ps.setInt(6, active);
                        ps.setString(7, item.getType().name());
                        ps.setInt(8, amount);
                        ps.setBigDecimal(9, pricePerItemBd);
                        ps.setString(10, contents == null ? AuctionLotType.ITEM.name() : AuctionLotType.INVENTORY.name());
                        ps.setString(11, serializedInventory);
                        ps.setString(12, groupKey(item, contents == null ? AuctionLotType.ITEM : AuctionLotType.INVENTORY, serializedInventory));
                        ps.executeUpdate();
                        try (ResultSet keys = ps.getGeneratedKeys()) {
                            if (!keys.next() || (id = keys.getLong(1)) <= 0) {
                                throw new SQLException("Отсутствует сгенерированный ID лота аукциона");
                            }
                        }
                    }
                    conn.commit();
                    return id;
                } catch (Exception e) {
                    try {
                        conn.rollback();
                    } catch (SQLException rollbackError) {
                        e.addSuppressed(rollbackError);
                    }
                    throw e;
                }
            }
        } catch (Exception e) {
            storage.plugin.getLogger().log(Level.WARNING, "Ошибка создания лота", e);
            return -1L;
        } finally {
            listingLock.unlock();
        }
    }

    public List<Long> loadInventoryLotIds(AuctionCurrency currency) {
        ObjectArrayList<Long> ids = new ObjectArrayList<>();
        try (Connection conn = storage.open();
             PreparedStatement ps = conn.prepareStatement("SELECT id FROM auction_items WHERE currency=? AND status=? AND lot_type=? ORDER BY id ASC")) {
            ps.setString(1, currency.name());
            ps.setInt(2, active);
            ps.setString(3, AuctionLotType.INVENTORY.name());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getLong("id"));
                }
            }
        } catch (SQLException e) {
            storage.plugin.getLogger().log(Level.WARNING, "Ошибка загрузки ID лотов-инвентарей", e);
        }
        return ids;
    }

    public Optional<AuctionItem> findById(long id) {
        try (Connection conn = storage.open();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM auction_items WHERE id=?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readItem(rs)) : Optional.empty();
            }
        } catch (Exception e) {
            storage.plugin.getLogger().log(Level.WARNING, "Ошибка поиска лота по ID", e);
            return Optional.empty();
        }
    }

    public boolean markAsProcessing(long id, String operationId, long startedAt) {
        try (Connection conn = storage.open();
             PreparedStatement ps = conn.prepareStatement("UPDATE auction_items SET status=?, operation_id=?, processing_started_at=? WHERE id=? AND status=?")) {
            ps.setInt(1, processing);
            ps.setString(2, operationId);
            ps.setLong(3, startedAt);
            ps.setLong(4, id);
            ps.setInt(5, active);
            boolean acquired = ps.executeUpdate() > 0;
            if (acquired && operationId != null) storage.liveOperations.add(operationId);
            return acquired;
        } catch (SQLException e) {
            storage.plugin.getLogger().log(Level.WARNING, "Ошибка пометки лота #" + id + " как обрабатываемого", e);
            return false;
        }
    }

    public void cancelProcessing(long id, String operationId) {
        if (operationId != null) storage.liveOperations.remove(operationId);
        try (Connection conn = storage.open();
             PreparedStatement ps = conn.prepareStatement("UPDATE auction_items SET status=?, operation_id=NULL, processing_started_at=0 WHERE id=? AND status=? AND (operation_id=? OR ? IS NULL)")) {
            ps.setInt(1, active);
            ps.setLong(2, id);
            ps.setInt(3, processing);
            ps.setString(4, operationId);
            ps.setString(5, operationId);
            ps.executeUpdate();
        } catch (SQLException e) {
            storage.plugin.getLogger().log(Level.WARNING, "Ошибка сброса статуса лота #" + id, e);
        }
    }

    public int countActiveBySellerSince(String sellerName, AuctionCurrency currency, long since) {
        try (Connection conn = storage.open()) {
            return countActiveBySellerSince(conn, sellerName, currency, since);
        } catch (SQLException e) {
            storage.plugin.getLogger().log(Level.WARNING, "Ошибка подсчёта активных лотов продавца", e);
            throw new IllegalStateException("Не удалось подсчитать активные лоты продавца", e);
        }
    }

    private int countActiveBySellerSince(Connection conn, String sellerName, AuctionCurrency currency, long since) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM auction_items WHERE seller_name=? AND currency=? AND created_at>? AND status IN (?,?)")) {
            ps.setString(1, sellerName);
            ps.setString(2, currency.name());
            ps.setLong(3, since);
            ps.setInt(4, active);
            ps.setInt(5, processing);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }
}
