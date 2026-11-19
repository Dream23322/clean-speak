package dev.cleanspeak.api;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A convenience integration layer for messaging plugins.
 *
 * <p>A session combines the pieces every caller would otherwise wire manually:</p>
 *
 * <ul>
 *   <li>one moderation call per conversation at a time, so a slow request cannot
 *       be overtaken by a later message in the same conversation;</li>
 *   <li>a caller-owned delivery deadline;</li>
 *   <li>typed {@link ModerationException} failures instead of raw timeout and
 *       rejection exceptions.</li>
 * </ul>
 *
 * <p>A session does <strong>not</strong> deliver messages, resolve recipients,
 * check permissions or ignore lists, retry, or decide what text to show the
 * player. The caller still owns all of that and must deliver exactly once on
 * {@link ModerationAction#ALLOW}.</p>
 *
 * <p>Sessions are thread-safe. Close the session when your plugin disables.</p>
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * ModerationSession session = ModerationSession.builder(api)
 *         .maxPendingPerConversation(8)
 *         .maxConversations(2000)
 *         .timeout(Duration.ofSeconds(5))
 *         .build();
 *
 * ModerationRequest request = ModerationRequests.privateMessage(
 *         senderId, senderName, recipientId, "myplugin:pm", message);
 *
 * session.submit(request).whenComplete((decision, error) -> {
 *     if (error != null) {
 *         ModerationException failure = ModerationException.from(error);
 *         // Not delivered; show a generic timeout/unavailable message.
 *         return;
 *     }
 *     if (decision.action() != ModerationAction.ALLOW) {
 *         // Not delivered; decision.playerReason() is player-safe text.
 *         return;
 *     }
 *     // Hop to the main thread, re-check online state, then deliver once.
 * });
 * }</pre>
 */
public final class ModerationSession implements AutoCloseable {

    /** Default pending-message limit per conversation. */
    public static final int DEFAULT_MAX_PENDING_PER_CONVERSATION = 8;

    /** Default active-conversation limit. */
    public static final int DEFAULT_MAX_CONVERSATIONS = 2000;

    /** Default caller-owned delivery deadline. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    private final CleanSpeakApi api;
    private final OrderedConversationQueue queue;
    private final long timeoutMillis;
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Creates a session with default limits and timeout.
     *
     * @param api non-null API implementation
     */
    public ModerationSession(CleanSpeakApi api) {
        this(api, DEFAULT_MAX_PENDING_PER_CONVERSATION, DEFAULT_MAX_CONVERSATIONS, DEFAULT_TIMEOUT);
    }

    /**
     * Creates a session.
     *
     * @param api non-null API implementation
     * @param maxPendingPerConversation maximum queued messages per conversation
     * @param maxConversations maximum simultaneously tracked conversations
     * @param timeout caller-owned delivery deadline; must be positive
     */
    public ModerationSession(
            CleanSpeakApi api,
            int maxPendingPerConversation,
            int maxConversations,
            Duration timeout
    ) {
        this.api = Objects.requireNonNull(api, "api");
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        this.queue = new OrderedConversationQueue(maxPendingPerConversation, maxConversations);
        this.timeoutMillis = Math.max(1L, timeout.toMillis());
    }

    /**
     * Starts building a session.
     *
     * @param api non-null API implementation
     * @return a new builder
     */
    public static Builder builder(CleanSpeakApi api) {
        return new Builder(api);
    }

    /**
     * Submits a request for moderation.
     *
     * <p>The returned future completes with a decision, or exceptionally with a
     * {@link ModerationException}. Failures may be wrapped in a
     * {@link java.util.concurrent.CompletionException}; use
     * {@link ModerationException#from(Throwable)}.</p>
     *
     * @param request non-null request
     * @return a future representing the decision
     */
    public CompletableFuture<ModerationDecision> submit(ModerationRequest request) {
        if (request == null) {
            return CompletableFuture.failedFuture(ModerationException.invalid("request is null"));
        }
        if (closed.get()) {
            return CompletableFuture.failedFuture(ModerationException.shutdown());
        }
        return queue.submit(request.conversationId(),
                        () -> api.moderate(request).orTimeout(timeoutMillis, TimeUnit.MILLISECONDS))
                .handle((decision, error) -> {
                    if (error != null) {
                        throw ModerationException.from(error);
                    }
                    return decision;
                });
    }

    /**
     * Returns the configured caller-owned timeout in milliseconds.
     *
     * @return timeout in milliseconds
     */
    public long timeoutMillis() {
        return timeoutMillis;
    }

    /**
     * Returns the number of queued messages for one conversation.
     *
     * @param conversationId conversation id
     * @return pending or running message count
     */
    public int pending(String conversationId) {
        return queue.pending(conversationId);
    }

    /**
     * Returns the number of conversations currently tracked by this session.
     *
     * @return active conversation count
     */
    public int activeConversations() {
        return queue.activeConversations();
    }

    /**
     * Returns whether this session has been closed.
     *
     * @return {@code true} after {@link #close()}
     */
    public boolean isClosed() {
        return closed.get();
    }

    /**
     * Rejects new submissions and releases the session's bookkeeping.
     *
     * <p>Already-running requests are allowed to finish. Their results must
     * still be treated as not delivered if the plugin is disabling.</p>
     */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            queue.close();
        }
    }

    /**
     * Builder for {@link ModerationSession}.
     */
    public static final class Builder {
        private final CleanSpeakApi api;
        private int maxPendingPerConversation = DEFAULT_MAX_PENDING_PER_CONVERSATION;
        private int maxConversations = DEFAULT_MAX_CONVERSATIONS;
        private Duration timeout = DEFAULT_TIMEOUT;

        private Builder(CleanSpeakApi api) {
            this.api = Objects.requireNonNull(api, "api");
        }

        /**
         * Sets the pending-message limit per conversation.
         *
         * @param maxPendingPerConversation positive limit
         * @return this builder
         */
        public Builder maxPendingPerConversation(int maxPendingPerConversation) {
            if (maxPendingPerConversation < 1) {
                throw new IllegalArgumentException("maxPendingPerConversation must be positive");
            }
            this.maxPendingPerConversation = maxPendingPerConversation;
            return this;
        }

        /**
         * Sets the maximum number of tracked conversations.
         *
         * @param maxConversations positive limit
         * @return this builder
         */
        public Builder maxConversations(int maxConversations) {
            if (maxConversations < 1) {
                throw new IllegalArgumentException("maxConversations must be positive");
            }
            this.maxConversations = maxConversations;
            return this;
        }

        /**
         * Sets the caller-owned delivery deadline.
         *
         * @param timeout positive duration
         * @return this builder
         */
        public Builder timeout(Duration timeout) {
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isZero() || timeout.isNegative()) {
                throw new IllegalArgumentException("timeout must be positive");
            }
            this.timeout = timeout;
            return this;
        }

        /**
         * Builds the session.
         *
         * @return a new session
         */
        public ModerationSession build() {
            return new ModerationSession(api, maxPendingPerConversation, maxConversations, timeout);
        }
    }
}
