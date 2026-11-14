package ru.moscow.foxkiss.config.loaders;

import org.bukkit.configuration.ConfigurationSection;

public final class SymbolsLoader {
    public record Symbols(String symbol) {}

    public Symbols load(ConfigurationSection section) {
        return new Symbols(section.getString("symbol"));
    }
}