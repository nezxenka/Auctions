package ru.moscow.foxkiss.utils;

import ru.moscow.foxkiss.auction.AuctionCurrency;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;

public final class PriceFormatter {

    private static final DecimalFormat DECIMALS;
    private static final DecimalFormat INTEGERS;

    static {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols();
        symbols.setGroupingSeparator('.');
        symbols.setDecimalSeparator('.');

        DECIMALS = new DecimalFormat("#,##0.00", symbols);
        INTEGERS = new DecimalFormat("#,##0", symbols);
    }

    public static String format(double amount) {
        DecimalFormat formatter = amount < 1_000 ? DECIMALS : INTEGERS;

        synchronized (formatter) {
            return formatter.format(amount);
        }
    }

    public static String format(double amount, AuctionCurrency currency) {
        if (currency != null && currency.isIntegerOnly()) {
            synchronized (INTEGERS) {
                return INTEGERS.format(Math.round(amount));
            }
        }

        return format(amount);
    }
}