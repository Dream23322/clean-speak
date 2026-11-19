#!/usr/bin/env python3
"""Acceptance gates for a trained CleanSpeak model.

The gates are deliberately stricter than "training ran without an exception".
They answer whether an artifact is safe to keep as the current model:

* required artifacts exist and load;
* the family-aware test split clears minimum risk/action/targeted metrics;
* the dataset is large enough and train/test do not leak families or texts;
* fresh challenge files clear minimum accuracy/recall and an error budget;
* final challenge files do not overlap training or the curated supplement;
* the sidecar scores ordinary venting low and targeted/threat text high.

Run after training::

    py tools/training/train_baseline.py
    py tools/training/check_gates.py

Exit code 0 means every gate passed; 1 means at least one gate failed; 2 means
the gate configuration or required files could not be read. The report never
prints player text or lexicon terms.
"""
from __future__ import annotations

import argparse
import json
import math
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable

try:
    import joblib
except ImportError as exc:
    print("Missing inference dependency:", exc, file=sys.stderr)
    print("Run: py -m pip install -r tools/training/requirements.txt", file=sys.stderr)
    raise SystemExit(2)

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))

import evaluate_challenge as ec  # noqa: E402
import serve_model  # noqa: E402
import train_baseline as tb  # noqa: E402

DEFAULT_CONFIG = HERE / "gates.json"

TEST_METRIC_PATHS = {
    "risk_accuracy": "risk.accuracy",
    "risk_macro_f1": "risk.macro_f1",
    "action_accuracy": "action.accuracy",
    "targeted_precision": "targeted_binary.precision",
    "targeted_recall": "targeted_binary.recall",
    "targeted_f1": "targeted_binary.f1",
}


def load_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def load_jsonl(path: Path) -> list[dict]:
    return [
        json.loads(line)
        for line in path.read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]


def resolve(base: Path, value: str) -> Path:
    path = Path(value)
    return path if path.is_absolute() else base / path


def metric_value(metrics: dict, dotted: str) -> float | None:
    current: Any = metrics
    for part in dotted.split("."):
        if not isinstance(current, dict) or part not in current:
            return None
        current = current[part]
    if isinstance(current, bool) or not isinstance(current, (int, float)):
        return None
    value = float(current)
    return value if math.isfinite(value) else None


def result(
    name: str,
    ok: bool,
    *,
    value: float | None = None,
    threshold: float | None = None,
    kind: str = "",
    detail: str = "",
) -> dict:
    item: dict[str, Any] = {"gate": name, "ok": bool(ok)}
    if value is not None:
        item["value"] = value
    if threshold is not None:
        item["threshold"] = threshold
    if kind:
        item["kind"] = kind
    if detail:
        item["detail"] = detail
    return item


def minimum(name: str, value: float | None, threshold: float) -> dict:
    if value is None:
        return result(name, False, threshold=threshold, kind="min",
                      detail="missing or non-finite metric")
    return result(name, value >= threshold, value=value, threshold=threshold, kind="min")


def maximum(name: str, value: float | None, threshold: float) -> dict:
    if value is None:
        return result(name, False, threshold=threshold, kind="max",
                      detail="missing or non-finite metric")
    return result(name, value <= threshold, value=value, threshold=threshold, kind="max")


def exact(name: str, value: int | None, expected: int) -> dict:
    if value is None:
        return result(name, False, threshold=float(expected), kind="exact",
                      detail="missing value")
    return result(name, value == expected, value=float(value),
                  threshold=float(expected), kind="exact")


def collect_test_metric_gates(metrics: dict, config: dict) -> list[dict]:
    gates: list[dict] = []
    for key, threshold in config.items():
        if key == "min_test_rows":
            continue
        dotted = TEST_METRIC_PATHS.get(key)
        if dotted is None:
            gates.append(result(f"test.{key}", False, detail="unknown gate key"))
            continue
        gates.append(minimum(f"test.{key}", metric_value(metrics, dotted), float(threshold)))
    if "min_test_rows" in config:
        gates.append(minimum(
            "test.rows", metric_value({"rows": metrics.get("test_rows")}, "rows"),
            float(config["min_test_rows"]),
        ))
    return gates


