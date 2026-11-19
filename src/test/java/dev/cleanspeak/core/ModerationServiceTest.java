package dev.cleanspeak.core;

import dev.cleanspeak.api.Category;
import dev.cleanspeak.api.MessageAudience;
import dev.cleanspeak.api.ModerationAction;
import dev.cleanspeak.api.ModerationDecision;
import dev.cleanspeak.api.ModerationRequest;
import dev.cleanspeak.core.ml.MlInput;
import dev.cleanspeak.core.ml.MlResult;
import dev.cleanspeak.core.ml.MlSettings;
import dev.cleanspeak.core.ml.TextClassifier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ModerationServiceTest {

    private static RuleEngine rules() {
        return new RuleEngine(new TextNormalizer(), new RuleSet(
                List.of(Rule.term("bad", Category.HATE_SLUR, ModerationAction.REPHRASE, 0.9, "badword")),
                LinkPolicy.defaults(), SpamPolicy.defaults()));
    }

    private static TextPolicy policy() {
        return new TextPolicy(false, TextPolicy.defaults().categoryActions(), "test", false, false, 0.55);
    }

    @Test
    void asyncModerationDoesNotBlockAndLogsDecision() {
        AtomicReference<ModerationDecision> seen = new AtomicReference<>();
        ModerationService service = new ModerationService(
                rules(), new ConversationTracker(3, 30_000L, 100),
                dev.cleanspeak.core.ml.DisabledTextClassifier.INSTANCE,
                MlSettings.disabled(), policy(), 1, 8, 1000,
                (request, decision) -> seen.set(decision), ignored -> {});
        long start = System.nanoTime();
        var future = service.moderate(request("badword"));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        assertTrue(elapsedMs < 200, "moderate() must return quickly");
        ModerationDecision decision = future.join();
        assertEquals(ModerationAction.REPHRASE, decision.action());
        assertEquals(decision, seen.get());
        service.close();
    }

    @Test
    void consecutiveBlockedMessagesAreIndependent() {
        ModerationService service = new ModerationService(
                rules(), new ConversationTracker(3, 30_000L, 100),
                dev.cleanspeak.core.ml.DisabledTextClassifier.INSTANCE,
                MlSettings.disabled(), policy(), 1, 8, 1000,
                (request, decision) -> {}, ignored -> {});
        try {
            for (int i = 0; i < 3; i++) {
                ModerationDecision decision = service.moderateRulesOnly(request("badword"));
                assertFalse(decision.isAllowed(), "blocked message " + i + " was allowed");
            }
        } finally {
            service.close();
        }
    }

    @Test
    void unavailableClassifierFallsBackToRules() {
        TextClassifier failing = new TextClassifier() {
            @Override public boolean enabled() { return true; }
            @Override public String version() { return "fake"; }
            @Override public MlResult classify(MlInput input) { return MlResult.timeout(); }
            @Override public String status() { return "timeout"; }
            @Override public boolean available() { return false; }
        };
        MlSettings settings = new MlSettings(true, "http://127.0.0.1:1", false, "fake", 100,
                Map.of(), Map.of(), "RULES_ONLY");
        TextPolicy policy = new TextPolicy(false, TextPolicy.defaults().categoryActions(), "test", true, false, 0.55);
        ModerationService service = new ModerationService(
                rules(), new ConversationTracker(3, 30_000L, 100), failing, settings, policy,
                1, 8, 1000, (r, d) -> {}, ignored -> {});
        ModerationDecision decision = service.moderate(request("hello")).join();
        assertEquals(ModerationAction.ALLOW, decision.action());
        assertTrue(decision.fallback());
        service.close();
    }

    @Test
    void slowModelWorkRunsOffTheCallingThread() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        TextClassifier slow = new TextClassifier() {
            @Override public boolean enabled() { return true; }
            @Override public String version() { return "slow"; }
            @Override public MlResult classify(MlInput input) {
                calls.incrementAndGet();
                entered.countDown();
                try {
                    release.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return MlResult.disabled();
            }
            @Override public String status() { return "slow"; }
            @Override public boolean available() { return true; }
        };
        MlSettings settings = new MlSettings(true, "http://127.0.0.1:1", false, "slow", 1000,
                Map.of(), Map.of(), "RULES_ONLY");
        TextPolicy policy = new TextPolicy(false, TextPolicy.defaults().categoryActions(), "test", true, false, 0.55);
        ModerationService service = new ModerationService(rules(), new ConversationTracker(3, 30_000L, 100),
                slow, settings, policy, 1, 8, 1000, (r, d) -> {}, ignored -> {});
        long start = System.nanoTime();
        service.moderate(request("hello"));
        assertTrue((System.nanoTime() - start) / 1_000_000L < 200);
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertTrue(calls.get() >= 1);
        release.countDown();
        service.close();
    }

    @Test
    void fullQueueFallsBackToRulesWithoutBlocking() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        TextClassifier blocking = new TextClassifier() {
            @Override public boolean enabled() { return true; }
            @Override public String version() { return "blocking"; }
            @Override public MlResult classify(MlInput input) {
                entered.countDown();
                try { release.await(2, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return MlResult.disabled();
            }
            @Override public String status() { return "blocking"; }
            @Override public boolean available() { return true; }
        };
        MlSettings settings = new MlSettings(true, "http://127.0.0.1:1", false, "blocking", 1000,
                Map.of(), Map.of(), "RULES_ONLY");
        TextPolicy policy = new TextPolicy(false, TextPolicy.defaults().categoryActions(), "test", true, false, 0.55);
        ModerationService service = new ModerationService(rules(), new ConversationTracker(3, 30_000L, 100),
                blocking, settings, policy, 1, 1, 1000, (r, d) -> {}, ignored -> {});
        service.moderate(request("hello"));
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        service.moderate(request("hello 2"));
        long start = System.nanoTime();
        ModerationDecision fallback = service.moderate(request("badword")).join();
        assertTrue((System.nanoTime() - start) / 1_000_000L < 1000);
        assertTrue(fallback.fallback());
        assertEquals(ModerationAction.REPHRASE, fallback.action());
        release.countDown();
        service.close();
    }

    @Test
    void rulesOnlyDoesNotCallModelOrWait() {
        AtomicInteger calls = new AtomicInteger();
        TextClassifier fake = new TextClassifier() {
            @Override public boolean enabled() { return true; }
            @Override public String version() { return "fake"; }
            @Override public MlResult classify(MlInput input) { calls.incrementAndGet(); return MlResult.disabled(); }
            @Override public String status() { return "unused"; }
            @Override public boolean available() { return true; }
        };
        MlSettings settings = new MlSettings(true, "http://127.0.0.1:1", false, "fake", 100,
                Map.of(), Map.of(), "RULES_ONLY");
        TextPolicy policy = new TextPolicy(false, TextPolicy.defaults().categoryActions(), "test", true, false, 0.55);
        ModerationService service = new ModerationService(rules(), new ConversationTracker(3, 30_000L, 100),
                fake, settings, policy, 1, 8, 1000, (r, d) -> {}, ignored -> {});
        ModerationDecision decision = service.moderateRulesOnly(request("badword"));
        assertEquals(ModerationAction.REPHRASE, decision.action());
        assertEquals(0, calls.get());
        service.close();
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
