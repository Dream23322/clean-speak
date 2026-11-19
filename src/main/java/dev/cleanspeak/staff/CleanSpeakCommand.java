package dev.cleanspeak.staff;

import dev.cleanspeak.api.AdapterRegistration;
import dev.cleanspeak.api.HealthSnapshot;
import dev.cleanspeak.api.ModerationDecision;
import dev.cleanspeak.config.PluginConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import dev.cleanspeak.CleanSpeakRuntime;
import dev.cleanspeak.storage.DecisionStore;
import dev.cleanspeak.storage.ReviewStore;
import dev.cleanspeak.storage.RateLimiter;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Staff and appeal commands. All output is plain Adventure/Legacy-safe text;
 * player names and reasons are never interpreted as MiniMessage.
 */
public final class CleanSpeakCommand implements CommandExecutor, TabCompleter {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private final JavaPlugin plugin;
    private final CleanSpeakRuntime runtime;
    private final ReviewStore reviews;
    private final RateLimiter appealLimiter = new RateLimiter(2000);

    public CleanSpeakCommand(JavaPlugin plugin, CleanSpeakRuntime runtime) {
        this.plugin = plugin;
        this.runtime = runtime;
        this.reviews = new ReviewStore(plugin.getDataFolder().toPath(), plugin.getLogger());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            help(sender);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        try {
            return switch (sub) {
                case "status" -> status(sender);
                case "alerts" -> alerts(sender, args);
                case "checks" -> checks(sender);
                case "recent" -> recent(sender, args);
                case "violations", "vl" -> violations(sender, args);
                case "show" -> show(sender, args);
                case "flag", "review" -> flag(sender, args, false);
                case "reverse", "undo" -> flag(sender, args, true);
                case "appeals" -> appeals(sender);
                case "resolve" -> resolveAppeal(sender, args);
                case "appeal" -> appeal(sender, args);
                case "reload" -> reload(sender);
                case "privacy" -> privacy(sender);
                default -> {
                    help(sender);
                    yield true;
                }
            };
        } catch (Throwable t) {
            plugin.getLogger().warning("CleanSpeak command error: " + t);
            msg(sender, "CleanSpeak command failed; check console.", NamedTextColor.RED);
            return true;
        }
    }

    private void help(CommandSender sender) {
        msg(sender, "CleanSpeak commands:", NamedTextColor.AQUA);
        if (has(sender, "cleanspeak.staff.status")) {
            msg(sender, "/cs status | /cs checks | /cs recent [n] | /cs violations [player] [n]", NamedTextColor.GRAY);
            msg(sender, "/cs show <requestId>", NamedTextColor.GRAY);
            msg(sender, "/cs alerts [on|off|status]", NamedTextColor.GRAY);
        }
        if (has(sender, "cleanspeak.staff.review")) {
            msg(sender, "/cs flag <requestId> [note] | /cs reverse <requestId> [note]", NamedTextColor.GRAY);
            msg(sender, "/cs appeals | /cs resolve <appealId> <note>", NamedTextColor.GRAY);
        }
        if (has(sender, "cleanspeak.staff.evidence")) {
        }
        if (has(sender, "cleanspeak.staff.reload")) {
            msg(sender, "/cs reload", NamedTextColor.GRAY);
        }
        if (has(sender, "cleanspeak.appeal")) {
            msg(sender, "/cs appeal <requestId> [reason]", NamedTextColor.GRAY);
        }
        msg(sender, "/cs privacy", NamedTextColor.GRAY);
    }

