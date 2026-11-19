package dev.cleanspeak.config;

import dev.cleanspeak.api.Category;
import dev.cleanspeak.api.ModerationAction;
import dev.cleanspeak.core.LinkPolicy;
import dev.cleanspeak.core.Rule;
import dev.cleanspeak.core.RuleSet;
import dev.cleanspeak.core.RuleType;
import dev.cleanspeak.core.SpamPolicy;
import dev.cleanspeak.core.MessageTemplates;
import dev.cleanspeak.core.TextPolicy;
import dev.cleanspeak.core.action.ActionEngine;
import dev.cleanspeak.core.action.ActionSettings;
import dev.cleanspeak.core.ml.MlSettings;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Reads config.yml and rules.yml. Invalid values produce warnings rather than
 * silently changing policy. If rules.yml cannot be parsed, a minimal local
 * fallback is used and the caller is told so.
 */
public final class ConfigLoader {

    public record LoadedConfig(
            PluginConfig config,
            RuleSet rules,
            TextPolicy textPolicy,
            MlSettings mlSettings,
            ActionSettings actions,
            List<String> warnings,
            boolean rulesFallback
    ) {
    }

    private final File dataFolder;
    private final Logger logger;
    private final List<String> warnings = new ArrayList<>();

    private ConfigLoader(File dataFolder, Logger logger) {
        this.dataFolder = dataFolder;
        this.logger = logger;
    }

    public static LoadedConfig load(JavaPlugin plugin) {
        return load(plugin.getDataFolder(), plugin.getLogger());
    }

    public static LoadedConfig load(File dataFolder, Logger logger) {
        return new ConfigLoader(dataFolder, logger).loadInternal();
    }

