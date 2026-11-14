package ru.moscow.foxkiss.auction;

import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

public record AuctionItem(long id, String sellerName, AuctionCurrency currency, ItemStack itemStack, double price, long createdAt, AuctionStatus status, AuctionLotType type, List<ItemStack> inventoryContents, String operationId, long processingStartedAt) {
    public AuctionItem(long id, String sellerName, AuctionCurrency currency, ItemStack itemStack, double price, long createdAt, AuctionStatus status, AuctionLotType type, List<ItemStack> inventoryContents) {
        this(id, sellerName, currency, itemStack, price, createdAt, status, type, inventoryContents, null, 0L);
    }

    public int amount() {
        return itemStack.getAmount();
    }

    public double pricePerItem() {
        return BigDecimal.valueOf(price).divide(BigDecimal.valueOf(Math.max(1, amount())), 2, RoundingMode.HALF_UP).doubleValue();
    }

    public boolean expired(int maxDays) {
        if (maxDays <= 0) return false;
        return createdAt < System.currentTimeMillis() - (long) maxDays * 86_400_000L;
    }

    public double calculatePurchasePrice(int buyAmount) {
        int currentAmount = Math.max(1, amount());
        if (buyAmount >= currentAmount) {
            return price;
        }
        int count = Math.max(1, buyAmount);

        if (currency == AuctionCurrency.PLAYER_POINTS) {
            long total = Math.round(price);
            if (total <= 0) return 0.0;
            long buyPrice = getBuyPrice(total, count, currentAmount);
            return (double) buyPrice;
        } else {
            BigDecimal total = BigDecimal.valueOf(price);
            BigDecimal buy = total.multiply(BigDecimal.valueOf(count))
                    .divide(BigDecimal.valueOf(currentAmount), 2, RoundingMode.HALF_UP);
            double buyVal = buy.doubleValue();
            if (buyVal < 0.01) {
                buyVal = 0.01;
            }
            if (buyVal >= price) {
                buyVal = Math.max(0.01, price - 0.01);
            }
            return buyVal;
        }
    }

    private static long getBuyPrice(long total, int count, int currentAmount) {
        long buyPrice = Math.round((double) total * count / currentAmount);
        if (buyPrice < 1) {
            buyPrice = 1;
        }
        if (total >= currentAmount) {
            long maxAllowed = total - (currentAmount - count);
            if (buyPrice > maxAllowed) {
                buyPrice = maxAllowed;
            }
        } else {
            if (buyPrice >= total) {
                buyPrice = Math.max(1, total - 1);
            }
        }
        return buyPrice;
    }

    public double calculateRemainingPrice(int buyAmount, double paidPrice) {
        int currentAmount = Math.max(1, amount());
        if (buyAmount >= currentAmount) {
            return 0.0;
        }
        if (currency == AuctionCurrency.PLAYER_POINTS) {
            long total = Math.round(price);
            long paid = Math.round(paidPrice);
            return (double) Math.max(0, total - paid);
        } else {
            return BigDecimal.valueOf(price)
                    .subtract(BigDecimal.valueOf(paidPrice))
                    .setScale(2, RoundingMode.HALF_UP)
                    .doubleValue();
        }
    }
}