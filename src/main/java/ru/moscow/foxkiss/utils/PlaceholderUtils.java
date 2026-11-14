package ru.moscow.foxkiss.utils;

import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import ru.moscow.foxkiss.config.interfaces.IConfigManager;

public final class PlaceholderUtils {
    private static boolean papiEnabled;

    public static void init() {
        papiEnabled = Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI");
    }

    public static String apply(CommandSender sender, String message, IConfigManager configManager) {
        if (message == null || message.isEmpty()
                || !papiEnabled
                || message.indexOf('%') == -1
                || !configManager.getConfigValues().usePapi()
                || !(sender instanceof Player player)) {
            return message;
        }
        return PlaceholderAPI.setPlaceholders(player, message);
    }
}