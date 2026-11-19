package dev.cleanspeak;

import dev.cleanspeak.api.AdapterRegistration;
import dev.cleanspeak.api.CleanSpeakApi;
import dev.cleanspeak.api.HealthSnapshot;
import dev.cleanspeak.api.ModerationAction;
import dev.cleanspeak.api.ModerationDecision;
import dev.cleanspeak.api.ModerationRequest;
import dev.cleanspeak.config.ConfigLoader;
import dev.cleanspeak.config.PluginConfig;
import dev.cleanspeak.core.ConversationTracker;
import dev.cleanspeak.core.ModerationService;
import dev.cleanspeak.core.RuleEngine;
import dev.cleanspeak.core.TextPolicy;
import dev.cleanspeak.core.action.ActionService;
import dev.cleanspeak.core.ml.TextClassifierFactory;
import dev.cleanspeak.core.ml.MlSettings;
import dev.cleanspeak.core.ml.TextClassifier;
import dev.cleanspeak.storage.AppealStore;
import dev.cleanspeak.storage.DecisionStore;
import dev.cleanspeak.staff.PlayerNotices;
import dev.cleanspeak.staff.StaffNotifier;
import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Owns the current immutable runtime state.
 */
public final class CleanSpeakRuntime implements AutoCloseable {

    private final JavaPlugin plugin;
    private final Logger logger;
    private final Path dataDirectory;
    private final StaffNotifier staffNotifier;
    private final PlayerNotices playerNotices;
    private final CleanSpeakApiImpl api;
    private final AtomicBoolean closed = new AtomicBoolean();

    private volatile State state;

    public CleanSpeakRuntime(JavaPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.dataDirectory = plugin.getDataFolder().toPath();
        ensureDefaults();
        this.staffNotifier = new StaffNotifier(plugin, this::config);
        this.playerNotices = new PlayerNotices(plugin, this::config);
        this.api = new CleanSpeakApiImpl(this);
    }

    public void start() {
        ConfigLoader.LoadedConfig loaded = ConfigLoader.load(plugin);
        State newState = new State(loaded);
        state = newState;
        Bukkit.getServicesManager().register(CleanSpeakApi.class, api, plugin, ServicePriority.Normal);
        logStartup(newState, loaded);
    }

    public CleanSpeakApiImpl api() {
        return api;
    }

    public State state() {
        return state;
    }

    public PluginConfig config() {
        State current = state;
        return current == null ? new PluginConfig() : current.config;
    }

    public StaffNotifier staffNotifier() {
        return staffNotifier;
    }

    public PlayerNotices playerNotices() {
        return playerNotices;
    }

    public boolean reload() {
        if (closed.get()) {
            return false;
        }
        try {
            ConfigLoader.LoadedConfig loaded = ConfigLoader.load(plugin);
            State old = state;
            State replacement = new State(loaded);
            state = replacement;
            if (old != null) {
                old.close();
            }
            logStartup(replacement, loaded);
            return true;
        } catch (Throwable t) {
            logger.log(Level.SEVERE, "CleanSpeak reload failed; previous configuration remains active.", t);
            return false;
        }
    }

    public HealthSnapshot health() {
        State current = state;
        if (current == null) {
            return new HealthSnapshot(false, true, false, false, "not-ready", "none",
                    0, Map.of(), System.currentTimeMillis());
        }
        Map<String, String> routes = new LinkedHashMap<>();
        routes.put("public-chat", current.config.text.enabled
                ? (current.config.text.shadowMode ? "shadow (" + current.config.text.deliveryMode + ")" : "enforcing (" + current.config.text.deliveryMode + ")")
                : "disabled");
        routes.put("messaging-api", "enabled; caller must deliver exactly once");
        routes.put("raw-command-inspection", current.config.rawCommandInspection.enabled
                ? "limited; configured commands only" : "disabled");
        for (AdapterRegistration adapter : api.adapters().values()) {
            routes.put(adapter.id(), adapter.preDelivery() ? "pre-delivery: " + adapter.route() : "observer: " + adapter.route());
        }
        return new HealthSnapshot(
                current.config.text.enabled,
                current.config.text.shadowMode,
                current.config.text.ml.enabled,
                current.classifier.available(),
                current.classifier.status(),
                current.classifier.version(),
                current.service.queueDepth(),
                routes,
                System.currentTimeMillis()
        );
    }

