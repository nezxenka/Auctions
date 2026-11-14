package ru.moscow.foxkiss.gui.holders.records;

import ru.moscow.foxkiss.auction.AuctionLotType;
import ru.moscow.foxkiss.auction.AuctionStatus;

public record LotSnapshot(
        long id,
        int amount,
        double price,
        long createdAt,
        AuctionLotType type,
        AuctionStatus status,
        String seller,
        String discord,
        String store,
        long sales,
        long ageBucket,
        long daysLeft
) {}