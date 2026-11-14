package ru.moscow.foxkiss.gui.holders;

import lombok.Getter;
import org.bukkit.inventory.Inventory;
import org.jetbrains.annotations.NotNull;
import ru.moscow.foxkiss.auction.AuctionCurrency;
import ru.moscow.foxkiss.gui.AuctionViewType;

import java.util.UUID;

@Getter
public class InventoryMenuHolder extends AuctionMenuHolder {

    private final long lotId;
    private final long prevLotId;
    private final long nextLotId;
    private final int currentIndex;
    private final int total;

    public InventoryMenuHolder(AuctionCurrency currency, UUID viewer, long lotId, long prevLotId, long nextLotId, int currentIndex, int total) {
        super(AuctionViewType.INVENTORY, currency, viewer, 0, null, null, null, null);
        this.lotId = lotId;
        this.prevLotId = prevLotId;
        this.nextLotId = nextLotId;
        this.currentIndex = currentIndex;
        this.total = total;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return super.getInventory();
    }
}
