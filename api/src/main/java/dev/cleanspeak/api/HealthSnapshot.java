package dev.cleanspeak.api;

import java.util.Map;

/**
 * Point-in-time health and integration status for staff and operators.
 *
 * <p>This is diagnostic data. Do not use it to decide whether a particular
 * message is safe to deliver.</p>
 *
 * @param textEnabled whether text moderation is enabled
 * @param textShadowMode whether text shadow mode is active
 * @param mlEnabled whether a model is configured
 * @param mlAvailable whether the configured model is currently usable
 * @param mlStatus short model status string
 * @param mlModelVersion configured or detected model version
 * @param apiQueueDepth number of async requests waiting for a worker
 * @param routes human-readable route statuses, including registered adapters
 * @param timestampEpochMillis snapshot time
 */
public record HealthSnapshot(
        boolean textEnabled,
        boolean textShadowMode,
        boolean mlEnabled,
        boolean mlAvailable,
        String mlStatus,
        String mlModelVersion,
        int apiQueueDepth,
        Map<String, String> routes,
        long timestampEpochMillis
) {

    /**
     * Normalizes optional fields.
     */
    public HealthSnapshot {
        mlStatus = mlStatus == null ? "unknown" : mlStatus;
        mlModelVersion = mlModelVersion == null ? "none" : mlModelVersion;
        routes = routes == null ? Map.of() : Map.copyOf(routes);
    }
}
