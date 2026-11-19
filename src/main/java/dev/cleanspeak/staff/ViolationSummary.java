package dev.cleanspeak.staff;

import dev.cleanspeak.storage.DecisionStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Grim-style per-check violation aggregation over the recent decision ring.
 * This is deliberately derived from decisions, not a second persistent store.
 */
public final class ViolationSummary {

    private ViolationSummary() {
    }

    public record Entry(
            String check,
            String category,
            String source,
            int count,
            long latestEpochMillis,
            String latestRequestId
    ) {
    }

    public record Report(
            String player,
            int scanned,
            int flagged,
            List<Entry> entries
    ) {
    }

    public static String sourceLabel(DecisionStore.DecisionRecord record) {
        return sourceLabel(record.contributions());
    }

    public static String sourceLabel(List<String> contributions) {
        boolean regex = false;
        boolean ai = false;
        for (String contribution : contributions) {
            if (contribution.startsWith("rule:")) {
                regex = true;
            } else if (contribution.startsWith("model:")) {
                ai = true;
            }
        }
        if (ai && regex) {
            return "AI+REGEX";
        }
        if (ai) {
            return "AI";
        }
        if (regex) {
            return "REGEX";
        }
        return "SYSTEM";
    }

    public static Report summarize(List<DecisionStore.DecisionRecord> records,
                                   String player,
                                   int maxEntries) {
        String wanted = player == null ? null : player.trim().toLowerCase(Locale.ROOT);
        List<DecisionStore.DecisionRecord> filtered = new ArrayList<>();
        for (DecisionStore.DecisionRecord record : records) {
            if (wanted == null
                    || record.senderName().toLowerCase(Locale.ROOT).equals(wanted)
                    || record.senderId().equalsIgnoreCase(wanted)) {
                filtered.add(record);
            }
        }

        Map<String, MutableEntry> grouped = new LinkedHashMap<>();
        int flagged = 0;
        for (DecisionStore.DecisionRecord record : filtered) {
            if (record.rawAction().equals("ALLOW")) {
                continue;
            }
            flagged++;
            Set<String> seen = new LinkedHashSet<>();
            for (String contribution : record.contributions()) {
                String[] parts = contribution.split("\\|", -1);
                if (parts.length < 2) {
                    continue;
                }
                String source = parts[0];
                String category = parts[1];
                String label;
                String check;
                if (source.startsWith("rule:")) {
                    label = "REGEX";
                    check = source.substring("rule:".length());
                } else if (source.startsWith("model:")) {
                    label = "AI";
                    check = source.substring("model:".length());
                } else {
                    continue;
                }
                String key = label + "|" + category + "|" + check;
                if (!seen.add(key)) {
                    continue;
                }
                grouped.computeIfAbsent(key, ignored -> new MutableEntry(
                        check, category, label, record.timestamp(), record.requestId()
                )).count++;
            }
            if (seen.isEmpty()) {
                String key = "SYSTEM|" + String.join(",", record.categories()) + "|decision";
                grouped.computeIfAbsent(key, ignored -> new MutableEntry(
                        "decision", String.join(",", record.categories()), "SYSTEM",
                        record.timestamp(), record.requestId()
                )).count++;
            }
        }

        List<Entry> entries = grouped.values().stream()
                .map(MutableEntry::entry)
                .sorted(Comparator.comparingInt(Entry::count).reversed()
                        .thenComparing(Comparator.comparingLong(Entry::latestEpochMillis).reversed()))
                .limit(Math.max(1, maxEntries))
                .toList();
        return new Report(player == null ? "" : player, filtered.size(), flagged, entries);
    }

    private static final class MutableEntry {
        private final String check;
        private final String category;
        private final String source;
        private final long latest;
        private final String latestRequestId;
        private int count;

        private MutableEntry(String check, String category, String source,
                             long latest, String latestRequestId) {
            this.check = check;
            this.category = category;
            this.source = source;
            this.latest = latest;
            this.latestRequestId = latestRequestId;
        }

        private Entry entry() {
            return new Entry(check, category, source, count, latest, latestRequestId);
        }
    }
}
