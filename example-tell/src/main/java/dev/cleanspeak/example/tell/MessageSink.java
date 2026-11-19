package dev.cleanspeak.example.tell;

import java.util.UUID;

@FunctionalInterface
public interface MessageSink {
    /**
     * Send a plain-text system notice to a player. Implementations must schedule
     * to the main thread when called from an async callback.
     */
    void send(UUID playerId, String message);
}
