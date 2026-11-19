# Testing

## Automated suite

```sh
./gradlew clean build
```

`build` runs the plugin, API and example test suites. The build must pass before
enabling enforcement.

Current automated coverage:

| Area | Tests |
|---|---|
| Normalization: Unicode, zero-width, homoglyph, spacing, innocents | `TextNormalizerTest` |
| Direct rules, obfuscation, threats, scams, quotation/reporting | `RuleEngineTest` |
| Split messages, isolation, expiry, prior-message poisoning | `ConversationTrackerTest` |
| Shadow mode, model thresholds, fallback, observation | `DecisionPolicyTest` |
| Bundled model: resource load, class mapping, neutral/targeted/threat behavior, Python parity, missing-resource fallback | `BundledTextClassifierTest` |
| Model sidecar wire contract: parsing, clamping, `ok:false`, non-2xx, timeout, circuit, health, remote-endpoint refusal | `HttpTextClassifierTest` |
| Async off-main behavior, model failure, rules-only path | `ModerationServiceTest` |
| Queue ordering, limits, timeout progression | `OrderedConversationQueueTest` |
| Request factories, required fields, typed failures, session timeout/close | `ApiHelpersTest` |
| `/tell` aliases, resolution, ignore, ordering, timeout, exactly-once, disconnect | `TellServiceTest` |
| Config defaults, per-check overrides/messages, rules fallback, storage persistence | `ConfigLoaderTest`, storage tests |
| AI/regex source labels and per-check violation aggregation | `ViolationSummaryTest` |
| Action thresholds, block/review escalation and dangerous-command guard | `ActionEngineTest`, `ActionServiceTest` |
| Identity hate variants and plural slurs are blocked | `StartupFallbackTest` |
| Per-staff alert toggle persistence | `AlertPreferencesTest` |

Run a single test:

```sh
./gradlew test --tests 'dev.cleanspeak.core.RuleEngineTest'
```

## Model pipeline tests

```sh
py -m unittest discover -s tools/training/tests -q
```

The model suite currently has 94 tests. It does not need the private lexicon or
a trained model for the core suite. It covers messy-input recovery,
canonicalization, dedupe, family-aware splitting, generator invariants,
placeholder expansion, a tiny train/predict pipeline, challenge metrics and
coherence, the sidecar HTTP contract, committed-data quality and final-challenge
leakage. Two extra regression tests run only when `data/model/` and
`data/lexicon.local.json` are present; they enforce minimum final-challenge
accuracy and ordinary-venting/targeted sidecar behavior.

The Java `HttpTextClassifierTest` adds 6 loopback HTTP tests for the plugin's
model wire contract, including timeout and circuit behavior.

To reproduce the trained artifact and challenge metrics:

```sh
py tools/training/generate_data.py
py tools/training/prepare_dataset.py
py tools/training/train_baseline.py
py tools/training/export_bundled_model.py
py tools/training/evaluate_challenge.py --challenge data/challenge11.jsonl \
  --out data/model/challenge11_metrics.json
```

The latest local run produced passing Java tests across all three modules and
94 Python model tests with 0 failures on Java 21. Gradle 8.14 does not support a
Java 25 launcher; set `JAVA_HOME` to a JDK 21 before running the wrapper.

## Model acceptance gates

After training, run the gates:

```sh
py tools/training/check_gates.py
```

The script lives in `tools/training/check_gates.py`; thresholds live in
`tools/training/gates.json`. It checks artifacts and model schema, test-set
metric floors, dataset size/report consistency, train/test family and text
overlap, live challenge accuracy/recall/error budget, final-challenge leakage,
and sidecar behavior on neutral, targeted and threat examples. A failing gate
exits non-zero and writes `data/model/gate_report.json`.

## Live test performed here

Environment:

- Paper 1.21.11 build 132
- Java 21.0.8
- CleanSpeak 1.0.0
- CleanSpeakTellExample 1.0.0
- Two offline-mode test clients

Observed:

1. Paper started with CleanSpeak and the example plugin.
2. The initial shadow-mode run delivered a targeted-harassment message, logged a
   `REPHRASE/shadow` decision and sent staff notifications to operators.
3. Switching `shadow-mode` to false and running `/cs reload` stopped the same
   message from reaching the second client; the sender got a rephrase notice and
   request id.
4. `/tell Bob hello there` was delivered to Bob; a blocked `/tell` was not.
5. `/reply` after a successful tell was delivered; blocked tells did not set a
   reply target.
6. `/ignore Alice` prevented later example messages; sender got a notice.
7. `/cs status`, `/cs recent`, `/cs violations`, `/cs show`, `/cs flag`, `/cs appeal` worked.
8. `ASYNC_REPLAY` was also smoke-tested with offline clients: allowed messages were replayed and blocked messages were not; signed behavior was not tested.
9. The local TF-IDF/logistic-regression sidecar was started and exercised over
    HTTP; the plugin reported the model as available and used it as an optional
    signal. Accuracy was measured separately on the challenge files.

Not performed here:

- Real signed chat / chat reporting with a Mojang account.
- A live EssentialsX server.
- Model accuracy on real server traffic (all reported model metrics are from
  synthetic families and hand-written challenges).
- A proxy, Discord bridge, or cross-server setup.
- A chat-formatting plugin besides the offline client.

## Reproducible integration plan

Use a staging server, not production.

### 1. Public chat

1. Install Paper 1.21.11 + CleanSpeak; keep `shadow-mode: true`.
2. Join with two accounts.
3. Send harmless messages, a direct rule violation, an obfuscated variant, a
   quotation/report, and a normal foreign-language message.
4. Check `/cs recent` and `/cs show`; verify would-be action and player reasons.
5. Set `shadow-mode: false`, `/cs reload`, and repeat.
6. Confirm harmless messages and allowed signed chat still arrive, and blocked
   messages do not.
7. Test with your existing chat formatter/chat channel plugin. Verify listener
   order: CleanSpeak listens at `LOW` and does not modify allowed messages.
8. Test signed chat/reporting with a real client. Record what happens when a
   message is blocked and when `ASYNC_REPLAY` is used.

### 2. Private messaging

1. Install the example plugin on an isolated test server.
2. Test `/tell`, `/msg`, `/w`, `/reply`, `/r`, self target, offline target,
   permission failure, ignore lists, and recipient disconnect after sending.
3. Confirm blocked messages are never delivered and allowed messages arrive once.
4. Test a slow model/worker by pointing the endpoint at a deliberately slow
   local service; verify timeout and no delivery.
5. Install EssentialsX separately and test its adapter and aliases. Do not assume
   the example adapter covers EssentialsX.

### 3. Failure and restart

1. Stop the model sidecar; confirm text rules still apply.
2. Disconnect a recipient between the command and delivery; confirm no delivery.
3. Restart the server with active appeals, reviews and decisions;
   verify persistence and expiry.
4. Fill a bounded queue with a slow endpoint; confirm drops/timeouts and a staff
   alert, not a server freeze.

## Metrics to record

- Harmful messages missed per channel/language.
- Harmless messages incorrectly blocked (false positives).
- Timeout/drop counts and queue depth.
- Appeals and staff reversals.

Review results by language and channel. Do not treat a high model
score as proof. Do not enable automatic bans (the plugin has none).

## Shadow test checklist

- [ ] Rule-based text only, shadow on.
- [ ] ML shadow observation, if enabled.
- [ ] Review false positives and misses.
- [ ] Enable selected text enforcement.
- [ ] Appeal/reversal process verified.
- [ ] Signed chat/formatting behavior documented.
