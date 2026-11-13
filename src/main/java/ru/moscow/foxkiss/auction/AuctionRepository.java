package ru.moscow.foxkiss.auction;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import org.bukkit.inventory.ItemStack;
import ru.moscow.foxkiss.economy.EconomyProvider;
import ru.moscow.foxkiss.gui.AuctionViewType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface AuctionRepository {
    long richi_limit = 0;

    void init();

    long createWithinLimit(String sellerName, AuctionCurrency currency, ItemStack itemStack, double price, int limit);

    long createInventoryWithinLimit(String sellerName, AuctionCurrency currency, ItemStack displayItem, List<ItemStack> contents, double price, int limit);

    Optional<AuctionItem> findById(long id);

    List<Long> loadInventoryLotIds(AuctionCurrency currency);

    MenuData loadMenuData(AuctionCurrency currency, String playerName, int maxDays, int page, int pageSize, AuctionSort sort, String category, String sellerFilter, String searchFilter, AuctionViewType viewType);

    SalesHistoryPage loadSalesHistory(String sellerName, AuctionCurrency currency, int page, int pageSize);

    List<TopSeller> getTopSellers(AuctionCurrency currency, int limit);
    default List<TopSeller> getTopSellersByRevenue(AuctionCurrency currency, int limit) {
        List<TopSeller> sellers = new ArrayList<>(getTopSellers(currency, Integer.MAX_VALUE));
        sellers.sort(Comparator.comparingDouble(TopSeller::totalEarned).reversed().thenComparing(TopSeller::name));
        return List.copyOf(sellers.subList(0, Math.min(limit, sellers.size())));
    }

    PlayerStats getPlayerStats(String playerName, AuctionCurrency currency);

    PlayerProfile getPlayerProfile(String playerName);

    void savePlayerProfile(String playerName, String discord, String storeName);

    Optional<String> findProfileOwner(String value);

    boolean markAsProcessing(long id, String operationId, long startedAt);

    void cancelProcessing(long id, String operationId);

    void logTransaction(String operationId, long lotId, String type, String buyerName, String sellerName, AuctionCurrency currency, int amount, double price, String status);

    void updateJournalStatus(String operationId, String status);

    PurchaseResult processPurchase(long lotId, String operationId, String buyerName, int buyAmount, double totalPrice);

    PurchaseResult processInventoryPurchase(long lotId, String operationId, String buyerName);

    TakeResult processTake(long lotId, String operationId, String playerName);

    List<PendingDelivery> getPendingDeliveries(String playerName);

    boolean completeInventoryDelivery(long pendingDeliveryId, String operationId, String playerName, AuctionCurrency currency, List<ItemStack> items);

    default boolean completeInventoryDeliveries(String playerName, List<DeliveryConfirmation> deliveries) {
        if (deliveries.size() != 1) throw new UnsupportedOperationException("Хранилище не поддерживает атомарное подтверждение пакета");
        DeliveryConfirmation delivery = deliveries.getFirst();
        return completeInventoryDelivery(delivery.id(), null, playerName, delivery.currency(), delivery.items());
    }

    void savePendingPayout(String playerName, AuctionCurrency currency, double amount);

    void processPendingPayouts(EconomyProvider economyProvider);

    void recoverStuckLots(EconomyProvider economyProvider, boolean forceAll);

    int countActiveBySellerSince(String sellerName, AuctionCurrency currency, long since);

    boolean prepareWithdrawal(String operationId);

    void refundPurchase(String operationId);

    void claimPendingInventoryItems(String playerName, AuctionCurrency currency);

    void savePendingDelivery(String playerName, AuctionCurrency currency, List<ItemStack> items);

    void close();

    record TopSeller(String name, int soldCount, double totalEarned) {}
    record DeliveryConfirmation(long id, AuctionCurrency currency, List<ItemStack> items) {}
    record PlayerStats(int soldCount, double totalEarned) {}
    record MenuData(List<AuctionItem> items, int totalCount, int sellingCount, int expiredCount, Map<String, PlayerProfile> profiles, Map<String, Integer> materialLots, Long2ObjectMap<String> itemGroups) { }
    record SalesHistoryPage(List<SaleHistoryEntry> entries, int totalCount, int page, int totalPages) {}
}
