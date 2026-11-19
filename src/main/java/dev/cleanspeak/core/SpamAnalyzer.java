package dev.cleanspeak.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class SpamAnalyzer {

    List<RuleMatch> analyze(
            NormalizedText text,
            List<ConversationTracker.RecentMessage> recent,
            SpamPolicy policy,
            long now
    ) {
        List<RuleMatch> matches = new ArrayList<>();
        int length = text.visible().length();
        if (length > policy.maxMessageLength()) {
            matches.add(match("spam.too-long", policy.spamAction(), 0.8D,
                    length, length, "message length " + length + " exceeds " + policy.maxMessageLength()));
        }

        int repeats = longestRun(text.visible());
        if (repeats >= policy.maxRepeatedCharacterRun()) {
            matches.add(match("spam.repeated-characters", policy.spamAction(), 0.7D,
                    0, text.visible().length(), "repeated character run " + repeats));
        }

        String original = text.original();
        int letters = 0;
        int upper = 0;
        for (int i = 0; i < original.length(); ) {
            int cp = original.codePointAt(i);
            if (Character.isLetter(cp)) {
                letters++;
                if (Character.isUpperCase(cp)) {
                    upper++;
                }
            }
            i += Character.charCount(cp);
        }
        if (letters >= 20 && (double) upper / letters >= policy.maxCapsRatio()) {
            matches.add(match("spam.excessive-caps", policy.capsAction(), 0.6D,
                    0, text.visible().length(), "uppercase ratio " + String.format(Locale.ROOT, "%.2f", (double) upper / letters)));
        }

        int duplicateCount = 1;
        int windowStart = Math.max(0, recent.size() - policy.duplicateThreshold());
        for (int i = windowStart; i < recent.size(); i++) {
            ConversationTracker.RecentMessage message = recent.get(i);
            if (now - message.timestampMillis() <= policy.windowSeconds() * 1000L
                    && message.analysisText().equalsIgnoreCase(text.visible())) {
                duplicateCount++;
            }
        }
        if (duplicateCount >= policy.duplicateThreshold()) {
            matches.add(match("spam.duplicate", policy.duplicateAction(), 0.75D,
                    0, text.visible().length(), "duplicate or near-duplicate message count " + duplicateCount));
        }

        int recentInWindow = 0;
        for (ConversationTracker.RecentMessage message : recent) {
            if (now - message.timestampMillis() <= policy.windowSeconds() * 1000L) {
                recentInWindow++;
            }
        }
        if (recentInWindow + 1 >= policy.maxMessagesPerWindow()) {
            matches.add(match("spam.flood", policy.spamAction(), 0.75D,
                    0, text.visible().length(), "messages in " + policy.windowSeconds() + "s: " + (recentInWindow + 1)));
        }

        int urls = countUrls(text.visible());
        if (urls > policy.maxUrls()) {
            matches.add(match("spam.too-many-links", policy.spamAction(), 0.7D,
                    0, text.visible().length(), "link count " + urls));
        }
        return matches;
    }

    private static RuleMatch match(
            String id,
            dev.cleanspeak.api.ModerationAction action,
            double score,
            int start,
            int end,
            String detail
    ) {
        return new RuleMatch(id, dev.cleanspeak.api.Category.SPAM, action,
                MatchStrength.DIRECT, score, start, end, detail, ContextAnalyzer.Verdict.NONE);
    }

    private static int longestRun(String value) {
        int longest = 0;
        int current = 0;
        int previous = -1;
        for (int i = 0; i < value.length(); ) {
            int cp = value.codePointAt(i);
            if (cp == previous) {
                current++;
            } else {
                previous = cp;
                current = 1;
            }
            longest = Math.max(longest, current);
            i += Character.charCount(cp);
        }
        return longest;
    }

    private static int countUrls(String value) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?iu)(?:https?://|www\\.)[^\\s<>]+|\\b(?:[a-z0-9-]+\\.)+[a-z]{2,}\\b")
                .matcher(value);
        int count = 0;
        while (matcher.find() && count < 20) {
            count++;
        }
        return count;
    }
}
