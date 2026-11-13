package ru.moscow.foxkiss.gui;

import java.util.logging.Level;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.scheduler.BukkitTask;
import ru.moscow.foxkiss.FMAuction;
import ru.moscow.foxkiss.auction.AuctionCurrency;
import ru.moscow.foxkiss.auction.AuctionItem;
import ru.moscow.foxkiss.auction.AuctionRepository;
import ru.moscow.foxkiss.auction.AuctionSort;
import ru.moscow.foxkiss.config.interfaces.IConfigManager;
import ru.moscow.foxkiss.gui.builder.MenuBuilder;
import ru.moscow.foxkiss.gui.holders.AuctionMenuHolder;
import ru.moscow.foxkiss.gui.holders.InventoryMenuHolder;
import ru.moscow.foxkiss.gui.holders.QuantityMenuHolder;
import ru.moscow.foxkiss.gui.holders.SalesHistoryMenuHolder;
import ru.moscow.foxkiss.scheduler.SchedulerService;
import ru.moscow.foxkiss.utils.PlaceholderUtils;
import ru.moscow.foxkiss.utils.TextUtils;

import java.util.*;

public final class AuctionMenu {
    private final FMAuction plugin;
    private final Map<UUID, TakeRefresh> takeRefreshes = new HashMap<>();
    private record TakeRefresh(AuctionMenuHolder holder, BukkitTask task) {}
    private final IConfigManager configManager;
    private final AuctionRepository repository;
    private final PlayerPreferences playerPreferences;
    private final MenuBuilder builder;
    private final QuantityMenuController quantityController;
    private final ConfirmMenuController confirmController;
    private final ItemDisplayFactory itemFactory;
    private final SchedulerService scheduler;
    private final SalesHistoryMenu salesHistoryMenu;
    private final ProfileMenu profileMenu;
    private final ProfileInputService profileInputService;

    private final Object2LongOpenHashMap<UUID> requestStartTime = new Object2LongOpenHashMap<>();

