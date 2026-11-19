package dev.cleanspeak.api;

import java.util.UUID;

/**
 * Helpers for constructing stable, isolated conversation identifiers.
 *
 * <p>A conversation id must identify one conversation for context and ordering.
 * Unrelated players, rooms or channels must never share one.</p>
 */
public final class ConversationIds {

    private ConversationIds() {
    }

    /**
     * Builds a private-message conversation id that is independent of who sent
     * first.
     *
     * <p>The UUIDs are sorted, so Alice-to-Bob and Bob-to-Alice share one id.</p>
     *
     * @param channelId stable route id, for example {@code myplugin:pm}
     * @param first one participant
     * @param second the other participant
     * @return a conversation id safe for {@link ModerationRequest.Builder#conversationId(String)}
     */
    public static String privatePair(String channelId, UUID first, UUID second) {
        String a = first.toString();
        String b = second.toString();
        if (a.compareTo(b) > 0) {
            String swap = a;
            a = b;
            b = swap;
        }
        return channelId + ":" + a + ":" + b;
    }

    /**
     * Builds a conversation id for one public channel.
     *
     * @param channelId stable public channel id
     * @return a conversation id safe for {@link ModerationRequest.Builder#conversationId(String)}
     */
    public static String publicChat(String channelId) {
        return channelId + ":public";
    }
}
