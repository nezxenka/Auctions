package ru.moscow.foxkiss.gui.holders;

import ru.moscow.foxkiss.auction.AuctionCurrency;
import ru.moscow.foxkiss.auction.AuctionSort;
import ru.moscow.foxkiss.gui.AuctionViewType;

import java.util.UUID;

public final class SalesHistoryMenuHolder extends AuctionMenuHolder {
    public SalesHistoryMenuHolder(AuctionCurrency currency, UUID viewer, int page, int totalPages) {
        super(AuctionViewType.HISTORY, currency, viewer, page, AuctionSort.NEWEST, null, null, null);
        setTotalPages(totalPages);
    }
}
