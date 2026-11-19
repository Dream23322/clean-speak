package dev.cleanspeak;

import dev.cleanspeak.api.MessageAudience;
import dev.cleanspeak.api.ModerationAction;
import dev.cleanspeak.api.ModerationRequest;
import dev.cleanspeak.config.ConfigLoader;
import dev.cleanspeak.core.ConversationTracker;
import dev.cleanspeak.core.ModerationService;
import dev.cleanspeak.core.RuleEngine;
import dev.cleanspeak.core.TextNormalizer;
import dev.cleanspeak.core.ml.BundledTextClassifier;
import dev.cleanspeak.core.ml.DisabledTextClassifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Startup fallback coverage for the absence of model workers. Full
 * plugin startup was also exercised on a live Paper server; this test isolates
 * the pure components that must remain usable without those dependencies.
 */
class StartupFallbackTest {

    @TempDir
    Path temp;

    private ConfigLoader.LoadedConfig loadDefaults() throws IOException {
        Files.copy(Path.of("src/main/resources/config.yml"), temp.resolve("config.yml"), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(Path.of("src/main/resources/rules.yml"), temp.resolve("rules.yml"), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(Path.of("src/main/resources/checks.yml"), temp.resolve("checks.yml"), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(Path.of("src/main/resources/messages.yml"), temp.resolve("messages.yml"), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(Path.of("src/main/resources/actions.yml"), temp.resolve("actions.yml"), StandardCopyOption.REPLACE_EXISTING);
        return ConfigLoader.load(temp.toFile(), Logger.getLogger("test"));
    }

    private ModerationService service(ConfigLoader.LoadedConfig loaded,
                                      dev.cleanspeak.core.ml.TextClassifier classifier) {
        return new ModerationService(
                new RuleEngine(new TextNormalizer(), loaded.rules()),
                new ConversationTracker(3, 30_000L, 100),
                classifier,
                loaded.mlSettings(),
                loaded.textPolicy(),
                1, 4, 500, (request, decision) -> {}, ignored -> {});
    }

    @Test
    void bundledModelCancelsTargetedChatByDefault() throws IOException {
        ConfigLoader.LoadedConfig loaded = loadDefaults();
        assertFalse(loaded.config().text.shadowMode);
        ModerationService service = service(loaded, new BundledTextClassifier());
        try {
            ModerationRequest targeted = ModerationRequest.builder()
                    .sender(UUID.randomUUID(), "Tester")
                    .audience(MessageAudience.PUBLIC_CHAT)
                    .channelId("paper:public-chat")
                    .conversationId("paper:public-chat:public")
                    .message("people like you should be removed from this server")
                    .build();
            var decision = service.moderateRulesOnly(targeted);
            assertFalse(decision.isAllowed());
            assertFalse(decision.shadowed());
            assertTrue(decision.modelVersion().contains("bundled"), decision.modelVersion());

            ModerationRequest neutral = ModerationRequest.builder()
                    .sender(UUID.randomUUID(), "Tester")
                    .audience(MessageAudience.PUBLIC_CHAT)
                    .channelId("paper:public-chat")
                    .conversationId("paper:public-chat:public")
                    .message("I hate mining, it takes forever")
                    .build();
            assertTrue(service.moderateRulesOnly(neutral).isAllowed());
        } finally {
            service.close();
        }
    }

    @Test
    void identityHateWithPluralSlurAndInsultsIsBlocked() throws IOException {
        ConfigLoader.LoadedConfig loaded = loadDefaults();
        ModerationService service = service(loaded, new BundledTextClassifier());
        try {
            for (String text : java.util.List.of(
                    "i hate trans sluts",
                    "i hate gay sluts",
                    "i hate fags",
                    "gay whore",
                    "trans whore",
                    "fags",
                    "faggs",
                    "why is this fags still here",
                    "you people should not exist",
                    "go back to your country",
                    "your kind should be removed",
                    "Nigga, what the fuck?")) {
                ModerationRequest request = ModerationRequest.builder()
                        .sender(UUID.randomUUID(), "Tester")
                        .audience(MessageAudience.PUBLIC_CHAT)
                        .channelId("paper:public-chat")
                        .conversationId("paper:public-chat:public")
                        .message(text)
                        .build();
                var decision = service.moderateRulesOnly(request);
                assertFalse(decision.isAllowed(), text + " was allowed");
            }
        } finally {
            service.close();
        }
    }

    @Test
    void gamingBanterAndGameActionsStayAllowed() throws IOException {
        ConfigLoader.LoadedConfig loaded = loadDefaults();
        ModerationService service = service(loaded, new BundledTextClassifier());
        try {
            for (String text : java.util.List.of(
                    "you missed every shot",
                    "good game everyone",
                    "your strategy makes no sense",
                    "I'm going to make you lose",
                    "I will beat you next round",
                    "I'll finish you in the arena",
                    "this boss is impossible",
                    "I hate mining, it takes forever")) {
                ModerationRequest request = ModerationRequest.builder()
                        .sender(UUID.randomUUID(), "Tester")
                        .audience(MessageAudience.PUBLIC_CHAT)
                        .channelId("paper:public-chat")
                        .conversationId("paper:public-chat:public")
                        .message(text)
                        .build();
                var decision = service.moderateRulesOnly(request);
                assertTrue(decision.isAllowed(), text + " was blocked as " + decision.action());
            }
        } finally {
            service.close();
        }
    }

    @Test
    void defaultConfigurationStartsWithRulesOnly() throws IOException {
        Files.copy(Path.of("src/main/resources/config.yml"), temp.resolve("config.yml"), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(Path.of("src/main/resources/rules.yml"), temp.resolve("rules.yml"), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(Path.of("src/main/resources/checks.yml"), temp.resolve("checks.yml"), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(Path.of("src/main/resources/messages.yml"), temp.resolve("messages.yml"), StandardCopyOption.REPLACE_EXISTING);
        ConfigLoader.LoadedConfig loaded = ConfigLoader.load(temp.toFile(), Logger.getLogger("test"));
        assertTrue(loaded.config().text.ml.enabled);
        assertTrue(loaded.config().text.ml.endpoint.isBlank());
        assertTrue(new BundledTextClassifier().enabled());
        assertFalse(DisabledTextClassifier.INSTANCE.enabled());

        ModerationService service = new ModerationService(
                new RuleEngine(new TextNormalizer(), loaded.rules()),
                new ConversationTracker(3, 30_000L, 100),
                DisabledTextClassifier.INSTANCE,
                loaded.mlSettings(),
                loaded.textPolicy(),
                1, 4, 500, (request, decision) -> {}, ignored -> {});
        ModerationRequest request = ModerationRequest.builder()
                .sender(UUID.randomUUID(), "Tester")
                .audience(MessageAudience.PUBLIC_CHAT)
                .channelId("startup")
                .conversationId("startup:public")
                .message("you are stupid")
                .build();
        var decision = service.moderateRulesOnly(request);
        assertEquals(ModerationAction.ALLOW, decision.action());
        assertEquals(ModerationAction.ALLOW, decision.rawAction());
        assertFalse(decision.shadowed());
        service.close();
    }
}
