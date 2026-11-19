# Optional ML and model guidance

CleanSpeak has a replaceable `TextClassifier` interface with two backends: a
bundled Java model and an optional local HTTP classifier. The rule engine is
always available; ML is a supporting signal, never a ban authority.

## Defaults

- A bundled hashed text model is included in the jar and enabled by default.
- The bundled model runs in-process; it needs no Python, sidecar, download or
  network access.
- Keep `text.ml.endpoint: ""` to use the bundled model. Set an endpoint to use an
  external localhost sidecar instead.
- Local/private endpoints only, unless `allow-remote-endpoint: true`.
- A model score can request `REVIEW` by default; it cannot alone block unless
  `text.ml.may-block: true`.
- Text enforcement is on by default. Set `text.shadow-mode: true` for staged
  observation; the bundled model then logs without stopping delivery.
- If the model fails, local rules continue. Decisions are marked `fallback`.
  This fallback can miss harmful content the model would have caught.

## Bundled model

The shipped classifier is a hashed linear model trained on the synthetic corpus.
`BundledTextClassifier` reads `/ml/bundled_model.json.gz` from the jar and
performs feature hashing and logistic-regression inference in Java. The artifact
contains idf values and coefficients, not a token vocabulary or slur list.

Bundled-model metrics from the current export:

| Head | Result |
|---|---:|
| Test risk accuracy | 97.86% |
| Test targeted precision / recall / F1 | 98.09% / 98.28% / 98.19% |
| Challenge risk accuracy | 89.89% |
| Challenge targeted recall | 85.0% |

The challenge number is lower than the sidecar because the bundled export is
trained without the private `__HAS_SLUR__` feature; exact known slurs are caught
independently by the rule layer. Java/Python score parity is asserted by
`BundledTextClassifierTest` using examples embedded in the artifact.

## Included starter baseline

`tools/training/` can also build a small TF-IDF word + character n-gram logistic
regression model and serve it through `tools/training/serve_model.py` when an
external sidecar is preferred. It is a starter baseline, not a production model.

Latest local artifacts (not committed; the vocabulary is derived from a private
lexicon):

| Head | Result |
|---|---:|
| Risk accuracy / macro F1 | 97.97% / 96.01% |
| Action accuracy / macro F1 | 98.09% / 96.09% |
| Binary targeted precision / recall / F1 | 97.91% / 98.66% / 98.29% |
| Subtype category accuracy / macro F1 | 93.47% / 83.44% |

A fresh hand-written challenge (`data/challenge11.jsonl`, 75 rows, 89 scored
expanded rows) scored 100% risk/action accuracy and 0 errors. This is **not** a
production-safety claim: it is English-only, written by the same project, and
much smaller and cleaner than real chat. Earlier fresh challenges scored
90.5-100% before their error patterns were added as new training families. Keep
an unseen challenge or staff-reviewed live traffic for honest reporting.

Acceptance gates make the difference between "training ran" and "this artifact
is acceptable". Run them after training:

```sh
py tools/training/check_gates.py
```

Thresholds are in `tools/training/gates.json`; the script checks artifact
loading, test-set metric floors, dataset thresholds and leakage, live challenge
accuracy/recall/error budget, and sidecar behavior. A failing gate exits non-zero
and writes `data/model/gate_report.json`.

The baseline uses a `{SLUR}` placeholder in committed data and expands it from a
gitignored local lexicon at training/inference time. It is intended to complement
the rule/context layer, not replace it.

Model tests are separate from the Java suite:

```sh
py -m unittest discover -s tools/training/tests -q
```

They cover dataset recovery/validation, family-aware splitting, generator
invariants, placeholder expansion, challenge scoring/coherence, the sidecar HTTP
contract and committed-data leakage guards. `BundledTextClassifierTest` checks
the shipped model resource and Java/Python score parity;
`HttpTextClassifierTest` covers the optional external HTTP wire contract.

## HTTP contract

CleanSpeak sends:

```http
POST /classify
Content-Type: application/json

{
  "text": "player message",
  "channel": "paper:public-chat",
  "conversation": "paper:public-chat:public",
  "policy_version": "2026.11.18.1",
  "review_thresholds": {
    "TARGETED_HARASSMENT": 0.55,
    "HATE_SLUR": 0.55,
    "THREAT": 0.50
  }
}
```

The server should return:

```json
{
  "ok": true,
  "scores": {
    "TARGETED_HARASSMENT": 0.12,
    "HATE_SLUR": 0.03,
    "THREAT": 0.01,
    "SPAM": 0.20,
    "SCAM_ADVERTISING": 0.01
  },
  "confidence": 0.93,
  "model_version": "my-small-classifier-v1"
}
```

Unknown categories are ignored. Scores and confidence are clamped to `[0,1]`.
`model_version` is stored with the decision. A non-2xx response, malformed JSON,
timeout or `ok:false` is a model failure.

The plugin applies an HTTP timeout (`text.ml.timeout-ms`) and opens a circuit
after repeated failures. It does not retry automatically. Per-category model
thresholds are configured in `checks.yml` under each check's `ml` section. The
model worker should have its own bounded concurrency and must not log player text
unless the owner explicitly wants that.

`/cs status` reports the last observed model status, not historical accuracy.

## Configuring a local classifier

Example: a small ONNX/PyTorch toxic-comment classifier wrapped in a localhost
HTTP service. No large language model is required. Choose a model with a license
that permits your use, document supported languages and evaluate it on your
community.

```yaml
text:
  ml:
    enabled: true
    endpoint: "http://127.0.0.1:8091/classify"
    allow-remote-endpoint: false
    model-version: "my-small-classifier-v1"
    timeout-ms: 800
    may-block: false
    min-confidence: 0.55
```

Hardware guidance for a small text classifier:

- Tiny transformer/ONNX model: CPU is usually enough for a few requests/second.
- GPU is useful only at high chat volume; otherwise queueing and latency matter
  more than raw throughput.
- Benchmark with your expected messages/second and keep the endpoint local.
- The plugin's bounded moderation queue protects the server; a slow sidecar does
  not block the Paper main thread.

## Model limitations

- Scores are estimates, not proof.
- Models can be biased or weak for slang, code-switching, reclaimed terms,
  irony, quotation and reporting.
  accurate.
- Do not automatically ban, kick or permanently mute on a model score.
- Keep a human review path and an appeal route.
- Record model/policy versions with decisions for later analysis.

## Security and privacy

- Bind local workers to `127.0.0.1`/private interfaces.
- Do not expose them to the public internet.
- Remote endpoints require explicit config and shift data handling to that
  service; update your player notice.
- Player text is untrusted data. No prompt/instruction inside it should be
  treated as control input. The HTTP contract is classification-only, not an LLM
  chat prompt.
- Do not log raw private messages by default.

## Model failure policy

Configured fallback:

1. Continue local rule checks.
2. Block clear rule violations.
3. Allow messages that pass them, marking `fallback=true` and alerting staff.
4. Keep health/status visible.

This is intentionally not full coverage. If you require stricter behavior when
the model is down, add your own route policy (for example, disable private
messages or hold them for review) rather than issuing automatic bans.
