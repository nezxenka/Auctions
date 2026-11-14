package ru.moscow.foxkiss.config;

import org.bukkit.Material;
import ru.moscow.foxkiss.auction.AuctionCurrency;
import ru.moscow.foxkiss.gui.actions.Action;

import java.util.List;
import java.util.Map;
import java.util.Set;

public record ConfigValues(
        String prefix,
        Map<String, Integer> vaultGroupLimits,
        Map<String, Integer> playerPointsGroupLimits,
        int maxAuctionStorageDays,
        Map<String, Set<Material>> categories,
        ConfigMessages messages,
        PriceLimits priceLimits,
        Cooldowns cooldowns,
        boolean bStatsEnabled,
        boolean usePapi,
        AuctionGuiConfig moneyGui,
        AuctionGuiConfig donateGui,
        ConfirmMenuConfig confirmMenu,
        QuantityMenuConfig quantityMenu,
        InventorySellingConfig inventorySelling,
        CommissionConfig commission,
        SalesHistoryConfig salesHistory,
        ProfileMenuConfig profileMenu
) {

    public AuctionGuiConfig gui(AuctionCurrency currency) {
        return switch (currency) {
            case VAULT -> moneyGui;
            case PLAYER_POINTS -> donateGui;
        };
    }

    public record AuctionData(
            int maxStorageDays,
            Map<String, Set<Material>> categories
    ) {}

    public record PriceLimits(
            double minPriceMoneyAuc,
            double minPriceMoneyDauc,
            double maxPriceMoneyAuc,
            double maxPriceMoneyDauc
    ) {}

    public record Cooldowns(
            double updateAuctionSeconds,
            double takeItemSeconds,
            boolean cooldownMessageEnabled,
            boolean cooldownEnabled
    ) {}

    public record AuctionGuiConfig(
            int menuSize,
            List<Integer> auctionSlots,
            TitlesConfig titles,
            Map<String, String> sortingNames,
            Map<String, String> categoryNames,
            String symbol,
            SortMenuConfig sortMenu,
            CategoryMenuConfig categoryMenu,
            ItemLoreConfig itemLore,
            NavigationConfig navigation,
            ButtonConfig exitButton,
            Map<Integer, GlassPane> mainGlass,
            Map<Integer, GlassPane> expiredGlass,
            String materialCardName,
            List<String> materialCardLore
    ) { }

    public record TitlesConfig(
            String main,
            String selling,
            String expired
    ) {}

    public record SortMenuConfig(
            Material material,
            String name,
            String selectedPrefix,
            String unselectedPrefix,
            String footer
    ) {}

    public record ItemLoreConfig(
            List<String> lore,
            List<String> loreOne,
            List<String> buyLore,
            List<String> discordLore,
            List<String> discordLoreOne,
            List<String> storeLore,
            List<String> storeLoreOne,
            List<String> discordStoreLore,
            List<String> discordStoreLoreOne
    ) {}

    public record QuantityMenuConfig(
            String title,
            int slotAmount,
            int sizeMenu,
            ButtonConfig decrease10,
            ButtonConfig decrease1,
            ButtonConfig amount,
            ButtonConfig increase1,
            ButtonConfig increase10,
            Map<Integer, GlassPane> glassPanes
    ) {}

    public record ButtonConfig(
            Material material,
            String name,
            List<String> lore,
            String skullTexture,
            List<Action> actions,
            List<Integer> slots,
            Integer customModelData
    ) {}

    public record ConfirmButtonConfig(
            Material material,
            String name,
            List<String> lore,
            String skullTexture,
            List<Integer> slots,
            List<Action> actions,
            Integer customModelData
    ) {}

    public record NavigationConfig(
            NavigationButton previous,
            NavigationButton refresh,
            NavigationButton next,
            NavigationButton selling,
            NavigationButton expired,
            NavigationButton sort,
            NavigationButton categories
    ) {}

    public record NavigationButton(
            int slot,
            Material material,
            String name,
            List<String> lore,
            String skullTexture,
            List<Action> actions,
            Integer customModelData
    ) {}

    public record CategoryMenuConfig(
            Material material,
            String name,
            String selectedPrefix,
            String unselectedPrefix,
            String footer,
            List<CategoryEntry> entries
    ) { }

    public record CategoryEntry(
            String id,
            String selected,
            String unselected
    ) {}

    public record GlassPane(
            Material material,
            String displayName,
            Integer customModelData
    ) {}

    public record ConfirmMenuConfig(
            String title,
            boolean enabled,
            int itemSlot,
            int size,
            ConfirmButtonConfig confirm,
            ConfirmButtonConfig cancel,
            Map<Integer, GlassPane> glassPanes
    ) {}

    public record ConfigMessages(
            String reload,
            String unknownSubcommand,
            String errorReload,
            String noPermission,
            String noName,
            String noId,
            String noPrice,
            String noOwn,
            String economyUnavailable,
            String air,
            String sellSuccess,
            String priceTooLow,
            String priceTooHigh,
            String limitReached,
            String databaseError,
            String enterPlayerName,
            String buySeller,
            String otmena,
            String yspex,
            String nomoney,
            String quantityExceeded,
            String takeExpired,
            String takeSelling,
            String cooldown,
            String inventoryFull,
            String cooldownItem,
            String inventoryMinItems,
            String commissionCharged,
            String commissionNotEnough,
            String pendingItemsEmpty,
            String pendingItemsReceived,
            String pendingItemsRemaining,
            String pendingItemsSaved,
            String pendingItemsAutoReceived,
            String discordSet,
            String discordCleared,
            String storeSet,
            String storeCleared,
            String tooLong
    ) {}

    public record InventorySellingConfig(
            boolean moneyAuc,
            boolean rublesAuc,
            int minItems,
            int minPrice,
            int maxPrice,
            int maxItems,
            String displayName,
            List<String> displayLore,
            List<Material> shulkerMaterials,
            InventoryViewConfig view
    ) {}

    public record CommissionRule(
            CommissionMode mode,
            double fixed,
            double percent,
            double min,
            AuctionCurrency currency
    ) {}

    public record CommissionConfig(
            boolean enabled,
            CommissionRule sell,
            CommissionRule sellInventory
    ) {}

    public record InventoryViewConfig(
            int startSlot,
            int endSlot,
            String title,
            InventoryNavButton prev,
            InventoryNavButton next,
            InventoryActionButton cancel,
            InventoryActionButton confirm,
            int itemSlot,
            Map<Integer, GlassPane> glassPanes
    ) {}

    public record InventoryNavButton(
            Material material,
            String name,
            List<String> lore,
            int slot,
            List<Action> actions
    ) {}

    public record InventoryActionButton(
            Material material,
            String name,
            List<String> lore,
            List<Integer> slots,
            List<Action> actions
    ) {}

}
