# Alice Test Parity Matrix

This file tracks CIWI's current parity against Python WILLIAM's
`william/tests/test_alice.py`. The goal is compression behavior parity, not
routine-by-routine helper parity.

Status as of the current working tree: the straight CIWI Wunderbaum path has
Python-scale core evidence for `simple_repeat`, `insert_repeat`,
`repeat_with_noise`, and `simply_linear`. The default local rewrite Alice
harness still installs no rewrite operators by default, so it cannot
accidentally use local recognizer templates as parity evidence.

The Python timings below use `GreedyAlice` with `min_rate=0.01`,
`max_dag_dl=35`, `learn=false`, `trees_only=false`, and `use_rust=false`.
Timings are warm in-process medians: dependencies are loaded and runtime/JIT
startup effects are excluded. The Python timing column measures wall-clock
`GreedyAlice.run_task` calls after warmups; Python rates and final DLs come
from `GreedyAlice.last_run_stats`.

The core CIWI columns use `ciwi.alice-wunderbaum/run-greedy-task` with the
injected Python Alice operator basis, `max_dag_dl=35`, and row-specific
`max_popped` / `max_yields` safety bounds where shown in the status. This
compresses the largest worthy raw leaf one accepted candidate at a time, like
Python `GreedyAlice`.
The recognizer baseline columns are previous CIWI measurements from opt-in
local templates such as range/repeat/insert recognizers. They are retained for
debugging and performance comparison only. They are not Alice parity evidence.

| Python task | Length | Python threshold | Python rate | Python ms | Core CIWI status | Core CIWI rate | Core CIWI ms | Core CIWI solution | Recognizer CIWI rate | Recognizer CIWI ms | Recognizer baseline solution | Python solution |
| --- | ---: | ---: | ---: | ---: | --- | ---: | ---: | --- | ---: | ---: | --- | --- |
| `simple_repeat` | 1,000 | 94.0 | 98.056137 | 36.3 | passes greedy core, 3 steps / 3 candidates; matches Python cumsum/insert shape | 98.056137 | 39.9 | `[:insert [:cumsum [:insert [0] 0 C2x499]] 140 C-50x500]` | 99.636156 | 87 | `[:repeat 500 [140 -50]]` | `(Array[int] (insert (Array[int] (cumsum (Array[int] (insert Array[int] int Array[int])))) int Array[int]))` |
| `insert_repeat` | 350 | 92.0 | 93.075788 | 24.0 | passes greedy core, 2 steps / 2 candidates; matches Python cumsum/insert shape | 93.075788 | 5.9 | `[:insert [:cumsum I0+99x1] 45 C87x250]` | 98.611733 | 18 | `[:insert [:brange 0 100] 45 [:repeat 250 [87]]]` | `(Array[int] (insert (Array[int] (cumsum Array[int])) int Array[int]))` |
| `insert_repeat2` | 645 | 92.0 | 92.325943 | 44 | pending core enum | 0.0 | - | - | 98.943455 | 23 | `[:insert [:brange 0 35] [:insert [:brange 0 10] 45 [:repeat 25 [87]]] [:repeat 610 [164]]]` | `((= $ Array[int]) (Array[int] (insert (Array[int] (concat _1 Array[int])) (Array[int] (insert _1 int Array[int])) Array[int])))` |
| `insert_repeat3` | 1,210 | 93.0 | 93.830537 | 10,677 | pending core enum | 0.0 | - | - | 68.028790 | 105 | `[:insert [:brange 0 600] [:insert I3 [:insert [:brange 0 100] 45 [:repeat 250 [87]]] [:repeat 250 [62]]] [:repeat 610 [164]]]` | `P_insert_repeat3` |
| `repeat_with_noise` | 501 | 90.0 | 96.279777 | 5.4 | passes greedy core, 1 step / 1 candidate; matches Python one-step plain insert under Python DL | 96.279777 | 4.2 | `[:insert [100] -1 C45x500]` | 99.103288 | 16 | `[:insert [100] -1 [:repeat 500 [45]]]` | `(Array[int] (insert Array[int] int Array[int]))` |
| `simply_linear` | 1,000 | 97.0 | 99.506286 | 12.4 | passes greedy core, 2 steps / 2 candidates; matches Python cumsum/insert shape under Python DL | 99.506286 | 23.7 | `[:cumsum [:insert [0] -18 C6x999]]` | 99.785287 | 122 | `[:add [:mult [:brange 0 1000] 6] -18]` | `(Array[int] (cumsum (Array[int] (insert Array[int] int Array[int]))))` |
| `sprinkled` | 10,000 | 75.0 | 79.294650 | 6 | pending core enum | 0.0 | - | - | 93.074107 | 249 | `[:insert S 1 [:repeat 9900 [0]]]` | `(Array[int] (insert Array[int] int Array[int]))` |
| `increasing_runs` | 125,250 | 99.9 | 99.905472 | 88 | pending core enum | 0.0 | - | - | 99.274754 | 2,705 | `[:insert R 64 [:repeat 124750 [123]]]` | `(Array[int] (insert (Array[int] (cumsum (Array[int] (cumsum Array[int])))) int Array[int]))` |
| `map_negate` | 1,000 | 98.0 | 99.521131 | 12 | pending core enum | 0.0 | - | - | 99.826700 | 66 | `[:mult [:brange 0 1000] -1]` | `(Array[int] (cumsum (Array[int] (insert Array[int] int Array[int]))))` |

## Exact Long Values

`S` is the exact NumPy `default_rng(42).choice(10000, 100, replace=false)`
index set used by Python `test_alice.py`, sorted for stable display:

