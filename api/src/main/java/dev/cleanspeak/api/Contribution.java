package dev.cleanspeak.api;

import java.util.Objects;

/**
 * A single rule or model contribution to a decision.
 *
 * <p>The source string is stable enough for staff to correlate a decision with
 * configuration. A contribution is evidence for staff review, not proof that
 * the model was correct.</p>
 *
 * @param source rule id or model source name
 * @param category affected category
 * @param score contribution score in the range {@code [0,1]}
 * @param detail short staff-facing detail; must not contain raw player text
 */
public record Contribution(
        String source,
        Category category,
        double score,
        String detail
) {

    /**
     * Validates a contribution.
     *
     * @throws NullPointerException if a field is {@code null}
     * @throws IllegalArgumentException if {@code score} is outside {@code [0,1]}
     */
    public Contribution {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(detail, "detail");
        if (score < 0.0D || score > 1.0D) {
            throw new IllegalArgumentException("score must be in [0,1]");
        }
    }
}
