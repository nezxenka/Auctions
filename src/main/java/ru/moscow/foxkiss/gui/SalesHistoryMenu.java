package ru.moscow.foxkiss.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;
import ru.moscow.foxkiss.FMAuction;
import ru.moscow.foxkiss.auction.AuctionCurrency;
import ru.moscow.foxkiss.auction.AuctionRepository;
import ru.moscow.foxkiss.auction.SaleHistoryEntry;
import ru.moscow.foxkiss.config.ConfigValues;
import ru.moscow.foxkiss.config.SalesHistoryConfig;
import ru.moscow.foxkiss.config.interfaces.IConfigManager;
import ru.moscow.foxkiss.gui.holders.SalesHistoryMenuHolder;
import ru.moscow.foxkiss.scheduler.SchedulerService;
import ru.moscow.foxkiss.utils.ItemUtils;
import ru.moscow.foxkiss.utils.PlaceholderUtils;
import ru.moscow.foxkiss.utils.PriceFormatter;
import ru.moscow.foxkiss.utils.TextUtils;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.logging.Level;

public final class SalesHistoryMenu {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss").withZone(ZoneId.systemDefault());

    private final FMAuction plugin;
    private final IConfigManager configManager;
    private final AuctionRepository repository;
    private final ItemDisplayFactory itemFactory;
    private final SchedulerService scheduler;
    private final Map<UUID, Long> requests = new HashMap<>();
    private long requestSequence;

