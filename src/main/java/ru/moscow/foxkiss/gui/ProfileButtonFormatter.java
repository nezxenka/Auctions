package ru.moscow.foxkiss.gui;

import ru.moscow.foxkiss.auction.PlayerProfile;
import ru.moscow.foxkiss.config.ConfigValues;

import java.util.ArrayList;
import java.util.List;

public final class ProfileButtonFormatter {

    public static ConfigValues.ButtonConfig format(ConfigValues.ButtonConfig button, String emptyValue, PlayerProfile profile) {
        String discord = profile.discord().isEmpty() ? emptyValue : profile.discord();
        String store = profile.storeName().isEmpty() ? emptyValue : profile.storeName();
        String sales = Integer.toString(profile.sales());

        List<String> lore = new ArrayList<>(button.lore().size());

        for (String line : button.lore()) {
            lore.add(replace(line, discord, store, sales));
        }

        return new ConfigValues.ButtonConfig(button.material(), replace(button.name(), discord, store, sales), List.copyOf(lore), button.skullTexture(), button.actions(), button.slots(), button.customModelData());
    }

    private static String replace(String text, String discord, String store, String sales) {
        return text.replace("{discord}", discord).replace("{store}", store).replace("{sales}", sales);
    }
}