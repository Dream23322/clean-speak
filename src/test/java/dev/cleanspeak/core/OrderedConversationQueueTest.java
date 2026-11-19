package dev.cleanspeak.core;

import dev.cleanspeak.api.Category;
import dev.cleanspeak.api.MessageAudience;
import dev.cleanspeak.api.ModerationAction;
import dev.cleanspeak.api.ModerationDecision;
import dev.cleanspeak.api.ModerationRequest;
import dev.cleanspeak.api.OrderedConversationQueue;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class OrderedConversationQueueTest {

    @Test
    void preservesOrderAndRunsDifferentConversationsIndependently() {
        OrderedConversationQueue queue = new OrderedConversationQueue(8, 100);
        CompletableFuture<ModerationDecision> firstTask = new CompletableFuture<>();
        CompletableFuture<ModerationDecision> secondTask = new CompletableFuture<>();
        List<String> callbacks = new ArrayList<>();

        CompletableFuture<ModerationDecision> first = queue.submit("a", () -> firstTask);
        CompletableFuture<ModerationDecision> second = queue.submit("a", () -> secondTask);
        queue.submit("b", () -> CompletableFuture.completedFuture(decision("other")))
                .thenAccept(d -> callbacks.add("other"));
        first.thenAccept(d -> callbacks.add("first"));
        second.thenAccept(d -> callbacks.add("second"));

        assertTrue(firstTask.isDone() == false);
        assertTrue(callbacks.contains("other"));
        firstTask.complete(decision("a1"));
        assertTrue(first.isDone());
        assertFalse(second.isDone());
        secondTask.complete(decision("a2"));
        await(() -> callbacks.size() >= 3);
        assertEquals("first", callbacks.get(1));
        assertEquals("second", callbacks.get(2));
    }

    @Test
    void rejectsTooManyPendingMessagesInOneConversation() {
        OrderedConversationQueue queue = new OrderedConversationQueue(1, 10);
        CompletableFuture<ModerationDecision> firstTask = new CompletableFuture<>();
        queue.submit("a", () -> firstTask);
        CompletableFuture<ModerationDecision> second = queue.submit("a", () -> CompletableFuture.completedFuture(decision("second")));
        await(() -> second.isCompletedExceptionally());
        firstTask.complete(decision("done"));
    }

    @Test
    void timedOutTaskStillAllowsNextTaskInOrder() {
        OrderedConversationQueue queue = new OrderedConversationQueue(4, 10);
        CompletableFuture<ModerationDecision> firstTask = new CompletableFuture<>();
        CompletableFuture<ModerationDecision> secondTask = new CompletableFuture<>();
        CompletableFuture<ModerationDecision> first = queue.submit("a",
                () -> firstTask.orTimeout(80, TimeUnit.MILLISECONDS));
        CompletableFuture<ModerationDecision> second = queue.submit("a", () -> secondTask);
        await(() -> first.isCompletedExceptionally());
        secondTask.complete(decision("second"));
        await(() -> second.isDone() && !second.isCompletedExceptionally());
    }

    private static void await(java.util.function.BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 2_000L;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(10L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        fail("condition not met in time");
    }

    private static ModerationDecision decision(String id) {
        ModerationRequest request = ModerationRequest.builder()
                .requestId(UUID.nameUUIDFromBytes(id.getBytes()))
                .sender(UUID.randomUUID(), "Tester")
                .audience(MessageAudience.PRIVATE_MESSAGE)
                .channelId("test")
                .conversationId("test")
                .message("hi")
                .build();
        return new ModerationDecision(request.requestId(), ModerationAction.ALLOW, ModerationAction.ALLOW,
                false, java.util.Set.<Category>of(), List.of(), "ok", "", "rules", "test",
                false, false, "test", "test", System.currentTimeMillis());
    }
}
