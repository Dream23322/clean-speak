package dev.cleanspeak.example.tell;

import java.util.List;
import java.util.UUID;

public interface IgnoreStore {
    boolean ignores(UUID ownerId, UUID senderId);

    void ignore(UUID ownerId, UUID ignoredId);

    void unignore(UUID ownerId, UUID ignoredId);

    List<UUID> ignored(UUID ownerId);
}
