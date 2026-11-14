package ru.moscow.foxkiss.gui.actions;

import ru.moscow.foxkiss.gui.enums.ActionType;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record Action(ActionType type) {

    private static final Pattern ACTION_PATTERN = Pattern.compile("\\[(\\w+)] ?.*");

    public static Action fromString(String str) {
        if (str == null) return null;
        Matcher matcher = ACTION_PATTERN.matcher(str);
        if (!matcher.matches()) {
            return null;
        }
        ActionType type = ActionType.get(matcher.group(1));
        return type != null ? new Action(type) : null;
    }
}
