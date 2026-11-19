package dev.cleanspeak.core;

import dev.cleanspeak.api.ModerationAction;
import dev.cleanspeak.api.ModerationDecision;
import dev.cleanspeak.api.ModerationRequest;
import dev.cleanspeak.core.ml.MlInput;
import dev.cleanspeak.core.ml.MlResult;
import dev.cleanspeak.core.ml.MlSettings;
import dev.cleanspeak.core.ml.TextClassifier;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Shared moderation core. Local rules are always used; the model is optional.
 * The class never blocks the Paper main thread while waiting for a model.
 */
public final class ModerationService implements AutoCloseable {

    public interface DecisionSink {
        void onDecision(ModerationRequest request, ModerationDecision decision);
    }

    public interface AlertSink {
        void onInfrastructureAlert(String message);
    }

    private final RuleEngine ruleEngine;
    private final ConversationTracker tracker;
    private final TextClassifier classifier;
    private final MlSettings mlSettings;
    private final TextPolicy policy;
    private final ThreadPoolExecutor executor;
    private final long requestTimeoutMillis;
    private final DecisionSink decisionSink;
    private final AlertSink alertSink;
    private final java.util.function.BiFunction<ModerationRequest, ModerationDecision, ModerationDecision> decisionAdjuster;
    private final AtomicBoolean closed = new AtomicBoolean();

    private final AtomicLong submitted = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong timedOut = new AtomicLong();
    private final AtomicLong fallbacks = new AtomicLong();
    private volatile String lastMlStatus = "not-called";
    private volatile String lastMlDetail = "";
    private volatile long lastMlAtMillis;

    public ModerationService(
            RuleEngine ruleEngine,
            ConversationTracker tracker,
            TextClassifier classifier,
            MlSettings mlSettings,
            TextPolicy policy,
            int workers,
            int queueCapacity,
            long requestTimeoutMillis,
            DecisionSink decisionSink,
            AlertSink alertSink
    ) {
        this(ruleEngine, tracker, classifier, mlSettings, policy, workers, queueCapacity,
                requestTimeoutMillis, decisionSink, alertSink, null);
    }

