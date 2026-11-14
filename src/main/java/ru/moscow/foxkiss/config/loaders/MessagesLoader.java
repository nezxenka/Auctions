package ru.moscow.foxkiss.config.loaders;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import ru.moscow.foxkiss.config.ConfigValues;
import ru.moscow.foxkiss.utils.TextUtils;

public record MessagesLoader(String prefix) {

    public ConfigValues.ConfigMessages load(FileConfiguration config) {
        ConfigurationSection messages = config.getConfigurationSection("messages");
        ConfigurationSection admin = messages.getConfigurationSection("admin");
        ConfigurationSection errors = messages.getConfigurationSection("errors");
        ConfigurationSection buy = messages.getConfigurationSection("buy");
        ConfigurationSection expired = messages.getConfigurationSection("expired");
        ConfigurationSection selling = messages.getConfigurationSection("selling");
        ConfigurationSection commands = config.getConfigurationSection("commands");
        ConfigurationSection sell = commands.getConfigurationSection("sell");
        ConfigurationSection auction = config.getConfigurationSection("auction");
        ConfigurationSection auctionErrors = auction.getConfigurationSection("errors");

        ConfigurationSection search = config.getConfigurationSection("search");
        ConfigurationSection profile = messages.getConfigurationSection("profile");

        return new ConfigValues.ConfigMessages(
                msg(admin.getString("reload")),
                msg(admin.getString("unknown-subcommand")),
                msg(admin.getString("error-reload")),
                msg(admin.getString("no-permission")),
                msg(errors.getString("no-name")),
                msg(errors.getString("no-id")),
                msg(sell.getString("non-price")),
                msg(errors.getString("no-own")),
                msg(auctionErrors.getString("economy-unavailable")),
                msg(sell.getString("air")),
                msg(sell.getString("success")),
                msg(sell.getString("price-too-low")),
                msg(sell.getString("price-too-high")),
                msg(sell.getString("limit-reached")),
                msg(sell.getString("database-error")),
                msg(String.join("\n", search.getStringList("enter-player-name"))),
                msg(buy.getString("buy-seller")),
                msg(buy.getString("otmena")),
                msg(buy.getString("yspex")),
                msg(buy.getString("nomoney")),
                msg(buy.getString("quantity-exceeded")),
                msg(expired.getString("take")),
                msg(selling.getString("take")),
                msg(messages.getString("cooldown")),
                msg(messages.getString("inventory-full")),
                msg(messages.getString("cooldown-item")),
                msg(sell.getString("inventory-min-items")),
                msg(sell.getString("commission-charged")),
                msg(sell.getString("commission-not-enough")),
                msg(buy.getString("pending-items-empty")),
                msg(buy.getString("pending-items-received")),
                msg(buy.getString("pending-items-remaining")),
                msg(buy.getString("pending-items-saved")),
                msg(buy.getString("pending-items-auto-received")),
                msg(profile.getString("discord-set")),
                msg(profile.getString("discord-cleared")),
                msg(profile.getString("store-set")),
                msg(profile.getString("store-cleared")),
                msg(profile.getString("too-long"))
        );
    }

    private String msg(String raw) {
        return TextUtils.colorize(raw.replace("%prefix%", prefix));
    }
}
