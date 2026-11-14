
package ru.moscow.foxkiss.commands;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;
import ru.moscow.foxkiss.FMAuction;
import ru.moscow.foxkiss.config.interfaces.IConfigManager;
import ru.moscow.foxkiss.utils.CommandUtils;
import ru.moscow.foxkiss.utils.PlaceholderUtils;
import ru.moscow.foxkiss.utils.TextUtils;

import java.util.List;

public record AdminCommand(FMAuction plugin, IConfigManager configManager) implements CommandExecutor, TabCompleter {

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("fmauction.admin")) {
            sendMessage(sender, configManager.getConfigValues().messages().noPermission());
            return true;
        }

        if (args.length != 1) {
            sendMessage(sender, configManager.getConfigValues().messages().unknownSubcommand());
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            handleReload(sender);
            return true;
        }

        sendMessage(sender, configManager.getConfigValues().messages().unknownSubcommand());
        return true;
    }

    private void handleReload(CommandSender sender) {
        long start = System.currentTimeMillis();

        try {
            plugin.reloadAll();

            sendMessage(sender, configManager.getConfigValues().messages().reload().replace("{time}", String.valueOf(System.currentTimeMillis() - start)));
        } catch (Exception e) {
            sendMessage(sender, configManager.getConfigValues().messages().errorReload().replace("{error}", e.getClass().getSimpleName()));
        }
    }

    private void sendMessage(CommandSender sender, String message) {
        sender.sendMessage(TextUtils.component(PlaceholderUtils.apply(sender, message, configManager)));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        if (!sender.hasPermission("fmauction.admin")) {
            return List.of();
        }

        final ObjectList<String> completions = new ObjectArrayList<>();

        if (args.length == 1) {
            completions.add("reload");
        }

        return CommandUtils.getResult(args, completions);
    }
}