    private boolean alerts(CommandSender sender, String[] args) {
        if (!require(sender, "cleanspeak.notify")) {
            return true;
        }
        if (!(sender instanceof Player player)) {
            msg(sender, "Console alerts are always enabled.", NamedTextColor.GRAY);
            return true;
        }
        String option = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "toggle";
        boolean enabled;
        switch (option) {
            case "on", "enable", "true" -> {
                runtime.staffNotifier().setAlerts(player.getUniqueId(), true);
                enabled = true;
            }
            case "off", "disable", "false" -> {
                runtime.staffNotifier().setAlerts(player.getUniqueId(), false);
                enabled = false;
            }
            case "status" -> enabled = runtime.staffNotifier().alertsEnabled(player.getUniqueId());
            case "toggle" -> enabled = runtime.staffNotifier().toggleAlerts(player.getUniqueId());
            default -> {
                msg(sender, "Usage: /cs alerts [on|off|status]", NamedTextColor.RED);
                return true;
            }
        }
        msg(sender, "CleanSpeak staff alerts " + (enabled ? "enabled" : "disabled") + ".",
                enabled ? NamedTextColor.GREEN : NamedTextColor.YELLOW);
        return true;
    }

    private boolean status(CommandSender sender) {
        if (!require(sender, "cleanspeak.staff.status")) {
            return true;
        }
        HealthSnapshot health = runtime.health();
        msg(sender, "CleanSpeak status", NamedTextColor.AQUA);
        msg(sender, "Text: " + (health.textEnabled() ? "enabled" : "disabled")
                + (health.textShadowMode() ? " (shadow)" : " (enforcing)")
                + " | API queue=" + health.apiQueueDepth(), NamedTextColor.GRAY);
        CleanSpeakRuntime.State current = runtime.state();
        if (current != null) {
            var disabled = current.textPolicy.disabledCategories();
            msg(sender, "Checks: " + current.textPolicy.enabledCategories().size()
                    + "/" + dev.cleanspeak.api.Category.values().length + " enabled"
                    + (disabled.isEmpty() ? "" : " | disabled=" + String.join(",", disabled.stream().map(Enum::name).toList()))
                    + " | config=" + current.config.configVersion, NamedTextColor.GRAY);
        }
        msg(sender, "ML: " + (health.mlEnabled() ? "enabled" : "disabled")
                + " | available=" + health.mlAvailable() + " | " + health.mlStatus()
                + " | model=" + health.mlModelVersion(), NamedTextColor.GRAY);
        msg(sender, "Policy=" + (runtime.state() == null ? "none" : runtime.state().config.policyVersion)
                + " | staff alerts suppressed=" + runtime.staffNotifier().suppressedCount(), NamedTextColor.GRAY);
        for (Map.Entry<String, String> route : health.routes().entrySet()) {
            msg(sender, "Route " + route.getKey() + ": " + route.getValue(), NamedTextColor.DARK_GRAY);
        }
        return true;
    }

    private boolean checks(CommandSender sender) {
        if (!require(sender, "cleanspeak.staff.status")) {
            return true;
        }
        CleanSpeakRuntime.State state = runtime.state();
        if (state == null) {
            msg(sender, "CleanSpeak is not ready.", NamedTextColor.RED);
            return true;
        }
        for (var category : dev.cleanspeak.api.Category.values()) {
            PluginConfig.Check check = state.config.checks.get(category.name());
            if (check == null) {
                continue;
            }
            String status = check.enabled ? "enabled" : "disabled";
            msg(sender, category.name() + ": " + status
                    + " | action=" + check.action
                    + " | display=" + (check.displayName == null ? "" : check.displayName)
                    + " | ml=" + String.format(java.util.Locale.ROOT, "%.2f/%.2f",
                    check.mlReviewThreshold, check.mlBlockThreshold), NamedTextColor.GRAY);
        }
        return true;
    }

