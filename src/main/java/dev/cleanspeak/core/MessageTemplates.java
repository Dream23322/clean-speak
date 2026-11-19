package dev.cleanspeak.core;

import dev.cleanspeak.api.Category;
import dev.cleanspeak.api.ModerationAction;

import java.util.Objects;

/**
 * Configurable player-facing explanation templates. Configuration is trusted
 * data; category display names are operator-controlled, not player input.
 */
public record MessageTemplates(
        String rephrase,
        String block,
        String review
) {
    public MessageTemplates {
        rephrase = nonBlank(rephrase, "Your message was not delivered. Please rephrase it without {category}.");
        block = nonBlank(block,
                "Your message was not delivered because it matched a clear {category} rule. "
                        + "Please rephrase it without {category}.");
        review = nonBlank(review,
                "Your message was not delivered and has been sent to staff for review.");
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    public String forAction(ModerationAction action, String categoryDisplayName, java.util.UUID requestId) {
        Objects.requireNonNull(action, "action");
        String template = switch (action) {
            case REPHRASE -> rephrase;
            case BLOCK -> block;
            case REVIEW -> review;
            case ALLOW -> "";
        };
        return template
                .replace("{category}", categoryDisplayName == null ? "the server rules" : categoryDisplayName)
                .replace("{request_id}", requestId == null ? "" : requestId.toString());
    }

    public static MessageTemplates defaults() {
        return new MessageTemplates(null, null, null);
    }
}
