package dev.cleanspeak.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts links and applies the configured link policy. A link is not blocked
 * merely because it exists; only configured scam/blocked/advertising rules
 * apply.
 */
final class LinkAnalyzer {

    private static final Pattern URL = Pattern.compile(
            "(?iu)(?:https?://|www\\.)[^\\s<>]+|\\b(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+" +
                    "[a-z]{2,}(?::\\d{2,5})?(?:/[^\\s<>]*)?"
    );

    List<RuleMatch> analyze(NormalizedText text, LinkPolicy policy) {
        if (!policy.scamEnabled() && !policy.advertisingEnabled()) {
            return List.of();
        }
        List<RuleMatch> matches = new ArrayList<>();
        Matcher matcher = URL.matcher(text.visible());
        int count = 0;
        while (matcher.find() && count < 5) {
            count++;
            String raw = trimTrailing(matcher.group());
            String host = hostOf(raw);
            if (host == null || host.isBlank()) {
                continue;
            }
            if (allowed(host, policy.allowlist())) {
                continue;
            }
            if (policy.scamEnabled() && blocked(host, policy.blocklist())) {
                matches.add(new RuleMatch(
                        "link.blocked-domain",
                        dev.cleanspeak.api.Category.SCAM_ADVERTISING,
                        policy.scamAction(),
                        MatchStrength.DIRECT,
                        0.95D,
                        matcher.start(),
                        matcher.start() + raw.length(),
                        "blocked link domain: " + host,
                        ContextAnalyzer.Verdict.NONE
                ));
                continue;
            }
            if (policy.scamEnabled() && host.startsWith("xn--")) {
                matches.add(new RuleMatch(
                        "link.punycode",
                        dev.cleanspeak.api.Category.SCAM_ADVERTISING,
                        dev.cleanspeak.api.ModerationAction.REVIEW,
                        MatchStrength.DIRECT,
                        0.65D,
                        matcher.start(),
                        matcher.start() + raw.length(),
                        "punycode link domain requires review: " + host,
                        ContextAnalyzer.Verdict.NONE
                ));
                continue;
            }
            if (policy.scamEnabled() && policy.suspiciousShorteners().contains(host)) {
                matches.add(new RuleMatch(
                        "link.shortener",
                        dev.cleanspeak.api.Category.SCAM_ADVERTISING,
                        dev.cleanspeak.api.ModerationAction.REVIEW,
                        MatchStrength.DIRECT,
                        0.55D,
                        matcher.start(),
                        matcher.start() + raw.length(),
                        "shortened link domain requires review: " + host,
                        ContextAnalyzer.Verdict.NONE
                ));
                continue;
            }
            String tld = tldOf(host);
            if (policy.scamEnabled() && tld != null && policy.suspiciousTlds().contains(tld)) {
                matches.add(new RuleMatch(
                        "link.suspicious-tld",
                        dev.cleanspeak.api.Category.SCAM_ADVERTISING,
                        dev.cleanspeak.api.ModerationAction.REVIEW,
                        MatchStrength.DIRECT,
                        0.55D,
                        matcher.start(),
                        matcher.start() + raw.length(),
                        "suspicious link TLD requires review: " + tld,
                        ContextAnalyzer.Verdict.NONE
                ));
                continue;
            }
            if (policy.advertisingEnabled()) {
                matches.add(new RuleMatch(
                        "link.advertising",
                        dev.cleanspeak.api.Category.SCAM_ADVERTISING,
                        policy.advertisingAction(),
                        MatchStrength.DIRECT,
                        0.7D,
                        matcher.start(),
                        matcher.start() + raw.length(),
                        "non-allowlisted link while advertising protection is enabled",
                        ContextAnalyzer.Verdict.NONE
                ));
            }
            if (matches.size() >= 3) {
                break;
            }
        }
        return matches;
    }

    private static String trimTrailing(String value) {
        int end = value.length();
        while (end > 0) {
            char c = value.charAt(end - 1);
            if (c == '.' || c == ',' || c == '!' || c == '?' || c == ')' || c == ']' || c == '}' || c == ';' || c == ':') {
                end--;
            } else {
                break;
            }
        }
        return value.substring(0, end);
    }

    private static String hostOf(String raw) {
        String value = raw.toLowerCase(Locale.ROOT);
        try {
            if (!value.contains("://")) {
                value = "http://" + value;
            }
            java.net.URI uri = java.net.URI.create(value);
            String host = uri.getHost();
            if (host == null) {
                return null;
            }
            if (host.startsWith("www.")) {
                host = host.substring(4);
            }
            return host;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String tldOf(String host) {
        int dot = host.lastIndexOf('.');
        if (dot < 0 || dot == host.length() - 1) {
            return null;
        }
        return host.substring(dot + 1);
    }

    private static boolean allowed(String host, java.util.Set<String> allowlist) {
        for (String allowed : allowlist) {
            String normalized = allowed.toLowerCase(Locale.ROOT);
            if (host.equals(normalized) || host.endsWith("." + normalized)) {
                return true;
            }
        }
        return false;
    }

    private static boolean blocked(String host, java.util.Set<String> blocklist) {
        for (String blocked : blocklist) {
            String normalized = blocked.toLowerCase(Locale.ROOT);
            if (host.equals(normalized) || host.endsWith("." + normalized)) {
                return true;
            }
        }
        return false;
    }
}
