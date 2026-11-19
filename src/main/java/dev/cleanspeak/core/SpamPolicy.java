package dev.cleanspeak.core;

import dev.cleanspeak.api.ModerationAction;

public record SpamPolicy(
        int maxMessageLength,
        int maxMessagesPerWindow,
        int windowSeconds,
        int duplicateThreshold,
        double maxCapsRatio,
        int maxRepeatedCharacterRun,
        int maxUrls,
        ModerationAction spamAction,
        ModerationAction duplicateAction,
        ModerationAction capsAction
) {
    public static SpamPolicy defaults() {
        return new SpamPolicy(
                512,
                6,
                10,
                3,
                0.80D,
                14,
                4,
                ModerationAction.REPHRASE,
                ModerationAction.REPHRASE,
                ModerationAction.REPHRASE
        );
    }
}
