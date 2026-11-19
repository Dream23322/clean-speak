package dev.cleanspeak.core;

import dev.cleanspeak.api.Category;
import dev.cleanspeak.api.Contribution;
import dev.cleanspeak.api.ModerationAction;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public record TextPolicy(
        boolean shadowMode,
        Map<Category, ModerationAction> categoryActions,
        String policyVersion,
        boolean mlExpected,
        boolean mlMayBlock,
        double minMlConfidence,
        Set<Category> disabledCategories,
        Map<Category, String> displayNames,
        MessageTemplates messages
) {
    public TextPolicy {
        EnumMap<Category, ModerationAction> actions = new EnumMap<>(Category.class);
        if (categoryActions != null) {
            actions.putAll(categoryActions);
        }
        categoryActions = Map.copyOf(actions);

        EnumSet<Category> disabled = EnumSet.noneOf(Category.class);
        if (disabledCategories != null) {
            disabled.addAll(disabledCategories);
        }
        disabledCategories = Set.copyOf(disabled);

        EnumMap<Category, String> names = new EnumMap<>(Category.class);
        if (displayNames != null) {
            names.putAll(displayNames);
        }
        for (Category category : Category.values()) {
            names.putIfAbsent(category, category.playerLabel());
        }
        displayNames = Map.copyOf(names);

        policyVersion = policyVersion == null || policyVersion.isBlank() ? "unknown" : policyVersion;
        minMlConfidence = Math.max(0.0D, Math.min(1.0D, minMlConfidence));
        messages = messages == null ? MessageTemplates.defaults() : messages;
    }

    /**
     * Backwards-compatible constructor for API/tests that only need global
     * category actions.
     */
    public TextPolicy(
            boolean shadowMode,
            Map<Category, ModerationAction> categoryActions,
            String policyVersion,
            boolean mlExpected,
            boolean mlMayBlock,
            double minMlConfidence
    ) {
        this(shadowMode, categoryActions, policyVersion, mlExpected, mlMayBlock, minMlConfidence,
                Set.of(), Map.of(), MessageTemplates.defaults());
    }

    public ModerationAction actionFor(Category category) {
        return categoryActions.getOrDefault(category, ModerationAction.REPHRASE);
    }

    public boolean enabled(Category category) {
        return !disabledCategories.contains(category);
    }

    public String displayName(Category category) {
        return displayNames.getOrDefault(category,
                category == null ? "the server rules" : category.playerLabel());
    }

    public TextPolicy withoutMl() {
        return new TextPolicy(shadowMode, categoryActions, policyVersion, false, false,
                minMlConfidence, disabledCategories, displayNames, messages);
    }

    public Set<Category> enabledCategories() {
        EnumSet<Category> enabled = EnumSet.allOf(Category.class);
        enabled.removeAll(disabledCategories);
        return Set.copyOf(enabled);
    }

    public static TextPolicy defaults() {
        EnumMap<Category, ModerationAction> actions = new EnumMap<>(Category.class);
        actions.put(Category.TARGETED_HARASSMENT, ModerationAction.REPHRASE);
        actions.put(Category.HATE_SLUR, ModerationAction.REPHRASE);
        actions.put(Category.THREAT, ModerationAction.BLOCK);
        actions.put(Category.SPAM, ModerationAction.REPHRASE);
        actions.put(Category.SCAM_ADVERTISING, ModerationAction.BLOCK);
        actions.put(Category.PROFANITY, ModerationAction.REPHRASE);
        return new TextPolicy(true, actions, "dev", false, false, 0.55D);
    }
}
