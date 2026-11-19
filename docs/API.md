# CleanSpeak moderation API

API version **1.0**. This document is the integration guide for plugin authors.
It covers build setup, the full call path, delivery rules, threading, failures,
ordering and testing.

The API lives in the standalone `dev.cleanspeak.api` package. It has no runtime
dependencies. The CleanSpeak plugin jar embeds those classes, so operators still
install one jar; developers can compile against either the API artifact or the
plugin jar.

The working example is [`example-tell/`](../example-tell/). Its
[`TellService`](../example-tell/src/main/java/dev/cleanspeak/example/tell/TellService.java)
is a complete async `/tell` integration.

## 1. Add the dependency

Publish the API module to your local Maven repository:

```sh
./gradlew :api:publishToMavenLocal
```

Then compile against it. Do **not** bundle it into your jar.

### Gradle

```kotlin
repositories {
    mavenLocal()
}

dependencies {
    compileOnly("dev.cleanspeak:cleanspeak-api:1.0.0")
}
```

### Maven

```xml
<dependency>
  <groupId>dev.cleanspeak</groupId>
  <artifactId>cleanspeak-api</artifactId>
  <version>1.0.0</version>
  <scope>provided</scope>
</dependency>
```

If you do not have a repository, compile against the plugin jar directly:

```kotlin
dependencies {
    compileOnly(files("libs/CleanSpeak-1.0.0.jar"))
}
```

`api/build/libs/cleanspeak-api-1.0.0.jar` also contains only the API classes.
The API module publishes sources and Javadoc jars alongside the binary.

Do not shade Paper, EssentialsX or CleanSpeak into your plugin.

## 2. Declare the plugin dependency

In your `plugin.yml`:

```yaml
depend: [CleanSpeak]
```

Use `softdepend` only if you have a safe fallback. When CleanSpeak is required
but missing, disable the moderated route. Do not silently deliver messages while
claiming they were checked.

```yaml
softdepend: [CleanSpeak]
```

## 3. Look up the API

CleanSpeak registers a Bukkit service while enabling:

```java
RegisteredServiceProvider<CleanSpeakApi> registration =
        Bukkit.getServicesManager().getRegistration(CleanSpeakApi.class);
if (registration == null || registration.getProvider() == null) {
    getLogger().severe("CleanSpeak API is unavailable; disabling moderated messaging.");
    getServer().getPluginManager().disablePlugin(this);
    return;
}
CleanSpeakApi api = registration.getProvider();
```

The API object remains valid across `/cs reload`. It is unregistered when
CleanSpeak disables. Check `health()` if you need to show integration status to
staff.

## 4. Quick start

This is the complete happy path for a private message:

```java
// Build once, when your plugin enables.
ModerationSession moderation = ModerationSession.builder(api)
        .maxPendingPerConversation(8)
        .maxConversations(2000)
        .timeout(Duration.ofSeconds(5))
        .build();

// Build a request per message attempt.
ModerationRequest request = ModerationRequests.privateMessage(
        sender.getUniqueId(),
        sender.getName(),
        recipient.getUniqueId(),
        "myplugin:pm",
        originalMessage);

// Callbacks have no guaranteed thread. Hop to the main thread before
// touching players, for both notifications and delivery.
moderation.submit(request).whenComplete((decision, error) -> {
    Bukkit.getScheduler().runTask(plugin, () -> {
        if (!sender.isOnline()) {
            return;
        }
        if (error != null) {
            ModerationException failure = ModerationException.from(error);
            // Never deliver. failure.reason() is TIMEOUT, OVERLOADED, SHUTDOWN, ...
            sender.sendMessage("Your message was not delivered: moderation unavailable.");
            return;
        }
        if (decision.action() != ModerationAction.ALLOW) {
            // Never deliver. playerReason() is short and player-safe.
            sender.sendMessage(decision.playerReason());
            return;
        }
        if (!recipient.isOnline()) {
            return;
        }
        recipient.sendMessage(originalMessage);
    });
});
```

What the snippet deliberately leaves to you:

- recipient resolution, permissions, ignore lists and reply targets;
- your own duplicate guard if the same attempt can be submitted twice;
- re-checking online state on the main thread;
- player notifications for every failure path.

## 5. Building a request

Prefer the factories. They prevent the three most common integration bugs:
missing channel ids, shared conversation ids and direction-dependent ids.

```java
ModerationRequests.privateMessage(senderId, senderName, recipientId, channelId, message);
ModerationRequests.publicChat(senderId, senderName, channelId, message);
ModerationRequests.groupMessage(senderId, senderName, recipientIds, channelId, conversationId, message);
```

Each factory generates a random request id. Overloads accept an explicit
`requestId` when a retry must keep the same id.

