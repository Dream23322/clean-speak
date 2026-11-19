package dev.cleanspeak.api;

import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * Typed failure raised by the CleanSpeak API.
 *
 * <p>All asynchronous failures returned by {@link CleanSpeakApi} and
 * {@link ModerationSession} are reported as a {@code ModerationException},
 * possibly wrapped in a {@link CompletionException}. Use {@link #from(Throwable)}
 * when handling a raw {@link Throwable} from {@code join()} or
 * {@code whenComplete}.</p>
 *
 * <p>Callers must treat every failure as "not delivered". A late decision after
 * a timeout must be ignored.</p>
 */
public final class ModerationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Machine-readable failure reasons.
     */
    public enum Reason {
        /** The request exceeded the caller or service timeout. */
        TIMEOUT,
        /** CleanSpeak is not ready, is reloading or is shutting down. */
        SHUTDOWN,
        /** A bounded queue rejected the request. The message was not checked. */
        OVERLOADED,
        /** The request failed validation before it reached the moderation core. */
        INVALID_REQUEST,
        /** An unexpected internal error occurred. */
        INTERNAL
    }

    /** Failure reason. */
    private final Reason reason;

    /**
     * Creates a failure.
     *
     * @param reason non-null failure reason
     * @param message human-readable detail safe to log; must not contain raw
     *                player text
     */
    public ModerationException(Reason reason, String message) {
        this(reason, message, null);
    }

    /**
     * Creates a failure with a cause.
     *
     * @param reason non-null failure reason
     * @param message human-readable detail safe to log; must not contain raw
     *                player text
     * @param cause underlying failure, or {@code null}
     */
    public ModerationException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    /**
     * Returns the failure reason.
     *
     * @return the reason
     */
    public Reason reason() {
        return reason;
    }

    /**
     * Creates a timeout failure.
     *
     * @return a new exception
     */
    public static ModerationException timeout() {
        return new ModerationException(Reason.TIMEOUT,
                "moderation did not finish before the delivery deadline");
    }

    /**
     * Creates a shutdown failure.
     *
     * @return a new exception
     */
    public static ModerationException shutdown() {
        return new ModerationException(Reason.SHUTDOWN,
                "CleanSpeak is not ready or is shutting down");
    }

    /**
     * Creates an overload failure.
     *
     * @return a new exception
     */
    public static ModerationException overloaded() {
        return new ModerationException(Reason.OVERLOADED,
                "moderation queue is full; the message was not checked");
    }

    /**
     * Creates an invalid-request failure.
     *
     * @param detail validation detail; must not contain raw player text
     * @return a new exception
     */
    public static ModerationException invalid(String detail) {
        return new ModerationException(Reason.INVALID_REQUEST,
                "invalid moderation request: " + detail);
    }

    /**
     * Creates an internal failure.
     *
     * @param cause underlying failure
     * @return a new exception
     */
    public static ModerationException internal(Throwable cause) {
        String detail = cause == null ? "unknown" : cause.getClass().getSimpleName();
        return new ModerationException(Reason.INTERNAL,
                "moderation failed internally (" + detail + ")", cause);
    }

    /**
     * Converts an arbitrary completion failure into a {@code ModerationException}.
     *
     * <p>Already-typed failures are returned unchanged. Wrapper exceptions such
     * as {@link CompletionException} and {@link ExecutionException} are
     * unwrapped. Unknown failures become {@link Reason#INTERNAL}.</p>
     *
     * @param error failure thrown by a future
     * @return a typed failure, never {@code null}
     */
    public static ModerationException from(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        if (current instanceof ModerationException typed) {
            return typed;
        }
        if (current instanceof TimeoutException) {
            return timeout();
        }
        if (current instanceof RejectedExecutionException) {
            return overloaded();
        }
        if (current instanceof IllegalArgumentException || current instanceof NullPointerException) {
            return invalid(current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage());
        }
        if (current instanceof IllegalStateException) {
            String detail = current.getMessage();
            if (detail != null && (detail.contains("shut") || detail.contains("not ready"))) {
                return new ModerationException(Reason.SHUTDOWN, detail, current);
            }
        }
        return internal(current);
    }
}