    private LoadedConfig loadInternal() {
        PluginConfig config = new PluginConfig();
        File configFile = new File(dataFolder, "config.yml");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(configFile);

        config.configVersion = yaml.getInt("config-version", 1);
        config.policyVersion = yaml.getString("policy-version", "dev");
        if (config.configVersion < 2) {
            warn("config-version is below 2. Move custom category-actions and ml.thresholds into checks.yml; legacy values remain available as fallbacks only when checks.yml has no value.");
        }

        ConfigurationSection text = section(yaml, "text");
        config.text.enabled = text.getBoolean("enabled", true);
        config.text.shadowMode = text.getBoolean("shadow-mode", false);
        config.text.deliveryMode = text.getString("delivery-mode", "RULES_ONLY").toUpperCase(Locale.ROOT);
        if (!config.text.deliveryMode.equals("RULES_ONLY") && !config.text.deliveryMode.equals("ASYNC_REPLAY")) {
            warn("Unknown text.delivery-mode '" + config.text.deliveryMode + "'; using RULES_ONLY.");
            config.text.deliveryMode = "RULES_ONLY";
        }
        config.text.notifyBlocked = text.getBoolean("notify-blocked", true);
        config.text.noticeCooldownSeconds = Math.max(0, text.getInt("notice-cooldown-seconds", 0));
        config.text.maxMessageLength = positive(text.getInt("max-message-length", 512), 512);
        config.text.contextMaxMessages = positive(text.getInt("context.max-messages", 3), 3);
        config.text.contextTtlSeconds = positive(text.getInt("context.ttl-seconds", 45), 45);
        config.text.contextMaxKeys = positive(text.getInt("context.max-keys", 5000), 5000);

        ConfigurationSection actions = text.getConfigurationSection("category-actions");
        for (Category category : Category.values()) {
            String raw = actions == null ? null : actions.getString(category.name());
            if (raw == null) {
                raw = config.text.categoryActions.get(category.name());
            }
            config.text.categoryActions.put(category.name(), parseAction(raw, category).name());
        }

        ConfigurationSection ml = text.getConfigurationSection("ml");
        config.text.ml.enabled = ml != null && ml.getBoolean("enabled", false);
        config.text.ml.endpoint = ml == null ? "" : ml.getString("endpoint", "");
        config.text.ml.allowRemoteEndpoint = ml != null && ml.getBoolean("allow-remote-endpoint", false);
        config.text.ml.modelVersion = ml == null ? "local" : ml.getString("model-version", "local");
        config.text.ml.timeoutMillis = positive(ml == null ? 800 : ml.getInt("timeout-ms", 800), 800);
        config.text.ml.failureMode = ml == null ? "RULES_ONLY"
                : ml.getString("failure-mode", "RULES_ONLY").toUpperCase(Locale.ROOT);
        if (!config.text.ml.failureMode.equals("RULES_ONLY")) {
            warn("Unsupported ml.failure-mode '" + config.text.ml.failureMode + "'; using RULES_ONLY.");
            config.text.ml.failureMode = "RULES_ONLY";
        }
        config.text.ml.mayBlock = ml != null && ml.getBoolean("may-block", false);
        config.text.ml.minConfidence = clamp(ml == null ? 0.55D : ml.getDouble("min-confidence", 0.55D));
        ConfigurationSection thresholds = ml == null ? null : ml.getConfigurationSection("thresholds");
        for (Category category : Category.values()) {
            PluginConfig.Threshold threshold = new PluginConfig.Threshold();
            if (thresholds != null && thresholds.isConfigurationSection(category.name())) {
                ConfigurationSection c = thresholds.getConfigurationSection(category.name());
                threshold.review = clamp(c.getDouble("review", 0.55D));
                threshold.block = clamp(c.getDouble("block", 0.88D));
            }
            config.text.ml.thresholds.put(category.name(), threshold);
        }

        ConfigurationSection links = section(yaml, "links");
        config.links.scamEnabled = links.getBoolean("scam-enabled", true);
        config.links.advertisingEnabled = links.getBoolean("advertising-enabled", false);
        config.links.scamAction = parseAction(links.getString("scam-action", "BLOCK"), Category.SCAM_ADVERTISING).name();
        config.links.advertisingAction = parseAction(links.getString("advertising-action", "REPHRASE"), Category.SCAM_ADVERTISING).name();
        config.links.allowlist = stringSet(links.getStringList("allowlist"));
        config.links.blocklist = stringSet(links.getStringList("blocklist"));
        config.links.shorteners = stringSet(links.getStringList("shorteners"));
        config.links.suspiciousTlds = stringSet(links.getStringList("suspicious-tlds"));

        ConfigurationSection spam = section(yaml, "spam");
        config.spam.maxMessagesPerWindow = positive(spam.getInt("max-messages-per-window", 6), 6);
        config.spam.windowSeconds = positive(spam.getInt("window-seconds", 10), 10);
        config.spam.duplicateThreshold = positive(spam.getInt("duplicate-threshold", 3), 3);
        config.spam.maxCapsRatio = clamp(spam.getDouble("max-caps-ratio", 0.80D));
        config.spam.maxRepeatedCharacterRun = positive(spam.getInt("max-repeated-character-run", 14), 14);
        config.spam.maxUrls = positive(spam.getInt("max-urls", 4), 4);
        config.spam.action = parseAction(spam.getString("action", "REPHRASE"), Category.SPAM).name();
        config.spam.duplicateAction = parseAction(spam.getString("duplicate-action", "REPHRASE"), Category.SPAM).name();
        config.spam.capsAction = parseAction(spam.getString("caps-action", "REPHRASE"), Category.SPAM).name();

        ConfigurationSection queues = section(yaml, "queues");
        config.queues.apiWorkers = positive(queues.getInt("api-workers", 2), 2);
        config.queues.apiCapacity = positive(queues.getInt("api-capacity", 256), 256);
        config.queues.requestTimeoutMillis = positive((int) queues.getLong("request-timeout-ms", 1500L), 1500);
        config.queues.conversationMaxPending = positive(queues.getInt("conversation-max-pending", 8), 8);
        config.queues.conversationMaxActive = positive(queues.getInt("conversation-max-active", 2000), 2000);

        ConfigurationSection privacy = section(yaml, "privacy");
        config.privacy.joinNotice = privacy.getBoolean("join-notice", true);
        config.privacy.joinNoticeMessage = privacy.getString("join-notice-message", "");
        config.privacy.retainRawText = privacy.getBoolean("retain-raw-text", false);
        config.privacy.decisionRetentionDays = positive(privacy.getInt("decision-retention-days", 14), 14);

        ConfigurationSection staff = section(yaml, "staff");
        config.staff.alerts = staff.getBoolean("alerts", true);
        config.staff.alertCooldownSeconds = Math.max(0, staff.getInt("alert-cooldown-seconds", 0));
        config.staff.recentLimit = positive(staff.getInt("recent-limit", 200), 200);
        config.staff.appealCooldownSeconds = positive(staff.getInt("appeal-cooldown-seconds", 60), 60);

        ConfigurationSection logging = section(yaml, "logging");
        config.logging.logAllowed = logging.getBoolean("log-allowed", false);
        config.logging.logShadow = logging.getBoolean("log-shadow", true);
        config.logging.writeDecisionLog = logging.getBoolean("write-decision-log", true);

        ConfigurationSection integrations = section(yaml, "integrations");
        config.integrations.essentialsRulesOnly = integrations.getBoolean("essentials-rules-only", true);

        ConfigurationSection raw = section(yaml, "raw-command-inspection");
        config.rawCommandInspection.enabled = raw.getBoolean("enabled", false);
        ConfigurationSection inspect = raw.getConfigurationSection("inspect");
        if (inspect != null) {
            for (String key : inspect.getKeys(false)) {
                config.rawCommandInspection.inspect.put(key.toLowerCase(Locale.ROOT), inspect.getInt(key, 0));
            }
        }

        loadChecks(config);
        loadMessages(config);
        ActionSettings actionSettings = loadActions();

        this.current = config;
        boolean rulesFallback = false;
        RuleSet rules;
        try {
            rules = loadRules();
        } catch (RuntimeException e) {
            warn("rules.yml could not be loaded: " + e.getMessage() + ". Using minimal local fallback rules.");
            rules = RuleSet.minimalFallback();
            rulesFallback = true;
        }

        MlSettings mlSettings = new MlSettings(
                config.text.ml.enabled,
                config.text.ml.endpoint,
                config.text.ml.allowRemoteEndpoint,
                config.text.ml.modelVersion,
                config.text.ml.timeoutMillis,
                checkThresholds(config, true),
                checkThresholds(config, false),
                config.text.ml.failureMode
        );

        EnumMap<Category, ModerationAction> categoryActions = new EnumMap<>(Category.class);
        EnumSet<Category> disabledCategories = EnumSet.noneOf(Category.class);
        EnumMap<Category, String> displayNames = new EnumMap<>(Category.class);
        for (Category category : Category.values()) {
            PluginConfig.Check check = config.checks.get(category.name());
            if (check == null) {
                categoryActions.put(category, parseAction(config.text.categoryActions.get(category.name()), category));
                displayNames.put(category, category.playerLabel());
            } else {
                categoryActions.put(category, parseAction(check.action, category));
                if (!check.enabled) {
                    disabledCategories.add(category);
                }
                if (check.displayName != null && !check.displayName.isBlank()) {
                    displayNames.put(category, check.displayName);
                } else {
                    displayNames.put(category, category.playerLabel());
                }
            }
        }
        MessageTemplates templates = new MessageTemplates(
                config.messages.playerRephrase,
                config.messages.playerBlock,
                config.messages.playerReview
        );
        TextPolicy textPolicy = new TextPolicy(
                config.text.shadowMode,
                categoryActions,
                config.policyVersion,
                config.text.ml.enabled,
                config.text.ml.mayBlock,
                config.text.ml.minConfidence,
                disabledCategories,
                displayNames,
                templates
        );

        return new LoadedConfig(config, rules, textPolicy, mlSettings, actionSettings,
                List.copyOf(warnings), rulesFallback);
    }


