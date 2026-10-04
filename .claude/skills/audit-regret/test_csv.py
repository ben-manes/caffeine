#!/usr/bin/env python3
"""Tests for preserving measurements when audit CSV output is resumed."""

import contextlib
import csv
import io
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
import regret
import search


class CsvTest(unittest.TestCase):
    """Tests both commands' output compatibility and append behavior."""

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name) / "results.csv"
        self.row = dict.fromkeys(regret.FIELDS, "")
        self.row.update(label="new", variant="hybrid", size="8192", seeds="1",
                        gap="4.0", ghost="70.0", ghost_gap="5.0", cf_loss="6.0",
                        cf_best="71.0", law_agree="0.5")

    def write_csv(self, fields, rows):
        """Create an output file using the specified schema."""
        with self.path.open("w", newline="") as stream:
            writer = csv.DictWriter(stream, fieldnames=fields)
            writer.writeheader()
            writer.writerows(rows)

    def invoke(self, module):
        """Run the command with a known measurement and no simulator process."""
        args = ["new.json", "--size", "8192", "--csv", str(self.path)]
        if module is search:
            args = ["eval", *args, "--rank", "ghost_gap"]
        with (mock.patch.object(sys, "argv", [module.__file__, *args]),
              mock.patch.object(regret, "resolve_trace", return_value=("trace.lirs", {})),
              mock.patch.object(regret, "evaluate_cell", return_value=[self.row]),
              contextlib.redirect_stdout(io.StringIO())):
            module.main()

    def test_new_and_empty_outputs_keep_measurements(self):
        for module in (regret, search):
            for empty in (False, True):
                with self.subTest(command=module.__name__, empty=empty):
                    self.path.unlink(missing_ok=True)
                    if empty:
                        self.path.touch()
                    self.invoke(module)
                    with self.path.open(newline="") as stream:
                        rows = list(csv.DictReader(stream))
                    self.assertEqual(rows, [self.row])
                    self.assertEqual(search.rank_key(rows[0], "ghost_gap"), 5.0)

    def test_resume_keeps_old_rows_and_new_measurements(self):
        old = dict(self.row, label="old", ghost_gap="3.0")
        for module in (regret, search):
            with self.subTest(command=module.__name__):
                self.write_csv(regret.FIELDS, [old])
                before = self.path.read_bytes()
                self.invoke(module)
                self.assertTrue(self.path.read_bytes().startswith(before))
                with self.path.open(newline="") as stream:
                    self.assertEqual(list(csv.DictReader(stream)), [old, self.row])

    def test_incompatible_headers_fail_before_work_and_preserve_file(self):
        for module in (regret, search):
            for fields in (regret.FIELDS[:-5], list(reversed(regret.FIELDS))):
                with self.subTest(command=module.__name__, fields=fields):
                    self.write_csv(fields, [{key: self.row[key] for key in fields}])
                    before = self.path.read_bytes()
                    args = ["new.json", "--size", "8192", "--csv", str(self.path)]
                    if module is search:
                        args.insert(0, "eval")
                    with (mock.patch.object(sys, "argv", [module.__file__, *args]),
                          mock.patch.object(regret, "resolve_trace") as resolve,
                          mock.patch.object(regret, "evaluate_cell") as evaluate,
                          self.assertRaisesRegex(ValueError, "use a new --csv path")):
                        module.main()
                    resolve.assert_not_called()
                    evaluate.assert_not_called()
                    self.assertEqual(self.path.read_bytes(), before)
                    with self.assertRaisesRegex(ValueError, "incompatible CSV header"):
                        regret.append_csv(self.path, [self.row])
                    self.assertEqual(self.path.read_bytes(), before)


if __name__ == "__main__":
    unittest.main()
