package dev.cleanspeak.core.action;

import dev.cleanspeak.api.Category;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure parsing and command expansion for actions.yml. */
public final class ActionEngine {

    private static final Pattern LINE = Pattern.compile("^\\s*\\[(\\d+)\\s*:\\s*(\\d+)]\\s*(.+?)\\s*$");

    private static final Set<String> DANGEROUS = Set.of(
            "ban", "ban-ip", "banip", "tempban", "ipban", "kick",
            "op", "deop", "pardon", "whitelist", "stop", "restart"
    );

    private ActionEngine() {
    }

    public static Optional<ActionSettings.Action> parse(String line) {
        if (line == null || line.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = LINE.matcher(line);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        int threshold;
        int interval;
        try {
            threshold = Integer.parseInt(matcher.group(1));
            interval = Integer.parseInt(matcher.group(2));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        if (threshold < 1) {
            return Optional.empty();
        }
        String action = matcher.group(3).trim();
        if (action.isEmpty()) {
            return Optional.empty();
        }
        String lower = action.toLowerCase(Locale.ROOT);
        if (lower.equals("[alert]")) {
            return Optional.of(action(threshold, interval, ActionSettings.Kind.ALERT, "", line));
        }
        if (lower.equals("[block]")) {
            return Optional.of(action(threshold, interval, ActionSettings.Kind.BLOCK, "", line));
        }
        if (lower.equals("[review]")) {
            return Optional.of(action(threshold, interval, ActionSettings.Kind.REVIEW, "", line));
        }
        return Optional.of(action(threshold, interval, ActionSettings.Kind.COMMAND,
                stripSlash(action), line));
    }

    public static boolean isDangerous(String command) {
        if (command == null) {
            return true;
        }
        String value = command.trim();
        while (value.startsWith("/")) {
            value = value.substring(1).trim();
        }
        String first = value.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        if (first.contains(":")) {
            first = first.substring(first.indexOf(':') + 1);
        }
        return DANGEROUS.contains(first);
    }

    public static List<String> commandsFor(
            ActionSettings settings,
            Category category,
            int count,
            UUID playerId,
            String playerName,
            String action,
            String requestId,
            String reason
    ) {
        if (!settings.enabled() || category == null) {
            return List.of();
        }
        ActionSettings.Check check = settings.checks().get(category);
        if (check == null || !check.enabled()) {
            return List.of();
        }
        List<String> commands = new ArrayList<>();
        for (ActionSettings.Action entry : check.actions()) {
            if (entry.kind() != ActionSettings.Kind.COMMAND || !entry.matches(count)) {
                continue;
            }
            String command = expand(entry.value(), playerId, playerName, category, count,
                    action, requestId, reason);
            if (command.isBlank() || command.contains("\n") || command.contains("\r")) {
                continue;
            }
            if (!settings.allowDangerousCommands() && isDangerous(command)) {
                continue;
            }
            commands.add(command);
        }
        return commands;
    }

    public static String expand(
            String raw,
            UUID playerId,
            String playerName,
            Category category,
            int count,
            String action,
            String requestId,
            String reason
    ) {
        return raw
                .replace("%player%", playerName == null ? "" : playerName)
                .replace("%uuid%", playerId == null ? "" : playerId.toString())
                .replace("%category%", category == null ? "" : category.name())
                .replace("%check%", category == null ? "" : category.name())
                .replace("%count%", Integer.toString(count))
                .replace("%action%", action == null ? "" : action)
                .replace("%request%", requestId == null ? "" : requestId)
                .replace("%reason%", reason == null ? "" : reason);
    }

    private static ActionSettings.Action action(
            int threshold,
            int interval,
            ActionSettings.Kind kind,
            String value,
            String raw
    ) {
        return new ActionSettings.Action(threshold, interval, kind, value, raw);
    }

    private static String stripSlash(String command) {
        String value = command.trim();
        while (value.startsWith("/")) {
            value = value.substring(1).trim();
        }
        return value;
    }
}