    public ModerationService(
            RuleEngine ruleEngine,
            ConversationTracker tracker,
            TextClassifier classifier,
            MlSettings mlSettings,
            TextPolicy policy,
            int workers,
            int queueCapacity,
            long requestTimeoutMillis,
            DecisionSink decisionSink,
            AlertSink alertSink,
            java.util.function.BiFunction<ModerationRequest, ModerationDecision, ModerationDecision> decisionAdjuster
    ) {
        this.ruleEngine = Objects.requireNonNull(ruleEngine, "ruleEngine");
        this.tracker = Objects.requireNonNull(tracker, "tracker");
        this.classifier = Objects.requireNonNull(classifier, "classifier");
        this.mlSettings = Objects.requireNonNull(mlSettings, "mlSettings");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.requestTimeoutMillis = Math.max(100L, requestTimeoutMillis);
        this.decisionSink = decisionSink == null ? (request, decision) -> {
        } : decisionSink;
        this.alertSink = alertSink == null ? (AlertSink) ignored -> {
        } : alertSink;
        this.executor = new ThreadPoolExecutor(
                Math.max(1, workers),
                Math.max(1, workers),
                30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(Math.max(1, queueCapacity)),
                runnable -> {
                    Thread thread = new Thread(runnable, "CleanSpeak-moderation");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
        this.decisionAdjuster = decisionAdjuster == null
                ? (request, decision) -> decision
                : decisionAdjuster;
        this.executor.allowCoreThreadTimeOut(true);
    }

    public CompletableFuture<ModerationDecision> moderate(ModerationRequest request) {
        Objects.requireNonNull(request, "request");
        if (closed.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("CleanSpeak is shutting down"));
        }
        submitted.incrementAndGet();
        CompletableFuture<ModerationDecision> result = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try {
                    ModerationDecision decision = evaluate(request, true, false);
                    result.complete(decision);
                } catch (Throwable t) {
                    result.completeExceptionally(t);
                }
            });
        } catch (RejectedExecutionException e) {
            rejected.incrementAndGet();
            alertSink.onInfrastructureAlert("Moderation queue full; applying rules-only fallback.");
            ModerationDecision decision = markFallback(evaluate(request, true, true));
            result.complete(decision);
        }
        return result.orTimeout(requestTimeoutMillis, TimeUnit.MILLISECONDS)
                .whenComplete((decision, error) -> {
                    if (error != null && error instanceof java.util.concurrent.TimeoutException) {
                        timedOut.incrementAndGet();
                        alertSink.onInfrastructureAlert("Moderation request timed out; delivery must be rejected.");
                    }
                });
    }

    /**
     * Fast local path for synchronous pre-delivery events. No model, disk or
     * network call is made.
     */
    public ModerationDecision moderateRulesOnly(ModerationRequest request) {
        Objects.requireNonNull(request, "request");
        if (closed.get()) {
            throw new IllegalStateException("CleanSpeak is shutting down");
        }
        // The bundled classifier is local, already-loaded and fast, so it can
        // participate in the synchronous pre-delivery path. HTTP sidecars stay
        // out of this path and are only used by ASYNC_REPLAY / the async API.
        return evaluate(request, true, !classifier.inProcess());
    }

    /**
     * Runs the full check for observation only. The effective action is always
     * ALLOW; staff can evaluate the would-be result. This is used for ML shadow
     * mode on public chat without cancelling and replaying signed messages.
     */
    public void observeShadow(ModerationRequest request) {
        if (!mlSettings.enabled() || classifier.inProcess() || closed.get()) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    evaluate(request, false, false, true);
                } catch (Throwable ignored) {
                    // Observation must never affect delivery.
                }
            });
        } catch (RejectedExecutionException e) {
            rejected.incrementAndGet();
        }
    }

    public int queueDepth() {
        return executor.getQueue().size();
    }

    public int activeWorkers() {
        return executor.getActiveCount();
    }

    public long submittedCount() {
        return submitted.get();
    }

    public long rejectedCount() {
        return rejected.get();
    }

    public long timeoutCount() {
        return timedOut.get();
    }

    public long fallbackCount() {
        return fallbacks.get();
    }

    public String lastMlStatus() {
        return lastMlStatus;
    }

    public String lastMlDetail() {
        return lastMlDetail;
    }

    public TextClassifier classifier() {
        return classifier;
    }

    public MlSettings mlSettings() {
        return mlSettings;
    }

    public TextPolicy policy() {
        return policy;
    }

    public RuleEngine ruleEngine() {
        return ruleEngine;
    }

    public ConversationTracker tracker() {
        return tracker;
    }

    private ModerationDecision evaluate(ModerationRequest request, boolean recordContext, boolean rulesOnly) {
        return evaluate(request, recordContext, rulesOnly, false);
    }

    private ModerationDecision evaluate(
            ModerationRequest request,
            boolean recordContext,
            boolean rulesOnly,
            boolean observation
    ) {
        long now = System.currentTimeMillis();
        List<ConversationTracker.RecentMessage> recent = tracker.recent(request, now);
        RuleEvaluation evaluation = ruleEngine.evaluate(request, recent, now);
        // Context is recorded only after the decision. Messages that were not
        // delivered must not poison the next message in the conversation.
        // The record call is below after the policy decision.

        MlResult ml = MlResult.disabled();
        if (!rulesOnly && mlSettings.enabled()) {
            try {
                ml = classifier.classify(new MlInput(
                        request.message(),
                        request.channelId(),
                        request.conversationId(),
                        policy.policyVersion(),
                        mlSettings.reviewThresholds()
                ));
            } catch (Throwable t) {
                ml = MlResult.error(t.getClass().getSimpleName() + ": " + t.getMessage());
            }
            lastMlStatus = ml.status().name();
            lastMlDetail = ml.detail();
            lastMlAtMillis = System.currentTimeMillis();
            if (ml.status() != MlResult.Status.OK) {
                fallbacks.incrementAndGet();
            }
        }

        TextPolicy effectivePolicy = rulesOnly ? policy.withoutMl() : policy;
        ModerationDecision decision = new DecisionPolicy().decide(
                request,
                evaluation,
                ml,
                effectivePolicy,
                mlSettings,
                observation
        );
        try {
            decision = decisionAdjuster.apply(request, decision);
        } catch (Throwable ignored) {
            // Actions must never change the underlying rule/model result by
            // crashing. Commands and alerts are best-effort.
        }
        try {
            decisionSink.onDecision(request, decision);
        } catch (Throwable ignored) {
            // A storage/notification failure must not change the moderation
            // decision or block message delivery processing.
        }
        if (recordContext && decision.action() == ModerationAction.ALLOW) {
            tracker.record(request, evaluation.normalized().visible(), now);
        }
        return decision;
    }

    private static ModerationDecision markFallback(ModerationDecision decision) {
        if (decision.fallback()) {
            return decision;
        }
        return new ModerationDecision(
                decision.requestId(),
                decision.action(),
                decision.rawAction(),
                decision.shadowed(),
                decision.categories(),
                decision.contributions(),
                decision.staffReason() + " notes=moderation-queue-full",
                decision.playerReason(),
                decision.modelVersion(),
                decision.policyVersion(),
                true,
                decision.observation(),
                decision.channelId(),
                decision.conversationId(),
                decision.createdAtEpochMillis()
        );
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        executor.shutdownNow();
        try {
            classifier.close();
        } catch (Throwable ignored) {
        }
        tracker.clear();
    }
}
