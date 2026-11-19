package dev.cleanspeak;

import dev.cleanspeak.api.AdapterRegistration;
import dev.cleanspeak.api.Category;
import dev.cleanspeak.api.CleanSpeakApi;
import dev.cleanspeak.api.HealthSnapshot;
import dev.cleanspeak.api.ModerationAction;
import dev.cleanspeak.api.ModerationDecision;
import dev.cleanspeak.api.ModerationException;
import dev.cleanspeak.api.ModerationRequest;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bukkit service implementation. The API remains valid across configuration
 * reloads; it delegates to the runtime's current immutable state.
 */
public final class CleanSpeakApiImpl implements CleanSpeakApi {

    private final CleanSpeakRuntime runtime;
    private final Map<String, AdapterRegistration> adapters = new ConcurrentHashMap<>();

    CleanSpeakApiImpl(CleanSpeakRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public CompletableFuture<ModerationDecision> moderate(ModerationRequest request) {
        if (request == null) {
            return CompletableFuture.failedFuture(ModerationException.invalid("request is null"));
        }
        CleanSpeakRuntime.State state = runtime.state();
        if (state == null) {
            return CompletableFuture.failedFuture(ModerationException.shutdown());
        }
        if (!state.config.text.enabled) {
            return CompletableFuture.completedFuture(allow(request, state));
        }
        final CompletableFuture<ModerationDecision> moderation;
        try {
            moderation = state.service.moderate(request);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(ModerationException.from(e));
        }
        return moderation.handle((decision, error) -> {
            if (error != null) {
                throw ModerationException.from(error);
            }
            return decision;
        });
    }

    @Override
    public ModerationDecision moderateSync(ModerationRequest request) {
        if (request == null) {
            throw ModerationException.invalid("request is null");
        }
        CleanSpeakRuntime.State state = runtime.state();
        if (state == null) {
            throw ModerationException.shutdown();
        }
        if (!state.config.text.enabled) {
            return allow(request, state);
        }
        try {
            return state.service.moderateRulesOnly(request);
        } catch (ModerationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw ModerationException.from(e);
        }
    }

    @Override
    public void registerAdapter(AdapterRegistration registration) {
        if (registration == null || registration.id().isBlank()) {
            throw new IllegalArgumentException("adapter id is required");
        }
        adapters.put(registration.id(), registration);
    }

    @Override
    public HealthSnapshot health() {
        return runtime.health();
    }

    Map<String, AdapterRegistration> adapters() {
        return Map.copyOf(adapters);
    }

    private static ModerationDecision allow(ModerationRequest request, CleanSpeakRuntime.State state) {
        return new ModerationDecision(
                request.requestId(),
                ModerationAction.ALLOW,
                ModerationAction.ALLOW,
                false,
                Set.<Category>of(),
                List.of(),
                "text moderation disabled in config",
                "",
                "rules-only",
                state.config.policyVersion,
                false,
                false,
                request.channelId(),
                request.conversationId(),
                System.currentTimeMillis()
        );
    }
}