    private void loadChecks(PluginConfig config) {
        File checksFile = new File(dataFolder, "checks.yml");
        YamlConfiguration yaml = checksFile.isFile()
                ? YamlConfiguration.loadConfiguration(checksFile)
                : new YamlConfiguration();
        ConfigurationSection section = yaml.getConfigurationSection("checks");
        ConfigurationSection root = section == null ? yaml : section;
        Map<String, ConfigurationSection> byKey = new LinkedHashMap<>();
        for (String key : root.getKeys(false)) {
            if (key.equalsIgnoreCase("config-version") || key.equalsIgnoreCase("version")) {
                continue;
            }
            ConfigurationSection checkSection = root.getConfigurationSection(key);
            if (checkSection == null) {
                warn("checks.yml entry '" + key + "' is not a section; ignoring it.");
                continue;
            }
            String normalized = normalizeCheckKey(key);
            Category category = categoryForCheck(normalized);
            if (category == null) {
                warn("checks.yml entry '" + key + "' does not match a known category; ignoring it.");
                continue;
            }
            if (byKey.put(normalized, checkSection) != null) {
                warn("checks.yml has duplicate entries for '" + key + "'.");
            }
        }

        for (Category category : Category.values()) {
            PluginConfig.Check check = new PluginConfig.Check();
            check.enabled = true;
            check.action = config.text.categoryActions.getOrDefault(category.name(), "REPHRASE");
            check.displayName = category.playerLabel();
            check.description = "";
            PluginConfig.Threshold legacy = config.text.ml.thresholds.get(category.name());
            check.mlReviewThreshold = legacy == null ? defaultReviewThreshold(category) : legacy.review;
            check.mlBlockThreshold = legacy == null ? defaultBlockThreshold(category) : legacy.block;

            ConfigurationSection configured = findCheckSection(byKey, category);
            if (configured != null) {
                check.enabled = configured.getBoolean("enabled", true);
                String action = configured.getString("action", check.action);
                check.action = parseAction(action, category).name();
                String display = configured.getString("display-name",
                        configured.getString("displayname", check.displayName));
                if (display != null && !display.isBlank()) {
                    check.displayName = display;
                }
                check.description = configured.getString("description", "");
                ConfigurationSection ml = configured.getConfigurationSection("ml");
                if (ml != null) {
                    check.mlReviewThreshold = clamp(ml.getDouble("review-threshold",
                            ml.getDouble("review", check.mlReviewThreshold)));
                    check.mlBlockThreshold = clamp(ml.getDouble("block-threshold",
                            ml.getDouble("block", check.mlBlockThreshold)));
                }
                check.mlReviewThreshold = clamp(configured.getDouble("ml-review-threshold", check.mlReviewThreshold));
                check.mlBlockThreshold = clamp(configured.getDouble("ml-block-threshold", check.mlBlockThreshold));
            }
            if (check.mlBlockThreshold < check.mlReviewThreshold) {
                warn("Check " + category + " has block threshold below review threshold; raising block threshold.");
                check.mlBlockThreshold = check.mlReviewThreshold;
            }
            config.checks.put(category.name(), check);
        }
        if (!checksFile.isFile()) {
            warn("checks.yml is missing; using built-in per-check defaults.");
        }
    }

