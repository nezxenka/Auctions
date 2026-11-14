package ru.moscow.foxkiss.utils;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import org.bukkit.command.CommandSender;
import org.bukkit.util.StringUtil;
import ru.moscow.foxkiss.config.interfaces.IConfigManager;

import java.util.Collections;
import java.util.List;

public final class CommandUtils {

    public static List<String> getResult(String[] args, List<String> completions) {
        if (completions == null || completions.isEmpty() || args == null || args.length == 0) {
            return Collections.emptyList();
        }
        String last = args[args.length - 1];
        ObjectList<String> result = new ObjectArrayList<>();
        for (String c : completions) {
            if (StringUtil.startsWithIgnoreCase(c, last)) {
                result.add(c);
            }
        }
        return result;
    }

    public static Double parsePrice(CommandSender sender, String[] args, IConfigManager configManager) {
        if (args.length < 2) {
            sendMessage(sender, configManager, configManager.getConfigValues().messages().noPrice());
            return null;
        }
        try {
            return Double.parseDouble(args[1]);
        } catch (NumberFormatException e) {
            sendMessage(sender, configManager, configManager.getConfigValues().messages().noPrice());
            return null;
        }
    }

    private static void sendMessage(CommandSender sender, IConfigManager configManager, String message) {
        sender.sendMessage(TextUtils.component(PlaceholderUtils.apply(sender, message, configManager)));
    }
}