package dev.cleanspeak.core;

import dev.cleanspeak.api.Category;
import dev.cleanspeak.api.Contribution;
import dev.cleanspeak.api.ModerationAction;
import dev.cleanspeak.api.ModerationDecision;
import dev.cleanspeak.api.ModerationRequest;
import dev.cleanspeak.core.ml.MlResult;
import dev.cleanspeak.core.ml.MlSettings;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Converts local rule matches and optional model scores into a structured
 * decision. It can never produce a ban, kick or permanent mute.
 */
public final class DecisionPolicy {

    public ModerationDecision decide(
            ModerationRequest request,
            RuleEvaluation evaluation,
            MlResult ml,
            TextPolicy policy,
            MlSettings mlSettings
    ) {
        return decide(request, evaluation, ml, policy, mlSettings, false);
    }

    public ModerationDecision decide(
            ModerationRequest request,
            RuleEvaluation evaluation,
            MlResult ml,
            TextPolicy policy,
            MlSettings mlSettings,
            boolean observation
    ) {
        List<Contribution> contributions = new ArrayList<>();
        Set<Category> categories = EnumSet.noneOf(Category.class);
        Set<Category> reasonCategories = EnumSet.noneOf(Category.class);
        ModerationAction raw = ModerationAction.ALLOW;
        List<String> staffNotes = new ArrayList<>();

        for (RuleMatch match : evaluation.matches()) {
            if (!policy.enabled(match.category())) {
                continue;
            }
            categories.add(match.category());
            ModerationAction effective = match.action().max(policy.actionFor(match.category()));
            if (match.strength() == MatchStrength.CONTEXT) {
                // A message split across entries is suspicious but is not, by
                // itself, proof of a direct threat. It can still stop delivery.
                effective = ModerationAction.REPHRASE.max(
                        effective == ModerationAction.BLOCK ? ModerationAction.REPHRASE : effective
                );
                staffNotes.add("context-match=" + match.ruleId());
            } else if (match.strength() == MatchStrength.OBFUSCATED
                    && match.category() != Category.THREAT
                    && match.category() != Category.SCAM_ADVERTISING) {
                // Aggressive normalisation supports a decision; it does not make
                // an innocent word automatically severe.
                if (effective.severity() > ModerationAction.REPHRASE.severity()) {
                    effective = ModerationAction.REPHRASE;
                }
            }

            if (match.contextVerdict() == ContextAnalyzer.Verdict.EXEMPT) {
                effective = ModerationAction.ALLOW;
                staffNotes.add("context-exempt=" + match.ruleId());
            } else if (match.contextVerdict() == ContextAnalyzer.Verdict.REVIEW) {
                effective = effective.max(ModerationAction.REVIEW);
                if (effective == ModerationAction.BLOCK) {
                    effective = ModerationAction.REVIEW;
                }
                staffNotes.add("context-review=" + match.ruleId());
            }

            if (effective != ModerationAction.ALLOW) {
                reasonCategories.add(match.category());
            }
            raw = raw.max(effective);
            contributions.add(new Contribution(
                    "rule:" + match.ruleId(),
                    match.category(),
                    match.score(),
                    match.detail() + " strength=" + match.strength()
                            + (match.contextVerdict() == ContextAnalyzer.Verdict.NONE
                            ? "" : " context=" + match.contextVerdict())
            ));
        }

        boolean mlFallback = false;
        if (policy.mlExpected()) {
            if (ml == null || !ml.usable()) {
                mlFallback = true;
                staffNotes.add("ml-unavailable=" + (ml == null ? "no-result" : ml.status() + "/" + ml.detail()));
                contributions.add(new Contribution(
                        "model:unavailable",
                        Category.OTHER,
                        0.0D,
                        ml == null ? "no model result" : ml.status() + ": " + ml.detail()
                ));
            } else {
                if (ml.confidence() < policy.minMlConfidence()) {
                    staffNotes.add("ml-low-confidence=" + String.format(java.util.Locale.ROOT, "%.2f", ml.confidence()));
                }
                for (Map.Entry<Category, Double> entry : ml.scores().entrySet()) {
                    Category category = entry.getKey();
                    double score = entry.getValue();
                    if (score < 0.30D || !policy.enabled(category)) {
                        continue;
                    }
                    categories.add(category);
                    contributions.add(new Contribution(
                            "model:" + ml.modelVersion(),
                            category,
                            score,
                            "confidence=" + String.format(java.util.Locale.ROOT, "%.2f", ml.confidence())
                    ));
                    if (ml.confidence() < policy.minMlConfidence()) {
                        continue;
                    }
                    if (score >= mlSettings.blockThreshold(category)) {
                        ModerationAction candidate;
                        if (policy.mlMayBlock()) {
                            candidate = policy.actionFor(category);
                            if (candidate == ModerationAction.ALLOW) {
                                candidate = ModerationAction.REVIEW;
                            }
                        } else {
                            // Scores are estimates, not proof. Without explicit
                            // owner opt-in, a model can only request review.
                            candidate = ModerationAction.REVIEW;
                        }
                        raw = raw.max(candidate);
                        if (candidate != ModerationAction.ALLOW) {
                            reasonCategories.add(category);
                        }
                        staffNotes.add("ml-block-threshold=" + category);
                    } else if (score >= mlSettings.reviewThreshold(category)) {
                        raw = raw.max(ModerationAction.REVIEW);
                        reasonCategories.add(category);
                        staffNotes.add("ml-review-threshold=" + category);
                    }
                }
            }
        }

        // A message that is over the local analysis limit is not delivered; an
        // attacker must not be able to hide a violation past the cap.
        if (evaluation.normalized().tooLong()) {
            raw = raw.max(ModerationAction.REVIEW);
            staffNotes.add("message-too-long");
        }

        boolean shadowed = observation || (policy.shadowMode() && raw != ModerationAction.ALLOW);
        ModerationAction effective = shadowed ? ModerationAction.ALLOW : raw;
        String modelVersion = ml != null && ml.usable() ? ml.modelVersion()
                : (policy.mlExpected() ? "unavailable" : "rules-only");
        String staffReason = buildStaffReason(staffNotes, raw, shadowed, mlFallback, evaluation, ml);
        String playerReason = effective == ModerationAction.ALLOW
                ? ""
                : playerReason(effective, reasonCategories, policy, request.requestId());

        return new ModerationDecision(
                request.requestId(),
                effective,
                raw,
                shadowed,
                categories,
                contributions,
                staffReason,
                playerReason,
                modelVersion,
                policy.policyVersion(),
                mlFallback,
                observation,
                request.channelId(),
                request.conversationId(),
                System.currentTimeMillis()
        );
    }

