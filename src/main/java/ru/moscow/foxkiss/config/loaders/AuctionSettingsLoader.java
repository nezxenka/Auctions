package ru.moscow.foxkiss.config.loaders;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;
import ru.moscow.foxkiss.config.ConfigValues;
import ru.moscow.foxkiss.gui.actions.Action;

import java.util.*;

public record AuctionSettingsLoader(JavaPlugin plugin) {

    public ConfigValues.AuctionData load(ConfigurationSection auction, ConfigurationSection categoriesSection) {
        Map<String, Set<Material>> categories = loadCategories(categoriesSection);
        return new ConfigValues.AuctionData(
                auction.getInt("max-storage-days"),
                categories
        );
    }

    public ConfigValues.InventorySellingConfig loadInventorySelling(ConfigurationSection toggles, ConfigurationSection menu) {
        List<Material> shulkerMaterials = new ArrayList<>();
        for (String raw : menu.getStringList("item-selling")) {
            raw = raw.trim();
            Material m = Material.matchMaterial(raw);
            if (m == null) {
                plugin.getLogger().warning("Неизвестный материал в shulker: " + raw);
            } else {
                shulkerMaterials.add(m);
            }
        }

        ConfigurationSection a = menu.getConfigurationSection("view");
        ConfigValues.InventoryViewConfig view = loadView(a);

        return new ConfigValues.InventorySellingConfig(
                toggles.getBoolean("money_auc"),
                toggles.getBoolean("rubles_auc"),
                toggles.getInt("min-items"),
                toggles.getInt("min-price"),
                toggles.getInt("max-price"),
                menu.getInt("max-items"),
                menu.getString("display-name"),
                menu.getStringList("display-lore"),
                List.copyOf(shulkerMaterials),
                view
        );
    }

    private ConfigValues.InventoryViewConfig loadView(ConfigurationSection section) {
        ConfigurationSection a = section.getConfigurationSection("prev");
        ConfigurationSection b = section.getConfigurationSection("next");
        ConfigurationSection c = section.getConfigurationSection("cancel");
        ConfigurationSection d = section.getConfigurationSection("confirm");
        ConfigValues.InventoryNavButton prev = loadNav(a);
        ConfigValues.InventoryNavButton next = loadNav(b);
        ConfigValues.InventoryActionButton cancel = loadActionBtn(c);
        ConfigValues.InventoryActionButton confirm = loadActionBtn(d);
        Map<Integer, ConfigValues.GlassPane> glass = GlassPaneLoader.load(section.getMapList("glass"));
        return new ConfigValues.InventoryViewConfig(
                section.getInt("start-slot"),
                section.getInt("end-slot"),
                section.getString("title"), prev, next, cancel, confirm,
                section.getInt("item-slot"),
                glass);
    }

    private ConfigValues.InventoryNavButton loadNav(ConfigurationSection section) {
        Material material = Material.matchMaterial(section.getString("material"));
        return new ConfigValues.InventoryNavButton(
                material,
                section.getString("name"),
                section.getStringList("lore"),
                section.getInt("slot"),
                parseActions(section)
        );
    }

    private ConfigValues.InventoryActionButton loadActionBtn(ConfigurationSection section) {
        Material material = Material.matchMaterial(section.getString("material"));
        List<Integer> slots = List.copyOf(section.getIntegerList("slots"));
        return new ConfigValues.InventoryActionButton(
                material,
                section.getString("name"),
                section.getStringList("lore"),
                slots,
                parseActions(section)
        );
    }

    private List<Action> parseActions(ConfigurationSection section) {
        List<Action> actions = new ArrayList<>();
        for (String raw : section.getStringList("actions")) {
            raw = raw.trim();
            Action action = Action.fromString(raw);
            if (action != null) {
                actions.add(action);
            }
        }
        return List.copyOf(actions);
    }

    private Map<String, Set<Material>> loadCategories(ConfigurationSection section) {
        Map<String, Set<Material>> materials = new HashMap<>();

        for (String key : section.getKeys(false)) {
            String category = key.toLowerCase();
            Set<Material> mats = EnumSet.noneOf(Material.class);
            for (String raw : section.getStringList(key)) {
                raw = raw.trim();
                Material m = Material.matchMaterial(raw);
                if (m == null) {
                    plugin.getLogger().warning("Неизвестный материал в категории " + key + ": " + raw);
                } else {
                    mats.add(m);
                }
            }
            materials.put(category, Set.copyOf(mats));
        }
        return Map.copyOf(materials);
    }
}
