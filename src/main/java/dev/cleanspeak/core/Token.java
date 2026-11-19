package dev.cleanspeak.core;

/**
 * A token from the analysis form. Offsets refer to the visible (NFKC,
 * format-stripped, lower-cased) string, not the original message.
 */
public record Token(
        String strict,
        String folded,
        String collapsed,
        int start,
        int end,
        boolean singleLetter
) {
}
