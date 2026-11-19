package dev.cleanspeak.api;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiHelpersTest {

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void privateMessagesShareOneConversationInBothDirections() {
        ModerationRequest outbound = ModerationRequests.privateMessage(
                ALICE, "Alice", BOB, "test:pm", "hello");
        ModerationRequest inbound = ModerationRequests.privateMessage(
                BOB, "Bob", ALICE, "test:pm", "hi");

        assertEquals(outbound.conversationId(), inbound.conversationId());
        assertEquals(MessageAudience.PRIVATE_MESSAGE, outbound.audience());
        assertEquals(List.of(BOB), outbound.recipientIds());
        assertFalse(outbound.requestId().equals(inbound.requestId()));
    }

    @Test
    void publicChatUsesAStablePublicConversation() {
        ModerationRequest request = ModerationRequests.publicChat(ALICE, "Alice", "test:global", "hello");

        assertEquals(ConversationIds.publicChat("test:global"), request.conversationId());
        assertEquals(MessageAudience.PUBLIC_CHAT, request.audience());
        assertTrue(request.isPublicChat());
    }

    @Test
    void builderRequiresChannelAndConversation() {
        ModerationRequest.Builder base = ModerationRequest.builder()
                .sender(ALICE, "Alice")
                .message("hello");
        assertThrows(NullPointerException.class, base::build);

        ModerationRequest.Builder noConversation = ModerationRequest.builder()
                .sender(ALICE, "Alice")
                .channelId("test")
                .message("hello");
        assertThrows(NullPointerException.class, noConversation::build);
    }

    @Test
    void queueRejectionIsAFailedFutureAndMapsToOverloaded() {
        OrderedConversationQueue queue = new OrderedConversationQueue(1, 1);
        CompletableFuture<ModerationDecision> running = queue.submit(
                "a", () -> new CompletableFuture<>());
        assertFalse(running.isDone());

        CompletableFuture<ModerationDecision> rejected = queue.submit(
                "b", () -> CompletableFuture.completedFuture(null));
        Throwable error = assertInstanceOf(
                java.util.concurrent.CompletionException.class,
                assertThrows(Exception.class, rejected::join)).getCause();
        assertInstanceOf(RejectedExecutionException.class, error);
        assertEquals(ModerationException.Reason.OVERLOADED, ModerationException.from(error).reason());
    }

    @Test
    void sessionMapsTimeoutToTypedFailure() throws Exception {
        CleanSpeakApi api = new FakeApi(request ->
                new CompletableFuture<>()); // never completes
        try (ModerationSession session = ModerationSession.builder(api)
                .timeout(Duration.ofMillis(50))
                .build()) {
            ModerationRequest request = ModerationRequests.privateMessage(
                    ALICE, "Alice", BOB, "test:pm", "hello");
            Throwable error = assertThrows(Exception.class, () -> session.submit(request).join());
            assertEquals(ModerationException.Reason.TIMEOUT, ModerationException.from(error).reason());
        }
    }

    @Test
    void sessionRejectsAfterClose() {
        ModerationSession session = ModerationSession.builder(new FakeApi(
                request -> CompletableFuture.completedFuture(null))).build();
        session.close();
        assertTrue(session.isClosed());
        ModerationRequest request = ModerationRequests.publicChat(ALICE, "Alice", "test", "hello");
        Throwable error = assertThrows(Exception.class, () -> session.submit(request).join());
        assertEquals(ModerationException.Reason.SHUTDOWN, ModerationException.from(error).reason());
    }

    private static final class FakeApi implements CleanSpeakApi {
        private final java.util.function.Function<ModerationRequest, CompletableFuture<ModerationDecision>> responder;

        private FakeApi(java.util.function.Function<ModerationRequest, CompletableFuture<ModerationDecision>> responder) {
            this.responder = responder;
        }

        @Override
        public CompletableFuture<ModerationDecision> moderate(ModerationRequest request) {
            return responder.apply(request);
        }

        @Override
        public ModerationDecision moderateSync(ModerationRequest request) {
            return null;
        }

        @Override
        public void registerAdapter(AdapterRegistration registration) {
        }

        @Override
        public HealthSnapshot health() {
            return new HealthSnapshot(false, false, false, false, "test", "test",
                    0, java.util.Map.of(), 0L);
        }
    }
}
