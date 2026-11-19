# Known limitations

## Filtering is not complete

- No text filter is unbypassable. Obfuscation, new slang, code-switching,
  context, images, signs, books, plugin messages and out-of-band communication
  can evade it.
- The seed rules are English-heavy and incomplete. Add local rules and test.
- Aggressive normalization can create false positives; the policy caps
  non-threat obfuscated matches at `REPHRASE` but still stops delivery.
- Quotation/reporting detection is heuristic and can miss sarcasm, nested quotes,
  or adversarial framing.
- A `REPHRASE`/`BLOCK`/`REVIEW` text decision stops delivery. There is no way to
  un-deliver a message that was already shown.

## Chat integration

- Paper public chat is handled through `AsyncChatEvent`. Other plugins that send
  messages directly, use their own channels, or rewrite after CleanSpeak may not
  be covered.
- `RULES_ONLY` preserves signed chat for allowed messages but ML cannot enforce;
  `ASYNC_REPLAY` loses signing/reportability and chat formatter output.
- The signed-chat behavior was investigated from Paper source, but not tested
  with a real Mojang client/chat reporting flow here.
- Existing chat-formatting plugins, proxies and Discord/cross-server bridges were
  not present in the repository or test server, so compatibility is unverified.
- The EssentialsX adapter is rules-only and not live-tested. Aliases and other
  private-message plugins are not automatically covered.
- Raw command inspection only sees configured command tokens and cannot reliably
  stop aliases, rewritten text, or plugin-to-plugin messages.
- There is no kick/ban/mute command; that is intentional.

## Model

- A small hashed text model is bundled and enabled by default. It is synthetic,
  English-only and not production evidence. The HTTP classifier is an optional external component the owner must install, license and test.
- The starter training pipeline is synthetic, English-only and small. Its
  reported test/challenge scores are not production evidence. Fresh challenge
  scores ranged from 90.5% to 100% during development; these sets were written by
  the same project and are not an independent benchmark.
- The model does not resolve quotation, reported abuse, reclaimed terms, irony or
  code-switching. Those belong to the rule/context layer or human review.
- Model scores are not proof. A high score can be wrong.
- The local-rules fallback when the model is unavailable can allow harmful
  content.
- The model status in `/cs status` is based on observed calls/health, not a
  guarantee of accuracy.
- Remote endpoints are a privacy/security decision and are disabled by default.

## Storage and privacy

- Decision hashes can still be sensitive; protect the data directory.
- Decision-log retention is applied when the store opens, not continuously.
- Evidence retention is periodic; backups may keep deleted files.
- Appeals/reviews are not automatically pruned by CleanSpeak.
- Files are not encrypted by CleanSpeak.
- Staff with filesystem access can read evidence regardless of plugin
  permissions; use OS permissions.
- Context is in-memory only; restarting resets split-message context.

## Operational

- The plugin targets Paper, not Folia or other forks.
- `/cs reload` is best-effort;
  pipeline is replaced within the existing registration.
- Message notices and staff alerts are rate-limited and can be suppressed.
- The API caller can ignore a decision; CleanSpeak cannot enforce delivery.
- No claim of complete coverage or bypass protection is made.