def _text_signature(row: dict) -> tuple[str, str, str]:
    return (
        str(row.get("text", "")).strip().lower(),
        str(row.get("label", "")),
        str(row.get("category", "")),
    )


def collect_dataset_gates(
        report: dict,
        train: list[dict],
        test: list[dict],
        clean: list[dict],
        config: dict,
) -> list[dict]:
    gates = [
        minimum("dataset.unique_records", metric_value({"v": report.get("unique_records")}, "v"),
                float(config.get("min_unique_records", 0))),
        minimum("dataset.train_rows", metric_value({"v": report.get("train_rows")}, "v"),
                float(config.get("min_train_rows", 0))),
        minimum("dataset.test_rows", metric_value({"v": report.get("test_rows")}, "v"),
                float(config.get("min_test_rows", 0))),
    ]
    gates.append(exact("dataset.train_rows_match_file", len(train),
                       int(report.get("train_rows", -1))))
    gates.append(exact("dataset.test_rows_match_file", len(test),
                       int(report.get("test_rows", -1))))
    gates.append(exact("dataset.unique_records_match_clean", len(clean),
                       int(report.get("unique_records", -1))))
    family_overlap = len(
        {row.get("family") for row in train}
        & {row.get("family") for row in test}
    )
    gates.append(maximum("dataset.train_test_family_overlap", family_overlap,
                         float(config.get("max_train_test_family_overlap", 0))))
    train_signatures = {_text_signature(row) for row in train}
    text_overlap = sum(1 for row in test if _text_signature(row) in train_signatures)
    gates.append(maximum("dataset.train_test_text_overlap", text_overlap,
                         float(config.get("max_train_test_text_overlap", 0))))
    return gates


def collect_challenge_gates(report: dict, config: dict, prefix: str = "challenge") -> list[dict]:
    gates = [
        minimum(f"{prefix}.scored_rows", float(report.get("expanded_rows_scored", 0)),
                float(config.get("min_scored_rows", 0))),
        minimum(f"{prefix}.risk_accuracy", float(report.get("risk", {}).get("accuracy", 0.0)),
                float(config.get("risk_accuracy", 0))),
        minimum(f"{prefix}.targeted_precision",
                float(report.get("targeted_binary", {}).get("precision", 0.0)),
                float(config.get("targeted_precision", 0))),
        minimum(f"{prefix}.targeted_recall",
                float(report.get("targeted_binary", {}).get("recall", 0.0)),
                float(config.get("targeted_recall", 0))),
        maximum(f"{prefix}.errors", float(len(report.get("errors", []))),
                float(config.get("max_errors", 0))),
    ]
    return gates


def collect_bundled_gates(metrics: dict, config: dict) -> list[dict]:
    targeted = metrics.get("test_targeted", {})
    challenge_targeted = metrics.get("challenge_targeted", {})
    return [
        minimum("bundled.test_risk_accuracy",
                metric_value(metrics, "test_risk_accuracy"),
                float(config.get("min_test_risk_accuracy", 0))),
        minimum("bundled.test_targeted_f1",
                metric_value(targeted, "f1"),
                float(config.get("min_test_targeted_f1", 0))),
        minimum("bundled.challenge_risk_accuracy",
                metric_value(metrics, "challenge_risk_accuracy"),
                float(config.get("min_challenge_risk_accuracy", 0))),
        minimum("bundled.challenge_targeted_recall",
                metric_value(challenge_targeted, "recall"),
                float(config.get("min_challenge_targeted_recall", 0))),
        maximum("bundled.challenge_errors",
                metric_value(metrics, "challenge_errors"),
                float(config.get("max_challenge_errors", 0))),
    ]


def collect_leakage_gates(
        train: list[dict],
        test: list[dict],
        challenge_rows: Iterable[dict],
        extra_rows: Iterable[dict],
        config: dict,
) -> list[dict]:
    known = {_text_signature(row) for row in [*train, *test, *extra_rows]}
    overlap = sum(1 for row in challenge_rows if _text_signature(row) in known)
    return [
        maximum("leakage.challenge_training_overlap", overlap,
                float(config.get("max_challenge_overlap", 0))),
    ]


