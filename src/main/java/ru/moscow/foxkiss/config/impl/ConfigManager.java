package ru.moscow.foxkiss.config.impl;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import ru.moscow.foxkiss.config.ConfigValues;
import ru.moscow.foxkiss.config.interfaces.IConfigManager;
import ru.moscow.foxkiss.config.loaders.*;
import ru.moscow.foxkiss.utils.ItemUtils;

import java.io.File;
import java.util.Map;

public final class ConfigManager implements IConfigManager {
    private final JavaPlugin plugin;
    private ConfigValues configValues;

    public ConfigManager(JavaPlugin plugin) {
        this.plugin = plugin;
        plugin.getDataFolder().mkdirs();
        copyResourceIfMissing("messages.yml");
        copyResourceIfMissing("menus/menu_auction_money.yml");
        copyResourceIfMissing("menus/menu_auction_donate.yml");
        copyResourceIfMissing("menus/menu_confirm_buy.yml");
        copyResourceIfMissing("menus/menu_quantity.yml");
        copyResourceIfMissing("menus/menu_inventory_view.yml");
        copyResourceIfMissing("menus/menu_sales_history.yml");
        copyResourceIfMissing("menus/menu_profile.yml");
        reload();
    }

    @Override
    public void reload() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();

        loadItemsYml();

        FileConfiguration config = plugin.getConfig();

        String prefix = config.getString("prefix");

        FileConfiguration messagesConfig = YamlConfiguration.loadConfiguration(getMenuFile("messages.yml"));
        MessagesLoader messagesLoader = new MessagesLoader(prefix);
        ConfigValues.ConfigMessages messages = messagesLoader.load(messagesConfig);

        ConfigurationSection cooldownSection = config.getConfigurationSection("cooldowns");
        CooldownLoader cooldownsLoader = new CooldownLoader();
        ConfigValues.Cooldowns cooldowns = cooldownsLoader.load(cooldownSection);

        ConfigurationSection priceLimitsSection = config.getConfigurationSection("price-limits");
        PriceLimitsLoader priceLimitsLoader = new PriceLimitsLoader();
        ConfigValues.PriceLimits priceLimits = priceLimitsLoader.load(priceLimitsSection);

        ConfigurationSection vaultAuction = config.getConfigurationSection("vault-auc");
        ConfigurationSection pointsAuction = config.getConfigurationSection("playerpoints-auc");
        ConfigurationSection vaultGroupSection = vaultAuction.getConfigurationSection("groups");
        ConfigurationSection pointsGroupSection = pointsAuction.getConfigurationSection("groups");

        Map<String, Integer> vaultGroups = LimitsLoader.load(vaultGroupSection);
        Map<String, Integer> ppGroups = LimitsLoader.load(pointsGroupSection);

        ConfigurationSection auctionSection = config.getConfigurationSection("auction");
        ConfigurationSection categorySection = config.getConfigurationSection("categories");

        AuctionSettingsLoader auctionLoader = new AuctionSettingsLoader(plugin);
        ConfigValues.AuctionData auctionSettings = auctionLoader.load(auctionSection, categorySection);
        FileConfiguration inventoryViewConfig = YamlConfiguration.loadConfiguration(getMenuFile("menus/menu_inventory_view.yml"));
        ConfigurationSection inventorySellingSection = config.getConfigurationSection("inventory-selling");
        ConfigValues.InventorySellingConfig inventorySelling = auctionLoader.loadInventorySelling(inventorySellingSection, inventoryViewConfig);

        ConfigurationSection commissionSection = config.getConfigurationSection("commission");
        ConfigValues.CommissionConfig commission = new CommissionLoader().load(commissionSection);

        boolean enableBstats = config.getBoolean("enable-bstats");
        boolean usePapi = config.getBoolean("use-papi");

        GuiLoader guiLoader = new GuiLoader();
        ConfigValues.AuctionGuiConfig moneyGui = guiLoader.loadMenu(YamlConfiguration.loadConfiguration(getMenuFile("menus/menu_auction_money.yml")));
        ConfigValues.AuctionGuiConfig donateGui = guiLoader.loadMenu(YamlConfiguration.loadConfiguration(getMenuFile("menus/menu_auction_donate.yml")));
        ConfigValues.ConfirmMenuConfig confirmMenu = guiLoader.loadConfirmMenu(YamlConfiguration.loadConfiguration(getMenuFile("menus/menu_confirm_buy.yml")));
        ConfigValues.QuantityMenuConfig quantityMenu = guiLoader.loadQuantityMenu(YamlConfiguration.loadConfiguration(getMenuFile("menus/menu_quantity.yml")));

        this.configValues = new ConfigValues(
                prefix,
                vaultGroups,
                ppGroups,
                auctionSettings.maxStorageDays(),
                auctionSettings.categories(),
                messages,
                priceLimits,
                cooldowns,
                enableBstats,
                usePapi,
                moneyGui,
                donateGui,
                confirmMenu,
                quantityMenu,
                inventorySelling,
                commission,
                new SalesHistoryLoader().load(YamlConfiguration.loadConfiguration(getMenuFile("menus/menu_sales_history.yml"))),
                new ProfileMenuLoader().load(YamlConfiguration.loadConfiguration(getMenuFile("menus/menu_profile.yml")))
        );
    }

    @Override
    public ConfigValues getConfigValues() {
        return configValues;
    }

    private File getMenuFile(String path) {
        return new File(plugin.getDataFolder(), path);
    }

    private void copyResourceIfMissing(String path) {
        File file = new File(plugin.getDataFolder(), path);
        if (!file.exists()) {
            plugin.saveResource(path, false);
        }
    }

    private void loadItemsYml() {
        File file = new File(plugin.getDataFolder(), "items.yml");
        if (!file.exists()) {
            plugin.saveResource("items.yml", false);
        }
        ItemUtils.loadTranslations(file, plugin.getLogger());
    }
}
