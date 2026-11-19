package dev.cleanspeak.core;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Builds the internal analysis forms used by the rule engine.
 *
 * <p>Normalisation is intentionally conservative when it comes to identity
 * terms and languages: it never treats another language or mixed scripts as a
 * violation. Aggressive transformations (leet folding, repeated-character
 * collapsing and single-letter concatenation) are reported as evidence and do
 * not, on their own, prove a violation. The policy layer decides whether a
 * match is strong enough.</p>
 */
public final class TextNormalizer {

    public static final int DEFAULT_MAX_CHARS = 1000;

    private static final Map<Integer, Character> FOLD = buildFoldMap();
    private static final Set<Integer> APOSTROPHES = Set.of(
            (int) '\'', (int) '\u2019', (int) '\u2018', (int) '`', (int) '\u00B4'
    );

    private final int maxChars;

    public TextNormalizer() {
        this(DEFAULT_MAX_CHARS);
    }

    public TextNormalizer(int maxChars) {
        if (maxChars < 32) {
            throw new IllegalArgumentException("maxChars must be >= 32");
        }
        this.maxChars = maxChars;
    }

    public NormalizedText normalize(String input) {
        String original = input == null ? "" : input;
        int[] codePoints = original.codePoints().toArray();
        boolean tooLong = codePoints.length > maxChars;
        int limit = Math.min(codePoints.length, maxChars);

        StringBuilder cleaned = new StringBuilder(limit + 16);
        int formatCharacters = 0;
        for (int i = 0; i < limit; i++) {
            int cp = codePoints[i];
            int type = Character.getType(cp);
            if (type == Character.FORMAT) {
                formatCharacters++;
                continue;
            }
            if (type == Character.CONTROL) {
                if (cp == '\n' || cp == '\r' || cp == '\t') {
                    cleaned.append(' ');
                }
                continue;
            }
            if (type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR) {
                cleaned.append(' ');
                continue;
            }
            cleaned.appendCodePoint(cp);
        }

        String nfkc = Normalizer.normalize(cleaned, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .trim();
        if (nfkc.length() > maxChars * 2) {
            nfkc = nfkc.substring(0, maxChars * 2);
        }

        List<Token> tokens = tokenize(nfkc);
        int homoglyphs = 0;
        int leet = 0;
        int collapsedRuns = 0;
        Set<Character.UnicodeScript> scripts = new HashSet<>();

        List<Token> finalTokens = new ArrayList<>(tokens.size());
        for (Token token : tokens) {
            String folded = fold(token.strict());
            String collapsed = collapseRuns(folded);
            if (!folded.equals(token.strict())) {
                int changed = countChanged(token.strict(), folded);
                for (int i = 0; i < Math.min(changed, token.strict().length()); i++) {
                    int cp = token.strict().charAt(i);
                    if (Character.isDigit(cp) || isLeetSymbol(cp)) {
                        leet++;
                    } else {
                        homoglyphs++;
                    }
                }
            }
            if (!collapsed.equals(folded)) {
                collapsedRuns++;
            }
            for (int i = 0; i < token.strict().length(); ) {
                int cp = token.strict().codePointAt(i);
                if (Character.isLetter(cp)) {
                    Character.UnicodeScript script = Character.UnicodeScript.of(cp);
                    if (script == Character.UnicodeScript.LATIN
                            || script == Character.UnicodeScript.CYRILLIC
                            || script == Character.UnicodeScript.GREEK) {
                        scripts.add(script);
                    }
                }
                i += Character.charCount(cp);
            }
            finalTokens.add(new Token(
                    token.strict(),
                    folded,
                    collapsed,
                    token.start(),
                    token.end(),
                    token.strict().codePointCount(0, token.strict().length()) == 1
            ));
        }

        boolean mixedScripts = scripts.contains(Character.UnicodeScript.LATIN)
                && scripts.size() > 1;
        int separatedLetterRuns = countSeparatedLetterRuns(finalTokens);
        String matchText = buildMatchText(nfkc, false);
        String foldedMatchText = buildMatchText(nfkc, true);

        return new NormalizedText(
                original,
                nfkc,
                matchText,
                foldedMatchText,
                finalTokens,
                formatCharacters,
                homoglyphs,
                leet,
                collapsedRuns,
                separatedLetterRuns,
                mixedScripts,
                tooLong
        );
    }

    /**
     * Canonicalizes a configured term or phrase into folded tokens. This is used
     * only for configuration data, never for player-facing text.
     */
    public List<String> canonicalTermTokens(String term) {
        String nfkc = Normalizer.normalize(term == null ? "" : term, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (Token token : tokenize(nfkc)) {
            String folded = fold(token.strict());
            if (!folded.isBlank()) {
                out.add(folded);
            }
        }
        return out;
    }

    public String fold(String input) {
        String nfd = Normalizer.normalize(input, Normalizer.Form.NFD);
        StringBuilder out = new StringBuilder(nfd.length());
        for (int i = 0; i < nfd.length(); ) {
            int cp = nfd.codePointAt(i);
            int type = Character.getType(cp);
            if (type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK) {
                i += Character.charCount(cp);
                continue;
            }
            Character mapped = FOLD.get(cp);
            if (mapped != null) {
                out.append(mapped);
            } else {
                out.appendCodePoint(cp);
            }
            i += Character.charCount(cp);
        }
        return out.toString();
    }

    private static List<Token> tokenize(String text) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            if (!Character.isLetterOrDigit(cp)) {
                i += Character.charCount(cp);
                continue;
            }
            int start = i;
            StringBuilder token = new StringBuilder();
            while (i < text.length()) {
                int current = text.codePointAt(i);
                if (!Character.isLetterOrDigit(current)) {
                    break;
                }
                token.appendCodePoint(current);
                i += Character.charCount(current);
            }
            String strict = token.toString();
            tokens.add(new Token(strict, strict, strict, start, i, strict.codePointCount(0, strict.length()) == 1));
        }
        return tokens;
    }

