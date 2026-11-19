package dev.cleanspeak.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.cleanspeak.api.Category;
import dev.cleanspeak.api.Contribution;
import dev.cleanspeak.api.ModerationDecision;
import dev.cleanspeak.api.ModerationRequest;
import dev.cleanspeak.config.PluginConfig;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * In-memory recent decisions plus an optional append-only JSONL log. Raw text is
 * not stored unless privacy.retain-raw-text is explicitly enabled.
 */
public final class DecisionStore implements AutoCloseable {

    private final Logger logger;
    private final PluginConfig config;
    private final int memoryLimit;
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    private final Deque<DecisionRecord> recent = new ArrayDeque<>();
    private final AsyncFileWriter writer;
    private final Path file;

    public DecisionStore(Path dataDirectory, PluginConfig config, Logger logger) {
        this.logger = logger;
        this.config = config;
        this.memoryLimit = Math.max(50, config.staff.recentLimit);
        this.file = dataDirectory.resolve("decisions.jsonl");
        pruneFile(config.privacy.decisionRetentionDays);
        this.writer = config.logging.writeDecisionLog
                ? new AsyncFileWriter(file, 2000, logger)
                : null;
        loadRecent();
    }

    /**
     * Decision-log retention is applied at startup so an active writer is not
     * competing with a rewrite. Evidence retention runs periodically.
     */
    private void pruneFile(int retentionDays) {
        if (!file.toFile().isFile() || retentionDays <= 0) {
            return;
        }
        long cutoff = System.currentTimeMillis() - retentionDays * 86_400_000L;
        try {
            java.util.List<String> keep = new java.util.ArrayList<>();
            try (var lines = Files.lines(file, StandardCharsets.UTF_8)) {
                lines.forEach(line -> {
                    try {
                        DecisionRecord record = gson.fromJson(line, DecisionRecord.class);
                        if (record != null && record.timestamp() >= cutoff) {
                            keep.add(line);
                        }
                    } catch (RuntimeException ignored) {
                    }
                });
            }
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(temp, keep, StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Could not apply decision-log retention", e);
        }
    }

    public void record(ModerationRequest request, ModerationDecision decision) {
        if (decision.observation() && !config.logging.logShadow) {
            return;
        }
        if (decision.action() == dev.cleanspeak.api.ModerationAction.ALLOW
                && decision.rawAction() == dev.cleanspeak.api.ModerationAction.ALLOW
                && !config.logging.logAllowed) {
            return;
        }

        DecisionRecord record = new DecisionRecord(
                request.requestId().toString(),
                decision.createdAtEpochMillis(),
                request.senderId().toString(),
                request.senderName(),
                request.audience().name(),
                request.channelId(),
                request.conversationId(),
                decision.action().name(),
                decision.rawAction().name(),
                decision.shadowed(),
                decision.observation(),
                decision.categories().stream().map(Category::name).toList(),
                decision.contributions().stream().map(DecisionStore::contribution).toList(),
                decision.staffReason(),
                decision.playerReason(),
                decision.modelVersion(),
                decision.policyVersion(),
                decision.fallback(),
                hash(request.message()),
                request.message().length(),
                config.privacy.retainRawText ? truncate(request.message(), 500) : null
        );

        synchronized (recent) {
            recent.addFirst(record);
            while (recent.size() > memoryLimit) {
                recent.removeLast();
            }
        }
        if (writer != null) {
            writer.write(gson.toJson(record));
        }
    }

    public List<DecisionRecord> recent(int limit) {
        int capped = Math.max(1, Math.min(limit, memoryLimit));
        synchronized (recent) {
            List<DecisionRecord> out = new ArrayList<>(capped);
            for (DecisionRecord record : recent) {
                out.add(record);
                if (out.size() >= capped) {
                    break;
                }
            }
            return out;
        }
    }

    public Optional<DecisionRecord> find(String requestId) {
        if (requestId == null) {
            return Optional.empty();
        }
        synchronized (recent) {
            for (DecisionRecord record : recent) {
                if (record.requestId().equalsIgnoreCase(requestId)) {
                    return Optional.of(record);
                }
            }
        }
        if (file.toFile().isFile()) {
            try (var lines = Files.lines(file, StandardCharsets.UTF_8)) {
                return lines.map(line -> {
                            try {
                                return gson.fromJson(line, DecisionRecord.class);
                            } catch (RuntimeException e) {
                                return null;
                            }
                        })
                        .filter(record -> record != null && record.requestId().equalsIgnoreCase(requestId))
                        .findFirst();
            } catch (Exception e) {
                logger.log(Level.FINE, "Could not search decision log", e);
            }
        }
        return Optional.empty();
    }

    public String gson() {
        return gson.toJson(recent(1));
    }

    public Gson gsonInstance() {
        return gson;
    }

    public long dropped() {
        return writer == null ? 0L : writer.droppedCount();
    }

    private void loadRecent() {
        if (!file.toFile().isFile()) {
            return;
        }
        try (var lines = Files.lines(file, StandardCharsets.UTF_8)) {
            List<DecisionRecord> loaded = new ArrayList<>();
            lines.forEach(line -> {
                try {
                    DecisionRecord record = gson.fromJson(line, DecisionRecord.class);
                    if (record != null) {
                        loaded.add(record);
                    }
                } catch (RuntimeException ignored) {
                }
            });
            synchronized (recent) {
                recent.clear();
                for (int i = loaded.size() - 1; i >= 0 && recent.size() < memoryLimit; i--) {
                    recent.addLast(loaded.get(i));
                }
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Could not read " + file, e);
        }
    }

    private static String contribution(Contribution contribution) {
        return contribution.source() + "|" + contribution.category().name() + "|"
                + String.format(Locale.ROOT, "%.3f", contribution.score()) + "|" + contribution.detail();
    }

    private static String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            return "unavailable";
        }
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }

    @Override
    public void close() {
        if (writer != null) {
            writer.close();
        }
    }

    public record DecisionRecord(
            String requestId,
            long timestamp,
            String senderId,
            String senderName,
            String audience,
            String channelId,
            String conversationId,
            String action,
            String rawAction,
            boolean shadowed,
            boolean observation,
            List<String> categories,
            List<String> contributions,
            String staffReason,
            String playerReason,
            String modelVersion,
            String policyVersion,
            boolean fallback,
            String messageHash,
            int messageLength,
            String messagePreview
    ) {
        public DecisionRecord {
            categories = categories == null ? List.of() : List.copyOf(categories);
            contributions = contributions == null ? List.of() : List.copyOf(contributions);
        }

        public String shortSummary() {
            String cats = categories.isEmpty() ? "none" : String.join(",", categories);
            return "#" + requestId + " " + rawAction + (shadowed ? "/shadow" : "")
                    + " " + senderName + " " + channelId + " " + cats;
        }
    }
}
