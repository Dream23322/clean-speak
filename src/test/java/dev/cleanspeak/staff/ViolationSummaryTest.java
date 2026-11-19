package dev.cleanspeak.staff;

import dev.cleanspeak.storage.DecisionStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViolationSummaryTest {

    private static DecisionStore.DecisionRecord record(
            String id, String sender, String action, List<String> categories,
            List<String> contributions, long timestamp) {
        return new DecisionStore.DecisionRecord(
                id, timestamp, sender.toLowerCase() + "-id", sender,
                "PUBLIC_CHAT", "paper:public-chat", "paper:public-chat:public",
                action, action, false, false, categories, contributions,
                "staff", "player", "cleanspeak-bundled-v1", "test", false,
                "hash", 10, null
        );
    }

    @Test
    void separatesRegexAndAiContributions() {
        DecisionStore.DecisionRecord regex = record(
                "r1", "Alice", "REPHRASE", List.of("HATE_SLUR"),
                List.of("rule:hate.seed-slurs|HATE_SLUR|0.950|matched"), 1_000L);
        DecisionStore.DecisionRecord ai = record(
                "r2", "Alice", "REPHRASE", List.of("TARGETED_HARASSMENT"),
                List.of("model:cleanspeak-bundled-v1|TARGETED_HARASSMENT|0.940|confidence=0.96"),
                2_000L);
        DecisionStore.DecisionRecord both = record(
                "r3", "Alice", "REPHRASE", List.of("HATE_SLUR", "TARGETED_HARASSMENT"),
                List.of(
                        "rule:hate.seed-slurs|HATE_SLUR|0.950|matched",
                        "model:cleanspeak-bundled-v1|TARGETED_HARASSMENT|0.940|confidence=0.96"
                ), 3_000L);

        assertEquals("REGEX", ViolationSummary.sourceLabel(regex));
        assertEquals("AI", ViolationSummary.sourceLabel(ai));
        assertEquals("AI+REGEX", ViolationSummary.sourceLabel(both));

        ViolationSummary.Report report = ViolationSummary.summarize(
                List.of(both, ai, regex), null, 20);
        assertEquals(3, report.flagged());
        ViolationSummary.Entry model = report.entries().stream()
                .filter(entry -> entry.source().equals("AI"))
                .findFirst().orElseThrow();
        ViolationSummary.Entry rule = report.entries().stream()
                .filter(entry -> entry.source().equals("REGEX"))
                .findFirst().orElseThrow();
        assertEquals(2, model.count());
        assertEquals("cleanspeak-bundled-v1", model.check());
        assertEquals(2, rule.count());
        assertEquals("hate.seed-slurs", rule.check());
    }

    @Test
    void filtersByPlayerAndIgnoresAllowedDecisions() {
        DecisionStore.DecisionRecord alice = record(
                "r1", "Alice", "REPHRASE", List.of("HATE_SLUR"),
                List.of("rule:hate.seed-slurs|HATE_SLUR|0.950|matched"), 1_000L);
        DecisionStore.DecisionRecord bob = record(
                "r2", "Bob", "REPHRASE", List.of("HATE_SLUR"),
                List.of("rule:hate.seed-slurs|HATE_SLUR|0.950|matched"), 2_000L);
        DecisionStore.DecisionRecord allowed = record(
                "r3", "Alice", "ALLOW", List.of(),
                List.of(), 3_000L);

        ViolationSummary.Report report = ViolationSummary.summarize(
                List.of(alice, bob, allowed), "alice", 20);
        assertEquals(2, report.scanned());
        assertEquals(1, report.flagged());
        assertEquals(1, report.entries().size());
        assertEquals("hate.seed-slurs", report.entries().get(0).check());
        assertTrue(report.entries().get(0).latestEpochMillis() >= 1_000L);
    }

    @Test
    void fallsBackToDecisionWhenNoSourceIsStored() {
        DecisionStore.DecisionRecord record = record(
                "r1", "Alice", "REVIEW", List.of("TARGETED_HARASSMENT"),
                List.of(), 1_000L);
        ViolationSummary.Report report = ViolationSummary.summarize(List.of(record), null, 20);
        assertEquals("SYSTEM", report.entries().get(0).source());
        assertEquals(1, report.entries().get(0).count());
    }
}
