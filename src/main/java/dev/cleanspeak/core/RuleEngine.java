package dev.cleanspeak.core;

import dev.cleanspeak.api.Category;
import dev.cleanspeak.api.ModerationAction;
import dev.cleanspeak.api.ModerationRequest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The local rule engine. It is deterministic, fast and has no network or disk
 * dependencies. Player text is only ever matched as data.
 */
public final class RuleEngine {

    private final TextNormalizer normalizer;
    private final RuleSet ruleSet;
    private final List<CompiledRule> compiledRules;
    private final ContextAnalyzer contextAnalyzer = new ContextAnalyzer();
    private final LinkAnalyzer linkAnalyzer = new LinkAnalyzer();
    private final SpamAnalyzer spamAnalyzer = new SpamAnalyzer();

    public RuleEngine(TextNormalizer normalizer, RuleSet ruleSet) {
        this.normalizer = normalizer;
        this.ruleSet = ruleSet;
        this.compiledRules = compile(ruleSet.rules());
    }

    public RuleSet ruleSet() {
        return ruleSet;
    }

    public RuleEvaluation evaluate(ModerationRequest request, List<ConversationTracker.RecentMessage> recent, long now) {
        NormalizedText normalized = normalizer.normalize(request.message());
        ContextAnalyzer.ContextInfo context = contextAnalyzer.analyze(normalized.visible());

        List<RuleMatch> matches = new ArrayList<>();
        matches.addAll(matchLexical(normalized, context));
        matches.addAll(linkAnalyzer.analyze(normalized, ruleSet.linkPolicy()));
        matches.addAll(spamAnalyzer.analyze(normalized, recent, ruleSet.spamPolicy(), now));
        matches.addAll(splitMessageMatches(normalized, recent, matches));

        if (normalized.tooLong()) {
            matches.add(new RuleMatch(
                    "limit.too-long",
                    Category.OTHER,
                    ModerationAction.REVIEW,
                    MatchStrength.DIRECT,
                    0.9D,
                    0,
                    normalized.visible().length(),
                    "message exceeds the local analysis limit",
                    ContextAnalyzer.Verdict.NONE
            ));
        }

        // Context analysis is applied last so spam/link rules are not accidentally
        // exempted by the word "quote" elsewhere in the message.
        List<RuleMatch> contextual = new ArrayList<>(matches.size());
        for (RuleMatch match : matches) {
            ContextAnalyzer.Verdict verdict = match.contextVerdict();
            if (verdict == ContextAnalyzer.Verdict.NONE) {
                verdict = context.verdictFor(match.start(), match.category());
            }
            contextual.add(match.withContext(verdict));
        }

        return new RuleEvaluation(normalized, context, contextual, recent);
    }

    private List<RuleMatch> matchLexical(NormalizedText normalized, ContextAnalyzer.ContextInfo context) {
        List<RuleMatch> matches = new ArrayList<>();
        for (CompiledRule compiled : compiledRules) {
            if (compiled.termRule()) {
                matches.addAll(matchTerms(compiled, normalized));
            } else {
                matches.addAll(matchRegex(compiled, normalized));
            }
        }

        List<RuleMatch> withContext = new ArrayList<>(matches.size());
        for (RuleMatch match : matches) {
            ContextAnalyzer.Verdict verdict = context.verdictFor(match.start(), match.category());
            withContext.add(match.withContext(verdict));
        }
        return withContext;
    }

