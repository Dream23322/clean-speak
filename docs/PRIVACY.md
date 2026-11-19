# Privacy and retention

CleanSpeak can analyse chat. Tell players this in plain language and
make sure the server's rules/privacy notice is accurate. The plugin's default
join notice is a starting point, not legal advice.

## Default data handling

| Data | Default | Where it lives |
|---|---|---|
| Original public/private message | Not retained by CleanSpeak | Processed in memory; original remains with the chat plugin |
| Recent conversation context | In memory only, short TTL | Same process; cleared on shutdown |
| Decision metadata | Retained (bounded) | `plugins/CleanSpeak/decisions.jsonl` |
| Message content in decisions | Not retained | Only SHA-256 hash + length unless `retain-raw-text` |
| Appeals/reviews | Retained until owner removes the files | `appeals.json`, `reviews.json` |
| Evidence access audit | Retained | `evidence-audit.log` |
| Example ignore list | Persisted UUID list | `plugins/CleanSpeakTellExample/ignores.yml` |

Decision metadata includes sender UUID/name, channel/conversation, categories,
rule ids/model contributions, reasons and versions. It does not include the raw
message by default. Hashes can still be sensitive in some contexts; restrict
access to the data directory.

## Conversation context

The core keeps a short, TTL-bounded, in-memory window per
`channel + conversation + sender` for split-message and duplicate checks. It is
not written to disk and does not combine unrelated channels or conversations.
Undelivered messages are not added to the window.

## Raw text retention

`privacy.retain-raw-text: true` stores a truncated preview (up to 500 characters)
in the decision log and exposes it to staff with `cleanspeak.staff.evidence`.
This includes private messages if their messaging adapter sends them to
CleanSpeak. Do not enable it unless staff have a clear need and a retention
policy.

## External services and models

CleanSpeak never downloads models and never sends text to an external
service by default. The ML endpoint must be configured and enabled.
Non-loopback/private endpoints are rejected unless
`allow-remote-endpoint: true` is explicitly set. That flag is a privacy decision;
the remote service's retention/logging policy applies.

## Retention and deletion

- Decision log: JSONL records kept in memory up to `staff.recent-limit` and on
  disk; old records are pruned when the store opens (startup/reload) according to
  `privacy.decision-retention-days`.
- Evidence: files are pruned periodically according to
  `privacy.evidence-retention-days`.
- Appeals/reviews: persisted and not automatically pruned by CleanSpeak. Delete
  or archive them according to the server's policy.
- Conversation context: memory only; expires/TTL and shutdown clears it.

## Access control

- `cleanspeak.staff.evidence` is required for retained text preview and evidence.
- Other staff views require `cleanspeak.staff.status` / `review`.
- Alerts go to `cleanspeak.notify`.
- CleanSpeak does not encrypt its JSON/WAV files. Use OS/filesystem permissions.

## Player notice

The default config sends a short notice on join:

```yaml
privacy:
  join-notice: true
  join-notice-message: "&eThis server may analyse public chat for moderation. Ask staff for details."
```

Update it to match what is actually enabled. If evidence
retention or remote endpoints are enabled, say so and provide a contact/appeal
route. Do not claim data is never stored if you enabled retention.
