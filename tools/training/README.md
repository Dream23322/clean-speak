# Starter dataset and baseline model

This directory builds a CleanSpeak training set from the messy
`data/noformat.txt`, generated synthetic families, and a small curated
supplement, then trains a first-pass text classifier.

This is a **synthetic starter model**, not a production moderation system. It
does not ban anyone. Use shadow mode and human review first.

## What the pipeline does

The source files contain compact JSONL, pretty-printed JSON, Markdown fences,
commentary text, malformed/truncated records and duplicate generations.

`prepare_dataset.py`:

- recovers complete JSON objects from mixed JSONL/pretty output;
- repairs a common extra-quote error;
- fills missing `channel` values;
- normalizes placeholders and fields;
- validates labels/categories and drops contradictory rows;
- keeps ambiguous `REVIEW` rows separate;
- holds out unsafe `ALLOW` examples containing `{SLUR}`, self-harm instructions,
  slur terms or incomplete fragments;
- deduplicates by normalized text;
- splits by **template family**, not individual paraphrase, so paraphrases of one
  family do not cross the train/test boundary;
- writes deterministic train/test files, rejects and an audit report.

`generate_data.py` creates family-labelled synthetic examples. It covers targeted
hate, identity-based exclusion, targeted harassment, real-world threats, spam and
a large hard-negative block (ordinary trash talk, venting, neutral identity
discussion, game-only threats). Actual slurs are never generated or committed;
rows use `{SLUR}` and expansion happens locally at training time.

## Current counts

After the latest generation and cleaning run:

| Item | Count |
|---|---:|
| Raw objects recovered | 4,301 |
| Unique rows | 4,187 |
| Duplicates removed | 76 |
| Ambiguous `REVIEW` rows held out | 31 |
| Context-unsafe `ALLOW` rows held out | 5 |
| Training rows before expansion | 3,321 |
| Test rows before expansion | 830 |
| Rejected/truncated fragments | 23 |

Training expands `{SLUR}` with the private lexicon and adds a `__HAS_SLUR__`
feature, which produces 3,672 expanded train rows and 888 expanded test rows.
The curated `extra_examples.jsonl` supplement is merged into training only.

## Files

- `data/clean.jsonl` - canonical unique rows, placeholders retained
- `data/review.jsonl` - ambiguous cases for human review, not used for training
- `data/context_only.jsonl` - held-out context examples, not used for training
- `data/train.jsonl` / `data/test.jsonl` - family-aware split
- `data/rejects.jsonl` - malformed/truncated input entries
- `data/duplicates.jsonl` - duplicate groups and kept source line
- `data/dataset_report.json` - counts, labels, warnings
- `data/challenge*.jsonl` - hand-written held-out challenge sets
- `data/model/` - generated local model, metrics, test errors (gitignored)

`data/lexicon.local.json` is a local mapping from `target_group` to actual
terms. It is gitignored and must not be published. The clean dataset keeps
`{SLUR}` rather than raw terms. Training expands placeholders locally.

## Install

```sh
py -m pip install -r tools/training/requirements.txt
```

## Prepare

```sh
py tools/training/generate_data.py
py tools/training/prepare_dataset.py
```

This is idempotent for the same inputs and seed.

## Train

```sh
py tools/training/train_baseline.py
```

The script trains:

- a 4-class category model (`NONE`, `TARGETED_HATE`,
  `TARGETED_HARASSMENT`, `THREAT`);
- a 3-class risk model (`NONE`, `TARGETED`, `THREAT`);
- a binary targeted model;
- a 3-class action model (`ALLOW`, `REPHRASE`, `BLOCK`).

It writes `data/model/model.joblib`, `metrics.json`, `labels.json`,
`test_errors.jsonl` and model-card-style metadata. The model artifact is
gitignored because it contains vocabulary derived from a private lexicon.

## Bundled model

The plugin ships a Java-native hashed model so first-run installs need no
sidecar. After training, export it:

```sh
py tools/training/export_bundled_model.py
```

This writes `src/main/resources/ml/bundled_model.json.gz` and
`data/model/bundled_metrics.json`. The artifact stores idf values and linear
coefficients, not a token vocabulary or slur list. `BundledTextClassifier`
implements the same FNV-1a hashing and feature layout in Java; parity examples
inside the artifact are checked by `BundledTextClassifierTest`.

## Acceptance gates

A trained artifact is not accepted just because training exited zero. Run:

```sh
py tools/training/check_gates.py
```

Exit code `0` means every gate passed; `1` means at least one failed; `2` means
the config or required files could not be read. The gate report is written to
`data/model/gate_report.json` and never prints player text or lexicon terms.

The default policy lives in `tools/training/gates.json`:

- required artifacts exist, load, and contain `risk_model`/`action_model`;
- family-aware test metrics clear risk/action/targeted floors and a minimum
  test-row count;
- the dataset clears minimum unique/train/test counts, the report matches the
  written files, and train/test have no family or text overlap;
- each fresh challenge is evaluated live and must clear risk accuracy,
  targeted precision/recall, scored-row count and an error budget;
- the final challenge has no overlap with train/test/the curated supplement;
- the sidecar scores ordinary venting low and targeted/threat text high, with
  every returned score in `[0,1]`.

