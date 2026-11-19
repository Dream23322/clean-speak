package dev.cleanspeak.example.tell;

import dev.cleanspeak.api.AdapterRegistration;
import dev.cleanspeak.api.CleanSpeakApi;
import dev.cleanspeak.api.HealthSnapshot;
import dev.cleanspeak.api.Category;
import dev.cleanspeak.api.ModerationAction;
import dev.cleanspeak.api.ModerationDecision;
import dev.cleanspeak.api.ModerationRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class TellServiceTest {

    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();

    private final FakeDirectory directory = new FakeDirectory();
    private final FakeIgnores ignores = new FakeIgnores();
    private final List<Message> messages = new ArrayList<>();
    private final FakeApi api = new FakeApi();
    private final TellService service = new TellService(api, directory, ignores,
            (id, text) -> messages.add(new Message(id, text)), Runnable::run, 4, 100, 500);

    @Test
    void allowedMessageIsDeliveredToOneRecipientAndSenderExactlyOnce() {
        directory.add(ALICE, "Alice");
        directory.add(BOB, "Bob");
        api.responder = request -> CompletableFuture.completedFuture(decision(request, ModerationAction.ALLOW));

        service.tell(player(ALICE, "Alice"), "Bob", "hello Bob");

        assertEquals(2, messages.size());
        assertTrue(messages.stream().anyMatch(m -> m.player.equals(BOB) && m.text.contains("hello Bob")));
        assertTrue(messages.stream().anyMatch(m -> m.player.equals(ALICE) && m.text.contains("hello Bob")));
    }

    @Test
    void blockedMessageIsNeverDelivered() {
        directory.add(ALICE, "Alice");
        directory.add(BOB, "Bob");
        api.responder = request -> CompletableFuture.completedFuture(decision(request, ModerationAction.REPHRASE));

        service.tell(player(ALICE, "Alice"), "Bob", "bad text");

        assertTrue(messages.stream().noneMatch(m -> m.player.equals(BOB)));
        assertTrue(messages.stream().anyMatch(m -> m.player.equals(ALICE)
                && m.text.contains("not delivered") && m.text.contains("Request ID")));
    }

    @Test
    void timedOutMessageIsNotDeliveredAndLateResultIsIgnored() {
        directory.add(ALICE, "Alice");
        directory.add(BOB, "Bob");
        CompletableFuture<ModerationDecision> pending = new CompletableFuture<>();
        api.responder = request -> pending;

        service.tell(player(ALICE, "Alice"), "Bob", "late text");
        await(() -> messages.stream().anyMatch(m -> m.player.equals(ALICE) && m.text.contains("timed out")));
        pending.complete(decision(UUID.randomUUID(), ModerationAction.ALLOW));
        assertTrue(messages.stream().noneMatch(m -> m.player.equals(BOB)));
    }

    @Test
    void recipientDisconnectBeforeDeliveryPreventsDelivery() {
        directory.add(ALICE, "Alice");
        directory.add(BOB, "Bob");
        CompletableFuture<ModerationDecision> pending = new CompletableFuture<>();
        api.responder = request -> pending;
        service.tell(player(ALICE, "Alice"), "Bob", "hello");
        directory.remove(BOB);
        pending.complete(decision(UUID.randomUUID(), ModerationAction.ALLOW));
        assertTrue(messages.stream().noneMatch(m -> m.player.equals(BOB)));
        assertTrue(messages.stream().anyMatch(m -> m.player.equals(ALICE) && m.text.contains("no longer online")));
    }

    @Test
    void replyTargetIsSetOnlyAfterSuccessfulDelivery() {
        directory.add(ALICE, "Alice");
        directory.add(BOB, "Bob");
        api.responder = request -> CompletableFuture.completedFuture(decision(request, ModerationAction.REPHRASE));
        service.tell(player(ALICE, "Alice"), "Bob", "blocked");
        service.reply(player(ALICE, "Alice"), "hello again");
        assertTrue(messages.stream().anyMatch(m -> m.text.contains("no reply target")));

        api.responder = request -> CompletableFuture.completedFuture(decision(request, ModerationAction.ALLOW));
        service.tell(player(ALICE, "Alice"), "Bob", "allowed");
        service.reply(player(ALICE, "Alice"), "reply now");
        assertTrue(messages.stream().anyMatch(m -> m.player.equals(BOB) && m.text.contains("reply now")));
    }

    @Test
    void ignoreListIsCheckedBeforeAndAfterModeration() {
        directory.add(ALICE, "Alice");
        directory.add(BOB, "Bob");
        ignores.ignore(BOB, ALICE);
        service.tell(player(ALICE, "Alice"), "Bob", "hello");
        assertTrue(messages.stream().noneMatch(m -> m.player.equals(BOB)));

        ignores.unignore(BOB, ALICE);
        CompletableFuture<ModerationDecision> pending = new CompletableFuture<>();
        api.responder = request -> pending;
        service.tell(player(ALICE, "Alice"), "Bob", "hello again");
        ignores.ignore(BOB, ALICE);
        pending.complete(decision(UUID.randomUUID(), ModerationAction.ALLOW));
        assertTrue(messages.stream().noneMatch(m -> m.player.equals(BOB)));
    }

    @Test
    void messagesAreOrderedPerConversation() {
        directory.add(ALICE, "Alice");
        directory.add(BOB, "Bob");
        List<CompletableFuture<ModerationDecision>> pending = new ArrayList<>();
        api.responder = request -> {
            CompletableFuture<ModerationDecision> future = new CompletableFuture<>();
            pending.add(future);
            return future;
        };
        service.tell(player(ALICE, "Alice"), "Bob", "one");
        service.tell(player(ALICE, "Alice"), "Bob", "two");
        assertEquals(1, pending.size(), "second request must wait for the first per conversation");
        pending.get(0).complete(decision(UUID.randomUUID(), ModerationAction.ALLOW));
        assertEquals(2, pending.size());
        pending.get(1).complete(decision(UUID.randomUUID(), ModerationAction.ALLOW));

        List<String> toBob = messages.stream().filter(m -> m.player.equals(BOB)).map(Message::text).toList();
        assertEquals(2, toBob.size());
        assertTrue(toBob.get(0).contains("one"));
        assertTrue(toBob.get(1).contains("two"));
    }

    @Test
    void selfAndOfflineAreRejected() {
        directory.add(ALICE, "Alice");
        service.tell(player(ALICE, "Alice"), "Alice", "hi");
        service.tell(player(ALICE, "Alice"), "Nobody", "hi");
        assertTrue(messages.stream().anyMatch(m -> m.text.contains("yourself")));
        assertTrue(messages.stream().anyMatch(m -> m.text.contains("not online")));
        assertEquals(0, api.calls.get());
    }

    @Test
    void parserRecognisesAliasesAndRejectsIncompleteCommands() {
        assertEquals(Optional.of(TellCommandParser.Kind.TELL), TellCommandParser.kind("msg"));
        assertEquals(Optional.of(TellCommandParser.Kind.TELL), TellCommandParser.kind("etell"));
        assertEquals(Optional.of(TellCommandParser.Kind.REPLY), TellCommandParser.kind("r"));
        assertEquals(TellCommandParser.parseTell(new String[]{"Bob", "hello", "there"}).message(), "hello there");
        assertNull(TellCommandParser.parseTell(new String[]{"Bob"}));
        assertEquals("hello there", TellCommandParser.parseReply(new String[]{"hello", "there"}));
    }

    private static TellPlayer player(UUID id, String name) {
        return new TellPlayer(id, name);
    }

    private static ModerationDecision decision(ModerationRequest request, ModerationAction action) {
        String playerReason = action == ModerationAction.ALLOW ? "" : "Your message was not delivered.";
        return new ModerationDecision(request.requestId(), action, action, false,
                Set.of(Category.HATE_SLUR), List.of(), "test", playerReason, "test", "test",
                false, false, request.channelId(), request.conversationId(), System.currentTimeMillis());
    }

    private static ModerationDecision decision(UUID requestId, ModerationAction action) {
        return new ModerationDecision(requestId, action, action, false,
                Set.of(Category.HATE_SLUR), List.of(), "test",
                action == ModerationAction.ALLOW ? "" : "not delivered", "test", "test",
                false, false, "test", "test", System.currentTimeMillis());
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

    private record Message(UUID player, String text) {
    }

    private static final class FakeDirectory implements PlayerDirectory {
        private final Map<UUID, TellPlayer> players = new HashMap<>();

        private void add(UUID id, String name) {
            players.put(id, new TellPlayer(id, name));
        }

        private void remove(UUID id) {
            players.remove(id);
        }

        @Override
        public Optional<TellPlayer> findOnline(String name) {
            return players.values().stream().filter(p -> p.name().equalsIgnoreCase(name)).findFirst();
        }

        @Override
        public Optional<TellPlayer> findOnline(UUID id) {
            return Optional.ofNullable(players.get(id));
        }

        @Override
        public boolean hasPermission(UUID playerId, String permission) {
            return players.containsKey(playerId);
        }
    }

    private static final class FakeIgnores implements IgnoreStore {
        private final Map<UUID, Set<UUID>> values = new HashMap<>();

        @Override
        public boolean ignores(UUID ownerId, UUID senderId) {
            return values.getOrDefault(ownerId, Set.of()).contains(senderId);
        }

        @Override
        public void ignore(UUID ownerId, UUID ignoredId) {
            values.computeIfAbsent(ownerId, ignored -> new HashSet<>()).add(ignoredId);
        }

        @Override
        public void unignore(UUID ownerId, UUID ignoredId) {
            Set<UUID> set = values.get(ownerId);
            if (set != null) {
                set.remove(ignoredId);
            }
        }

        @Override
        public List<UUID> ignored(UUID ownerId) {
            return List.copyOf(values.getOrDefault(ownerId, Set.of()));
        }
    }

    private static final class FakeApi implements CleanSpeakApi {
        private final AtomicInteger calls = new AtomicInteger();
        private volatile Function<ModerationRequest, CompletableFuture<ModerationDecision>> responder =
                request -> CompletableFuture.completedFuture(decision(request, ModerationAction.ALLOW));

        @Override
        public CompletableFuture<ModerationDecision> moderate(ModerationRequest request) {
            calls.incrementAndGet();
            return responder.apply(request);
        }

        @Override
        public ModerationDecision moderateSync(ModerationRequest request) {
            return decision(request, ModerationAction.ALLOW);
        }

        @Override
        public void registerAdapter(AdapterRegistration registration) {
        }

        @Override
        public HealthSnapshot health() {
            return new HealthSnapshot(false, true, false, false, "test", "test",
                    0, Map.of(), 0);
        }
    }
}
