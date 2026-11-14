package ru.moscow.foxkiss.config.loaders;

import org.bukkit.configuration.ConfigurationSection;
import ru.moscow.foxkiss.config.ConfigValues;
import ru.moscow.foxkiss.config.SalesHistoryConfig;
import ru.moscow.foxkiss.gui.actions.Action;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class SalesHistoryLoader {

    public SalesHistoryConfig load(ConfigurationSection root) {
        ConfigurationSection menu = root.getConfigurationSection("menu");
        ConfigurationSection button = root.getConfigurationSection("button");
        ConfigurationSection entry = root.getConfigurationSection("entry");
        ConfigurationSection empty = root.getConfigurationSection("empty");
        ConfigurationSection itemsSection = root.getConfigurationSection("items");

        List<ConfigValues.ButtonConfig> items = new ArrayList<>();
        Set<Integer> reserved = new LinkedHashSet<>();

        for (String key : itemsSection.getKeys(false)) {
            ConfigurationSection itemSection = itemsSection.getConfigurationSection(key);
            if (!itemSection.getBoolean("enabled", true)) continue;

            ConfigValues.ButtonConfig item = loadItem(itemSection, true);
            reserved.addAll(item.slots());
            items.add(item);
        }

        List<Integer> slots = new ArrayList<>();
        for (int slot = menu.getInt("start-slot") - 1, end = menu.getInt("end-slot"); slot < end; slot++) {
            if (!reserved.contains(slot)) slots.add(slot);
        }

        return new SalesHistoryConfig(
                root.getBoolean("enabled"),
                menu.getString("title"),
                menu.getInt("size"),
                List.copyOf(slots),
                loadItem(button, true),
                entry.getBoolean("use-original-item"),
                loadItem(entry, false),
                loadItem(empty, true),
                List.copyOf(items)
        );
    }

    private ConfigValues.ButtonConfig loadItem(ConfigurationSection section, boolean withSlots) {
        MaterialParser.ParsedMaterial parsed = MaterialParser.parse(section);
        List<Integer> slots = new ArrayList<>();

        if (withSlots) {
            for (int slot : SlotLoader.loadSlots(section)) {
                slots.add(slot - 1);
            }
        }

        List<Action> actions = new ArrayList<>();
        for (String raw : section.getStringList("actions")) {
            actions.add(Action.fromString(raw));
        }

        return new ConfigValues.ButtonConfig(
                parsed.material(),
                section.getString("display_name"),
                List.copyOf(section.getStringList("lore")),
                parsed.skullTexture(),
                List.copyOf(actions),
                List.copyOf(slots),
                section.isInt("custom-model-data") ? section.getInt("custom-model-data") : null
        );
    }
}
