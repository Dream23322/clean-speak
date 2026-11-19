package dev.cleanspeak.chat;

import com.earth2me.essentials.messaging.IMessageRecipient;
import dev.cleanspeak.CleanSpeakRuntime;
import dev.cleanspeak.api.AdapterRegistration;
import dev.cleanspeak.api.ConversationIds;
import dev.cleanspeak.api.MessageAudience;
import dev.cleanspeak.api.ModerationRequest;
import net.ess3.api.IUser;
import net.ess3.api.events.PrivateMessagePreSendEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.List;
import java.util.UUID;

/**
 * Optional EssentialsX adapter for PrivateMessagePreSendEvent.
 *
 * <p>The EssentialsX event is synchronous. This adapter therefore applies only
 * the fast local rule engine and does not apply ML blocking. The messaging plugin
 * still owns recipient resolution, permissions, ignore lists, reply targets and
 * delivery. This adapter has not been live-tested on the target server; install
 * it only after verifying the EssentialsX version.</p>
 */
public final class EssentialsPreSendAdapter implements Listener {

    private final CleanSpeakRuntime runtime;

    public EssentialsPreSendAdapter(CleanSpeakRuntime runtime) {
        this.runtime = runtime;
        runtime.api().registerAdapter(new AdapterRegistration(
                "essentials-pm",
                "EssentialsX (installed)",
                "/tell,/msg,/w,/reply via EssentialsX PrivateMessagePreSendEvent",
                true,
                "synchronous; rules-only pre-delivery. ML blocking is not applied on this path."
        ));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPrivateMessage(PrivateMessagePreSendEvent event) {
        CleanSpeakRuntime.State state = runtime.state();
        if (state == null || !state.config.text.enabled || !state.config.integrations.essentialsRulesOnly) {
            return;
        }
        IMessageRecipient senderRecipient = event.getSender();
        if (!(senderRecipient instanceof IUser sender)) {
            return;
        }
        Player player = sender.getBase();
        if (player == null) {
            return;
        }
        String channel = "essentials:pm";
        UUID recipientId = null;
        String recipientName = "";
        IMessageRecipient recipient = event.getRecipient();
        if (recipient instanceof IUser recipientUser) {
            recipientId = recipientUser.getUUID();
            recipientName = recipientUser.getName();
        }
        String conversation = recipientId == null
                ? channel + ":unresolved:" + player.getUniqueId()
                : ConversationIds.privatePair(channel, player.getUniqueId(), recipientId);
        List<UUID> recipients = recipientId == null ? List.of() : List.of(recipientId);
        String messageText = event.getMessage();
        if (messageText == null || messageText.isBlank()) {
            return;
        }
        ModerationRequest request = ModerationRequest.builder()
                .requestId(UUID.randomUUID())
                .sender(player.getUniqueId(), player.getName())
                .audience(MessageAudience.PRIVATE_MESSAGE)
                .recipients(recipients)
                .channelId(channel)
                .conversationId(conversation)
                .message(messageText)
                .build();
        try {
            var decision = state.service.moderateRulesOnly(request);
            if (!decision.isAllowed()) {
                event.setCancelled(true);
                runtime.playerNotices().notifyBlocked(player, decision);
            }
        } catch (Throwable t) {
            runtime.staffNotifier().alert("EssentialsX rules-only check failed; message was allowed. Check console.");
        }
    }
}
