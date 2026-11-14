package ru.moscow.foxkiss.auction;

import org.bukkit.inventory.ItemStack;

public record SaleHistoryEntry(long id, String sellerName, String buyerName, AuctionCurrency currency, String itemType, ItemStack item, int amount, double price, long soldAt) {
}
