package dev.cleanspeak.example.tell;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Small example ignore list persisted in ignores.yml. Real messaging plugins
 * should use their own account/ignore storage.
 */
public final class YamlIgnoreStore implements IgnoreStore {

    private final File file;
    private final YamlConfiguration yaml;
    private final java.util.Map<UUID, Set<UUID>> ignores = new java.util.HashMap<>();

    public YamlIgnoreStore(JavaPlugin plugin) {
        this.file = new File(plugin.getDataFolder(), "ignores.yml");
        this.yaml = YamlConfiguration.loadConfiguration(file);
        for (String key : yaml.getKeys(false)) {
            try {
                UUID owner = UUID.fromString(key);
                Set<UUID> values = new LinkedHashSet<>();
                for (String value : yaml.getStringList(key)) {
                    try {
                        values.add(UUID.fromString(value));
                    } catch (IllegalArgumentException ignored) {
                    }
                }
                ignores.put(owner, values);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    @Override
    public synchronized boolean ignores(UUID ownerId, UUID senderId) {
        return ignores.getOrDefault(ownerId, Set.of()).contains(senderId);
    }

    @Override
    public synchronized void ignore(UUID ownerId, UUID ignoredId) {
        ignores.computeIfAbsent(ownerId, ignored -> new LinkedHashSet<>()).add(ignoredId);
        save();
    }

    @Override
    public synchronized void unignore(UUID ownerId, UUID ignoredId) {
        Set<UUID> values = ignores.get(ownerId);
        if (values != null) {
            values.remove(ignoredId);
            if (values.isEmpty()) {
                ignores.remove(ownerId);
            }
            save();
        }
    }

    @Override
    public synchronized List<UUID> ignored(UUID ownerId) {
        return new ArrayList<>(ignores.getOrDefault(ownerId, Set.of()));
    }

    private void save() {
        for (String key : yaml.getKeys(false)) {
            yaml.set(key, null);
        }
        for (var entry : ignores.entrySet()) {
            List<String> values = entry.getValue().stream().map(UUID::toString).toList();
            yaml.set(entry.getKey().toString(), values);
        }
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            yaml.save(file);
        } catch (IOException ignored) {
        }
    }
}
