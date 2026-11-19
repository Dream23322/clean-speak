"""Tests for challenge expansion, exclusions, coherence and metric math."""
from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import support  # noqa: E402

import evaluate_challenge as ec  # noqa: E402


class ExpandTests(unittest.TestCase):
    def test_leaves_non_placeholder_text_untouched(self):
        rows = [support.make_row("nice build")]
        expanded = ec.expand(rows, support.FAKE_LEXICON)
        self.assertEqual(expanded[0]["text"], "nice build")
        self.assertEqual(expanded[0]["variant"], "original")

    def test_uses_group_terms_then_other_fallback(self):
        rows = [
            support.make_row("you {SLUR}", label="REPHRASE", category="TARGETED_HATE",
                             targeted=True, target_group="religion"),
            support.make_row("you {SLUR}", label="REPHRASE", category="TARGETED_HATE",
                             targeted=True, target_group="disability"),
        ]
        expanded = ec.expand(rows, support.FAKE_LEXICON)
        self.assertEqual([row["text"] for row in expanded], [
            "you zorbite", "you blorple", "you gribbit",
        ])

    def test_keeps_a_placeholder_row_when_no_terms_exist(self):
        rows = [support.make_row("you {SLUR}", label="REPHRASE",
                                 category="TARGETED_HATE", targeted=True)]
        expanded = ec.expand(rows, {})
        self.assertEqual(len(expanded), 1)
        self.assertEqual(expanded[0]["text"], "you {SLUR}")
        self.assertEqual(expanded[0]["variant"], "placeholder")


class ExclusionTests(unittest.TestCase):
    def test_context_rows_are_detected_by_note_or_text(self):
        cases = [
            support.make_row("a message", note="reported abuse; context-only challenge"),
            support.make_row("a message", note="education/question; context-only challenge"),
            support.make_row("a message", note="meta-discussion; context-only challenge"),
            support.make_row("he called me something"),
            support.make_row("someone called me something"),
            support.make_row("what does that mean"),
        ]
        for row in cases:
            with self.subTest(row=row):
                self.assertTrue(ec.context_only(row))

    def test_ordinary_rows_are_not_context(self):
        for text in ("nice build", "I hate mining", "he is bad at PvP"):
            with self.subTest(text=text):
                self.assertFalse(ec.context_only(support.make_row(text)))

    def test_spam_and_scam_are_rule_layer(self):
        for category in ("SPAM", "SCAM_ADVERTISING"):
            with self.subTest(category=category):
                self.assertTrue(ec.rule_layer_only(support.make_row("x", category=category)))
        self.assertFalse(ec.rule_layer_only(support.make_row("x")))


class RiskAndCoherenceTests(unittest.TestCase):
    def test_expected_risk_maps_categories(self):
        self.assertEqual(ec.expected_risk(support.make_row("x")), "NONE")
        self.assertEqual(ec.expected_risk(support.make_row("x", category="TARGETED_HATE")), "TARGETED")
        self.assertEqual(ec.expected_risk(support.make_row("x", category="THREAT")), "THREAT")

    def test_coherent_action_never_allows_or_escalates_wrongly(self):
        cases = [
            ("NONE", "ALLOW", "ALLOW"),
            ("NONE", "BLOCK", "ALLOW"),
            ("NONE", "REPHRASE", "ALLOW"),
            ("TARGETED", "ALLOW", "REPHRASE"),
            ("TARGETED", "REPHRASE", "REPHRASE"),
            ("TARGETED", "BLOCK", "BLOCK"),
            ("THREAT", "ALLOW", "BLOCK"),
            ("THREAT", "REPHRASE", "BLOCK"),
            ("THREAT", "BLOCK", "BLOCK"),
        ]
        for risk, action, expected in cases:
            with self.subTest(risk=risk, action=action):
                self.assertEqual(ec.coherent_action(risk, action), expected)


