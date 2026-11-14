package ru.moscow.foxkiss.auction;

import org.bukkit.entity.Player;
import ru.moscow.foxkiss.FMAuction;
import ru.moscow.foxkiss.auction.services.AuctionBuyService;
import ru.moscow.foxkiss.auction.services.AuctionSellInventoryService;
import ru.moscow.foxkiss.auction.services.AuctionSellService;
import ru.moscow.foxkiss.auction.services.AuctionTakeService;
import ru.moscow.foxkiss.auction.services.AuctionTransactionService;
import ru.moscow.foxkiss.auction.services.AuctionValidationService;
import ru.moscow.foxkiss.config.interfaces.IConfigManager;
import ru.moscow.foxkiss.economy.EconomyProvider;
import ru.moscow.foxkiss.gui.ItemDisplayFactory;
import ru.moscow.foxkiss.permissions.LimitService;
import ru.moscow.foxkiss.scheduler.SchedulerService;

import java.util.function.Consumer;

public record AuctionService(IConfigManager configManager, AuctionSellService sellService, AuctionSellInventoryService sellInventoryService, AuctionBuyService buyService, AuctionTakeService takeService, AuctionRepository repository, AuctionTransactionService transactionService, SchedulerService scheduler) {

    public static AuctionService create(FMAuction plugin, IConfigManager configManager, AuctionRepository repository, EconomyProvider economyProvider, LimitService limitService, ItemDisplayFactory itemFactory, SchedulerService scheduler) {
        AuctionValidationService validationService = new AuctionValidationService(configManager, economyProvider);
        AuctionTransactionService transactionService = new AuctionTransactionService(scheduler, repository, economyProvider, itemFactory);

        AuctionSellService sellService = new AuctionSellService(plugin, scheduler, configManager, repository, limitService, validationService, transactionService);
        AuctionSellInventoryService sellInventoryService = new AuctionSellInventoryService(plugin,scheduler, configManager, repository, limitService, validationService, transactionService);

        AuctionBuyService buyService = new AuctionBuyService(scheduler, configManager, repository, validationService, transactionService, itemFactory);

        AuctionTakeService takeService = new AuctionTakeService(scheduler, configManager, repository, validationService, transactionService, itemFactory);

        return new AuctionService(configManager, sellService, sellInventoryService, buyService, takeService, repository, transactionService, scheduler);
    }

    public void sell(Player player, AuctionCurrency currency, double price) {
        sellService.sell(player, currency, price);
    }

    public void sellInventory(Player player, AuctionCurrency currency, double price) {
        sellInventoryService.sellInventory(player, currency, price);
    }

    public void buy(Player buyer, long lotId, int amount, Consumer<Boolean> callback) {
        buyService.buy(buyer, lotId, amount, callback);
    }

    public void buyInventory(Player buyer, long lotId, Consumer<Boolean> callback) {
        buyService.buyInventory(buyer, lotId, callback);
    }

    public void take(Player player, long lotId, Consumer<Boolean> callback) {
        takeService.take(player, lotId, callback);
    }

    public void processPendingDeliveries(Player player) {
        transactionService.deliverPending(player, null, configManager);
    }

    public void processPendingPayouts(Player player) {
        scheduler.runAsync(() -> repository.processPendingPayouts(transactionService.getEconomyProvider()));
    }

    public void takePendingInventoryItems(Player player, AuctionCurrency currency) {
        transactionService.deliverPending(player, currency, configManager);
    }
}
