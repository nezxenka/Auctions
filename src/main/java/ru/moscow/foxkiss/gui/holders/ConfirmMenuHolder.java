package ru.moscow.foxkiss.gui.holders;

import lombok.EqualsAndHashCode;
import lombok.Value;
import ru.moscow.foxkiss.auction.AuctionCurrency;
import ru.moscow.foxkiss.gui.AuctionViewType;

import java.util.UUID;

@Value
@EqualsAndHashCode(callSuper = true)
public class ConfirmMenuHolder extends AuctionMenuHolder {
    long confirmLotId;
    int confirmAmount;
    boolean isInventoryBuy;

    public ConfirmMenuHolder(AuctionCurrency currency, UUID viewer, long confirmLotId, int confirmAmount, boolean isInventoryBuy) {
        super(AuctionViewType.CONFIRM, currency, viewer, 0, null, null, null, null);
        this.confirmLotId = confirmLotId;
        this.confirmAmount = confirmAmount;
        this.isInventoryBuy = isInventoryBuy;
    }
}