class MetricsTests(unittest.TestCase):
    def test_perfect_and_imperfect_metrics(self):
        perfect = ec.metrics(["A", "B"], ["A", "B"], ["A", "B"])
        self.assertEqual(perfect["accuracy"], 1.0)
        self.assertEqual(perfect["per_label"]["A"]["f1"], 1.0)

        imperfect = ec.metrics(["A", "B"], ["A", "B"], ["B", "B"])
        self.assertEqual(imperfect["accuracy"], 0.5)
        self.assertEqual(imperfect["per_label"]["A"]["recall"], 0.0)
        self.assertEqual(imperfect["per_label"]["B"]["precision"], 0.5)
        self.assertEqual(imperfect["confusion"]["A"]["B"], 1)

    def test_empty_predictions_do_not_divide_by_zero(self):
        empty = ec.metrics(["A"], [], [])
        self.assertEqual(empty["accuracy"], 0.0)
        self.assertEqual(empty["per_label"]["A"]["precision"], 0.0)


class EvaluateTests(unittest.TestCase):
    def _rows(self):
        return [
            support.make_row("nice build"),
            support.make_row("you are a {SLUR}", label="REPHRASE",
                             category="TARGETED_HATE", targeted=True, note="directed slur"),
            support.make_row("I will hurt you", label="BLOCK", category="THREAT",
                             targeted=True, note="physical threat"),
            support.make_row("maybe", label="REVIEW", category="NONE", note="ambiguous"),
            support.make_row("he called me a name", label="ALLOW",
                             note="reported abuse; context-only challenge"),
            support.make_row("BUY BUY BUY", label="REPHRASE", category="SPAM",
                             note="flooding"),
        ]

    def _correct_predictors(self):
        def risk(rows):
            return [ec.expected_risk(row) for row in rows]

        def action(rows):
            return [
                "BLOCK" if row["category"] == "THREAT"
                else "REPHRASE" if row["label"] == "REPHRASE"
                else "ALLOW"
                for row in rows
            ]

        return risk, action

    def test_scores_only_eligible_rows_and_reports_exclusions(self):
        risk, action = self._correct_predictors()
        report = ec.evaluate(self._rows(), support.FAKE_LEXICON, risk, action)
        self.assertEqual(report["challenge_rows"], 6)
        # neutral 1 + targeted 2 terms + threat 1
        self.assertEqual(report["expanded_rows_scored"], 4)
        self.assertEqual(report["review_rows_excluded"], 1)
        self.assertEqual(report["context_rows_excluded"], 1)
        self.assertEqual(report["rule_layer_rows_excluded"], 1)
        self.assertEqual(report["errors"], [])
        self.assertEqual(report["risk"]["accuracy"], 1.0)
        self.assertEqual(report["action"]["accuracy"], 1.0)
        self.assertEqual(report["targeted_binary"]["tp"], 3)

    def test_wrong_predictions_are_reported_with_coherent_action(self):
        def risk(rows):
            return ["NONE" for _ in rows]

        def action(rows):
            return ["ALLOW" for _ in rows]

        report = ec.evaluate(self._rows(), support.FAKE_LEXICON, risk, action)
        self.assertEqual(len(report["errors"]), 3)
        for error in report["errors"]:
            self.assertEqual(error["predicted_risk"], "NONE")
            self.assertEqual(error["predicted_action"], "ALLOW")

    def test_risk_action_disagreement_is_reconciled(self):
        rows = [support.make_row("you are a {SLUR}", label="REPHRASE",
                                 category="TARGETED_HATE", targeted=True)]
        report = ec.evaluate(
            rows,
            {"other": ["blorple"]},
            lambda items: ["TARGETED" for _ in items],
            lambda items: ["ALLOW" for _ in items],
        )
        self.assertEqual(report["errors"], [])
        self.assertEqual(report["action_model_standalone"]["accuracy"], 0.0)
        self.assertEqual(report["action"]["accuracy"], 1.0)

    def test_predictor_length_mismatch_is_an_error(self):
        with self.assertRaises(ValueError):
            ec.evaluate(
                [support.make_row("nice build")],
                support.FAKE_LEXICON,
                lambda items: [],
                lambda items: ["ALLOW"] * len(items),
            )


if __name__ == "__main__":
    unittest.main()
