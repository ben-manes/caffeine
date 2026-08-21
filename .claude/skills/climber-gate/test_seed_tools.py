#!/usr/bin/env python3
"""Tests for seed identity in the climber gate's comparison tools."""

import csv
import contextlib
import importlib.util
import io
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock


HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))


def load_module(name, path):
    """Load a sibling script as a module."""
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


PAIR = load_module("climber_pair", HERE / "pair.py")
RUN = load_module("climber_run", HERE / "run.py")
BY_WORKLOAD = load_module("climber_byworkload", HERE / "byworkload.py")
SWEEP = load_module("climber_sweep", HERE / "sweep.py")
FIELDS = ["label", "size", "arm", "n", "runs", "seeds"]


class PairTest(unittest.TestCase):
    """Tests the long-format seeded comparison contract."""

    def write_rows(self, rows, fields=FIELDS):
        """Write rows to a temporary CSV and return its path."""
        csv_file = tempfile.NamedTemporaryFile(mode="w", newline="", delete=False)
        with csv_file:
            writer = csv.DictWriter(csv_file, fieldnames=fields)
            writer.writeheader()
            writer.writerows(rows)
        self.addCleanup(Path(csv_file.name).unlink)
        return csv_file.name

    def test_pairs_by_seed_identity(self):
        path = self.write_rows([
            {"label": "cell", "size": "4096", "arm": "a", "n": "3",
             "runs": "10 20 30", "seeds": "7 11 13"},
            {"label": "cell", "size": "4096", "arm": "b", "n": "3",
             "runs": "33 17 21", "seeds": "13 7 11"},
        ])

        self.assertEqual(
            PAIR.read_pairs(path, "a", "b"),
            [("cell", "4096", [(7, 7.0), (11, 1.0), (13, 3.0)])])

    def test_rejects_legacy_positional_rows(self):
        path = self.write_rows([
            {"label": "cell", "size": "4096", "arm": "a", "n": "2", "runs": "1 2"},
        ], fields=["label", "size", "arm", "n", "runs"])

        with self.assertRaisesRegex(ValueError, "missing required CSV columns: seeds"):
            PAIR.read_pairs(path, "a", "b")

    def test_rejects_mismatched_seed_sets(self):
        path = self.write_rows([
            {"label": "cell", "size": "4096", "arm": "a", "n": "2",
             "runs": "1 2", "seeds": "7 11"},
            {"label": "cell", "size": "4096", "arm": "b", "n": "2",
             "runs": "3 4", "seeds": "7 13"},
        ])

        with self.assertRaisesRegex(ValueError, "seed sets differ"):
            PAIR.read_pairs(path, "a", "b")

    def test_rejects_duplicate_seed(self):
        row = {"label": "cell", "size": "4096", "arm": "a", "n": "2",
               "runs": "1 2", "seeds": "7 7"}

        with self.assertRaisesRegex(ValueError, "duplicate seed 7"):
            PAIR.parse_seeded_runs(row)

    def test_accepts_signed_long_seed_bounds(self):
        row = {"label": "cell", "size": "4096", "arm": "a", "n": "2",
               "runs": "1 2", "seeds": f"{-(1 << 63)} {(1 << 63) - 1}"}

        self.assertEqual(
            set(PAIR.parse_seeded_runs(row)), {-(1 << 63), (1 << 63) - 1})

    def test_rejects_seed_outside_signed_long_range(self):
        row = {"label": "cell", "size": "4096", "arm": "a", "n": "1",
               "runs": "1", "seeds": str(1 << 63)}

        with self.assertRaisesRegex(ValueError, "outside the signed 64-bit range"):
            PAIR.parse_seeded_runs(row)

    def test_rejects_non_finite_hit_rate(self):
        row = {"label": "cell", "size": "4096", "arm": "a", "n": "1",
               "runs": "nan", "seeds": "7"}

        with self.assertRaisesRegex(ValueError, "non-finite hit rate"):
            PAIR.parse_seeded_runs(row)

    def test_rejects_count_mismatch(self):
        row = {"label": "cell", "size": "4096", "arm": "a", "n": "3",
               "runs": "1 2", "seeds": "7 11"}

        with self.assertRaisesRegex(ValueError, "n=3 but found 2 seeds"):
            PAIR.parse_seeded_runs(row)

    def test_rejects_incomplete_arm_pair(self):
        path = self.write_rows([
            {"label": "cell", "size": "4096", "arm": "a", "n": "1",
             "runs": "1", "seeds": "7"},
        ])

        with self.assertRaisesRegex(ValueError, "missing arm b"):
            PAIR.read_pairs(path, "a", "b")

    def test_rejects_duplicate_arm_row(self):
        row = {"label": "cell", "size": "4096", "arm": "a", "n": "1",
               "runs": "1", "seeds": "7"}
        path = self.write_rows([row, row])

        with self.assertRaisesRegex(ValueError, "duplicate arm a"):
            PAIR.read_pairs(path, "a", "b")


class ByWorkloadTest(unittest.TestCase):
    """Tests seed identity across the tier boundary."""

    def test_pairs_sizes_by_seed_identity(self):
        runs = {"cell": {
            4096: {1: 10.0, 2: 20.0},
            4097: {2: 23.0, 1: 11.0},
        }}

        self.assertEqual(BY_WORKLOAD.cliff_deltas(runs), {"cell": [1.0, 3.0]})

    def test_rejects_size_seed_mismatch(self):
        runs = {"cell": {
            4096: {1: 10.0, 2: 20.0},
            4097: {1: 11.0, 3: 23.0},
        }}

        with self.assertRaisesRegex(ValueError, "4096/4097 seed sets differ"):
            BY_WORKLOAD.cliff_deltas(runs)

    def test_rejects_missing_size(self):
        with self.assertRaisesRegex(ValueError, "missing hybrid row at size 4097"):
            BY_WORKLOAD.cliff_deltas({"cell": {4096: {1: 10.0}}})


