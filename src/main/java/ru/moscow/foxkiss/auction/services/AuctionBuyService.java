package ru.moscow.foxkiss.auction.services;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import ru.moscow.foxkiss.auction.*;
import ru.moscow.foxkiss.auction.services.base.BaseAuctionService;
import ru.moscow.foxkiss.config.interfaces.IConfigManager;
import ru.moscow.foxkiss.gui.ItemDisplayFactory;
import ru.moscow.foxkiss.scheduler.SchedulerService;
import ru.moscow.foxkiss.utils.ItemUtils;
import ru.moscow.foxkiss.utils.PriceFormatter;

import java.util.UUID;
import java.util.function.Consumer;

public final class AuctionBuyService extends BaseAuctionService {
    private final AuctionValidationService validationService;
    private final ItemDisplayFactory itemFactory;

    public AuctionBuyService(SchedulerService scheduler, IConfigManager configManager, AuctionRepository repository, AuctionValidationService validationService, AuctionTransactionService transactionService, ItemDisplayFactory itemFactory) {
        super(scheduler, configManager, repository, transactionService);
        this.validationService = validationService;
        this.itemFactory = itemFactory;
    }

    public void buy(Player buyer, long lotId, int amount, Consumer<Boolean> callback) {
        begin(buyer, lotId, amount, false, callback);
    }

    public void buyInventory(Player buyer, long lotId, Consumer<Boolean> callback) {
        begin(buyer, lotId, 1, true, callback);
    }

    private void begin(Player buyer, long lotId, int amount, boolean inventory, Consumer<Boolean> callback) {
        String operationId = UUID.randomUUID().toString();
        String buyerName = buyer.getName();
        scheduler.runAsync(() -> {
            if (repository.markAsProcessing(lotId, operationId, System.currentTimeMillis())) {
                scheduler.runSync(() -> finishWithMessage(buyer, config().messages().noId(), callback));
                return;
            }
            try {
                AuctionItem item = repository.findById(lotId).orElse(null);
                if (item == null || (item.type() == AuctionLotType.INVENTORY) != inventory) {
                    repository.cancelProcessing(lotId, operationId);
                    scheduler.runSync(() -> finishWithMessage(buyer, config().messages().noId(), callback));
                    return;
                }
                int count = inventory ? 1 : Math.max(1, Math.min(amount, item.amount()));
                double price = inventory ? item.price() : item.calculatePurchasePrice(count);
                repository.logTransaction(operationId, lotId, inventory ? "BUY_INVENTORY" : "BUY",
                        buyerName, item.sellerName(), item.currency(), count, price, "STARTED");
                scheduler.runSync(() -> validate(buyer, item, count, price, operationId, callback));
            } catch (RuntimeException e) {
                repository.cancelProcessing(lotId, operationId);
                scheduler.runSync(() -> finishWithMessage(buyer, config().messages().databaseError(), callback));
                throw e;
            }
        });
    }

    private void validate(Player buyer, AuctionItem item, int count, double price, String op, Consumer<Boolean> callback) {
        String error = null;
        if (!buyer.isOnline()) error = config().messages().noId();
        else if (validationService.isOwner(buyer, item)) error = config().messages().noOwn();
        else if (validationService.isExpired(item)) error = config().messages().noId();
        else if (item.type() == AuctionLotType.ITEM) {
            ItemStack purchased = item.itemStack().clone();
            purchased.setAmount(count);
            if (!validationService.canFit(buyer, purchased)) error = config().messages().inventoryFull();
        }
        if (error == null && validationService.hasInsufficientFunds(buyer, item.currency(), price)) error = config().messages().nomoney();
        if (error != null) { cancel(buyer, item.id(), op, error, callback); return; }
        String buyerName = buyer.getName();
        scheduler.runAsync(() -> {
            if (!repository.prepareWithdrawal(op)) {
                cancel(buyer, item.id(), op, config().messages().databaseError(), callback);
                return;
            }
            scheduler.runSync(() -> {
                if (!buyer.isOnline()) { cancel(buyer, item.id(), op, config().messages().noId(), callback); return; }
                if (!transactionService.withdrawMoney(buyer, item.currency(), price)) {
                    cancel(buyer, item.id(), op, config().messages().nomoney(), callback);
                    return;
                }
                scheduler.runAsync(() -> {
                    repository.updateJournalStatus(op, "MONEY_WITHDRAWN");
                    PurchaseResult result = item.type() == AuctionLotType.INVENTORY
                            ? repository.processInventoryPurchase(item.id(), op, buyerName)
                            : repository.processPurchase(item.id(), op, buyerName, count, price);
                    if (!result.success()) repository.refundPurchase(op);
                    repository.processPendingPayouts(transactionService.getEconomyProvider());
                    scheduler.runSync(() -> {
                        if (!result.success()) { finishWithMessage(buyer, config().messages().databaseError(), callback); return; }
                        itemFactory.invalidateLotCache(item.id());
                        transactionService.deliverPending(buyer, null, configManager);
                        String name = item.type() == AuctionLotType.INVENTORY ? "Инвентарь" : ItemUtils.getItemDisplayName(result.purchasedItem());
                        String formatted = PriceFormatter.format(price, item.currency());
                        String symbol = item.currency().symbol(config());
                        sendMessage(buyer, config().messages().yspex().replace("{item_name}", name).replace("{symbol_value}", formatted + symbol));
                        int totalItems = count;
                        if (item.type() == AuctionLotType.INVENTORY) {
                            totalItems = 0;
                            for (ItemStack content : item.inventoryContents()) {
                                if (content != null && !content.getType().isAir()) totalItems += content.getAmount();
                            }
                        }
                        Player seller = Bukkit.getPlayerExact(item.sellerName());
                        if (seller != null) sendMessage(seller, config().messages().buySeller()
                                .replace("{buyer}", buyerName).replace("{item_name}", name)
                                .replace("{amount}", String.valueOf(totalItems))
                                .replace("{price}", formatted).replace("{symbol_value}", symbol));
                        if (callback != null) callback.accept(true);
                    });
                });
            });
        });
    }

    private void cancel(Player buyer, long lotId, String op, String message, Consumer<Boolean> callback) {
        scheduler.runAsync(() -> {
            repository.updateJournalStatus(op, "CANCELLED");
            repository.cancelProcessing(lotId, op);
            scheduler.runSync(() -> finishWithMessage(buyer, message, callback));
        });
    }
}