Manual requests are still supported:

```java
ModerationRequest request = ModerationRequest.builder()
        .requestId(UUID.randomUUID())
        .sender(senderUuid, senderName)
        .audience(MessageAudience.PRIVATE_MESSAGE)
        .recipient(recipientUuid)
        .channelId("myplugin:pm")
        .conversationId(ConversationIds.privatePair("myplugin:pm", senderUuid, recipientUuid))
        .message(originalMessage)
        .build();
```

`channelId` and `conversationId` are **required** and have no fallback. Omitting
either fails at `build()` instead of silently merging unrelated conversations.

| Field | Notes |
|---|---|
| `requestId` | Generated by default. Stable for one attempt; use it in staff output. |
| `senderId` / `senderName` | Sender identity. Name may be empty. |
| `audience` | Routing hint for staff output. Does not resolve recipients. |
| `recipientIds` | Intended recipients. The core does not resolve them. |
| `channelId` | Stable route id, e.g. `myplugin:pm`. Required. |
| `conversationId` | Isolation id. Required. See below. |
| `message` | Original text. Never modified. Maximum 2000 characters. |
| `createdAtEpochMillis` | Optional; defaults to now. |

### Conversation ids

Context, split-message checks, velocity checks and queue ordering are keyed by
`channelId + conversationId + senderId`. Unrelated conversations must not share
an id.

```java
// Same id in both directions:
String pm = ConversationIds.privatePair("myplugin:pm", alice, bob);

// One id for a public channel:
String global = ConversationIds.publicChat("myplugin:global");

// Group/room: use an id that is unique to the room.
String room = "myplugin:room:" + roomId;
```

Do not reuse one conversation id for messages to different people, and do not
derive it only from the sender.

## 6. Async and synchronous paths

### `moderate(request)` — asynchronous, preferred

Returns immediately and never waits for a model. Local rules and (if configured)
the classifier run on a bounded worker. The future completes with a decision or
a `ModerationException`.

- Safe to call from the main thread or any other thread.
- Completion callbacks may run on a worker thread. When text moderation is
  disabled they may run on the calling thread. Never assume the main thread.
- Apply your own deadline. `ModerationSession` does this for you.
- Treat timeout, shutdown and rejection as **not delivered**. Ignore late
  results.

### `moderateSync(request)` — synchronous pre-delivery event

For a cancellable event that cannot take an asynchronous decision:

```java
ModerationDecision decision = api.moderateSync(request);
if (decision.action() != ModerationAction.ALLOW) {
    event.setCancelled(true);
    player.sendMessage(decision.playerReason());
}
```

It runs on the calling thread and makes no network or disk calls. An in-process
bundled classifier may run; a remote HTTP classifier does not. It still respects
shadow mode. If CleanSpeak is shutting down or the request is invalid, it throws
`ModerationException`.

Use this only when you must decide inside the event. The async path is more
complete because it can use every configured check.

## 7. Decisions

`ModerationDecision.action()` is the effective action. Use it for delivery.

| Action | Delivery |
|---|---|
| `ALLOW` | Deliver the original message exactly once. |
| `REPHRASE` | Do not deliver. Show `playerReason()` or a rephrase prompt. |
| `REVIEW` | Do not deliver. Staff inspect it later. |
| `BLOCK` | Do not deliver. Show `playerReason()`. |

Other useful fields:

- `rawAction()` — what the check decided before shadow mode.
- `shadowed()` — whether shadow mode changed the effective action.
- `categories()` and `contributions()` — staff evidence, not player text.
- `staffReason()` vs `playerReason()` — never send staff text to players.
- `fallback()` — part of the check was unavailable; local rules were used.
- `observation()` — observation-only result.
- `requestId()`, `channelId()`, `conversationId()` — correlation.

In shadow mode `action()` is `ALLOW` and `playerReason()` is empty, while
`rawAction()` records what would have happened. Always branch on `action()`, not
`rawAction()`.

There is no ban or kick action. A model score is an estimate, not proof.

## 8. Failures

All API failures are reported as `ModerationException`:

```java
future.whenComplete((decision, error) -> {
    if (error != null) {
        ModerationException failure = ModerationException.from(error);
        switch (failure.reason()) {
            case TIMEOUT -> { /* not delivered */ }
            case OVERLOADED -> { /* queue full; not delivered */ }
            case SHUTDOWN -> { /* disabling/reloading; not delivered */ }
            case INVALID_REQUEST -> { /* programming error; not delivered */ }
            case INTERNAL -> { /* not delivered */ }
        }
        return;
    }
    // handle decision
});
```