    public AuctionMenu(FMAuction plugin, IConfigManager configManager, AuctionRepository repository, PlayerPreferences playerPreferences, ItemDisplayFactory itemFactory, SchedulerService scheduler, ProfileInputService profileInputService) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.repository = repository;
        this.playerPreferences = playerPreferences;
        this.scheduler = scheduler;
        this.itemFactory = itemFactory;
        this.builder = new MenuBuilder(configManager, itemFactory);
        this.quantityController = new QuantityMenuController(configManager, itemFactory, builder);
        this.confirmController = new ConfirmMenuController(configManager, itemFactory, builder);
        this.salesHistoryMenu = new SalesHistoryMenu(plugin, configManager, repository, itemFactory, scheduler);
        this.profileMenu = new ProfileMenu(configManager, repository, itemFactory, scheduler);
        this.profileInputService = profileInputService;
    }

    public void openInventoryView(Player player, AuctionCurrency currency, long lotId) {
        scheduler.runAsync(() -> {
            AuctionItem lot = repository.findById(lotId).orElse(null);
            List<Long> ids = repository.loadInventoryLotIds(currency);
            scheduler.runSync(() -> {
                if (!player.isOnline()) return;
                if (lot == null) {
                    sendMessage(player, configManager.getConfigValues().messages().noId());
                    return;
                }
                int total = ids.size();
                int current = ids.indexOf(lotId);
                if (current < 0) current = 0;
                long prevId = current > 0 ? ids.get(current - 1) : -1L;
                long nextId = current < total - 1 ? ids.get(current + 1) : -1L;

                String title = configManager.getConfigValues().inventorySelling().view().title().replace("{current}", String.valueOf(current + 1)).replace("{max}", String.valueOf(Math.max(1, total)));

                InventoryMenuHolder holder = new InventoryMenuHolder(currency, player.getUniqueId(), lotId, prevId, nextId, current, total);
                Inventory inv = Bukkit.createInventory(holder, 54, TextUtils.component(title));
                holder.setInventory(inv);
                builder.buildInventoryView(holder, lot, current, total);
                player.openInventory(inv);
            });
        });
    }

    public void openMain(Player player, AuctionCurrency currency, int page, AuctionSort sort, String sellerFilter, String searchFilter, String category) {
        UUID uuid = player.getUniqueId();

        if (sort == null) {
            sort = playerPreferences.getSort(uuid, currency);
        } else {
            playerPreferences.setSort(uuid, currency, sort);
        }

        if (category == null) {
            category = playerPreferences.getCategory(uuid, currency);
        } else {
            playerPreferences.setCategory(uuid, currency, category);
        }

        openInventory(player, AuctionViewType.MAIN, currency, page, sort, sellerFilter, searchFilter, category);
    }

    public void openSelling(Player player, AuctionCurrency currency, int page) {
        openInventory(player, AuctionViewType.SELLING, currency, page, AuctionSort.NEWEST, null, null, null);
    }

    public void openMaterialOffers(Player player, AuctionMenuHolder source, String material) {
        if (source.getCurrency() != AuctionCurrency.VAULT) return;
        openInventory(player, AuctionViewType.OFFERS, source.getCurrency(), 0, source.getSort(), source.getSellerFilter(), source.getSearchFilter(), material);
    }

    public void openSalesHistory(Player player, AuctionCurrency currency, int page) {
        salesHistoryMenu.open(player, currency, page);
    }

    public void openProfile(Player player, AuctionCurrency currency) {
        profileMenu.open(player, currency);
    }

    public void openProfileInput(Player player, boolean discord) {
        profileInputService.open(player, discord);
    }

    public void openSeller(Player player, AuctionCurrency currency, String query) {
        scheduler.runAsync(() -> {
            String seller = repository.findProfileOwner(query).orElse(query);
            scheduler.runSync(() -> {
                if (player.isOnline()) {
                    openMain(player, currency, 0, null, seller, null, "all");
                }
            });
        });
    }

    public void openExpired(Player player, AuctionCurrency currency, int page) {
        openInventory(player, AuctionViewType.EXPIRED, currency, page, AuctionSort.NEWEST, null, null, null);
    }

    public void openQuantity(Player player, AuctionCurrency currency, long lotId) {
        scheduler.runAsync(() -> {
            AuctionItem item = repository.findById(lotId).orElse(null);
            scheduler.runSync(() -> {
                if (!player.isOnline()) return;
                if (item == null) {
                    sendMessage(player, configManager.getConfigValues().messages().noId());
                    return;
                }
                quantityController.openQuantity(player, currency, item, 1);
            });
        });
    }

    public void openConfirm(Player player, AuctionCurrency currency, long lotId, int amount, boolean isInventoryBuy) {
        scheduler.runAsync(() -> {
            AuctionItem item = repository.findById(lotId).orElse(null);
            scheduler.runSync(() -> {
                if (!player.isOnline()) return;
                if (item == null) {
                    sendMessage(player, configManager.getConfigValues().messages().noId());
                    return;
                }
                confirmController.openConfirm(player, currency, item, amount, isInventoryBuy);
            });
        });
    }

    public void openInventory(Player player, AuctionViewType viewType, AuctionCurrency currency, int page, AuctionSort sort, String sellerFilter, String searchFilter, String category) {
        renderNew(player, viewType, currency, page, sort, sellerFilter, searchFilter, category);
    }

    public boolean isRefreshing(Player player) {
        UUID uuid = player.getUniqueId();
        synchronized (requestStartTime) {
            if (!requestStartTime.containsKey(uuid)) return false;
            long started = requestStartTime.getLong(uuid);
            return System.nanoTime() - started <= 15_000_000_000L;
        }
    }

    public void refreshAfterTake(Player player, AuctionMenuHolder holder, int slot, long lotId) {
        if (player.getOpenInventory().getTopInventory().getHolder() != holder) return;
        holder.incrementAndGetRequestVersion();
        if (Objects.equals(holder.getLot(slot), lotId)) {
            holder.getLotsBySlot().remove(slot);
            holder.getLotsAmountBySlot().remove(slot);
            holder.getInventoryLotBySlot().remove(slot);
            holder.getLotSnapshots().remove(slot);
            holder.getInventory().setItem(slot, null);
        }
        scheduleTakeRefresh(player, holder);
    }

    private void scheduleTakeRefresh(Player player, AuctionMenuHolder holder) {
        UUID uuid = player.getUniqueId();
        TakeRefresh pending = takeRefreshes.get(uuid);
        if (pending != null) {
            if (pending.holder() == holder) return;
            pending.task().cancel();
        }
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            takeRefreshes.remove(uuid);
            if (!player.isOnline() || player.getOpenInventory().getTopInventory().getHolder() != holder) return;
            if (isRefreshing(player)) scheduleTakeRefresh(player, holder);
            else refreshInventory(player, holder);
        }, 4L);
        takeRefreshes.put(uuid, new TakeRefresh(holder, task));
    }

    public void onPlayerQuit(UUID uuid) {
        TakeRefresh pending = takeRefreshes.remove(uuid);
        if (pending != null) pending.task().cancel();

        synchronized (requestStartTime) {
            requestStartTime.removeLong(uuid);
        }

        playerPreferences.remove(uuid);
    }

    public void refreshInventory(Player player, AuctionMenuHolder holder) {
        if (holder instanceof SalesHistoryMenuHolder) {
            openSalesHistory(player, holder.getCurrency(), holder.getPage());
            return;
        }
        UUID uuid = player.getUniqueId();
        synchronized (requestStartTime) {
            if (requestStartTime.containsKey(uuid)) {
                long start = requestStartTime.getLong(uuid);
                if (System.nanoTime() - start <= 15_000_000_000L) {
                    return;
                }
                requestStartTime.removeLong(uuid);
            }
            requestStartTime.put(uuid, System.nanoTime());
        }
        if (holder.getViewType() == AuctionViewType.MAIN) {
            playerPreferences.setSort(uuid, holder.getCurrency(), holder.getSort());
            playerPreferences.setCategory(uuid, holder.getCurrency(), holder.getCategory());
        }
        long requestVersion = holder.incrementAndGetRequestVersion();
        renderRefresh(player, holder, requestVersion);
    }

    private boolean isCurrentRequest(UUID uuid, long token) {
        synchronized (requestStartTime) {
            return !requestStartTime.containsKey(uuid) || requestStartTime.getLong(uuid) != token;
        }
    }

    private void releaseRequest(UUID uuid, long token) {
        synchronized (requestStartTime) {
            if (!requestStartTime.containsKey(uuid) || requestStartTime.getLong(uuid) != token) return;
            requestStartTime.removeLong(uuid);
        }
    }

    public void updateQuantityDisplay(Inventory inventory, QuantityMenuHolder holder, AuctionItem item) {
        quantityController.updateQuantityDisplay(inventory, holder, item);
    }

    public void reload() {
        itemFactory.clearCache();
        builder.buildGlassCache();
    }

    private void sendMessage(CommandSender sender, String message) {
        sender.sendMessage(TextUtils.component(PlaceholderUtils.apply(sender, message, configManager)));
    }

    private void renderNew(Player player, AuctionViewType viewType, AuctionCurrency currency, int page, AuctionSort sort, String sellerFilter, String searchFilter, String category) {
        UUID uuid = player.getUniqueId();
        synchronized (requestStartTime) {
            if (requestStartTime.containsKey(uuid)) {
                long start = requestStartTime.getLong(uuid);
                if (System.nanoTime() - start <= 15_000_000_000L) {
                    return;
                }
                requestStartTime.removeLong(uuid);
            }
            requestStartTime.put(uuid, System.nanoTime());
        }

        int pageSize = builder.getAuctionSlots(viewType, currency).size();
        if (pageSize == 0) {
            synchronized (requestStartTime) {
                requestStartTime.removeLong(uuid);
            }
            return;
        }
        String playerName = player.getName();
        int maxStorageDays = configManager.getConfigValues().maxAuctionStorageDays();

        if (viewType == AuctionViewType.SELLING || viewType == AuctionViewType.EXPIRED) {
            sellerFilter = playerName;
            category = null;
            searchFilter = null;
            sort = AuctionSort.NEWEST;
        }

        final String fSeller = sellerFilter;
        final String fCategory = category;
        final String fSearch = searchFilter;
        final AuctionSort fSort = sort;

        final long token;
        synchronized (requestStartTime) { token = requestStartTime.getLong(uuid); }

        scheduler.runAsync(() -> {
            try {
                AuctionRepository.MenuData data = repository.loadMenuData(currency, playerName, maxStorageDays, page, pageSize, fSort, fCategory, fSeller, fSearch, viewType);

                scheduler.runSync(() -> {
                    try {
                        if (!player.isOnline() || isCurrentRequest(uuid, token)) return;

                        Inventory inv = builder.buildMainMenu(player, viewType, currency, page, fSort, fSeller, fSearch, fCategory, data.items(), data.totalCount(), pageSize, data.sellingCount(), data.expiredCount(), data.profiles(), data.materialLots(), data.itemGroups());
                        player.openInventory(inv);
                    } finally {
                        releaseRequest(uuid, token);
                    }
                });
            } catch (Exception e) {
                releaseRequest(uuid, token);
                plugin.getLogger().log(Level.WARNING, "Не удалось открыть меню аукциона", e);
            }
        });
    }

    private void renderRefresh(Player player, AuctionMenuHolder holder, long requestVersion) {
        UUID uuid = player.getUniqueId();
        AuctionCurrency currency = holder.getCurrency();

        AuctionViewType viewType = holder.getViewType();
        int pageSize = builder.getAuctionSlots(viewType, currency).size();
        if (pageSize == 0) {
            synchronized (requestStartTime) {
                requestStartTime.removeLong(uuid);
            }
            return;
        }
        String playerName = player.getName();
        int maxStorageDays = configManager.getConfigValues().maxAuctionStorageDays();

        int page = holder.getPage();
        AuctionSort sort = holder.getSort();
        String sellerFilter = holder.getSellerFilter();
        String searchFilter = holder.getSearchFilter();
        String category = holder.getCategory();

        if (viewType == AuctionViewType.SELLING || viewType == AuctionViewType.EXPIRED) {
            sellerFilter = playerName;
            category = null;
            searchFilter = null;
            sort = AuctionSort.NEWEST;
        }

        final String fSeller = sellerFilter;
        final String fCategory = category;
        final String fSearch = searchFilter;
        final AuctionSort fSort = sort;

        final long token;
        synchronized (requestStartTime) { token = requestStartTime.getLong(uuid); }

        scheduler.runAsync(() -> {
            try {
                AuctionRepository.MenuData data = repository.loadMenuData(currency, playerName, maxStorageDays, page, pageSize, fSort, fCategory, fSeller, fSearch, viewType);

                scheduler.runSync(() -> {
                    try {
                        if (!player.isOnline() || isCurrentRequest(uuid, token)) return;
                        if (player.getOpenInventory().getTopInventory().getHolder() != holder) return;
                        if (requestVersion != holder.getRequestVersion()) return;

                        builder.refreshLotDisplays(holder.getInventory(), holder, data.items(), data.totalCount(), pageSize, data.profiles(), data.materialLots(), data.itemGroups());
                        builder.refreshNavigationButtons(holder.getInventory(), holder);
                        builder.refreshCounters(holder.getInventory(), holder, data.sellingCount(), data.expiredCount());
                    } finally {
                        releaseRequest(uuid, token);
                    }
                });
            } catch (Exception e) {
                releaseRequest(uuid, token);
                plugin.getLogger().log(Level.WARNING, "Не удалось обновить меню аукциона", e);
            }
        });
    }
}
