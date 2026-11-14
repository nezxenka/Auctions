package ru.moscow.foxkiss.database.h2;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import ru.moscow.foxkiss.economy.EconomyProvider;
import ru.moscow.foxkiss.gui.AuctionViewType;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import ru.moscow.foxkiss.auction.*;
import ru.moscow.foxkiss.database.h2.impl.*;

public final class H2AuctionRepository implements AuctionRepository {

    private final JavaPlugin plugin;
    private final String jdbcUrl;
    private DataSource dataSource;
    private final SqlRuntime sqlRuntime = new SqlRuntime();
    private H2MenuStore menus;
    private H2ListingStore listings;
    private H2SalesStore sales;
    private H2DeliveryStore deliveries;
    private H2PaymentStore payments;
    private H2PurchaseStore purchases;

    public void clearReadCache() { menus.clearReadCache(); }

    private final ExecutorService asyncExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public H2AuctionRepository(JavaPlugin plugin) {
        this.plugin = plugin;
        Path dbPath = plugin.getDataFolder().toPath().resolve("auction").toAbsolutePath();
        this.jdbcUrl = "jdbc:h2:file:" + dbPath + ";MODE=MySQL;WRITE_DELAY=0";
    }

    @Override
    public void init() {
        if (!plugin.getDataFolder().mkdirs() && !plugin.getDataFolder().exists()) {
            plugin.getLogger().severe("Не удалось создать папку плагина!");
            return;
        }
        dataSource = H2LibraryLoader.loadDataSource(plugin, jdbcUrl);
        H2Storage storage = new H2Storage(plugin, dataSource, sqlRuntime, asyncExecutor);
        listings = new H2ListingStore(storage);
        sales = new H2SalesStore(storage);
        deliveries = new H2DeliveryStore(storage);
        payments = new H2PaymentStore(storage, listings);
        purchases = new H2PurchaseStore(storage, sales, payments);
        ConfigurationSection database = plugin.getConfig().getConfigurationSection("database");
        boolean separatePageCount = database.getBoolean("separate-page-count");
        menus = new H2MenuStore(plugin, dataSource, sqlRuntime, separatePageCount);

        try (Connection conn = open()) {
            new H2Schema(plugin).initialize(conn);
            sqlRuntime.clearSamples();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Ошибка инициализации БД", e);
        }
    }


    @Override
    public long createWithinLimit(String sellerName, AuctionCurrency currency, ItemStack itemStack, double price, int limit) {
        return listings.createWithinLimit(sellerName, currency, itemStack, price, limit);
    }

    @Override
    public long createInventoryWithinLimit(String sellerName, AuctionCurrency currency, ItemStack displayItem, List<ItemStack> contents, double price, int limit) {
        return listings.createInventoryWithinLimit(sellerName, currency, displayItem, contents, price, limit);
    }

    @Override
    public List<Long> loadInventoryLotIds(AuctionCurrency currency) {
        return listings.loadInventoryLotIds(currency);
    }

    @Override
    public MenuData loadMenuData(AuctionCurrency currency, String playerName, int maxDays, int page, int pageSize, AuctionSort sort, String category, String sellerFilter, String searchFilter, AuctionViewType viewType) {
        return menus.loadMenuData(currency, playerName, maxDays, page, pageSize, sort, category, sellerFilter, searchFilter, viewType);
    }

    @Override
    public PlayerProfile getPlayerProfile(String playerName) { return menus.getPlayerProfile(playerName); }

    @Override
    public void savePlayerProfile(String playerName, String discord, String storeName) { menus.savePlayerProfile(playerName, discord, storeName); }

    @Override
    public Optional<String> findProfileOwner(String value) { return menus.findProfileOwner(value); }

    @Override
    public Optional<AuctionItem> findById(long id) {
        return listings.findById(id);
    }

    @Override
    public SalesHistoryPage loadSalesHistory(String sellerName, AuctionCurrency currency, int requestedPage, int pageSize) {
        return sales.loadSalesHistory(sellerName, currency, requestedPage, pageSize);
    }

    @Override
    public boolean markAsProcessing(long id, String operationId, long startedAt) {
        return !listings.markAsProcessing(id, operationId, startedAt);
    }

