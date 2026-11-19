# Data directory

`noformat.txt` is the original mixed LLM output. It is kept as received for
provenance. It is not consumed directly by the trainer.

Canonical outputs:

- `clean.jsonl` - unique validated rows with placeholders intact
- `review.jsonl` - ambiguous rows for human review
- `context_only.jsonl` - rows held out from the first text-only model
- `generated.jsonl` - synthetic family-labelled training rows
- `train.jsonl` / `test.jsonl` - family-aware train/test split
- `rejects.jsonl` - malformed, truncated or unrecoverable entries
- `duplicates.jsonl` - duplicate groups
- `dataset_report.json` - counts and quality warnings
- `challenge*.jsonl` - hand-written held-out challenge sets

Local-only generated artifacts (gitignored):

- `lexicon.local.json` - actual terms used to expand `{SLUR}`
- `model/` - model, metrics, labels, test errors and gate report

Do not commit raw slur lists, raw private messages or generated model artifacts
without reviewing the server's data policy. See
`tools/training/README.md` for preparation and training commands.
