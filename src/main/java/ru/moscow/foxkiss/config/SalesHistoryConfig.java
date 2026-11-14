package ru.moscow.foxkiss.config;

import java.util.List;

public record SalesHistoryConfig(boolean enabled, String title, int size, List<Integer> entrySlots, ConfigValues.ButtonConfig button, boolean useOriginalItem, ConfigValues.ButtonConfig entry, ConfigValues.ButtonConfig empty, List<ConfigValues.ButtonConfig> items) {
}
