package dev.cleanspeak.example.tell;

@FunctionalInterface
public interface MainThreadScheduler {
    void run(Runnable runnable);
}
