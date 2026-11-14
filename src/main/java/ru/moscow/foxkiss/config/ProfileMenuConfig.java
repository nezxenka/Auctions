package ru.moscow.foxkiss.config;

import java.util.List;

public record ProfileMenuConfig(boolean enabled, String title, int size, int maxLength, String emptyValue, ConfigValues.ButtonConfig button, List<ConfigValues.ButtonConfig> items) {
}