    private boolean recent(CommandSender sender, String[] args) {
        if (!require(sender, "cleanspeak.staff.status")) {
            return true;
        }
        int limit = args.length > 1 ? parseInt(args[1], 10) : 10;
        CleanSpeakRuntime.State state = runtime.state();
        if (state == null) {
            msg(sender, "CleanSpeak is not ready.", NamedTextColor.RED);
            return true;
        }
        List<DecisionStore.DecisionRecord> records = state.decisions.recent(limit);
        if (records.isEmpty()) {
            msg(sender, "No recent recorded decisions.", NamedTextColor.GRAY);
            return true;
        }
        for (DecisionStore.DecisionRecord record : records) {
            String source = ViolationSummary.sourceLabel(record);
            Component line = Component.text("#" + record.requestId() + " ", NamedTextColor.AQUA)
                    .append(Component.text("[" + source + "] ", sourceColor(source)))
                    .append(Component.text(record.rawAction()
                                    + (record.shadowed() ? "/shadow" : "")
                                    + (record.observation() ? "/observe" : ""),
                            record.action().equals("BLOCK") ? NamedTextColor.RED : NamedTextColor.YELLOW))
                    .append(Component.text(" " + record.senderName() + " " + record.channelId()
                            + " " + String.join(",", record.categories()), NamedTextColor.GRAY))
                    .clickEvent(ClickEvent.runCommand("/cs show " + record.requestId()));
            sender.sendMessage(line);
        }
        return true;
    }

    private boolean violations(CommandSender sender, String[] args) {
        if (!require(sender, "cleanspeak.staff.status")) {
            return true;
        }
        CleanSpeakRuntime.State state = runtime.state();
        if (state == null) {
            msg(sender, "CleanSpeak is not ready.", NamedTextColor.RED);
            return true;
        }
        String player = null;
        int limit = 200;
        if (args.length > 1) {
            if (args[1].matches("\\d+")) {
                limit = parseInt(args[1], 200);
            } else {
                player = args[1];
                if (args.length > 2) {
                    limit = parseInt(args[2], 200);
                }
            }
        }
        ViolationSummary.Report report = ViolationSummary.summarize(
                state.decisions.recent(limit), player, 20);
        String who = report.player().isBlank() ? "all players" : report.player();
        msg(sender, "Violations for " + who + " | scanned=" + report.scanned()
                + " flagged=" + report.flagged(), NamedTextColor.AQUA);
        if (report.entries().isEmpty()) {
            msg(sender, "No flagged violations in the retained decisions.", NamedTextColor.GRAY);
            return true;
        }
        for (ViolationSummary.Entry entry : report.entries()) {
            Component line = Component.text("  " + entry.category() + " ", NamedTextColor.GRAY)
                    .append(Component.text("[" + entry.source() + "] ", sourceColor(entry.source())))
                    .append(Component.text(entry.check() + " x" + entry.count()
                            + " | latest " + TIME.format(Instant.ofEpochMilli(entry.latestEpochMillis()))
                            + " #" + entry.latestRequestId(), NamedTextColor.DARK_GRAY))
                    .clickEvent(ClickEvent.runCommand("/cs show " + entry.latestRequestId()));
            sender.sendMessage(line);
        }
        return true;
    }

    private static NamedTextColor sourceColor(String source) {
        if (source.contains("AI+REGEX")) {
            return NamedTextColor.LIGHT_PURPLE;
        }
        if (source.contains("AI")) {
            return NamedTextColor.AQUA;
        }
        if (source.contains("REGEX")) {
            return NamedTextColor.YELLOW;
        }
        return NamedTextColor.DARK_GRAY;
    }

