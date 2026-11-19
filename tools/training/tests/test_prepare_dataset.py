"""Unit tests for dataset recovery, validation, dedupe and family splitting."""
from __future__ import annotations

import contextlib
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
import support  # noqa: E402

import prepare_dataset as pd  # noqa: E402


class CleanTextTests(unittest.TestCase):
    def test_normalizes_whitespace_and_placeholder(self):
        raw = "  you\u00a0are\u200b a { slur }  "
        self.assertEqual(pd.clean_text(raw), "you are a {SLUR}")

    def test_handles_none_and_control_free_text(self):
        self.assertEqual(pd.clean_text(None), "")
        self.assertEqual(pd.clean_text("plain  text"), "plain text")


class DecodeObjectsTests(unittest.TestCase):
    def test_recovers_compact_pretty_and_fenced_objects(self):
        raw = (
            '{"text":"one","label":"ALLOW","category":"NONE"}\n'
            "```json\n"
            '{\n  "text": "two",\n  "label": "ALLOW",\n  "category": "NONE"\n}\n'
            "```\n"
            '{"text":"three","label":"ALLOW","category":"NONE"}\n'
        )
        objects, rejects = pd.decode_objects(raw)
        self.assertEqual([obj["text"] for _, obj in objects], ["one", "two", "three"])
        self.assertEqual(rejects, [])

    def test_repairs_extra_quote_in_text(self):
        raw = '{"text": "broken "" , "label":"ALLOW","category":"NONE"}'
        objects, _ = pd.decode_objects(raw)
        self.assertEqual(len(objects), 1)
        self.assertEqual(objects[0][1]["text"], "broken ")

    def test_records_invalid_json_and_ignores_non_objects(self):
        raw = '[1, 2, 3]\n{"text": "ok", "label": "ALLOW", "category": "NONE"}\n{"broken": '
        objects, rejects = pd.decode_objects(raw)
        self.assertEqual([obj["text"] for _, obj in objects], ["ok"])
        self.assertTrue(any("invalid JSON" in reject["reason"] for reject in rejects))

    def test_truncated_object_is_rejected_not_crashed(self):
        objects, rejects = pd.decode_objects('{"text": "unfinished", "label":')
        self.assertEqual(objects, [])
        self.assertEqual(len(rejects), 1)


class CanonicalizeTests(unittest.TestCase):
    def test_normalizes_case_channel_and_family(self):
        record, reason = pd.canonicalize({
            "text": "  a   normal message ",
            "label": "allow",
            "category": "none",
            "channel": "not-a-channel",
            "target_group": "Race-Ethnicity",
        }, 7, "unit")
        self.assertIsNone(reason)
        self.assertEqual(record.text, "a normal message")
        self.assertEqual(record.label, "ALLOW")
        self.assertEqual(record.category, "NONE")
        self.assertEqual(record.channel, "public_chat")
        self.assertEqual(record.target_group, "race_ethnicity")
        self.assertEqual(record.family, "unit:7")
        self.assertFalse(record.targeted)

    def test_infers_targeted_for_action_rows(self):
        record, reason = pd.canonicalize({
            "text": "a targeted message",
            "label": "REPHRASE",
            "category": "TARGETED_HATE",
        }, 1, "unit")
        self.assertIsNone(reason)
        self.assertTrue(record.targeted)

    def test_explicit_false_targeted_on_action_row_is_rejected(self):
        _, reason = pd.canonicalize({
            "text": "a targeted message",
            "label": "REPHRASE",
            "category": "TARGETED_HATE",
            "targeted": False,
        }, 1, "unit")
        self.assertEqual(reason, "action row is not marked targeted")

    def test_rejects_inconsistent_label_category_pairs(self):
        cases = [
            ({"label": "ALLOW", "category": "THREAT"}, "ALLOW row has a non-NONE category"),
            ({"label": "REPHRASE", "category": "NONE"}, "action row has category NONE"),
            ({"label": "REPHRASE", "category": "THREAT"}, "THREAT row must be BLOCK"),
            ({"label": "BLOCK", "category": "THREAT", "targeted": False}, "action row is not marked targeted"),
        ]
        for extra, expected in cases:
            with self.subTest(expected=expected):
                obj = {"text": "a complete sentence", **extra}
                _, reason = pd.canonicalize(obj, 1, "unit")
                self.assertEqual(reason, expected)

    def test_rejects_invalid_fields_and_bad_text(self):
        cases = [
            ({"text": "", "label": "ALLOW", "category": "NONE"}, "missing text"),
            ({"text": "ab", "label": "ALLOW", "category": "NONE"}, "text too short"),
            ({"text": "ok text", "label": "MAYBE", "category": "NONE"}, "invalid label"),
            ({"text": "ok text", "label": "ALLOW", "category": "HATE"}, "invalid category"),
            ({"text": "ok text", "label": "ALLOW", "category": "NONE", "target_group": "alien"},
             "invalid target_group"),
            ({"text": "bad \ufffd text", "label": "ALLOW", "category": "NONE"},
             "replacement/control character in text"),
        ]
        for obj, expected in cases:
            with self.subTest(expected=expected):
                _, reason = pd.canonicalize(obj, 1, "unit")
                self.assertTrue(
                    str(reason).startswith(expected),
                    f"{reason!r} does not start with {expected!r}",
                )

    def test_ids_are_stable_for_same_content(self):
        obj = {"text": "same text", "label": "ALLOW", "category": "NONE"}
        first, _ = pd.canonicalize(obj, 1, "unit")
        second, _ = pd.canonicalize(obj, 99, "other")
        self.assertEqual(first.id, second.id)


