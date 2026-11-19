# Installation

## Requirements

- Paper **1.21.11** (tested on build 132)
- Java **21** (tested with Temurin 21.0.8)
  and matching client mod
- Optional: EssentialsX **2.21.x** for its private-message pre-send event
- Optional: a local speech-to-text worker and/or text-classifier sidecar

CleanSpeak does not bundle Paper, EssentialsX or third-party
plugins. It does bundle its own small hashed text model.

## Build

```sh
./gradlew clean build
./gradlew jar :example-tell:jar :api:jar
```

Windows:

```powershell
.\gradlew.bat clean build
.\gradlew.bat jar :example-tell:jar :api:jar
```

Build output:

- `build/libs/CleanSpeak-1.0.0.jar` (plugin)
- `api/build/libs/cleanspeak-api-1.0.0.jar` (standalone API)
- `example-tell/build/libs/CleanSpeakTellExample-1.0.0.jar`

Plugin authors should read [API.md](API.md) for dependency setup.

The build declares Paper's official Maven repository plus a mirror fallback
because the 1.21.11 API snapshot may be retired from the official index. Pin or
mirror dependencies and verify their checksums in CI. The plugin uses compile-only
APIs; do not shade them.

## Install CleanSpeak

1. Stop the server.
2. Put `CleanSpeak-1.0.0.jar` into `plugins/`.
3. Start the server once.
4. Edit `config.yml`, `checks.yml`, `messages.yml`, `actions.yml` and
   `rules.yml`.
5. Restart or run `/cs reload`.
6. Run `/cs status` and `/cs checks` as an operator.

CleanSpeak uses a check-oriented layout:

- `config.yml` — global behavior
- `checks.yml` — per-check enable/action/display/thresholds
- `messages.yml` — player-facing templates
- `actions.yml` - per-check actions and console commands
- `rules.yml` — terms, patterns and rule-level actions

Existing installs may keep legacy `text.category-actions` and
`text.ml.thresholds` as fallbacks, but `checks.yml` is authoritative. Move
custom values there.

The first startup creates the default config, checks, messages, actions and
rules. Text enforcement is on (`text.shadow-mode: false`). For a staged rollout, set `text.shadow-mode: true` and review
`/cs recent` before enforcing. Existing `punishments.yml` files are migrated to
`actions.yml` automatically.

## Install the example `/tell` plugin

Only do this on a test server or after resolving command conflicts.

1. Put `CleanSpeakTellExample-1.0.0.jar` into `plugins/`.
2. Ensure no other plugin owns `/tell`, `/msg`, `/w`, `/reply`, `/r`, `/ignore`,
   `/unignore` or `/ignorelist`.
3. Restart.
4. Check `/cs status`; the route `example-tell` should appear.
5. Test with two players.

The example plugin is a separate plugin on purpose. It owns recipient
resolution, permissions, ignore lists, reply targets and exactly-once delivery.
CleanSpeak only returns a decision.

If you use EssentialsX, do not install the example commands on top of it.
EssentialsX has its own `/msg`, `/tell`, `/w`, `/r` etc.; use its dedicated
adapter described below.

## EssentialsX adapter

CleanSpeak's optional adapter listens to EssentialsX
`PrivateMessagePreSendEvent`. It is a **synchronous rules-only** adapter: it
cannot wait for an ML decision without taking over the delivery path, so it does
not apply ML blocking. EssentialsX still owns delivery, permissions, ignore
lists and reply targets.

Requirements:

- EssentialsX 2.21.x installed and enabled
- `integrations.essentials-rules-only: true`
- CleanSpeak loaded after Essentials (declared as a soft dependency)

This adapter was compiled against the EssentialsX 2.21.2 API but was not
live-tested on the target server. Verify it with the actual EssentialsX version
and aliases before claiming `/tell` is protected. See [ROUTES.md](ROUTES.md).

## Reload and restart behavior

`/cs reload` reloads the config files and rebuilds the moderation service. It
does **not** retroactively deliver blocked text.

Decision-log retention is applied when the decision store is opened (startup or
reload). See [PRIVACY.md](PRIVACY.md).

## Signed chat and formatting trade-offs

`text.delivery-mode: RULES_ONLY` is the safe default. It keeps signed chat and
existing chat formatters untouched for allowed messages; prohibited messages are
cancelled before delivery.

`ASYNC_REPLAY` is experimental. It cancels first, waits for the model, and
replays an allowed message as an unsigned system message. That changes signing,
reportability, and the output of chat-formatting/chat-channel plugins. Do not
enable it on a production server without testing those effects.

Read [ROUTES.md](ROUTES.md) and [ARCHITECTURE.md](ARCHITECTURE.md).
