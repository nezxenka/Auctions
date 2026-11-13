package ru.moscow.foxkiss.config.loaders;

import org.bukkit.configuration.ConfigurationSection;
import ru.moscow.foxkiss.gui.actions.Action;

import java.util.ArrayList;
import java.util.List;

public final class ActionLoader {

    public static List<Action> load(ConfigurationSection section) {
        List<String> rawActions = section.getStringList("actions");
        List<Action> actions = new ArrayList<>(rawActions.size());

        for (String raw : rawActions) {
            Action action = Action.fromString(raw);

            if (action != null) {
                actions.add(action);
            }
        }

        return List.copyOf(actions);
    }
}