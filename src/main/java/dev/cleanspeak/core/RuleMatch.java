package dev.cleanspeak.core;

import dev.cleanspeak.api.Category;
import dev.cleanspeak.api.ModerationAction;

/**
 * A single rule hit. {@code exempted} and {@code contextVerdict} are filled in
 * after context analysis; policy uses them to avoid punishing quotations and
 * abuse reports.
 */
public record RuleMatch(
        String ruleId,
        Category category,
        ModerationAction action,
        MatchStrength strength,
        double score,
        int start,
        int end,
        String detail,
        ContextAnalyzer.Verdict contextVerdict
) {
    public RuleMatch {
        if (score < 0.0D || score > 1.0D) {
            score = Math.max(0.0D, Math.min(1.0D, score));
        }
        contextVerdict = contextVerdict == null ? ContextAnalyzer.Verdict.NONE : contextVerdict;
    }

    public boolean exempted() {
        return contextVerdict == ContextAnalyzer.Verdict.EXEMPT;
    }

    public RuleMatch withContext(ContextAnalyzer.Verdict verdict) {
        return new RuleMatch(ruleId, category, action, strength, score, start, end, detail, verdict);
    }

    public RuleMatch withDetail(String newDetail) {
        return new RuleMatch(ruleId, category, action, strength, score, start, end, newDetail, contextVerdict);
    }
}
