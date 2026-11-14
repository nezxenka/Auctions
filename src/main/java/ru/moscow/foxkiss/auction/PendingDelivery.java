package ru.moscow.foxkiss.auction;

import org.bukkit.inventory.ItemStack;

import java.util.List;

public record PendingDelivery(
        long id,
        String playerName,
        AuctionLotType lotType,
        ItemStack item,
        List<ItemStack> inventoryContents,
        long createdAt,
        AuctionCurrency currency) {
}
