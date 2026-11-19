package dev.cleanspeak.core;

import dev.cleanspeak.api.Category;
import dev.cleanspeak.api.MessageAudience;
import dev.cleanspeak.api.ModerationAction;
import dev.cleanspeak.api.ModerationRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RuleEngineTest {

    private final TextNormalizer normalizer = new TextNormalizer();

    private RuleSet rules() {
        return new RuleSet(List.of(
                Rule.term("hate.term", Category.HATE_SLUR, ModerationAction.REPHRASE, 0.9, "badword"),
                Rule.regex("harass.insult", Category.TARGETED_HARASSMENT, ModerationAction.REPHRASE, 0.8,
                        "\\byou\\s+(?:are\\s+)?(?:a\\s+)?idiot\\b"),
                Rule.regex("threat.kill", Category.THREAT, ModerationAction.BLOCK, 0.95,
                        "\\bi\\s+will\\s+kill\\s+you\\b"),
                Rule.regex("scam.nitro", Category.SCAM_ADVERTISING, ModerationAction.BLOCK, 0.9,
                        "\\bfree\\s+nitro\\b")
        ), LinkPolicy.defaults(), SpamPolicy.defaults());
    }

    @Test
    void directTermsAndAlteredTermsAreRecognised() {
        RuleEngine engine = new RuleEngine(normalizer, rules());
        assertFalse(engine.evaluate(request("badword"), List.of(), now()).matches().isEmpty());
        RuleEvaluation obfuscated = engine.evaluate(request("b a d w o r d"), List.of(), now());
        assertFalse(obfuscated.matches().isEmpty());
        assertEquals(MatchStrength.OBFUSCATED, obfuscated.matches().get(0).strength());
        RuleEvaluation homoglyph = engine.evaluate(request("b\u0430dword"), List.of(), now());
        assertFalse(homoglyph.matches().isEmpty());
        assertEquals(MatchStrength.OBFUSCATED, homoglyph.matches().get(0).strength());
    }

    @Test
    void innocentWordsAndNormalConversationDoNotMatch() {
        RuleEngine engine = new RuleEngine(normalizer, rules());
        assertTrue(engine.evaluate(request("classic assassin passes the bass"), List.of(), now()).matches().isEmpty());
        assertTrue(engine.evaluate(request("hello, how are you today?"), List.of(), now()).matches().isEmpty());
    }

    @Test
    void threatsAndScamsAreCategorised() {
        RuleEngine engine = new RuleEngine(normalizer, rules());
        RuleEvaluation threat = engine.evaluate(request("I will kill you"), List.of(), now());
        assertTrue(threat.hasCategory(Category.THREAT));
        RuleEvaluation scam = engine.evaluate(request("free nitro here"), List.of(), now());
        assertTrue(scam.hasCategory(Category.SCAM_ADVERTISING));
    }

    @Test
    void quotedReportIsExemptButThreatReportNeedsReview() {
        RuleEngine engine = new RuleEngine(normalizer, rules());
        RuleEvaluation quoted = engine.evaluate(request("he called me \"badword\" in chat"), List.of(), now());
        assertFalse(quoted.matches().isEmpty());
        assertTrue(quoted.matches().stream().allMatch(RuleMatch::exempted));

        RuleEvaluation singleQuoted = engine.evaluate(request("he called me 'badword' in chat"), List.of(), now());
        assertFalse(singleQuoted.matches().isEmpty());
        assertTrue(singleQuoted.matches().stream().allMatch(RuleMatch::exempted));

        RuleEvaluation reportThreat = engine.evaluate(request("he said I will kill you"), List.of(), now());
        assertFalse(reportThreat.matches().isEmpty());
        assertEquals(ContextAnalyzer.Verdict.REVIEW, reportThreat.matches().get(0).contextVerdict());

        RuleEvaluation quotedThreat = engine.evaluate(request("he said \"I will kill you\""), List.of(), now());
        assertFalse(quotedThreat.matches().isEmpty());
        assertEquals(ContextAnalyzer.Verdict.REVIEW, quotedThreat.matches().get(0).contextVerdict());
    }

    @Test
    void spamAndLinkPoliciesDoNotBlockOrdinaryText() {
        RuleEngine engine = new RuleEngine(normalizer, rules());
        assertTrue(engine.evaluate(request("join https://example.com/guide"), List.of(), now()).matches().isEmpty());
        assertTrue(engine.evaluate(request("GG well played"), List.of(), now()).matches().isEmpty());
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    private static ModerationRequest request(String message) {
        return ModerationRequest.builder()
                .sender(UUID.randomUUID(), "Tester")
                .audience(MessageAudience.PUBLIC_CHAT)
                .channelId("test")
                .conversationId("test:public")
                .message(message)
                .build();
    }
}
