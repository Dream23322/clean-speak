# Configuration and rules

CleanSpeak uses a check-oriented layout similar to modern anticheats:

| File | Purpose |
|---|---|
| `config.yml` | Global behavior: delivery mode, shadow mode, ML endpoint, queues, privacy, staff, logging |
| `checks.yml` | Per-check enable/action/display name, descriptions and ML thresholds |
| `messages.yml` | Player-facing message templates |
| `actions.yml` | Per-check actions, thresholds and console commands |
| `rules.yml` | Lexical/regex rules, terms and rule-level actions |

All five are loaded on startup and `/cs reload`. Invalid values are warned about;
invalid rules fall back to a minimal local rule set.

## Global config

```yaml
config-version: 2
policy-version: "2026.11.18.1"

text:
  enabled: true
  shadow-mode: false
  delivery-mode: RULES_ONLY       # or ASYNC_REPLAY (experimental)
  notify-blocked: true
  notice-cooldown-seconds: 0
  max-message-length: 512
  context:
    max-messages: 3
    ttl-seconds: 45
    max-keys: 5000
  ml:
    enabled: true
    endpoint: ""
    allow-remote-endpoint: false
    model-version: "bundled-v1"
    timeout-ms: 800
    failure-mode: RULES_ONLY
    may-block: false
    min-confidence: 0.55
```

The shipped default is `shadow-mode: false` (enforce). Set it to `true` for a
staged rollout; that logs the would-be action but allows delivery. Review
`/cs recent` and `/cs violations` before switching to enforcement.

`RULES_ONLY` applies local rules and the bundled in-process model synchronously
before delivery, and preserves signed chat and existing formatters for allowed
messages. External HTTP sidecars are not called on this synchronous path. `ASYNC_REPLAY` cancels
and replays approved messages as unsigned system messages; signing,
reportability and formatter behavior change. See [ROUTES.md](ROUTES.md).

With `ml.endpoint: ""`, the plugin uses the bundled hashed model from the jar.
Set a localhost endpoint only if you prefer an external sidecar. `ml.may-block:
false` means a model score can request `REVIEW` but cannot alone block. Even with
`true`, the model only selects configured text actions; it never bans or kicks.
Local rules always run.

## Per-check configuration

`checks.yml` is authoritative for whether a category is active, what action it
uses, its display name and its model thresholds.

```yaml
targeted-harassment:
  enabled: true
  action: REPHRASE
  display-name: "targeted harassment"
  description: "Direct degradation or exclusion aimed at a player."
  ml:
    review-threshold: 0.55
    block-threshold: 0.88

threats:
  enabled: true
  action: BLOCK
  display-name: "threats"
  ml:
    review-threshold: 0.50
    block-threshold: 0.85

profanity:
  enabled: false
  action: REPHRASE
  display-name: "profanity"
  ml:
    review-threshold: 0.70
    block-threshold: 0.95
```

Supported check keys and aliases:

| Check | Keys |
|---|---|
| Targeted harassment | `targeted-harassment`, `harassment`, `targeted` |
| Hate/slurs | `hate-slurs`, `hate-slur`, `hate`, `slurs` |
| Threats | `threats`, `threat`, `violence` |
| Spam | `spam`, `flooding` |
| Scam/advertising | `scam-advertising`, `scam`, `advertising`, `links` |
| Profanity | `profanity`, `swearing` |
| Other | `other`, `misc` |

Actions are `ALLOW`, `REPHRASE`, `REVIEW`, `BLOCK`. There is no ban/kick action.
`REPHRASE` and `BLOCK` both stop delivery; `REVIEW` also stops text delivery and
creates a staff-visible decision.

Disabling a check removes that category from decisions and from ML policy. The
rule engine may still compute matches for diagnostics, but they are ignored by
the policy. `REVIEW` is the safest fallback for uncategorized limits.

The old `text.category-actions` and `text.ml.thresholds` blocks in `config.yml`
are still read as fallbacks for existing installs. Move custom values into
`checks.yml`; the checks file wins. A warning is emitted for old config versions.

`checks.yml` still decides the base text action. `actions.yml` can escalate that
decision at a violation threshold (`[block]`, `[review]`), alert staff, or run a console command. CleanSpeak has no automatic ban, kick
or built-in mute; server-side mutes are commands.

## Actions

`actions.yml` uses the Grim-style threshold format:

```yaml
enabled: true
reset-time: 3600
allow-dangerous-commands: false

hate-speech:
  enabled: true
  reset-time: 100
  actions:
    - "[1:0] [block]"
    - "[1:0] [alert]"
    - "[3:0] mute %player% 1h hate speech"

threats:
  reset-time: 300
  actions:
    - "[1:0] [block]"
    - "[1:0] [alert]"
```

Format:

- `[threshold:interval] action`
- `threshold` is the first violation count that fires.
- `interval: 0` fires once at the threshold. A non-zero interval repeats every
  N violations after the threshold, matching Grim's punishment syntax.
- `[alert]` sends a staff alert and suppresses the normal decision alert for
  that request.
- `[block]` stops delivery and marks the decision `BLOCK`.
- `[review]` stops delivery and marks the decision `REVIEW`.
- Any other text is a console command. A leading `/` is optional.

Placeholders: `%player%`, `%uuid%`, `%category%`, `%check%`, `%count%`,
`%action%`, `%request%`, `%reason%`.

`actions` applies to text moderation.