    @Override
    public void cancelProcessing(long id, String operationId) {
        listings.cancelProcessing(id, operationId);
    }

    @Override
    public void logTransaction(String operationId, long lotId, String type, String buyerName, String sellerName, AuctionCurrency currency, int amount, double price, String status) {
        payments.logTransaction(operationId, lotId, type, buyerName, sellerName, currency, amount, price, status);
    }

    @Override
    public void updateJournalStatus(String operationId, String status) {
        payments.updateJournalStatus(operationId, status);
    }

    @Override
    public PurchaseResult processPurchase(long lotId, String operationId, String buyerName, int buyAmount, double totalPrice) {
        return purchases.processPurchase(lotId, operationId, buyerName, buyAmount, totalPrice);
    }

    @Override
    public PurchaseResult processInventoryPurchase(long lotId, String operationId, String buyerName) {
        return purchases.processInventoryPurchase(lotId, operationId, buyerName);
    }

    @Override
    public TakeResult processTake(long lotId, String operationId, String playerName) {
        return purchases.processTake(lotId, operationId, playerName);
    }


    @Override
    public List<PendingDelivery> getPendingDeliveries(String playerName) {
        return deliveries.getPendingDeliveries(playerName);
    }

    @Override
    public boolean completeInventoryDelivery(long pendingDeliveryId, String operationId, String playerName, AuctionCurrency currency, List<ItemStack> items) {
        return false;
    }

    @Override
    public boolean completeInventoryDeliveries(String playerName, List<DeliveryConfirmation> deliveries) {
        return this.deliveries.completeInventoryDeliveries(playerName, deliveries);
    }

    @Override
    public void savePendingDelivery(String playerName, AuctionCurrency currency, List<ItemStack> items) {
        deliveries.savePendingDelivery(playerName, currency, items);
    }

    @Override
    public void claimPendingInventoryItems(String playerName, AuctionCurrency currency) {
        deliveries.claimPendingInventoryItems(playerName, currency);
    }

    @Override
    public void savePendingPayout(String playerName, AuctionCurrency currency, double amount) {
        payments.savePendingPayout(playerName, currency, amount);
    }

    @Override
    public boolean prepareWithdrawal(String operationId) {
        return payments.prepareWithdrawal(operationId);
    }

    @Override
    public void refundPurchase(String operationId) {
        payments.refundPurchase(operationId);
    }

    @Override
    public void processPendingPayouts(EconomyProvider economyProvider) {
        payments.processPendingPayouts(economyProvider);
    }

    @Override
    public void recoverStuckLots(EconomyProvider economyProvider, boolean forceAll) {
        payments.recoverStuckLots(economyProvider, forceAll);
    }

    @Override
    public List<TopSeller> getTopSellersByRevenue(AuctionCurrency currency, int limit) {
        return sales.getTopSellersByRevenue(currency, limit);
    }

    @Override
    public List<TopSeller> getTopSellers(AuctionCurrency currency, int limit) {
        return sales.getTopSellers(currency, limit);
    }

    @Override
    public PlayerStats getPlayerStats(String playerName, AuctionCurrency currency) {
        return sales.getPlayerStats(playerName, currency);
    }


    @Override
    public int countActiveBySellerSince(String sellerName, AuctionCurrency currency, long since) {
        return listings.countActiveBySellerSince(sellerName, currency, since);
    }

    @Override
    public void close() {
        asyncExecutor.shutdown();
        try {
            if (!asyncExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                asyncExecutor.shutdownNow();
                if (!asyncExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    plugin.getLogger().severe("Database tasks did not stop; leaving the pool open to avoid interrupting a commit");
                    return;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        if (dataSource == null) return;
        try {
            dataSource.getClass().getMethod("dispose").invoke(dataSource);
        } catch (ReflectiveOperationException e) {
            plugin.getLogger().log(Level.WARNING, "Ошибка закрытия пула подключений", e);
        }
    }

    private Connection open() throws SQLException {
        if (dataSource == null) {
            throw new SQLException("DataSource не инициализирован");
        }
        return sqlRuntime.open(dataSource);
    }

}
