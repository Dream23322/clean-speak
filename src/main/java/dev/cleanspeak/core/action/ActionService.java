package dev.cleanspeak.core.action;

import dev.cleanspeak.api.Category;
import dev.cleanspeak.api.MessageAudience;
import dev.cleanspeak.api.ModerationAction;
import dev.cleanspeak.api.ModerationDecision;
import dev.cleanspeak.api.ModerationRequest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Counts enforced violations per player, check and audience, then applies the
 * configured actions: alert, block, review or a console command.
 *
 * <p>The service can escalate the decision before delivery, so an action such as
 * {@code [3:0] [block]} takes effect on the same message that reaches the
 * threshold.</p>
 */
public final class ActionService {

    @FunctionalInterface
    public interface CommandSink {
        void run(String command);
    }

    @FunctionalInterface
    public interface AlertSink {
        void alert(ModerationRequest request, ModerationDecision decision, int count,
                   List<ActionSettings.Action> actions);
    }

    private final ActionSettings settings;
    private final CommandSink commandSink;
    private final AlertSink alertSink;
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();

    public ActionService(
            ActionSettings settings,
            CommandSink commandSink,
            AlertSink alertSink
    ) {
        this.settings = settings;
        this.commandSink = commandSink == null ? command -> {
        } : commandSink;
        this.alertSink = alertSink == null ? (request, decision, count, actions) -> {
        } : alertSink;
    }

    public ModerationDecision adjust(ModerationRequest request, ModerationDecision decision) {
        if (!settings.enabled()
                || decision.shadowed()
                || decision.observation()
                || decision.rawAction() == ModerationAction.ALLOW) {
            return decision;
        }
        long now = System.currentTimeMillis();
        ModerationAction override = null;
        List<ActionSettings.Action> matchedAlerts = new ArrayList<>();
        int alertCount = 0;
        List<Category> categories = new ArrayList<>(decision.categories());
        categories.sort(Comparator.comparing(Enum::name));
        for (Category category : categories) {
            ActionSettings.Check check = settings.checks().get(category);
            if (check == null || !check.enabled()) {
                continue;
            }
            int count = increment(request, category, check, now);
            List<ActionSettings.Action> matchedCommands = new ArrayList<>();
            for (ActionSettings.Action action : check.actions()) {
                if (!action.matches(count)) {
                    continue;
                }
                switch (action.kind()) {
                    case ALERT -> {
                        matchedAlerts.add(action);
                        alertCount = Math.max(alertCount, count);
                    }
                    case BLOCK -> override = ModerationAction.BLOCK;
                    case REVIEW -> {
                        if (override == null || override.severity() < ModerationAction.REVIEW.severity()) {
                            override = ModerationAction.REVIEW;
                        }
                    }
                    case COMMAND -> matchedCommands.add(action);
                }
            }

            if (override != null) {
                decision = escalate(decision, override);
            }
            for (ActionSettings.Action action : matchedCommands) {
                runCommand(request, decision, category, count, action);
            }
        }

        // A decision can contain several categories. Fire the alert once per
        // message, not once per category, so staff never see the same
        // cancellation twice.
        if (!matchedAlerts.isEmpty()) {
            alertSink.alert(request, decision, alertCount, List.copyOf(matchedAlerts));
        }
        return decision;
    }

    public int count(UUID playerId, Category category, MessageAudience audience) {
        Counter counter = counters.get(key(playerId, category, audience));
        if (counter == null) {
            return 0;
        }
        synchronized (counter) {
            return counter.count;
        }
    }

    public ActionSettings settings() {
        return settings;
    }

    private int increment(
            ModerationRequest request,
            Category category,
            ActionSettings.Check check,
            long now
    ) {
        String key = key(request.senderId(), category, request.audience());
        Counter counter = counters.computeIfAbsent(key, ignored -> new Counter());
        int resetSeconds = check.resetSeconds() > 0 ? check.resetSeconds() : settings.resetSeconds();
        synchronized (counter) {
            if (now - counter.lastMillis > resetSeconds * 1000L) {
                counter.count = 0;
            }
            counter.count++;
            counter.lastMillis = now;
            return counter.count;
        }
    }

    private void runCommand(
            ModerationRequest request,
            ModerationDecision decision,
            Category category,
            int count,
            ActionSettings.Action action
    ) {
        String command = ActionEngine.expand(
                action.value(), request.senderId(), request.senderName(), category, count,
                decision.action().name(), request.requestId().toString(), decision.staffReason()
        );
        if (command.isBlank() || command.contains("\n") || command.contains("\r")) {
            return;
        }
        if (!settings.allowDangerousCommands() && ActionEngine.isDangerous(command)) {
            return;
        }
        try {
            commandSink.run(command);
        } catch (Throwable ignored) {
            // A broken command must not affect moderation.
        }
    }

    private static ModerationDecision escalate(ModerationDecision decision, ModerationAction override) {
        ModerationAction action = decision.action().max(override);
        ModerationAction raw = decision.rawAction().max(override);
        if (action == decision.action() && raw == decision.rawAction()) {
            return decision;
        }
        return new ModerationDecision(
                decision.requestId(), action, raw, decision.shadowed(), decision.categories(),
                decision.contributions(), decision.staffReason(), decision.playerReason(),
                decision.modelVersion(), decision.policyVersion(), decision.fallback(),
                decision.observation(), decision.channelId(), decision.conversationId(),
                decision.createdAtEpochMillis()
        );
    }

    private static String key(UUID playerId, Category category, MessageAudience audience) {
        return playerId + "|" + category.name() + "|" + audience.name();
    }

    private static final class Counter {
        private int count;
        private long lastMillis;
    }
}