    public SalesHistoryMenu(FMAuction plugin, IConfigManager configManager, AuctionRepository repository, ItemDisplayFactory itemFactory, SchedulerService scheduler) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.repository = repository;
        this.itemFactory = itemFactory;
        this.scheduler = scheduler;
    }

    public void open(Player player, AuctionCurrency currency, int page) {
        SalesHistoryConfig settings = configManager.getConfigValues().salesHistory();
        if (!settings.enabled()) return;
        UUID viewer = player.getUniqueId();
        String seller = player.getName();
        Inventory origin = player.getOpenInventory().getTopInventory();
        long request = ++requestSequence;
        requests.put(viewer, request);
        scheduler.runAsync(() -> {
            AuctionRepository.SalesHistoryPage loaded = null;
            try {
                loaded = repository.loadSalesHistory(seller, currency, page, settings.entrySlots().size());
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.WARNING, "Не удалось открыть историю продаж " + seller, e);
            }
            AuctionRepository.SalesHistoryPage result = loaded;
            scheduler.runSync(() -> {
                if (!requests.remove(viewer, request)) {
                    return;
                }
                if (!player.isOnline() || player.getOpenInventory().getTopInventory() != origin
                        || configManager.getConfigValues().salesHistory() != settings) return;
                if (result == null) {
                    sendMessage(player, configManager.getConfigValues().messages().databaseError());
                    return;
                }
                build(player, currency, settings, result);
            });
        });
    }

    private void build(Player player, AuctionCurrency currency, SalesHistoryConfig settings, AuctionRepository.SalesHistoryPage page) {
        SalesHistoryMenuHolder holder = new SalesHistoryMenuHolder(currency, player.getUniqueId(), page.page(), page.totalPages());
        String currentPage = String.valueOf(page.page() + 1);
        String totalPages = String.valueOf(page.totalPages());
        String total = String.valueOf(page.totalCount());
        String symbol = currency.symbol(configManager.getConfigValues());
        UnaryOperator<String> common = text -> text
                .replace("{page}", currentPage)
                .replace("{pages}", totalPages)
                .replace("{total}", total)
                .replace("{player}", player.getName())
                .replace("{currency}", currency.name())
                .replace("{symbol}", symbol)
                .replace("{symbol_value}", symbol);
        Inventory inventory = Bukkit.createInventory(holder, settings.size(),
                applyComponent(player, common.apply(settings.title())));
        holder.setInventory(inventory);

        for (ConfigValues.ButtonConfig configured : settings.items()) {
            ItemStack item = button(player, configured, common);
            for (int slot : configured.slots()) {
                inventory.setItem(slot, item.clone());
                holder.addActions(slot, configured.actions());
            }
        }

        List<SaleHistoryEntry> entries = page.entries();
        List<Integer> slots = settings.entrySlots();
        int count = Math.min(entries.size(), slots.size());
        for (int i = 0; i < count; i++) {
            inventory.setItem(slots.get(i), entry(player, entries.get(i), settings, common));
        }

        if (entries.isEmpty()) {
            ItemStack empty = button(player, settings.empty(), common);
            for (int slot : settings.empty().slots()) {
                inventory.setItem(slot, empty.clone());
                holder.addActions(slot, settings.empty().actions());
            }
        }
        player.openInventory(inventory);
    }

    private ItemStack entry(Player player, SaleHistoryEntry sale, SalesHistoryConfig settings, UnaryOperator<String> common) {
        ItemStack original = sale.item();

        if (original == null) {
            Material material = Material.matchMaterial(sale.itemType());
            original = new ItemStack(material == null ? settings.entry().material() : material);
        }

        String name;
        if ("INVENTORY".equals(sale.itemType())) {
            name = configManager.getConfigValues().inventorySelling().displayName().replace("%player%", sale.sellerName());
        } else {
            ItemMeta meta = original.getItemMeta();
            name = meta.hasDisplayName()
                    ? LegacyComponentSerializer.legacySection().serialize(meta.displayName())
                    : ItemUtils.getItemDisplayName(original);
        }

        String date = DATE_FORMAT.format(Instant.ofEpochMilli(sale.soldAt()));
        String price = PriceFormatter.format(sale.price(), sale.currency());
        String pricePerItem = PriceFormatter.format(sale.price() / sale.amount(), sale.currency());
        String amount = String.valueOf(sale.amount());
        String materialName = original.getType().name();
        String id = String.valueOf(sale.id());
        String timestamp = String.valueOf(sale.soldAt());
        UnaryOperator<String> replacements = text -> common.apply(text)
                .replace("{item_name}", name)
                .replace("{buyer}", sale.buyerName())
                .replace("{seller}", sale.sellerName())
                .replace("{date}", date)
                .replace("{price}", price)
                .replace("{price_per_item}", pricePerItem)
                .replace("{amount}", amount)
                .replace("{material}", materialName)
                .replace("{id}", id)
                .replace("{timestamp}", timestamp);
        ItemStack display = settings.useOriginalItem()
                ? original.clone() : itemFactory.createButton(settings.entry());
        display.setAmount(Math.max(1, Math.min(sale.amount(), display.getMaxStackSize())));
        applyText(player, display, settings.entry(), replacements);
        return display;
    }

    private ItemStack button(Player player, ConfigValues.ButtonConfig configured, UnaryOperator<String> replacements) {
        ItemStack item = itemFactory.createButton(configured);
        applyText(player, item, configured, replacements);
        return item;
    }

    private void applyText(Player player, ItemStack item, ConfigValues.ButtonConfig configured, UnaryOperator<String> replacements) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;

        String nameRaw = replacements.apply(configured.name());
        meta.displayName(applyComponent(player, nameRaw));

        List<Component> lore = new ArrayList<>();
        for (String line : configured.lore()) {
            String lineRaw = replacements.apply(line);
            lore.add(applyComponent(player, lineRaw));
        }
        meta.lore(lore);
        if (configured.customModelData() != null) {
            CustomModelDataComponent component = meta.getCustomModelDataComponent();
            component.setFloats(List.of(configured.customModelData().floatValue()));
            meta.setCustomModelDataComponent(component);
        }
        item.setItemMeta(meta);
    }

    private Component applyComponent(Player player, String message) {
        return TextUtils.component(PlaceholderUtils.apply(player, message, configManager));
    }

    private void sendMessage(Player player, String message) {
        player.sendMessage(applyComponent(player, message));
    }
}