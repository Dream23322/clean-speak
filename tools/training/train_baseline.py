#!/usr/bin/env python3
"""
Train a small first-pass CleanSpeak text classifier.

This is a baseline, not a production moderation model:
  * TF-IDF word + character features
  * logistic regression
  * category classifier: NONE / TARGETED_HATE / TARGETED_HARASSMENT / THREAT
  * binary targeted classifier
  * optional action classifier

The starter data uses {SLUR}. This script expands it locally using
data/lexicon.local.json, which is intentionally gitignored. Do not commit raw
slur lists or the generated model artifacts without reviewing your data policy.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from collections import Counter
from pathlib import Path
from typing import Any

try:
    import joblib
    import numpy as np
    from sklearn.feature_extraction.text import TfidfVectorizer
    from sklearn.linear_model import LogisticRegression
    from sklearn.metrics import (
        accuracy_score, classification_report, confusion_matrix, f1_score,
        precision_recall_fscore_support,
    )
    from sklearn.model_selection import StratifiedKFold, cross_val_predict
    from sklearn.pipeline import FeatureUnion, Pipeline
except ImportError as exc:
    print("Missing training dependency:", exc, file=sys.stderr)
    print("Run: py -m pip install -r tools/training/requirements.txt", file=sys.stderr)
    raise SystemExit(2)

CATEGORIES = ["NONE", "TARGETED_HATE", "TARGETED_HARASSMENT", "THREAT"]
RISK_LABELS = ["NONE", "TARGETED", "THREAT"]
ACTIONS = ["ALLOW", "REPHRASE", "BLOCK"]
PLACEHOLDER = "{SLUR}"
HAS_SLUR = "__HAS_SLUR__"


def load_jsonl(path: Path) -> list[dict[str, Any]]:
    rows = []
    with path.open(encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if line:
                rows.append(json.loads(line))
    return rows


def resolve_terms(lexicon: dict[str, list[str]], target_group: str) -> list[str]:
    terms = lexicon.get(target_group) or []
    if not terms:
        terms = lexicon.get("other") or []
    if not terms:
        terms = [PLACEHOLDER]
    return list(dict.fromkeys(terms))


def expand_rows(
        rows: list[dict[str, Any]],
        lexicon: dict[str, list[str]],
        include_placeholder: bool,
) -> list[dict[str, Any]]:
    expanded: list[dict[str, Any]] = []
    for row in rows:
        text = row["text"]
        if PLACEHOLDER not in text:
            expanded.append({**row, "variant": "original"})
            continue
        # A text-only model must not be taught that a bare slur is ALLOW. Such
        # rows are context examples and prepare_dataset.py keeps them separate.
        if row["label"] == "ALLOW":
            continue
        terms = resolve_terms(lexicon, row.get("target_group", "none"))
        for index, term in enumerate(terms):
            expanded.append({
                **row,
                "text": text.replace(PLACEHOLDER, term),
                "variant": f"actual:{index}",
            })
        if include_placeholder:
            expanded.append({**row, "variant": "placeholder"})
    # Remove exact duplicate expanded texts; keep a deterministic first copy.
    seen = set()
    unique = []
    for row in expanded:
        key = (row["text"].lower(), row["label"], row["category"])
        if key in seen:
            continue
        seen.add(key)
        unique.append(row)
    return unique


def prepare_text(text: str, terms: list[str]) -> str:
    """Canonicalize known terms so the model learns context, not a specific term.

    The plugin's rule layer already handles exact terms. This preprocessing is
    for the first model so it can generalize across a local lexicon and so test
    examples with terms unseen in a particular context still expose the
    "__HAS_SLUR__" signal.
    """
    value = text
    found = any(
        re.search(r"(?<!\w)" + re.escape(term) + r"(?:s|es)?(?!\w)", value, re.IGNORECASE)
        for term in terms if term
    )
    if PLACEHOLDER in value.upper() or found:
        value = value + " " + HAS_SLUR
    return value


def make_pipeline(seed: int) -> Pipeline:
    features = FeatureUnion([
        ("word", TfidfVectorizer(
            lowercase=True, ngram_range=(1, 2), min_df=1, max_df=0.98,
            sublinear_tf=True, strip_accents="unicode",
        )),
        ("char", TfidfVectorizer(
            lowercase=True, analyzer="char_wb", ngram_range=(2, 5),
            min_df=1, sublinear_tf=True,
        )),
    ])
    classifier = LogisticRegression(
        C=2.0, class_weight="balanced", max_iter=3000, solver="lbfgs",
        random_state=seed,
    )
    return Pipeline([("features", features), ("classifier", classifier)])


def report_for(labels: list[str], truth: list[str], prediction: list[str]) -> dict[str, Any]:
    present = sorted(set(truth) | set(prediction))
    return {
        "accuracy": round(float(accuracy_score(truth, prediction)), 4),
        "macro_f1": round(float(f1_score(truth, prediction, labels=present, average="macro", zero_division=0)), 4),
        "weighted_f1": round(float(f1_score(truth, prediction, labels=present, average="weighted", zero_division=0)), 4),
        "classification_report": classification_report(
            truth, prediction, labels=present, output_dict=True, zero_division=0
        ),
        "labels": present,
        "confusion_matrix": {
            "labels": present,
            "rows_true_columns_predicted": confusion_matrix(truth, prediction, labels=present).tolist(),
        },
    }


def binary_metrics(truth: list[bool], prediction: list[bool]) -> dict[str, Any]:
    precision, recall, f1, _ = precision_recall_fscore_support(
        truth, prediction, average="binary", zero_division=0
    )
    tp = sum(1 for t, p in zip(truth, prediction) if t and p)
    fp = sum(1 for t, p in zip(truth, prediction) if not t and p)
    fn = sum(1 for t, p in zip(truth, prediction) if t and not p)
    tn = sum(1 for t, p in zip(truth, prediction) if not t and not p)
    return {
        "precision": round(float(precision), 4),
        "recall": round(float(recall), 4),
        "f1": round(float(f1), 4),
        "tp": tp, "fp": fp, "fn": fn, "tn": tn,
    }


def write_errors(path: Path, rows: list[dict[str, Any]], predicted_categories: list[str], predicted_actions: list[str]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="\n") as handle:
        for row, category, action in zip(rows, predicted_categories, predicted_actions):
            if category == row["category"]:
                continue
            handle.write(json.dumps({
                "text": row["text"],
                "true_category": row["category"],
                "predicted_category": category,
                "true_label": row["label"],
                "predicted_action": action,
                "target_group": row.get("target_group"),
                "hard_negative": row.get("hard_negative"),
                "variant": row.get("variant"),
            }, ensure_ascii=False) + "\n")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--train", default="data/train.jsonl")
    parser.add_argument("--test", default="data/test.jsonl")
    parser.add_argument("--lexicon", default="data/lexicon.local.json")
    parser.add_argument("--extra", default="tools/training/extra_examples.jsonl")
    parser.add_argument("--out-dir", default="data/model")
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--no-placeholder-augmentation", action="store_true")
    args = parser.parse_args()

    train_path, test_path, lexicon_path = Path(args.train), Path(args.test), Path(args.lexicon)
    if not train_path.exists() or not test_path.exists():
        print("Run prepare_dataset.py first.", file=sys.stderr)
        return 2
    if not lexicon_path.exists():
        print(f"local lexicon missing: {lexicon_path}", file=sys.stderr)
        print("Create a local mapping of target groups to terms; see tools/training/README.md.", file=sys.stderr)
        return 2

    lexicon = json.loads(lexicon_path.read_text(encoding="utf-8"))
    train = expand_rows(load_jsonl(train_path), lexicon, not args.no_placeholder_augmentation)
    extra_path = Path(args.extra)
    if extra_path.exists():
        train.extend(expand_rows(load_jsonl(extra_path), lexicon, False))
    test = expand_rows(load_jsonl(test_path), lexicon, False)
    if not train or not test:
        print("empty train/test after filtering", file=sys.stderr)
        return 2

    # Remove exact train/test overlap after expansion.
    train_keys = {(r["text"].lower(), r["label"], r["category"]) for r in train}
    test = [r for r in test if (r["text"].lower(), r["label"], r["category"]) not in train_keys]
    if not test:
        print("all test rows collided with train rows after expansion; re-run prepare_dataset.py with a different seed", file=sys.stderr)
        return 2

    terms = list(dict.fromkeys(t for values in lexicon.values() for t in values if t))
    x_train = [prepare_text(r["text"], terms) for r in train]
    sample_weight = np.array([2.0 if r.get("hard_negative") else 1.0 for r in train])
    y_train_cat = [r["category"] for r in train]
    y_train_action = [r["label"] for r in train]
    y_train_targeted = [bool(r["targeted"]) for r in train]

    def risk_label(row: dict[str, Any]) -> str:
        if row["category"] == "THREAT":
            return "THREAT"
        return "TARGETED" if row["category"] != "NONE" else "NONE"

    y_train_risk = [risk_label(r) for r in train]

    x_test = [prepare_text(r["text"], terms) for r in test]
    y_test_cat = [r["category"] for r in test]
    y_test_action = [r["label"] for r in test]
    y_test_targeted = [bool(r["targeted"]) for r in test]
    y_test_risk = [risk_label(r) for r in test]

    category_model = make_pipeline(args.seed)
    category_model.fit(x_train, y_train_cat, classifier__sample_weight=sample_weight)
    category_pred = list(category_model.predict(x_test))
    category_proba = category_model.predict_proba(x_test)
    classes = list(category_model.classes_)

    action_model = make_pipeline(args.seed)
    action_model.fit(x_train, y_train_action, classifier__sample_weight=sample_weight)
    action_pred = list(action_model.predict(x_test))

    risk_model = make_pipeline(args.seed)
    risk_model.fit(x_train, y_train_risk, classifier__sample_weight=sample_weight)
    risk_pred = list(risk_model.predict(x_test))

    targeted_model = make_pipeline(args.seed)
    targeted_model.fit(x_train, y_train_targeted, classifier__sample_weight=sample_weight)
    targeted_pred = [bool(v) for v in targeted_model.predict(x_test)]

    category_metrics = report_for(CATEGORIES, y_test_cat, category_pred)
    action_metrics = report_for(ACTIONS, y_test_action, action_pred)
    risk_metrics = report_for(RISK_LABELS, y_test_risk, risk_pred)
    targeted_metrics = binary_metrics(y_test_targeted, targeted_pred)

    # Cross-validation on the expanded training set gives a second, less
    # optimistic view. It is still synthetic/template-biased.
    cv_metrics: dict[str, Any] = {}
    try:
        min_class = min(Counter(y_train_cat).values())
        n_splits = max(2, min(5, min_class))
        cv = StratifiedKFold(n_splits=n_splits, shuffle=True, random_state=args.seed)
        cv_pred = cross_val_predict(make_pipeline(args.seed), x_train, y_train_cat, cv=cv)
        cv_metrics = report_for(CATEGORIES, y_train_cat, list(cv_pred))
        cv_metrics["n_splits"] = n_splits
    except Exception as exc:
        cv_metrics = {"error": f"{type(exc).__name__}: {exc}"}

    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    model_bundle = {
        "category_model": category_model,
        "risk_model": risk_model,
        "action_model": action_model,
        "targeted_model": targeted_model,
        "categories": classes,
        "actions": ACTIONS,
        "policy": "text-only baseline; no ban action",
        "placeholder": PLACEHOLDER,
        "has_slur_token": HAS_SLUR,
    }
    joblib.dump(model_bundle, out_dir / "model.joblib")

    metrics = {
        "model": "tfidf-word-char-logistic-regression",
        "seed": args.seed,
        "train_rows": len(train),
        "test_rows": len(test),
        "test_label_counts": dict(Counter(y_test_action)),
        "test_category_counts": dict(Counter(y_test_cat)),
        "category": category_metrics,
        "action": action_metrics,
        "targeted_binary": targeted_metrics,
        "risk": risk_metrics,
        "category_cross_validation": cv_metrics,
        "note": "Synthetic starter data; metrics are not evidence of production safety.",
    }
    (out_dir / "metrics.json").write_text(json.dumps(metrics, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (out_dir / "labels.json").write_text(json.dumps({
        "categories": CATEGORIES,
        "actions": ACTIONS,
        "model_version": "baseline-2026-11-18",
        "placeholder": PLACEHOLDER,
        "has_slur_token": HAS_SLUR,
    }, indent=2) + "\n", encoding="utf-8")

    write_errors(out_dir / "test_errors.jsonl", test, category_pred, action_pred)

    print(json.dumps({
        "train_rows": len(train),
        "test_rows": len(test),
        "category_accuracy": category_metrics["accuracy"],
        "category_macro_f1": category_metrics["macro_f1"],
        "action_accuracy": action_metrics["accuracy"],
        "action_macro_f1": action_metrics["macro_f1"],
        "risk_accuracy": risk_metrics["accuracy"],
        "risk_macro_f1": risk_metrics["macro_f1"],
        "targeted_precision": targeted_metrics["precision"],
        "targeted_recall": targeted_metrics["recall"],
        "targeted_f1": targeted_metrics["f1"],
        "model": str(out_dir / "model.joblib"),
    }, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
