package ru.moscow.foxkiss.placeholders;

public record CachedPlayerStats(int sold, double money, long timestamp) {

    public boolean isExpired(long currentTime, long maxAge) {
        return (currentTime - timestamp) >= maxAge;
    }
}
