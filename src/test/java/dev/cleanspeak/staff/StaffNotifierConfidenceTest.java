package dev.cleanspeak.staff;

import dev.cleanspeak.api.Category;
import dev.cleanspeak.api.Contribution;
import dev.cleanspeak.api.ModerationAction;
import dev.cleanspeak.api.ModerationDecision;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StaffNotifierConfidenceTest {

    @Test
    void readsConfidenceFromModelContribution() {
        ModerationDecision decision = decision(
                "would-be=REVIEW notes=ml-review-threshold=TARGETED_HARASSMENT",
                List.of(new Contribution("model:cleanspeak-bundled-v1", Category.TARGETED_HARASSMENT,
                        0.71D, "confidence=0.83")));
        assertEquals("0.83", StaffNotifier.modelConfidence(decision));
    }

    @Test
    void fallsBackToStaffReason() {
        ModerationDecision decision = decision(
                "would-be=REVIEW ml-confidence=0.77 notes=ml-review-threshold=TARGETED_HARASSMENT",
                List.of());
        assertEquals("0.77", StaffNotifier.modelConfidence(decision));
    }

    @Test
    void returnsBlankForRulesOnly() {
        ModerationDecision decision = decision(
                "would-be=REPHRASE notes=rule:hate.seed-slurs",
                List.of(new Contribution("rule:hate.seed-slurs", Category.HATE_SLUR,
                        0.95D, "matched")));
        assertEquals("", StaffNotifier.modelConfidence(decision));
    }

    private static ModerationDecision decision(String staffReason, List<Contribution> contributions) {
        return new ModerationDecision(
                UUID.randomUUID(), ModerationAction.REVIEW, ModerationAction.REVIEW, false,
                Set.of(Category.TARGETED_HARASSMENT), contributions, staffReason, "",
                "cleanspeak-bundled-v1", "test", false, false, "chat", "chat:public",
                System.currentTimeMillis()
        );
    }
}
