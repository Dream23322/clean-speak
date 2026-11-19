"""Tests for the model acceptance gates."""
from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import support  # noqa: E402

import check_gates as cg  # noqa: E402


class FakeActionModel:
    def predict(self, texts):
        output = []
        for text in texts:
            lowered = text.lower()
            if "threat" in lowered:
                output.append("BLOCK")
            elif "targeted" in lowered or "__has_slur__" in lowered:
                output.append("REPHRASE")
            else:
                output.append("ALLOW")
        return output


def challenge_row(text, label, category, targeted):
    return {
        "text": text, "channel": "public_chat", "label": label,
        "category": category, "targeted": targeted, "target_group": "other",
        "hard_negative": label == "ALLOW", "language": "en",
        "note": "gate test", "family": "gate.test", "source": "gate",
    }


class MetricGateTests(unittest.TestCase):
    def test_metric_value_handles_dotted_paths_and_bad_values(self):
        data = {"a": {"b": 0.5}, "nan": float("nan"), "flag": True}
        self.assertEqual(cg.metric_value(data, "a.b"), 0.5)
        self.assertIsNone(cg.metric_value(data, "a.missing"))
        self.assertIsNone(cg.metric_value(data, "nan"))
        self.assertIsNone(cg.metric_value(data, "flag"))

    def test_minimum_and_maximum_results(self):
        self.assertTrue(cg.minimum("x", 0.8, 0.75)["ok"])
        self.assertFalse(cg.minimum("x", 0.7, 0.75)["ok"])
        self.assertTrue(cg.maximum("x", 0.2, 0.25)["ok"])
        self.assertFalse(cg.maximum("x", 0.3, 0.25)["ok"])
        self.assertFalse(cg.minimum("x", None, 0.75)["ok"])

    def test_collect_test_metric_gates(self):
        metrics = {
            "test_rows": 100,
            "risk": {"accuracy": 0.96, "macro_f1": 0.94},
            "action": {"accuracy": 0.97},
            "targeted_binary": {"precision": 0.95, "recall": 0.94, "f1": 0.945},
        }
        config = {
            "risk_accuracy": 0.95, "risk_macro_f1": 0.90,
            "action_accuracy": 0.95, "targeted_precision": 0.90,
            "targeted_recall": 0.90, "targeted_f1": 0.90,
            "min_test_rows": 50,
        }
        gates = cg.collect_test_metric_gates(metrics, config)
        self.assertEqual(len(gates), 7)
        self.assertTrue(all(gate["ok"] for gate in gates))

        metrics["risk"]["accuracy"] = 0.50
        gates = cg.collect_test_metric_gates(metrics, config)
        failed = [gate for gate in gates if not gate["ok"]]
        self.assertEqual([gate["gate"] for gate in failed], ["test.risk_accuracy"])


class DatasetGateTests(unittest.TestCase):
    def _data(self):
        train = [
            {"text": "a", "label": "ALLOW", "category": "NONE", "family": "f1"},
            {"text": "b", "label": "REPHRASE", "category": "TARGETED_HATE", "family": "f2"},
        ]
        test = [
            {"text": "c", "label": "BLOCK", "category": "THREAT", "family": "f3"},
        ]
        report = {"unique_records": 3, "train_rows": 2, "test_rows": 1}
        config = {
            "min_unique_records": 1, "min_train_rows": 1, "min_test_rows": 1,
            "max_train_test_family_overlap": 0, "max_train_test_text_overlap": 0,
        }
        return report, train, test, config

    def test_dataset_gates_pass(self):
        report, train, test, config = self._data()
        gates = cg.collect_dataset_gates(report, train, test, train + test, config)
        self.assertTrue(all(gate["ok"] for gate in gates), gates)

    def test_family_and_text_overlap_fail(self):
        report, train, test, config = self._data()
        test[0]["family"] = "f1"
        test[0]["text"] = "a"
        test[0]["label"] = "ALLOW"
        test[0]["category"] = "NONE"
        gates = cg.collect_dataset_gates(report, train, test, train + test, config)
        failed = {gate["gate"] for gate in gates if not gate["ok"]}
        self.assertIn("dataset.train_test_family_overlap", failed)
        self.assertIn("dataset.train_test_text_overlap", failed)

    def test_report_file_count_mismatch_fails(self):
        report, train, test, config = self._data()
        report["train_rows"] = 99
        gates = cg.collect_dataset_gates(report, train, test, train + test, config)
        failed = {gate["gate"] for gate in gates if not gate["ok"]}
        self.assertIn("dataset.train_rows_match_file", failed)


