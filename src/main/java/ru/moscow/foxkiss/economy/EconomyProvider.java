package ru.moscow.foxkiss.economy;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import ru.moscow.foxkiss.auction.AuctionCurrency;

public interface EconomyProvider {
    boolean available(AuctionCurrency currency);
    boolean has(Player player, AuctionCurrency currency, double amount);
    boolean withdraw(Player player, AuctionCurrency currency, double amount);
    boolean deposit(OfflinePlayer player, AuctionCurrency currency, double amount);
}
