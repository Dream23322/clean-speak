package dev.cleanspeak.storage;

import dev.cleanspeak.api.Category;
import dev.cleanspeak.api.MessageAudience;
import dev.cleanspeak.api.ModerationAction;
import dev.cleanspeak.api.ModerationDecision;
import dev.cleanspeak.api.ModerationRequest;
import dev.cleanspeak.config.PluginConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class DecisionStoreTest {

    @TempDir
    Path temp;

    @Test
    void rawPrivateTextIsNotStoredByDefaultAndRecordsReload() throws Exception {
        PluginConfig config = new PluginConfig();
        config.logging.writeDecisionLog = true;
        DecisionStore store = new DecisionStore(temp, config, Logger.getLogger("test"));
        ModerationRequest request = ModerationRequest.builder()
                .sender(UUID.randomUUID(), "Tester")
                .audience(MessageAudience.PRIVATE_MESSAGE)
                .channelId("test:pm")
                .conversationId("test:pm:one")
                .message("a private secret phrase")
                .build();
        ModerationDecision decision = new ModerationDecision(request.requestId(),
                ModerationAction.REPHRASE, ModerationAction.REPHRASE, false,
                Set.of(Category.HATE_SLUR), List.of(), "rule match", "not delivered",
                "rules", "test", false, false, request.channelId(), request.conversationId(), System.currentTimeMillis());
        store.record(request, decision);
        Thread.sleep(300L);
        store.close();

        String log = Files.readString(temp.resolve("decisions.jsonl"));
        assertFalse(log.contains("private secret phrase"));
        assertTrue(log.contains("messageHash"));

        DecisionStore reloaded = new DecisionStore(temp, config, Logger.getLogger("test"));
        assertTrue(reloaded.find(request.requestId().toString()).isPresent());
        reloaded.close();
    }
}
