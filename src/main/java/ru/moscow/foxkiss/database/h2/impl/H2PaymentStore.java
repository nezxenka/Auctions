package ru.moscow.foxkiss.database.h2.impl;

import org.bukkit.Bukkit;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import ru.moscow.foxkiss.economy.EconomyProvider;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.*;
import java.util.*;
import java.util.logging.Level;

import ru.moscow.foxkiss.auction.*;
import ru.moscow.foxkiss.auction.AuctionRepository.*;

public final class H2PaymentStore {
    private final H2Storage storage;
    private final H2ListingStore listings;

    public H2PaymentStore(H2Storage storage, H2ListingStore listings) {
        this.storage = storage;
        this.listings = listings;
    }

    public void logTransaction(String operationId, long lotId, String type, String buyerName, String sellerName, AuctionCurrency currency, int amount, double price, String status) {
        long now = System.currentTimeMillis();
        try (Connection conn = storage.open();
             PreparedStatement ps = conn.prepareStatement("MERGE INTO auction_transaction_journal(operation_id,lot_id,type,buyer_name,seller_name,currency,amount,price,status,created_at,updated_at) KEY(operation_id) VALUES(?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, operationId);
            ps.setLong(2, lotId);
            ps.setString(3, type);
            ps.setString(4, buyerName);
            ps.setString(5, sellerName);
            ps.setString(6, currency.name());
            ps.setInt(7, amount);
            ps.setBigDecimal(8, BigDecimal.valueOf(price).setScale(2, RoundingMode.HALF_UP));
            ps.setString(9, status);
            ps.setLong(10, now);
            ps.setLong(11, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Не удалось сохранить журнал транзакций", e);
        }
    }

    public void updateJournalStatus(String operationId, String status) {
        long now = System.currentTimeMillis();
        try (Connection conn = storage.open();
             PreparedStatement ps = conn.prepareStatement("UPDATE auction_transaction_journal SET status=?, updated_at=? WHERE operation_id=?")) {
            ps.setString(1, status);
            ps.setLong(2, now);
            ps.setString(3, operationId);
            if (ps.executeUpdate() != 1) throw new SQLException("Отсутствует запись журнала: " + operationId);
        } catch (SQLException e) {
            throw new IllegalStateException("Не удалось сохранить статус транзакции " + operationId, e);
        }
    }

    public void savePendingPayout(String playerName, AuctionCurrency currency, double amount) {
        long now = System.currentTimeMillis();
        try (Connection conn = storage.open();
             PreparedStatement ps = conn.prepareStatement("INSERT INTO auction_pending_payouts(player_name,currency,amount,created_at) VALUES(?,?,?,?)")) {
            ps.setString(1, playerName);
            ps.setString(2, currency.name());
            ps.setBigDecimal(3, BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP));
            ps.setLong(4, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            storage.plugin.getLogger().log(Level.WARNING, "Ошибка сохранения ожидающей выплаты игроку " + playerName, e);
        }
    }


    void savePayout(Connection conn, String operationId, String player, AuctionCurrency currency, double amount) throws SQLException {
        if (amount == 0) return;
        if (!Double.isFinite(amount) || amount < 0) throw new SQLException("Недопустимая сумма выплаты");
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO auction_pending_payouts(player_name,currency,amount,created_at,operation_id) SELECT ?,?,?,?,? WHERE NOT EXISTS (SELECT 1 FROM auction_pending_payouts WHERE operation_id=?)")) {
            ps.setString(1, player);
            ps.setString(2, currency.name());
            ps.setBigDecimal(3, BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP));
            ps.setLong(4, System.currentTimeMillis());
            ps.setString(5, operationId);
            ps.setString(6, operationId);
            ps.executeUpdate();
        }
    }