Counters are per player, check and audience. They reset after the check's
`reset-time` (or the global value) of inactivity. Shadow-mode and observation
decisions never count and never run actions. Commands only run for enforced
decisions.

For safety, `ban`, `kick`, `op`, `deop`, `pardon`, `whitelist`, `stop` and
`restart` commands are skipped unless `allow-dangerous-commands: true`. The
shipped policy has no automatic bans or kicks. Enable commands only after
confirming your server provides them (EssentialsX, LiteBans, a custom mute
plugin, etc.); CleanSpeak does not ship a text-mute command.

Existing `punishments.yml` files are migrated automatically on first start. The
old file is renamed to `punishments.yml.migrated` after the generated
`actions.yml` is written. Legacy thresholds become `[count:0] command` lines.

## Messages

`messages.yml` controls the short player-facing reasons. Keep them short and do
not include prohibited source text.

```yaml
player:
  rephrase: "Your message was not delivered. Please rephrase it without {category}."
  block: "Your message was not delivered because it matched a clear {category} rule. Please rephrase it without {category}."
  review: "Your message was not delivered and has been sent to staff for review."
```

Placeholders:

- `{category}` — the check's `display-name`
- `{request_id}` — the decision request id

The plugin appends a request id to blocked notices when the template does not
already contain it.

## Rules

```yaml
rules:
  - id: "example.term"
    category: HATE_SLUR
    type: TERM
    action: REPHRASE
    score: 0.85
    terms:
      - "replace-with-local-term"

  - id: "example.pattern"
    category: THREAT
    type: REGEX
    action: BLOCK
    score: 0.92
    pattern: '\bi\s+will\s+find\s+you\b'
```

Rule ids are unique and shown to staff. `score` is supporting confidence in
`[0,1]`, not proof. Keep regexes simple; they are trusted configuration. Patterns
run against a lower-case analysis form with punctuation replaced by spaces.

The starter rules are a seed, not a complete lexicon. Add language- and
community-specific rules. Do not treat identity terms as standalone violations;
require actual hate/threat context.

### Normalization

The engine builds analysis forms for Unicode NFKC/fullwidth forms, case and
diacritic folding, zero-width/format removal, common homoglyphs, leet
substitutions, repeated characters, separator spacing and single-letter runs.

Aggressive forms are supporting evidence, not automatic proof. Obfuscated
non-threat matches are capped at `REPHRASE`. Innocent substrings are avoided by
token matching (`class`, `assassin`, `bass` do not match `ass`). Other languages
or mixed scripts are never a violation by themselves.

### Context

Recent context is ephemeral, in-memory and keyed by channel + conversation +
sender. It is not persisted. Messages that were not delivered are not added.
Quotation/reporting cues can exempt a slur/insult; a reported threat is held for
review instead of silently allowed. Context detection is heuristic.

## Spam and links

```yaml
spam:
  max-messages-per-window: 6
  window-seconds: 10
  duplicate-threshold: 3
  max-caps-ratio: 0.80
  max-repeated-character-run: 14
  max-urls: 4
  action: REPHRASE
  duplicate-action: REPHRASE
  caps-action: REPHRASE

links:
  scam-enabled: true
  advertising-enabled: false
  scam-action: BLOCK
  advertising-action: REPHRASE
  allowlist: ["example.com"]
  blocklist: ["grabify.link"]
  shorteners: ["bit.ly", "tinyurl.com"]
  suspicious-tlds: []
```

Links are not blocked merely for existing. Advertising protection is off by
default.

## Queues

```yaml
queues:
  api-workers: 2
  api-capacity: 256
  request-timeout-ms: 1500
  conversation-max-pending: 8
  conversation-max-active: 2000
```

Bounded queues protect the server. Overflow does not block the main thread.
Callers must apply their own delivery timeout and never deliver timed-out
messages.

## Privacy and logging

```yaml
privacy:
  join-notice: true
  join-notice-message: "&eThis server may analyse public chat for moderation..."
  retain-raw-text: false
  decision-retention-days: 14

logging:
  log-allowed: false
  log-shadow: true
  write-decision-log: true
```

Default storage is minimal: message hash and length, decision metadata and
rule/category contributions; no raw text. Read
[PRIVACY.md](PRIVACY.md) before enabling retention.

Decision-log retention is applied when the store opens (startup/reload).
Evidence retention runs periodically. Files are not encrypted by CleanSpeak;
protect the data directory.

## Staff, integrations and raw commands

```yaml
staff:
  alerts: true
  alert-cooldown-seconds: 0
  recent-limit: 200
  appeal-cooldown-seconds: 60

integrations:
  essentials-rules-only: true

raw-command-inspection:
  enabled: false
  inspect:
    tell: 1
    msg: 1
    w: 1
    reply: 0
    r: 0
```

`alert-cooldown-seconds: 0` means every enforced decision produces an alert.
`/cs alerts`, `/cs alerts on`, and `/cs alerts off` toggle alerts for the
individual staff member and persist in `plugins/CleanSpeak/alerts.json`. The
global `staff.alerts` switch remains the master control.

Raw inspection is an opt-in, limited fallback. It only sees exact configured
commands, cannot guarantee aliases/rewrites/plugin delivery and should remain
disabled when a real adapter is installed. See [ROUTES.md](ROUTES.md).

## Reload

`/cs reload` reloads the config files and rebuilds the moderation state.
Decision-log retention is reapplied.
After reload, verify `/cs status` and `/cs checks`; do not assume full coverage if
a model or messaging adapter is unavailable.