    private List<RuleMatch> matchTerms(CompiledRule compiled, NormalizedText normalized) {
        List<RuleMatch> matches = new ArrayList<>();
        List<Token> tokens = normalized.tokens();
        Set<Integer> matchedStarts = new HashSet<>();

        for (Phrase phrase : compiled.phrases()) {
            List<String> termTokens = phrase.tokens();
            int size = termTokens.size();
            if (size == 0) {
                continue;
            }
            for (int i = 0; i + size <= tokens.size(); i++) {
                boolean strict = true;
                boolean folded = true;
                boolean collapsed = true;
                for (int j = 0; j < size; j++) {
                    Token token = tokens.get(i + j);
                    String expected = termTokens.get(j);
                    if (!token.strict().equals(expected)) {
                        strict = false;
                    }
                    if (!token.folded().equals(expected)) {
                        folded = false;
                    }
                    if (!token.collapsed().equals(expected)) {
                        collapsed = false;
                    }
                }
                if (strict || folded || collapsed) {
                    MatchStrength strength = strict && folded ? MatchStrength.DIRECT : MatchStrength.OBFUSCATED;
                    int start = tokens.get(i).start();
                    int end = tokens.get(i + size - 1).end();
                    matches.add(ruleMatch(compiled.rule(), strength, start, end,
                            strength == MatchStrength.DIRECT
                                    ? "configured term matched"
                                    : "configured term matched after normalisation"));
                    matchedStarts.add(i);
                    break;
                }
            }

            if (size == 1 && termTokens.get(0).length() >= 3) {
                String expected = termTokens.get(0);
                int i = 0;
                while (i < tokens.size()) {
                    if (!tokens.get(i).singleLetter()) {
                        i++;
                        continue;
                    }
                    int startIndex = i;
                    StringBuilder joined = new StringBuilder();
                    StringBuilder joinedCollapsed = new StringBuilder();
                    int j = i;
                    while (j < tokens.size() && tokens.get(j).singleLetter()
                            && joined.length() < expected.length() + 8) {
                        String foldedToken = tokens.get(j).folded();
                        joined.append(foldedToken);
                        joinedCollapsed.append(tokens.get(j).collapsed());
                        j++;
                    }
                    String joinedText = joined.toString();
                    String joinedCollapsedText = joinedCollapsed.toString();
                    if (j - startIndex >= Math.min(3, expected.length())
                            && (joinedText.equals(expected) || joinedCollapsedText.equals(expected))) {
                        matches.add(ruleMatch(compiled.rule(), MatchStrength.OBFUSCATED,
                                tokens.get(startIndex).start(), tokens.get(j - 1).end(),
                                "configured term matched across separated letters"));
                        break;
                    }
                    i = Math.max(i + 1, j);
                }
            }
        }
        return matches;
    }

    private List<RuleMatch> matchRegex(CompiledRule compiled, NormalizedText normalized) {
        List<RuleMatch> matches = new ArrayList<>();
        List<int[]> direct = find(compiled.pattern(), normalized.matchText(), 2);
        for (int[] span : direct) {
            matches.add(ruleMatch(compiled.rule(), MatchStrength.DIRECT, span[0], span[1],
                    "configured pattern matched"));
        }
        if (direct.isEmpty() && !normalized.foldedMatchText().equals(normalized.matchText())) {
            List<int[]> folded = find(compiled.pattern(), normalized.foldedMatchText(), 2);
            for (int[] span : folded) {
                matches.add(ruleMatch(compiled.rule(), MatchStrength.OBFUSCATED, span[0], span[1],
                        "configured pattern matched after normalisation"));
            }
        }
        return matches;
    }

