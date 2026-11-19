package dev.cleanspeak.staff;

import dev.cleanspeak.api.ModerationDecision;
import dev.cleanspeak.api.ModerationRequest;
import dev.cleanspeak.config.PluginConfig;
import dev.cleanspeak.storage.RateLimiter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Rate-limited staff alerts. They contain rule/category identifiers, not the
 * player's raw message. Staff can use /cs show for details permitted by policy.
 */
public final class StaffNotifier {

    private final JavaPlugin plugin;
    private final Supplier<PluginConfig> config;
    private final AlertPreferences alertPreferences;
    private final RateLimiter limiter = new RateLimiter(1000);
    private final AtomicLong suppressed = new AtomicLong();
    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();
    private final Set<UUID> actionAlerts = ConcurrentHashMap.newKeySet();
    private static final Pattern CONFIDENCE = Pattern.compile("ml-confidence=([0-9]+(?:\\.[0-9]+)?)");

    public StaffNotifier(JavaPlugin plugin, Supplier<PluginConfig> config) {
        this.plugin = plugin;
        this.config = config;
        this.alertPreferences = new AlertPreferences(plugin.getDataFolder().toPath(), plugin.getLogger());
    }

    public boolean alertsEnabled(UUID playerId) {
        return alertPreferences.enabled(playerId);
    }

    public boolean toggleAlerts(UUID playerId) {
        return alertPreferences.toggle(playerId);
    }

    public void setAlerts(UUID playerId, boolean enabled) {
        alertPreferences.set(playerId, enabled);
    }

    public void notifyDecision(ModerationRequest request, ModerationDecision decision) {
        PluginConfig config = this.config.get();
        if (!config.staff.alerts) {
            return;
        }
        if (decision.rawAction() == dev.cleanspeak.api.ModerationAction.ALLOW
                && !decision.shadowed()
                && !decision.observation()) {
            return;
        }
        if (actionAlerts.remove(request.requestId())) {
            return;
        }
        boolean canceled = !decision.shadowed() && !decision.observation()
                && decision.action() != dev.cleanspeak.api.ModerationAction.ALLOW;
        String kind = canceled ? "cancel" : "flag";
        String actionText = canceled
                ? "Message canceled from " + request.senderName()
                : request.senderName() + " has been flagged";
        String categories = decision.categories().isEmpty() ? "none"
                : String.join(",", decision.categories().stream().map(Enum::name).toList());
        String source = ViolationSummary.sourceLabel(decision.contributions().stream()
                .map(contribution -> contribution.source() + "|"
                        + contribution.category().name() + "|"
                        + String.format(java.util.Locale.ROOT, "%.3f", contribution.score()) + "|"
                        + contribution.detail())
                .toList());
        String confidence = modelConfidence(decision);
        String detail = "source=" + source
                + "\ncategory=" + categories
                + "\naction=" + decision.action().name()
                + (confidence.isBlank() ? "" : "\nconfidence=" + confidence)
                + "\nmodel=" + decision.modelVersion()
                + "\nchannel=" + request.channelId()
                + "\nrequest=" + decision.requestId();
        notifyPlayerEvent(request.senderName(), kind, actionText,
                decision.requestId().toString(), detail);
    }

    /**
     * Explicit actions.yml [alert]. The normal decision alert is suppressed for
     * this request so a configured threshold does not produce two lines.
     */
    public void notifyAction(ModerationRequest request, ModerationDecision decision, int count) {
        PluginConfig config = this.config.get();
        if (!config.staff.alerts) {
            return;
        }
        actionAlerts.add(request.requestId());
        boolean canceled = !decision.shadowed() && !decision.observation()
                && decision.action() != dev.cleanspeak.api.ModerationAction.ALLOW;
        String actionText = canceled
                ? "Message canceled from " + request.senderName() + " at threshold " + count
                : request.senderName() + " has been flagged at threshold " + count;
        String categories = decision.categories().isEmpty() ? "none"
                : String.join(",", decision.categories().stream().map(Enum::name).toList());
        String confidence = modelConfidence(decision);
        String detail = "category=" + categories
                + "\naction=" + decision.action().name()
                + (confidence.isBlank() ? "" : "\nconfidence=" + confidence)
                + "\ncount=" + count
                + "\nmodel=" + decision.modelVersion()
                + "\nchannel=" + request.channelId()
                + "\nrequest=" + decision.requestId();
        if (!notifyPlayerEvent(request.senderName(), "action", actionText,
                decision.requestId().toString(), detail)) {
            actionAlerts.remove(request.requestId());
        }
    }

