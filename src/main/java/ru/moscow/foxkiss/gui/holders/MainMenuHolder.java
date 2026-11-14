package ru.moscow.foxkiss.gui.holders;

import lombok.EqualsAndHashCode;
import lombok.Value;
import ru.moscow.foxkiss.auction.AuctionCurrency;
import ru.moscow.foxkiss.auction.AuctionSort;
import ru.moscow.foxkiss.gui.AuctionViewType;

import java.util.UUID;

@Value
@EqualsAndHashCode(callSuper = true)
public class MainMenuHolder extends AuctionMenuHolder {
    public MainMenuHolder(AuctionViewType viewType, AuctionCurrency currency, UUID viewer, int page, AuctionSort sort, String sellerFilter, String searchFilter, String category) {
        super(viewType, currency, viewer, page, sort, sellerFilter, searchFilter, category);
    }
}