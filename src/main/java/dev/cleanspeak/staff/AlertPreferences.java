package dev.cleanspeak.staff;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Per-staff alert preference. Stored outside the main configuration so a
 * personal toggle never rewrites config.yml.
 */
public final class AlertPreferences {

    private final Path file;
    private final Logger logger;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Set<UUID> disabled = ConcurrentHashMap.newKeySet();

    public AlertPreferences(Path dataDirectory, Logger logger) {
        this.file = dataDirectory.resolve("alerts.json");
        this.logger = logger;
        load();
    }

    public boolean enabled(UUID playerId) {
        return playerId == null || !disabled.contains(playerId);
    }

    public boolean toggle(UUID playerId) {
        if (playerId == null) {
            return true;
        }
        boolean nowEnabled;
        if (disabled.remove(playerId)) {
            nowEnabled = true;
        } else {
            disabled.add(playerId);
            nowEnabled = false;
        }
        save();
        return nowEnabled;
    }

    public void set(UUID playerId, boolean enabled) {
        if (playerId == null) {
            return;
        }
        if (enabled) {
            disabled.remove(playerId);
        } else {
            disabled.add(playerId);
        }
        save();
    }

    private void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            String[] values = gson.fromJson(json, String[].class);
            if (values != null) {
                for (String value : values) {
                    try {
                        disabled.add(UUID.fromString(value));
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Could not read " + file.getFileName() + "; alerts default to enabled.", e);
        }
    }

    private synchronized void save() {
        List<String> values = new ArrayList<>();
        for (UUID id : disabled) {
            values.add(id.toString());
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, gson.toJson(values), StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.log(Level.WARNING, "Could not save " + file.getFileName() + ".", e);
        }
    }
}
