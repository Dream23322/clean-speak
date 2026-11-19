package dev.cleanspeak.api;

import java.util.Collection;
import java.util.Objects;
import java.util.UUID;

/**
 * Factory methods for correctly isolated {@link ModerationRequest}s.
 *
 * <p>These helpers exist because the most common integration bugs are a missing
 * channel id, a shared conversation id, or a conversation id that changes when
 * the sender and recipient swap roles. Using a factory makes the safe choice
 * the short one.</p>
 *
 * <p>All factories generate a random request id. Use the overloads that accept a
 * {@code requestId} when a retry must keep the same id.</p>
 */
public final class ModerationRequests {

    private ModerationRequests() {
    }

    /**
     * Creates a two-party private-message request.
     *
     * <p>The conversation id is derived from the channel and both UUIDs, so it
     * is identical for messages in either direction.</p>
     *
     * @param senderId sender UUID
     * @param senderName sender display name; may be empty
     * @param recipientId recipient UUID
     * @param channelId stable route id, for example {@code myplugin:pm}
     * @param message original message text
     * @return a validated request
     */
    public static ModerationRequest privateMessage(
            UUID senderId,
            String senderName,
            UUID recipientId,
            String channelId,
            String message
    ) {
        return privateMessage(UUID.randomUUID(), senderId, senderName, recipientId, channelId, message);
    }

    /**
     * Creates a two-party private-message request with an explicit request id.
     *
     * @param requestId stable request id
     * @param senderId sender UUID
     * @param senderName sender display name; may be empty
     * @param recipientId recipient UUID
     * @param channelId stable route id, for example {@code myplugin:pm}
     * @param message original message text
     * @return a validated request
     */
    public static ModerationRequest privateMessage(
            UUID requestId,
            UUID senderId,
            String senderName,
            UUID recipientId,
            String channelId,
            String message
    ) {
        Objects.requireNonNull(recipientId, "recipientId");
        Objects.requireNonNull(channelId, "channelId");
        return ModerationRequest.builder()
                .requestId(requestId)
                .sender(senderId, senderName)
                .audience(MessageAudience.PRIVATE_MESSAGE)
                .recipient(recipientId)
                .channelId(channelId)
                .conversationId(ConversationIds.privatePair(channelId, senderId, recipientId))
                .message(message)
                .build();
    }

    /**
     * Creates a public-chat request.
     *
     * @param senderId sender UUID
     * @param senderName sender display name; may be empty
     * @param channelId stable public channel id
     * @param message original message text
     * @return a validated request
     */
    public static ModerationRequest publicChat(
            UUID senderId,
            String senderName,
            String channelId,
            String message
    ) {
        return publicChat(UUID.randomUUID(), senderId, senderName, channelId, message);
    }

    /**
     * Creates a public-chat request with an explicit request id.
     *
     * @param requestId stable request id
     * @param senderId sender UUID
     * @param senderName sender display name; may be empty
     * @param channelId stable public channel id
     * @param message original message text
     * @return a validated request
     */
    public static ModerationRequest publicChat(
            UUID requestId,
            UUID senderId,
            String senderName,
            String channelId,
            String message
    ) {
        Objects.requireNonNull(channelId, "channelId");
        return ModerationRequest.builder()
                .requestId(requestId)
                .sender(senderId, senderName)
                .audience(MessageAudience.PUBLIC_CHAT)
                .channelId(channelId)
                .conversationId(ConversationIds.publicChat(channelId))
                .message(message)
                .build();
    }

    /**
     * Creates a group-message request.
     *
     * <p>The caller supplies the conversation id because only the caller knows
     * whether two sends to the same participant set belong to one conversation
     * or to separate rooms.</p>
     *
     * @param senderId sender UUID
     * @param senderName sender display name; may be empty
     * @param recipientIds intended recipients
     * @param channelId stable route id
     * @param conversationId isolation id unique to this room or conversation
     * @param message original message text
     * @return a validated request
     */
    public static ModerationRequest groupMessage(
            UUID senderId,
            String senderName,
            Collection<UUID> recipientIds,
            String channelId,
            String conversationId,
            String message
    ) {
        return groupMessage(UUID.randomUUID(), senderId, senderName, recipientIds, channelId, conversationId, message);
    }

    /**
     * Creates a group-message request with an explicit request id.
     *
     * @param requestId stable request id
     * @param senderId sender UUID
     * @param senderName sender display name; may be empty
     * @param recipientIds intended recipients
     * @param channelId stable route id
     * @param conversationId isolation id unique to this room or conversation
     * @param message original message text
     * @return a validated request
     */
    public static ModerationRequest groupMessage(
            UUID requestId,
            UUID senderId,
            String senderName,
            Collection<UUID> recipientIds,
            String channelId,
            String conversationId,
            String message
    ) {
        return ModerationRequest.builder()
                .requestId(requestId)
                .sender(senderId, senderName)
                .audience(MessageAudience.GROUP_MESSAGE)
                .recipients(recipientIds)
                .channelId(channelId)
                .conversationId(conversationId)
                .message(message)
                .build();
    }
}
