package dev.cleanspeak.staff;

import dev.cleanspeak.api.ModerationAction;
import dev.cleanspeak.api.ModerationDecision;
import dev.cleanspeak.config.PluginConfig;
import dev.cleanspeak.storage.RateLimiter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.function.Supplier;

/**
 * Player-facing notices. They are rate-limited separately from staff alerts and
 * never include the original prohibited text.
 */
public final class PlayerNotices {

    private final JavaPlugin plugin;
    private final Supplier<PluginConfig> config;
    private final RateLimiter limiter = new RateLimiter(2000);

    public PlayerNotices(JavaPlugin plugin, Supplier<PluginConfig> config) {
        this.plugin = plugin;
        this.config = config;
    }

    public void notifyBlocked(Player player, ModerationDecision decision) {
        if (player == null || decision.action() == ModerationAction.ALLOW) {
            return;
        }
        PluginConfig config = this.config.get();
        if (!config.text.notifyBlocked) {
            return;
        }
        // The caller may be on an async chat or moderation thread. Do the
        // player-state lookup and rate-limit key on the main thread.
        runMain(() -> {
            if (!player.isOnline()) {
                return;
            }
            String key = "player:" + player.getUniqueId();
            if (!limiter.allow(key, config.text.noticeCooldownSeconds * 1000L)) {
                return;
            }
            String reason = decision.playerReason().isBlank()
                    ? "Your message was not delivered."
                    : decision.playerReason();
            Component component = Component.text(reason, NamedTextColor.RED);
            if (!reason.contains(decision.requestId().toString())) {
                component = component.append(Component.text(" Request ID: " + decision.requestId(), NamedTextColor.GRAY));
            }
            player.sendMessage(component);
        });
    }

    public void notifyMessage(Player player, String message) {
        if (player == null || message == null || message.isBlank()) {
            return;
        }
        runMain(() -> {
            if (player.isOnline()) {
                player.sendMessage(Component.text(message, NamedTextColor.RED));
            }
        });
    }

    public void joinNotice(Player player) {
        PluginConfig config = this.config.get();
        if (player == null || !config.privacy.joinNotice || config.privacy.joinNoticeMessage.isBlank()) {
            return;
        }
        Component component = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                .legacyAmpersand()
                .deserialize(config.privacy.joinNoticeMessage);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                player.sendMessage(component);
            }
        }, 40L);
    }

    private void runMain(Runnable runnable) {
        if (Bukkit.isPrimaryThread()) {
            runnable.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, runnable);
        }
    }
}
