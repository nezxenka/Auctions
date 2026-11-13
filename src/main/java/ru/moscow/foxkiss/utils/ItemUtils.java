package ru.moscow.foxkiss.utils;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.CustomModelData;
import lombok.Getter;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.io.File;
import java.util.logging.Logger;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class ItemUtils {

    private static final Map<String, PlayerProfile> profileSkull = new HashMap<>();
    private static final Map<String, ItemStack> cacheSkull = new HashMap<>();
    private static final Map<String, String> perevod = new HashMap<>();
    @Getter
    private static MaterialSearchIndex searchIndex = createSearchIndex(Map.of());

    private static MaterialSearchIndex createSearchIndex(Map<String, String> translations) {
        List<String> ids = new ArrayList<>();
        for (Material material : Material.values()) {
            if (!material.name().startsWith("LEGACY_")) ids.add(material.name());
        }
        return new MaterialSearchIndex(translations, ids);
    }


    public static ItemStack named(Material material, String name, List<String> lore, Integer customModelData) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        if (name != null && !name.isEmpty()) {
            meta.displayName(TextUtils.component(name));
        }

        if (customModelData != null) {
            item.setData(DataComponentTypes.CUSTOM_MODEL_DATA, CustomModelData.customModelData().addFloat(customModelData.floatValue()).build());
        }

        if (lore != null && !lore.isEmpty()) {
            List<Component> coloredLore = new ArrayList<>(lore.size());
            for (String line : lore) {
                coloredLore.add(TextUtils.component(line));
            }
            meta.lore(coloredLore);
        }

        item.setItemMeta(meta);
        return item;
    }

    public static ItemStack skull(String base64, String name, List<String> lore, Integer customModelData) {
        if (base64 == null || base64.isBlank()) {
            return named(Material.PLAYER_HEAD, name, lore, customModelData);
        }

        String cacheKey = base64 + "|" + name + "|" + (lore != null ? String.join("", lore) : "") + "|" + customModelData;
        ItemStack cached = cacheSkull.get(cacheKey);
        if (cached != null) {
            return cached.clone();
        }

        ItemStack item = named(Material.PLAYER_HEAD, name, lore, customModelData);
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof SkullMeta skullMeta) {
            if (!profileSkull.containsKey(base64) && profileSkull.size() >= 128) {
                Iterator<String> keys = profileSkull.keySet().iterator(); keys.next(); keys.remove();
            }
            PlayerProfile profile = profileSkull.computeIfAbsent(base64, ItemUtils::createSkullProfile);
            skullMeta.setPlayerProfile(profile);
            item.setItemMeta(skullMeta);
        }

        if (cacheSkull.size() >= 512) {
            Iterator<String> keys = cacheSkull.keySet().iterator(); keys.next(); keys.remove();
        }
        cacheSkull.put(cacheKey, item.clone());
        return item;
    }

    public static boolean isSellable(ItemStack item) {
        return item.getType() != Material.AIR && item.getAmount() > 0;
    }

    public static void loadTranslations(File itemsFile, Logger logger) {
        perevod.clear();

        if (itemsFile == null || !itemsFile.exists()) {
            searchIndex = createSearchIndex(Map.of());
            logger.warning("items.yml не найден, переводы не загружены.");
            return;
        }
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(itemsFile);
        for (String key : cfg.getKeys(false)) {
            if (Material.getMaterial(key) == null) {
                logger.warning("Пропущен не поддерживаемый или не валидный материал: " + key);
                continue;
            }
            perevod.put(key, cfg.getString(key));
        }
        searchIndex = createSearchIndex(perevod);
    }

    public static String getItemDisplayName(ItemStack item) {
        Material material = item.getType();
        String materialName = material.name();
        String translated = perevod.get(materialName);

        return translated != null ? translated : materialName.toLowerCase().replace('_', ' ');
    }

    public static String getTranslation(Material material) {
        if (material == null) return null;
        return perevod.get(material.name());
    }

    public static Set<String> getAllTranslatedMaterials() {
        return Collections.unmodifiableSet(perevod.keySet());
    }

    public static void clearCache() {
        cacheSkull.clear();
        profileSkull.clear();
    }

    private static PlayerProfile createSkullProfile(String texture) {
        UUID uuid = UUID.nameUUIDFromBytes(texture.getBytes(StandardCharsets.UTF_8));
        PlayerProfile profile = Bukkit.createProfile(uuid);
        profile.setProperty(new ProfileProperty("textures", texture));
        return profile;
    }
}