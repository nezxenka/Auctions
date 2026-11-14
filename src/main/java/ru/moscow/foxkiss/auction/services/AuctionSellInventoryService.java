package ru.moscow.foxkiss.auction.services;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import ru.moscow.foxkiss.FMAuction;
import ru.moscow.foxkiss.auction.AuctionCurrency;
import ru.moscow.foxkiss.auction.AuctionRepository;
import ru.moscow.foxkiss.auction.services.base.BaseAuctionSellService;
import ru.moscow.foxkiss.config.ConfigValues;
import ru.moscow.foxkiss.config.interfaces.IConfigManager;
import ru.moscow.foxkiss.permissions.LimitService;
import ru.moscow.foxkiss.scheduler.SchedulerService;

import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

public final class AuctionSellInventoryService extends BaseAuctionSellService {

    private final LimitService limitService;
    private final AuctionValidationService validationService;

    public AuctionSellInventoryService(FMAuction plugin, SchedulerService scheduler, IConfigManager configManager, AuctionRepository repository, LimitService limitService, AuctionValidationService validationService, AuctionTransactionService transactionService) {
        super(plugin, scheduler, configManager, repository, transactionService);
        this.limitService = limitService;
        this.validationService = validationService;
    }

    public void sellInventory(Player player, AuctionCurrency currency, double price) {
        ConfigValues values = config();
        ConfigValues.InventorySellingConfig inv = values.inventorySelling();

        boolean enabled = currency == AuctionCurrency.VAULT
                ? inv.moneyAuc()
                : inv.rublesAuc();

        if (!enabled) {
            sendMessage(player, values.messages().economyUnavailable());
            return;
        }

        if (!validateListingPrice(player, currency, price, validationService)) {
            return;
        }

        String symbol = currency.symbol(values);

        if (validationService.isInvalidPrice(price, currency)) {
            sendMessage(player, config().messages().noPrice());
            return;
        }

        boolean isDonate = currency == AuctionCurrency.PLAYER_POINTS;
        if (validationService.isPriceOutOfRange(price, currency, isDonate)) {
            double min = validationService.getMinPrice(currency, isDonate);
            double max = validationService.getMaxPrice(currency, isDonate);
            String msg;
            if (price < min) {
                msg = config().messages().priceTooLow().replace("{min_price}", String.valueOf((long) min)).replace("{symbol_value}", symbol);
            } else {
                msg = config().messages().priceTooHigh().replace("{max_price}", String.valueOf((long) max)).replace("{symbol_value}", symbol);
            }
            sendMessage(player, msg);
            return;
        }

        List<ItemStack> contents = collectInventory(player);
        int itemCount = 0;
        for (ItemStack it : contents) {
            if (it != null && it.getType() != Material.AIR) itemCount++;
        }

        if (itemCount == 0) {
            sendMessage(player, config().messages().air());
            return;
        }

        if (itemCount > inv.maxItems()) {
            sendMessage(player, config().messages().limitReached());
            return;
        }

        if (itemCount < inv.minItems()) {
            sendMessage(player, config().messages().inventoryMinItems().replace("{min_items}", String.valueOf(inv.minItems())));
            return;
        }

        if (price < inv.minPrice()) {
            sendMessage(player, config().messages().priceTooLow()
                    .replace("{min_price}", String.valueOf(inv.minPrice()))
                    .replace("{symbol_value}", symbol));
            return;
        }
        if (price > inv.maxPrice()) {
            sendMessage(player, config().messages().priceTooHigh()
                    .replace("{max_price}", String.valueOf(inv.maxPrice()))
                    .replace("{symbol_value}", symbol));
            return;
        }

        int limit = limitService.getLimit(player, currency);
        String playerName = player.getName();
        List<Material> shulkerMaterials = inv.shulkerMaterials();
        Material displayMaterial = shulkerMaterials.get(ThreadLocalRandom.current().nextInt(shulkerMaterials.size()));
        ItemStack displayItem = new ItemStack(displayMaterial, 1);
        Map<Integer, ItemStack> reserved = new HashMap<>();
        for (int slot = 0; slot < contents.size(); slot++) {
            ItemStack item = contents.get(slot);
            if (item != null && !item.getType().isAir()) reserved.put(slot, item);
        }

        submitListing(player, currency, price, config().commission().sellInventory(),
                reserved,
                () -> repository.countActiveBySellerSince(playerName, currency, 0) >= limit,
                () -> repository.createInventoryWithinLimit(playerName, currency, displayItem, contents, price, limit));
    }

    private List<ItemStack> collectInventory(Player player) {
        List<ItemStack> contents = new ArrayList<>();
        for (ItemStack item : player.getInventory().getStorageContents()) {
            contents.add(item == null ? null : item.clone());
        }
        for (ItemStack item : player.getInventory().getArmorContents()) {
            contents.add(item == null ? null : item.clone());
        }
        ItemStack offHand = player.getInventory().getItemInOffHand();
        contents.add(offHand.clone());
        return contents;
    }
}
