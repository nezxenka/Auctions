
package ru.moscow.foxkiss.config.loaders;

import org.bukkit.Material;
import ru.moscow.foxkiss.config.ConfigValues;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class GlassPaneLoader {

    public static Map<Integer, ConfigValues.GlassPane> load(List<Map<?, ?>> entries) {
        Map<Integer, ConfigValues.GlassPane> panes = new HashMap<>();

        for (Map<?, ?> entry : entries) {
            Material material = Material.matchMaterial((String) entry.get("glass-type"));
            String name = (String) entry.get("display-name");
            Integer modelData = entry.containsKey("custom-model-data")
                    ? (Integer) entry.get("custom-model-data")
                    : null;

            ConfigValues.GlassPane pane = new ConfigValues.GlassPane(material, name, modelData);

            if (entry.get("slots") instanceof List<?> slots) {
                for (Object value : slots) {
                    if (value instanceof Number number) {
                        panes.put(number.intValue(), pane);
                    }
                }
            } else if (entry.get("slot") instanceof Number number) {
                panes.put(number.intValue(), pane);
            }
        }

        return Map.copyOf(panes);
    }
}
