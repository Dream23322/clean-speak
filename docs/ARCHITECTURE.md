# Architecture

```
Paper public chat ─┐
Custom messaging ──┤
                    ▼
             Shared moderation core
                    ▼
       Local rules + optional ML model
                    ▼
       Decision + optional staff review
```

## Components

- `dev.cleanspeak.core` — normalization, context, rule engine, spam/link
  checks, policy, async service, decision objects.
- `api/` (`dev.cleanspeak.api`) — standalone, dependency-free public API:
  request/decision records, factories, typed failures,
  `OrderedConversationQueue` and `ModerationSession`.
- `dev.cleanspeak.config` — global `config.yml`, per-check `checks.yml`,
  player `messages.yml`, and legacy compatibility.
- `dev.cleanspeak.chat` — Paper `AsyncChatEvent`, example-facing API use,
  EssentialsX pre-send adapter and limited raw-command fallback.
- `dev.cleanspeak.staff` — commands, alerts, player notices.
- `dev.cleanspeak.storage` — decision log, appeals, reviews.
- `example-tell/` — separate working `/tell`-style plugin.

## Text flow

1. A route creates a `ModerationRequest` with a unique request ID, sender,
   audience, channel id, conversation id and original message.
2. The normalizer creates analysis forms without changing the original.
3. The rule engine matches configured terms/patterns, spam heuristics and link
   policy. Recent context is read only for the same sender/channel/conversation.
4. Quotation/reporting context can exempt a match. A reported threat is held for
   review instead of being silently allowed.
5. Optional ML scores are combined according to thresholds. A model score is an
   estimate; without explicit opt-in it can only request `REVIEW`.
6. Policy applies the enabled per-check settings from `checks.yml`, chooses
   `ALLOW`, `REPHRASE`, `REVIEW` or `BLOCK`, records contributions and
   staff/player explanations, then applies shadow mode. Disabled checks are
   excluded from text and model policy.
7. The route applies the effective action. In shadow mode, `action` is `ALLOW`
   while `rawAction` records what would have happened.

## Messaging API and delivery

The shared core never delivers a private message. The messaging plugin:

- resolves recipients and permissions;
- checks ignore lists;
- handles reply targets;
- calls `moderate` and waits with its own timeout;
- delivers exactly once only on `ALLOW`;
- checks players are still online at delivery;
- notifies the sender on block/timeout.

`ModerationSession` combines request ordering, a caller-owned timeout and typed
failures for plugin authors. Internally, `OrderedConversationQueue` serializes
moderation futures per conversation so a slow first request cannot be overtaken
by a later one. Neither helper delivers or retries. Bounded request queues
prevent overload; rejected requests are not delivered.

## Public chat modes

- **RULES_ONLY (default).** Runs local rules synchronously in the event, cancels
  before delivery on a violation, and leaves allowed signed chat and formatters
  untouched. ML runs only as observation, never enforcing.
- **ASYNC_REPLAY (experimental).** Cancels every inspected message, runs rules +
  ML asynchronously, and replays allowed messages as unsigned system messages.
  Signing/reporting and existing formatter output are changed. Requests that time
  out are not delivered.

## Failure behavior

- ML unavailable/timeout/queue full: local rules remain authoritative, decisions
  are marked `fallback=true`, and staff are alerted. This can allow harmful text.

## Storage

- Decision log: JSONL with message hash, length and optional preview (off by
  default).
- Appeals/reviews: small JSON files.
- Recent conversation context: in-memory only, TTL-bounded, never persisted.

There are deliberately no ban or kick actions anywhere in the moderation path.
