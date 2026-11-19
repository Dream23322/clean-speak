"""Unit tests for text preparation, placeholder expansion and the pipeline."""
from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import support  # noqa: E402

import train_baseline as tb  # noqa: E402


class PrepareTextTests(unittest.TestCase):
    def test_adds_signal_for_known_term_case_insensitively(self):
        prepared = tb.prepare_text("you are a BLORPLE", ["blorple"])
        self.assertTrue(prepared.endswith(tb.HAS_SLUR))

    def test_adds_signal_for_plural_and_punctuation(self):
        for text in ("two blorples here", "blorple!", "(blorple)"):
            with self.subTest(text=text):
                self.assertTrue(tb.prepare_text(text, ["blorple"]).endswith(tb.HAS_SLUR))

    def test_does_not_match_a_substring_inside_another_word(self):
        prepared = tb.prepare_text("this is blorpeline, not the term", ["blorp"])
        self.assertFalse(prepared.endswith(tb.HAS_SLUR))

    def test_adds_signal_for_the_committed_placeholder(self):
        prepared = tb.prepare_text("you are a {SLUR}", [])
        self.assertTrue(prepared.endswith(tb.HAS_SLUR))

    def test_leaves_neutral_text_unchanged(self):
        prepared = tb.prepare_text("I hate mining, it takes forever", ["blorple"])
        self.assertEqual(prepared, "I hate mining, it takes forever")


class ResolveTermsTests(unittest.TestCase):
    def test_uses_group_then_other_then_placeholder(self):
        self.assertEqual(tb.resolve_terms(support.FAKE_LEXICON, "religion"), ["zorbite"])
        self.assertEqual(tb.resolve_terms(support.FAKE_LEXICON, "disability"),
                         support.FAKE_LEXICON["other"])
        self.assertEqual(tb.resolve_terms({}, "other"), ["{SLUR}"])


class ExpandRowsTests(unittest.TestCase):
    def test_expands_each_group_term_and_keeps_placeholders_distinct(self):
        rows = [support.make_row("you are a {SLUR}", label="REPHRASE",
                                 category="TARGETED_HATE", targeted=True,
                                 target_group="religion")]
        expanded = tb.expand_rows(rows, support.FAKE_LEXICON, include_placeholder=True)
        self.assertEqual({row["text"] for row in expanded},
                         {"you are a zorbite", "you are a {SLUR}"})
        # One actual term plus one placeholder row.
        self.assertEqual(len(expanded), 2)
        self.assertEqual({row["variant"] for row in expanded}, {"actual:0", "placeholder"})

    def test_skips_allow_rows_containing_a_placeholder(self):
        rows = [support.make_row("he called me a {SLUR}", label="ALLOW")]
        self.assertEqual(tb.expand_rows(rows, support.FAKE_LEXICON, True), [])

    def test_keeps_ordinary_rows_untouched(self):
        rows = [support.make_row("nice build", label="ALLOW")]
        expanded = tb.expand_rows(rows, support.FAKE_LEXICON, True)
        self.assertEqual(expanded[0]["text"], "nice build")
        self.assertEqual(expanded[0]["variant"], "original")

    def test_deduplicates_identical_expansions(self):
        lexicon = {"other": ["blorple", "blorple"]}
        rows = [support.make_row("you {SLUR}", label="REPHRASE",
                                 category="TARGETED_HATE", targeted=True)]
        expanded = tb.expand_rows(rows, lexicon, include_placeholder=False)
        self.assertEqual(len(expanded), 1)


class PipelineTests(unittest.TestCase):
    def test_pipeline_learns_a_tiny_separable_batch(self):
        texts = [
            "nice build well played",
            "good luck next round",
            "I hate this map",
            "you are a blorple",
            "you are a gribbit",
            "shut up blorple",
        ]
        labels = ["ALLOW", "ALLOW", "ALLOW", "REPHRASE", "REPHRASE", "REPHRASE"]
        pipeline = tb.make_pipeline(42)
        pipeline.fit(texts, labels)
        predictions = list(pipeline.predict(["you are a blorple", "nice build"]))
        self.assertEqual(predictions, ["REPHRASE", "ALLOW"])
        self.assertEqual(set(pipeline.classes_), {"ALLOW", "REPHRASE"})
        self.assertEqual(len(pipeline.predict_proba(["you are a blorple"])[0]), 2)

    def test_pipeline_construction_is_deterministic(self):
        first = tb.make_pipeline(42)
        second = tb.make_pipeline(42)
        self.assertEqual(first.get_params()["classifier__random_state"],
                         second.get_params()["classifier__random_state"])


if __name__ == "__main__":
    unittest.main()
