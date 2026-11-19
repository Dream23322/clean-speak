package dev.cleanspeak.api;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * A small, reusable ordering helper for messaging plugins.
 *
 * <p>It serializes moderation futures per conversation. This preserves the order
 * in which approved messages can be scheduled for delivery. It deliberately
 * does not deliver messages and does not decide whether a message is allowed.</p>
 *
 * <p>Futures are executed on the thread that completes the previous future or
 * the caller's thread for the first task. A supplier must return promptly; it
 * should start asynchronous work, not block.</p>
 *
 * <p>Most integrations should use {@link ModerationSession}, which combines this
 * queue with request creation and typed timeouts. Use this class directly only
 * when building a custom pipeline.</p>
 *
 * <h2>Failure contract</h2>
 *
 * <p>This class <strong>never throws</strong> for rejected work; it returns a
 * future that completes exceptionally with a {@link RejectedExecutionException}.
 * Null arguments are programmer errors and still raise
 * {@link NullPointerException} immediately. Use
 * {@link ModerationException#from(Throwable)} to map failures.</p>
 */
public final class OrderedConversationQueue {

    private final int maxPendingPerConversation;
    private final int maxConversations;
    private final Map<String, Chain> chains = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Creates a queue.
     *
     * @param maxPendingPerConversation maximum queued or running messages per
     *                                 conversation; must be positive
     * @param maxConversations maximum simultaneously tracked conversations;
     *                        must be positive
     * @throws IllegalArgumentException if either limit is less than one
     */
    public OrderedConversationQueue(int maxPendingPerConversation, int maxConversations) {
        if (maxPendingPerConversation < 1 || maxConversations < 1) {
            throw new IllegalArgumentException("limits must be positive");
        }
        this.maxPendingPerConversation = maxPendingPerConversation;
        this.maxConversations = maxConversations;
    }

    /**
     * Submits work for one conversation.
     *
     * @param conversationId isolation id; requests that share it are serialized
     * @param task supplier that starts the asynchronous moderation work
     * @return a future for this task; completes exceptionally with
     *         {@link RejectedExecutionException} when the queue is closed or full
     * @throws NullPointerException if an argument is {@code null}
     */
    public CompletableFuture<ModerationDecision> submit(
            String conversationId,
            Supplier<CompletableFuture<ModerationDecision>> task
    ) {
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(task, "task");
        if (conversationId.isBlank()) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("conversationId must not be blank"));
        }
        if (closed.get()) {
            return CompletableFuture.failedFuture(new RejectedExecutionException("queue is closed"));
        }

        final Chain chain;
        final Task queued = new Task(task);
        try {
            chain = chains.compute(conversationId, (key, existing) -> {
                Chain selected = existing;
                if (selected == null) {
                    if (chains.size() >= maxConversations) {
                        throw new RejectedExecutionException("too many active conversations");
                    }
                    selected = new Chain(conversationId);
                }
                if (!selected.enqueue(queued, maxPendingPerConversation)) {
                    throw new RejectedExecutionException("too many pending messages in conversation");
                }
                return selected;
            });
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }

        chain.start();
        return queued.result;
    }

    /**
     * Submits a request through {@link CleanSpeakApi#moderate(ModerationRequest)}.
     *
     * <p>The conversation id is taken from the request. Apply your own deadline
     * or use {@link ModerationSession}.</p>
     *
     * @param api API implementation
     * @param request request to moderate
     * @return a future for the decision
     */
    public CompletableFuture<ModerationDecision> submit(CleanSpeakApi api, ModerationRequest request) {
        Objects.requireNonNull(api, "api");
        Objects.requireNonNull(request, "request");
        return submit(request.conversationId(), () -> api.moderate(request));
    }

    /**
     * Submits a request with a custom worker.
     *
     * @param request request whose conversation id controls ordering
     * @param task supplier that starts the asynchronous moderation work
     * @return a future for the decision
     */
    public CompletableFuture<ModerationDecision> submit(
            ModerationRequest request,
            Supplier<CompletableFuture<ModerationDecision>> task
    ) {
        Objects.requireNonNull(request, "request");
        return submit(request.conversationId(), task);
    }

    /**
     * Returns the number of queued or running messages for one conversation.
     *
     * @param conversationId conversation id
     * @return pending message count, or zero when the conversation is untracked
     */
    public int pending(String conversationId) {
        Chain chain = chains.get(conversationId);
        return chain == null ? 0 : chain.pending;
    }

    /**
     * Returns the number of conversations currently tracked.
     *
     * @return active conversation count
     */
    public int activeConversations() {
        return chains.size();
    }

    /**
     * Returns whether the queue has been closed.
     *
     * @return {@code true} after {@link #close()}
     */
    public boolean isClosed() {
        return closed.get();
    }

    /**
     * Rejects new work. Already-running and queued work is left untouched;
     * callers should treat its results as not delivered when shutting down.
     */
    public void close() {
        closed.set(true);
    }

    private final class Chain {
        private final String key;
        private final Deque<Task> queue = new ArrayDeque<>();
        private boolean running;
        private int pending;

        private Chain(String key) {
            this.key = key;
        }

        private synchronized boolean enqueue(Task task, int limit) {
            if (pending >= limit) {
                return false;
            }
            queue.addLast(task);
            pending++;
            return true;
        }

        /**
         * Starts exactly one task. The completion callback starts the next one,
         * so a slow first message cannot be bypassed by a later submit.
         */
        private void start() {
            Task next;
            synchronized (this) {
                if (running) {
                    return;
                }
                next = queue.pollFirst();
                if (next == null) {
                    return;
                }
                running = true;
            }

            CompletableFuture<ModerationDecision> future;
            try {
                future = next.supplier.get();
                if (future == null) {
                    future = CompletableFuture.failedFuture(
                            new IllegalStateException("task supplier returned null"));
                }
            } catch (Throwable t) {
                future = CompletableFuture.failedFuture(t);
            }

            future.whenComplete((decision, error) -> {
                try {
                    if (error == null) {
                        next.result.complete(decision);
                    } else {
                        next.result.completeExceptionally(error);
                    }
                } finally {
                    boolean idle;
                    synchronized (this) {
                        pending--;
                        running = false;
                        idle = pending == 0 && queue.isEmpty();
                    }
                    if (idle) {
                        chains.compute(key, (k, current) -> current == this && current.isIdle() ? null : current);
                    } else {
                        start();
                    }
                }
            });
        }

        private synchronized boolean isIdle() {
            return pending == 0 && queue.isEmpty() && !running;
        }
    }

    private static final class Task {
        private final Supplier<CompletableFuture<ModerationDecision>> supplier;
        private final CompletableFuture<ModerationDecision> result = new CompletableFuture<>();

        private Task(Supplier<CompletableFuture<ModerationDecision>> supplier) {
            this.supplier = supplier;
        }
    }
}