    static String modelConfidence(ModerationDecision decision) {
        for (var contribution : decision.contributions()) {
            if (!contribution.source().startsWith("model:")) {
                continue;
            }
            String detail = contribution.detail();
            int start = detail.indexOf("confidence=");
            if (start < 0) {
                continue;
            }
            start += "confidence=".length();
            int end = start;
            while (end < detail.length()
                    && (Character.isDigit(detail.charAt(end)) || detail.charAt(end) == '.')) {
                end++;
            }
            if (end > start) {
                return detail.substring(start, end);
            }
        }
        Matcher matcher = CONFIDENCE.matcher(decision.staffReason());
        return matcher.find() ? matcher.group(1) : "";
    }

    private boolean notifyPlayerEvent(String playerName, String kind, String actionText,
                                      String requestId, String detail) {
        PluginConfig config = this.config.get();
        if (!config.staff.alerts) {
            return false;
        }
        long count = counters.computeIfAbsent(
                playerName.toLowerCase(java.util.Locale.ROOT) + ":" + kind,
                ignored -> new AtomicLong()).incrementAndGet();
        if (!limiter.allow("staff:" + kind + ":" + playerName.toLowerCase(java.util.Locale.ROOT),
                config.staff.alertCooldownSeconds * 1000L)) {
            suppressed.incrementAndGet();
            return false;
        }
        long suppressedNow = suppressed.getAndSet(0);
        Component info = Component.text("[info]", NamedTextColor.AQUA)
                .hoverEvent(HoverEvent.showText(Component.text(detail, NamedTextColor.GRAY)));
        if (!requestId.isBlank()) {
            info = info.clickEvent(ClickEvent.runCommand("/cleanspeak show " + requestId));
        }
        Component component = Component.text("[CleanSpeak] ", NamedTextColor.AQUA)
                .append(Component.text(actionText, NamedTextColor.YELLOW))
                .append(Component.text(" ", NamedTextColor.GRAY))
                .append(info)
                .append(Component.text(" (x" + count + ")"
                        + (suppressedNow > 0 ? " (+" + suppressedNow + " suppressed)" : ""),
                        NamedTextColor.DARK_GRAY));
        Component finalLine = component;
        runMain(() -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.hasPermission("cleanspeak.notify")
                        && alertPreferences.enabled(player.getUniqueId())) {
                    player.sendMessage(finalLine);
                }
            }
        });
        return true;
    }

    public void alert(String message) {
        PluginConfig config = this.config.get();
        if (!config.staff.alerts) {
            return;
        }
        if (!limiter.allow("infra", Math.max(5000L, config.staff.alertCooldownSeconds * 1000L))) {
            suppressed.incrementAndGet();
            return;
        }
        long suppressedNow = suppressed.getAndSet(0);
        Component component = Component.text("[CleanSpeak] ", NamedTextColor.AQUA)
                .append(Component.text(message
                        + (suppressedNow > 0 ? " (+" + suppressedNow + " suppressed)" : ""), NamedTextColor.GOLD));
        runMain(() -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.hasPermission("cleanspeak.notify")
                        && alertPreferences.enabled(player.getUniqueId())) {
                    player.sendMessage(component);
                }
            }
        });
    }

    public long suppressedCount() {
        return suppressed.get();
    }

    private void runMain(Runnable runnable) {
        if (Bukkit.isPrimaryThread()) {
            runnable.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, runnable);
        }
    }
}
