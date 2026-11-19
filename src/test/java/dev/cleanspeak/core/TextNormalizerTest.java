package dev.cleanspeak.core;

import dev.cleanspeak.api.Category;
import dev.cleanspeak.api.ModerationAction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TextNormalizerTest {

    private final TextNormalizer normalizer = new TextNormalizer();

    @Test
    void leavesInnocentSubstringsAlone() {
        RuleEngine engine = new RuleEngine(normalizer, new RuleSet(
                java.util.List.of(Rule.term("bad.ass", Category.HATE_SLUR, ModerationAction.REPHRASE, 0.8, "ass")),
                LinkPolicy.defaults(), SpamPolicy.defaults()));
        var request = request("classic assassin passes bass");
        RuleEvaluation result = engine.evaluate(request, java.util.List.of(), System.currentTimeMillis());
        assertTrue(result.matches().isEmpty(), "substring-only matches must not fire");
    }

    @Test
    void detectsZeroWidthAndHomoglyphObfuscationAsSupportingEvidence() {
        NormalizedText text = normalizer.normalize("bad\u200Bword and b\u0430dword");
        assertTrue(text.formatCharacters() > 0);
        assertTrue(text.homoglyphCharacters() > 0);
        assertTrue(text.obfuscationScore() > 0.0);
        assertTrue(text.visible().contains("badword"));
    }

    @Test
    void foldsFullwidthAndLeetForAnalysisOnly() {
        NormalizedText text = normalizer.normalize("ｂａｄｗｏｒｄ b4dw0rd fuuuck");
        assertTrue(text.visible().contains("badword"));
        assertTrue(text.foldedMatchText().contains("badword"));
        assertTrue(text.obfuscationScore() > 0.0);
        assertEquals("ｂａｄｗｏｒｄ b4dw0rd fuuuck".length(), text.original().length());
    }

    @Test
    void mixedLanguagesAreNotAViolationByThemselves() {
        NormalizedText text = normalizer.normalize("Привет, how are you? 你好");
        assertTrue(text.mixedScripts());
        RuleEngine engine = new RuleEngine(normalizer, new RuleSet(
                java.util.List.of(Rule.term("badword", Category.HATE_SLUR, ModerationAction.REPHRASE, 0.8, "badword")),
                LinkPolicy.defaults(), SpamPolicy.defaults()));
        assertTrue(engine.evaluate(request(text.original()), java.util.List.of(), System.currentTimeMillis()).matches().isEmpty());
    }

    private static dev.cleanspeak.api.ModerationRequest request(String message) {
        return dev.cleanspeak.api.ModerationRequest.builder()
                .sender(java.util.UUID.randomUUID(), "Tester")
                .audience(dev.cleanspeak.api.MessageAudience.PUBLIC_CHAT)
                .channelId("test")
                .conversationId("test:public")
                .message(message)
                .build();
    }
}
