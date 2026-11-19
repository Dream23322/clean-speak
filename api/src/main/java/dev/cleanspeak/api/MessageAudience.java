package dev.cleanspeak.api;

/**
 * The intended audience of a message.
 *
 * <p>The audience is used for conversation isolation guidance and for explaining
 * the route in staff tools. It does not by itself resolve recipients or grant
 * permissions.</p>
 */
public enum MessageAudience {
    /** Public server chat. */
    PUBLIC_CHAT,
    /** A message between exactly two parties. */
    PRIVATE_MESSAGE,
    /** A message to a group, room or channel. */
    GROUP_MESSAGE,
    /** A command whose arguments are being inspected. */
    COMMAND,
    /** Any other plugin-owned route. */
    OTHER
}
