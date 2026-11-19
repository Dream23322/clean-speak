package dev.cleanspeak.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Simple appeal/review workflow. Both text and voice decisions can be appealed;
 * staff can mark an appeal resolved and remove a linked voice mute separately.
 */
public final class AppealStore {

    public record Appeal(
            String id,
            String requestId,
            String playerId,
            String playerName,
            String reason,
            long createdAtEpochMillis,
            String status,
            String resolvedBy,
            String staffNote,
            long resolvedAtEpochMillis
    ) {
        public boolean open() {
            return "OPEN".equalsIgnoreCase(status);
        }
    }

    private final Path file;
    private final Logger logger;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final Map<String, Appeal> appeals = new LinkedHashMap<>();

    public AppealStore(Path dataDirectory, Logger logger) {
        this.file = dataDirectory.resolve("appeals.json");
        this.logger = logger;
        load();
    }

    public synchronized Appeal create(String requestId, String playerId, String playerName, String reason) {
        for (Appeal appeal : appeals.values()) {
            if (appeal.open() && appeal.requestId().equals(requestId) && appeal.playerId().equals(playerId)) {
                return appeal;
            }
        }
        Appeal appeal = new Appeal(
                UUID.randomUUID().toString(),
                requestId,
                playerId,
                playerName,
                reason == null ? "" : reason,
                System.currentTimeMillis(),
                "OPEN",
                "",
                "",
                0L
        );
        appeals.put(appeal.id(), appeal);
        save();
        return appeal;
    }

    public synchronized List<Appeal> open() {
        List<Appeal> out = new ArrayList<>();
        for (Appeal appeal : appeals.values()) {
            if (appeal.open()) {
                out.add(appeal);
            }
        }
        out.sort(Comparator.comparingLong(Appeal::createdAtEpochMillis));
        return out;
    }

    public synchronized List<Appeal> all() {
        List<Appeal> out = new ArrayList<>(appeals.values());
        out.sort(Comparator.comparingLong(Appeal::createdAtEpochMillis));
        return out;
    }

    public synchronized Optional<Appeal> find(String id) {
        return Optional.ofNullable(appeals.get(id));
    }

    public synchronized Optional<Appeal> findOpenForPlayer(String requestId, String playerId) {
        return appeals.values().stream()
                .filter(Appeal::open)
                .filter(a -> a.requestId().equals(requestId) && a.playerId().equals(playerId))
                .findFirst();
    }

    public synchronized boolean resolve(String id, String staffName, String note) {
        Appeal appeal = appeals.get(id);
        if (appeal == null || !appeal.open()) {
            return false;
        }
        appeals.put(id, new Appeal(
                appeal.id(),
                appeal.requestId(),
                appeal.playerId(),
                appeal.playerName(),
                appeal.reason(),
                appeal.createdAtEpochMillis(),
                "RESOLVED",
                staffName == null ? "" : staffName,
                note == null ? "" : note,
                System.currentTimeMillis()
        ));
        save();
        return true;
    }

    private void load() {
        if (!file.toFile().isFile()) {
            return;
        }
        try {
            Appeal[] loaded = gson.fromJson(Files.readString(file, StandardCharsets.UTF_8), Appeal[].class);
            if (loaded != null) {
                for (Appeal appeal : loaded) {
                    if (appeal != null && appeal.id() != null) {
                        appeals.put(appeal.id(), appeal);
                    }
                }
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Could not load appeals", e);
        }
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, gson.toJson(appeals.values()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.log(Level.WARNING, "Could not save appeals", e);
        }
    }
}
