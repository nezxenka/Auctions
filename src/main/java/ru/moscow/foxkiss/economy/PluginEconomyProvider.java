package ru.moscow.foxkiss.economy;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import ru.moscow.foxkiss.auction.AuctionCurrency;

import java.util.EnumMap;
import java.util.Map;

@RequiredArgsConstructor
public final class PluginEconomyProvider implements EconomyProvider {

    private final Map<AuctionCurrency, CurrencyHandler> handlers = new EnumMap<>(AuctionCurrency.class);

    public void init() {
        handlers.put(AuctionCurrency.VAULT, new VaultHandler());
        handlers.put(AuctionCurrency.PLAYER_POINTS, new PlayerPointsHandler());
    }

    @Override
    public boolean available(AuctionCurrency currency) {
        return handlers.containsKey(currency);
    }

    @Override
    public boolean has(Player player, AuctionCurrency currency, double amount) {
        CurrencyHandler handler = handlers.get(currency);
        return handler != null && handler.has(player, amount);
    }

    @Override
    public boolean withdraw(Player player, AuctionCurrency currency, double amount) {
        CurrencyHandler handler = handlers.get(currency);
        return handler != null && handler.withdraw(player, amount);
    }

    @Override
    public boolean deposit(OfflinePlayer player, AuctionCurrency currency, double amount) {
        CurrencyHandler handler = handlers.get(currency);
        return handler != null && handler.deposit(player, amount);
    }

    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    private static final class VaultHandler implements CurrencyHandler {
        private final VaultApi vault = new VaultApi();

        @Override
        public boolean has(Player player, double amount) {
            return vault.has(player, amount);
        }

        @Override
        public boolean withdraw(Player player, double amount) {
            return vault.withdraw(player, amount);
        }

        @Override
        public boolean deposit(OfflinePlayer player, double amount) {
            return vault.deposit(player, amount);
        }
    }

    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    private static final class PlayerPointsHandler implements CurrencyHandler {
        private final PlayerPointsApi points = new PlayerPointsApi();

        private int toPoints(double amount) {
            if (!Double.isFinite(amount) || amount < 0 || amount > Integer.MAX_VALUE) return -1;
            return amount == 0 ? 0 : Math.max(1, (int) Math.round(amount));
        }

        @Override
        public boolean has(Player player, double amount) {
            int value = toPoints(amount);
            return value >= 0 && points.has(player, value);
        }

        @Override
        public boolean withdraw(Player player, double amount) {
            int value = toPoints(amount);
            return value >= 0 && points.withdraw(player, value);
        }

        @Override
        public boolean deposit(OfflinePlayer player, double amount) {
            int value = toPoints(amount);
            return value >= 0 && points.deposit(player, value);
        }
    }

    private interface CurrencyHandler {
        boolean has(Player player, double amount);
        boolean withdraw(Player player, double amount);
        boolean deposit(OfflinePlayer player, double amount);
    }
}