| Reason | Meaning |
|---|---|
| `TIMEOUT` | The caller or service deadline expired. |
| `OVERLOADED` | A bounded queue rejected the request. |
| `SHUTDOWN` | CleanSpeak is not ready, reloading or disabling. |
| `INVALID_REQUEST` | The request failed validation. |
| `INTERNAL` | Unexpected failure in the moderation core. |

Every reason means **do not deliver**. Log only the reason and request id; never
log raw player text.

Additional runtime behavior:

- If the model is unavailable, local rules still run and decisions are marked
  `fallback=true`. This can allow content only the model would have caught.
- If the API is missing, do not claim the route is protected.
- If CleanSpeak is shutting down, do not deliver.

## 9. Ordering and exactly-once delivery

Use `ModerationSession` whenever several messages for one conversation can be in
flight. It runs one moderation future per conversation at a time and applies one
timeout to each.

```java
ModerationSession session = ModerationSession.builder(api)
        .maxPendingPerConversation(8)
        .maxConversations(2000)
        .timeout(Duration.ofSeconds(5))
        .build();

CompletableFuture<ModerationDecision> result = session.submit(request);
```

The session:

- preserves the order in which approved messages reach the delivery callback;
- rejects excess work with `ModerationException.Reason.OVERLOADED`;
- rejects submissions after `close()` with `SHUTDOWN`;
- does not deliver, retry or resurrect a rejected message;
- does not decide whether a message is allowed.

For a custom pipeline, `OrderedConversationQueue` provides the same ordering
primitive. It never throws for rejected work; it returns a failed future with a
`RejectedExecutionException` (map it with `ModerationException.from`).

Your plugin must still:

- deliver an approved message exactly once (an `AtomicBoolean` is a simple
  guard);
- never deliver rejected, failed or timed-out messages;
- schedule Bukkit/delivery work on the main thread;
- re-check sender/recipient online state and ignore lists at delivery time;
- notify the sender when blocked, timed out or not deliverable;
- bound its own pending queues and expire stale requests;
- close the session in `onDisable()`.

The example plugin implements all of these behaviors in `TellService`.

## 10. Threading and lifecycle

- `moderate` may be called from any thread.
- `moderateSync` runs on the calling thread.
- `health` and `registerAdapter` may be called from any thread.
- Future callbacks have no guaranteed thread. Hop to the main thread before
  touching Bukkit players or worlds.
- The API object survives `/cs reload`; the underlying runtime state is replaced
  atomically.
- Close your `ModerationSession` when your plugin disables. Pending results after
  disable must be ignored.

## 11. Testing your integration

The API is an interface, so you can fake it without a server:

```java
final class FakeApi implements CleanSpeakApi {
    @Override
    public CompletableFuture<ModerationDecision> moderate(ModerationRequest request) {
        return CompletableFuture.completedFuture(decision(request, ModerationAction.ALLOW));
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
        return new HealthSnapshot(false, false, false, false,
                "test", "test", 0, Map.of(), System.currentTimeMillis());
    }
}
```

Recommended test cases:

- `ALLOW` delivers exactly once;
- `BLOCK` / `REPHRASE` / `REVIEW` never deliver;
- timeout and rejected futures never deliver;
- two messages in one conversation keep their order;
- different conversations progress independently;
- recipient going offline between submit and delivery prevents delivery.

The example test suite in
[`TellServiceTest`](../example-tell/src/test/java/dev/cleanspeak/example/tell/TellServiceTest.java)
covers the delivery path.

## 12. Adapter status

Register informational details so staff can see what is integrated:

```java
api.registerAdapter(new AdapterRegistration(
        "my-pm-plugin",
        "1.4.0",
        "/tell,/msg,/reply",
        true,
        "Async pre-delivery API; caller owns delivery."
));
```

This is informational. It does not modify routing and does not make a route
protected.

## 13. Versioning

- `CleanSpeakApi.API_VERSION` and `apiVersion()` expose the contract version.
- Additive methods and helpers may be added in 1.x.
- Breaking changes require a major API version.
- Compile against the API artifact and feature-detect only if you choose to
  support multiple CleanSpeak versions.

## 14. Raw command fallback

`raw-command-inspection` is disabled by default. When enabled, it only inspects
the explicitly listed command names and calls the synchronous path on the raw
arguments. It cannot see unlisted aliases, rewritten commands, unknown
recipients, or messages sent directly by another plugin. It is not a substitute
for integrating the actual delivery path. It is also an explicit privacy
decision because command arguments may be private.

## 15. Unsupported uses

- Do not call the synchronous path on a huge rule set on the main thread; it is
  fast but bounded local work, not a remote model.
- Do not pass raw player text to logs, filenames or staff commands.
- Do not use a decision as proof that model output was accurate.
- Do not issue automatic bans from any decision.
- Do not deliver a message after a failure or timeout, and never deliver twice.
