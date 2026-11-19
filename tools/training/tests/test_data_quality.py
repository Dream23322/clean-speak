"""Data-quality and leakage guards for the committed model datasets."""
from __future__ import annotations

import json
import re
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import support  # noqa: E402

DATA = support.ROOT / "data"
TRAINING = support.ROOT / "tools" / "training"

CORE_FILES = [
    "clean.jsonl", "train.jsonl", "test.jsonl",
    "review.jsonl", "context_only.jsonl",
]
GENERATED_FILE = "generated.jsonl"
CHALLENGE_FILES = sorted(DATA.glob("challenge*.jsonl"))


def load(name: str) -> list[dict]:
    return support.load_jsonl(DATA / name)


def all_challenge_rows() -> list[tuple[str, dict]]:
    rows = []
    for path in CHALLENGE_FILES:
        for row in support.load_jsonl(path):
            rows.append((path.name, row))
    return rows


class CommittedDataTests(unittest.TestCase):
    def test_core_files_exist_and_parse(self):
        for name in CORE_FILES + [GENERATED_FILE]:
            with self.subTest(file=name):
                self.assertTrue((DATA / name).exists(), name)
                self.assertTrue(load(name))

    def test_all_rows_have_required_fields_and_valid_types(self):
        canonical_fields = {
            "id", "text", "channel", "label", "category", "targeted",
            "target_group", "hard_negative", "language", "note", "family",
        }
        generated_fields = (canonical_fields - {"id"}) | {"source"}
        for name in CORE_FILES:
            for row in load(name):
                with self.subTest(file=name, text=row.get("text")):
                    self.assertEqual(set(row), canonical_fields)
                    self.assertIsInstance(row["text"], str)
                    self.assertTrue(row["text"].strip())
                    self.assertIsInstance(row["targeted"], bool)
                    self.assertIsInstance(row["hard_negative"], bool)
        for row in load(GENERATED_FILE):
            with self.subTest(file=GENERATED_FILE, text=row.get("text")):
                self.assertEqual(set(row), generated_fields)
                self.assertEqual(row["source"], "generated")
                self.assertIsInstance(row["targeted"], bool)
                self.assertIsInstance(row["hard_negative"], bool)

    def test_challenge_rows_have_required_fields(self):
        self.assertTrue(CHALLENGE_FILES)
        for name, row in all_challenge_rows():
            with self.subTest(file=name, text=row.get("text")):
                self.assertEqual(
                    set(row),
                    {"text", "channel", "label", "category", "targeted",
                     "target_group", "hard_negative", "language", "note",
                     "family", "source"},
                )
                self.assertEqual(row["source"], name.replace(".jsonl", ""))
                self.assertTrue(row["family"])

    def test_label_category_and_targeted_invariants(self):
        for name in CORE_FILES + [GENERATED_FILE]:
            for row in load(name):
                with self.subTest(file=name, text=row["text"]):
                    self.assertIn(row["label"], {"ALLOW", "REPHRASE", "BLOCK", "REVIEW"})
                    self.assertIn(row["category"], {
                        "NONE", "TARGETED_HATE", "TARGETED_HARASSMENT", "THREAT",
                        "SPAM", "SCAM_ADVERTISING",
                    })
                    if row["label"] == "ALLOW":
                        self.assertEqual(row["category"], "NONE")
                    if row["category"] == "NONE":
                        self.assertIn(row["label"], {"ALLOW", "REVIEW"})
                    if row["category"] == "THREAT":
                        self.assertEqual(row["label"], "BLOCK")
                    if row["category"] in {"TARGETED_HATE", "TARGETED_HARASSMENT", "THREAT"}:
                        self.assertTrue(row["targeted"])
                        if row["label"] != "REVIEW":
                            self.assertIn(row["label"], {"REPHRASE", "BLOCK"})

    def test_no_duplicate_text_inside_a_file(self):
        for name in CORE_FILES + [GENERATED_FILE] + [path.name for path in CHALLENGE_FILES]:
            rows = load(name)
            texts = [row["text"].lower() for row in rows]
            with self.subTest(file=name):
                self.assertEqual(len(texts), len(set(texts)))

    def test_train_and_test_families_do_not_overlap(self):
        train = load("train.jsonl")
        test = load("test.jsonl")
        self.assertEqual(
            {row["family"] for row in train} & {row["family"] for row in test},
            set(),
        )

    def test_final_challenge_has_no_training_leakage(self):
        # Older challenge files were development sets: their errors were
        # deliberately turned into training families. challenge11 is the final
        # held-out set and must not overlap train/test/extra.
        train = load("train.jsonl")
        test = load("test.jsonl")
        extra_path = TRAINING / "extra_examples.jsonl"
        extra = support.load_jsonl(extra_path) if extra_path.exists() else []
        known = {
            (row["text"].lower(), row["label"], row["category"])
            for row in train + test + extra
        }
        final = support.load_jsonl(DATA / "challenge11.jsonl")
        for row in final:
            with self.subTest(text=row["text"]):
                self.assertNotIn(
                    (row["text"].lower(), row["label"], row["category"]), known
                )

    def test_committed_data_contains_no_raw_lexicon_terms(self):
        lexicon_path = DATA / "lexicon.local.json"
        if not lexicon_path.exists():
            self.skipTest("private lexicon is not present on this checkout")
        lexicon = json.loads(lexicon_path.read_text(encoding="utf-8"))
        terms = sorted({
            str(term).strip().lower()
            for values in lexicon.values() for term in values if str(term).strip()
        })
        if not terms:
            self.skipTest("lexicon is empty")
        files = list(DATA.glob("*.jsonl"))
        for path in files:
            text = path.read_text(encoding="utf-8", errors="ignore").lower()
            found = [
                term for term in terms
                if re.search(r"(?<!\w)" + re.escape(term) + r"(?!\w)", text)
            ]
            with self.subTest(file=path.name):
                self.assertEqual(found, [], f"{path.name} contains raw lexicon terms")

    def test_dataset_report_matches_written_files(self):
        report = json.loads((DATA / "dataset_report.json").read_text(encoding="utf-8"))
        self.assertEqual(report["unique_records"], len(load("clean.jsonl")))
        self.assertEqual(report["train_rows"], len(load("train.jsonl")))
        self.assertEqual(report["test_rows"], len(load("test.jsonl")))
        self.assertEqual(report["review_rows"], len(load("review.jsonl")))
        self.assertEqual(
            report["context_only_allow_rows"], len(load("context_only.jsonl"))
        )
        duplicates = load("duplicates.jsonl")
        self.assertEqual(
            report["duplicates_removed"],
            sum(row["count"] - 1 for row in duplicates),
        )

    def test_final_challenge_covers_the_intended_behaviour(self):
        path = DATA / "challenge11.jsonl"
        self.assertTrue(path.exists())
        rows = support.load_jsonl(path)
        categories = {row["category"] for row in rows}
        self.assertEqual(
            categories,
            {"NONE", "TARGETED_HATE", "TARGETED_HARASSMENT", "THREAT",
             "SPAM", "SCAM_ADVERTISING"},
        )
        self.assertTrue(any(
            row["label"] == "ALLOW" and "venting" in row["note"]
            for row in rows
        ))
        self.assertTrue(any(
            row["label"] == "ALLOW" and "neutral identity" in row["note"]
            for row in rows
        ))
        self.assertTrue(any(
            "{SLUR}" in row["text"] and row["label"] == "REPHRASE"
            for row in rows
        ))
        self.assertTrue(any(
            row["category"] == "THREAT" and row["label"] == "BLOCK"
            for row in rows
        ))


if __name__ == "__main__":
    unittest.main()
