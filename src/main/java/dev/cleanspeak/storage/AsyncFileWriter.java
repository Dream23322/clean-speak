package dev.cleanspeak.storage;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Bounded asynchronous line writer. A full queue drops lines and increments a
 * counter rather than blocking chat or voice threads.
 */
public final class AsyncFileWriter implements AutoCloseable {

    private final Path path;
    private final Logger logger;
    private final BlockingQueue<String> queue;
    private final Thread thread;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicBoolean warned = new AtomicBoolean();

    public AsyncFileWriter(Path path, int capacity, Logger logger) {
        this.path = path;
        this.logger = logger;
        this.queue = new ArrayBlockingQueue<>(Math.max(16, capacity));
        try {
            Files.createDirectories(path.getParent());
        } catch (IOException e) {
            logger.log(Level.WARNING, "Could not create log directory " + path.getParent(), e);
        }
        this.thread = new Thread(this::run, "CleanSpeak-log-writer");
        this.thread.setDaemon(true);
        this.thread.start();
    }

    public void write(String line) {
        if (!running.get() || line == null) {
            return;
        }
        if (!queue.offer(line)) {
            dropped.incrementAndGet();
            if (warned.compareAndSet(false, true)) {
                logger.warning("CleanSpeak log queue is full; some records are being dropped.");
            }
        }
    }

    public long droppedCount() {
        return dropped.get();
    }

    private void run() {
        boolean append = true;
        try {
            while (running.get() || !queue.isEmpty()) {
                String line = queue.poll(500, TimeUnit.MILLISECONDS);
                if (line == null) {
                    continue;
                }
                try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, append ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING)) {
                    writer.write(line);
                    writer.newLine();
                    writer.flush();
                }
                append = true;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            logger.log(Level.WARNING, "Could not write " + path, e);
        }
    }

    @Override
    public void close() {
        running.set(false);
        try {
            thread.join(2000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
