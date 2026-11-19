package dev.cleanspeak.core;

import java.util.List;
import java.util.Objects;

public record RuleSet(
        List<Rule> rules,
        LinkPolicy linkPolicy,
        SpamPolicy spamPolicy
) {
    public RuleSet {
        rules = rules == null ? List.of() : List.copyOf(rules);
        Objects.requireNonNull(linkPolicy, "linkPolicy");
        Objects.requireNonNull(spamPolicy, "spamPolicy");
    }

    public static RuleSet empty() {
        return new RuleSet(List.of(), LinkPolicy.defaults(), SpamPolicy.defaults());
    }

    /**
     * A tiny last-resort set used only if the configured rules file cannot be
     * parsed. It is intentionally not a replacement for the full rules file.
     */
    public static RuleSet minimalFallback() {
        return new RuleSet(
                List.of(
                        Rule.regex("fallback.kill-yourself",
                                dev.cleanspeak.api.Category.TARGETED_HARASSMENT,
                                dev.cleanspeak.api.ModerationAction.REPHRASE,
                                0.9D,
                                "\\b(?:kys|kill\\s+your\\s*self|go\\s+die)\\b"),
                        Rule.regex("fallback.direct-threat",
                                dev.cleanspeak.api.Category.THREAT,
                                dev.cleanspeak.api.ModerationAction.BLOCK,
                                0.9D,
                                "\\bi\\s*(?:will|m\\s+gonna|m\\s+going\\s+to)\\s+(?:kill|murder|hurt|stab|shoot)\\s+(?:you|u)\\b")
                ),
                LinkPolicy.defaults(),
                SpamPolicy.defaults()
        );
    }
}
