package ru.moscow.foxkiss.auction;

public record TakeResult(
        boolean success,
        long pendingDeliveryId,
        AuctionItem item
) {}