class ChallengeAndLeakageGateTests(unittest.TestCase):
    def test_challenge_gates(self):
        report = {
            "expanded_rows_scored": 80,
            "risk": {"accuracy": 0.95},
            "targeted_binary": {"precision": 0.94, "recall": 0.93},
            "errors": [{"text": "x"}],
        }
        config = {
            "min_scored_rows": 50, "risk_accuracy": 0.90,
            "targeted_precision": 0.90, "targeted_recall": 0.90, "max_errors": 2,
        }
        self.assertTrue(all(gate["ok"] for gate in cg.collect_challenge_gates(report, config)))

        report["risk"]["accuracy"] = 0.5
        report["errors"] = [{}, {}, {}]
        failed = {gate["gate"] for gate in cg.collect_challenge_gates(report, config) if not gate["ok"]}
        self.assertEqual(failed, {"challenge.risk_accuracy", "challenge.errors"})

    def test_leakage_gate(self):
        train = [{"text": "Same", "label": "ALLOW", "category": "NONE"}]
        challenge = [{"text": "same", "label": "ALLOW", "category": "NONE"}]
        failed = cg.collect_leakage_gates(
            train, [], challenge, [], {"max_challenge_overlap": 0}
        )
        self.assertFalse(failed[0]["ok"])
        passed = cg.collect_leakage_gates(
            train, [], [{"text": "other", "label": "ALLOW", "category": "NONE"}],
            [], {"max_challenge_overlap": 0},
        )
        self.assertTrue(passed[0]["ok"])


class BundledModelGateTests(unittest.TestCase):
    def test_bundled_gates_pass_and_fail(self):
        metrics = {
            "test_risk_accuracy": 0.97,
            "test_targeted": {"f1": 0.96},
            "challenge_risk_accuracy": 0.90,
            "challenge_targeted": {"recall": 0.88},
            "challenge_errors": 8,
        }
        config = {
            "min_test_risk_accuracy": 0.95,
            "min_test_targeted_f1": 0.94,
            "min_challenge_risk_accuracy": 0.85,
            "min_challenge_targeted_recall": 0.80,
            "max_challenge_errors": 15,
        }
        self.assertTrue(all(gate["ok"] for gate in cg.collect_bundled_gates(metrics, config)))

        metrics["test_risk_accuracy"] = 0.50
        metrics["challenge_errors"] = 99
        failed = {
            gate["gate"] for gate in cg.collect_bundled_gates(metrics, config)
            if not gate["ok"]
        }
        self.assertEqual(failed, {
            "bundled.test_risk_accuracy", "bundled.challenge_errors",
        })


class SidecarGateTests(unittest.TestCase):
    def test_sidecar_gates_pass_on_expected_behavior(self):
        model = {"risk_model": support.FakeRiskModel()}
        config = {
            "neutral_text": "nice build",
            "neutral_targeted_max": 0.25,
            "neutral_threat_max": 0.10,
            "targeted_text": "you are worthless {SLUR}",
            "targeted_score_min": 0.75,
            "threat_text": "threat now",
            "threat_score_min": 0.75,
        }
        gates = cg.collect_sidecar_gates(model, ["blorple"], config)
        self.assertTrue(all(gate["ok"] for gate in gates), gates)

    def test_neutral_false_positive_fails(self):
        model = {"risk_model": support.FakeRiskModel()}
        config = {
            "neutral_text": "targeted text",
            "neutral_targeted_max": 0.25,
            "neutral_threat_max": 0.10,
            "targeted_text": "targeted text",
            "targeted_score_min": 0.75,
            "threat_text": "threat now",
            "threat_score_min": 0.75,
        }
        gates = cg.collect_sidecar_gates(model, [], config)
        neutral = [gate for gate in gates if gate["gate"].startswith("sidecar.neutral.")]
        self.assertTrue(any(not gate["ok"] for gate in neutral))


