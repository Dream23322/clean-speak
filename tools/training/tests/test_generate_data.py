"""Tests for the synthetic family generator and its label invariants."""
from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import support  # noqa: E402

import generate_data as gd  # noqa: E402


class GenerateDataTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.rows = gd.generate(42)
        cls.by_text = {row["text"].lower(): row for row in cls.rows}

    def test_generates_a_useful_volume_and_families(self):
        self.assertGreater(len(self.rows), 1000)
        self.assertGreater(len({row["family"] for row in self.rows}), 500)

    def test_rows_have_a_consistent_schema(self):
        required = {
            "text", "channel", "label", "category", "targeted", "target_group",
            "hard_negative", "language", "family", "note", "source",
        }
        for row in self.rows:
            with self.subTest(text=row["text"]):
                self.assertEqual(set(row), required)
                self.assertEqual(row["source"], "generated")
                self.assertEqual(row["channel"], "public_chat")
                self.assertEqual(row["language"], "en")
                self.assertTrue(row["text"].strip())

    def test_labels_and_categories_are_consistent(self):
        for row in self.rows:
            with self.subTest(text=row["text"]):
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
                    self.assertIn(row["label"], {"REPHRASE", "BLOCK"})

    def test_no_duplicate_texts(self):
        texts = [row["text"].lower() for row in self.rows]
        self.assertEqual(len(texts), len(set(texts)))

    def test_never_emits_a_raw_term_only_placeholders(self):
        for row in self.rows:
            with self.subTest(text=row["text"]):
                self.assertNotIn("{ slur }", row["text"].lower())
                # Any brace-wrapped term must be the canonical placeholder.
                if "{" in row["text"]:
                    self.assertIn("{SLUR}", row["text"])

    def test_hard_negatives_cover_venting_and_game_threats(self):
        for text in [
            "I hate mining, it takes forever",
            "I hate this map",
            "I'll find your base",
            "you're trash at PvP, uninstall",
        ]:
            with self.subTest(text=text):
                row = self.by_text[text.lower()]
                self.assertEqual(row["label"], "ALLOW")
                self.assertEqual(row["category"], "NONE")
                self.assertTrue(row["hard_negative"])

    def test_neutral_identity_rows_are_hard_negatives(self):
        rows = [
            row for row in self.rows
            if row["hard_negative"]
            and row["label"] == "ALLOW"
            and "transgender" in row["text"].lower()
        ]
        self.assertTrue(rows)

    def test_targeted_patterns_cover_the_requested_distinctions(self):
        cases = {
            "women are bad at PvP": "TARGETED_HATE",
            "nobody wants your kind on this team": "TARGETED_HATE",
            "your sexuality is a problem": "TARGETED_HATE",
            "Jewish people control the economy on this server": "TARGETED_HATE",
            "please disappear from this server": "TARGETED_HARASSMENT",
            "you're a burden to every team": "TARGETED_HARASSMENT",
            "I will find you and hurt you": "THREAT",
        }
        for text, category in cases.items():
            with self.subTest(text=text):
                row = self.by_text[text.lower()]
                self.assertEqual(row["category"], category)
                self.assertTrue(row["targeted"])
                self.assertEqual(row["label"], "BLOCK" if category == "THREAT" else "REPHRASE")

    def test_generation_is_deterministic(self):
        second = gd.generate(42)
        self.assertEqual([row["text"] for row in self.rows],
                         [row["text"] for row in second])
        self.assertEqual([row["family"] for row in self.rows],
                         [row["family"] for row in second])

    def test_spam_and_scam_stay_out_of_the_hate_and_threat_classes(self):
        rows = [row for row in self.rows if row["category"] in {"SPAM", "SCAM_ADVERTISING"}]
        self.assertTrue(rows)
        for row in rows:
            with self.subTest(text=row["text"]):
                self.assertNotIn(row["category"], {
                    "TARGETED_HATE", "TARGETED_HARASSMENT", "THREAT",
                })
                if row["category"] == "SCAM_ADVERTISING":
                    self.assertIn("example.com", row["text"].lower())


if __name__ == "__main__":
    unittest.main()
