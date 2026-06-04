# Alice Test Parity Matrix

This file tracks CIWI's current parity against Python WILLIAM's
`william/tests/test_alice.py`. The goal is compression behavior parity, not
routine-by-routine helper parity.

The Python timings below were measured with the local checkout's
`_run_single_task_worker(task-name, None)`, which constructs `GreedyAlice` with
`max_dag_dl=35`, `learn=false`, `trees_only=false`, and `use_rust=false`.

The CIWI timings were measured with `ciwi.alice/run-task-comparison`, running
the current exhaustive reference plus bounded local rewrite comparison with
`re-eval-budget 256`. These timings are not runner-equivalent to Python, but
they expose where CIWI's current generic comparison is plainly too slow.

Rows marked `performance-gap` use the full Python-scale target generator in
`ciwi.alice-test`; they are not replaced by scaled-down tests. Their compression
assertions are excluded from the default test path until the performance issue
is understood and fixed.

| Python task | Length | Python threshold | Python serial ms | CIWI status | CIWI ms | CIWI selected family | Notes |
| --- | ---: | ---: | ---: | --- | ---: | --- | --- |
| `simple_repeat` | 1,000 | 94.0 | 1,061 | covered | 834 | `repeat` | Exact CIWI expression: `[:repeat 500 [140 -50]]`. |
| `insert_repeat` | 350 | 92.0 | 51 | covered | 151 | `insert`, `brange`, `repeat` | Exact CIWI expression matches the expected insert-over-repeat structure. |
| `insert_repeat2` | 645 | 92.0 | 44 | covered | 562 | `insert`, `brange`, `repeat` | Exact CIWI expression is nested insert over repeated runs; `cumsum` is forbidden here. |
| `insert_repeat3` | 1,210 | 93.0 | 10,677 | covered | 2,101 | `insert`, `brange`, `repeat` | CIWI finds the right edit family, but its compression rate is weaker than Python's threshold because the alternating content is still represented less compactly. |
| `repeat_with_noise` | 501 | 90.0 | 6 | covered | 248 | `insert`, `repeat` | Exact CIWI expression: `[:insert [100] -1 [:repeat 500 [45]]]`. |
| `simply_linear` | 1,000 | 97.0 | 12 | covered | 3,057 | `add`, `mult`, `brange` | Exact CIWI expression: `[:add [:mult [:brange 0 1000] 6] -18]`. |
| `sprinkled` | 10,000 | 75.0 | 6 | performance-gap | 83,556 | `insert`, `repeat` | Full-scale CIWI compresses structurally, but generic exhaustive/bounded comparison is about four orders of magnitude slower than Python. |
| `increasing_runs` | 125,250 | 99.9 | 88 | performance-gap | timeout | `insert`, `repeat` expected | Full-scale CIWI did not finish in the probe timeout. Python handles it quickly by bounded search. |
| `map_negate` | 1,000 | 98.0 | 12 | covered | 2,013 | `mult`, `brange` | Exact CIWI expression: `[:mult [:brange 0 1000] -1]`. |

## Current Interpretation

The covered rows show that CIWI can produce the same basic Alice operator
families, and exact expressions where the selected structure is stable under
CIWI's current DL codec. CIWI thresholds intentionally do not reuse Python's
floating DL thresholds yet because the two implementations use different value
codecs.

The performance-gap rows are not acceptable parity. They point to a concrete
implementation issue: CIWI's generic local reference scoring still does too much
work over large raw child vectors for sparse insert and increasing-run patterns.
The next fix should make bounded local rewrite search propose and score these
structures without running the full exhaustive comparison over large generated
children.
