package ru.moscow.foxkiss.gui;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;
import ru.moscow.foxkiss.auction.AuctionCurrency;
import ru.moscow.foxkiss.auction.AuctionItem;
import ru.moscow.foxkiss.auction.AuctionService;
import ru.moscow.foxkiss.auction.AuctionSort;
import ru.moscow.foxkiss.config.ConfigValues;
import ru.moscow.foxkiss.config.interfaces.IConfigManager;
import ru.moscow.foxkiss.gui.actions.Action;
import ru.moscow.foxkiss.gui.enums.ActionType;
import ru.moscow.foxkiss.gui.holders.AuctionMenuHolder;
import ru.moscow.foxkiss.gui.holders.ConfirmMenuHolder;
import ru.moscow.foxkiss.gui.holders.InventoryMenuHolder;
import ru.moscow.foxkiss.gui.holders.QuantityMenuHolder;
import ru.moscow.foxkiss.gui.holders.SalesHistoryMenuHolder;
import ru.moscow.foxkiss.utils.PlaceholderUtils;
import ru.moscow.foxkiss.utils.TextUtils;

import java.util.*;

public final class AuctionMenuListener implements Listener {
    private final IConfigManager configManager;
    private final AuctionMenu auctionMenu;
    private final AuctionService auctionService;

    private final Map<AuctionCurrency, List<String>> cats = new EnumMap<>(AuctionCurrency.class);
    private final Object2LongOpenHashMap<UUID> quantityMessageCooldowns = new Object2LongOpenHashMap<>();
    private final Object2LongOpenHashMap<UUID> updateCooldowns = new Object2LongOpenHashMap<>();
    private final Object2LongOpenHashMap<UUID> takeCooldowns = new Object2LongOpenHashMap<>();
    private final Object2LongOpenHashMap<UUID> selectionTimes = new Object2LongOpenHashMap<>();
    private final Object2IntOpenHashMap<UUID> cooldownMessageSkips = new Object2IntOpenHashMap<>();

    public AuctionMenuListener(IConfigManager configManager, AuctionMenu auctionMenu, AuctionService auctionService) {
        this.configManager = configManager;
        this.auctionMenu = auctionMenu;
        this.auctionService = auctionService;
        reloadCategories();
    }

    private final Map<AuctionCurrency, List<AuctionSort>> sortOrders = new EnumMap<>(AuctionCurrency.class);

    public void reloadCategories() {
        cats.clear();
        sortOrders.clear();
        AuctionSort[] availableSorts = AuctionSort.values();
        for (AuctionCurrency currency : AuctionCurrency.values()) {
            List<AuctionSort> sorts = new ArrayList<>();
            List<String> sortLore = configManager.getConfigValues().gui(currency).navigation().sort().lore();
            if (sortLore.isEmpty()) Collections.addAll(sorts, AuctionSort.values());
            else {
                for (String line : sortLore) {
                    for (AuctionSort sort : availableSorts) {
                        if (!sorts.contains(sort) && line.contains("{sort:" + sort.name() + "}")) {
                            sorts.add(sort);
                        }
                    }
                }
            }
            sortOrders.put(currency, List.copyOf(sorts));
            List<String> ids = getStrings(currency);
            cats.put(currency, List.copyOf(ids));
        }
    }

    private @NotNull List<String> getStrings(AuctionCurrency currency) {
        List<String> ids = new ArrayList<>();
        List<ConfigValues.CategoryEntry> entries = configManager.getConfigValues().gui(currency).categoryMenu().entries();
        if (entries == null) {
            ids.add("all");
            for (String id : configManager.getConfigValues().categories().keySet()) if (!id.equals("all")) ids.add(id);
        } else {
            for (ConfigValues.CategoryEntry entry : entries) if (!entry.id().isEmpty()) ids.add(entry.id());
        }
        return ids;
    }

