package ru.moscow.foxkiss.database.h2.impl;

import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.*;
import java.util.*;
import java.util.logging.Level;

import ru.moscow.foxkiss.auction.*;
import ru.moscow.foxkiss.auction.AuctionRepository.*;
import static ru.moscow.foxkiss.database.h2.impl.H2ItemCodec.*;
import static ru.moscow.foxkiss.database.h2.impl.H2Storage.*;

public final class H2PurchaseStore {
    private final H2Storage storage;
    private final H2SalesStore sales;
    private final H2PaymentStore payments;

    public H2PurchaseStore(H2Storage storage, H2SalesStore sales, H2PaymentStore payments) {
        this.storage = storage;
        this.sales = sales;
        this.payments = payments;
    }

    public PurchaseResult processPurchase(long lotId, String operationId, String buyerName, int buyAmount, double totalPrice) {
        try (Connection conn = storage.open()) {
            conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            conn.setAutoCommit(false);
            try {
                AuctionItem item = null;
                try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM auction_items WHERE id=? AND status=? AND operation_id=? FOR UPDATE")) {
                    ps.setLong(1, lotId);
                    ps.setInt(2, processing);
                    ps.setString(3, operationId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            item = readItem(rs);
                        }
                    }
                }
                if (item == null) {
                    conn.rollback();
                    return new PurchaseResult(false, 0, null, false);
                }

                payments.requireWithdrawn(conn, operationId);
                int currentAmount = item.amount();
                int actualBuyAmount = Math.max(1, Math.min(buyAmount, currentAmount));
                ItemStack purchasedItem = item.itemStack().clone();
                purchasedItem.setAmount(actualBuyAmount);

                boolean remainingUpdated = false;
                if (actualBuyAmount < currentAmount) {
                    int remainingAmount = currentAmount - actualBuyAmount;
                    ItemStack remainingItem = item.itemStack().clone();
                    remainingItem.setAmount(remainingAmount);
                    double actualBuyPrice = item.calculatePurchasePrice(actualBuyAmount);
                    double remainingPrice = item.calculateRemainingPrice(actualBuyAmount, actualBuyPrice);
                    BigDecimal remainingPriceBd = BigDecimal.valueOf(remainingPrice).setScale(2, RoundingMode.HALF_UP);
                    BigDecimal pricePerItemBd = BigDecimal.valueOf(remainingPrice)
                            .divide(BigDecimal.valueOf(remainingAmount), 2, RoundingMode.HALF_UP);
                    String serializedRemaining = serialize(remainingItem);

                    try (PreparedStatement ps = conn.prepareStatement(
                            "UPDATE auction_items SET amount=?, item=?, price=?, price_per_item=?, status=?, operation_id=NULL, processing_started_at=0 WHERE id=?")) {
                        ps.setInt(1, remainingAmount);
                        ps.setString(2, serializedRemaining);
                        ps.setBigDecimal(3, remainingPriceBd);
                        ps.setBigDecimal(4, pricePerItemBd);
                        ps.setInt(5, active);
                        ps.setLong(6, lotId);
                        ps.executeUpdate();
                    }
                    remainingUpdated = true;
                } else {
                    try (PreparedStatement ps = conn.prepareStatement("DELETE FROM auction_items WHERE id=?")) {
                        ps.setLong(1, lotId);
                        ps.executeUpdate();
                    }
                }

                long deliveryId = 0;
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO auction_pending_deliveries(player_name, lot_type, item, inventory_data, created_at, currency) VALUES(?,?,?,?,?,?)",
                        Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, buyerName);
                    ps.setString(2, AuctionLotType.ITEM.name());
                    ps.setString(3, serialize(purchasedItem));
                    ps.setString(4, null);
                    ps.setLong(5, System.currentTimeMillis());
                    ps.setString(6, item.currency().name());
                    ps.executeUpdate();
                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        if (keys.next()) {
                            deliveryId = keys.getLong(1);
                        }
                    }
                }

                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE auction_transaction_journal SET status=?, updated_at=? WHERE operation_id=?")) {
                    ps.setString(1, "SALE_COMMITTED");
                    ps.setLong(2, System.currentTimeMillis());
                    ps.setString(3, operationId);
                    ps.executeUpdate();
                }

                sales.recordSale(conn, operationId, item, buyerName, purchasedItem, actualBuyAmount, totalPrice);
                payments.savePayout(conn, operationId + ":seller", item.sellerName(), item.currency(),
                        item.calculatePurchasePrice(actualBuyAmount));
                conn.commit();
                storage.liveOperations.remove(operationId);
                return new PurchaseResult(true, deliveryId, purchasedItem, remainingUpdated);
            } catch (Exception e) {
                try {
                    conn.rollback();
                } catch (SQLException rollbackEx) {
                    e.addSuppressed(rollbackEx);
                }
                storage.plugin.getLogger().log(Level.WARNING, "Ошибка обработки покупки лота #" + lotId, e);
                return new PurchaseResult(false, 0, null, false);
            }
        } catch (SQLException e) {
            storage.plugin.getLogger().log(Level.WARNING, "Ошибка подключения БД при покупке лота #" + lotId, e);
            return new PurchaseResult(false, 0, null, false);
        }
    }

    public PurchaseResult processInventoryPurchase(long lotId, String operationId, String buyerName) {
        try (Connection conn = storage.open()) {
            conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            conn.setAutoCommit(false);
            try {
                AuctionItem item = null;
                try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM auction_items WHERE id=? AND status=? AND operation_id=? FOR UPDATE")) {
                    ps.setLong(1, lotId);
                    ps.setInt(2, processing);
                    ps.setString(3, operationId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            item = readItem(rs);
                        }
                    }
                }
                if (item == null || item.type() != AuctionLotType.INVENTORY) {
                    conn.rollback();
                    return new PurchaseResult(false, 0, null, false);
                }

                payments.requireWithdrawn(conn, operationId);
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM auction_items WHERE id=?")) {
                    ps.setLong(1, lotId);
                    ps.executeUpdate();
                }

                long deliveryId = 0;
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO auction_pending_deliveries(player_name, lot_type, item, inventory_data, created_at, currency) VALUES(?,?,?,?,?,?)",
                        Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, buyerName);
                    ps.setString(2, AuctionLotType.INVENTORY.name());
                    ps.setString(3, serialize(item.itemStack()));
                    ps.setString(4, serializeInventory(item.inventoryContents()));
                    ps.setLong(5, System.currentTimeMillis());
                    ps.setString(6, item.currency().name());
                    ps.executeUpdate();
                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        if (keys.next()) {
                            deliveryId = keys.getLong(1);
                        }
                    }
                }

                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE auction_transaction_journal SET status=?, updated_at=? WHERE operation_id=?")) {
                    ps.setString(1, "SALE_COMMITTED");
                    ps.setLong(2, System.currentTimeMillis());
                    ps.setString(3, operationId);
                    ps.executeUpdate();
                }

                sales.recordSale(conn, operationId, item, buyerName, item.itemStack(), 1, item.price());
                payments.savePayout(conn, operationId + ":seller", item.sellerName(), item.currency(), item.price());
                conn.commit();
                storage.liveOperations.remove(operationId);
                return new PurchaseResult(true, deliveryId, item.itemStack(), false);
            } catch (Exception e) {
                try {
                    conn.rollback();
                } catch (SQLException rollbackEx) {
                    e.addSuppressed(rollbackEx);
                }
                storage.plugin.getLogger().log(Level.WARNING, "Ошибка покупки инвентаря #" + lotId, e);
                return new PurchaseResult(false, 0, null, false);
            }
        } catch (SQLException e) {
            storage.plugin.getLogger().log(Level.WARNING, "Ошибка подключения БД при покупке инвентаря #" + lotId, e);
            return new PurchaseResult(false, 0, null, false);
        }
    }

    public TakeResult processTake(long lotId, String operationId, String playerName) {
        try (Connection conn = storage.open()) {
            conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            conn.setAutoCommit(false);
            try {
                AuctionItem item = null;
                try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM auction_items WHERE id=? AND status=? AND operation_id=? FOR UPDATE")) {
                    ps.setLong(1, lotId);
                    ps.setInt(2, processing);
                    ps.setString(3, operationId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            item = readItem(rs);
                        }
                    }
                }
                if (item == null || !item.sellerName().equalsIgnoreCase(playerName)) {
                    conn.rollback();
                    return new TakeResult(false, 0, null);
                }

                long deliveryId = 0;
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO auction_pending_deliveries(player_name, lot_type, item, inventory_data, created_at, currency) VALUES(?,?,?,?,?,?)",
                        Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, playerName);
                    ps.setString(2, item.type().name());
                    ps.setString(3, serialize(item.itemStack()));
                    ps.setString(4, item.type() == AuctionLotType.INVENTORY ? serializeInventory(item.inventoryContents()) : null);
                    ps.setLong(5, System.currentTimeMillis());
                    ps.setString(6, item.currency().name());
                    ps.executeUpdate();
                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        if (keys.next()) {
                            deliveryId = keys.getLong(1);
                        }
                    }
                }

                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM auction_items WHERE id=?")) {
                    ps.setLong(1, lotId);
                    ps.executeUpdate();
                }

                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE auction_transaction_journal SET status=?, updated_at=? WHERE operation_id=?")) {
                    ps.setString(1, "PENDING_DELIVERY");
                    ps.setLong(2, System.currentTimeMillis());
                    ps.setString(3, operationId);
                    ps.executeUpdate();
                }

                conn.commit();
                storage.liveOperations.remove(operationId);
                return new TakeResult(true, deliveryId, item);
            } catch (Exception e) {
                try {
                    conn.rollback();
                } catch (SQLException rollbackEx) {
                    e.addSuppressed(rollbackEx);
                }
                storage.plugin.getLogger().log(Level.WARNING, "Ошибка возврата лота #" + lotId, e);
                return new TakeResult(false, 0, null);
            }
        } catch (SQLException e) {
            storage.plugin.getLogger().log(Level.WARNING, "Ошибка подключения БД при возврате лота #" + lotId, e);
            return new TakeResult(false, 0, null);
        }
    }
}
