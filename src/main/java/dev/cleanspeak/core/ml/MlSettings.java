package dev.cleanspeak.core.ml;

import dev.cleanspeak.api.Category;

import java.util.EnumMap;
import java.util.Map;

public record MlSettings(
        boolean enabled,
        String endpoint,
        boolean allowRemoteEndpoint,
        String modelVersion,
        int timeoutMillis,
        Map<Category, Double> reviewThresholds,
        Map<Category, Double> blockThresholds,
        String failureMode
) {
    public MlSettings {
        timeoutMillis = Math.max(100, Math.min(30_000, timeoutMillis));
        endpoint = endpoint == null ? "" : endpoint.trim();
        modelVersion = modelVersion == null || modelVersion.isBlank() ? "configured-local" : modelVersion;
        failureMode = failureMode == null ? "RULES_ONLY" : failureMode;
        reviewThresholds = immutable(reviewThresholds);
        blockThresholds = immutable(blockThresholds);
    }

    private static Map<Category, Double> immutable(Map<Category, Double> source) {
        EnumMap<Category, Double> map = new EnumMap<>(Category.class);
        if (source != null) {
            source.forEach((category, value) -> {
                if (category != null && value != null) {
                    map.put(category, Math.max(0.0D, Math.min(1.0D, value)));
                }
            });
        }
        return Map.copyOf(map);
    }

    public static MlSettings disabled() {
        return new MlSettings(false, "", false, "disabled", 800, Map.of(), Map.of(), "RULES_ONLY");
    }

    public double reviewThreshold(Category category) {
        return reviewThresholds.getOrDefault(category, 0.55D);
    }

    public double blockThreshold(Category category) {
        return blockThresholds.getOrDefault(category, 0.88D);
    }
}
