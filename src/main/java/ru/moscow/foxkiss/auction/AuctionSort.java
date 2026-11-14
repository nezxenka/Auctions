package ru.moscow.foxkiss.auction;

public enum AuctionSort {
    NEWEST,
    OLDEST,
    EXPENSIVE,
    CHEAP,
    EXPENSIVE_PER_ITEM,
    CHEAP_PER_ITEM;

    public AuctionSort next() {
        AuctionSort[] values = values();
        return values[(ordinal() + 1) % values.length];
    }

    public AuctionSort previous() {
        AuctionSort[] values = values();
        return values[(ordinal() - 1 + values.length) % values.length];
    }
}