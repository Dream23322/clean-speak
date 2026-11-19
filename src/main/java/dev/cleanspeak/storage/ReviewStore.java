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
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Staff corrections for decisions. This is deliberately separate from the
 * append-only decision log so a decision remains auditable after review.
 */
public final class ReviewStore {

    public record Review(
            String requestId,
            String markedBy,
            long markedAtEpochMillis,
            String note,
            boolean voiceMuteRemoved
    ) {
    }

    private final Path file;
    private final Logger logger;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final Map<String, Review> reviews = new LinkedHashMap<>();

    public ReviewStore(Path dataDirectory, Logger logger) {
        this.file = dataDirectory.resolve("reviews.json");
        this.logger = logger;
        load();
    }

    public synchronized Review mark(String requestId, String markedBy, String note, boolean voiceMuteRemoved) {
        Review review = new Review(requestId, markedBy == null ? "" : markedBy,
                System.currentTimeMillis(), note == null ? "" : note, voiceMuteRemoved);
        reviews.put(requestId, review);
        save();
        return review;
    }

    public synchronized Optional<Review> find(String requestId) {
        return Optional.ofNullable(reviews.get(requestId));
    }

    public synchronized List<Review> all() {
        List<Review> out = new ArrayList<>(reviews.values());
        out.sort(Comparator.comparingLong(Review::markedAtEpochMillis));
        return out;
    }

    private void load() {
        if (!file.toFile().isFile()) {
            return;
        }
        try {
            Review[] loaded = gson.fromJson(Files.readString(file, StandardCharsets.UTF_8), Review[].class);
            if (loaded != null) {
                for (Review review : loaded) {
                    if (review != null && review.requestId() != null) {
                        reviews.put(review.requestId(), review);
                    }
                }
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Could not load review records", e);
        }
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, gson.toJson(reviews.values()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.log(Level.WARNING, "Could not save review records", e);
        }
    }
}
