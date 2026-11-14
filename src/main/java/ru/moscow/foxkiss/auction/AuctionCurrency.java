package ru.moscow.foxkiss.auction;

import lombok.Getter;
import ru.moscow.foxkiss.config.ConfigValues;

public enum AuctionCurrency {

    VAULT("vault") {
        @Override
        public String symbol(ConfigValues values) {
            return values.moneyGui().symbol();
        }
    },
    PLAYER_POINTS("playerpoints") {
        @Override
        public String symbol(ConfigValues values) {
            return values.donateGui().symbol();
        }
    };

    @Getter
    private final String configKey;

    AuctionCurrency(String configKey) {
        this.configKey = configKey;
    }

    public abstract String symbol(ConfigValues values);

    public boolean isIntegerOnly() {
        return this == PLAYER_POINTS;
    }
}