class DedupeTests(unittest.TestCase):
    def test_collapses_duplicates_and_prefers_complete_note(self):
        records = [
            support.make_record(text="Same   Text", note="", line=1),
            support.make_record(text="same text", note="longer note", line=2),
        ]
        unique, duplicates, conflicts = pd.dedupe(records)
        self.assertEqual(len(unique), 1)
        self.assertEqual(unique[0].note, "longer note")
        self.assertEqual(duplicates[0]["count"], 2)
        self.assertEqual(conflicts, [])

    def test_conflicting_labels_are_removed_and_reported(self):
        records = [
            support.make_record(text="same text", label="ALLOW", category="NONE", line=1),
            support.make_record(text="same text", label="REPHRASE", category="TARGETED_HATE",
                                targeted=True, line=2),
        ]
        unique, duplicates, conflicts = pd.dedupe(records)
        self.assertEqual(unique, [])
        self.assertEqual(duplicates, [])
        self.assertEqual(len(conflicts), 1)
        self.assertEqual(len(conflicts[0]["labels"]), 2)


class HoldOutAllowTests(unittest.TestCase):
    def test_holds_out_context_unsafe_allow_rows(self):
        cases = [
            ("he called me a {SLUR}", []),
            ("just kys", []),
            ("I was going to say something...", []),
            ("a normal sentence with blorple inside", ["blorple"]),
        ]
        for text, terms in cases:
            with self.subTest(text=text):
                record = support.make_record(text=text)
                self.assertTrue(pd.should_hold_out_allow(record, terms))

    def test_keeps_ordinary_allow_rows(self):
        record = support.make_record(text="nice build, how long did it take")
        self.assertFalse(pd.should_hold_out_allow(record, ["blorple"]))
        self.assertFalse(pd.should_hold_out_allow(
            support.make_record(text="targeted text", label="REPHRASE",
                                category="TARGETED_HATE", targeted=True),
            ["blorple"],
        ))


class GroupedSplitTests(unittest.TestCase):
    def _records(self):
        records = []
        line = 1
        for stratum, (label, category, targeted) in enumerate([
            ("ALLOW", "NONE", False),
            ("REPHRASE", "TARGETED_HATE", True),
            ("BLOCK", "THREAT", True),
        ]):
            for family in range(3):
                for index in range(3):
                    records.append(support.make_record(
                        text=f"{stratum}-{family}-{index}",
                        label=label,
                        category=category,
                        targeted=targeted,
                        family=f"fam.{stratum}.{family}",
                        line=line,
                    ))
                    line += 1
        return records

    def test_no_family_crosses_the_split(self):
        train, test = pd.grouped_split(self._records(), 0.2, 42)
        self.assertTrue(train)
        self.assertTrue(test)
        train_families = {record.family for record in train}
        test_families = {record.family for record in test}
        self.assertEqual(train_families & test_families, set())
        self.assertLess(len(test), len(train))

    def test_every_stratum_keeps_training_rows(self):
        train, _ = pd.grouped_split(self._records(), 0.2, 42)
        self.assertEqual({record.label for record in train}, {"ALLOW", "REPHRASE", "BLOCK"})

    def test_split_is_deterministic(self):
        first = pd.grouped_split(self._records(), 0.2, 42)
        second = pd.grouped_split(self._records(), 0.2, 42)
        self.assertEqual([r.id for r in first[0]], [r.id for r in second[0]])
        self.assertEqual([r.id for r in first[1]], [r.id for r in second[1]])


class PrepareCliIntegrationTests(unittest.TestCase):
    def test_main_writes_all_artifacts_and_a_consistent_report(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            source = root / "input.txt"
            out_dir = root / "out"
            rows = []
            for stratum, (label, category, targeted) in enumerate([
                ("ALLOW", "NONE", False),
                ("REPHRASE", "TARGETED_HATE", True),
                ("BLOCK", "THREAT", True),
            ]):
                for family in range(3):
                    for index in range(2):
                        rows.append({
                            "text": f"{stratum} message {family} {index}",
                            "label": label,
                            "category": category,
                            "targeted": targeted,
                            "family": f"family.{stratum}.{family}",
                            "note": "integration",
                        })
            source.write_text(
                "\n".join(json.dumps(row) for row in rows) + "\n",
                encoding="utf-8",
            )
            argv = [
                "prepare_dataset.py",
                "--input", str(source),
                "--generated", str(root / "missing-generated.jsonl"),
                "--out-dir", str(out_dir),
                "--lexicon", str(root / "missing-lexicon.json"),
                "--test-fraction", "0.2",
                "--seed", "42",
            ]
            with mock.patch.object(sys, "argv", argv), contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(pd.main(), 0)

            for name in (
                "clean.jsonl", "train.jsonl", "test.jsonl", "review.jsonl",
                "context_only.jsonl", "rejects.jsonl", "duplicates.jsonl",
                "dataset_report.json",
            ):
                with self.subTest(file=name):
                    self.assertTrue((out_dir / name).exists(), name)

            report = json.loads((out_dir / "dataset_report.json").read_text(encoding="utf-8"))
            clean = support.load_jsonl(out_dir / "clean.jsonl")
            train = support.load_jsonl(out_dir / "train.jsonl")
            test = support.load_jsonl(out_dir / "test.jsonl")
            self.assertEqual(report["raw_objects_recovered"], len(rows))
            self.assertEqual(report["unique_records"], len(clean))
            self.assertEqual(report["train_rows"], len(train))
            self.assertEqual(report["test_rows"], len(test))
            self.assertEqual(len(train) + len(test), len(clean))
            self.assertEqual(
                {row["family"] for row in train} & {row["family"] for row in test},
                set(),
            )


if __name__ == "__main__":
    unittest.main()
