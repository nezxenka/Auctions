
package ru.moscow.foxkiss.gui;

public final class LotDisplayTime {

    public static long ageBucket(long createdAt, long now) {
        long elapsed = Math.max(0L, now - createdAt);

        if (elapsed < 3_600_000) {
            return elapsed / 60_000;
        }

        if (elapsed < 86_400_000) {
            return 60 + elapsed / 3_600_000;
        }

        return 84 + elapsed / 86_400_000;
    }

    public static long daysLeft(long createdAt, long now, int maxStorageDays) {
        long elapsedDays = Math.max(0, now - createdAt) / 86_400_000;

        return Math.max(0, (long) maxStorageDays - elapsedDays);
    }

    public static String formatAge(long bucket) {
        if (bucket < 60) {
            return bucket + " мин. назад";
        }

        if (bucket < 84) {
            return (bucket - 60) + " ч. назад";
        }

        return (bucket - 84) + " дн. назад";
    }
}
