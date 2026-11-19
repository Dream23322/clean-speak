"""Shared fixtures for the model-pipeline unit tests.

The tests never use the private lexicon or the trained artifact. They use
harmless invented tokens and tiny fake models so they can run on a clean
checkout.
"""
from __future__ import annotations

import hashlib
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
TRAINING = ROOT / "tools" / "training"
if str(TRAINING) not in sys.path:
    sys.path.insert(0, str(TRAINING))

FAKE_LEXICON = {
    "other": ["blorple", "gribbit"],
    "religion": ["zorbite"],
    "race_ethnicity": ["flimflam"],
}


def load_jsonl(path: Path) -> list[dict]:
    return [
        json.loads(line)
        for line in Path(path).read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]


def make_record(
    *,
    text: str,
    label: str = "ALLOW",
    category: str = "NONE",
    targeted: bool = False,
    target_group: str = "none",
    hard_negative: bool = False,
    note: str = "",
    family: str = "test.family",
    channel: str = "public_chat",
    language: str = "en",
    line: int = 1,
):
    import prepare_dataset as pd

    return pd.Record(
        id="id-" + hashlib.sha1(
            (text + "\n" + label + "\n" + category + "\n" + family).encode("utf-8")
        ).hexdigest()[:12],
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
    )


def make_row(
    text: str,
    *,
    label: str = "ALLOW",
    category: str = "NONE",
    targeted: bool = False,
    target_group: str = "none",
    hard_negative: bool = False,
    note: str = "",
    family: str = "test.family",
) -> dict:
    return {
        "text": text,
        "channel": "public_chat",
        "label": label,
        "category": category,
        "targeted": targeted,
        "target_group": target_group,
        "hard_negative": hard_negative,
        "language": "en",
        "note": note,
        "family": family,
    }


class FakeRiskModel:
    """Minimal sklearn-like classifier for the sidecar contract tests."""

    classes_ = ["NONE", "TARGETED", "THREAT"]

    def __init__(self, table: dict[str, list[float]] | None = None):
        # Default probabilities: neutral unless the text contains a marker.
        self.table = table or {
            "normal": [0.90, 0.08, 0.02],
            "targeted": [0.02, 0.96, 0.02],
            "threat": [0.01, 0.01, 0.98],
        }

    def predict_proba(self, texts):
        rows = []
        for text in texts:
            lowered = text.lower()
            if "threat" in lowered:
                rows.append(self.table["threat"])
            elif "targeted" in lowered or "__has_slur__" in lowered:
                rows.append(self.table["targeted"])
            else:
                rows.append(self.table["normal"])
        return rows

    def predict(self, texts):
        return [self.classes_[max(range(len(row)), key=row.__getitem__)] for row in self.predict_proba(texts)]
