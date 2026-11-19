# CleanSpeak

A standalone Paper 1.21.11 text moderation plugin with a pre-delivery API for
messaging plugins.

**This is a filter, not a security boundary.** Determined bypasses are possible.
Do not describe it as completely unbypassable. It also deliberately contains **no
server-ban or kick action** for moderation decisions.

## Safety policy

- No automatic server bans.
- No automatic permanent mutes.
- Text messages can be blocked or returned to the player for rephrasing.
- Staff can review, mark decisions incorrect and reverse decisions.
- Text enforcement is on by default; set `text.shadow-mode: true` for observation.
- A small hashed text model is bundled and enabled by default; text shadow mode
  still only observes until the owner turns enforcement on.
- The local rule engine works without a model.

## Verified versions

| Component | Version used |
|---|---|
| Java | 21 (tested with Temurin 21.0.8) |
| Paper server | 1.21.11 build 132 |
| Paper API | `1.21.11-R0.1-SNAPSHOT` |
| EssentialsX compile API | 2.21.2 (optional adapter; not live-tested) |

The Paper 1.21.11 API is compiled against a snapshot. Pin or mirror the API
artifact in CI if you need byte-for-byte reproducible dependency resolution.

## What is in this repository

```
api/                               Standalone, dependency-free public API module
src/main/java/dev/cleanspeak/...   CleanSpeak plugin and shared core
src/test/java/...                  JUnit 5 core/API/storage tests
example-tell/                      Complete working example /tell-style plugin
docs/                              API, privacy, routes, testing and rollout docs
```

## Build

```sh
./gradlew clean build
./gradlew jar :example-tell:jar :api:jar
```

On Windows use `gradlew.bat`. The jars are:

- `build/libs/CleanSpeak-1.0.0.jar` (plugin; embeds the API classes)
- `api/build/libs/cleanspeak-api-1.0.0.jar` (standalone API for plugin authors)
- `example-tell/build/libs/CleanSpeakTellExample-1.0.0.jar`

Plugin authors can install the API locally and compile against it without the
plugin jar:

```sh
./gradlew :api:publishToMavenLocal
```

```kotlin
dependencies {
    compileOnly("dev.cleanspeak:cleanspeak-api:1.0.0")
}
```

Do **not** shade Paper, EssentialsX or CleanSpeak into your jar. They are
compile-only dependencies. See [docs/API.md](docs/API.md) for the full
integration guide.

## Install (minimum)

1. Use Paper 1.21.11 and Java 21.
2. Put `CleanSpeak-1.0.0.jar` in `plugins/`.
3. Start once; edit `config.yml`, `checks.yml`, `messages.yml`, `actions.yml` and `rules.yml`.
4. Enforcement is on by default. For a staged rollout, set `text.shadow-mode: true`
   first and review `/cs recent` / `/cs violations`.
5. Add the example jar only on a test server or after checking `/tell`
   command conflicts:

   `example-tell/build/libs/CleanSpeakTellExample-1.0.0.jar`

The example plugin is deliberately separate. It owns `/tell`, `/msg`, `/w`,
`/reply`, `/r`, `/ignore`, `/unignore` and `/ignorelist` in that jar. Do not
install it beside EssentialsX or another plugin that already registers those
command names without resolving the conflict first.

Optional:

- EssentialsX 2.21.x for the optional rules-only `PrivateMessagePreSendEvent`
  adapter.
- An optional local text classifier sidecar.

Configuration is split in the check-oriented style:

- `config.yml` â€” global behavior
- `checks.yml` â€” per-check enable/action/display/thresholds
- `messages.yml` â€” player-facing templates
- `actions.yml` - per-check actions and console commands
- `rules.yml` â€” lexical/regex rules

See [docs/INSTALL.md](docs/INSTALL.md).

## Routes

Default protected routes:

- Paper public chat through `AsyncChatEvent` (local rules + bundled model by
  default; signed chat is preserved in `RULES_ONLY` mode).
- The example `/tell`/`/msg`/`/reply` delivery path through the async API.

Not protected until you integrate the actual delivery path:

- Third-party private-message commands, including EssentialsX if its adapter is
  not enabled/verified.
- Proxy, Discord bridge, cross-server chat, mail, signs, books, item names and
  arbitrary plugin messages.
- Other command aliases or rewritten messages that the limited raw-command
  fallback cannot see.

Read the full [route table](docs/ROUTES.md). Do not claim `/tell` is protected
until the actual delivery path is tested.