    private static String buildStaffReason(
            List<String> notes,
            ModerationAction raw,
            boolean shadowed,
            boolean mlFallback,
            RuleEvaluation evaluation,
            MlResult ml
    ) {
        StringBuilder out = new StringBuilder();
        out.append("would-be=").append(raw);
        if (shadowed) {
            out.append(" shadowed=true");
        }
        if (mlFallback) {
            out.append(" fallback=rules-only");
        }
        if (!evaluation.normalized().staffSignalSummary().isBlank()) {
            out.append(" signals=").append(evaluation.normalized().staffSignalSummary());
        }
        if (ml != null && ml.usable()) {
            out.append(" ml-confidence=").append(String.format(java.util.Locale.ROOT, "%.2f", ml.confidence()));
        }
        if (!notes.isEmpty()) {
            out.append(" notes=").append(String.join(",", notes.subList(0, Math.min(notes.size(), 8))));
        }
        if (out.length() > 900) {
            out.setLength(900);
        }
        return out.toString();
    }

    private static String playerReason(
            ModerationAction action,
            Set<Category> categories,
            TextPolicy policy,
            UUID requestId
    ) {
        Category selected = categories.stream()
                .filter(c -> c != Category.OTHER)
                .findFirst()
                .orElse(Category.OTHER);
        String displayName = selected == Category.OTHER
                ? "the server rules"
                : policy.displayName(selected);
        return policy.messages().forAction(action, displayName, requestId);
    }
}
