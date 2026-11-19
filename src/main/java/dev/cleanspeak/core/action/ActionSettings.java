package dev.cleanspeak.core.action;

import dev.cleanspeak.api.Category;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Parsed actions.yml policy.
 *
 * <p>Each check has a list of action lines in the Grim-style
 * {@code [threshold:interval] action} form. Special actions are {@code [alert]},
 * {@code [block]} and {@code [review]}; anything else is a console command.
 * Mutes are server-side commands, not built into CleanSpeak.</p>
 */
public record ActionSettings(
        boolean enabled,
        int resetSeconds,
        boolean allowDangerousCommands,
        Map<Category, Check> checks
) {
    public enum Kind {
        ALERT,
        BLOCK,
        REVIEW,
        COMMAND
    }

    public record Action(
            int threshold,
            int interval,
            Kind kind,
            String value,
            String raw
    ) {
        public Action {
            threshold = Math.max(1, threshold);
            interval = Math.max(0, interval);
            kind = kind == null ? Kind.COMMAND : kind;
            value = value == null ? "" : value.trim();
            raw = raw == null ? "" : raw.trim();
        }

        /** Grim semantics: interval 0 runs once at the threshold. */
        public boolean matches(int count) {
            if (count < threshold) {
                return false;
            }
            if (interval == 0) {
                return count == threshold;
            }
            return (count - threshold) % interval == 0;
        }
    }

    public record Check(
            boolean enabled,
            int resetSeconds,
            List<Action> actions
    ) {
        public Check {
            actions = actions == null ? List.of() : List.copyOf(actions);
            resetSeconds = Math.max(30, Math.min(86_400, resetSeconds));
        }
    }

    public ActionSettings {
        resetSeconds = Math.max(30, Math.min(86_400, resetSeconds));
        EnumMap<Category, Check> copy = new EnumMap<>(Category.class);
        if (checks != null) {
            checks.forEach((category, check) -> {
                if (category != null && check != null) {
                    copy.put(category, check);
                }
            });
        }
        checks = Map.copyOf(copy);
    }

    public static ActionSettings disabled() {
        return new ActionSettings(false, 3600, false, Map.of());
    }
}
