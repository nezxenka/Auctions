package ru.moscow.foxkiss.auction.services.base;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import ru.moscow.foxkiss.FMAuction;
import ru.moscow.foxkiss.auction.AuctionCurrency;
import ru.moscow.foxkiss.auction.AuctionRepository;
import ru.moscow.foxkiss.auction.services.AuctionTransactionService;
import ru.moscow.foxkiss.auction.services.AuctionValidationService;
import ru.moscow.foxkiss.config.ConfigValues;
import ru.moscow.foxkiss.config.interfaces.IConfigManager;
import ru.moscow.foxkiss.scheduler.SchedulerService;
import ru.moscow.foxkiss.utils.PriceFormatter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.logging.Level;

public abstract class BaseAuctionSellService extends BaseAuctionService {

    private final FMAuction plugin;

    protected BaseAuctionSellService(FMAuction plugin, SchedulerService scheduler, IConfigManager configManager, AuctionRepository repository, AuctionTransactionService transactionService) {
        super(scheduler, configManager, repository, transactionService);
        this.plugin = plugin;
    }

    protected void submitListing(Player player, AuctionCurrency currency, double price, ConfigValues.CommissionRule rule, Map<Integer, ItemStack> reserved, BooleanSupplier limitReached, LongSupplier createLot) {
        if (!transactionService.beginListing(player)) return;

        scheduler.runAsync(() -> checkLimit(player, currency, price, rule, reserved, limitReached, createLot));
    }