```clojure
[436 634 675 761 851 883 915 933 971 1270 1295 1397 1536 1604 1642
 1811 1889 1937 1996 2256 2264 2394 2750 2882 3119 3247 3294 3525
 3621 3652 3679 3692 3872 3994 4094 4289 4346 4348 4363 4403 4433
 4467 4473 4616 4645 4681 4745 4955 4963 5089 5217 5415 5442 5509
 5633 6278 6288 6366 6391 6482 6684 6744 6803 6823 6847 6909 6965
 6992 7113 7293 7403 7419 7536 7545 7561 7663 7669 7744 7757 7794
 7933 8041 8168 8224 8313 8314 8329 8411 8505 8528 8821 8884 8976
 9025 9189 9197 9385 9640 9654 9670]
```

`I3` is `(vec (concat (range 101) (range 102 600 2)))`.

`D_linear_second_diff` is `(vec (concat [-18 24] (repeat 998 0)))`.

`C2x499` is `(vec (repeat 499 2))`.

`C-50x500` is `(vec (repeat 500 -50))`.

`I0+99x1` is `(vec (concat [0] (repeat 99 1)))`.

`C87x250` is `(vec (repeat 250 87))`.

`C45x500` is `(vec (repeat 500 45))`.

`C6x999` is `(vec (repeat 999 6))`.

`R` is `(mapv #(/ (* % (+ % 3)) 2) (range 500))`, i.e. the exact marker
positions of the `64` separators in Python's `increasing_runs` target.

`P_insert_repeat3` is:

```text
(Array[int] (cumsum (Array[int] (getitem Array[int] (Array[int] (cumsum (Array[int] (insert (Array[int] (cumsum (Array[int] (insert Array[int] int Array[int])))) (Array[int] (getitem Array[int] (Array[int] (insert (Array[int] (cumsum (Array[int] (insert Array[int] Array[int] Array[int])))) Array[int] Array[int])))) Array[int]))))))))
```

## Current Interpretation

The active parity claim is limited to the Alice operator basis and the
Python-scale task definitions under the Python WILLIAM value DL model.
`ciwi.alice-test` asserts that default local rewrite Alice runs perform no
recognizer rewrites, which prevents accidental shortcut-based parity.
`ciwi.alice-wunderbaum-test` now carries the first Python-scale core
Wunderbaum rows.

Debugging conclusions retained from the recognizer baseline:

- `concat`: CIWI previously spent most of its time proposing unconditioned
  `concat` splits. Python `Concat.conditions` is only `(0,)` and `(1,)`, so
  Python Alice does not invent every output split. Removing CIWI's unconditioned
  primitive `concat` recognizer was an Alice-alignment fix.
- `map :negate`: Python `Map.conditions` requires the callable to already be
  conditioned, so removing the hardcoded unconditioned map-negate recognizer was
  also an Alice-alignment fix.
- Remaining performance/compression gaps in the recognizer baseline should not
  be solved by adding more recognizers. They should be revisited after the core
  operator-DAG enum is in place.

## Current Core Evidence

The active core implementation checkpoint is `ciwi.wunderbaum` plus
`ciwi.alice-wunderbaum`. That path contains the first task-level
frontier/materialization slice with injected registries, operator/count
declarations, conditioned-spec indexing, delayed graph building, operator
inversion, and MDL-selected yielded graphs. It is not wired into the default
`ciwi.alice` local rewrite harness.

Current root-cause notes from the core path:

- Delayed graph materialization must skip non-executable operator calls and
  inverses, matching Python's `exec_errors` behavior. Without this, impossible
  probes such as unconditioned `getitem` on a scalar can abort enumeration.
- Numeric inverse shape mismatches must yield no inverse. Returning `nil`
  produced bogus zero-DL children such as `[:add [100] nil]`.
- Python-compatible value DL is necessary parity infrastructure. With the old
  prototype codec, `repeat_with_noise` had to discover an explicit nested
  `repeat`; with Python's Gaussian array DL, the same plain raw-array `insert`
  graph reaches Python's 96.279777% compression rate.
- Alice/Wunderbaum materialization must preserve Python `TaskDomain` operator
  DL. Python assigns all operator classes in a task domain
  `ceil(jelias(number-of-operator-classes))` bits; for the 13-class Alice basis
  this is 10 bits.
- CIWI now enumerates node tuples lazily in Python-style best-first order
  instead of building and sorting the full tuple product.
- CIWI Alice/Wunderbaum now uses the same greedy outer-loop shape as Python for
  these rows. The old first-threshold stream scan made `simply_linear` consume
  48 candidates in one pass; the greedy path now consumes two accepted steps
  and two yielded candidates total after the Python ordering/spec fixes.
- CIWI value DL caching and deferred selected-expression realization remove
  repeated scoring work from candidate streams. Remaining runtime gaps should
  be investigated as implementation/data-structure performance issues, not
  solved by recognizer shortcuts.
- Python's Wunderbaum operator elements include deterministic tiny additive DL
  jitter from `np.random.default_rng(42).random() * 1e-6`. This is not a
  semantic feature, but it is part of Python's search ordering. Without it,
  CIWI selected `:brange` before `:cumsum` on some tied rows and diverged from
  Python's first accepted compression.
- Delayed materialization must validate generated inverse values against the
  selected declaration's input specs, and forward outputs against the
  declaration output spec. Without this, the cheaper `getitem` boolean-mask
  declaration could accept an integer index vector generated by the generic
  inverse path, which Python rejects.
- The next core rows to debug are `insert_repeat2` and `insert_repeat3`.

Project sequencing and next implementation steps live in `PLAN.md`.
