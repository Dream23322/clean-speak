package dev.cleanspeak.chat;

import dev.cleanspeak.CleanSpeakRuntime;
import dev.cleanspeak.api.ConversationIds;
import dev.cleanspeak.api.MessageAudience;
import dev.cleanspeak.api.ModerationDecision;
import dev.cleanspeak.api.ModerationRequest;
import dev.cleanspeak.config.PluginConfig;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Public chat adapter.
 *
 * <p>RULES_ONLY is the default: local rules are evaluated synchronously and the
 * original signed chat is untouched if allowed. ASYNC_REPLAY cancels first,
 * checks asynchronously, and replays with a system message if allowed. That
 * second mode intentionally trades away signing/reportability and existing chat
 * formatting; it is off by default.</p>
 */
public final class PublicChatListener implements Listener {

    private final JavaPlugin plugin;
    private final CleanSpeakRuntime runtime;

    public PublicChatListener(JavaPlugin plugin, CleanSpeakRuntime runtime) {
        this.plugin = plugin;
        this.runtime = runtime;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        CleanSpeakRuntime.State state = runtime.state();
        if (state == null) {
            return;
        }
        PluginConfig config = state.config;
        if (!config.text.enabled) {
            return;
        }
        Player player = event.getPlayer();
        String message = PlainTextComponentSerializer.plainText().serialize(event.message());
        if (message.isBlank()) {
            return;
        }
        if (message.length() > ModerationRequest.ABSOLUTE_MAX_MESSAGE_LENGTH) {
            event.setCancelled(true);
            runtime.playerNotices().notifyMessage(player,
                    "Your message was not delivered because it is too long for moderated chat.");
            return;
        }

        ModerationRequest request = ModerationRequest.builder()
                .requestId(UUID.randomUUID())
                .sender(player.getUniqueId(), player.getName())
                .audience(MessageAudience.PUBLIC_CHAT)
                .channelId("paper:public-chat")
                .conversationId(ConversationIds.publicChat("paper:public-chat"))
                .message(message)
                .build();

        if (config.text.deliveryMode.equals("ASYNC_REPLAY")) {
            handleReplay(event, player, request, state);
            return;
        }

        try {
            ModerationDecision decision = state.service.moderateRulesOnly(request);
            if (!decision.isAllowed()) {
                event.setCancelled(true);
                runtime.playerNotices().notifyBlocked(player, decision);
                return;
            }
            if (config.text.ml.enabled && !state.service.classifier().inProcess()) {
                // External model observation only. The bundled model already ran
                // synchronously in the pre-delivery path above.
                state.service.observeShadow(request);
            }
        } catch (Throwable t) {
            // Fail open on an internal local-rule error, but tell staff. Never
            // take down chat because the filter itself failed.
            plugin.getLogger().warning("Public chat rule check failed; message allowed: " + t);
            runtime.staffNotifier().alert("Public chat rule check failed; message was allowed. Check console.");
        }
    }

    private void handleReplay(AsyncChatEvent event, Player player, ModerationRequest request, CleanSpeakRuntime.State state) {
        // Capture viewer audiences and the message before leaving the event
        // thread. These are immutable/adventure objects.
        List<Audience> viewers = new ArrayList<>(event.viewers());
        Component original = event.message();
        event.setCancelled(true);

        try {
            state.service.moderate(request).whenComplete((decision, error) -> {
                if (error != null) {
                    Bukkit.getScheduler().runTask(plugin, () ->
                            runtime.playerNotices().notifyMessage(player,
                                    "Your message was not delivered because it could not be checked in time."));
                    return;
                }
                if (!decision.isAllowed()) {
                    runtime.playerNotices().notifyBlocked(player, decision);
                    return;
                }
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    Component rendered = Component.text("<" + player.getName() + "> ")
                            .append(original);
                    for (Audience viewer : viewers) {
                        viewer.sendMessage(rendered);
                    }
                });
            });
        } catch (Throwable t) {
            runtime.playerNotices().notifyMessage(player,
                    "Your message was not delivered because moderation is unavailable.");
        }
    }
}
