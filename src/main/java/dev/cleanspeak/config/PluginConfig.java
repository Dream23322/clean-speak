package dev.cleanspeak.config;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Mutable configuration object built once on startup/reload and then published
 * atomically through a volatile reference. Do not mutate it after publication.
 */
public final class PluginConfig {

    public int configVersion = 2;
    public String policyVersion = "dev";
    public Text text = new Text();
    public Links links = new Links();
    public Spam spam = new Spam();
    public Queues queues = new Queues();
    public Privacy privacy = new Privacy();
    public Staff staff = new Staff();
    public Logging logging = new Logging();
    public Integrations integrations = new Integrations();
    public RawCommandInspection rawCommandInspection = new RawCommandInspection();
    public Map<String, Check> checks = new LinkedHashMap<>();
    public Messages messages = new Messages();

    public static final class Text {
        public boolean enabled = true;
        public boolean shadowMode = false;
        public String deliveryMode = "RULES_ONLY";
        public boolean notifyBlocked = true;
        public int noticeCooldownSeconds = 0;
        public int maxMessageLength = 512;
        public int contextMaxMessages = 3;
        public int contextTtlSeconds = 45;
        public int contextMaxKeys = 5000;
        public Map<String, String> categoryActions = new LinkedHashMap<>(Map.of(
                "TARGETED_HARASSMENT", "REPHRASE",
                "HATE_SLUR", "REPHRASE",
                "THREAT", "BLOCK",
                "SPAM", "REPHRASE",
                "SCAM_ADVERTISING", "BLOCK",
                "PROFANITY", "REPHRASE"
        ));
        public Ml ml = new Ml();
    }

    public static final class Ml {
        public boolean enabled = true;
        public String endpoint = "";
        public boolean allowRemoteEndpoint = false;
        public String modelVersion = "bundled-v1";
        public int timeoutMillis = 800;
        public String failureMode = "RULES_ONLY";
        public boolean mayBlock = false;
        public double minConfidence = 0.55D;
        public Map<String, Threshold> thresholds = new LinkedHashMap<>();
    }

    public static final class Threshold {
        public double review = 0.55D;
        public double block = 0.88D;
    }

    public static final class Links {
        public boolean scamEnabled = true;
        public boolean advertisingEnabled = false;
        public String scamAction = "BLOCK";
        public String advertisingAction = "REPHRASE";
        public Set<String> allowlist = new LinkedHashSet<>();
        public Set<String> blocklist = new LinkedHashSet<>();
        public Set<String> shorteners = new LinkedHashSet<>();
        public Set<String> suspiciousTlds = new LinkedHashSet<>();
    }

    public static final class Spam {
        public int maxMessagesPerWindow = 6;
        public int windowSeconds = 10;
        public int duplicateThreshold = 3;
        public double maxCapsRatio = 0.80D;
        public int maxRepeatedCharacterRun = 14;
        public int maxUrls = 4;
        public String action = "REPHRASE";
        public String duplicateAction = "REPHRASE";
        public String capsAction = "REPHRASE";
    }

    public static final class Queues {
        public int apiWorkers = 2;
        public int apiCapacity = 256;
        public long requestTimeoutMillis = 1500L;
        public int conversationMaxPending = 8;
        public int conversationMaxActive = 2000;
    }

    public static final class Privacy {
        public boolean joinNotice = true;
        public String joinNoticeMessage = "";
        public boolean retainRawText = false;
        public int decisionRetentionDays = 14;
    }

    public static final class Staff {
        public boolean alerts = true;
        public int alertCooldownSeconds = 0;
        public int recentLimit = 200;
        public int appealCooldownSeconds = 60;
    }

    public static final class Logging {
        public boolean logAllowed = false;
        public boolean logShadow = true;
        public boolean writeDecisionLog = true;
    }

    public static final class Integrations {
        public boolean essentialsRulesOnly = true;
    }

    public static final class RawCommandInspection {
        public boolean enabled = false;
        public Map<String, Integer> inspect = new LinkedHashMap<>();
    }

    /**
     * Per-category settings, loaded from checks.yml in the style of a
     * check-oriented anticheat configuration.
     */
    public static final class Check {
        public boolean enabled = true;
        public String action = "REPHRASE";
        public String displayName = "";
        public String description = "";
        public double mlReviewThreshold = 0.55D;
        public double mlBlockThreshold = 0.88D;
    }

    public static final class Messages {
        public String playerRephrase = "";
        public String playerBlock = "";
        public String playerReview = "";
    }
}
