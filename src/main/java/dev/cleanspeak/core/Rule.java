package dev.cleanspeak.core;

import dev.cleanspeak.api.Category;
import dev.cleanspeak.api.ModerationAction;

import java.util.List;
import java.util.Objects;

/**
 * A configurable lexical rule. Rules are data, not executable player input.
 */
public record Rule(
        String id,
        Category category,
        RuleType type,
        ModerationAction action,
        double score,
        boolean enabled,
        List<String> terms,
        String pattern
) {
    public Rule {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("rule id must not be blank");
        }
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(action, "action");
        if (score < 0.0D || score > 1.0D) {
            throw new IllegalArgumentException("rule score must be in [0,1]");
        }
        terms = terms == null ? List.of() : List.copyOf(terms);
        pattern = pattern == null ? null : pattern;
        if (type == RuleType.TERM && terms.isEmpty()) {
            throw new IllegalArgumentException("term rule " + id + " has no terms");
        }
        if (type == RuleType.REGEX && (pattern == null || pattern.isBlank())) {
            throw new IllegalArgumentException("regex rule " + id + " has no pattern");
        }
    }

    public static Rule term(String id, Category category, ModerationAction action, double score, String... terms) {
        return new Rule(id, category, RuleType.TERM, action, score, true, List.of(terms), null);
    }

    public static Rule regex(String id, Category category, ModerationAction action, double score, String pattern) {
        return new Rule(id, category, RuleType.REGEX, action, score, true, List.of(), pattern);
    }
}
