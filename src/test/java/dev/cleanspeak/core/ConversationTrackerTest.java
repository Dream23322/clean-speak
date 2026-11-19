package dev.cleanspeak.core;

import dev.cleanspeak.api.Category;
import dev.cleanspeak.api.MessageAudience;
import dev.cleanspeak.api.ModerationAction;
import dev.cleanspeak.api.ModerationRequest;
import dev.cleanspeak.core.ml.MlSettings;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ConversationTrackerTest {

    @Test
    void splitMessagesAreCombinedOnlyWithinTheSameConversationAndSender() {
        ConversationTracker tracker = new ConversationTracker(4, 30_000L, 100);
        TextNormalizer normalizer = new TextNormalizer();
        RuleEngine engine = new RuleEngine(normalizer, new RuleSet(
                List.of(Rule.term("combined", Category.TARGETED_HARASSMENT, ModerationAction.REPHRASE, 0.8, "badword")),
                LinkPolicy.defaults(), SpamPolicy.defaults()));
        UUID sender = UUID.randomUUID();
        ModerationRequest first = request(sender, "test:one", "you are a bad");
        ModerationRequest second = request(sender, "test:one", "word");
        long now = System.currentTimeMillis();
        RuleEvaluation firstResult = engine.evaluate(first, List.of(), now);
        assertTrue(firstResult.matches().isEmpty());
        tracker.record(first, firstResult.normalized().visible(), now);

        RuleEvaluation secondResult = engine.evaluate(second, tracker.recent(second, now + 1), now + 1);
        assertTrue(secondResult.matches().stream().anyMatch(m -> m.ruleId().equals("combined")));

        ModerationRequest otherConversation = request(sender, "test:two", "word");
        RuleEvaluation isolated = engine.evaluate(otherConversation, tracker.recent(otherConversation, now + 1), now + 1);
        assertTrue(isolated.matches().stream().noneMatch(m -> m.ruleId().equals("combined")));
    }

    @Test
    void priorMatchedMessageDoesNotPoisonTheNextMessage() {
        ConversationTracker tracker = new ConversationTracker(4, 30_000L, 100);
        TextNormalizer normalizer = new TextNormalizer();
        RuleEngine engine = new RuleEngine(normalizer, new RuleSet(
                List.of(Rule.regex("harass.insult", Category.TARGETED_HARASSMENT, ModerationAction.REPHRASE, 0.8,
                        "\\byou\\s+are\\s+stupid\\b")),
                LinkPolicy.defaults(), SpamPolicy.defaults()));
        UUID sender = UUID.randomUUID();
        ModerationRequest prior = request(sender, "test:one", "you are stupid");
        long now = System.currentTimeMillis();
        RuleEvaluation priorEval = engine.evaluate(prior, List.of(), now);
        assertFalse(priorEval.matches().isEmpty());
        tracker.record(prior, priorEval.normalized().visible(), now);

        ModerationRequest next = request(sender, "test:one", "hello there");
        RuleEvaluation nextEval = engine.evaluate(next, tracker.recent(next, now + 1), now + 1);
        assertTrue(nextEval.matches().stream().noneMatch(m -> m.ruleId().equals("harass.insult")));
    }

    @Test
    void contextExpiresAndOtherSendersDoNotLeak() {
        ConversationTracker tracker = new ConversationTracker(4, 1_000L, 100);
        UUID sender = UUID.randomUUID();
        ModerationRequest first = request(sender, "test:one", "badword");
        tracker.record(first, "badword", 1_000L);
        ModerationRequest second = request(sender, "test:one", "word");
        assertTrue(tracker.recent(second, 5_000L).isEmpty());
        ModerationRequest otherSender = request(UUID.randomUUID(), "test:one", "word");
        assertTrue(tracker.recent(otherSender, 1_500L).isEmpty());
    }

    @Test
    void shadowPolicyAllowsButKeepsRawAction() {
        RuleEngine engine = new RuleEngine(new TextNormalizer(), new RuleSet(
                List.of(Rule.term("bad", Category.HATE_SLUR, ModerationAction.REPHRASE, 0.9, "badword")),
                LinkPolicy.defaults(), SpamPolicy.defaults()));
        ModerationRequest request = request(UUID.randomUUID(), "test:one", "badword");
        RuleEvaluation evaluation = engine.evaluate(request, List.of(), System.currentTimeMillis());
        var decision = new DecisionPolicy().decide(request, evaluation,
                dev.cleanspeak.core.ml.MlResult.disabled(), TextPolicy.defaults(), MlSettings.disabled());
        assertEquals(ModerationAction.ALLOW, decision.action());
        assertEquals(ModerationAction.REPHRASE, decision.rawAction());
        assertTrue(decision.shadowed());
        assertTrue(decision.playerReason().isBlank());
    }

    @Test
    void nonShadowPolicyRephrasesWithoutBanActions() {
        RuleEngine engine = new RuleEngine(new TextNormalizer(), new RuleSet(
                List.of(Rule.term("bad", Category.HATE_SLUR, ModerationAction.REPHRASE, 0.9, "badword")),
                LinkPolicy.defaults(), SpamPolicy.defaults()));
        ModerationRequest request = request(UUID.randomUUID(), "test:one", "badword");
        RuleEvaluation evaluation = engine.evaluate(request, List.of(), System.currentTimeMillis());
        var policy = new TextPolicy(false, TextPolicy.defaults().categoryActions(), "test", false, false, 0.55);
        var decision = new DecisionPolicy().decide(request, evaluation,
                dev.cleanspeak.core.ml.MlResult.disabled(), policy, MlSettings.disabled());
        assertEquals(ModerationAction.REPHRASE, decision.action());
        assertFalse(decision.playerReason().isBlank());
    }

    private static ModerationRequest request(UUID sender, String conversation, String message) {
        return ModerationRequest.builder()
                .sender(sender, "Tester")
                .audience(MessageAudience.PRIVATE_MESSAGE)
                .channelId("test")
                .conversationId(conversation)
                .message(message)
                .build();
    }
}
