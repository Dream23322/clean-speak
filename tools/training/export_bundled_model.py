#!/usr/bin/env python3
"""Train and export the bundled, dependency-free text model.

The exported model is intentionally *not* a pickled sklearn pipeline. It uses
deterministic feature hashing with FNV-1a, so the same inference can be
implemented in Java without Python, a sidecar, a vocabulary file, or raw terms
stored in the artifact.

Artifact:
    src/main/resources/ml/bundled_model.json.gz

The JSON contains only:
  * feature configuration and hash name
  * the idf vector and logistic-regression coefficients
  * class labels and a few parity examples with expected scores

It does not contain a token vocabulary or a slur list.
"""
from __future__ import annotations

import argparse
import gzip
import json
import math
import re
import sys
from pathlib import Path

try:
    import numpy as np
    from scipy import sparse
    from sklearn.linear_model import LogisticRegression
    from sklearn.metrics import precision_recall_fscore_support
except ImportError as exc:
    print("Missing training dependency:", exc, file=sys.stderr)
    print("Run: py -m pip install -r tools/training/requirements.txt", file=sys.stderr)
    raise SystemExit(2)

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))

import evaluate_challenge as ec  # noqa: E402
import train_baseline as tb  # noqa: E402

WORD_RE = re.compile(r"[a-z0-9_]+")
FNV_OFFSET = 0xCBF29CE484222325
FNV_PRIME = 0x100000001B3
MASK64 = 0xFFFFFFFFFFFFFFFF
N_FEATURES = 1 << 17
WORD_NGRAMS = (1, 2)
CHAR_NGRAMS = (3, 4, 5)
RESOURCE_PATH = ROOT / "src" / "main" / "resources" / "ml" / "bundled_model.json.gz"
METRICS_PATH = ROOT / "data" / "model" / "bundled_metrics.json"


def normalize(text: str) -> str:
    return " ".join(str(text).lower().split())


def fnv1a64(value: str) -> int:
    hashed = FNV_OFFSET
    for byte in value.encode("utf-8"):
        hashed ^= byte
        hashed = (hashed * FNV_PRIME) & MASK64
    return hashed


def feature_buckets(text: str) -> dict[int, float]:
    """Signed hashed counts, matching the Java implementation exactly."""
    normalized = normalize(text)
    tokens = WORD_RE.findall(normalized)
    buckets: dict[int, float] = {}

    def add(feature: str) -> None:
        hashed = fnv1a64(feature)
        bucket = hashed % N_FEATURES
        sign = 1.0 if hashed < (1 << 63) else -1.0
        buckets[bucket] = buckets.get(bucket, 0.0) + sign

    for size in WORD_NGRAMS:
        for index in range(0, len(tokens) - size + 1):
            add("w:" + " ".join(tokens[index:index + size]))
    for size in CHAR_NGRAMS:
        for index in range(0, len(normalized) - size + 1):
            add("c:" + normalized[index:index + size])
    return buckets


def build_document_matrix(texts: list[str]) -> sparse.csr_matrix:
    rows, cols, values = [], [], []
    for row, text in enumerate(texts):
        buckets = feature_buckets(text)
        for bucket, count in buckets.items():
            if count == 0:
                continue
            magnitude = abs(count)
            tf = 1.0 + math.log(magnitude)
            rows.append(row)
            cols.append(bucket)
            values.append(math.copysign(tf, count))
    return sparse.csr_matrix(
        (values, (rows, cols)), shape=(len(texts), N_FEATURES), dtype=np.float64
    )


def fit_idf(matrix: sparse.csr_matrix) -> np.ndarray:
    document_frequency = np.asarray((matrix != 0).sum(axis=0)).ravel()
    return np.log((1.0 + matrix.shape[0]) / (1.0 + document_frequency)) + 1.0


def apply_tfidf(matrix: sparse.csr_matrix, idf: np.ndarray) -> sparse.csr_matrix:
    weighted = matrix.multiply(idf).tocsr()
    norms = np.sqrt(np.asarray(weighted.multiply(weighted).sum(axis=1)).ravel())
    norms[norms == 0.0] = 1.0
    return sparse.diags(1.0 / norms).dot(weighted).tocsr()