    void requireWithdrawn(Connection conn, String operationId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT status FROM auction_transaction_journal WHERE operation_id=? FOR UPDATE")) {
            ps.setString(1, operationId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next() || !"MONEY_WITHDRAWN".equals(rs.getString(1))) throw new SQLException("Отсутствует подтверждённое списание для операции");
            }
        }
    }

    public boolean prepareWithdrawal(String operationId) {
        try (Connection conn = storage.open(); PreparedStatement ps = conn.prepareStatement("UPDATE auction_transaction_journal SET status='WITHDRAW_INTENT',updated_at=? WHERE operation_id=? AND status='STARTED'")) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, operationId);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("Не удалось подготовить списание", e);
        }
    }

    public void refundPurchase(String operationId) {
        try (Connection conn = storage.open()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM auction_transaction_journal WHERE operation_id=? FOR UPDATE")) {
                ps.setString(1, operationId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next() || !"MONEY_WITHDRAWN".equals(rs.getString("status"))) { conn.rollback(); return; }
                    if (rs.getDouble("price") <= 0) {
                        conn.rollback();
                        updateJournalStatus(operationId, "REVIEW");
                        storage.plugin.getLogger().severe("Missing debit amount; reconciliation required: " + operationId);
                        return;
                    }
                    savePayout(conn, operationId + ":refund", rs.getString("buyer_name"), AuctionCurrency.valueOf(rs.getString("currency")), rs.getDouble("price"));
                    try (PreparedStatement lot = conn.prepareStatement("UPDATE auction_items SET status=0,operation_id=NULL,processing_started_at=0 WHERE operation_id=?")) {
                        lot.setString(1, operationId); lot.executeUpdate();
                    }
                    try (PreparedStatement journal = conn.prepareStatement("UPDATE auction_transaction_journal SET status='REFUND_QUEUED',updated_at=? WHERE operation_id=?")) {
                        journal.setLong(1, System.currentTimeMillis()); journal.setString(2, operationId); journal.executeUpdate();
                    }
                    conn.commit();
                    storage.liveOperations.remove(operationId);
                }
            }
        } catch (SQLException e) { throw new IllegalStateException("Не удалось поставить возврат в очередь " + operationId, e); }
    }

    public void processPendingPayouts(EconomyProvider economyProvider) {
        List<PendingPayout> pending = new ArrayList<>();
        try (Connection conn = storage.open(); PreparedStatement ps = conn.prepareStatement("SELECT * FROM auction_pending_payouts WHERE status='READY' ORDER BY id LIMIT 100" ); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) pending.add(new PendingPayout(rs.getLong("id"), rs.getString("player_name"), AuctionCurrency.valueOf(rs.getString("currency")), rs.getDouble("amount"), rs.getLong("created_at")));
        } catch (SQLException e) { throw new IllegalStateException("Не удалось загрузить выплаты", e); }
        for (PendingPayout payout : pending) {
            try (Connection conn = storage.open(); PreparedStatement ps = conn.prepareStatement("UPDATE auction_pending_payouts SET status='PAYING' WHERE id=? AND status='READY'")) {
                ps.setLong(1, payout.id());
                if (ps.executeUpdate() != 1) continue;
            } catch (SQLException e) { throw new IllegalStateException("Не удалось зарезервировать выплату", e); }
            Bukkit.getScheduler().runTask(storage.plugin, () -> {
                try {
                    boolean paid = economyProvider.deposit(Bukkit.getOfflinePlayer(payout.playerName()), payout.currency(), payout.amount());
                    storage.asyncExecutor.execute(() -> {
                        try (Connection conn = storage.open(); PreparedStatement ps = conn.prepareStatement("UPDATE auction_pending_payouts SET status=? WHERE id=? AND status='PAYING'")) {
                            ps.setString(1, paid ? "PAID" : "READY"); ps.setLong(2, payout.id()); ps.executeUpdate();
                        } catch (SQLException e) { storage.plugin.getLogger().log(Level.SEVERE, "Payout requires reconciliation: " + payout.id(), e); }
                    });
                } catch (Exception e) { storage.plugin.getLogger().log(Level.SEVERE, "Payout requires reconciliation: " + payout.id(), e); }
            });
        }
    }

    public void recoverStuckLots(EconomyProvider economyProvider, boolean forceAll) {
        long cutoff = forceAll ? Long.MAX_VALUE : System.currentTimeMillis() - 60_000L;
        try (Connection conn = storage.open(); PreparedStatement ps = conn.prepareStatement("SELECT operation_id,status,lot_id FROM auction_transaction_journal WHERE updated_at<? AND status IN ('STARTED','WITHDRAW_INTENT','MONEY_WITHDRAWN','LOT_UPDATED')")) {
            ps.setLong(1, cutoff);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String op = rs.getString(1), status = rs.getString(2);
                    if (storage.liveOperations.contains(op)) continue;
                    if ("MONEY_WITHDRAWN".equals(status)) refundPurchase(op);
                    else if ("STARTED".equals(status)) {
                        listings.cancelProcessing(rs.getLong(3), op);
                        updateJournalStatus(op, "CANCELLED");
                    } else {
                        updateJournalStatus(op, "REVIEW");
                        storage.plugin.getLogger().severe("Transaction requires economy reconciliation: " + op + " (" + status + ")");
                    }
                }
            }
            try (PreparedStatement orphan = conn.prepareStatement("SELECT id,operation_id FROM auction_items WHERE status=1 AND processing_started_at<? AND NOT EXISTS (SELECT 1 FROM auction_transaction_journal j WHERE j.operation_id=auction_items.operation_id)")) {
                orphan.setLong(1, cutoff);
                try (ResultSet rs = orphan.executeQuery()) {
                    while (rs.next()) {
                        String op = rs.getString(2);
                        if (op == null || !storage.liveOperations.contains(op)) listings.cancelProcessing(rs.getLong(1), op);
                    }
                }
            }
        } catch (SQLException e) { throw new IllegalStateException("Не удалось восстановить транзакции", e); }
        processPendingPayouts(economyProvider);
    }
}