## Plugin authors

Integrate a messaging plugin with `CleanSpeakApi`, `ModerationRequests` and
`ModerationSession`. The guide covers Gradle/Maven setup, the complete async
call path, conversation ids, failure handling, ordering, threading, lifecycle
and testing. See [docs/API.md](docs/API.md).

## Staff commands

```
/cs help
/cs status
/cs checks
/cs recent [n]
/cs violations [player] [n]
/cs show <requestId>
/cs flag <requestId> [note]
/cs reverse <requestId> [note]
/cs appeals
/cs resolve <appealId> <note>
/cs appeal <requestId> [reason]
/cs reload
/cs alerts [on|off|status]
/cs privacy
```

`actions.yml` defines per-check actions in the anticheat style:

```yaml
hate-speech:
  reset-time: 100
  actions:
    - "[1:0] [block]"
    - "[1:0] [alert]"
    - "[3:0] mute %player% 1h hate speech"
```

The leading `[threshold:interval]` follows Grim: `0` runs once at the threshold,
otherwise the action repeats every interval. Built-in actions are `[alert]`,
`[block]` and `[review]`; anything else is a console command.
Actions never run in shadow mode, and ban/kick/op commands are blocked unless
`allow-dangerous-commands: true`. Existing `punishments.yml` files are migrated
to `actions.yml` automatically on first start.

`/cs recent` prefixes decisions with `[REGEX]`, `[AI]` or `[AI+REGEX]`.
`/cs violations [player] [n]` aggregates recent flags per check and source, similar
to an anticheat violation list, with clickable request IDs. `/cs show` prints the
same source and separates regex and AI contributions.

Staff alerts use the anticheat-style format:

```text
[CleanSpeak] Alice has been flagged [info] (x3)
[CleanSpeak] Message canceled from Alice [info] (x5)
```

The `[info]` hover card shows source (AI/REGEX), category, action, model confidence
when a model produced the flag, model version, channel and request ID. Clicking it opens `/cs show` for that decision. Counts are
per player and event type for the current server session.

Permissions:

- `cleanspeak.appeal` (default true)
- `cleanspeak.notify` (default op)
- `cleanspeak.staff` and children `status`, `review`, `reload`, `evidence`,
  `debug` (default op)

No command in this plugin bans or kicks a player.

## Implemented vs tested

**Implemented**

- Local rules, normalization, context/quotation checks, spam/link checks,
  structured decisions, shadow mode and a bounded async API.
- Paper public chat adapter, example async `/tell` integration, EssentialsX
  rules-only pre-send adapter, limited raw-command fallback.
- Staff tools, evidence controls and appeals.

**Tested**

- Java tests across the plugin, API and example modules
  (`./gradlew clean build`) and 94 Python model-pipeline tests
  (`py -m unittest discover -s tools/training/tests -q`).
- Model acceptance gates pass (`py tools/training/check_gates.py`): artifacts,
  metric floors, dataset/leakage checks, live challenge gates and sidecar
  behavior.
- Paper 1.21.11 build 132 startup.
- Live shadow and enforcing public chat with two test clients.
- Live example `/tell`, `/msg`, `/reply`, ignore list and staff status/recent/
  show/flag/appeal flows.
- A local TF-IDF/logistic-regression sidecar over HTTP and its optional plugin
  integration. Challenge-set accuracy is reported in
  [ML.md](docs/ML.md); it is not real-server accuracy.

**Not verified here**

- Signed chat reporting/delete behavior with a real Mojang client.
- Existing chat-formatting plugins, proxies, Discord bridges and cross-server
  routes (none were present in the repository or test server).
- EssentialsX live delivery.
- ML accuracy on real server traffic.
- Folia. This plugin targets Paper.

## Documentation

- [Installation](docs/INSTALL.md)
- [Architecture](docs/ARCHITECTURE.md)
- [Moderation API](docs/API.md)
- [Configuration and rules](docs/CONFIGURATION.md)
- [Privacy and retention](docs/PRIVACY.md)
- [ML models](docs/ML.md)
- [Protected/unprotected routes](docs/ROUTES.md)
- [Testing](docs/TESTING.md)
- [Rollout](docs/ROLLOUT.md)
- [Limitations](docs/LIMITATIONS.md)
- [Starter dataset and baseline training](tools/training/README.md)

## License

The repository is released under the Unlicense. EssentialsX, Paper and Simple
CleanSpeak bundles its own hashed text model; it does not bundle third-party
model files or plugins.