    private ActionSettings loadActions() {
        File file = new File(dataFolder, "actions.yml");
        File legacy = new File(dataFolder, "punishments.yml");
        if (!file.isFile() && legacy.isFile()) {
            migrateLegacyPunishments(legacy, file);
        }
        YamlConfiguration yaml = file.isFile()
                ? YamlConfiguration.loadConfiguration(file)
                : new YamlConfiguration();
        boolean enabled = yaml.getBoolean("enabled", true);
        int resetSeconds = Math.max(30, Math.min(86_400,
                yaml.getInt("reset-time", yaml.getInt("reset-seconds", 3600))));
        boolean allowDangerous = yaml.getBoolean("allow-dangerous-commands", false);
        EnumMap<Category, ActionSettings.Check> checks = new EnumMap<>(Category.class);
        ConfigurationSection wrapped = yaml.getConfigurationSection("checks");
        ConfigurationSection root = wrapped == null ? yaml : wrapped;
        for (String key : root.getKeys(false)) {
            if (isGlobalActionKey(key)) {
                continue;
            }
            Category category = categoryForCheck(key);
            if (category == null) {
                warn("actions.yml entry '" + key + "' does not match a known category; ignoring it.");
                continue;
            }
            Object value = root.get(key);
            List<String> actionLines = new ArrayList<>();
            boolean checkEnabled = true;
            int checkReset = resetSeconds;
            if (value instanceof ConfigurationSection check) {
                checkEnabled = check.getBoolean("enabled", true);
                checkReset = Math.max(30, Math.min(86_400,
                        check.getInt("reset-time", check.getInt("reset-seconds", resetSeconds))));
                actionLines.addAll(check.getStringList("actions"));
                if (actionLines.isEmpty()) {
                    actionLines.addAll(check.getStringList("commands"));
                }
            } else if (value instanceof List<?> list) {
                for (Object item : list) {
                    if (item != null) {
                        actionLines.add(String.valueOf(item));
                    }
                }
            } else {
                warn("actions.yml entry '" + key + "' is not a section or list; ignoring it.");
                continue;
            }

            List<ActionSettings.Action> actions = parseActions(actionLines, key);
            if (!allowDangerous) {
                for (ActionSettings.Action action : actions) {
                    warnDangerous(action, category);
                }
            }
            checks.put(category, new ActionSettings.Check(
                    checkEnabled, checkReset, actions));
        }
        if (!file.isFile()) {
            warn("actions.yml is missing; no per-check actions are active.");
        }
        return new ActionSettings(enabled, resetSeconds, allowDangerous, checks);
    }

