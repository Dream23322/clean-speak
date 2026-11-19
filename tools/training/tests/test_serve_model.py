"""Tests for the local model sidecar's response and HTTP contract."""
from __future__ import annotations

import json
import sys
import threading
import unittest
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import support  # noqa: E402

import serve_model  # noqa: E402


class ScoresForTests(unittest.TestCase):
    def setUp(self):
        self.model = {"risk_model": support.FakeRiskModel()}

    def test_returns_targeted_and_threat_scores(self):
        result = serve_model.scores_for("you are a blorple", self.model, ["blorple"])
        self.assertTrue(result["ok"])
        self.assertEqual(result["model_version"], "cleanspeak-baseline-v1")
        self.assertAlmostEqual(result["scores"]["TARGETED_HARASSMENT"], 0.96)
        self.assertAlmostEqual(result["scores"]["THREAT"], 0.02)
        self.assertAlmostEqual(result["confidence"], 0.96)

    def test_threat_marker_returns_a_high_threat_score(self):
        result = serve_model.scores_for("threat", self.model, [])
        self.assertGreater(result["scores"]["THREAT"], 0.9)
        self.assertLess(result["scores"]["TARGETED_HARASSMENT"], 0.1)

    def test_neutral_text_stays_low(self):
        result = serve_model.scores_for("nice build", self.model, ["blorple"])
        self.assertLess(result["scores"]["TARGETED_HARASSMENT"], 0.1)
        self.assertLess(result["scores"]["THREAT"], 0.1)

    def test_scores_are_rounded_and_in_range(self):
        result = serve_model.scores_for("blorple", self.model, ["blorple"])
        for value in result["scores"].values():
            self.assertGreaterEqual(value, 0.0)
            self.assertLessEqual(value, 1.0)
            self.assertEqual(value, round(value, 6))

    def test_missing_model_is_an_error(self):
        with self.assertRaises(RuntimeError):
            serve_model.scores_for("hello", None, [])


class HttpContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls._old_model = serve_model._model
        cls._old_terms = serve_model._terms
        cls._old_log = serve_model.Handler.log_message
        serve_model._model = {"risk_model": support.FakeRiskModel()}
        serve_model._terms = ["blorple"]
        serve_model.Handler.log_message = lambda *args, **kwargs: None
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), serve_model.Handler)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.base = f"http://127.0.0.1:{cls.server.server_port}"

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join(timeout=5)
        serve_model._model = cls._old_model
        serve_model._terms = cls._old_terms
        serve_model.Handler.log_message = cls._old_log

    def _request(self, path, body=None, headers=None):
        data = None if body is None else (
            body if isinstance(body, bytes) else json.dumps(body).encode("utf-8")
        )
        request = urllib.request.Request(
            self.base + path,
            data=data,
            headers=headers or ({"Content-Type": "application/json"} if data else {}),
        )
        try:
            with urllib.request.urlopen(request, timeout=5) as response:
                return response.status, json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as error:
            return error.code, json.loads(error.read().decode("utf-8"))

    def test_health_reports_loaded_model(self):
        status, body = self._request("/health")
        self.assertEqual(status, 200)
        self.assertTrue(body["ok"])
        self.assertEqual(body["model_version"], "cleanspeak-baseline-v1")

    def test_classify_returns_scores(self):
        status, body = self._request("/classify", {"text": "you are a blorple"})
        self.assertEqual(status, 200)
        self.assertTrue(body["ok"])
        self.assertIn("TARGETED_HARASSMENT", body["scores"])
        self.assertIn("THREAT", body["scores"])

    def test_missing_text_is_rejected(self):
        status, body = self._request("/classify", {"message": "wrong key"})
        self.assertEqual(status, 400)
        self.assertFalse(body["ok"])

    def test_non_string_text_is_rejected(self):
        status, _ = self._request("/classify", {"text": 42})
        self.assertEqual(status, 400)

    def test_invalid_json_and_non_object_are_rejected(self):
        status, _ = self._request(
            "/classify",
            b"{not json",
            {"Content-Type": "application/json", "Content-Length": "9"},
        )
        self.assertEqual(status, 400)
        status, _ = self._request("/classify", [1, 2, 3])
        self.assertEqual(status, 400)

    def test_oversized_body_is_rejected_before_reading(self):
        status, body = self._request(
            "/classify",
            b"{}",
            {
                "Content-Type": "application/json",
                "Content-Length": str(serve_model.MAX_BODY + 1),
            },
        )
        self.assertEqual(status, 413)
        self.assertFalse(body["ok"])

    def test_unknown_routes_return_404(self):
        status, _ = self._request("/nope")
        self.assertEqual(status, 404)


if __name__ == "__main__":
    unittest.main()