def softmax(logits: np.ndarray) -> np.ndarray:
    shifted = logits - np.max(logits)
    values = np.exp(shifted)
    return values / values.sum()


def predict_scores(text: str, idf: np.ndarray, coef: np.ndarray,
                   intercept: np.ndarray, classes: list[str]) -> dict[str, float]:
    buckets = feature_buckets(text)
    values: dict[int, float] = {}
    norm = 0.0
    for bucket, count in buckets.items():
        if count == 0:
            continue
        magnitude = abs(count)
        value = math.copysign(1.0 + math.log(magnitude), count) * float(idf[bucket])
        values[bucket] = value
        norm += value * value
    norm = math.sqrt(norm) or 1.0
    probabilities = softmax(
        np.array([
            sum(coef[class_index][bucket] * value for bucket, value in values.items()) / norm
            + intercept[class_index]
            for class_index in range(len(classes))
        ])
    )
    return {name: float(probabilities[index]) for index, name in enumerate(classes)}


def risk_label(row: dict) -> str:
    if row["category"] == "THREAT":
        return "THREAT"
    return "TARGETED" if row["category"] != "NONE" else "NONE"


def binary_metrics(truth: list[str], predicted: list[str]) -> dict:
    tp = sum(1 for true, pred in zip(truth, predicted) if true != "NONE" and pred != "NONE")
    fp = sum(1 for true, pred in zip(truth, predicted) if true == "NONE" and pred != "NONE")
    fn = sum(1 for true, pred in zip(truth, predicted) if true != "NONE" and pred == "NONE")
    precision = tp / (tp + fp) if tp + fp else 0.0
    recall = tp / (tp + fn) if tp + fn else 0.0
    f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0
    return {
        "precision": round(precision, 4), "recall": round(recall, 4),
        "f1": round(f1, 4), "tp": tp, "fp": fp, "fn": fn,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--train", default="data/train.jsonl")
    parser.add_argument("--test", default="data/test.jsonl")
    parser.add_argument("--extra", default="tools/training/extra_examples.jsonl")
    parser.add_argument("--lexicon", default="data/lexicon.local.json")
    parser.add_argument("--challenge", default="data/challenge11.jsonl")
    parser.add_argument("--resource", default=str(RESOURCE_PATH))
    parser.add_argument("--metrics", default=str(METRICS_PATH))
    parser.add_argument("--seed", type=int, default=42)
    args = parser.parse_args()

    lexicon = json.loads(Path(args.lexicon).read_text(encoding="utf-8"))
    train = tb.expand_rows(tb.load_jsonl(Path(args.train)), lexicon, False)
    extra = Path(args.extra)
    if extra.exists():
        train.extend(tb.expand_rows(tb.load_jsonl(extra), lexicon, False))
    test = tb.expand_rows(tb.load_jsonl(Path(args.test)), lexicon, False)
    known = {(r["text"].lower(), r["label"], r["category"]) for r in train}
    test = [r for r in test if (r["text"].lower(), r["label"], r["category"]) not in known]
    if not train or not test:
        print("empty train/test after expansion", file=sys.stderr)
        return 2

    x_train_raw = build_document_matrix([row["text"] for row in train])
    x_test_raw = build_document_matrix([row["text"] for row in test])
    idf = fit_idf(x_train_raw)
    x_train = apply_tfidf(x_train_raw, idf)
    x_test = apply_tfidf(x_test_raw, idf)

    y_train = np.array([risk_label(row) for row in train])
    y_test = np.array([risk_label(row) for row in test])
    weights = np.array([2.0 if row.get("hard_negative") else 1.0 for row in train])
    classifier = LogisticRegression(
        C=2.0, class_weight="balanced", max_iter=3000, solver="lbfgs",
        random_state=args.seed,
    )
    classifier.fit(x_train, y_train, sample_weight=weights)
    classes = [str(name) for name in classifier.classes_]
    coef = np.asarray(classifier.coef_, dtype=np.float64)
    intercept = np.asarray(classifier.intercept_, dtype=np.float64)

    test_pred = []
    for row in test:
        scores = predict_scores(row["text"], idf, coef, intercept, classes)
        test_pred.append(max(scores, key=scores.get))
    accuracy = sum(1 for a, b in zip(y_test, test_pred) if a == b) / len(y_test)
    precision, recall, f1, _ = precision_recall_fscore_support(
        y_test, test_pred, average="macro", zero_division=0
    )
    targeted = binary_metrics(list(y_test), test_pred)

    challenge_metrics = None
    challenge_path = Path(args.challenge)
    if challenge_path.exists():
        rows = [json.loads(line) for line in challenge_path.read_text(encoding="utf-8").splitlines() if line.strip()]

        def predict_risk(items):
            output = []
            for row in items:
                scores = predict_scores(row["text"], idf, coef, intercept, classes)
                output.append(max(scores, key=scores.get))
            return output

        def predict_action(items):
            return [
                "BLOCK" if row["category"] == "THREAT"
                else "REPHRASE" if row["category"] != "NONE" else "ALLOW"
                for row in items
            ]

        challenge_metrics = ec.evaluate(rows, lexicon, predict_risk, predict_action)
        # The action head above is deterministic, so only the risk head matters
        # for the bundled model. Keep the standard challenge report shape.
        challenge_metrics["model"] = "bundled-hashed-risk"
        challenge_metrics["action"] = challenge_metrics["action"]
        challenge_metrics["note"] = (
            "Bundled hashed risk model; action is mapped deterministically from risk."
        )
        (ROOT / "data" / "model" / "bundled_challenge_metrics.json").write_text(
            json.dumps(challenge_metrics, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )

    examples = []
    for text in [
        "I hate mining, it takes forever",
        "nice build, how long did it take",
        "I will find you and hurt you",
        "people like you should be removed from this server",
        "nobody wants your kind on this team",
    ]:
        scores = predict_scores(text, idf, coef, intercept, classes)
        examples.append({
            "text": text,
            "scores": {
                "TARGETED_HARASSMENT": round(scores.get("TARGETED", 0.0), 6),
                "THREAT": round(scores.get("THREAT", 0.0), 6),
            },
        })

    artifact = {
        "version": "cleanspeak-bundled-v1",
        "feature_spec": {
            "hash": "fnv1a64",
            "n_features": N_FEATURES,
            "word_ngrams": list(WORD_NGRAMS),
            "char_ngrams": list(CHAR_NGRAMS),
            "normalization": "lowercase + collapse whitespace",
            "tf": "signed sublinear tf; l2 normalized; idf",
        },
        "classes": classes,
        "idf": [round(float(value), 6) for value in idf],
        "coef": [[round(float(value), 6) for value in row] for row in coef],
        "intercept": [round(float(value), 6) for value in intercept],
        "examples": examples,
        "metrics": {
            "train_rows": len(train),
            "test_rows": len(test),
            "test_risk_accuracy": round(float(accuracy), 4),
            "test_risk_macro_f1": round(float(f1), 4),
            "test_targeted": targeted,
            "challenge_scored_rows": challenge_metrics["expanded_rows_scored"] if challenge_metrics else 0,
            "challenge_risk_accuracy": challenge_metrics["risk"]["accuracy"] if challenge_metrics else 0,
            "challenge_targeted": challenge_metrics["targeted_binary"] if challenge_metrics else {},
            "challenge_errors": len(challenge_metrics["errors"]) if challenge_metrics else 0,
        },
    }

    resource = Path(args.resource)
    resource.parent.mkdir(parents=True, exist_ok=True)
    payload = json.dumps(artifact, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    with resource.open("wb") as raw:
        with gzip.GzipFile(fileobj=raw, mode="wb", compresslevel=9, mtime=0) as handle:
            handle.write(payload)

    metrics_path = Path(args.metrics)
    metrics_path.parent.mkdir(parents=True, exist_ok=True)
    metrics_path.write_text(
        json.dumps({
            "resource": str(resource),
            "uncompressed_bytes": len(payload),
            "compressed_bytes": resource.stat().st_size,
            **artifact["metrics"],
        }, indent=2) + "\n",
        encoding="utf-8",
    )

    print(json.dumps({
        "resource": str(resource),
        "compressed_bytes": resource.stat().st_size,
        "uncompressed_bytes": len(payload),
        "classes": classes,
        **artifact["metrics"],
    }, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
