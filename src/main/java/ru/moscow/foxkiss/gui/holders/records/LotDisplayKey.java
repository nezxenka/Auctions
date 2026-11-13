package ru.moscow.foxkiss.gui.holders.records;

import java.util.Objects;
import ru.moscow.foxkiss.auction.AuctionItem;
import ru.moscow.foxkiss.auction.AuctionStatus;
import ru.moscow.foxkiss.auction.PlayerProfile;

public final class LotDisplayKey {

    private final long id;
    private final int amount;
    private final double price;
    private final long createdAt;
    private final AuctionStatus status;
    private final String discord;
    private final String store;
    private final long sales;
    private final long ageBucket;
    private final long daysLeft;

    private final int hash;

    public LotDisplayKey(AuctionItem item, PlayerProfile profile, long ageBucket, long daysLeft) {
        this.id = item.id();
        this.amount = item.amount();
        this.price = item.price();
        this.createdAt = item.createdAt();
        this.status = item.status();
        this.discord = profile.discord();
        this.store = profile.storeName();
        this.sales = profile.sales();
        this.ageBucket = ageBucket;
        this.daysLeft = daysLeft;
        this.hash = computeHash();
    }

    private int computeHash() {
        int h = 1;
        h = 31 * h + Long.hashCode(id);
        h = 31 * h + amount;
        h = 31 * h + Double.hashCode(price);
        h = 31 * h + Long.hashCode(createdAt);
        h = 31 * h + (status == null ? 0 : status.ordinal());
        h = 31 * h + (discord == null ? 0 : discord.hashCode());
        h = 31 * h + (store == null ? 0 : store.hashCode());
        h = 31 * h + Long.hashCode(sales);
        h = 31 * h + Long.hashCode(ageBucket);
        h = 31 * h + Long.hashCode(daysLeft);
        return h;
    }

    public long id() { return id; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LotDisplayKey other)) return false;
        return id == other.id
                && amount == other.amount
                && Double.compare(price, other.price) == 0
                && createdAt == other.createdAt
                && status == other.status
                && sales == other.sales
                && ageBucket == other.ageBucket
                && daysLeft == other.daysLeft
                && Objects.equals(discord, other.discord)
                && Objects.equals(store, other.store);
    }

    @Override
    public int hashCode() {
        return hash;
    }
}