class RunGatesIntegrationTests(unittest.TestCase):
    def _write_json(self, path, value):
        path.write_text(json.dumps(value), encoding="utf-8")

    def _write_jsonl(self, path, rows):
        path.write_text(
            "\n".join(json.dumps(row) for row in rows) + "\n", encoding="utf-8"
        )

    def _temp_project(self):
        temp = tempfile.TemporaryDirectory()
        root = Path(temp.name)
        (root / "model").mkdir()
        import joblib
        joblib.dump({
            "risk_model": support.FakeRiskModel(),
            "action_model": FakeActionModel(),
        }, root / "model" / "model.joblib")
        self._write_json(root / "model" / "metrics.json", {
            "test_rows": 3,
            "risk": {"accuracy": 1.0, "macro_f1": 1.0},
            "action": {"accuracy": 1.0},
            "targeted_binary": {"precision": 1.0, "recall": 1.0, "f1": 1.0},
        })
        self._write_json(root / "lexicon.json", {"other": ["blorple"]})
        train = [
            {"text": "nice build", "label": "ALLOW", "category": "NONE", "family": "f1"},
            {"text": "threat now", "label": "BLOCK", "category": "THREAT", "family": "f2"},
        ]
        test = [
            {"text": "good game", "label": "ALLOW", "category": "NONE", "family": "f3"},
        ]
        clean = train + test
        self._write_jsonl(root / "train.jsonl", train)
        self._write_jsonl(root / "test.jsonl", test)
        self._write_jsonl(root / "clean.jsonl", clean)
        self._write_json(root / "report.json", {
            "unique_records": len(clean), "train_rows": len(train), "test_rows": len(test),
        })
        challenge = [
            challenge_row("a neutral gate example", "ALLOW", "NONE", False),
            challenge_row("you are a {SLUR}", "REPHRASE", "TARGETED_HATE", True),
            challenge_row("threat gate example", "BLOCK", "THREAT", True),
        ]
        self._write_jsonl(root / "challenge.jsonl", challenge)
        self._write_jsonl(root / "extra.jsonl", [])
        config = {
            "artifacts": {
                "model": "model/model.joblib", "metrics": "model/metrics.json",
                "lexicon": "lexicon.json", "dataset_report": "report.json",
                "train": "train.jsonl", "test": "test.jsonl", "clean": "clean.jsonl",
            },
            "dataset": {
                "min_unique_records": 1, "min_train_rows": 1, "min_test_rows": 1,
                "max_train_test_family_overlap": 0, "max_train_test_text_overlap": 0,
            },
            "test_metrics": {
                "risk_accuracy": 0.9, "risk_macro_f1": 0.9, "action_accuracy": 0.9,
                "targeted_precision": 0.9, "targeted_recall": 0.9,
                "targeted_f1": 0.9, "min_test_rows": 1,
            },
            "challenges": [{
                "path": "challenge.jsonl", "min_scored_rows": 1,
                "risk_accuracy": 0.9, "targeted_precision": 0.9,
                "targeted_recall": 0.9, "max_errors": 0,
            }],
            "leakage": {
                "challenge_paths": ["challenge.jsonl"], "extra_training": "extra.jsonl",
                "max_challenge_overlap": 0,
            },
            "sidecar": {
                "neutral_text": "nice build", "neutral_targeted_max": 0.25,
                "neutral_threat_max": 0.10,
                "targeted_text": "you are worthless {SLUR}", "targeted_score_min": 0.75,
                "threat_text": "threat now", "threat_score_min": 0.75,
            },
        }
        config_path = root / "gates.json"
        self._write_json(config_path, config)
        return temp, root, config_path

    def test_full_gate_run_passes(self):
        temp, root, config_path = self._temp_project()
        with temp:
            report = cg.run_gates(config_path, root)
        self.assertTrue(report["ok"], report)
        self.assertGreater(report["passed"], 20)
        self.assertEqual(report["failed"], 0)

    def test_full_gate_run_fails_on_bad_metric(self):
        temp, root, config_path = self._temp_project()
        try:
            metrics = json.loads((root / "model" / "metrics.json").read_text(encoding="utf-8"))
            metrics["risk"]["accuracy"] = 0.10
            self._write_json(root / "model" / "metrics.json", metrics)
            report = cg.run_gates(config_path, root)
        finally:
            temp.cleanup()
        self.assertFalse(report["ok"])
        failed = {check["gate"] for check in report["checks"] if not check["ok"]}
        self.assertIn("test.risk_accuracy", failed)

    def test_missing_config_returns_error(self):
        report = cg.run_gates("definitely-not-here.json", base_dir=support.ROOT)
        self.assertFalse(report["ok"])
        self.assertIn("error", report)


if __name__ == "__main__":
    unittest.main()
