package dev.cleanspeak.config;

import dev.cleanspeak.api.Category;
import dev.cleanspeak.api.ModerationAction;
import dev.cleanspeak.core.ml.BundledTextClassifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class ConfigLoaderTest {

    @TempDir
    Path temp;

    @Test
    void defaultFilesLoadAndPreserveSafeDefaults() throws IOException {
        copyDefaults();
        ConfigLoader.LoadedConfig loaded = ConfigLoader.load(temp.toFile(), Logger.getLogger("test"));
        assertFalse(loaded.rulesFallback());
        assertTrue(loaded.warnings().isEmpty(), () -> "unexpected warnings: " + loaded.warnings());
        assertFalse(loaded.config().text.shadowMode);
        assertTrue(loaded.config().text.ml.enabled);
        assertTrue(loaded.config().text.ml.endpoint.isBlank());
        assertTrue(new BundledTextClassifier().enabled());
        assertTrue(loaded.actions().enabled());
        assertTrue(loaded.actions().checks().containsKey(Category.HATE_SLUR));
        assertTrue(loaded.actions().checks().get(Category.HATE_SLUR).actions().size() >= 2);
        assertEquals(ModerationAction.REPHRASE,
                loaded.textPolicy().actionFor(Category.HATE_SLUR));
        assertEquals(ModerationAction.BLOCK,
                loaded.textPolicy().actionFor(Category.THREAT));
        assertFalse(loaded.textPolicy().enabled(Category.PROFANITY));
        assertTrue(loaded.textPolicy().enabled(Category.HATE_SLUR));
        assertFalse(loaded.textPolicy().messages().forAction(
                ModerationAction.REPHRASE, "hate speech", java.util.UUID.randomUUID()).isBlank());
        assertTrue(loaded.rules().rules().size() >= 6);
        assertTrue(loaded.rules().linkPolicy().scamEnabled());
        assertFalse(loaded.rules().linkPolicy().advertisingEnabled());
    }

    @Test
    void checkSettingsAndMessagesOverrideDefaults() throws IOException {
        copyDefaults();
        Path checks = temp.resolve("checks.yml");
        Files.writeString(checks, Files.readString(checks).replace(
                "hate-slurs:\n  enabled: true", "hate-slurs:\n  enabled: false"));
        Path messages = temp.resolve("messages.yml");
        Files.writeString(messages, Files.readString(messages).replace(
                "Your message was not delivered. Please rephrase it without {category}.",
                "Custom rephrase: {category}."));
        ConfigLoader.LoadedConfig loaded = ConfigLoader.load(temp.toFile(), Logger.getLogger("test"));
        assertFalse(loaded.textPolicy().enabled(Category.HATE_SLUR));
        assertTrue(loaded.textPolicy().messages().forAction(
                ModerationAction.REPHRASE, "a custom check", java.util.UUID.randomUUID())
                .contains("Custom rephrase: a custom check."));
    }

    @Test
    void migratesLegacyPunishmentsAutomatically() throws IOException {
        copyDefaults();
        Files.deleteIfExists(temp.resolve("actions.yml"));
        Files.writeString(temp.resolve("punishments.yml"), """
                enabled: true
                reset-seconds: 120
                checks:
                  hate-slurs:
                    enabled: true
                    thresholds:
                      - count: 3
                        commands:
                          - "mute %player% 10m hate speech"
                """);
        ConfigLoader.LoadedConfig loaded = ConfigLoader.load(temp.toFile(), Logger.getLogger("test"));
        assertTrue(Files.exists(temp.resolve("actions.yml")));
        assertTrue(Files.exists(temp.resolve("punishments.yml.migrated")));
        assertTrue(loaded.actions().checks().get(Category.HATE_SLUR).actions().stream()
                .anyMatch(action -> action.kind() == dev.cleanspeak.core.action.ActionSettings.Kind.COMMAND
                        && action.threshold() == 3));
        assertTrue(loaded.warnings().stream().anyMatch(w -> w.contains("Migrated punishments.yml")));
    }

    @Test
    void invalidRulesUseMinimalFallbackInsteadOfCrashing() throws IOException {
        copyDefaults();
        Files.writeString(temp.resolve("rules.yml"), "rules:\n  - id: broken\n    category: THREAT\n    type: REGEX\n    action: BLOCK\n    pattern: ''\n");
        ConfigLoader.LoadedConfig loaded = ConfigLoader.load(temp.toFile(), Logger.getLogger("test"));
        assertTrue(loaded.rulesFallback());
        assertFalse(loaded.warnings().isEmpty());
        assertFalse(loaded.rules().rules().isEmpty());
    }

    private void copyDefaults() throws IOException {
        Files.copy(Path.of("src/main/resources/config.yml"), temp.resolve("config.yml"), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(Path.of("src/main/resources/rules.yml"), temp.resolve("rules.yml"), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(Path.of("src/main/resources/checks.yml"), temp.resolve("checks.yml"), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(Path.of("src/main/resources/messages.yml"), temp.resolve("messages.yml"), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(Path.of("src/main/resources/actions.yml"), temp.resolve("actions.yml"), StandardCopyOption.REPLACE_EXISTING);
    }
}
