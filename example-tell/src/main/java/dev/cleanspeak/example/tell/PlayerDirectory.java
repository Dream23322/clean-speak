package dev.cleanspeak.example.tell;

import java.util.Optional;
import java.util.UUID;

public interface PlayerDirectory {
    Optional<TellPlayer> findOnline(String name);

    Optional<TellPlayer> findOnline(UUID id);

    boolean hasPermission(UUID playerId, String permission);
}
