package dev.cleanspeak.core;

import java.util.List;

/**
 * Internal analysis forms of a message. Original text is always retained for
 * display by the caller; these forms are never displayed to players.
 */
public record NormalizedText(
        String original,
        String visible,
        String matchText,
        String foldedMatchText,
        List<Token> tokens,
        int formatCharacters,
        int homoglyphCharacters,
        int leetCharacters,
        int collapsedRuns,
        int separatedLetterRuns,
        boolean mixedScripts,
        boolean tooLong
) {

    public NormalizedText {
        tokens = List.copyOf(tokens);
    }

    /**
     * Supporting evidence only. A high score must never become proof by itself.
     */
    public double obfuscationScore() {
        double score = 0.0D;
        if (formatCharacters > 0) {
            score += 0.25D;
        }
        if (homoglyphCharacters > 0) {
            score += 0.25D;
        }
        if (leetCharacters > 0) {
            score += 0.20D;
        }
        if (collapsedRuns > 0) {
            score += 0.15D;
        }
        if (separatedLetterRuns > 0) {
            score += 0.25D;
        }
        if (mixedScripts) {
            score += 0.10D;
        }
        return Math.min(1.0D, score);
    }

    public String staffSignalSummary() {
        StringBuilder out = new StringBuilder();
        if (formatCharacters > 0) {
            out.append("format-chars=").append(formatCharacters).append(' ');
        }
        if (homoglyphCharacters > 0) {
            out.append("homoglyphs=").append(homoglyphCharacters).append(' ');
        }
        if (leetCharacters > 0) {
            out.append("leet=").append(leetCharacters).append(' ');
        }
        if (collapsedRuns > 0) {
            out.append("collapsed-runs=").append(collapsedRuns).append(' ');
        }
        if (separatedLetterRuns > 0) {
            out.append("letter-spacing=").append(separatedLetterRuns).append(' ');
        }
        if (mixedScripts) {
            out.append("mixed-scripts=yes ");
        }
        return out.toString().trim();
    }
}
