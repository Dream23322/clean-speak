package dev.cleanspeak.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight context checks for quotation and abuse reports. These checks are
 * deliberately conservative: a threat is never fully exempted merely because
 * the word "quote" appears somewhere in the message.
 */
public final class ContextAnalyzer {

    public enum Verdict {
        NONE,
        EXEMPT,
        REVIEW
    }

    private static final Pattern REPORT_CUE = Pattern.compile(
            "(?iu)\\b(?:" +
                    "(?:he|she|they|someone|somebody|that\\s+player|this\\s+player|a\\s+player|" +
                    "[a-z0-9_]{2,16})\\s+(?:said|says|told|wrote|posted|typed|messaged|asked|called)|" +
                    "i\\s+(?:was|am|have\\s+been)\\s+(?:called|told|threatened|harassed)|" +
                    "i\\s+(?:am\\s+)?reporting|reporting\\s+(?:abuse|harassment|a\\s+player)|" +
                    "for\\s+context|screenshot|evidence|quote|quoting|" +
                    "(?:he|she|they)\\s+told\\s+me\\s+to" +
                    ")\\b"
    );

    private static final Pattern META_CUE = Pattern.compile(
            "(?iu)\\b(?:" +
                    "what\\s+does|what\\s+is|is\\s+it\\s+(?:ok|okay|allowed)\\s+to\\s+say|" +
                    "meaning\\s+of|definition\\s+of|word\\s+for|study|research|history\\s+of|" +
                    "offensive|slur" +
                    ").{0,80}\\b(?:mean|means|offensive|slur|allowed\\s+to\\s+say|word|term)\\b"
    );

    private static final Pattern QUESTION = Pattern.compile(
            "(?iu)(?:\\?|\\bwhat\\b|\\bwhy\\b|\\bhow\\b|\\bis\\b|\\bdoes\\b|\\bcan\\b)"
    );

    public ContextInfo analyze(String visible) {
        String text = visible == null ? "" : visible;
        List<QuoteSpan> quotes = findQuotes(text);
        List<Integer> reportCues = cueEnds(REPORT_CUE, text);
        List<Integer> metaCues = cueEnds(META_CUE, text);
        boolean question = QUESTION.matcher(text).find();
        return new ContextInfo(quotes, reportCues, metaCues, question);
    }

    private static List<Integer> cueEnds(Pattern pattern, String text) {
        List<Integer> ends = new ArrayList<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            ends.add(matcher.end());
            if (ends.size() >= 16) {
                break;
            }
        }
        return ends;
    }

    private static List<QuoteSpan> findQuotes(String text) {
        List<QuoteSpan> spans = new ArrayList<>();
        int open = -1;
        boolean single = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean doubleLike = c == '"' || c == '\u201C' || c == '\u201D' || c == '\u00AB' || c == '\u00BB' || c == '`';
            boolean singleLike = c == '\'' || c == '\u2018' || c == '\u2019';
            if (doubleLike) {
                if (open < 0) {
                    open = i;
                    single = false;
                } else if (!single) {
                    if (i - open > 2) {
                        spans.add(new QuoteSpan(open, i + 1));
                    }
                    open = -1;
                }
            } else if (singleLike) {
                char previous = i == 0 ? ' ' : text.charAt(i - 1);
                char next = i + 1 >= text.length() ? ' ' : text.charAt(i + 1);
                if (open < 0) {
                    if (!isWordChar(previous)) {
                        open = i;
                        single = true;
                    }
                } else if (single && !isWordChar(next)) {
                    if (i - open > 2) {
                        spans.add(new QuoteSpan(open, i + 1));
                    }
                    open = -1;
                }
            }
        }
        if (open >= 0 && text.length() - open > 2) {
            spans.add(new QuoteSpan(open, text.length()));
        }
        return spans;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    public record QuoteSpan(int start, int end) {
        public boolean contains(int offset) {
            return offset >= start && offset < end;
        }
    }

    public record ContextInfo(
            List<QuoteSpan> quotes,
            List<Integer> reportCueEnds,
            List<Integer> metaCueEnds,
            boolean question
    ) {
        public ContextInfo {
            quotes = List.copyOf(quotes);
            reportCueEnds = List.copyOf(reportCueEnds);
            metaCueEnds = List.copyOf(metaCueEnds);
        }

        public boolean insideQuote(int offset) {
            for (QuoteSpan quote : quotes) {
                if (quote.contains(offset)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * @param start    match start in the visible analysis string
         * @param category match category
         * @return whether this particular match should be treated as context
         */
        public Verdict verdictFor(int start, dev.cleanspeak.api.Category category) {
            if (insideQuote(start)) {
                // A quoted slur/insult can be discussion; a quoted threat is still
                // held for staff so quotation cannot be used as a trivial bypass.
                return category == dev.cleanspeak.api.Category.THREAT
                        ? Verdict.REVIEW : Verdict.EXEMPT;
            }
            boolean report = cueBefore(reportCueEnds, start, 120);
            boolean meta = cueBefore(metaCueEnds, start, 160);
            if (category == dev.cleanspeak.api.Category.THREAT) {
                // "he said he will kill me" is a report, but it is safer to let
                // staff look at it than to silently allow a threat.
                return report || meta ? Verdict.REVIEW : Verdict.NONE;
            }
            if (meta) {
                return Verdict.EXEMPT;
            }
            if (report) {
                return Verdict.EXEMPT;
            }
            return Verdict.NONE;
        }

        private static boolean cueBefore(List<Integer> ends, int start, int maxDistance) {
            for (int end : ends) {
                if (end <= start + 8 && start - end <= maxDistance) {
                    return true;
                }
            }
            return false;
        }
    }
}
