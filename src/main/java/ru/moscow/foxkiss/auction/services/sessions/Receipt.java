package ru.moscow.foxkiss.auction.services.sessions;

import org.bukkit.inventory.ItemStack;
import ru.moscow.foxkiss.auction.services.AuctionTransactionService;

import java.util.List;

public record Receipt(long deliveryId, List<ItemStack> leftovers, List<AuctionTransactionService.Receipt> entries) {
    public Receipt(long deliveryId, List<ItemStack> leftovers
    ) {
        this(deliveryId, leftovers, List.of());
    }
}
