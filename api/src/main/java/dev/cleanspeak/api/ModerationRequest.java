package dev.cleanspeak.api;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A pre-delivery moderation request.
 *
 * <p>A request describes one message attempt. The original message is used only
 * for analysis and is never modified by CleanSpeak. Callers must send the
 * original text to players after an {@link ModerationAction#ALLOW} decision; the
 * decision object does not contain a rewritten message.</p>
 *
 * <p>Conversation isolation is controlled by {@link #channelId()} and
 * {@link #conversationId()}. Callers must not reuse a conversation id for an
 * unrelated audience or channel. A private message between Alice and Bob should
 * use a stable id derived from both UUIDs; public chat should use a single
 * public channel id. {@link ModerationRequests} builds correctly isolated
 * requests for the common cases.</p>
 *
 * <p>All player-provided text is untrusted data. It is never interpreted as an
 * instruction by the rule engine or a model worker.</p>
 *
 * <p>Instances are immutable, validated and safe to share between threads.</p>
 *
 * @param requestId unique identifier for this attempt; stable for retries
 * @param senderId unique id of the player or system actor sending the message
 * @param senderName display name used in staff tooling; may be empty
 * @param audience intended audience, used for routing and staff explanations
 * @param recipientIds intended recipients; the core does not resolve them
 * @param channelId stable identifier for the delivery route (for example
 *                  {@code myplugin:pm})
 * @param conversationId identifier that isolates unrelated conversations
 * @param message original message text; never modified or stored by default
 * @param createdAtEpochMillis creation time in milliseconds since the epoch
 */
public record ModerationRequest(
        UUID requestId,
        UUID senderId,
        String senderName,
        MessageAudience audience,
        List<UUID> recipientIds,
        String channelId,
        String conversationId,
        String message,
        long createdAtEpochMillis
) {

    /**
     * Absolute upper bound accepted by the API, in characters. Callers may apply
     * a smaller limit for their own route.
     */
    public static final int ABSOLUTE_MAX_MESSAGE_LENGTH = 2000;

    /** Maximum accepted {@link #channelId()} length. */
    public static final int MAX_CHANNEL_ID_LENGTH = 128;

    /** Maximum accepted {@link #conversationId()} length. */
    public static final int MAX_CONVERSATION_ID_LENGTH = 160;

    /**
     * Validates and normalizes a request.
     *
     * @throws NullPointerException if a required field is {@code null}
     * @throws IllegalArgumentException if an identifier is blank or too long, or
     *                                  if the message exceeds
     *                                  {@link #ABSOLUTE_MAX_MESSAGE_LENGTH}
     */
    public ModerationRequest {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(senderId, "senderId");
        Objects.requireNonNull(audience, "audience");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(channelId, "channelId");
        Objects.requireNonNull(conversationId, "conversationId");

        senderName = senderName == null ? "" : senderName;
        if (senderName.length() > 64) {
            senderName = senderName.substring(0, 64);
        }
        recipientIds = recipientIds == null ? List.of() : List.copyOf(recipientIds);
        channelId = requireShort(channelId, "channelId", MAX_CHANNEL_ID_LENGTH);
        conversationId = requireShort(conversationId, "conversationId", MAX_CONVERSATION_ID_LENGTH);
        if (message.length() > ABSOLUTE_MAX_MESSAGE_LENGTH) {
            throw new IllegalArgumentException("message exceeds " + ABSOLUTE_MAX_MESSAGE_LENGTH + " characters");
        }
        if (createdAtEpochMillis <= 0L) {
            createdAtEpochMillis = System.currentTimeMillis();
        }
    }

    /**
     * Returns whether this request is for public chat.
     *
     * @return {@code true} when the audience is {@link MessageAudience#PUBLIC_CHAT}
     */
    public boolean isPublicChat() {
        return audience == MessageAudience.PUBLIC_CHAT;
    }

    /**
     * Returns whether this request has one or more explicit recipients.
     *
     * @return {@code true} when at least one recipient id is present
     */
    public boolean hasRecipients() {
        return !recipientIds.isEmpty();
    }

    private static String requireShort(String value, String name, int max) {
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        if (value.length() > max) {
            throw new IllegalArgumentException(name + " exceeds " + max + " characters");
        }
        return value;
    }

    /**
     * Starts building a request.
     *
     * <p>{@code channelId} and {@code conversationId} are required. They
     * intentionally have no unsafe fallback: sharing them across unrelated
     * conversations would merge context and ordering.</p>
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * {@inheritDoc}
     *
     * <p>The message body is intentionally omitted to reduce accidental logging
     * of private content.</p>
     */
    @Override
    public String toString() {
        return "ModerationRequest{" +
                "requestId=" + requestId +
                ", senderId=" + senderId +
                ", audience=" + audience +
                ", channelId='" + channelId + '\'' +
                ", conversationId='" + conversationId + '\'' +
                ", messageLength=" + message.length() +
                ", createdAt=" + Instant.ofEpochMilli(createdAtEpochMillis) +
                '}';
    }

    /**
     * Mutable builder for {@link ModerationRequest}.
     *
     * <p>A random {@link #requestId(UUID) request id} and the current time are
     * supplied by default. {@link #channelId(String)} and
     * {@link #conversationId(String)} must be set explicitly, or
     * {@link #build()} fails.</p>
     */
    public static final class Builder {
        private UUID requestId = UUID.randomUUID();
        private UUID senderId;
        private String senderName = "";
        private MessageAudience audience = MessageAudience.OTHER;
        private List<UUID> recipientIds = List.of();
        private String channelId;
        private String conversationId;
        private String message;
        private long createdAtEpochMillis = System.currentTimeMillis();

        private Builder() {
        }

        /**
         * Sets the unique request id. The default is a random UUID.
         *
         * @param requestId non-null request id
         * @return this builder
         */
        public Builder requestId(UUID requestId) {
            this.requestId = Objects.requireNonNull(requestId, "requestId");
            return this;
        }

        /**
         * Sets both the sender id and display name.
         *
         * @param senderId non-null sender UUID
         * @param senderName display name; {@code null} becomes an empty string
         * @return this builder
         */
        public Builder sender(UUID senderId, String senderName) {
            this.senderId = Objects.requireNonNull(senderId, "senderId");
            this.senderName = senderName == null ? "" : senderName;
            return this;
        }

        /**
         * Sets the sender id.
         *
         * @param senderId non-null sender UUID
         * @return this builder
         */
        public Builder senderId(UUID senderId) {
            this.senderId = Objects.requireNonNull(senderId, "senderId");
            return this;
        }

        /**
         * Sets the sender display name.
         *
         * @param senderName display name; {@code null} becomes an empty string
         * @return this builder
         */
        public Builder senderName(String senderName) {
            this.senderName = senderName == null ? "" : senderName;
            return this;
        }

        /**
         * Sets the intended audience.
         *
         * @param audience non-null audience, defaults to {@link MessageAudience#OTHER}
         * @return this builder
         */
        public Builder audience(MessageAudience audience) {
            this.audience = Objects.requireNonNull(audience, "audience");
            return this;
        }

        /**
         * Sets all intended recipients.
         *
         * @param recipientIds recipients; {@code null} becomes an empty list
         * @return this builder
         */
        public Builder recipients(Collection<UUID> recipientIds) {
            this.recipientIds = recipientIds == null ? List.of() : List.copyOf(recipientIds);
            return this;
        }

        /**
         * Sets exactly one intended recipient.
         *
         * @param recipientId recipient UUID; {@code null} clears the list
         * @return this builder
         */
        public Builder recipient(UUID recipientId) {
            this.recipientIds = recipientId == null ? List.of() : List.of(recipientId);
            return this;
        }

        /**
         * Sets the delivery route identifier. Required.
         *
         * @param channelId non-blank, stable route id, for example
         *                  {@code myplugin:pm}
         * @return this builder
         */
        public Builder channelId(String channelId) {
            this.channelId = Objects.requireNonNull(channelId, "channelId");
            return this;
        }

        /**
         * Sets the conversation isolation identifier. Required.
         *
         * <p>Use {@link ConversationIds#privatePair(String, UUID, UUID)} for
         * two-party messages and {@link ConversationIds#publicChat(String)} for
         * one public channel. Never reuse an id for unrelated participants.</p>
         *
         * @param conversationId non-blank conversation id
         * @return this builder
         */
        public Builder conversationId(String conversationId) {
            this.conversationId = Objects.requireNonNull(conversationId, "conversationId");
            return this;
        }

        /**
         * Sets the original message text. Required.
         *
         * @param message original message; must not exceed
         *                {@link ModerationRequest#ABSOLUTE_MAX_MESSAGE_LENGTH} characters
         * @return this builder
         */
        public Builder message(String message) {
            this.message = Objects.requireNonNull(message, "message");
            return this;
        }

        /**
         * Overrides the creation timestamp. Values at or below zero are replaced
         * with the current time by the constructor.
         *
         * @param createdAtEpochMillis creation time in milliseconds since the epoch
         * @return this builder
         */
        public Builder createdAtEpochMillis(long createdAtEpochMillis) {
            this.createdAtEpochMillis = createdAtEpochMillis;
            return this;
        }

        /**
         * Builds and validates the request.
         *
         * @return an immutable request
         * @throws NullPointerException if required fields are missing
         * @throws IllegalArgumentException if a field is blank, too long, or the
         *                                  message is too large
         */
        public ModerationRequest build() {
            Objects.requireNonNull(senderId, "senderId is required");
            Objects.requireNonNull(channelId,
                    "channelId is required; use ModerationRequests factories for the common cases");
            Objects.requireNonNull(conversationId,
                    "conversationId is required; use ConversationIds or ModerationRequests factories");
            Objects.requireNonNull(message, "message is required");
            return new ModerationRequest(
                    requestId,
                    senderId,
                    senderName,
                    audience,
                    recipientIds,
                    channelId,
                    conversationId,
                    message,
                    createdAtEpochMillis
            );
        }
    }
}