Do not lower a gate just to make a failing model pass. Change the floor only
after reviewing why the new model is better for the policy, and record that
decision with the report.

## Baseline metrics

Latest family-aware split, after placeholder expansion:

| Head | Accuracy | Macro F1 |
|---|---:|---:|
| Risk (`NONE`/`TARGETED`/`THREAT`) | 97.97% | 96.01% |
| Action (`ALLOW`/`REPHRASE`/`BLOCK`) | 98.09% | 96.09% |
| Binary targeted | 97.91% precision, 98.66% recall | 98.29% F1 |
| 4-class subtype category | 93.47% | 83.44% |

Cross-validation on the expanded training set gives 95.45% category accuracy
and 75.39% macro F1. The subtype classifier is still weaker than the broad risk
head; use the risk/targeted score for moderation and do not present the subtype
label as reliable.

## Held-out challenge evaluation

`evaluate_challenge.py` runs the model against a hand-written challenge file. It
expands `{SLUR}` locally, excludes `REVIEW` rows, quotation/reporting rows (the
rule/context layer handles those) and spam/scam rows (deterministic rules handle
those), and reports the remaining scored rows.

Latest fresh challenge (`data/challenge11.jsonl`, never added to training):

| Metric | Result |
|---|---:|
| Challenge rows | 75 |
| Scored expanded rows | 89 |
| Risk/action accuracy | 100% |
| Targeted precision | 100% |
| Targeted recall | 100% |
| Targeted F1 | 100% |
| Errors | 0 |

This number is **not** evidence of production safety. The challenge set was
written by the same author, contains English only, and is much smaller and
cleaner than real chat. Earlier fresh challenges scored between 90.5% and 100%
before their error patterns were turned into new paraphrase families; that is
the iterative development loop, not an unbiased benchmark. Keep a genuinely
unseen challenge (or, better, staff-reviewed live traffic) for honest reporting.

Run:

```sh
py tools/training/evaluate_challenge.py --challenge data/challenge11.jsonl \
  --out data/model/challenge11_metrics.json
```

## Tests

Run the model-pipeline suite:

```sh
py -m unittest discover -s tools/training/tests -q
```

The suite is dependency-light (standard library `unittest` plus the installed
training dependencies) and does **not** need the private lexicon or a trained
model. It currently covers 94 tests:

- messy JSON recovery, the extra-quote repair, invalid/truncated input and
  non-object handling;
- placeholder/whitespace normalization and label/category/targeted invariants;
- duplicate collapsing, conflicting duplicate rejection and context-holdout
  rules;
- family-aware splitting: no family crosses train/test, every stratum keeps
  training rows, and the split is deterministic;
- generator schema, determinism, no duplicate texts, hard-negative coverage and
  the targeted/identity distinctions;
- `{SLUR}` expansion, group fallback, ALLOW context-row exclusion and the
  `__HAS_SLUR__` word-boundary behavior;
- a tiny end-to-end pipeline fit/predict check;
- challenge expansion, context/rule-layer exclusions, coherent risk/action
  reconciliation and precision/recall edge cases;
- the sidecar score contract and live HTTP routes (health, classify, malformed
  input, oversized body, 404);
- committed-data quality: train/test family disjointness, final-challenge
  leakage guards, report/file count consistency, and a raw-lexicon scan when
  `data/lexicon.local.json` is present;
- an optional regression gate that runs when the trained artifact and private
  lexicon exist: minimum final-challenge accuracy and low/high sidecar scores
  for ordinary venting versus targeted text.

The Java side has additional wire-contract tests in
`src/test/java/dev/cleanspeak/core/ml/HttpTextClassifierTest.java`; run them with
`./gradlew :test --tests 'dev.cleanspeak.core.ml.HttpTextClassifierTest'`.

## Local inference server

```sh
py tools/training/serve_model.py --host 127.0.0.1 --port 8091
```

Then in `plugins/CleanSpeak/config.yml`:

```yaml
text:
  ml:
    enabled: true
    endpoint: "http://127.0.0.1:8091/classify"
    allow-remote-endpoint: false
    model-version: "cleanspeak-baseline-v1"
    timeout-ms: 800
    may-block: false
```

The server returns `TARGETED_HARASSMENT` and `THREAT` scores. It is local,
unauthenticated, and must not be exposed publicly. The plugin's rule layer still
runs and catches exact configured terms independently.

Verified locally:

```text
"you are a worthless {SLUR}"       -> TARGETED_HARASSMENT 0.999
"I hate mining, it takes forever"   -> TARGETED_HARASSMENT 0.033
"I will find you and hurt you"      -> THREAT 0.996
"my friend is transgender and plays here" -> TARGETED_HARASSMENT 0.069
```

## Important limitations

- Synthetic data, not ground truth.
- English-only training and evaluation.
- The model does not understand quotation/reported abuse; those rows are held
  out deliberately and belong to the rule/context layer.
- It can miss hate that does not contain a known slur or a familiar pattern, and
  it can flag unusual but harmless wording.
- Slang, reclaimed terms, code-switching, irony and adversarial obfuscation are
  not solved by this baseline.
- Do not use it for bans or permanent mutes.
- Add staff-reviewed shadow-mode errors and external reviewed data before
  enabling any automatic text enforcement.
