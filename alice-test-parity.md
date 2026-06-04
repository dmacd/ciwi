# Alice Test Parity Matrix

This file tracks CIWI's current parity against Python WILLIAM's
`william/tests/test_alice.py`. The goal is compression behavior parity, not
routine-by-routine helper parity.

Status as of this checkpoint: core Alice enum parity is pending. CIWI now
installs no rewrite operators by default, so the Alice harness does not use
local recognizer templates unless a caller opts in explicitly. A default Alice
run therefore has 0.0 percent compression today. That is intentional until the
Python Wunderbaum/Alice path has been ported and validated.

The Python timings below were measured with the local checkout's
`_run_single_task_worker(task-name, None)`, which constructs `GreedyAlice` with
`max_dag_dl=35`, `learn=false`, `trees_only=false`, and `use_rust=false`.

The recognizer baseline columns are previous CIWI measurements from opt-in
local templates such as range/repeat/insert recognizers. They are retained for
debugging and performance comparison only. They are not Alice parity evidence.

| Python task | Length | Python threshold | Python rate | Python ms | Core CIWI status | Core CIWI rate | Recognizer CIWI rate | Recognizer CIWI ms | Recognizer baseline solution | Python solution |
| --- | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | --- | --- |
| `simple_repeat` | 1,000 | 94.0 | 98.056137 | 1,061 | pending core enum | 0.0 | 99.636156 | 87 | `[:repeat 500 [140 -50]]` | `(Array[int] (insert (Array[int] (cumsum (Array[int] (insert Array[int] int Array[int])))) int Array[int]))` |
| `insert_repeat` | 350 | 92.0 | 93.075788 | 51 | pending core enum | 0.0 | 98.611733 | 18 | `[:insert [:brange 0 100] 45 [:repeat 250 [87]]]` | `(Array[int] (insert (Array[int] (cumsum Array[int])) int Array[int]))` |
| `insert_repeat2` | 645 | 92.0 | 92.325943 | 44 | pending core enum | 0.0 | 98.943455 | 23 | `[:insert [:brange 0 35] [:insert [:brange 0 10] 45 [:repeat 25 [87]]] [:repeat 610 [164]]]` | `((= $ Array[int]) (Array[int] (insert (Array[int] (concat _1 Array[int])) (Array[int] (insert _1 int Array[int])) Array[int])))` |
| `insert_repeat3` | 1,210 | 93.0 | 93.830537 | 10,677 | pending core enum | 0.0 | 68.028790 | 105 | `[:insert [:brange 0 600] [:insert I3 [:insert [:brange 0 100] 45 [:repeat 250 [87]]] [:repeat 250 [62]]] [:repeat 610 [164]]]` | `P_insert_repeat3` |
| `repeat_with_noise` | 501 | 90.0 | 96.279777 | 6 | pending core enum | 0.0 | 99.103288 | 16 | `[:insert [100] -1 [:repeat 500 [45]]]` | `(Array[int] (insert Array[int] int Array[int]))` |
| `simply_linear` | 1,000 | 97.0 | 99.506286 | 12 | pending core enum | 0.0 | 99.785287 | 122 | `[:add [:mult [:brange 0 1000] 6] -18]` | `(Array[int] (cumsum (Array[int] (insert Array[int] int Array[int]))))` |
| `sprinkled` | 10,000 | 75.0 | 79.294650 | 6 | pending core enum | 0.0 | 93.074107 | 249 | `[:insert S 1 [:repeat 9900 [0]]]` | `(Array[int] (insert Array[int] int Array[int]))` |
| `increasing_runs` | 125,250 | 99.9 | 99.905472 | 88 | pending core enum | 0.0 | 99.274754 | 2,705 | `[:insert R 64 [:repeat 124750 [123]]]` | `(Array[int] (insert (Array[int] (cumsum (Array[int] (cumsum Array[int])))) int Array[int]))` |
| `map_negate` | 1,000 | 98.0 | 99.521131 | 12 | pending core enum | 0.0 | 99.826700 | 66 | `[:mult [:brange 0 1000] -1]` | `(Array[int] (cumsum (Array[int] (insert Array[int] int Array[int]))))` |

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

`R` is `(mapv #(/ (* % (+ % 3)) 2) (range 500))`, i.e. the exact marker
positions of the `64` separators in Python's `increasing_runs` target.

`P_insert_repeat3` is:

```text
(Array[int] (cumsum (Array[int] (getitem Array[int] (Array[int] (cumsum (Array[int] (insert (Array[int] (cumsum (Array[int] (insert Array[int] int Array[int])))) (Array[int] (getitem Array[int] (Array[int] (insert (Array[int] (cumsum (Array[int] (insert Array[int] Array[int] Array[int])))) Array[int] Array[int])))) Array[int]))))))))
```

## Current Interpretation

The active parity claim is limited to the Alice operator basis and the
Python-scale task definitions. The compression proof itself is pending the core
enum operator. `ciwi.alice-test` asserts that default Alice runs perform no
recognizer rewrites, which prevents accidental shortcut-based parity.

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

## Roadmap

The next step is a straight Clojure port of Python Wunderbaum and the Alice path
that uses it. This first version should preserve the Python search architecture:
operator/count inputs, conditioned-spec indexing, node-tuple enumeration,
delayed DAG building, propagation/inversion, bottleneck scoring, and
`test_alice.py` task behavior. The operator registry must be injected by the
caller rather than defaulted or hardcoded.

Only after that port passes the relevant Python parity tests should CIWI adapt
Wunderbaum into the local resource-bounded rewrite model. The bounded version
can then become a `RewriteOperator` that respects focused neighborhoods,
resource budgets, and incremental/local graph edit constraints. Until then,
recognizer-template results remain a baseline only, not parity evidence.