    private void checkLimit(Player player, AuctionCurrency currency, double price, ConfigValues.CommissionRule rule, Map<Integer, ItemStack> reserved, BooleanSupplier limitReached, LongSupplier createLot) {
        final boolean reached;
        try {
            reached = limitReached.getAsBoolean();
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Не удалось проверить лимит аукциона", e);
            scheduler.runSync(() -> {
                try {
                    if (player.isOnline()) {
                        sendMessage(player, config().messages().databaseError());
                    }
                } finally {
                    transactionService.endListing(player);
                }
            });
            return;
        }

        scheduler.runSync(() -> {
            if (reached) {
                try {
                    if (player.isOnline()) {
                        sendMessage(player, config().messages().limitReached());
                    }
                } finally {
                    transactionService.endListing(player);
                }
                return;
            }
            try {
                chargeAndSubmit(player, currency, price, rule, reserved, createLot);
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.SEVERE, "Ошибка синхронного этапа выставления лота", e);
                transactionService.endListing(player);
            }
        });
    }

    private void chargeAndSubmit(Player player, AuctionCurrency currency, double price, ConfigValues.CommissionRule rule, Map<Integer, ItemStack> reserved, LongSupplier createLot) {
        if (!reservationMatches(player, reserved)) {
            try {
                if (player.isOnline()) {
                    sendMessage(player, config().messages().databaseError());
                }
            } finally {
                transactionService.endListing(player);
            }
            return;
        }

        final double commission;
        try {
            PlayerInventory inventory = player.getInventory();
            for (int slot : reserved.keySet()) {
                inventory.setItem(slot, null);
            }
            commission = chargeCommission(player, currency, rule, price);
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Не удалось списать комиссию за лот", e);
            try {
                restoreItems(player, currency, reserved);
            } finally {
                transactionService.endListing(player);
            }
            return;
        }

        if (commission == -1) {
            try {
                restoreItems(player, currency, reserved);
            } finally {
                transactionService.endListing(player);
            }
            return;
        }

        scheduler.runAsync(() -> persistListing(player, currency, price, rule, reserved, createLot, commission));
    }

    private void persistListing(Player player, AuctionCurrency currency, double price, ConfigValues.CommissionRule rule, Map<Integer, ItemStack> reserved, LongSupplier createLot, double commission) {
        long result;
        try {
            result = createLot.getAsLong();
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Не удалось создать лот на аукционе", e);
            result = -1L;
        }

        final long lotId = result;
        scheduler.runSync(() -> completeListing(
                player, currency, price, rule, reserved, commission, lotId));
    }

    private void completeListing(Player player, AuctionCurrency currency, double price, ConfigValues.CommissionRule rule, Map<Integer, ItemStack> reserved, double commission, long lotId) {
        try {
            if (lotId == AuctionRepository.richi_limit || lotId < 0) {
                rollback(player, currency, rule, commission, reserved);
                if (player.isOnline()) {
                    ConfigValues config = config();
                    String message = lotId == AuctionRepository.richi_limit
                            ? config.messages().limitReached()
                            : config.messages().databaseError();
                    sendMessage(player, message);
                }
                return;
            }

            if (player.isOnline()) {
                ConfigValues config = config();
                ConfigValues.ConfigMessages messages = config.messages();
                String symbol = rule.currency().symbol(config);

                if (commission > 0) {
                    String commissionMessage = messages.commissionCharged()
                            .replace("{amount}", PriceFormatter.format(commission))
                            .replace("{symbol_value}", symbol);
                    sendMessage(player, commissionMessage);
                }
                String formatted = PriceFormatter.format(price) + currency.symbol(config);
                sendMessage(player, messages.sellSuccess()
                        .replace("{symbol_value}", formatted));
            }
        } finally {
            transactionService.endListing(player);
        }
    }

    protected boolean validateListingPrice(Player player, AuctionCurrency currency, double price, AuctionValidationService validation) {
        ConfigValues values = config();
        ConfigValues.ConfigMessages messages = values.messages();

        if (validation.isEconomyUnavailable(currency)) {
            sendMessage(player, messages.economyUnavailable());
            return false;
        }

        if (validation.isInvalidPrice(price, currency)) {
            sendMessage(player, messages.noPrice());
            return false;
        }

        boolean donate = currency == AuctionCurrency.PLAYER_POINTS;

        if (!validation.isPriceOutOfRange(price, currency, donate)) {
            return true;
        }

        double min = validation.getMinPrice(currency, donate);
        double max = validation.getMaxPrice(currency, donate);
        String symbol = currency.symbol(values);

        String message = price < min ? messages.priceTooLow().replace("{min_price}", String.valueOf((long) min)) : messages.priceTooHigh().replace("{max_price}", String.valueOf((long) max));

        sendMessage(player, message.replace("{symbol_value}", symbol));

        return false;
    }

    private boolean reservationMatches(Player player, Map<Integer, ItemStack> reserved) {
        if (!player.isOnline()) return false;
        PlayerInventory inventory = player.getInventory();
        for (Map.Entry<Integer, ItemStack> entry : reserved.entrySet()) {
            if (!entry.getValue().equals(inventory.getItem(entry.getKey()))) {
                return false;
            }
        }
        return true;
    }

    private void rollback(Player player, AuctionCurrency currency, ConfigValues.CommissionRule rule, double commission, Map<Integer, ItemStack> reserved) {
        RuntimeException failure = null;
        try {
            restoreItems(player, currency, reserved);
        } catch (RuntimeException e) {
            failure = e;
            plugin.getLogger().log(Level.SEVERE, "Не удалось восстановить предметы после отмены лота", e);
        }

        if (commission > 0) {
            try {
                boolean refunded = transactionService.depositMoney(player, rule.currency(), commission);
                if (!refunded) {
                    repository.savePendingPayout(player.getName(), rule.currency(), commission);
                    plugin.getLogger().warning(
                            "Не удалось вернуть комиссию игроку " + player.getName() + "; предпринята попытка сохранить ожидающую выплату.");
                }
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.SEVERE, "Возврат комиссии требует ручной сверки: " + player.getName(), e);
                if (failure == null) failure = e;
                else failure.addSuppressed(e);
            }
        }

        if (failure != null) throw failure;
    }

    private void restoreItems(Player player, AuctionCurrency currency, Map<Integer, ItemStack> reserved) {
        PlayerInventory inventory = player.getInventory();
        List<ItemStack> displaced = null;

        for (Map.Entry<Integer, ItemStack> entry : reserved.entrySet()) {
            int slot = entry.getKey();
            ItemStack item = entry.getValue();
            ItemStack current = inventory.getItem(slot);
            if (current == null || current.getType().isAir()) {
                inventory.setItem(slot, item.clone());
            } else {
                if (displaced == null) displaced = new ArrayList<>();
                displaced.add(item.clone());
            }
        }

        if (displaced != null) {
            transactionService.returnItems(player, currency, displaced, configManager);
        }
    }
}