    private static String collapseRuns(String input) {
        StringBuilder out = new StringBuilder(input.length());
        int i = 0;
        while (i < input.length()) {
            int cp = input.codePointAt(i);
            int count = 1;
            int next = i + Character.charCount(cp);
            while (next < input.length()) {
                int other = input.codePointAt(next);
                if (other != cp) {
                    break;
                }
                count++;
                next += Character.charCount(other);
            }
            out.appendCodePoint(cp);
            if (count > 1) {
                // One occurrence is enough for matching. This is intentionally
                // aggressive and is only used as a secondary form.
            }
            i = next;
        }
        return out.toString();
    }

    private static int countChanged(String strict, String folded) {
        int changed = 0;
        int limit = Math.min(strict.length(), folded.length());
        for (int i = 0; i < limit; i++) {
            if (strict.charAt(i) != folded.charAt(i)) {
                changed++;
            }
        }
        return changed;
    }

    private static int countSeparatedLetterRuns(List<Token> tokens) {
        int runs = 0;
        int length = 0;
        for (Token token : tokens) {
            if (token.singleLetter()) {
                length++;
            } else {
                if (length >= 4) {
                    runs++;
                }
                length = 0;
            }
        }
        if (length >= 4) {
            runs++;
        }
        return runs;
    }

    private static String buildMatchText(String visible, boolean folded) {
        // Keep one character per input position so regex offsets can be used
        // against the visible string for quotation/context checks. Apostrophes
        // become spaces; "I'm" becomes "i m" and patterns use i\s*m.
        StringBuilder out = new StringBuilder(visible.length());
        for (int i = 0; i < visible.length(); ) {
            int cp = visible.codePointAt(i);
            if (Character.isLetterOrDigit(cp)) {
                if (folded) {
                    Character mapped = FOLD.get(cp);
                    if (mapped != null) {
                        out.append(mapped);
                    } else {
                        out.appendCodePoint(cp);
                    }
                } else {
                    out.appendCodePoint(cp);
                }
            } else {
                out.append(' ');
            }
            i += Character.charCount(cp);
        }
        return out.toString();
    }

    private static boolean isLeetSymbol(int cp) {
        return cp == '@' || cp == '$' || cp == '!' || cp == '|' || cp == '+' || cp == '€';
    }

    private static Map<Integer, Character> buildFoldMap() {
        Map<Integer, Character> map = new HashMap<>();
        // Common Latin/Greek/Cyrillic confusables. This is not a script check and
        // is only used to find configured terms.
        String[][] pairs = {
                {"а", "a"}, {"в", "b"}, {"е", "e"}, {"ё", "e"}, {"к", "k"},
                {"м", "m"}, {"н", "h"}, {"о", "o"}, {"р", "p"}, {"с", "c"},
                {"т", "t"}, {"у", "y"}, {"х", "x"}, {"і", "i"}, {"ј", "j"},
                {"ѕ", "s"}, {"ԁ", "d"}, {"һ", "h"}, {"ӏ", "l"}, {"ɡ", "g"},
                {"α", "a"}, {"β", "b"}, {"γ", "y"}, {"ε", "e"}, {"η", "n"},
                {"ι", "i"}, {"κ", "k"}, {"ν", "v"}, {"ο", "o"}, {"ρ", "p"},
                {"σ", "o"}, {"τ", "t"}, {"υ", "u"}, {"χ", "x"}, {"ω", "w"},
                {"ϲ", "c"}, {"ӕ", "ae"}, {"œ", "oe"}, {"ﬁ", "fi"}, {"ﬂ", "fl"},
                {"ı", "i"}, {"ł", "l"}, {"ø", "o"}, {"đ", "d"}, {"ħ", "h"},
                {"0", "o"}, {"1", "i"}, {"3", "e"}, {"4", "a"}, {"5", "s"},
                {"7", "t"}, {"8", "b"}, {"9", "g"}, {"@", "a"}, {"$", "s"},
                {"!", "i"}, {"|", "l"}, {"+", "t"}, {"€", "e"}
        };
        for (String[] pair : pairs) {
            map.put(pair[0].codePointAt(0), pair[1].charAt(0));
        }
        return Map.copyOf(map);
    }
}