    private boolean show(CommandSender sender, String[] args) {
        if (!require(sender, "cleanspeak.staff.status")) {
            return true;
        }
        if (args.length < 2) {
            msg(sender, "Usage: /cs show <requestId>", NamedTextColor.RED);
            return true;
        }
        CleanSpeakRuntime.State state = runtime.state();
        Optional<DecisionStore.DecisionRecord> maybe = state == null ? Optional.empty() : state.decisions.find(args[1]);
        if (maybe.isEmpty()) {
            msg(sender, "Decision not found.", NamedTextColor.RED);
            return true;
        }
        DecisionStore.DecisionRecord record = maybe.get();
        msg(sender, "Decision #" + record.requestId(), NamedTextColor.AQUA);
        msg(sender, "Time: " + TIME.format(Instant.ofEpochMilli(record.timestamp()))
                + " | sender=" + record.senderName() + " (" + record.senderId() + ")", NamedTextColor.GRAY);
        msg(sender, "Effective=" + record.action() + " | would-be=" + record.rawAction()
                + " | shadow=" + record.shadowed() + " | fallback=" + record.fallback(), NamedTextColor.GRAY);
        msg(sender, "Source=" + ViolationSummary.sourceLabel(record)
                + " | model=" + record.modelVersion(), NamedTextColor.GRAY);
        msg(sender, "Channel=" + record.channelId() + " | conversation=" + record.conversationId()
                + " | categories=" + String.join(",", record.categories()), NamedTextColor.GRAY);
        msg(sender, "Staff reason: " + record.staffReason(), NamedTextColor.GRAY);
        msg(sender, "Player reason: " + (record.playerReason().isBlank() ? "(none)" : record.playerReason()), NamedTextColor.GRAY);
        for (String contribution : record.contributions()) {
            String[] parts = contribution.split("\\|", -1);
            String label = parts.length > 0 && parts[0].startsWith("rule:") ? "REGEX"
                    : parts.length > 0 && parts[0].startsWith("model:") ? "AI"
                    : "CONTRIB";
            msg(sender, "  " + label + " " + contribution, NamedTextColor.DARK_GRAY);
        }
        msg(sender, "Message hash=" + record.messageHash() + " length=" + record.messageLength(), NamedTextColor.DARK_GRAY);
        if (record.messagePreview() != null && has(sender, "cleanspeak.staff.evidence")) {
            msg(sender, "Retained text preview: " + record.messagePreview(), NamedTextColor.YELLOW);
        }
        reviews.find(record.requestId()).ifPresent(review ->
                msg(sender, "Review: " + review.note() + " (by " + review.markedBy() + " at "
                        + TIME.format(Instant.ofEpochMilli(review.markedAtEpochMillis())) + ")", NamedTextColor.GOLD));
        return true;
    }

