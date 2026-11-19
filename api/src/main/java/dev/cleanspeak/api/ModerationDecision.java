package dev.cleanspeak.api;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The structured result of moderation.
 *
 * <p>{@link #action()} is the effective action and is the value integrations
 * must use for delivery. {@link #rawAction()} is what the rules/model decided
 * before shadow mode was applied. In shadow mode {@code action == ALLOW} while
 * {@code rawAction} still records the would-be action for staff evaluation.</p>
 *
 * <p>No decision action can ban, kick or permanently mute a player.</p>
 *
 * @param requestId id of the request that produced this decision
 * @param action effective action after shadow mode; use this for delivery
 * @param rawAction action before shadow mode
 * @param shadowed whether shadow mode changed the effective action
 * @param categories categories that contributed to the decision
 * @param contributions rule/model evidence; useful for staff, not for players
 * @param staffReason detailed reason for staff logs and review tools
 * @param playerReason short, player-safe reason; may be empty
 * @param modelVersion model version used, or a rules-only marker
 * @param policyVersion configured policy version
 * @param fallback whether part of the check was unavailable and local rules
 *                 were used as a fallback
 * @param observation whether this was an observation-only decision
 * @param channelId route id copied from the request
 * @param conversationId conversation id copied from the request
 * @param createdAtEpochMillis decision creation time
 */
public record ModerationDecision(
        UUID requestId,
        ModerationAction action,
        ModerationAction rawAction,
        boolean shadowed,
        Set<Category> categories,
        List<Contribution> contributions,
        String staffReason,
        String playerReason,
        String modelVersion,
        String policyVersion,
        boolean fallback,
        boolean observation,
        String channelId,
        String conversationId,
        long createdAtEpochMillis
) {

    /**
     * Validates and copies a decision.
     *
     * @throws NullPointerException if a required field is {@code null}
     */
    public ModerationDecision {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(rawAction, "rawAction");
        Objects.requireNonNull(categories, "categories");
        Objects.requireNonNull(contributions, "contributions");
        Objects.requireNonNull(staffReason, "staffReason");
        playerReason = playerReason == null ? "" : playerReason;
        modelVersion = modelVersion == null ? "unknown" : modelVersion;
        policyVersion = policyVersion == null ? "unknown" : policyVersion;
        Objects.requireNonNull(channelId, "channelId");
        Objects.requireNonNull(conversationId, "conversationId");
        categories = Set.copyOf(categories);
        contributions = List.copyOf(contributions);
    }

    /**
     * Returns whether the effective action permits delivery.
     *
     * @return {@code true} when {@link #action()} is {@link ModerationAction#ALLOW}
     */
    public boolean isAllowed() {
        return action == ModerationAction.ALLOW;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The player-facing reason is omitted to reduce accidental logging of
     * moderation details.</p>
     */
    @Override
    public String toString() {
        return "ModerationDecision{" +
                "requestId=" + requestId +
                ", action=" + action +
                ", rawAction=" + rawAction +
                ", shadowed=" + shadowed +
                ", categories=" + categories +
                ", fallback=" + fallback +
                ", observation=" + observation +
                ", channelId='" + channelId + '\'' +
                '}';
    }
}
