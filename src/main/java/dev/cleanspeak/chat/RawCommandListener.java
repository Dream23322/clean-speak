package dev.cleanspeak.chat;

import dev.cleanspeak.CleanSpeakRuntime;
import dev.cleanspeak.config.PluginConfig;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;

/**
 * Limited, opt-in raw command inspection fallback.
 *
 * <p>It only inspects commands explicitly listed in config and only calls the
 * synchronous local rule engine. It cannot see aliases that are not configured,
 * messages rewritten by the command plugin, or messages sent directly by other
 * plugins. It is not equivalent to integrating the actual delivery path.</p>
 */
public final class RawCommandListener implements Listener {

    private final CleanSpeakRuntime runtime;

    public RawCommandListener(CleanSpeakRuntime runtime) {
        this.runtime = runtime;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        CleanSpeakRuntime.State state = runtime.state();
        if (state == null) {
            return;
        }
        PluginConfig config = state.config;
        if (!config.rawCommandInspection.enabled || !config.text.enabled) {
            return;
        }
        String raw = event.getMessage();
        if (raw == null || raw.length() < 2) {
            return;
        }
        String withoutSlash = raw.startsWith("/") ? raw.substring(1) : raw;
        String[] parts = withoutSlash.trim().split("\\s+");
        if (parts.length == 0) {
            return;
        }
        String command = parts[0].toLowerCase(Locale.ROOT);
        Integer messageStart = config.rawCommandInspection.inspect.get(command);
        if (messageStart == null) {
            return;
        }
        if (messageStart >= parts.length) {
            return;
        }
        String message = String.join(" ", Arrays.copyOfRange(parts, messageStart, parts.length));
        if (message.isBlank()) {
            return;
        }
        UUID requestId = UUID.randomUUID();
        var request = dev.cleanspeak.api.ModerationRequest.builder()
                .requestId(requestId)
                .sender(event.getPlayer().getUniqueId(), event.getPlayer().getName())
                .audience(dev.cleanspeak.api.MessageAudience.COMMAND)
                .channelId("command:" + command)
                // Do not merge unrelated raw commands; recipient parsing is not
                // guaranteed here, so this request has no split-message context.
                .conversationId("raw-command:" + requestId)
                .message(message)
                .build();
        try {
            var decision = state.service.moderateRulesOnly(request);
            if (!decision.isAllowed()) {
                event.setCancelled(true);
                runtime.playerNotices().notifyBlocked(event.getPlayer(), decision);
            }
        } catch (Throwable t) {
            runtime.staffNotifier().alert("Raw command rules-only check failed; command was allowed. Check console.");
        }
    }
}
