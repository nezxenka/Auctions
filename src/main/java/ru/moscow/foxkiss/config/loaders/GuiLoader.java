package ru.moscow.foxkiss.config.loaders;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import ru.moscow.foxkiss.config.ConfigValues;
import java.util.HashSet;
import java.util.Set;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class GuiLoader {

    public ConfigValues.AuctionGuiConfig loadMenu(ConfigurationSection section) {
        ConfigurationSection symbolSection = section.getConfigurationSection("symbol_value");
        ConfigurationSection sortingSection = section.getConfigurationSection("sorting");
        ConfigurationSection categoryNames = section.getConfigurationSection("category-names");
        ConfigurationSection exitAction = section.getConfigurationSection("exit-action");
        ConfigurationSection groupedItems = section.getConfigurationSection("grouped-items");
        ConfigurationSection navigation = section.getConfigurationSection("navigation");
        ConfigurationSection categoryButton = navigation.getConfigurationSection("categories");
        SymbolsLoader.Symbols symbols = new SymbolsLoader().load(symbolSection);

        List<Integer> slots = section.getIntegerList("active-slots");
        if (slots.isEmpty()) {
            for (int i = 0; i < 36; i++) slots.add(i);
        }

        return new ConfigValues.AuctionGuiConfig(
                section.getInt("menu-size"),
                List.copyOf(slots),
                loadTitles(section),
                loadStringMap(sortingSection, false),
                loadStringMap(categoryNames, true),
                symbols.symbol(),
                loadSortMenu(section),
                loadCategoryMenu(section, categoryNames, categoryButton),
                loadItemLore(symbolSection),
                loadNavigation(navigation, categoryButton),
                loadButton(exitAction),
                GlassPaneLoader.load(section.getMapList("glass-selling")),
                GlassPaneLoader.load(section.getMapList("glass-expired")),
                groupedItems == null ? null : groupedItems.getString("display_name"),
                groupedItems == null ? List.of() : List.copyOf(groupedItems.getStringList("lore"))
        );
    }

    public ConfigValues.ConfirmMenuConfig loadConfirmMenu(ConfigurationSection section) {
        ConfigurationSection confirm = section.getConfigurationSection("confirm");
        ConfigurationSection cancel = section.getConfigurationSection("cancel");
        return new ConfigValues.ConfirmMenuConfig(
                section.getString("title", ""),
                section.getBoolean("enable-confirm-menu", true),
                section.getInt("item-slot"),
                section.getInt("size"),
                loadConfirmButton(confirm),
                loadConfirmButton(cancel),
                GlassPaneLoader.load(section.getMapList("glass"))
        );
    }

    public ConfigValues.QuantityMenuConfig loadQuantityMenu(ConfigurationSection section) {
        ConfigurationSection decreaseTen = section.getConfigurationSection("decrease-10");
        ConfigurationSection decreaseOne = section.getConfigurationSection("decrease-1");
        ConfigurationSection amount = section.getConfigurationSection("amount");
        ConfigurationSection increaseOne = section.getConfigurationSection("increase-1");
        ConfigurationSection increaseTen = section.getConfigurationSection("increase-10");
        return new ConfigValues.QuantityMenuConfig(
                section.getString("title", ""),
                section.getInt("slot-amount"),
                section.getInt("size-menu"),
                loadButton(decreaseTen),
                loadButton(decreaseOne),
                loadButton(amount),
                loadButton(increaseOne),
                loadButton(increaseTen),
                GlassPaneLoader.load(section.getMapList("glass"))
        );
    }

    private ConfigValues.TitlesConfig loadTitles(ConfigurationSection section) {
        ConfigurationSection titles = section.getConfigurationSection("titles");
        return new ConfigValues.TitlesConfig(
                titles.getString("main"),
                titles.getString("selling"),
                titles.getString("expired")
        );
    }

    private ConfigValues.SortMenuConfig loadSortMenu(ConfigurationSection section) {
        ConfigurationSection sortMenu = section.getConfigurationSection("sort-menu");
        return new ConfigValues.SortMenuConfig(
                Material.matchMaterial(sortMenu.getString("material")),
                sortMenu.getString("name"),
                sortMenu.getString("selected-prefix"),
                sortMenu.getString("unselected-prefix"),
                sortMenu.getString("footer")
        );
    }

    private ConfigValues.ItemLoreConfig loadItemLore(ConfigurationSection symbolSection) {
        ConfigurationSection itemLore = symbolSection.getConfigurationSection("item-lore");
        ConfigurationSection singleItemLore = symbolSection.getConfigurationSection("item-lore_one");
        ConfigurationSection buyLore = symbolSection.getConfigurationSection("item-lore_buy_one_item");
        ConfigurationSection discordLore = symbolSection.getConfigurationSection("item-lore_discord");
        ConfigurationSection storeLore = symbolSection.getConfigurationSection("item-lore_store");
        ConfigurationSection discordStoreLore = symbolSection.getConfigurationSection("item-lore_discord_store");
        return new ConfigValues.ItemLoreConfig(
                itemLore.getStringList("lore"),
                singleItemLore.getStringList("lore"),
                buyLore.getStringList("lore"),
                discordLore.getStringList("lore"),
                discordLore.getStringList("lore_one"),
                storeLore.getStringList("lore"),
                storeLore.getStringList("lore_one"),
                discordStoreLore.getStringList("lore"),
                discordStoreLore.getStringList("lore_one")
        );
    }

    private ConfigValues.CategoryMenuConfig loadCategoryMenu(ConfigurationSection section, ConfigurationSection names, ConfigurationSection categoryButton) {
        ConfigurationSection menu = section.getConfigurationSection("category-menu");
        String selectedPrefix = menu.getString("selected-prefix");
        String unselectedPrefix = menu.getString("unselected-prefix");
        List<ConfigValues.CategoryEntry> entries = null;
        if (menu.contains("entries")) {
            entries = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            for (Map<?, ?> entry : menu.getMapList("entries")) {
                String id = entry.get("id").toString().toLowerCase();
                if (!ids.add(id)) throw new IllegalArgumentException("Категория повторяется в меню: " + id);
                String name = entry.containsKey("name") ? entry.get("name").toString() : names.getString(id, id);
                String selected = entry.containsKey("selected") ? entry.get("selected").toString() : "{prefix}{name}";
                String unselected = entry.containsKey("unselected") ? entry.get("unselected").toString() : "{prefix}{name}";
                entries.add(new ConfigValues.CategoryEntry(id,
                        selected.replace("{prefix}", selectedPrefix).replace("{name}", name),
                        unselected.replace("{prefix}", unselectedPrefix).replace("{name}", name)));
            }
            entries = List.copyOf(entries);
        }
        List<String> lore = categoryButton.getStringList("lore");
        if (!lore.isEmpty()) {
            entries = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            for (String line : lore) {
                int start = line.indexOf("{category:");
                if (start < 0) {
                    entries.add(new ConfigValues.CategoryEntry("", line, line));
                    continue;
                }
                int end = line.indexOf('}', start);
                if (end < 0) throw new IllegalArgumentException("Не закрыт маркер категории: " + line);
                String id = line.substring(start + 10, end).toLowerCase();
                if (id.isEmpty() || !ids.add(id)) throw new IllegalArgumentException("Пустая или повторная категория: " + id);
                String marker = line.substring(start, end + 1);
                entries.add(new ConfigValues.CategoryEntry(id, line.replace(marker, selectedPrefix), line.replace(marker, unselectedPrefix)));
            }
            entries = List.copyOf(entries);
        }
        return new ConfigValues.CategoryMenuConfig(Material.matchMaterial(menu.getString("material")),
                menu.getString("name"), selectedPrefix, unselectedPrefix, menu.getString("footer"), entries);
    }

    private ConfigValues.NavigationConfig loadNavigation(ConfigurationSection navigation, ConfigurationSection categories) {
        ConfigurationSection previous = navigation.getConfigurationSection("previous");
        ConfigurationSection refresh = navigation.getConfigurationSection("refresh");
        ConfigurationSection next = navigation.getConfigurationSection("next");
        ConfigurationSection selling = navigation.getConfigurationSection("selling");
        ConfigurationSection expired = navigation.getConfigurationSection("expired");
        ConfigurationSection sort = navigation.getConfigurationSection("sort");
        return new ConfigValues.NavigationConfig(
                loadNavButton(previous),
                loadNavButton(refresh),
                loadNavButton(next),
                loadNavButton(selling),
                loadNavButton(expired),
                loadNavButton(sort),
                loadNavButton(categories)
        );
    }

    private ConfigValues.ConfirmButtonConfig loadConfirmButton(ConfigurationSection section) {
        MaterialParser.ParsedMaterial parsed = MaterialParser.parse(section);
        return new ConfigValues.ConfirmButtonConfig(
                parsed.material(),
                section.getString("name", ""),
                section.getStringList("lore"),
                parsed.skullTexture(),
                SlotLoader.loadSlots(section),
                ActionLoader.load(section),
                loadModelData(section)
        );
    }

    private ConfigValues.ButtonConfig loadButton(ConfigurationSection section) {
        MaterialParser.ParsedMaterial parsed = MaterialParser.parse(section);
        return new ConfigValues.ButtonConfig(
                parsed.material(),
                section.getString("name", ""),
                List.copyOf(section.getStringList("lore")),
                parsed.skullTexture(),
                ActionLoader.load(section),
                SlotLoader.loadSlots(section),
                loadModelData(section)
        );
    }

    private ConfigValues.NavigationButton loadNavButton(ConfigurationSection section) {
        MaterialParser.ParsedMaterial parsed = MaterialParser.parse(section);
        return new ConfigValues.NavigationButton(
                section.getInt("slot"),
                parsed.material(),
                section.getString("name"),
                section.getStringList("lore"),
                parsed.skullTexture(),
                ActionLoader.load(section),
                loadModelData(section)
        );
    }

    private Integer loadModelData(ConfigurationSection section) {
        return section.isSet("custom-model-data")
                ? section.getInt("custom-model-data")
                : null;
    }

    private Map<String, String> loadStringMap(ConfigurationSection section, boolean lowerCaseKeys) {
        Map<String, String> map = new HashMap<>();
        for (String key : section.getKeys(false)) {
            map.put(lowerCaseKeys ? key.toLowerCase() : key, section.getString(key));
        }
        return Map.copyOf(map);
    }
}
