package dev.cleanspeak.example.tell;

import dev.cleanspeak.api.CleanSpeakApi;
import dev.cleanspeak.api.ModerationAction;
import dev.cleanspeak.api.ModerationDecision;
import dev.cleanspeak.api.ModerationException;
import dev.cleanspeak.api.ModerationRequest;
import dev.cleanspeak.api.ModerationRequests;
import dev.cleanspeak.api.ModerationSession;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Example messaging service. The messaging plugin remains responsible for
 * permissions, ignore lists, recipients, reply targets and exactly-once
 * delivery. CleanSpeak only returns a decision.
 */
public final class TellService {

    public static final String CHANNEL = "example:tell";
    public static final String PERMISSION = "cleanspeak.example.tell";
    private static final int MAX_MESSAGE_LENGTH = 1000;

    private final PlayerDirectory directory;
    private final IgnoreStore ignores;
    private final MessageSink messages;
    private final MainThreadScheduler scheduler;
    private final ModerationSession moderation;
    private final java.util.concurrent.ConcurrentHashMap<UUID, UUID> replyTargets = new java.util.concurrent.ConcurrentHashMap<>();

    public TellService(
            CleanSpeakApi api,
            PlayerDirectory directory,
            IgnoreStore ignores,
            MessageSink messages,
            MainThreadScheduler scheduler,
            int maxPendingPerConversation,
            int maxConversations,
            long timeoutMillis
    ) {
        this.directory = directory;
        this.ignores = ignores;
        this.messages = messages;
        this.scheduler = scheduler;
        this.moderation = ModerationSession.builder(api)
                .maxPendingPerConversation(maxPendingPerConversation)
                .maxConversations(maxConversations)
                .timeout(Duration.ofMillis(Math.max(500L, timeoutMillis)))
                .build();
    }

    public void tell(TellPlayer sender, String targetName, String message) {
        if (!directory.hasPermission(sender.id(), PERMISSION)) {
            messages.send(sender.id(), "You do not have permission to send private messages.");
            return;
        }
        if (message == null || message.isBlank()) {
            messages.send(sender.id(), "Usage: /tell <player> <message>");
            return;
        }
        if (message.length() > MAX_MESSAGE_LENGTH) {
            messages.send(sender.id(), "Your message is too long for this example.");
            return;
        }
        Optional<TellPlayer> target = directory.findOnline(targetName);
        if (target.isEmpty()) {
            messages.send(sender.id(), "That player is not online.");
            return;
        }
        if (target.get().id().equals(sender.id())) {
            messages.send(sender.id(), "You cannot message yourself.");
            return;
        }
        if (!directory.hasPermission(target.get().id(), PERMISSION)) {
            messages.send(sender.id(), "That player cannot receive private messages.");
            return;
        }
        if (ignores.ignores(target.get().id(), sender.id())) {
            messages.send(sender.id(), "That player is not accepting messages from you.");
            return;
        }
        submit(sender, target.get(), message);
    }

    public void reply(TellPlayer sender, String message) {
        if (!directory.hasPermission(sender.id(), PERMISSION)) {
            messages.send(sender.id(), "You do not have permission to send private messages.");
            return;
        }
        if (message == null || message.isBlank()) {
            messages.send(sender.id(), "Usage: /reply <message>");
            return;
        }
        if (message.length() > MAX_MESSAGE_LENGTH) {
            messages.send(sender.id(), "Your message is too long for this example.");
            return;
        }
        UUID targetId = replyTargets.get(sender.id());
        if (targetId == null) {
            messages.send(sender.id(), "You have no reply target yet.");
            return;
        }
        Optional<TellPlayer> target = directory.findOnline(targetId);
        if (target.isEmpty()) {
            messages.send(sender.id(), "That player is no longer online.");
            return;
        }
        if (!directory.hasPermission(target.get().id(), PERMISSION)) {
            messages.send(sender.id(), "That player cannot receive private messages.");
            return;
        }
        if (ignores.ignores(target.get().id(), sender.id())) {
            messages.send(sender.id(), "That player is not accepting messages from you.");
            return;
        }
        submit(sender, target.get(), message);
    }

    public Optional<TellPlayer> replyTarget(UUID senderId) {
        UUID target = replyTargets.get(senderId);
        return target == null ? Optional.empty() : directory.findOnline(target);
    }

    private void submit(TellPlayer sender, TellPlayer target, String originalMessage) {
        ModerationRequest request = ModerationRequests.privateMessage(
                sender.id(),
                sender.name(),
                target.id(),
                CHANNEL,
                originalMessage
        );
        UUID requestId = request.requestId();
        AtomicBoolean completed = new AtomicBoolean();

        CompletableFuture<ModerationDecision> future;
        try {
            future = moderation.submit(request);
        } catch (RuntimeException e) {
            messages.send(sender.id(), "Private messages are busy; your message was not delivered.");
            return;
        }
        future.whenComplete((decision, error) -> {
            if (!completed.compareAndSet(false, true)) {
                return;
            }
            if (error != null) {
                ModerationException failure = ModerationException.from(error);
                switch (failure.reason()) {
                    case TIMEOUT -> messages.send(sender.id(),
                            "Your message was not delivered: moderation timed out.");
                    case OVERLOADED -> messages.send(sender.id(),
                            "Your message was not delivered: the moderation queue is full.");
                    case INVALID_REQUEST -> messages.send(sender.id(),
                            "Your message was not delivered because the request was invalid.");
                    default -> messages.send(sender.id(),
                            "Your message was not delivered: moderation is unavailable.");
                }
                return;
            }
            if (decision == null || decision.action() != ModerationAction.ALLOW) {
                String reason = decision == null || decision.playerReason().isBlank()
                        ? "Your message was not delivered because it violates the server rules."
                        : decision.playerReason();
                messages.send(sender.id(), reason + " Request ID: " + requestId);
                return;
            }
            scheduler.run(() -> deliverOnce(sender, target, originalMessage, requestId));
        });
    }

    private void deliverOnce(TellPlayer sender, TellPlayer target, String message, UUID requestId) {
        // Re-check availability and ignore state on the main thread. A player may
        // have disconnected after the command was issued.
        Optional<TellPlayer> onlineSender = directory.findOnline(sender.id());
        Optional<TellPlayer> onlineTarget = directory.findOnline(target.id());
        if (onlineSender.isEmpty() || onlineTarget.isEmpty()) {
            if (onlineSender.isPresent()) {
                messages.send(sender.id(), "Your message was not delivered because a player is no longer online.");
            }
            return;
        }
        if (!directory.hasPermission(sender.id(), PERMISSION)
                || !directory.hasPermission(target.id(), PERMISSION)) {
            messages.send(sender.id(), "Your message was not delivered because messaging permission changed.");
            return;
        }
        if (ignores.ignores(target.id(), sender.id())) {
            messages.send(sender.id(), "Your message was not delivered because the recipient is ignoring you.");
            return;
        }
        // The actual delivery is the messaging plugin's job; this is exactly once
        // per approved request.
        messages.send(target.id(), "[" + sender.name() + " -> you] " + message);
        messages.send(sender.id(), "[you -> " + target.name() + "] " + message);
        replyTargets.put(sender.id(), target.id());
    }

    public void close() {
        moderation.close();
    }
}
