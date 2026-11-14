package ru.moscow.foxkiss.gui.enums;

public enum ActionType {
    MAIN,
    SELLING,
    HISTORY,
    PROFILE,
    DISCORD,
    STORE,
    EXPIRED,
    SORT,
    CATEGORIES,
    PREVIOUS,
    NEXT,
    REFRESH,
    CONFIRM,
    CANCEL,
    CLOSE,
    INVENTORY_PREV,
    INVENTORY_NEXT,
    DECREASE_10,
    DECREASE_1,
    INCREASE_1,
    INCREASE_10,
    BUY;

    public static ActionType get(String string) {
        try {
            return valueOf(string.toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