def collect_sidecar_gates(model: dict, terms: list[str], config: dict) -> list[dict]:
    gates: list[dict] = []
    cases = [
        ("neutral", config.get("neutral_text", ""), {
            "TARGETED_HARASSMENT": float(config.get("neutral_targeted_max", 0.0)),
            "THREAT": float(config.get("neutral_threat_max", 0.0)),
        }, "max"),
        ("targeted", config.get("targeted_text", ""), {
            "TARGETED_HARASSMENT": float(config.get("targeted_score_min", 0.0)),
        }, "min"),
        ("threat", config.get("threat_text", ""), {
            "THREAT": float(config.get("threat_score_min", 0.0)),
        }, "min"),
    ]
    for name, text, thresholds, direction in cases:
        if not text:
            gates.append(result(f"sidecar.{name}", False, detail="missing config text"))
            continue
        response = serve_model.scores_for(text, model, terms)
        scores = response.get("scores", {})
        for category, threshold in thresholds.items():
            value = scores.get(category)
            gate_name = f"sidecar.{name}.{category.lower()}"
            if direction == "max":
                gates.append(maximum(gate_name, value, threshold))
            else:
                gates.append(minimum(gate_name, value, threshold))
        for category, value in scores.items():
            gates.append(result(
                f"sidecar.{name}.{category.lower()}_range",
                isinstance(value, (int, float)) and 0.0 <= float(value) <= 1.0,
                value=float(value),
            ))
    return gates


