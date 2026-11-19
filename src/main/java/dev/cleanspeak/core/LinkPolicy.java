package dev.cleanspeak.core;

import dev.cleanspeak.api.ModerationAction;

import java.util.Set;

public record LinkPolicy(
        boolean scamEnabled,
        boolean advertisingEnabled,
        ModerationAction scamAction,
        ModerationAction advertisingAction,
        Set<String> allowlist,
        Set<String> blocklist,
        Set<String> suspiciousShorteners,
        Set<String> suspiciousTlds
) {
    public LinkPolicy {
        allowlist = allowlist == null ? Set.of() : Set.copyOf(allowlist);
        blocklist = blocklist == null ? Set.of() : Set.copyOf(blocklist);
        suspiciousShorteners = suspiciousShorteners == null ? Set.of() : Set.copyOf(suspiciousShorteners);
        suspiciousTlds = suspiciousTlds == null ? Set.of() : Set.copyOf(suspiciousTlds);
    }

    public static LinkPolicy defaults() {
        return new LinkPolicy(
                true,
                false,
                ModerationAction.BLOCK,
                ModerationAction.REPHRASE,
                Set.of(),
                Set.of("grabify.link", "iplogger.org", "iplogger.com", "2no.co", "yip.su", "blasze.com"),
                Set.of("bit.ly", "tinyurl.com", "t.co", "goo.gl", "is.gd", "cutt.ly", "shorturl.at", "rb.gy"),
                Set.of()
        );
    }
}