    private boolean flag(CommandSender sender, String[] args, boolean reverse) {
        if (!require(sender, "cleanspeak.staff.review")) {
            return true;
        }
        if (args.length < 2) {
            msg(sender, "Usage: /cs " + (reverse ? "reverse" : "flag") + " <requestId> [note]", NamedTextColor.RED);
            return true;
        }
        CleanSpeakRuntime.State state = runtime.state();
        Optional<DecisionStore.DecisionRecord> maybe = state == null ? Optional.empty() : state.decisions.find(args[1]);
        if (maybe.isEmpty()) {
            msg(sender, "Decision not found.", NamedTextColor.RED);
            return true;
        }
        String note = args.length > 2 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length))
                : (reverse ? "staff reversed decision" : "staff marked decision incorrect");
        reviews.mark(args[1], sender.getName(), note, false);
        msg(sender, (reverse ? "Reversed" : "Flagged") + " decision " + args[1]
                + ". Text that was already delivered cannot be unsent; blocked text cannot be unblocked.", NamedTextColor.GREEN);
        if (reverse) {
            runtime.staffNotifier().alert(sender.getName() + " reversed decision " + args[1]);
        }
        return true;
    }

    private boolean appeals(CommandSender sender) {
        if (!require(sender, "cleanspeak.staff.review")) {
            return true;
        }
        CleanSpeakRuntime.State state = runtime.state();
        if (state == null) {
            return true;
        }
        List<dev.cleanspeak.storage.AppealStore.Appeal> open = state.appeals.open();
        if (open.isEmpty()) {
            msg(sender, "No open appeals.", NamedTextColor.GRAY);
            return true;
        }
        for (var appeal : open) {
            msg(sender, "#" + appeal.id() + " request=" + appeal.requestId()
                    + " player=" + appeal.playerName() + " reason=" + appeal.reason(), NamedTextColor.GRAY);
        }
        return true;
    }

    private boolean resolveAppeal(CommandSender sender, String[] args) {
        if (!require(sender, "cleanspeak.staff.review")) {
            return true;
        }
        CleanSpeakRuntime.State state = runtime.state();
        if (state == null || args.length < 3) {
            msg(sender, "Usage: /cs resolve <appealId> <note>", NamedTextColor.RED);
            return true;
        }
        String note = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        if (!state.appeals.resolve(args[1], sender.getName(), note)) {
            msg(sender, "Open appeal not found.", NamedTextColor.RED);
            return true;
        }
        msg(sender, "Appeal resolved.", NamedTextColor.GREEN);
        return true;
    }

    private boolean appeal(CommandSender sender, String[] args) {
        if (!require(sender, "cleanspeak.appeal")) {
            return true;
        }
        if (!(sender instanceof Player player)) {
            msg(sender, "Only an in-game player can submit an appeal.", NamedTextColor.RED);
            return true;
        }
        CleanSpeakRuntime.State state = runtime.state();
        if (state == null) {
            return true;
        }
        if (args.length < 2) {
            msg(sender, "Usage: /cs appeal <requestId> [reason]", NamedTextColor.RED);
            return true;
        }
        if (!appealLimiter.allow("appeal:" + player.getUniqueId(), state.config.staff.appealCooldownSeconds * 1000L)) {
            msg(sender, "Please wait before submitting another appeal.", NamedTextColor.RED);
            return true;
        }
        Optional<DecisionStore.DecisionRecord> decision = state.decisions.find(args[1]);
        if (decision.isEmpty() || !decision.get().senderId().equalsIgnoreCase(player.getUniqueId().toString())) {
            msg(sender, "No decision with that request ID belongs to you.", NamedTextColor.RED);
            return true;
        }
        String reason = args.length > 2 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : "";
        var appeal = state.appeals.create(args[1], player.getUniqueId().toString(), player.getName(), reason);
        msg(sender, "Appeal submitted (#" + appeal.id() + "). Staff will review it.", NamedTextColor.GREEN);
        runtime.staffNotifier().alert("New appeal #" + appeal.id() + " from " + player.getName()
                + " for request " + args[1]);
        return true;
    }

    private boolean reload(CommandSender sender) {
        if (!require(sender, "cleanspeak.staff.reload")) {
            return true;
        }
        msg(sender, "Reloading CleanSpeak...", NamedTextColor.YELLOW);
        boolean ok = runtime.reload();
        msg(sender, ok ? "Reload complete. Check /cs status for changed limits."
                : "Reload failed; previous configuration remains active. Check console.", ok ? NamedTextColor.GREEN : NamedTextColor.RED);
        return true;
    }

    private boolean privacy(CommandSender sender) {
        PluginConfig config = runtime.config();
        msg(sender, "Privacy and retention", NamedTextColor.AQUA);
        msg(sender, "Raw text retained=" + config.privacy.retainRawText, NamedTextColor.GRAY);
        msg(sender, "Decision retention=" + config.privacy.decisionRetentionDays + " days", NamedTextColor.GRAY);
        return true;
    }

    private boolean require(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) {
            return true;
        }
        msg(sender, "You do not have permission: " + permission, NamedTextColor.RED);
        return false;
    }

    private static boolean has(CommandSender sender, String permission) {
        return sender.hasPermission(permission);
    }

    private void msg(CommandSender sender, String message, NamedTextColor color) {
        sender.sendMessage(Component.text(message, color));
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Math.max(1, Math.min(100, Integer.parseInt(value)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }



    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>();
            options.add("help");
            options.add("privacy");
            if (has(sender, "cleanspeak.staff.status")) {
                options.addAll(List.of("status", "alerts", "checks", "recent", "violations", "show"));
            }
            if (has(sender, "cleanspeak.staff.review")) {
                options.addAll(List.of("flag", "reverse", "appeals", "resolve"));
            }
            if (has(sender, "cleanspeak.staff.reload")) {
                options.add("reload");
            }
            if (has(sender, "cleanspeak.appeal")) {
                options.add("appeal");
            }
            return options;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("alerts")) {
            return List.of("on", "off", "status");
        }
        return List.of();
    }
}
