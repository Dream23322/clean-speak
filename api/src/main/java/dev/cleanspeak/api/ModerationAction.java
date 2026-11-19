package dev.cleanspeak.api;

/**
 * Text moderation actions.
 *
 * <p>There is deliberately no ban or kick action. A {@link #REVIEW} decision is
 * not delivered and is held for staff inspection. A model score is an estimate,
 * not proof.</p>
 */
public enum ModerationAction {
    /** Deliver the original message. */
    ALLOW(0),
    /** Do not deliver; ask the player to rephrase. */
    REPHRASE(1),
    /** Do not deliver; hold for staff review. */
    REVIEW(2),
    /** Do not deliver; treat as a blocked message. */
    BLOCK(3);

    private final int severity;

    ModerationAction(int severity) {
        this.severity = severity;
    }

    /**
     * Returns the relative severity.
     *
     * @return {@code 0} for allow through {@code 3} for block
     */
    public int severity() {
        return severity;
    }

    /**
     * Returns the more severe of two actions.
     *
     * @param other action to compare
     * @return the higher-severity action
     */
    public ModerationAction max(ModerationAction other) {
        return other.severity > severity ? other : this;
    }

    /**
     * Returns whether this action permits delivery.
     *
     * @return {@code true} only for {@link #ALLOW}
     */
    public boolean isAllowed() {
        return this == ALLOW;
    }
}
