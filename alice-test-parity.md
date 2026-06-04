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
they expose remaining performance gaps. Earlier CIWI probes timed out on
`increasing_runs` and took about 84s on `sprinkled`; removing unbounded primitive
`concat` split enumeration made all sequence rows part of the default full-scale
CIWI parity test.

Python solutions are the final root `to_sexpr()` strings returned by Python
WILLIAM. CIWI solutions are Clojure-native selected expressions. For long index
vectors, exact symbolic index definitions appear below the table.

| Python task | Length | Python threshold | Python rate | Python serial ms | CIWI status | CIWI rate | CIWI ms | CIWI solution | Python solution |
| --- | ---: | ---: | ---: | ---: | --- | ---: | ---: | --- | --- |
| `simple_repeat` | 1,000 | 94.0 | 98.056137 | 1,061 | covered | 99.636156 | 78 | `[:repeat 500 [140 -50]]` | `(Array[int] (insert (Array[int] (cumsum (Array[int] (insert Array[int] int Array[int])))) int Array[int]))` |
| `insert_repeat` | 350 | 92.0 | 93.075788 | 51 | covered | 98.611733 | 12 | `[:insert [:brange 0 100] 45 [:repeat 250 [87]]]` | `(Array[int] (insert (Array[int] (cumsum Array[int])) int Array[int]))` |
| `insert_repeat2` | 645 | 92.0 | 92.325943 | 44 | covered | 98.943455 | 17 | `[:insert [:brange 0 35] [:insert [:brange 0 10] 45 [:repeat 25 [87]]] [:repeat 610 [164]]]` | `((= $ Array[int]) (Array[int] (insert (Array[int] (concat _1 Array[int])) (Array[int] (insert _1 int Array[int])) Array[int])))` |
| `insert_repeat3` | 1,210 | 93.0 | 93.830537 | 10,677 | compression-gap | 68.028790 | 57 | `[:insert [:brange 0 600] [:insert I3 [:insert [:brange 0 100] 45 [:repeat 250 [87]]] [:repeat 250 [62]]] [:repeat 610 [164]]]` | `P_insert_repeat3` |
| `repeat_with_noise` | 501 | 90.0 | 96.279777 | 6 | covered | 99.103288 | 7 | `[:insert [100] -1 [:repeat 500 [45]]]` | `(Array[int] (insert Array[int] int Array[int]))` |
| `simply_linear` | 1,000 | 97.0 | 99.506286 | 12 | covered, perf-watch | 99.785287 | 90 | `[:add [:mult [:brange 0 1000] 6] -18]` | `(Array[int] (cumsum (Array[int] (insert Array[int] int Array[int]))))` |
| `sprinkled` | 10,000 | 75.0 | 79.294650 | 6 | covered, perf-watch | 93.074107 | 62 | `[:insert S 1 [:repeat 9900 [0]]]` | `(Array[int] (insert Array[int] int Array[int]))` |
| `increasing_runs` | 125,250 | 99.9 | 99.905472 | 88 | compression-gap, perf-watch | 99.274754 | 514 | `[:insert R 64 [:repeat 124750 [123]]]` | `(Array[int] (insert (Array[int] (cumsum (Array[int] (cumsum Array[int])))) int Array[int]))` |
| `map_negate` | 1,000 | 98.0 | 99.521131 | 12 | covered, perf-watch | 99.826700 | 37 | `[:mult [:brange 0 1000] -1]` | `(Array[int] (cumsum (Array[int] (insert Array[int] int Array[int]))))` |

## Exact Long Solutions

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

The covered rows show that CIWI can produce the same basic Alice operator
families, and exact expressions where the selected structure is stable under
CIWI's current DL codec.

The remaining parity gaps are now more specific:

- `insert_repeat3`: CIWI is faster but compresses much less. Its local insert
  solution leaves a large explicit index vector where Python finds a deeper
  `cumsum`/`getitem` structure.
- `increasing_runs`: CIWI now runs quickly enough for default tests, but it is
  still slower and less compressed than Python because the marker index vector
  is raw instead of a double-`cumsum` subgraph.
- `sprinkled`, `simply_linear`, and `map_negate`: CIWI is still slower than
  Python on this measurement, but the largest prior slowdown was fixed by
  removing unbounded primitive `concat` split enumeration.
