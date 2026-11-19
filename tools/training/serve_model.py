#!/usr/bin/env python3
"""
Local inference server for the first CleanSpeak baseline model.

Usage:
    py tools/training/train_baseline.py
    py tools/training/serve_model.py --host 127.0.0.1 --port 8091

Then configure:
    text.ml.enabled: true
    text.ml.endpoint: "http://127.0.0.1:8091/classify"

The endpoint is local by default. It does not log message text or store requests.
"""

from __future__ import annotations

import argparse
import json
import sys
import threading
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

try:
    import joblib
    import numpy as np
except ImportError as exc:
    print("Missing inference dependency:", exc, file=sys.stderr)
    print("Run: py -m pip install -r tools/training/requirements.txt", file=sys.stderr)
    raise SystemExit(2)

from train_baseline import PLACEHOLDER, HAS_SLUR, prepare_text

MAX_BODY = 64 * 1024
_model = None
_terms: list[str] = []
_model_path: Path | None = None
_lock = threading.Lock()
_inflight = threading.BoundedSemaphore(2)


def load_model(path: Path, lexicon_path: Path):
    global _model, _terms, _model_path
    with _lock:
        if _model is None:
            _model = joblib.load(path)
            _model_path = path
            if lexicon_path.exists():
                try:
                    lexicon = json.loads(lexicon_path.read_text(encoding="utf-8"))
                    _terms = list(dict.fromkeys(
                        str(term) for values in lexicon.values() for term in values if str(term).strip()
                    ))
                except Exception:
                    _terms = []
    return _model


def scores_for(text: str, model=None, terms: list[str] | None = None) -> dict:
    """Return the sidecar response for one piece of text.

    ``model`` and ``terms`` can be supplied by tests so this contract does not
    depend on the private on-disk artifact. Production calls use the globals.
    """
    model = _model if model is None else model
    terms = _terms if terms is None else terms
    if model is None:
        raise RuntimeError("model is not loaded")
    prepared = prepare_text(text[:2000], terms)
    risk = model["risk_model"]
    classes = list(risk.classes_)
    probability = risk.predict_proba([prepared])[0]
    by_class = {name: float(probability[classes.index(name)]) for name in classes}
    scores = {
        # The first baseline predicts a broad TARGETED risk, not a reliable
        # HATE-vs-HARASSMENT subtype. The plugin treats both as rephrase-capable.
        "TARGETED_HARASSMENT": round(by_class.get("TARGETED", 0.0), 6),
        "THREAT": round(by_class.get("THREAT", 0.0), 6),
    }
    confidence = float(max(by_class.values())) if by_class else 0.0
    return {
        "ok": True,
        "scores": scores,
        "confidence": round(confidence, 6),
        "model_version": "cleanspeak-baseline-v1",
    }


class Handler(BaseHTTPRequestHandler):
    server_version = "CleanSpeakBaseline/1.0"

    def log_message(self, fmt, *args):
        # Never log request bodies, text or scores.
        print("[model] " + (fmt % args), flush=True)

    def _json(self, status: int, body: dict):
        payload = json.dumps(body, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def do_GET(self):
        if self.path == "/health":
            self._json(HTTPStatus.OK, {
                "ok": _model is not None,
                "model_version": "cleanspeak-baseline-v1",
                "model_path": str(_model_path) if _model_path else "",
            })
            return
        self._json(HTTPStatus.NOT_FOUND, {"ok": False, "error": "not found"})

    def do_POST(self):
        if self.path != "/classify":
            self._json(HTTPStatus.NOT_FOUND, {"ok": False, "error": "not found"})
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            self._json(HTTPStatus.BAD_REQUEST, {"ok": False, "error": "invalid length"})
            return
        if length <= 0 or length > MAX_BODY:
            self._json(HTTPStatus.REQUEST_ENTITY_TOO_LARGE, {"ok": False, "error": "request size out of range"})
            return
        if not _inflight.acquire(blocking=False):
            self._json(HTTPStatus.SERVICE_UNAVAILABLE, {"ok": False, "error": "worker overloaded"})
            return
        try:
            body = self.rfile.read(length)
            try:
                payload = json.loads(body.decode("utf-8"))
            except (UnicodeDecodeError, json.JSONDecodeError):
                self._json(HTTPStatus.BAD_REQUEST, {"ok": False, "error": "invalid json"})
                return
            if not isinstance(payload, dict):
                self._json(HTTPStatus.BAD_REQUEST, {"ok": False, "error": "json object required"})
                return
            text = payload.get("text")
            if not isinstance(text, str):
                self._json(HTTPStatus.BAD_REQUEST, {"ok": False, "error": "text is required"})
                return
            self._json(HTTPStatus.OK, scores_for(text))
        except Exception as exc:
            self._json(HTTPStatus.INTERNAL_SERVER_ERROR, {"ok": False, "error": type(exc).__name__})
        finally:
            _inflight.release()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8091)
    parser.add_argument("--model", default="data/model/model.joblib")
    parser.add_argument("--lexicon", default="data/lexicon.local.json")
    args = parser.parse_args()
    model_path = Path(args.model)
    lexicon_path = Path(args.lexicon)
    if not model_path.exists():
        print(f"model not found: {model_path}", file=sys.stderr)
        print("Run tools/training/train_baseline.py first.", file=sys.stderr)
        return 2
    if args.host not in ("127.0.0.1", "::1", "localhost"):
        print("[model] WARNING: binding to a non-loopback interface. Configure the plugin's "
              "allow-remote-endpoint only with a privacy/security plan.", flush=True)
    load_model(model_path, lexicon_path)
    print(f"[model] listening on {args.host}:{args.port}; no message text is logged", flush=True)
    ThreadingHTTPServer((args.host, args.port), Handler).serve_forever()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
