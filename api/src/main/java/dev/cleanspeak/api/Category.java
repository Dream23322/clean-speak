package dev.cleanspeak.api;

/**
 * Categories understood by the shared moderation core.
 *
 * <p>Identity terms by themselves are not categories or violations. A category
 * is only reported when a configured rule or model actually matches.</p>
 */
public enum Category {
    /** Targeted harassment of a player or group. */
    TARGETED_HARASSMENT("targeted harassment"),
    /** Hate speech or slurs. */
    HATE_SLUR("hate speech or slurs"),
    /** Threats of harm. */
    THREAT("threats"),
    /** Spam, flooding or repeated messages. */
    SPAM("spam or flooding"),
    /** Scam links or prohibited advertising. */
    SCAM_ADVERTISING("scam links or prohibited advertising"),
    /** Profanity that is configured as a violation. */
    PROFANITY("profanity"),
    /** Any configured category that does not fit the others. */
    OTHER("other");

    private final String playerLabel;

    Category(String playerLabel) {
        this.playerLabel = playerLabel;
    }

    /**
     * Returns a short, player-safe label for this category.
     *
     * <p>Do not assume the label is exhaustive; prefer
     * {@link ModerationDecision#playerReason()} for player-facing text.</p>
     *
     * @return a display label
     */
    public String playerLabel() {
        return playerLabel;
    }
}
