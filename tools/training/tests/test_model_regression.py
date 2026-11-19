"""Optional regression gate for the locally trained artifact.

These tests are skipped on a clean checkout because the model and private
lexicon are gitignored. When both exist, they prevent a retrain from silently
falling below a documented floor on the final held-out challenge.
"""
from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import support  # noqa: E402

DATA = support.ROOT / "data"
MODEL_PATH = DATA / "model" / "model.joblib"
LEXICON_PATH = DATA / "lexicon.local.json"


@unittest.skipUnless(
    MODEL_PATH.exists() and LEXICON_PATH.exists(),
    "trained model/private lexicon are not present on this checkout",
)
class TrainedModelRegressionTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        import json
        import joblib
        import evaluate_challenge as ec
        import serve_model
        import train_baseline as tb

        cls.ec = ec
        cls.tb = tb
        cls.serve_model = serve_model
        cls.model = joblib.load(MODEL_PATH)
        cls.lexicon = json.loads(LEXICON_PATH.read_text(encoding="utf-8"))
        cls.terms = list(dict.fromkeys(
            term for values in cls.lexicon.values() for term in values if term
        ))
        cls.challenge = support.load_jsonl(DATA / "challenge11.jsonl")

    def _report(self):
        def predict_risk(rows):
            return self.model["risk_model"].predict([
                self.tb.prepare_text(row["text"], self.terms) for row in rows
            ])

        def predict_action(rows):
            return self.model["action_model"].predict([
                self.tb.prepare_text(row["text"], self.terms) for row in rows
            ])

        return self.ec.evaluate(
            self.challenge, self.lexicon, predict_risk, predict_action
        )

    def test_final_challenge_does_not_regress(self):
        report = self._report()
        binary = report["targeted_binary"]
        self.assertGreaterEqual(report["risk"]["accuracy"], 0.92)
        self.assertGreaterEqual(binary["precision"], 0.92)
        self.assertGreaterEqual(binary["recall"], 0.90)
        self.assertLessEqual(len(report["errors"]), 5)

    def test_sidecar_is_low_on_ordinary_venting_and_high_on_targeted_text(self):
        neutral = self.serve_model.scores_for(
            "I hate mining, it takes forever", self.model, self.terms
        )
        targeted = self.serve_model.scores_for(
            "you are a worthless {SLUR}", self.model, self.terms
        )
        threat = self.serve_model.scores_for(
            "I will find you and hurt you", self.model, self.terms
        )
        self.assertLess(neutral["scores"]["TARGETED_HARASSMENT"], 0.25)
        self.assertGreater(targeted["scores"]["TARGETED_HARASSMENT"], 0.75)
        self.assertGreater(threat["scores"]["THREAT"], 0.75)


if __name__ == "__main__":
    unittest.main()