    @EventHandler(ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (event.getInventory().getType() != InventoryType.CHEST) return;

        if (!(event.getInventory().getHolder() instanceof AuctionMenuHolder holder)) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;

        event.setCancelled(true);

        int slot = event.getRawSlot();
        if (slot < 0 || slot >= event.getInventory().getSize()) return;

        String materialGroup = holder.getMaterialGroups().isEmpty() ? null : holder.getMaterialGroups().get(slot);
        if (materialGroup != null) {
            auctionMenu.openMaterialOffers(player, holder, materialGroup);
            return;
        }

        Long lotId = holder.getLot(slot);
        if (lotId != null) {
            handleLotClick(player, holder, slot, lotId, event.isRightClick());
            return;
        }

        List<Action> actions = holder.getActions(slot);
        if (actions == null || actions.isEmpty()) return;

        boolean close = false;
        for (Action action : actions) {
            if (action.type() == ActionType.CLOSE) {
                close = true;
                break;
            }
        }

        switch (holder) {
            case QuantityMenuHolder qh -> handleQuantityActions(player, qh, slot, actions);
            case ConfirmMenuHolder ch -> handleConfirmActions(player, ch, actions);
            case InventoryMenuHolder ih -> handleInventoryActions(player, ih, actions);
            default -> handleNavigationActions(player, holder, actions, event.isRightClick());
        }

        if (close && player.getOpenInventory().getTopInventory().getHolder() == holder) {
            player.closeInventory();
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof AuctionMenuHolder)) return;
        int topSize = event.getView().getTopInventory().getSize();
        for (int slot : event.getRawSlots()) {
            if (slot < topSize) {
                event.setCancelled(true);
                return;
            }
        }
    }

    private void handleQuantityActions(Player player, QuantityMenuHolder holder, int slot, List<Action> actions) {
        boolean buy = false;
        int amount = holder.getSelectedAmount();
        boolean changed = false;
        for (Action action : actions) {
            switch (action.type()) {
                case DECREASE_10 -> { amount -= 10; changed = true; }
                case DECREASE_1 -> { amount--; changed = true; }
                case INCREASE_1 -> { amount++; changed = true; }
                case INCREASE_10 -> { amount += 10; changed = true; }
                case BUY -> { if (slot == configManager.getConfigValues().quantityMenu().slotAmount()) buy = true; }
                default -> { }
            }
        }

        if (buy) {
            auctionService.buy(player, holder.getLotId(), holder.getSelectedAmount(), success -> {
                if (!success || !player.isOnline()) return;
                auctionMenu.openMain(player, holder.getCurrency(), 0, null, null, null, null);
            });
            return;
        }

        if (!changed) return;

        if (amount < 1) amount = 1;

        if (amount > holder.getMaxAmount()) {
            UUID uuid = player.getUniqueId();
            long now = System.currentTimeMillis();
            long last = quantityMessageCooldowns.getLong(uuid);
            if (now - last >= 5000L) {
                sendMessage(player, configManager.getConfigValues().messages().quantityExceeded()
                        .replace("{max}", String.valueOf(holder.getMaxAmount())));
                quantityMessageCooldowns.put(uuid, now);
            }
            amount = holder.getMaxAmount();
        }

        holder.setSelectedAmount(amount);
        AuctionItem auctionItem = holder.getAuctionItem();
        if (auctionItem != null) {
            auctionMenu.updateQuantityDisplay(holder.getInventory(), holder, auctionItem);
        }
    }

    private void handleConfirmActions(Player player, ConfirmMenuHolder holder, List<Action> actions) {
        boolean confirmed = false;
        for (Action action : actions) {
            if (action.type() == ActionType.CONFIRM) {
                confirmed = true;
                break;
            }
        }

        if (confirmed) {
            if (holder.isInventoryBuy()) {
                auctionService.buyInventory(player, holder.getConfirmLotId(), success -> {
                    if (!success || !player.isOnline()) return;
                    auctionMenu.openMain(player, holder.getCurrency(), 0, null, null, null, null);
                });
            } else {
                auctionService.buy(player, holder.getConfirmLotId(), holder.getConfirmAmount(), success -> {
                    if (!success || !player.isOnline()) return;
                    auctionMenu.openMain(player, holder.getCurrency(), 0, null, null, null, null);
                });
            }
        }
    }

    private void handleInventoryActions(Player player, InventoryMenuHolder holder, List<Action> actions) {
        for (Action action : actions) {
            switch (action.type()) {
                case CLOSE -> { }
                case INVENTORY_PREV -> {
                    if (holder.getPrevLotId() != -1L) {
                        auctionMenu.openInventoryView(player, holder.getCurrency(), holder.getPrevLotId());
                    }
                }
                case INVENTORY_NEXT -> {
                    if (holder.getNextLotId() != -1L) {
                        auctionMenu.openInventoryView(player, holder.getCurrency(), holder.getNextLotId());
                    }
                }
                case BUY -> {
                    if (configManager.getConfigValues().confirmMenu().enabled()) {
                        auctionMenu.openConfirm(player, holder.getCurrency(), holder.getLotId(), 1, true);
                    } else {
                        auctionService.buyInventory(player, holder.getLotId(), success -> {
                            if (!success || !player.isOnline()) return;
                            auctionMenu.openMain(player, holder.getCurrency(), 0, null, null, null, null);
                        });
                    }
                }
                default -> { }
            }
        }
    }

    private void handleNavigationActions(Player player, AuctionMenuHolder holder, List<Action> actions, boolean rightClick) {
        ActionType action = actions.getFirst().type();

        if (action == ActionType.REFRESH) {
            if (tryUseCooldown(player, updateCooldowns,
                    configManager.getConfigValues().cooldowns().updateAuctionSeconds())) {
                return;
            }
        }

        int currentPage = holder.getPage();
        int totalPages = holder.getTotalPages();

        switch (action) {
            case MAIN -> auctionMenu.openMain(player, holder.getCurrency(), 0, null, null, null, null);

            case SELLING -> auctionMenu.openSelling(player, holder.getCurrency(), 0);

            case HISTORY -> auctionMenu.openSalesHistory(player, holder.getCurrency(), 0);

            case PROFILE -> auctionMenu.openProfile(player, holder.getCurrency());

            case DISCORD -> auctionMenu.openProfileInput(player, true);

            case STORE -> auctionMenu.openProfileInput(player, false);

            case EXPIRED -> auctionMenu.openExpired(player, holder.getCurrency(), 0);

            case PREVIOUS -> {
                if (currentPage <= 0) return;
                if (holder instanceof SalesHistoryMenuHolder) {
                    auctionMenu.openSalesHistory(player, holder.getCurrency(), currentPage - 1);
                    return;
                }
                holder.setPage(currentPage - 1);
                auctionMenu.refreshInventory(player, holder);
            }
            case NEXT -> {
                if (currentPage + 1 >= totalPages) return;
                if (holder instanceof SalesHistoryMenuHolder) {
                    auctionMenu.openSalesHistory(player, holder.getCurrency(), currentPage + 1);
                    return;
                }
                holder.setPage(currentPage + 1);
                auctionMenu.refreshInventory(player, holder);
            }
            case REFRESH -> auctionMenu.refreshInventory(player, holder);

            case SORT -> {
                if (auctionMenu.isRefreshing(player) || trySwitchSelection(player.getUniqueId())) return;
                List<AuctionSort> order = sortOrders.get(holder.getCurrency());
                if (order.isEmpty()) return;
                int index = order.indexOf(holder.getSort());
                int next = index < 0 ? (rightClick ? order.size() - 1 : 0)
                        : Math.floorMod(index + (rightClick ? -1 : 1), order.size());
                AuctionSort newSort = order.get(next);
                holder.setSort(newSort);
                holder.setPage(0);
                auctionMenu.refreshInventory(player, holder);
            }
            case CATEGORIES -> {
                if (auctionMenu.isRefreshing(player) || trySwitchSelection(player.getUniqueId())) return;
                if (cats.get(holder.getCurrency()).isEmpty()) return;
                String newCat = switchCategory(holder.getCategory(), holder.getCurrency(), rightClick ? -1 : 1);
                holder.setCategory(newCat);
                holder.setPage(0);
                auctionMenu.refreshInventory(player, holder);
            }
            default -> {}
        }
    }

    private void handleLotClick(Player player, AuctionMenuHolder holder, int slot, long lotId, boolean rightClick) {
        if (holder.isInventoryLot(slot) && holder.getViewType() != AuctionViewType.SELLING && holder.getViewType() != AuctionViewType.EXPIRED) {
            auctionMenu.openInventoryView(player, holder.getCurrency(), lotId);
            return;
        }

        if (holder.getViewType() == AuctionViewType.SELLING || holder.getViewType() == AuctionViewType.EXPIRED) {
            if (tryUseCooldown(player, takeCooldowns, configManager.getConfigValues().cooldowns().takeItemSeconds())) {
                return;
            }

            auctionService.take(player, lotId, success -> {
                if (!success || !player.isOnline()) return;
                auctionMenu.refreshAfterTake(player, holder, slot, lotId);
            });
            return;
        }

        Integer amount = holder.getLotAmount(slot);
        if (amount == null || amount <= 0) return;

        if (rightClick && amount > 1) {
            auctionMenu.openQuantity(player, holder.getCurrency(), lotId);
            return;
        }

        if (configManager.getConfigValues().confirmMenu().enabled() && !rightClick) {
            auctionMenu.openConfirm(player, holder.getCurrency(), lotId, amount, false);
            return;
        }

        auctionService.buy(player, lotId, amount, success -> {
            if (!success || !player.isOnline()) return;
            auctionMenu.refreshInventory(player, holder);
        });
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        auctionService.transactionService().onPlayerQuit(event.getPlayer());
        UUID uuid = event.getPlayer().getUniqueId();
        updateCooldowns.removeLong(uuid);
        takeCooldowns.removeLong(uuid);
        selectionTimes.removeLong(uuid);
        auctionMenu.onPlayerQuit(uuid);
        cooldownMessageSkips.removeInt(uuid);
        quantityMessageCooldowns.removeLong(uuid);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        auctionService.processPendingDeliveries(event.getPlayer());
        auctionService.processPendingPayouts(event.getPlayer());
    }

    private boolean tryUseCooldown(Player player, Object2LongOpenHashMap<UUID> cooldowns, double seconds) {
        ConfigValues.Cooldowns settings = configManager.getConfigValues().cooldowns();
        if (!settings.cooldownEnabled()) return false;

        long cooldownMillis = Math.round(seconds * 1000D);
        if (cooldownMillis <= 0L) return false;

        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();

        if (now - cooldowns.getLong(uuid) >= cooldownMillis) {
            cooldowns.put(uuid, now);
            cooldownMessageSkips.removeInt(uuid);
            return false;
        }

        if (!settings.cooldownMessageEnabled()) return true;

        int skips = cooldownMessageSkips.getInt(uuid);

        if (skips > 0) {
            cooldownMessageSkips.put(uuid, skips - 1);
            return true;
        }

        cooldownMessageSkips.put(uuid, 2);

        sendMessage(player, configManager.getConfigValues().messages().cooldownItem());

        return true;
    }

    private boolean trySwitchSelection(UUID uuid) {
        long now = System.nanoTime();
        long previous = selectionTimes.getLong(uuid);
        if (selectionTimes.containsKey(uuid) && now - previous < 10_000_000L) return true;
        selectionTimes.put(uuid, now);
        return false;
    }

    private String switchCategory(String current, AuctionCurrency currency, int direction) {
        List<String> categories = cats.get(currency);

        if (categories == null || categories.isEmpty()) {
            return "all";
        }

        int index = current == null ? -1 : categories.indexOf(current.toLowerCase(Locale.ROOT));

        if (index < 0) {
            return direction > 0 ? categories.getFirst() : categories.getLast();
        }

        return categories.get(Math.floorMod(index + direction, categories.size()));
    }

    private void sendMessage(CommandSender sender, String message) {
        sender.sendMessage(TextUtils.component(PlaceholderUtils.apply(sender, message, configManager)));
    }
}
