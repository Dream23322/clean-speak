package dev.cleanspeak.core.ml;

import dev.cleanspeak.api.Category;

import java.util.Map;

public record MlInput(
        String text,
        String channelId,
        String conversationId,
        String policyVersion,
        Map<Category, Double> reviewThresholds
) {
    public MlInput {
        text = text == null ? "" : text;
        channelId = channelId == null ? "" : channelId;
        conversationId = conversationId == null ? "" : conversationId;
        policyVersion = policyVersion == null ? "unknown" : policyVersion;
        reviewThresholds = reviewThresholds == null ? Map.of() : Map.copyOf(reviewThresholds);
    }
}
