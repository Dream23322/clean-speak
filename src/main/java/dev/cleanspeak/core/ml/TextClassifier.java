package dev.cleanspeak.core.ml;

/**
 * Replaceable model interface. Implementations must be safe to call from a
 * bounded worker, must enforce their own network timeout and must never block
 * the Paper main thread.
 */
public interface TextClassifier extends AutoCloseable {

    boolean enabled();

    String version();

    MlResult classify(MlInput input);

    String status();

    boolean available();

    /** True when inference is local, fast and safe to run on the chat event thread. */
    default boolean inProcess() {
        return false;
    }

    @Override
    default void close() {
    }
}
