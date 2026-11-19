package dev.cleanspeak.core;

import dev.cleanspeak.api.Category;
import dev.cleanspeak.api.MessageAudience;
import dev.cleanspeak.api.ModerationAction;
import dev.cleanspeak.api.ModerationDecision;
import dev.cleanspeak.api.ModerationRequest;
import dev.cleanspeak.core.ml.MlResult;
import dev.cleanspeak.core.ml.MlSettings;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DecisionPolicyTest {

    private final TextNormalizer normalizer = new TextNormalizer();
    private final RuleEngine engine = new RuleEngine(normalizer, new RuleSet(
            List.of(Rule.term("bad", Category.HATE_SLUR, ModerationAction.REPHRASE, 0.9, "badword")),
            LinkPolicy.defaults(), SpamPolicy.defaults()));

    @Test
    void modelScoresCannotBlockWithoutOwnerOptIn() {
        ModerationRequest request = request("hello there");
        RuleEvaluation evaluation = engine.evaluate(request, List.of(), System.currentTimeMillis());
        MlResult ml = new MlResult(MlResult.Status.OK,
                Map.of(Category.THREAT, 0.99D), 0.99D, "test-model", "ok");
        MlSettings settings = new MlSettings(true, "http://127.0.0.1:1/classify", false,
                "test-model", 500, Map.of(Category.THREAT, 0.5), Map.of(Category.THREAT, 0.8), "RULES_ONLY");
        TextPolicy policy = new TextPolicy(false, TextPolicy.defaults().categoryActions(), "test", true, false, 0.5);
        ModerationDecision decision = new DecisionPolicy().decide(request, evaluation, ml, policy, settings);
        assertEquals(ModerationAction.REVIEW, decision.action());
        assertEquals(ModerationAction.REVIEW, decision.rawAction());
        assertTrue(decision.contributions().stream().anyMatch(c -> c.source().startsWith("model:")));
    }

    @Test
    void unavailableModelUsesRulesAndMarksFallback() {
        ModerationRequest request = request("badword");
        RuleEvaluation evaluation = engine.evaluate(request, List.of(), System.currentTimeMillis());
        MlSettings settings = new MlSettings(true, "http://127.0.0.1:1/classify", false,
                "test-model", 500, Map.of(), Map.of(), "RULES_ONLY");
        TextPolicy policy = new TextPolicy(false, TextPolicy.defaults().categoryActions(), "test", true, false, 0.5);
        ModerationDecision decision = new DecisionPolicy().decide(request, evaluation,
                MlResult.timeout(), policy, settings);
        assertEquals(ModerationAction.REPHRASE, decision.action());
        assertTrue(decision.fallback());
        assertTrue(decision.staffReason().contains("fallback"));
    }

    @Test
    void reportContextExemptsSlurButNotThreat() {
        ModerationRequest report = request("he called me \"badword\"");
        RuleEvaluation reportEval = engine.evaluate(report, List.of(), System.currentTimeMillis());
        TextPolicy policy = new TextPolicy(false, TextPolicy.defaults().categoryActions(), "test", false, false, 0.5);
        ModerationDecision reportDecision = new DecisionPolicy().decide(report, reportEval,
                MlResult.disabled(), policy, MlSettings.disabled());
        assertEquals(ModerationAction.ALLOW, reportDecision.action());
    }

    @Test
    void disabledChecksAreExcludedFromPolicy() {
        ModerationRequest request = request("badword");
        RuleEvaluation evaluation = engine.evaluate(request, List.of(), System.currentTimeMillis());
        TextPolicy base = new TextPolicy(false, TextPolicy.defaults().categoryActions(), "test", false, false, 0.5);
        TextPolicy policy = new TextPolicy(base.shadowMode(), base.categoryActions(), base.policyVersion(),
                base.mlExpected(), base.mlMayBlock(), base.minMlConfidence(),
                java.util.Set.of(Category.HATE_SLUR), base.displayNames(), base.messages());
        ModerationDecision decision = new DecisionPolicy().decide(request, evaluation,
                MlResult.disabled(), policy, MlSettings.disabled());
        assertEquals(ModerationAction.ALLOW, decision.action());
        assertFalse(decision.categories().contains(Category.HATE_SLUR));
    }

    @Test
    void observationNeverEnforces() {
        ModerationRequest request = request("badword");
        RuleEvaluation evaluation = engine.evaluate(request, List.of(), System.currentTimeMillis());
        TextPolicy policy = new TextPolicy(false, TextPolicy.defaults().categoryActions(), "test", false, false, 0.5);
        ModerationDecision decision = new DecisionPolicy().decide(request, evaluation,
                MlResult.disabled(), policy, MlSettings.disabled(), true);
        assertEquals(ModerationAction.ALLOW, decision.action());
        assertEquals(ModerationAction.REPHRASE, decision.rawAction());
        assertTrue(decision.observation());
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
