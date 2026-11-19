package dev.cleanspeak.core;

import dev.cleanspeak.api.Category;
import dev.cleanspeak.api.ModerationAction;

import java.util.List;

/**
 * The local rule result before optional ML and policy.
 */
public record RuleEvaluation(
        NormalizedText normalized,
        ContextAnalyzer.ContextInfo context,
        List<RuleMatch> matches,
        List<ConversationTracker.RecentMessage> recentMessages
) {
    public RuleEvaluation {
        matches = List.copyOf(matches);
        recentMessages = List.copyOf(recentMessages);
    }

    public boolean hasNonExempt() {
        for (RuleMatch match : matches) {
            if (!match.exempted()) {
                return true;
            }
        }
        return false;
    }

    public ModerationAction strongestNonExemptAction() {
        ModerationAction strongest = ModerationAction.ALLOW;
        for (RuleMatch match : matches) {
            if (!match.exempted()) {
                strongest = strongest.max(match.action());
            }
        }
        return strongest;
    }

    public boolean hasCategory(Category category) {
        for (RuleMatch match : matches) {
            if (match.category() == category && !match.exempted()) {
                return true;
            }
        }
        return false;
    }
}
