package ru.moscow.foxkiss.economy;

import org.black_ixx.playerpoints.PlayerPoints;
import org.black_ixx.playerpoints.PlayerPointsAPI;
import org.bukkit.OfflinePlayer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public final class PlayerPointsApi {

    private final PlayerPointsAPI api;

    public PlayerPointsApi() {
        Object plugin = Bukkit.getPluginManager().getPlugin("PlayerPoints");
        if (!(plugin instanceof PlayerPoints playerPoints)) {
            throw new IllegalStateException("Плагин PlayerPoints не найден");
        }
        this.api = playerPoints.getAPI();
        if (api == null) {
            throw new IllegalStateException("API PlayerPoints недоступен");
        }
    }

    public boolean has(Player player, int amount) {
        return api.look(player.getUniqueId()) >= amount;
    }

    public boolean withdraw(Player player, int amount) {
        return api.take(player.getUniqueId(), amount);
    }

    public boolean deposit(OfflinePlayer player, int amount) {
        return api.give(player.getUniqueId(), amount);
    }
}