    private void logStartup(State current, ConfigLoader.LoadedConfig loaded) {
        logger.info("CleanSpeak policy=" + current.config.policyVersion
                + " text=" + (current.config.text.enabled ? (current.config.text.shadowMode ? "shadow" : "enforcing") : "disabled")
                + " delivery=" + current.config.text.deliveryMode
                + " ml=" + (current.config.text.ml.enabled ? "enabled" : "disabled"));
        if (current.config.text.shadowMode) {
            logger.warning("Text shadow mode is ON: prohibited messages are logged but delivered. Review before enforcing.");
        }
        if (current.config.text.deliveryMode.equals("ASYNC_REPLAY")) {
            logger.warning("ASYNC_REPLAY is experimental: signed chat and existing chat formatting are not preserved for replayed messages.");
        }
        if (loaded.rulesFallback()) {
            logger.warning("Using minimal fallback rules. Add a valid rules.yml for full local coverage.");
        }
        for (String warning : loaded.warnings()) {
            logger.warning(warning);
        }
        logger.info("Routes: public chat=" + (current.config.text.enabled ? "integrated" : "disabled")
                + ", messaging API=available for adapters"
                + ", EssentialsX=" + (Bukkit.getPluginManager().getPlugin("Essentials") != null ? "adapter available (rules-only)" : "not installed")
                + ", raw command inspection=" + (current.config.rawCommandInspection.enabled ? "limited" : "disabled"));
    }

    private void ensureDefaults() {
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            logger.warning("Could not create plugin data directory " + plugin.getDataFolder());
        }
        File config = new File(plugin.getDataFolder(), "config.yml");
        if (!config.isFile()) {
            plugin.saveResource("config.yml", false);
        }
        File rules = new File(plugin.getDataFolder(), "rules.yml");
        if (!rules.isFile()) {
            plugin.saveResource("rules.yml", false);
        }
        File checks = new File(plugin.getDataFolder(), "checks.yml");
        if (!checks.isFile()) {
            plugin.saveResource("checks.yml", false);
        }
        File messages = new File(plugin.getDataFolder(), "messages.yml");
        if (!messages.isFile()) {
            plugin.saveResource("messages.yml", false);
        }
        File actions = new File(plugin.getDataFolder(), "actions.yml");
        File legacy = new File(plugin.getDataFolder(), "punishments.yml");
        if (!actions.isFile() && !legacy.isFile()) {
            plugin.saveResource("actions.yml", false);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        State current = state;
        state = null;
        if (current != null) {
            current.close();
        }
        Bukkit.getServicesManager().unregister(CleanSpeakApi.class, api);
    }

    public final class State {
        public final PluginConfig config;
        public final TextPolicy textPolicy;
        public final MlSettings mlSettings;
        public final TextClassifier classifier;
        public final ActionService actions;
        public final ModerationService service;
        public final DecisionStore decisions;
        public final AppealStore appeals;

        private State(ConfigLoader.LoadedConfig loaded) {
            this.config = loaded.config();
            this.textPolicy = loaded.textPolicy();
            this.mlSettings = loaded.mlSettings();
            this.classifier = TextClassifierFactory.create(config.text.ml.enabled, mlSettings);
            if (config.text.ml.enabled && !this.classifier.available()) {
                logger.warning("Text model is enabled but unavailable: " + this.classifier.status());
            }
            this.actions = new ActionService(loaded.actions(),
                    command -> Bukkit.getScheduler().runTask(plugin, () -> {
                        if (!Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command)) {
                            logger.warning("Action command was not recognised: " + command);
                        }
                    }),
                    (request, decision, count, matchedActions) ->
                            staffNotifier.notifyAction(request, decision, count));
            this.decisions = new DecisionStore(dataDirectory, config, logger);
            this.appeals = new AppealStore(dataDirectory, logger);
            RuleEngine ruleEngine = new RuleEngine(
                    new dev.cleanspeak.core.TextNormalizer(1000),
                    loaded.rules()
            );
            ConversationTracker tracker = new ConversationTracker(
                    config.text.contextMaxMessages,
                    config.text.contextTtlSeconds * 1000L,
                    config.text.contextMaxKeys
            );
            this.service = new ModerationService(
                    ruleEngine,
                    tracker,
                    classifier,
                    mlSettings,
                    textPolicy,
                    config.queues.apiWorkers,
                    config.queues.apiCapacity,
                    config.queues.requestTimeoutMillis,
                    (request, decision) -> {
                        decisions.record(request, decision);
                        staffNotifier.notifyDecision(request, decision);
                    },
                    staffNotifier::alert,
                    actions::adjust
            );
        }

        private void close() {
            service.close();
            decisions.close();
        }
    }
}
