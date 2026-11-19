#!/usr/bin/env python3
"""Evaluate the local model on a hand-written challenge set.

The challenge file is deliberately outside the training/validation split. It
contains ordinary trash talk, neutral identity discussion, reported abuse,
targeted hate, threats and spam. {SLUR} is expanded locally for evaluation.

The scoring functions are exposed separately from ``main`` so they can be unit
tested without a trained model or private lexicon.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

try:
    import joblib
except ImportError as exc:
    print("Run pip install -r tools/training/requirements.txt", file=sys.stderr)
    raise SystemExit(2)

sys.path.insert(0, str(Path(__file__).parent))
import train_baseline as tb

RISK_LABELS = ["NONE", "TARGETED", "THREAT"]
ACTION_LABELS = ["ALLOW", "REPHRASE", "BLOCK"]
PLACEHOLDER = "{SLUR}"


def expand(rows, lexicon):
    """Expand ``{SLUR}`` using the row's group lexicon, then the fallback one."""
    out = []
    for row in rows:
        text = row["text"]
        if PLACEHOLDER not in text:
            out.append({**row, "text": text, "variant": "original"})
            continue
        group_terms = lexicon.get(row.get("target_group", "other")) or lexicon.get("other") or []
        if not group_terms:
            # Keep the row visible. prepare_text() still adds the slur signal
            # for a placeholder, so this is scoreable rather than silently lost.
            out.append({**row, "variant": "placeholder"})
            continue
        for index, term in enumerate(group_terms):
            out.append({
                **row,
                "text": text.replace(PLACEHOLDER, term),
                "variant": f"actual:{index}",
            })
    return out


def context_only(row) -> bool:
    """Return true when a text-only model cannot fairly score the row.

    Quotation, reported abuse, education and meta-discussion need the plugin's
    context/rule layer. Scoring them as ordinary first-person chat would make the
    numbers look better or worse for the wrong reason.
    """
    note = str(row.get("note", "")).lower()
    text = str(row.get("text", "")).strip().lower()
    return (
        "context-only" in note
        or "reported abuse" in note
        or "reported threat" in note
        or "education/question" in note
        or "meta-discussion" in note
        or text.startswith("he called me")
        or text.startswith("she called me")
        or text.startswith("they called me")
        or text.startswith("a player called me")
        or text.startswith("someone called me")
        or text.startswith("what does")
        or text.startswith("i think")
    )


def rule_layer_only(row) -> bool:
    """Spam/scam are deterministic rules, not the ML sidecar's job."""
    return str(row.get("category", "")) in ("SPAM", "SCAM_ADVERTISING")


def expected_risk(row) -> str:
    if row["category"] == "THREAT":
        return "THREAT"
    return "TARGETED" if row["category"] != "NONE" else "NONE"


def coherent_action(risk: str, action: str) -> str:
    """Reconcile the two model heads into one safe decision.

    The shipped plugin uses the risk model; the action model only chooses
    ``BLOCK`` versus ``REPHRASE``. A targeted row must never be allowed, and a
    row the risk model calls ``NONE`` must never be escalated.
    """
    if risk == "THREAT":
        return "BLOCK"
    if risk == "NONE":
        return "ALLOW"
    return action if action in ("BLOCK", "REPHRASE") else "REPHRASE"


def confusion(labels, truth, pred):
    matrix = {label: {other: 0 for other in labels} for label in labels}
    for true, predicted in zip(truth, pred):
        matrix.setdefault(true, {})
        matrix[true][predicted] = matrix[true].get(predicted, 0) + 1
    return matrix


def metrics(labels, truth, pred):
    correct = sum(1 for true, predicted in zip(truth, pred) if true == predicted)
    out = {"accuracy": correct / len(truth) if truth else 0.0, "n": len(truth)}
    per = {}
    for label in labels:
        tp = sum(1 for true, predicted in zip(truth, pred) if true == label and predicted == label)
        fp = sum(1 for true, predicted in zip(truth, pred) if true != label and predicted == label)
        fn = sum(1 for true, predicted in zip(truth, pred) if true == label and predicted != label)
        precision = tp / (tp + fp) if tp + fp else 0.0
        recall = tp / (tp + fn) if tp + fn else 0.0
        f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0
        per[label] = {
            "precision": precision, "recall": recall, "f1": f1,
            "tp": tp, "fp": fp, "fn": fn,
        }
    out["per_label"] = per
    out["confusion"] = confusion(labels, truth, pred)
    return out


