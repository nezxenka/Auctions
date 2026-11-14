package ru.moscow.foxkiss.auction;

public record PendingPayout(
        long id,
        String playerName,
        AuctionCurrency currency,
        double amount,
        long createdAt
) {}
