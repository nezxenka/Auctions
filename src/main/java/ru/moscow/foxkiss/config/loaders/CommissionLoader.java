package ru.moscow.foxkiss.config.loaders;

import org.bukkit.configuration.ConfigurationSection;
import ru.moscow.foxkiss.auction.AuctionCurrency;
import ru.moscow.foxkiss.config.CommissionMode;
import ru.moscow.foxkiss.config.ConfigValues;

public final class CommissionLoader {

    public ConfigValues.CommissionConfig load(ConfigurationSection section) {
        boolean enabled = section.getBoolean("enabled");
        ConfigurationSection sell = section.getConfigurationSection("sell");
        ConfigurationSection sellInventory = section.getConfigurationSection("sell-inventory");
        return new ConfigValues.CommissionConfig(enabled, loadRule(sell), loadRule(sellInventory));
    }

    private ConfigValues.CommissionRule loadRule(ConfigurationSection section) {
        CommissionMode mode = "PERCENT".equalsIgnoreCase(section.getString("mode")) ? CommissionMode.PERCENT : CommissionMode.FIXED;
        double fixed = section.getDouble("fixed");
        double percent = section.getDouble("percent");
        double min = section.getDouble("min");
        AuctionCurrency currency = AuctionCurrency.valueOf(section.getString("currency", "VAULT"));
        return new ConfigValues.CommissionRule(mode, fixed, percent, min, currency);
    }
}
