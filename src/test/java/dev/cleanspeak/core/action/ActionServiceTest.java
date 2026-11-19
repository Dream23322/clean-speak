package dev.cleanspeak.core.action;

import dev.cleanspeak.api.Category;
import dev.cleanspeak.api.MessageAudience;
import dev.cleanspeak.api.ModerationAction;
import dev.cleanspeak.api.ModerationDecision;
import dev.cleanspeak.api.ModerationRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionServiceTest {

    private static ModerationRequest request(UUID player) {
        return ModerationRequest.builder()
                .sender(player, "Alice")
                .audience(dev.cleanspeak.api.MessageAudience.PUBLIC_CHAT)
                .channelId("chat")
                .conversationId("chat:public")
                .message("targeted message")
                .build();
    }

    private static ModerationDecision decision(boolean shadowed) {
        return new ModerationDecision(
                UUID.randomUUID(), ModerationAction.REPHRASE, ModerationAction.REPHRASE,
                shadowed, Set.of(Category.HATE_SLUR), List.of(),
                "rule:hate.seed-slurs", "player reason", "bundled",
                "test", false, false, "chat", "chat:public", System.currentTimeMillis()
        );
    }

    @Test
    void runsThresholdActionAndEscalatesTheSameDecision() {
        List<String> executed = new ArrayList<>();
        ActionSettings settings = new ActionSettings(true, 3600, false, Map.of(
                Category.HATE_SLUR, new ActionSettings.Check(true, 3600,
                        parse("[2:0] [block]", "[2:0] warn %player% x%count%"))
        ));
        ActionService service = new ActionService(settings, executed::add, null);
        UUID player = UUID.randomUUID();

        ModerationDecision first = service.adjust(request(player), decision(false));
        assertEquals(ModerationAction.REPHRASE, first.action());
        assertTrue(executed.isEmpty());
        assertEquals(1, service.count(player, Category.HATE_SLUR, MessageAudience.PUBLIC_CHAT));

        ModerationDecision second = service.adjust(request(player), decision(false));
        assertEquals(ModerationAction.BLOCK, second.action());
        assertEquals(List.of("warn Alice x2"), executed);
        assertEquals(2, service.count(player, Category.HATE_SLUR, MessageAudience.PUBLIC_CHAT));
    }

    @Test
    void oneMessageWithTwoCategoriesAlertsOnlyOnce() {
        AtomicInteger alerts = new AtomicInteger();
        ActionSettings settings = new ActionSettings(true, 3600, false, Map.of(
                Category.HATE_SLUR, new ActionSettings.Check(true, 3600,
                        parse("[1:0] [alert]")),
                Category.TARGETED_HARASSMENT, new ActionSettings.Check(true, 3600,
                        parse("[1:0] [alert]"))
        ));
        ActionService service = new ActionService(settings, null,
                (request, decision, count, actions) -> alerts.incrementAndGet());
        ModerationDecision decision = new ModerationDecision(
                UUID.randomUUID(), ModerationAction.BLOCK, ModerationAction.BLOCK, false,
                Set.of(Category.HATE_SLUR, Category.TARGETED_HARASSMENT), List.of(),
                "rules", "reason", "bundled", "test", false, false,
                "chat", "chat:public", System.currentTimeMillis());
        service.adjust(request(UUID.randomUUID()), decision);
        assertEquals(1, alerts.get());
    }

    @Test
    void shadowedDecisionsDoNotCountOrRun() {
        List<String> executed = new ArrayList<>();
        ActionSettings settings = new ActionSettings(true, 3600, false, Map.of(
                Category.HATE_SLUR, new ActionSettings.Check(true, 3600,
                        parse("[1:0] warn %player%"))
        ));
        ActionService service = new ActionService(settings, executed::add, null);
        UUID player = UUID.randomUUID();
        service.adjust(request(player), decision(true));
        assertTrue(executed.isEmpty());
        assertEquals(0, service.count(player, Category.HATE_SLUR, MessageAudience.PUBLIC_CHAT));
    }

    private static List<ActionSettings.Action> parse(String... lines) {
        List<ActionSettings.Action> actions = new ArrayList<>();
        for (String line : lines) {
            actions.add(ActionEngine.parse(line).orElseThrow());
        }
        return actions;
    }
}