    private static List<int[]> find(Pattern pattern, String text, int limit) {
        List<int[]> spans = new ArrayList<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find() && spans.size() < limit) {
            if (matcher.end() > matcher.start()) {
                spans.add(new int[]{matcher.start(), matcher.end()});
            }
        }
        return spans;
    }

    private List<RuleMatch> splitMessageMatches(
            NormalizedText current,
            List<ConversationTracker.RecentMessage> recent,
            List<RuleMatch> existing
    ) {
        if (recent.isEmpty()) {
            return List.of();
        }
        StringBuilder combined = new StringBuilder();
        int from = Math.max(0, recent.size() - 2);
        for (int i = from; i < recent.size(); i++) {
            combined.append(recent.get(i).analysisText()).append(' ');
        }
        combined.append(current.visible());
        if (combined.length() > 400) {
            return List.of();
        }
        NormalizedText normalized = normalizer.normalize(combined.toString());
        ContextAnalyzer.ContextInfo context = contextAnalyzer.analyze(normalized.visible());
        List<RuleMatch> combinedMatches = new ArrayList<>(matchLexical(normalized, context));

        // Also consider the boundary between the most recent message and the
        // current one without a separator, so deliberate splits such as
        // "bad" + "word" are visible. It is only supporting evidence and policy
        // caps it at REPHRASE.
        String previous = recent.get(recent.size() - 1).analysisText();
        String boundary = previous + current.visible();
        if (!boundary.equals(normalized.visible())) {
            NormalizedText boundaryText = normalizer.normalize(boundary);
            ContextAnalyzer.ContextInfo boundaryContext = contextAnalyzer.analyze(boundaryText.visible());
            combinedMatches.addAll(matchLexical(boundaryText, boundaryContext));
        }

        // A rule that already matched a previous message must not make the next
        // message look like a split violation. This is especially important in
        // shadow mode, where the prior message was delivered.
        Set<String> priorRuleIds = new HashSet<>();
        for (ConversationTracker.RecentMessage message : recent) {
            NormalizedText priorText = normalizer.normalize(message.analysisText());
            ContextAnalyzer.ContextInfo priorContext = contextAnalyzer.analyze(priorText.visible());
            for (RuleMatch priorMatch : matchLexical(priorText, priorContext)) {
                priorRuleIds.add(priorMatch.ruleId());
            }
        }
        Set<String> already = new HashSet<>(priorRuleIds);
        for (RuleMatch match : existing) {
            already.add(match.ruleId());
        }
        List<RuleMatch> out = new ArrayList<>();
        for (RuleMatch match : combinedMatches) {
            if (!already.contains(match.ruleId())) {
                out.add(new RuleMatch(
                        match.ruleId(),
                        match.category(),
                        match.action(),
                        MatchStrength.CONTEXT,
                        Math.min(0.65D, match.score()),
                        0,
                        0,
                        "possible split message across recent messages in the same conversation",
                        ContextAnalyzer.Verdict.NONE
                ));
            }
        }
        return out;
    }

    private static RuleMatch ruleMatch(Rule rule, MatchStrength strength, int start, int end, String detail) {
        return new RuleMatch(rule.id(), rule.category(), rule.action(), strength,
                rule.score(), start, end, detail, ContextAnalyzer.Verdict.NONE);
    }

    private static List<CompiledRule> compile(List<Rule> rules) {
        List<CompiledRule> compiled = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        TextNormalizer normalizer = new TextNormalizer();
        for (Rule rule : rules) {
            if (!rule.enabled()) {
                continue;
            }
            if (!ids.add(rule.id())) {
                throw new IllegalArgumentException("duplicate rule id: " + rule.id());
            }
            if (rule.type() == RuleType.TERM) {
                List<Phrase> phrases = new ArrayList<>();
                for (String term : rule.terms()) {
                    List<String> tokens = normalizer.canonicalTermTokens(term);
                    if (!tokens.isEmpty()) {
                        phrases.add(new Phrase(tokens));
                    }
                }
                if (phrases.isEmpty()) {
                    throw new IllegalArgumentException("term rule has no usable terms: " + rule.id());
                }
                compiled.add(new CompiledRule(rule, phrases, null));
            } else {
                try {
                    Pattern pattern = Pattern.compile(rule.pattern(),
                            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS);
                    compiled.add(new CompiledRule(rule, List.of(), pattern));
                } catch (PatternSyntaxException e) {
                    throw new IllegalArgumentException("invalid regex in rule " + rule.id() + ": " + e.getDescription(), e);
                }
            }
        }
        return List.copyOf(compiled);
    }

    private record Phrase(List<String> tokens) {
    }

    private record CompiledRule(Rule rule, List<Phrase> phrases, Pattern pattern) {
        boolean termRule() {
            return rule.type() == RuleType.TERM;
        }
    }
}
