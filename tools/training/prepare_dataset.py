#!/usr/bin/env python3
"""
Normalize the messy CleanSpeak starter dataset.

The input may contain:
  * compact JSONL records
  * pretty-printed JSON objects
  * Markdown fences and commentary
  * duplicate records
  * malformed lines (for example an extra quote in "text")
  * a truncated final object

This script writes a canonical JSONL dataset, keeps ambiguous REVIEW rows
separate, creates a deterministic stratified train/test split, and records
rejected/suspect input instead of silently throwing it away.

It does not replace {SLUR}. Keep the normalized placeholder dataset for review
and use train_baseline.py with a local lexicon for actual model training.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from collections import Counter, defaultdict
from dataclasses import dataclass, asdict
from json import JSONDecoder
from pathlib import Path
from typing import Any

VALID_LABELS = {"ALLOW", "REPHRASE", "REVIEW", "BLOCK"}
VALID_CATEGORIES = {"NONE", "TARGETED_HATE", "TARGETED_HARASSMENT", "THREAT"}
VALID_TARGET_GROUPS = {
    "none", "sexual_orientation", "gender_identity", "race_ethnicity",
    "religion", "disability", "nationality", "other",
}
VALID_CHANNELS = {"public_chat", "private_message", "voice_transcript"}


@dataclass
class Record:
    id: str
    text: str
    channel: str
    label: str
    category: str
    targeted: bool
    target_group: str
    hard_negative: bool
    language: str
    note: str
    source_line: int
    family: str

    def training_dict(self) -> dict[str, Any]:
        return {
            "id": self.id,
            "text": self.text,
            "channel": self.channel,
            "label": self.label,
            "category": self.category,
            "targeted": self.targeted,
            "target_group": self.target_group,
            "hard_negative": self.hard_negative,
            "language": self.language,
            "note": self.note,
            "family": self.family,
        }


def clean_text(value: Any) -> str:
    text = "" if value is None else str(value)
    text = text.replace("\u00a0", " ").replace("\u200b", "").replace("\u200c", "").replace("\u200d", "")
    text = re.sub(r"\s+", " ", text).strip()
    # Keep the placeholder canonical so downstream substitution is predictable.
    text = re.sub(r"\{\s*slur\s*\}", "{SLUR}", text, flags=re.IGNORECASE)
    return text


def normalize_label(value: Any) -> str:
    return str(value or "").strip().upper()


def normalize_category(value: Any) -> str:
    return str(value or "").strip().upper().replace("-", "_")


def normalize_target_group(value: Any) -> str:
    return str(value or "").strip().lower().replace("-", "_")


def line_of(text: str, offset: int) -> int:
    return text.count("\n", 0, offset) + 1


def decode_objects(text: str) -> tuple[list[tuple[int, dict[str, Any]]], list[dict[str, Any]]]:
    """Recover all complete JSON objects, including pretty-printed ones."""
    decoder = JSONDecoder()
    objects: list[tuple[int, dict[str, Any]]] = []
    rejects: list[dict[str, Any]] = []
    pos = 0
    while True:
        start = text.find("{", pos)
        if start < 0:
            break
        try:
            obj, end = decoder.raw_decode(text[start:])
        except Exception as exc:
            line = line_of(text, start)
            # Try one common repair: an extra double quote before the comma
            # following the text field.
            line_text = text[start:].splitlines()[0] if "\n" in text[start:] else text[start:]
            repaired = re.sub(r'("text"\s*:\s*".*?)""(\s*,)', r'\1"\2', line_text, count=1)
            if repaired != line_text:
                try:
                    obj, end = decoder.raw_decode(repaired)
                    objects.append((start, obj))
                    pos = start + len(repaired)
                    continue
                except Exception:
                    pass
            rejects.append({
                "line": line,
                "reason": f"invalid JSON: {exc}",
                "preview": line_text[:180],
            })
            pos = start + 1
            continue
        if isinstance(obj, dict):
            objects.append((start, obj))
            pos = start + end
        else:
            rejects.append({
                "line": line_of(text, start),
                "reason": "JSON value is not an object",
            })
            pos = start + 1
    return objects, rejects


def canonicalize(obj: dict[str, Any], line: int, source_name: str) -> tuple[Record | None, str | None]:
    text = clean_text(obj.get("text"))
    if not text:
        return None, "missing text"
    if "\ufffd" in text or "\x00" in text:
        return None, "replacement/control character in text"
    if len(text) < 3:
        return None, "text too short"
    if len(text) > 500:
        return None, "text too long for starter data"

    label = normalize_label(obj.get("label"))
    category = normalize_category(obj.get("category"))
    target_group = normalize_target_group(obj.get("target_group", "none"))
    channel = str(obj.get("channel") or "public_chat").strip().lower()
    if label not in VALID_LABELS:
        return None, f"invalid label {label!r}"
    if category not in VALID_CATEGORIES:
        return None, f"invalid category {category!r}"
    if target_group not in VALID_TARGET_GROUPS:
        return None, f"invalid target_group {target_group!r}"
    if channel not in VALID_CHANNELS:
        channel = "public_chat"

    targeted = obj.get("targeted")
    if not isinstance(targeted, bool):
        targeted = label in {"REPHRASE", "BLOCK"} or category in {"TARGETED_HATE", "TARGETED_HARASSMENT", "THREAT"}
    if label == "ALLOW" and category != "NONE":
        return None, "ALLOW row has a non-NONE category"
    if label in {"REPHRASE", "BLOCK"} and category == "NONE":
        return None, "action row has category NONE"
    if category == "THREAT" and label != "BLOCK":
        return None, "THREAT row must be BLOCK"
    if label in {"REPHRASE", "BLOCK"} and not targeted:
        return None, "action row is not marked targeted"

    hard_negative = bool(obj.get("hard_negative", False))
    language = str(obj.get("language") or "en").strip().lower() or "en"
    note = clean_text(obj.get("note"))
    family = clean_text(obj.get("family")) or f"{source_name}:{line}"
    digest = hashlib.sha1((text.lower() + "\n" + label + "\n" + category).encode("utf-8")).hexdigest()[:12]
    return Record(
        id=digest,
        text=text,
        channel=channel,
        label=label,
        category=category,
        targeted=targeted,
        target_group=target_group,
        hard_negative=hard_negative,
        language=language,
        note=note,
        source_line=line,
        family=family,
    ), None


def dedupe(records: list[Record]) -> tuple[list[Record], list[dict[str, Any]], list[dict[str, Any]]]:
    groups: dict[str, list[Record]] = defaultdict(list)
    for record in records:
        key = re.sub(r"\s+", " ", record.text.strip().lower())
        groups[key].append(record)

    unique: list[Record] = []
    duplicates: list[dict[str, Any]] = []
    conflicts: list[dict[str, Any]] = []
    for key, values in groups.items():
        signatures = {
            (r.label, r.category, r.targeted, r.target_group)
            for r in values
        }
        if len(signatures) > 1:
            conflicts.append({
                "text": values[0].text,
                "labels": [
                    {"label": r.label, "category": r.category, "targeted": r.targeted,
                     "note": r.note, "source_line": r.source_line}
                    for r in values
                ],
            })
            continue
        # Prefer the record with the most complete source fields; otherwise the
        # first occurrence is deterministic.
        chosen = sorted(values, key=lambda r: (-len(r.note), r.source_line))[0]
        unique.append(chosen)
        if len(values) > 1:
            duplicates.append({
                "text": chosen.text,
                "count": len(values),
                "kept_source_line": chosen.source_line,
            })
    unique.sort(key=lambda r: (r.source_line, r.id))
    return unique, duplicates, conflicts


SELF_HARM_HINTS = ("kys", "kill your", "kill urself", "go die")


def should_hold_out_allow(record: Record, lexicon_terms: list[str]) -> bool:
    """Return true for ALLOW rows a text-only first model cannot score safely.

    A placeholder slur, self-harm instruction, truncated fragment or raw lexicon
    term in an ALLOW row is a context example. The plugin's rule/context layer
    handles those; the first model would otherwise learn that a bare slur is
    acceptable.
    """
    if record.label != "ALLOW":
        return False
    lower = record.text.lower()
    if "{SLUR}" in record.text.upper():
        return True
    if any(hint in lower for hint in SELF_HARM_HINTS):
        return True
    if record.text.rstrip().endswith("..."):
        return True
    if any(term in lower for term in lexicon_terms):
        return True
    return False


def grouped_split(records: list[Record], test_fraction: float, seed: int) -> tuple[list[Record], list[Record]]:
    """Split template families, not individual paraphrases.

    A generated family (or a source line for the hand-provided starter file) is
    wholly in train or wholly in test. This makes the holdout less deceptive than
    a random split where the same template appears in both sides.
    """
    import random
    by_family: dict[str, list[Record]] = defaultdict(list)
    for record in records:
        by_family[record.family].append(record)

    # Families generally share one label/category. Stratify family groups by that
    # signature so each split gets positive and negative families.
    families_by_stratum: dict[tuple[str, str], list[list[Record]]] = defaultdict(list)
    for family, values in by_family.items():
        signature = (values[0].label, values[0].category)
        families_by_stratum[signature].append(values)

    rng = random.Random(seed)
    train: list[Record] = []
    test: list[Record] = []
    for key in sorted(families_by_stratum):
        families = families_by_stratum[key]
        rng.shuffle(families)
        target = max(1, round(sum(len(v) for v in families) * test_fraction))
        taken = 0
        index = 0
        # Take whole families until close to the target. Avoid taking the last
        # family if it would leave no test sample for this stratum.
        while index < len(families) and taken < target and index < len(families) - 1:
            group = families[index]
            test.extend(group)
            taken += len(group)
            index += 1
        if not test and families:
            if index < len(families):
                test.extend(families[index])
                index += 1
        for group in families[index:]:
            train.extend(group)
    # Guarantee at least one item from every record (e.g. a very small stratum)
    # stays in train.
    known_train = {r.id for r in train}
    if not known_train:
        train = list(records)
        test = []
    elif not test:
        # Degenerate tiny dataset fallback.
        train, test = list(records), []
    train.sort(key=lambda r: (r.family, r.source_line, r.id))
    test.sort(key=lambda r: (r.family, r.source_line, r.id))
    return train, test


def write_jsonl(path: Path, records: list[Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="\n") as handle:
        for record in records:
            if isinstance(record, Record):
                value = record.training_dict()
            else:
                value = record
            handle.write(json.dumps(value, ensure_ascii=False, sort_keys=True) + "\n")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", default="data/noformat.txt")
    parser.add_argument("--out-dir", default="data")
    parser.add_argument("--test-fraction", type=float, default=0.20)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--lexicon", default="data/lexicon.local.json")
    parser.add_argument("--generated", default="data/generated.jsonl")
    args = parser.parse_args()

    input_path = Path(args.input)
    out_dir = Path(args.out_dir)
    if not input_path.exists():
        print(f"input not found: {input_path}", file=sys.stderr)
        return 2
    lex_path = Path(args.lexicon)
    lexicon_terms: list[str] = []
    if lex_path.exists():
        try:
            lexicon_data = json.loads(lex_path.read_text(encoding="utf-8"))
            lexicon_terms = sorted({str(t).lower() for values in lexicon_data.values() for t in values if str(t).strip()}, key=len, reverse=True)
        except Exception as exc:
            print(f"warning: could not read lexicon for quality filtering: {exc}", file=sys.stderr)

    source_specs = [(input_path, "noformat")]
    generated_path = Path(args.generated)
    if generated_path.exists():
        source_specs.append((generated_path, "generated"))

    records: list[Record] = []
    rejects: list[dict[str, Any]] = []
    source_object_counts: dict[str, int] = {}
    for source_path, source_name in source_specs:
        raw = source_path.read_text(encoding="utf-8-sig")
        objects, extraction_rejects = decode_objects(raw)
        source_object_counts[source_name] = len(objects)
        for reject in extraction_rejects:
            reject["source"] = source_name
            rejects.append(reject)
        for offset, obj in objects:
            line = line_of(raw, offset)
            record, reason = canonicalize(obj, line, source_name)
            if record is None:
                rejects.append({"source": source_name, "line": line, "reason": reason,
                                "preview": json.dumps(obj, ensure_ascii=False)[:180]})
            else:
                records.append(record)

    unique, duplicates, conflicts = dedupe(records)
    review = [r for r in unique if r.label == "REVIEW"]
    review_ids = {r.id for r in review}
    train_pool = [r for r in unique if r.id not in review_ids]

    # A placeholder slur in an ALLOW row is a context hard negative. A text-only
    # first model cannot resolve it safely; keep it out of training and out of
    # the split until the context layer is added.
    context_only = [r for r in train_pool if should_hold_out_allow(r, lexicon_terms)]
    context_ids = {r.id for r in context_only}
    trainable = [r for r in train_pool if r.id not in context_ids]
    train, test = grouped_split(trainable, args.test_fraction, args.seed)

    out_dir.mkdir(parents=True, exist_ok=True)
    write_jsonl(out_dir / "clean.jsonl", unique)
    write_jsonl(out_dir / "review.jsonl", review)
    write_jsonl(out_dir / "context_only.jsonl", context_only)
    write_jsonl(out_dir / "train.jsonl", train)
    write_jsonl(out_dir / "test.jsonl", test)
    write_jsonl(out_dir / "rejects.jsonl", rejects)
    write_jsonl(out_dir / "duplicates.jsonl", duplicates)

    report = {
        "sources": source_specs and [str(p) for p, _ in source_specs],
        "source_object_counts": source_object_counts,
        "raw_objects_recovered": sum(source_object_counts.values()),
        "valid_records_before_dedupe": len(records),
        "unique_records": len(unique),
        "duplicates_removed": sum(d["count"] - 1 for d in duplicates),
        "duplicate_groups": len(duplicates),
        "conflicting_duplicate_groups_rejected": len(conflicts),
        "review_rows": len(review),
        "context_only_allow_rows": len(context_only),
        "context_only_examples": [r.text for r in context_only[:20]],
        "trainable_rows": len(trainable),
        "train_rows": len(train),
        "test_rows": len(test),
        "rejected_entries": len(rejects),
        "labels": dict(Counter(r.label for r in unique)),
        "categories": dict(Counter(r.category for r in unique)),
        "target_groups": dict(Counter(r.target_group for r in unique)),
        "train_labels": dict(Counter(r.label for r in train)),
        "test_labels": dict(Counter(r.label for r in test)),
        "train_categories": dict(Counter(r.category for r in train)),
        "test_categories": dict(Counter(r.category for r in test)),
        "conflicts": conflicts[:20],
        "warnings": [
            "Synthetic data; not ground truth.",
            "Review rows are kept separate and are not used for the first model.",
            "ALLOW rows containing {SLUR} are held out as context-only examples.",
            "Placeholders remain in clean.jsonl; substitute only in a local training copy.",
            "Near-duplicate templates may remain; this split is not a guarantee against template leakage.",
        ],
    }
    (out_dir / "dataset_report.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )

    print(json.dumps({
        k: report[k] for k in (
            "raw_objects_recovered", "unique_records", "duplicates_removed",
            "review_rows", "context_only_allow_rows", "train_rows", "test_rows",
            "rejected_entries", "labels", "categories",
        )
    }, indent=2))
    print(f"wrote {out_dir / 'clean.jsonl'}")
    print(f"wrote {out_dir / 'train.jsonl'}")
    print(f"wrote {out_dir / 'test.jsonl'}")
    print(f"wrote {out_dir / 'dataset_report.json'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
