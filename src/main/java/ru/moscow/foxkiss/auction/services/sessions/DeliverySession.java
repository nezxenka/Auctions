package ru.moscow.foxkiss.auction.services.sessions;

import lombok.Getter;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import org.bukkit.entity.Player;
import ru.moscow.foxkiss.auction.AuctionCurrency;
import ru.moscow.foxkiss.auction.PendingDelivery;
import ru.moscow.foxkiss.auction.services.AuctionTransactionService;
import ru.moscow.foxkiss.config.interfaces.IConfigManager;

import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

@Getter
public final class DeliverySession {
    public final UUID uuid;
    public final String name;
    public final IConfigManager config;
    public final EnumSet<AuctionCurrency> claims = EnumSet.noneOf(AuctionCurrency.class);
    public Player player;
    public boolean inFlight;
    public boolean rescan;
    public boolean allRequested;
    public boolean all;
    public boolean commandBatch;
    public boolean deliveredAny;
    public EnumSet<AuctionCurrency> currencies = EnumSet.noneOf(AuctionCurrency.class);
    public final LongSet prioritizedIds = new LongOpenHashSet();
    public AuctionTransactionService.Receipt receipt;
    public List<PendingDelivery> pending = List.of();
    public int index;
    public boolean receiptPending;
    public DeliverySession(UUID uuid, String name, Player player, IConfigManager config, AuctionTransactionService.Receipt receipt) {
        this.uuid = uuid; this.name = name; this.player = player; this.config = config; this.receipt = receipt;
    }
}
