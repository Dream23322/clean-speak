package dev.cleanspeak.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Short-lived, in-memory conversation context. Nothing here is persisted.
 * Keys include channel, conversation and sender so unrelated channels,
 * conversations or players are never combined.
 */
public final class ConversationTracker {

    private final int maxMessages;
    private final long ttlMillis;
    private final int maxKeys;
    private final Map<Key, Deque<RecentMessage>> messages = new ConcurrentHashMap<>();

    public ConversationTracker(int maxMessages, long ttlMillis, int maxKeys) {
        if (maxMessages < 1 || ttlMillis < 1 || maxKeys < 1) {
            throw new IllegalArgumentException("limits must be positive");
        }
        this.maxMessages = maxMessages;
        this.ttlMillis = ttlMillis;
        this.maxKeys = maxKeys;
    }

    public List<RecentMessage> recent(dev.cleanspeak.api.ModerationRequest request, long now) {
        Key key = Key.of(request);
        Deque<RecentMessage> deque = messages.get(key);
        if (deque == null) {
            return List.of();
        }
        synchronized (deque) {
            prune(deque, now);
            return List.copyOf(deque);
        }
    }

    public void record(dev.cleanspeak.api.ModerationRequest request, String analysisText, long now) {
        Key key = Key.of(request);
        if (messages.size() > maxKeys) {
            pruneAll(now);
        }
        Deque<RecentMessage> deque = messages.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        synchronized (deque) {
            prune(deque, now);
            deque.addLast(new RecentMessage(request.senderId(), analysisText, now));
            while (deque.size() > maxMessages) {
                deque.removeFirst();
            }
        }
    }

    public void remove(UUID senderId) {
        messages.keySet().removeIf(key -> key.senderId().equals(senderId));
    }

    public void clear() {
        messages.clear();
    }

    public int trackedKeys() {
        return messages.size();
    }

    private void pruneAll(long now) {
        List<Key> empty = new ArrayList<>();
        for (Map.Entry<Key, Deque<RecentMessage>> entry : messages.entrySet()) {
            Deque<RecentMessage> deque = entry.getValue();
            synchronized (deque) {
                prune(deque, now);
                if (deque.isEmpty()) {
                    empty.add(entry.getKey());
                }
            }
        }
        for (Key key : empty) {
            messages.remove(key);
        }
        // If all entries are still active, enforce a hard bound by dropping the
        // oldest conversation entries. This is a safety valve, not a policy.
        while (messages.size() > maxKeys) {
            Key oldest = null;
            long oldestTime = Long.MAX_VALUE;
            for (Map.Entry<Key, Deque<RecentMessage>> entry : messages.entrySet()) {
                Deque<RecentMessage> deque = entry.getValue();
                synchronized (deque) {
                    RecentMessage first = deque.peekFirst();
                    if (first != null && first.timestampMillis() < oldestTime) {
                        oldestTime = first.timestampMillis();
                        oldest = entry.getKey();
                    }
                }
            }
            if (oldest == null) {
                break;
            }
            messages.remove(oldest);
        }
    }

    private void prune(Deque<RecentMessage> deque, long now) {
        while (!deque.isEmpty() && now - deque.peekFirst().timestampMillis() > ttlMillis) {
            deque.removeFirst();
        }
    }

    public record RecentMessage(UUID senderId, String analysisText, long timestampMillis) {
    }

    private record Key(String channelId, String conversationId, UUID senderId) {
        private static Key of(dev.cleanspeak.api.ModerationRequest request) {
            return new Key(request.channelId(), request.conversationId(), request.senderId());
        }
    }
}