def evaluate(rows, lexicon, risk_predict, action_predict):
    """Score a challenge set.

    ``risk_predict`` and ``action_predict`` take a list of expanded rows and
    return one label per row. Keeping prediction as a callable makes the scoring
    contract testable with tiny fakes and keeps the metric rules independent of
    the model implementation.
    """
    expanded = expand(rows, lexicon)
    risk_pred = [str(value) for value in risk_predict(expanded)]
    action_pred = [str(value) for value in action_predict(expanded)]
    if len(risk_pred) != len(expanded) or len(action_pred) != len(expanded):
        raise ValueError("predictor returned the wrong number of labels")

    scored = [
        (row, risk, action)
        for row, risk, action in zip(expanded, risk_pred, action_pred)
        if row["label"] != "REVIEW" and not context_only(row) and not rule_layer_only(row)
    ]
    review_rows = [row for row in expanded if row["label"] == "REVIEW"]
    context_rows = [row for row in expanded if context_only(row)]
    rule_rows = [row for row in expanded if rule_layer_only(row)]

    risk_truth = []
    risk_output = []
    action_truth = []
    action_output = []
    standalone_truth = []
    standalone_output = []
    errors = []
    for row, risk, action in scored:
        effective = coherent_action(risk, action)
        truth_risk = expected_risk(row)
        risk_truth.append(truth_risk)
        risk_output.append(risk)
        action_truth.append(row["label"])
        action_output.append(effective)
        standalone_truth.append(row["label"])
        standalone_output.append(action)
        if risk != truth_risk or effective != row["label"]:
            errors.append({
                "text": row["text"],
                "variant": row.get("variant"),
                "expected_label": row["label"],
                "predicted_action": effective,
                "standalone_action": action,
                "expected_risk": truth_risk,
                "predicted_risk": risk,
                "category": row["category"],
                "target_group": row.get("target_group"),
                "note": row.get("note"),
            })

    binary_truth = [value != "NONE" for value in risk_truth]
    binary_pred = [value != "NONE" for value in risk_output]
    tp = sum(1 for true, predicted in zip(binary_truth, binary_pred) if true and predicted)
    fp = sum(1 for true, predicted in zip(binary_truth, binary_pred) if not true and predicted)
    fn = sum(1 for true, predicted in zip(binary_truth, binary_pred) if true and not predicted)
    tn = sum(1 for true, predicted in zip(binary_truth, binary_pred) if not true and not predicted)
    binary = {
        "precision": tp / (tp + fp) if tp + fp else 0.0,
        "recall": tp / (tp + fn) if tp + fn else 0.0,
        "f1": (2 * tp / (2 * tp + fp + fn)) if (2 * tp + fp + fn) else 0.0,
        "tp": tp, "fp": fp, "fn": fn, "tn": tn,
    }

    return {
        "challenge_rows": len(rows),
        "expanded_rows_scored": len(scored),
        "review_rows_excluded": len(review_rows),
        "context_rows_excluded": len(context_rows),
        "rule_layer_rows_excluded": len(rule_rows),
        "risk": metrics(RISK_LABELS, risk_truth, risk_output),
        "action": metrics(ACTION_LABELS, action_truth, action_output),
        "action_model_standalone": metrics(ACTION_LABELS, standalone_truth, standalone_output),
        "targeted_binary": binary,
        "errors": errors,
        "review_examples": [{"text": r["text"], "note": r.get("note")} for r in review_rows],
        "context_examples": [{"text": r["text"], "note": r.get("note")} for r in context_rows],
        "rule_layer_examples": [{"text": r["text"], "note": r.get("note")} for r in rule_rows],
        "note": (
            "Challenge set is hand-written and not used in training; it still is "
            "not a substitute for live server data."
        ),
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--challenge", default="data/challenge.jsonl")
    parser.add_argument("--model", default="data/model/model.joblib")
    parser.add_argument("--lexicon", default="data/lexicon.local.json")
    parser.add_argument("--out", default="data/model/challenge_metrics.json")
    args = parser.parse_args()
    rows = [
        json.loads(line)
        for line in Path(args.challenge).read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]
    lexicon = json.loads(Path(args.lexicon).read_text(encoding="utf-8"))
    model = joblib.load(args.model)
    terms = list(dict.fromkeys(t for values in lexicon.values() for t in values if t))
    risk_model = model["risk_model"]
    action_model = model["action_model"]

    def predict_risk(expanded):
        return risk_model.predict([
            tb.prepare_text(row["text"], terms) for row in expanded
        ])

    def predict_action(expanded):
        return action_model.predict([
            tb.prepare_text(row["text"], terms) for row in expanded
        ])

    report = evaluate(rows, lexicon, predict_risk, predict_action)
    Path(args.out).parent.mkdir(parents=True, exist_ok=True)
    Path(args.out).write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    binary = report["targeted_binary"]
    print(json.dumps({
        "challenge_rows": report["challenge_rows"],
        "scored_expanded_rows": report["expanded_rows_scored"],
        "risk_accuracy": round(report["risk"]["accuracy"], 4),
        "action_accuracy": round(report["action"]["accuracy"], 4),
        "targeted_precision": round(binary["precision"], 4),
        "targeted_recall": round(binary["recall"], 4),
        "targeted_f1": round(binary["f1"], 4),
        "errors": len(report["errors"]),
        "output": args.out,
    }, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
