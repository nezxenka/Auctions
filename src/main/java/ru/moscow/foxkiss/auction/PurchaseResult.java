package ru.moscow.foxkiss.auction;

import org.bukkit.inventory.ItemStack;

public record PurchaseResult(
        boolean success,
        long pendingDeliveryId,
        ItemStack purchasedItem,
        boolean remainingLotUpdated
) {}
