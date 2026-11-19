# Protected and unprotected message routes

"Protected" here means the route calls CleanSpeak **before delivery**, not that
bypass is impossible. A route is only protected once its actual delivery path is
integrated and tested.

At implementation time the repository contained only a README. No server
directory, plugin list, proxy configuration, Discord bridge or existing /tell
plugin was available to inspect. The live test server contained only CleanSpeak,
the example plugin. Any existing messaging/chat plugin must
be identified and integrated by the owner; do not assume this repository's
example commands replace it.

## Route table

| Route | Status | Notes |
|---|---|---|
| Paper public chat (`AsyncChatEvent`) | **Integrated; live-tested** | Default `RULES_ONLY` checks local rules and cancels before delivery. Allowed signed messages/formatters are untouched. ML is observation-only in this mode. `ASYNC_REPLAY` is experimental and unsigned. |
| Example `/tell`, `/msg`, `/w`, `/reply`, `/r` | **Integrated; live-tested** | `example-tell` calls the async API before delivery, preserves per-conversation order, times out, checks online/ignore state, and delivers once. |
| Example `/ignore`, `/unignore`, `/ignorelist` | **Integrated; live-tested** | Example messaging plugin's own ignore list. |
| EssentialsX `/tell`/`/msg`/`/w`/`/reply` | **Adapter implemented; rules-only; not live-tested** | Uses `PrivateMessagePreSendEvent` synchronously. ML is not applied. Actual aliases/versions must be tested. |
| Other third-party private-message plugins | **Unprotected until adapted** | No source/API was available in the repository or test server. Use the CleanSpeak API or a pre-delivery adapter. |
| Raw command fallback | **Limited, opt-in, disabled by default** | Only explicitly listed command tokens, rules-only, no guaranteed alias/recipient/rewrite coverage. |
| Paper `ChatEvent` (synchronous) | Covered indirectly | CleanSpeak listens to `AsyncChatEvent`, which is fired for incoming chat and plugin-triggered chat. It does not listen to the deprecated `ChatEvent` separately. |
| Other chat/channel plugins that send plugin-to-plugin messages | **Unprotected** | They may not fire `AsyncChatEvent`, or may send directly to audiences. |
| `/me`, `/say`, `/broadcast`, server announcements | **Unprotected** | Commands/system messages are not player chat; only explicitly configured raw commands are inspected. |
| Signs, books, item names, scoreboards, holograms, mail | **Unprotected** | Not implemented. |
| Proxy chat/Velocity/BungeeCord messages | **Unprotected/unknown** | No proxy was present to inspect or test. |
| Discord bridge / cross-server messaging | **Unprotected/unknown** | No bridge was present to inspect. Integrate the actual delivery path. |
| Messages sent directly by another plugin with `Player#sendMessage` | **Unprotected** | Not player chat; use the API if the plugin owns a message route. |

## Public chat details

- CleanSpeak listens at `EventPriority.LOW` and does not modify allowed messages.
- If another plugin cancels the event before CleanSpeak, the message is not
  delivered; CleanSpeak may not log it.
- If another plugin rewrites the message after CleanSpeak, the original analysis
  may not match the final text. Review your formatter order.
- In `RULES_ONLY`, a `REPHRASE`/`BLOCK`/`REVIEW` decision cancels signed chat
  before delivery. It does not send a replacement signed message.
- In `ASYNC_REPLAY`, every inspected message is cancelled and an approved message
  is sent by CleanSpeak as an unsigned system-style message. Signing/reporting,
  chat formatter output and channel routing are not preserved. This mode was not
  live-tested with a real signed client.
- The plugin does not use `AsyncPlayerChatEvent` or a client packet interception.

## Messaging API details

The API protects only the caller that uses it. A plugin can call the API and then
ignore the decision; CleanSpeak cannot prevent that. The caller must not deliver
`REPHRASE`, `BLOCK`, `REVIEW` or failed/timed-out messages. `ModerationSession`
provides per-conversation ordering and typed failures; `ModerationRequests`
builds correctly isolated requests.

`ConversationIds` must isolate unrelated conversations. A shared channel and
conversation id will produce shared context and ordering. The request builder
now requires both fields, so an integration cannot silently fall back to a
shared default.

## Raw fallback limitations

Raw inspection:

- sees only configured command names, not unlisted aliases;
- cannot reliably parse every recipient/argument format;
- cannot see messages rewritten or delivered by another plugin;
- may see private arguments, so it is a privacy decision;
- is synchronous rules-only and cannot apply ML;
- does not provide a full delivery path.

## Claiming coverage

Before claiming `/tell` is protected, confirm all of:

1. The actual command/event path calls the API or adapter.
2. Recipient resolution, permissions, ignore lists and reply targets still work.
3. A blocked message is not delivered.
4. An allowed message is delivered exactly once.
5. Timeouts/failures are not delivered.
6. Aliases and the installed plugin version were tested.

No route can be made completely unbypassable. Keep staff review and appeals.
