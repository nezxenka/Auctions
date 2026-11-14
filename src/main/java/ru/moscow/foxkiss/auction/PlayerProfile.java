package ru.moscow.foxkiss.auction;

public record PlayerProfile(String playerName, String discord, String storeName, int sales) {

    public static PlayerProfile empty(String playerName) {
        return new PlayerProfile(playerName, "", "", 0);
    }
}
