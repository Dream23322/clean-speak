package dev.cleanspeak.storage;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Cooldown limiter for player notices and staff alerts. It is intentionally
 * small and bounded; it does not park threads.
 */
public final class RateLimiter {

    private final ConcurrentHashMap<String, Long> last = new ConcurrentHashMap<>();
    private final AtomicLong denied = new AtomicLong();
    private final int maxKeys;

    public RateLimiter(int maxKeys) {
        this.maxKeys = Math.max(64, maxKeys);
    }

    public boolean allow(String key, long cooldownMillis) {
        if (cooldownMillis <= 0) {
            return true;
        }
        long now = System.currentTimeMillis();
        if (last.size() > maxKeys) {
            last.entrySet().removeIf(entry -> now - entry.getValue() > Math.max(cooldownMillis, 60_000L));
            if (last.size() > maxKeys) {
                last.clear();
            }
        }
        final boolean[] allowed = {false};
        last.compute(key, (k, previous) -> {
            if (previous == null || now - previous >= cooldownMillis) {
                allowed[0] = true;
                return now;
            }
            return previous;
        });
        if (!allowed[0]) {
            denied.incrementAndGet();
        }
        return allowed[0];
    }

    public long deniedCount() {
        return denied.get();
    }
}