def run_gates(config_path: Path | str = DEFAULT_CONFIG, base_dir: Path | str = ROOT) -> dict:
    base = Path(base_dir)
    path = Path(config_path)
    if not path.is_absolute():
        path = base / path
    try:
        config = load_json(path)
    except Exception as exc:
        return {
            "ok": False,
            "error": f"could not read gate config: {type(exc).__name__}: {exc}",
            "checks": [],
        }

    checks: list[dict] = []
    artifacts = config.get("artifacts", {})
    paths = {name: resolve(base, value) for name, value in artifacts.items()}
    for name, artifact in paths.items():
        exists = artifact.exists() and artifact.stat().st_size > 0
        checks.append(result(f"artifact.{name}", exists, detail=str(artifact) if not exists else ""))

    metrics = None
    report = None
    train: list[dict] = []
    test: list[dict] = []
    clean: list[dict] = []
    lexicon: dict[str, list[str]] = {}
    model = None
    bundled_metrics = None
    load_errors: list[str] = []

    try:
        if paths.get("metrics") and paths["metrics"].exists():
            metrics = load_json(paths["metrics"])
    except Exception as exc:
        load_errors.append(f"metrics: {type(exc).__name__}")
    bundled_cfg = config.get("bundled_model", {})
    bundled_metrics_path = resolve(base, bundled_cfg["metrics"]) if bundled_cfg.get("metrics") else None
    try:
        if bundled_metrics_path and bundled_metrics_path.exists():
            bundled_metrics = load_json(bundled_metrics_path)
    except Exception as exc:
        load_errors.append(f"bundled metrics: {type(exc).__name__}")
    try:
        if paths.get("dataset_report") and paths["dataset_report"].exists():
            report = load_json(paths["dataset_report"])
    except Exception as exc:
        load_errors.append(f"dataset_report: {type(exc).__name__}")
    try:
        if paths.get("train") and paths["train"].exists():
            train = load_jsonl(paths["train"])
        if paths.get("test") and paths["test"].exists():
            test = load_jsonl(paths["test"])
        if paths.get("clean") and paths["clean"].exists():
            clean = load_jsonl(paths["clean"])
    except Exception as exc:
        load_errors.append(f"dataset files: {type(exc).__name__}")
    try:
        if paths.get("lexicon") and paths["lexicon"].exists():
            lexicon = load_json(paths["lexicon"])
    except Exception as exc:
        load_errors.append(f"lexicon: {type(exc).__name__}")
    try:
        if paths.get("model") and paths["model"].exists():
            model = joblib.load(paths["model"])
    except Exception as exc:
        load_errors.append(f"model: {type(exc).__name__}")

    for error in load_errors:
        checks.append(result("artifact.load", False, detail=error))
    if model is not None:
        keys_ok = isinstance(model, dict) and "risk_model" in model and "action_model" in model
        checks.append(result("artifact.model_schema", keys_ok,
                             detail="" if keys_ok else "missing risk_model/action_model"))

    if metrics is not None:
        checks.extend(collect_test_metric_gates(metrics, config.get("test_metrics", {})))
    if bundled_metrics is not None:
        checks.extend(collect_bundled_gates(bundled_metrics, bundled_cfg))
    if report is not None:
        checks.extend(collect_dataset_gates(
            report, train, test, clean, config.get("dataset", {})
        ))

    terms = list(dict.fromkeys(
        str(term) for values in lexicon.values() for term in values if str(term).strip()
    ))

    if model is not None and lexicon:
        for challenge in config.get("challenges", []):
            challenge_path = resolve(base, challenge["path"])
            if not challenge_path.exists():
                checks.append(result(f"challenge.{challenge_path.name}.file", False,
                                     detail="missing"))
                continue
            rows = load_jsonl(challenge_path)
            try:
                def predict_risk(items):
                    return model["risk_model"].predict([
                        tb.prepare_text(row["text"], terms) for row in items
                    ])

                def predict_action(items):
                    return model["action_model"].predict([
                        tb.prepare_text(row["text"], terms) for row in items
                    ])

                challenge_report = ec.evaluate(rows, lexicon, predict_risk, predict_action)
                checks.extend(collect_challenge_gates(
                    challenge_report, challenge, f"challenge.{challenge_path.stem}"
                ))
            except Exception as exc:
                checks.append(result(f"challenge.{challenge_path.name}", False,
                                     detail=f"{type(exc).__name__}"))

    leakage = config.get("leakage", {})
    if train and test and leakage:
        challenge_rows: list[dict] = []
        for challenge_path in leakage.get("challenge_paths", []):
            path = resolve(base, challenge_path)
            if path.exists():
                challenge_rows.extend(load_jsonl(path))
        extra_rows: list[dict] = []
        extra_path = resolve(base, leakage["extra_training"]) if leakage.get("extra_training") else None
        if extra_path and extra_path.exists():
            extra_rows = load_jsonl(extra_path)
        checks.extend(collect_leakage_gates(
            train, test, challenge_rows, extra_rows, leakage
        ))

    if model is not None and lexicon:
        try:
            checks.extend(collect_sidecar_gates(model, terms, config.get("sidecar", {})))
        except Exception as exc:
            checks.append(result("sidecar.run", False, detail=type(exc).__name__))

    failed = [check for check in checks if not check["ok"]]
    return {
        "ok": not failed and not load_errors,
        "passed": len(checks) - len(failed),
        "failed": len(failed),
        "checks": checks,
        "config": str(path),
        "generated_at": datetime.now(timezone.utc).isoformat(),
    }


def print_report(report: dict) -> None:
    if report.get("error"):
        print(report["error"], file=sys.stderr)
        return
    for check in report["checks"]:
        status = "PASS" if check["ok"] else "FAIL"
        detail = check.get("detail", "")
        if "value" in check and "threshold" in check:
            relation = {"min": ">=", "max": "<=", "exact": "=="}.get(check.get("kind"), "")
            message = f"{check['value']:.4f} {relation} {check['threshold']:.4f}"
        else:
            message = detail
        print(f"{status} {check['gate']} {message}".rstrip())
    print()
    if report["ok"]:
        print(f"GATES PASSED ({report['passed']} checks)")
    else:
        print(f"GATES FAILED ({report['failed']} failed, {report['passed']} passed)")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", default=str(DEFAULT_CONFIG))
    parser.add_argument("--out", default="data/model/gate_report.json")
    parser.add_argument("--base-dir", default=str(ROOT))
    parser.add_argument("--json", action="store_true", help="print the full JSON report")
    args = parser.parse_args()
    report = run_gates(args.config, args.base_dir)
    if report.get("error"):
        print(report["error"], file=sys.stderr)
        return 2
    out = resolve(Path(args.base_dir), args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print_report(report)
    return 0 if report["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