    private List<ActionSettings.Action> parseActions(List<String> lines, String checkKey) {
        List<ActionSettings.Action> out = new ArrayList<>();
        for (String line : lines) {
            var parsed = ActionEngine.parse(line);
            if (parsed.isEmpty()) {
                warn("actions.yml entry '" + checkKey + "' has an invalid action line: " + line);
            } else {
                out.add(parsed.get());
            }
        }
        return out;
    }

    private void warnDangerous(ActionSettings.Action action, Category category) {
        if (action.kind() != ActionSettings.Kind.COMMAND) {
            return;
        }
        if (ActionEngine.isDangerous(action.value())) {
            warn("actions.yml has a blocked dangerous command for " + category
                    + "; set allow-dangerous-commands: true only if that is your policy.");
        }
    }

    private void migrateLegacyPunishments(File legacy, File actionsFile) {
        try {
            YamlConfiguration old = YamlConfiguration.loadConfiguration(legacy);
            int globalReset = Math.max(30, Math.min(86_400, old.getInt("reset-seconds", 3600)));
            YamlConfiguration migrated = new YamlConfiguration();
            migrated.set("enabled", old.getBoolean("enabled", true));
            migrated.set("reset-time", globalReset);
            migrated.set("allow-dangerous-commands", old.getBoolean("allow-dangerous-commands", false));
            ConfigurationSection legacyChecks = old.getConfigurationSection("checks");
            if (legacyChecks != null) {
                for (String key : legacyChecks.getKeys(false)) {
                    ConfigurationSection check = legacyChecks.getConfigurationSection(key);
                    if (check == null) {
                        continue;
                    }
                    Category category = categoryForCheck(key);
                    if (category == null) {
                        continue;
                    }
                    String base = checkKey(category);
                    migrated.set(base + ".enabled", check.getBoolean("enabled", true));
                    migrated.set(base + ".reset-time", check.getInt("reset-time",
                            check.getInt("reset-seconds", globalReset)));
                    List<String> actions = new ArrayList<>();
                    for (Map<?, ?> threshold : check.getMapList("thresholds")) {
                        int count = threshold.get("count") instanceof Number number
                                ? Math.max(1, number.intValue()) : 1;
                        List<String> commands = commandList(threshold.get("commands"));
                        if (commands.isEmpty()) {
                            commands = commandList(threshold.get("command"));
                        }
                        for (String command : commands) {
                            actions.add("[" + count + ":0] " + command);
                        }
                    }
                    for (String command : check.getStringList("commands")) {
                        actions.add("[1:0] " + command);
                    }
                    migrated.set(base + ".actions", actions);
                }
            }
            String header = "# CleanSpeak actions.yml\n"
                    + "# Auto-migrated from punishments.yml. Review commands and thresholds.\n\n";
            java.nio.file.Files.writeString(actionsFile.toPath(), header + migrated.saveToString(),
                    java.nio.charset.StandardCharsets.UTF_8);
            java.nio.file.Files.move(legacy.toPath(),
                    legacy.toPath().resolveSibling("punishments.yml.migrated"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            warn("Migrated punishments.yml to actions.yml. The old file is now punishments.yml.migrated.");
        } catch (Exception e) {
            warn("Could not migrate punishments.yml to actions.yml: " + e.getMessage());
        }
    }

    private static boolean isGlobalActionKey(String key) {
        String normalized = key.toLowerCase(Locale.ROOT);
        return normalized.equals("enabled")
                || normalized.equals("reset-time")
                || normalized.equals("reset-seconds")
                || normalized.equals("allow-dangerous-commands")
                || normalized.equals("config-version")
                || normalized.equals("version");
    }

    private static String checkKey(Category category) {
        return switch (category) {
            case HATE_SLUR -> "hate-speech";
            case TARGETED_HARASSMENT -> "targeted-harassment";
            case THREAT -> "threats";
            case SPAM -> "spam";
            case SCAM_ADVERTISING -> "scam-advertising";
            case PROFANITY -> "profanity";
            case OTHER -> "other";
        };
    }

    private static List<String> commandList(Object value) {
        List<String> commands = new ArrayList<>();
        if (value instanceof String text) {
            commands.add(text);
        } else if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    commands.add(String.valueOf(item));
                }
            }
        }
        return commands;
    }

    private void loadMessages(PluginConfig config) {
        File messagesFile = new File(dataFolder, "messages.yml");
        if (!messagesFile.isFile()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(messagesFile);
        ConfigurationSection player = yaml.getConfigurationSection("player");
        if (player != null) {
            config.messages.playerRephrase = player.getString("rephrase", config.messages.playerRephrase);
            config.messages.playerBlock = player.getString("block", config.messages.playerBlock);
            config.messages.playerReview = player.getString("review", config.messages.playerReview);
        }
    }

    private static ConfigurationSection findCheckSection(Map<String, ConfigurationSection> byKey, Category category) {
        for (Map.Entry<String, ConfigurationSection> entry : byKey.entrySet()) {
            if (categoryForCheck(entry.getKey()) == category) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static String normalizeCheckKey(String key) {
        return key == null ? "" : key.trim().toLowerCase(Locale.ROOT)
                .replace('_', '-').replace(' ', '-');
    }

    private static Category categoryForCheck(String key) {
        if (key == null) {
            return null;
        }
        String normalized = normalizeCheckKey(key);
        return switch (normalized) {
            case "hate", "hate-slur", "hate-slurs", "hatespeech", "hate-speech", "slurs" -> Category.HATE_SLUR;
            case "targeted", "harassment", "targeted-harassment" -> Category.TARGETED_HARASSMENT;
            case "threat", "threats", "violence" -> Category.THREAT;
            case "spam", "flooding" -> Category.SPAM;
            case "scam", "scams", "scam-advertising", "advertising", "links" -> Category.SCAM_ADVERTISING;
            case "profanity", "swearing" -> Category.PROFANITY;
            case "other", "misc" -> Category.OTHER;
            default -> {
                try {
                    yield Category.valueOf(normalized.toUpperCase(Locale.ROOT).replace('-', '_'));
                } catch (IllegalArgumentException e) {
                    yield null;
                }
            }
        };
    }

    private Map<Category, Double> checkThresholds(PluginConfig config, boolean review) {
        EnumMap<Category, Double> out = new EnumMap<>(Category.class);
        for (Category category : Category.values()) {
            PluginConfig.Check check = config.checks.get(category.name());
            if (check != null) {
                out.put(category, review ? check.mlReviewThreshold : check.mlBlockThreshold);
            }
        }
        return out;
    }

    private static double defaultReviewThreshold(Category category) {
        return switch (category) {
            case THREAT -> 0.50D;
            case SPAM -> 0.65D;
            case SCAM_ADVERTISING -> 0.60D;
            case PROFANITY -> 0.70D;
            default -> 0.55D;
        };
    }

    private static double defaultBlockThreshold(Category category) {
        return switch (category) {
            case HATE_SLUR, THREAT -> 0.85D;
            case SPAM -> 0.90D;
            case PROFANITY -> 0.95D;
            default -> 0.88D;
        };
    }

    private RuleSet loadRules() {
        File rulesFile = new File(dataFolder, "rules.yml");
        if (!rulesFile.isFile()) {
            throw new IllegalStateException("rules.yml is missing");
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(rulesFile);
        List<Map<?, ?>> maps = yaml.getMapList("rules");
        List<Rule> rules = new ArrayList<>();
        java.util.Set<String> ids = new java.util.LinkedHashSet<>();
        for (Map<?, ?> map : maps) {
            String id = string(map, "id");
            String categoryName = string(map, "category");
            String typeName = string(map, "type");
            String actionName = string(map, "action");
            if (id == null || categoryName == null || typeName == null) {
                throw new IllegalArgumentException("a rules.yml entry is missing id, category or type");
            }
            if (!ids.add(id)) {
                throw new IllegalArgumentException("duplicate rule id '" + id + "'");
            }
            Category category = parseCategoryStrict(categoryName);
            RuleType type;
            try {
                type = RuleType.valueOf(typeName.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("rule " + id + " has unknown type " + typeName);
            }
            ModerationAction action = parseAction(actionName, category);
            double score = clamp(number(map, "score", 0.8D));
            boolean enabled = bool(map, "enabled", true);
            List<String> terms = strings(map.get("terms"));
            String pattern = map.get("pattern") == null ? null : String.valueOf(map.get("pattern"));
            try {
                rules.add(new Rule(id, category, type, action, score, enabled, terms, pattern));
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("rule " + id + " is invalid: " + e.getMessage(), e);
            }
        }
        LinkPolicy linkPolicy = new LinkPolicy(
                configLinksScam(),
                configLinksAdvertising(),
                parseAction(configLinksScamAction(), Category.SCAM_ADVERTISING),
                parseAction(configLinksAdvertisingAction(), Category.SCAM_ADVERTISING),
                configLinksAllowlist(),
                configLinksBlocklist(),
                configLinksShorteners(),
                configLinksTlds()
        );
        SpamPolicy spamPolicy = new SpamPolicy(
                Math.max(64, configTextMaxLength()),
                configSpamMaxMessages(),
                configSpamWindow(),
                configSpamDuplicate(),
                configSpamCaps(),
                configSpamRepeated(),
                configSpamUrls(),
                parseAction(configSpamAction(), Category.SPAM),
                parseAction(configSpamDuplicateAction(), Category.SPAM),
                parseAction(configSpamCapsAction(), Category.SPAM)
        );
        if (rules.isEmpty()) {
            throw new IllegalStateException("rules.yml contains no rules");
        }
        return new RuleSet(rules, linkPolicy, spamPolicy);
    }

    // Small accessors used to keep the rule-policy construction above readable.
    private boolean configLinksScam() {
        return current.links.scamEnabled;
    }

    private boolean configLinksAdvertising() {
        return current.links.advertisingEnabled;
    }

    private String configLinksScamAction() {
        return current.links.scamAction;
    }

    private String configLinksAdvertisingAction() {
        return current.links.advertisingAction;
    }

    private Set<String> configLinksAllowlist() {
        return current.links.allowlist;
    }

    private Set<String> configLinksBlocklist() {
        return current.links.blocklist;
    }

    private Set<String> configLinksShorteners() {
        return current.links.shorteners;
    }

    private Set<String> configLinksTlds() {
        return current.links.suspiciousTlds;
    }

    private int configTextMaxLength() {
        return current.text.maxMessageLength;
    }

    private int configSpamMaxMessages() {
        return current.spam.maxMessagesPerWindow;
    }

    private int configSpamWindow() {
        return current.spam.windowSeconds;
    }

    private int configSpamDuplicate() {
        return current.spam.duplicateThreshold;
    }

    private double configSpamCaps() {
        return current.spam.maxCapsRatio;
    }

    private int configSpamRepeated() {
        return current.spam.maxRepeatedCharacterRun;
    }

    private int configSpamUrls() {
        return current.spam.maxUrls;
    }

    private String configSpamAction() {
        return current.spam.action;
    }

    private String configSpamDuplicateAction() {
        return current.spam.duplicateAction;
    }

    private String configSpamCapsAction() {
        return current.spam.capsAction;
    }

    private PluginConfig current;

    private Map<Category, Double> thresholds(Map<String, PluginConfig.Threshold> source, boolean review) {
        EnumMap<Category, Double> out = new EnumMap<>(Category.class);
        for (Category category : Category.values()) {
            PluginConfig.Threshold threshold = source.get(category.name());
            if (threshold != null) {
                out.put(category, review ? threshold.review : threshold.block);
            }
        }
        return out;
    }

    private static Set<String> stringSet(List<String> values) {
        Set<String> set = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    set.add(value.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        return set;
    }

    private static List<String> strings(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    out.add(String.valueOf(item));
                }
            }
        }
        return out;
    }

    private static String string(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static boolean bool(Map<?, ?> map, String key, boolean fallback) {
        Object value = map.get(key);
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            return Boolean.parseBoolean(s);
        }
        return fallback;
    }

    private static double number(Map<?, ?> map, String key, double fallback) {
        Object value = map.get(key);
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof String s) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static ConfigurationSection section(ConfigurationSection root, String path) {
        ConfigurationSection section = root.getConfigurationSection(path);
        return section == null ? root.createSection(path) : section;
    }

    private static int positive(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    private static double positiveDouble(double value, double min, double max) {
        if (Double.isNaN(value) || value < min || value > max) {
            return min;
        }
        return value;
    }

    private static double clamp(double value) {
        if (Double.isNaN(value)) {
            return 0.0D;
        }
        return Math.max(0.0D, Math.min(1.0D, value));
    }

    private Category parseCategory(String raw) {
        if (raw != null) {
            try {
                return Category.valueOf(raw.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
            } catch (IllegalArgumentException ignored) {
                warn("Unknown category '" + raw + "'; using OTHER.");
            }
        }
        return Category.OTHER;
    }

    private Category parseCategoryStrict(String raw) {
        try {
            return Category.valueOf(raw.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("unknown rule category '" + raw + "'", e);
        }
    }

    private ModerationAction parseAction(String raw, Category category) {
        if (raw != null) {
            try {
                return ModerationAction.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                warn("Unknown action '" + raw + "' for " + category + "; using REPHRASE.");
            }
        }
        return ModerationAction.REPHRASE;
    }

    private void warn(String message) {
        // The runtime logs the collected warnings after loading, so warnings are
        // not printed twice and startup output stays grouped.
        warnings.add(message);
    }
}