class RunTest(unittest.TestCase):
    """Tests seed parsing and execution order."""

    def test_interleaves_variants_within_seed(self):
        self.assertEqual(
            RUN.execution_plan(["hybrid", "reactive"], 99, [7, 11]),
            [("hybrid", 7, 0), ("reactive", 7, 0),
             ("hybrid", 11, 1), ("reactive", 11, 1)])

    def test_unseeded_order_is_unchanged(self):
        self.assertEqual(
            RUN.execution_plan(["hybrid", "reactive"], 2, None),
            [("hybrid", None, 0), ("hybrid", None, 1),
             ("reactive", None, 0), ("reactive", None, 1)])

    def test_rejects_duplicate_seeds(self):
        with self.assertRaisesRegex(ValueError, "must not contain duplicates"):
            RUN.parse_seeds("7,7")

    def test_accepts_signed_long_seed_bounds(self):
        self.assertEqual(
            RUN.parse_seeds(f"{-(1 << 63)},{(1 << 63) - 1}"),
            [-(1 << 63), (1 << 63) - 1])

    def test_rejects_seed_outside_signed_long_range(self):
        with self.assertRaisesRegex(ValueError, "signed 64-bit integers"):
            RUN.parse_seeds(str(-(1 << 63) - 1))

    def test_rejects_duplicate_variants(self):
        with self.assertRaisesRegex(ValueError, "must not contain duplicates"):
            RUN.parse_variants("a,a")

    def test_prints_seed_identity_on_existing_summary_line(self):
        argv = ["run.py", "trace.lirs", "--size", "4096", "--variants", "a,b",
                "--seeds", "7,11"]
        rates = iter([(10.0, []), (20.0, []), (11.0, []), (21.0, [])])
        output = io.StringIO()
        with mock.patch.object(sys, "argv", argv), \
                mock.patch.object(RUN, "variant", side_effect=lambda *args, **kwargs: next(rates)), \
                contextlib.redirect_stdout(output):
            RUN.main()

        lines = output.getvalue().splitlines()
        self.assertIn("seeds=s7:10.00,s11:11.00", lines[1])
        self.assertIn("seeds=s7:20.00,s11:21.00", lines[2])

    def test_missing_seeded_result_fails(self):
        argv = ["run.py", "trace.lirs", "--size", "4096", "--seeds", "7"]
        output = io.StringIO()
        with mock.patch.object(sys, "argv", argv), \
                mock.patch.object(RUN, "variant", return_value=(None, [])), \
                contextlib.redirect_stdout(output), \
                self.assertRaisesRegex(SystemExit, "missing hit rate.*seed=7"):
            RUN.main()


class SweepTest(unittest.TestCase):
    """Tests the canonical long-format producer."""

    def test_interleaves_and_retains_seed_identity(self):
        calls = []

        def variant(trace, size, arm, fmt, seed):
            calls.append((arm, seed))
            return float(seed), []

        with mock.patch.object(SWEEP.R, "variant", side_effect=variant):
            results = SWEEP.measure("trace", 4096, "lirs", ["a", "b"], [7, 11])

        self.assertEqual(calls, [("a", 7), ("b", 7), ("a", 11), ("b", 11)])
        self.assertEqual(results["a"], [(7, 7.0), (11, 11.0)])

    def test_output_round_trips_through_pair_reader(self):
        csv_file = tempfile.NamedTemporaryFile(mode="w", newline="", delete=False)
        with csv_file:
            writer = csv.writer(csv_file)
            writer.writerow(SWEEP.FIELDS)
            SWEEP.write_arm(writer, "cell", 4096, "a", [(7, 10.0), (11, 20.0)],
                            "", "", "")
            SWEEP.write_arm(writer, "cell", 4096, "b", [(11, 23.0), (7, 11.0)],
                            "", "", "")
        self.addCleanup(Path(csv_file.name).unlink)

        self.assertEqual(
            PAIR.read_pairs(csv_file.name, "a", "b"),
            [("cell", "4096", [(7, 1.0), (11, 3.0)])])

    def test_missing_result_aborts_cell(self):
        with mock.patch.object(SWEEP.R, "variant", return_value=(None, [])), \
                self.assertRaisesRegex(RuntimeError, "missing hit rate.*seed=7"):
            SWEEP.measure("trace", 4096, "lirs", ["a"], [7])

    def test_rejects_duplicate_cells_before_measurement(self):
        cells = [
            {"label": "cell", "size": 4096},
            {"label": "cell", "size": 4096},
        ]

        with self.assertRaisesRegex(ValueError, "duplicate cell cell@4096"):
            SWEEP.validate_cells(cells)

    def test_rejects_legacy_resume_file(self):
        csv_file = tempfile.NamedTemporaryFile(mode="w", newline="", delete=False)
        with csv_file:
            csv_file.write("label,size,arm,n,runs\n")
        self.addCleanup(Path(csv_file.name).unlink)

        with self.assertRaisesRegex(ValueError, "expected CSV header"):
            SWEEP.load_done(csv_file.name, [7])


if __name__ == "__main__":
    unittest.main()
