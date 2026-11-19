package dev.cleanspeak.core.ml;

import dev.cleanspeak.api.Category;

import java.util.Map;

public record MlResult(
        Status status,
        Map<Category, Double> scores,
        double confidence,
        String modelVersion,
        String detail
) {
    public enum Status {
        OK,
        DISABLED,
        UNAVAILABLE,
        TIMEOUT,
        ERROR,
        INSECURE_ENDPOINT
    }

    public MlResult {
        scores = scores == null ? Map.of() : Map.copyOf(scores);
        confidence = Math.max(0.0D, Math.min(1.0D, confidence));
        modelVersion = modelVersion == null ? "unknown" : modelVersion;
        detail = detail == null ? "" : detail;
    }

    public boolean usable() {
        return status == Status.OK;
    }

    public double score(Category category) {
        return scores.getOrDefault(category, 0.0D);
    }

    public static MlResult disabled() {
        return new MlResult(Status.DISABLED, Map.of(), 0.0D, "disabled", "model disabled");
    }

    public static MlResult unavailable(String detail) {
        return new MlResult(Status.UNAVAILABLE, Map.of(), 0.0D, "unavailable", detail);
    }

    public static MlResult error(String detail) {
        return new MlResult(Status.ERROR, Map.of(), 0.0D, "error", detail);
    }

    public static MlResult timeout() {
        return new MlResult(Status.TIMEOUT, Map.of(), 0.0D, "timeout", "model request timed out");
    }
}
