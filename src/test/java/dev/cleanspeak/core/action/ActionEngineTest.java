package dev.cleanspeak.core.action;

import dev.cleanspeak.api.Category;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionEngineTest {

    @Test
    void parsesThresholdIntervalAndSpecialActions() {
        ActionSettings.Action block = ActionEngine.parse("[1:0] [block]").orElseThrow();
        assertEquals(1, block.threshold());
        assertEquals(0, block.interval());
        assertEquals(ActionSettings.Kind.BLOCK, block.kind());
        assertTrue(block.matches(1));
        assertFalse(block.matches(2));

        ActionSettings.Action alert = ActionEngine.parse("[2:2]   [ALERT]").orElseThrow();
        assertEquals(ActionSettings.Kind.ALERT, alert.kind());
        assertTrue(alert.matches(2));
        assertFalse(alert.matches(3));
        assertTrue(alert.matches(4));
        assertTrue(alert.matches(6));

        ActionSettings.Action command = ActionEngine.parse("[3:0] /mute %player% 1h yeah").orElseThrow();
        assertEquals(ActionSettings.Kind.COMMAND, command.kind());
        assertEquals("mute %player% 1h yeah", command.value());
    }

    @Test
    void rejectsMalformedLines() {
        assertTrue(ActionEngine.parse("1:0 [block]").isEmpty());
        assertTrue(ActionEngine.parse("[0:0] [block]").isEmpty());
        assertTrue(ActionEngine.parse("[1:0]").isEmpty());
        assertTrue(ActionEngine.parse("").isEmpty());
    }

    @Test
    void dangerousCommandsAreBlockedUnlessOptedIn() {
        assertTrue(ActionEngine.isDangerous("ban Alice"));
        assertTrue(ActionEngine.isDangerous("/minecraft:kick Alice"));
        assertFalse(ActionEngine.isDangerous("mute Alice 10m hate"));
        assertFalse(ActionEngine.isDangerous("warn Alice spam"));
    }

    @Test
    void expandsPlaceholders() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
        String expanded = ActionEngine.expand(
                "mute %player% %count% %category% %request% %reason%", id, "Alice",
                Category.HATE_SLUR, 3, "BLOCK", "req-1", "rule:hate");
        assertEquals("mute Alice 3 HATE_SLUR req-1 rule:hate", expanded);
    }

    @Test
    void commandSelectionRunsOnlyMatchingThreshold() {
        ActionSettings settings = new ActionSettings(true, 3600, false, java.util.Map.of(
                Category.HATE_SLUR, new ActionSettings.Check(true, 3600,
                        List.of(
                                ActionEngine.parse("[3:0] mute %player% 10m %category%").orElseThrow(),
                                ActionEngine.parse("[5:0] mute %player% 1h").orElseThrow()))
        ));
        assertEquals(List.of("mute Alice 10m HATE_SLUR"), ActionEngine.commandsFor(
                settings, Category.HATE_SLUR, 3, UUID.randomUUID(), "Alice",
                "REPHRASE", "req-1", "reason"));
        assertTrue(ActionEngine.commandsFor(
                settings, Category.HATE_SLUR, 4, UUID.randomUUID(), "Alice",
                "REPHRASE", "req-1", "reason").isEmpty());
    }
}
