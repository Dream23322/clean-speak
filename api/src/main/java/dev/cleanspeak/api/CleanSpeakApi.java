package dev.cleanspeak.api;

import java.util.concurrent.CompletableFuture;

/**
 * Public CleanSpeak moderation API.
 *
 * <h2>Obtaining the API</h2>
 *
 * <p>CleanSpeak registers this service when it enables. Declare CleanSpeak as a
 * {@code depend} (or {@code softdepend}) in your {@code plugin.yml}, then look
 * the service up after both plugins are enabled:</p>
 *
 * <pre>{@code
 * RegisteredServiceProvider<CleanSpeakApi> registration =
 *         Bukkit.getServicesManager().getRegistration(CleanSpeakApi.class);
 * if (registration == null || registration.getProvider() == null) {
 *     // CleanSpeak is missing or failed to enable. Do not pretend the route
 *     // is protected; disable the route or use an explicit safe fallback.
 *     return;
 * }
 * CleanSpeakApi api = registration.getProvider();
 * }</pre>
 *
 * <p>The API object remains valid across {@code /cs reload}; it delegates to the
 * current runtime state. It is unregistered when CleanSpeak disables.</p>
 *
 * <h2>Delivery contract</h2>
 *
 * <p>The caller resolves recipients, checks permissions and ignore lists,
 * handles reply targets, applies a delivery deadline, and delivers the original
 * message exactly once only for {@link ModerationAction#ALLOW}. Never deliver a
 * rejected, failed or timed-out message. Never use a decision to ban or kick a
 * player.</p>
 *
 * <h2>Threading</h2>
 *
 * <p>{@link #moderate(ModerationRequest)} may be called from any thread and
 * returns immediately. It never waits for a model. Completion callbacks may run
 * on a CleanSpeak worker thread, or on the calling thread when text moderation
 * is disabled, so never assume the main thread in a callback. Schedule Bukkit
 * work explicitly.</p>
 *
 * <p>{@link #moderateSync(ModerationRequest)} runs on the calling thread and is
 * intended for synchronous events. Do not use it on a latency-sensitive main
 * thread with an enormous custom rule set.</p>
 *
 * <h2>Related helpers</h2>
 *
 * <ul>
 *   <li>{@link ModerationRequests} builds correctly isolated requests.</li>
 *   <li>{@link ModerationSession} adds per-conversation ordering and typed
 *       timeouts.</li>
 *   <li>{@link ModerationException} describes failures.</li>
 * </ul>
 */
public interface CleanSpeakApi {

    /** Current public API contract version. */
    String API_VERSION = "1.0";

    /**
     * Runs local rules and, if configured, the optional classifier on a bounded
     * asynchronous worker.
     *
     * <p>The returned future completes with a decision, or exceptionally. Use
     * {@link ModerationException#from(Throwable)} to turn completion failures
     * into a typed reason. A timed-out or failed request must be treated as not
     * delivered, and a late result must be ignored.</p>
     *
     * <p>The caller should apply its own timeout; {@link ModerationSession}
     * does this automatically.</p>
     *
     * @param request non-null request built with {@link ModerationRequest} or
     *                {@link ModerationRequests}
     * @return a future that completes with the decision
     */
    CompletableFuture<ModerationDecision> moderate(ModerationRequest request);

    /**
     * Runs the moderation checks synchronously on the calling thread.
     *
     * <p>This is the path for a cancellable, pre-delivery event that cannot take
     * an asynchronous decision. It never performs network or disk I/O. An
     * in-process bundled classifier may run; a remote HTTP classifier does not.
     * The returned decision still respects shadow mode.</p>
     *
     * <p>If CleanSpeak is shutting down, this method throws
     * {@link ModerationException}. Validation failures are also reported as
     * {@link ModerationException}.</p>
     *
     * @param request non-null request
     * @return the decision for immediate pre-delivery use
     */
    ModerationDecision moderateSync(ModerationRequest request);

    /**
     * Registers informational adapter details for staff status output.
     *
     * <p>This does not alter routing and does not make an unintegrated route
     * protected. Call it once during your plugin's enable when you actually own
     * a pre-delivery path.</p>
     *
     * @param registration non-null adapter details
     */
    void registerAdapter(AdapterRegistration registration);

    /**
     * Returns a point-in-time health snapshot.
     *
     * <p>Safe to call from any thread. Useful for integration diagnostics and
     * staff output, not for changing delivery decisions.</p>
     *
     * @return a snapshot; never {@code null}
     */
    HealthSnapshot health();

    /**
     * Returns the API contract version.
     *
     * @return {@link #API_VERSION}
     */
    default String apiVersion() {
        return API_VERSION;
    }
}
