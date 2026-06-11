#!/usr/bin/env python3
import argparse
import contextlib
import io
import os
import statistics
import sys
import time
from pathlib import Path

os.environ.setdefault("NUMBA_CACHE_DIR", "/tmp/william-numba-cache")

CIWI_ROOT = Path(__file__).resolve().parents[1]
WILLIAM_ROOT = CIWI_ROOT.parent / "william"
sys.path.insert(0, str(WILLIAM_ROOT))

import numpy as np

with contextlib.redirect_stdout(sys.stderr):
    from william.alice import CompressionTask, GreedyAlice, TaskDomain
    from william.fix_op import Fix
    from william.library.basic_ops import (
        Add,
        BRange,
        Concat,
        Equal,
        GetItem,
        LessThan,
        Map,
        Mult,
        Negate,
        Repeat,
    )
    from william.library.complex_ops import CumSum, Insert


BASIC_OPS = [
    Map,
    Fix,
    BRange,
    Add,
    Mult,
    Negate,
    Concat,
    Repeat,
    GetItem,
    Insert,
    CumSum,
    LessThan,
    Equal,
]

DEFAULT_TASKS = ["insert_repeat3", "increasing_runs", "reg_only_y"]
DEFAULT_SCALES = ["small", "medium", "large"]
DEFAULT_WORKERS = [1, 2, 4, 8]


def insert_repeat3_target(head: int, pairs: int, tail: int) -> np.ndarray:
    return np.array([45] * head + [87, 62] * pairs + [164] * tail, dtype=int)


def increasing_runs_target(n_runs: int) -> np.ndarray:
    values: list[int] = []
    for x in range(n_runs):
        values.extend([123] * x)
        values.append(64)
    return np.array(values, dtype=int)


def reg_only_y_target(n: int) -> np.ndarray:
    return np.arange(n, dtype=int) * 3 - 5


def task_case(task: str, scale: str) -> tuple[np.ndarray, float]:
    match (task, scale):
        case ("insert_repeat3", "small"):
            return insert_repeat3_target(25, 62, 152), 93.0
        case ("insert_repeat3", "medium"):
            return insert_repeat3_target(50, 125, 305), 93.0
        case ("insert_repeat3", "large"):
            return insert_repeat3_target(100, 250, 610), 93.0
        case ("increasing_runs", "small"):
            return increasing_runs_target(150), 99.9
        case ("increasing_runs", "medium"):
            return increasing_runs_target(300), 99.9
        case ("increasing_runs", "large"):
            return increasing_runs_target(500), 99.9
        case ("reg_only_y", "small"):
            return reg_only_y_target(1000), 98.0
        case ("reg_only_y", "medium"):
            return reg_only_y_target(5000), 98.0
        case ("reg_only_y", "large"):
            return reg_only_y_target(10000), 98.0
        case _:
            raise ValueError(f"unknown task/scale: {task}/{scale}")


def timed_run(task_name: str, scale: str, workers: int) -> dict[str, object]:
    target, threshold_rate = task_case(task_name, scale)
    task = CompressionTask([target], threshold_rate=threshold_rate, name=task_name, solutions={})
    domain = TaskDomain(f"bench_{task_name}", [task], BASIC_OPS)
    alice = GreedyAlice(
        domain,
        min_rate=0.01,
        max_dag_dl=35,
        learn=False,
        trees_only=False,
        use_rust=False,
        num_workers=None if workers == 1 else workers,
    )
    started = time.perf_counter()
    error = None
    try:
        alice.run()
    except RuntimeError as exc:
        error = exc
    elapsed_ms = (time.perf_counter() - started) * 1000.0
    stats = alice.last_run_stats or {}
    compression_rate = float(stats.get("final_rate", 0.0)) / 100.0
    stop_reason = stats.get("stop_reason", "unknown")
    if error is not None:
        stop_reason = f"error:{stop_reason}"
    return {
        "elapsed_ms": elapsed_ms,
        "length": len(target),
        "compression_rate": compression_rate,
        "meets_threshold": compression_rate >= threshold_rate / 100.0,
        "steps": int(stats.get("steps_succeeded", 0)),
        "stop_reason": stop_reason,
    }


def summarize(task: str, scale: str, workers: int, warmups: int, runs: int) -> dict[str, object]:
    for _ in range(warmups):
        timed_run(task, scale, workers)
    samples = [timed_run(task, scale, workers) for _ in range(runs)]
    times = [float(sample["elapsed_ms"]) for sample in samples]
    last = samples[-1]
    return {
        **last,
        "impl": "python",
        "task": task,
        "scale": scale,
        "workers": workers,
        "warmups": warmups,
        "runs": runs,
        "median_ms": statistics.median(times),
        "min_ms": min(times),
        "max_ms": max(times),
    }


def csv_row(row: dict[str, object]) -> str:
    return ",".join(
        [
            str(row["impl"]),
            str(row["task"]),
            str(row["scale"]),
            str(row["workers"]),
            str(row["length"]),
            str(row["warmups"]),
            str(row["runs"]),
            f"{float(row['median_ms']):.3f}",
            f"{float(row['min_ms']):.3f}",
            f"{float(row['max_ms']):.3f}",
            f"{float(row['compression_rate']):.9f}",
            str(row["meets_threshold"]).lower(),
            str(row["steps"]),
            str(row["stop_reason"]),
        ]
    )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--tasks", default=",".join(DEFAULT_TASKS))
    parser.add_argument("--scales", default=",".join(DEFAULT_SCALES))
    parser.add_argument("--workers", default=",".join(map(str, DEFAULT_WORKERS)))
    parser.add_argument("--warmups", type=int, default=1)
    parser.add_argument("--runs", type=int, default=1)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    tasks = args.tasks.split(",")
    scales = args.scales.split(",")
    workers = [int(x) for x in args.workers.split(",")]
    print(
        "impl,task,scale,workers,length,warmups,runs,median_ms,min_ms,max_ms,"
        "compression_rate,meets_threshold,steps,stop_reason"
    )
    for task in tasks:
        for scale in scales:
            for worker_count in workers:
                print(csv_row(summarize(task, scale, worker_count, args.warmups, args.runs)))


if __name__ == "__main__":
    main()
