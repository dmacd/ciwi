# Alice Test Parity Matrix

This file tracks CIWI's current parity against Python WILLIAM's
`william/tests/test_alice.py`. The goal is compression behavior parity, not
routine-by-routine helper parity.

Status as of the current working tree: the straight CIWI Wunderbaum path has
Python-scale core evidence for `simple_repeat`, `insert_repeat`,
`insert_repeat2`, `insert_repeat3`, `repeat_with_noise`, `simply_linear`, and
`sprinkled`, `increasing_runs`, and `map_negate`. The default local rewrite
Alice baseline now lives in `ciwi.alice-legacy`; it still installs no rewrite
operators by default, so it cannot accidentally use local recognizer templates
as parity evidence.

The Python timings below use `GreedyAlice` with `min_rate=0.01`,
`max_dag_dl=35`, `learn=false`, `trees_only=false`, and `use_rust=false`.
Timings are warm in-process medians: dependencies are loaded and runtime/JIT
startup effects are excluded. The Python timing column measures wall-clock
`GreedyAlice.run_task` calls after warmups; Python rates and final DLs come
from `GreedyAlice.last_run_stats`, normalized here to CIWI's fraction rate
convention.

The core CIWI columns use `ciwi.alice.wunderbaum/run-greedy-task` with the
injected Python Alice operator basis, `max_dag_dl=35`, and row-specific
`max_popped` / `max_yields` safety bounds where shown in the status. This
compresses the largest worthy raw leaf one accepted candidate at a time, like
Python `GreedyAlice`. `Core CIWI vector ms` uses CIWI's default vector dense
backend. `Core CIWI DJL ms` installs the opt-in `:ciwi.djl` backend as the
process default and reruns the same core path. Both CIWI timing columns are
warm in-process medians; regular rows use five measured runs after two warmups,
and the full `insert_repeat3` threshold row uses three measured runs after two
warmups.
The recognizer baseline columns are previous CIWI measurements from opt-in
local templates such as range/repeat/insert recognizers. They are retained for
debugging and performance comparison only. They are not Alice parity evidence.

| Python task | Length | Python threshold | Python rate | Python ms | Core CIWI status | Core CIWI rate | Core CIWI vector ms | Core CIWI DJL ms | Core CIWI solution | Recognizer CIWI rate | Recognizer CIWI ms | Recognizer baseline solution | Python solution |
| --- | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | --- | ---: | ---: | --- | --- |
| `simple_repeat` | 1,000 | 0.94 | 0.98056137 | 35.4 | passes greedy core, 3 steps / 3 candidates; matches Python cumsum/insert shape | 0.98056137 | 48.6 | 27.3 | `[:insert [:cumsum [:insert [0] 0 C2x499]] 140 C-50x500]` | 0.99636156 | 87 | `[:repeat 500 [140 -50]]` | `(Array[int] (insert (Array[int] (cumsum (Array[int] (insert Array[int] int Array[int])))) int Array[int]))` |
| `insert_repeat` | 350 | 0.92 | 0.93075788 | 23.3 | passes greedy core, 2 steps / 2 candidates; matches Python cumsum/insert shape | 0.93075788 | 23.5 | 12.7 | `[:insert [:cumsum I0+99x1] 45 C87x250]` | 0.98611733 | 18 | `[:insert [:brange 0 100] 45 [:repeat 250 [87]]]` | `(Array[int] (insert (Array[int] (cumsum Array[int])) int Array[int]))` |
| `insert_repeat2` | 645 | 0.92 | 0.92325943 | 43.7 | passes greedy core, 3 steps / 3 candidates; matches Python shared-DAG concat/insert shape | 0.92325943 | 15.7 | 31.2 | `[:insert [:concat I0+9 I10+34] [:insert I0+9 45 C87x25] C164x610]` | 0.98943455 | 23 | `[:insert [:brange 0 35] [:insert [:brange 0 10] 45 [:repeat 25 [87]]] [:repeat 610 [164]]]` | `((= $ Array[int]) (Array[int] (insert (Array[int] (concat _1 Array[int])) (Array[int] (insert _1 int Array[int])) Array[int])))` |
| `insert_repeat3` | 1,210 | 0.93 | 0.93830537 | 10,677 | passes greedy core, 7 steps / 7 candidates; matches Python nested cumsum/getitem/insert skeleton; default regression covers the hard fourth step | 0.93830537 | 3,834.8 | 7,017.1 | `C_insert_repeat3` | 0.68028790 | 105 | `[:insert [:brange 0 600] [:insert I3 [:insert [:brange 0 100] 45 [:repeat 250 [87]]] [:repeat 250 [62]]] [:repeat 610 [164]]]` | `P_insert_repeat3` |
| `repeat_with_noise` | 501 | 0.90 | 0.96279777 | 5.2 | passes greedy core, 1 step / 1 candidate; matches Python one-step plain insert under Python DL | 0.96279777 | 1.6 | 1.3 | `[:insert [100] -1 C45x500]` | 0.99103288 | 16 | `[:insert [100] -1 [:repeat 500 [45]]]` | `(Array[int] (insert Array[int] int Array[int]))` |
| `simply_linear` | 1,000 | 0.97 | 0.99506286 | 12.0 | passes greedy core, 2 steps / 2 candidates; matches Python cumsum/insert shape under Python DL | 0.99506286 | 6.6 | 6.2 | `[:cumsum [:insert [0] -18 C6x999]]` | 0.99785287 | 122 | `[:add [:mult [:brange 0 1000] 6] -18]` | `(Array[int] (cumsum (Array[int] (insert Array[int] int Array[int]))))` |
| `sprinkled` | 10,000 | 0.75 | 0.79294650 | 6 | passes greedy core, 1 step / 1 candidate; matches Python plain insert shape | 0.79294650 | 20.3 | 12.1 | `[:insert S 1 C0x9900]` | 0.93074107 | 249 | `[:insert S 1 [:repeat 9900 [0]]]` | `(Array[int] (insert Array[int] int Array[int]))` |
| `increasing_runs` | 125,250 | 0.999 | 0.99905472 | 88 | passes greedy core, 3 steps / 3 candidates; matches Python insert/cumsum/cumsum shape | 0.99905472 | 980.3 | 178.3 | `[:insert [:cumsum [:cumsum D_inc]] 64 C123x124750]` | 0.99274754 | 2,705 | `[:insert R 64 [:repeat 124750 [123]]]` | `(Array[int] (insert (Array[int] (cumsum (Array[int] (cumsum Array[int])))) int Array[int]))` |
| `map_negate` | 1,000 | 0.98 | 0.99521131 | 12 | passes greedy core, 2 steps / 2 candidates; matches Python cumsum/insert shape | 0.99521131 | 6.4 | 6.5 | `[:cumsum [:insert [0] 0 C-1x999]]` | 0.99826700 | 66 | `[:mult [:brange 0 1000] -1]` | `(Array[int] (cumsum (Array[int] (insert Array[int] int Array[int]))))` |

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

`I0+9` is `(vec (range 10))`.

`I10+34` is `(vec (range 10 35))`.

`C87x250` is `(vec (repeat 250 87))`.

`C87x25` is `(vec (repeat 25 87))`.

`C164x610` is `(vec (repeat 610 164))`.

`C45x500` is `(vec (repeat 500 45))`.

`C6x999` is `(vec (repeat 999 6))`.

`C0x9900` is `(vec (repeat 9900 0))`.

`C-1x999` is `(vec (repeat 999 -1))`.

`R` is `(mapv #(/ (* % (+ % 3)) 2) (range 500))`, i.e. the exact marker
positions of the `64` separators in Python's `increasing_runs` target.

`D_inc` is `(vec (concat [0 2] (repeat 498 1)))`, whose double cumulative sum
is `R`.

`C123x124750` is `(vec (repeat 124750 123))`.

`P_insert_repeat3` is:

```text
(Array[int] (cumsum (Array[int] (getitem Array[int] (Array[int] (cumsum (Array[int] (insert (Array[int] (cumsum (Array[int] (insert Array[int] int Array[int])))) (Array[int] (getitem Array[int] (Array[int] (insert (Array[int] (cumsum (Array[int] (insert Array[int] Array[int] Array[int])))) Array[int] Array[int])))) Array[int]))))))))
```

`C_insert_repeat3` has the same operator skeleton as `P_insert_repeat3` under
CIWI's native selected-expression syntax. The exact expanded Clojure expression
contains several Python-scale raw vectors, so the table names the structural
solution and keeps the full raw-vector definitions in the task fixture rather
than duplicating them inline. The focused regression in
`ciwi.alice.wunderbaum-test` asserts the formerly missing fourth-step
`[:insert [:cumsum [:insert ...]] ...]` candidate on the Python-scale target.

## Current Interpretation

The active parity claim is limited to the Alice operator basis and the
Python-scale task definitions under the Python WILLIAM value DL model.
`ciwi.alice-legacy-test` asserts that `ciwi.alice-legacy` performs no
recognizer rewrites by default, which prevents accidental shortcut-based parity.
`ciwi.alice.wunderbaum-test` now carries Python-scale core Wunderbaum rows and
a focused `insert_repeat3` regression for the hard nested fourth step. The
full `insert_repeat3` seven-step threshold run is tracked here because it is
an important regression case for nested delayed materialization.

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
`ciwi.alice.wunderbaum`. That path contains the first task-level
frontier/materialization slice with injected registries, operator/count
declarations, conditioned-spec indexing, delayed graph building, operator
inversion, and MDL-selected yielded graphs. It is not wired into the default
`ciwi.alice` local rewrite harness.

Current root-cause notes from the core path:

- Delayed graph materialization must skip non-executable operator calls and
  inverses, matching Python's `exec_errors` behavior. Without this, impossible
  probes such as unconditioned `getitem` on a scalar can abort enumeration.
- Parallel Alice/Wunderbaum is now opt-in through `:parallelism` or
  `:num-workers`. The current evidence is a small Alice compression smoke test
  plus the standalone Python Wunderbaum bounded-drain shape; it is not yet a
  timing claim for all Python-scale `test_single_task_parallel` rows.
- Numeric inverse shape mismatches must yield no inverse. Returning `nil`
  produced bogus zero-DL children such as `[:add [100] nil]`.
- Python-compatible value DL is necessary parity infrastructure. With the old
  prototype codec, `repeat_with_noise` had to discover an explicit nested
  `repeat`; with Python's Gaussian array DL, the same plain raw-array `insert`
  graph reaches Python's 0.96279777 compression rate.
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
- Each greedy compression step must initialize current graph leaves other than
  the focused leaf as zero-cost free values. This is how Python reuses the
  inner `[0..9]` index leaf in `insert_repeat2`, producing the shared `_1`
  concat/insert DAG rather than independently rediscovering the outer index
  vector with `cumsum`.
- Alice passes the `0.01` step threshold down to Wunderbaum as
  `threshold_dl`. CIWI's iterator now mirrors that by continuing frontier
  expansion internally but yielding only the first graph below the threshold.
  This keeps the yielded candidate counts aligned with Python's `graph_nums`
  shape.
- CIWI value DL caching and deferred selected-expression realization remove
  repeated scoring work from candidate streams. Remaining runtime gaps should
  be investigated as implementation/data-structure performance issues, not
  solved by recognizer shortcuts.
- CIWI's current timing table uses warm in-process medians and should not be
  compared to cold JVM startup time. Several earlier CIWI timings were stale
  after the DL and de-duplication work; the table now reflects a fresh broad
  warm pass over the Python-scale rows.
- Runtime profiling found several such implementation issues. `insert_repeat3`
  was slowed by repeated attachment reachability walks during graph expansion;
  CIWI now computes a per-expansion attachment context. `increasing_runs` was
  slowed by repeated large-vector spec inference, repeated stable value
  comparison, and an O(n * unique) unconditioned `getitem` inverse. CIWI now
  carries inferred specs on materialized `Value` records, buckets
  delayed-builder duplicate checks by value-content fingerprint with exact
  comparison inside matching buckets, and computes the `getitem` inverse index
  map in one pass. The 1D homogeneous-vector DL path also avoids double array
  analysis, avoids flattening already-flat vectors, and computes Gaussian
  statistics with direct passes over the vector. Large vector operator probes
  now avoid lazy sequence pipelines for elementwise work and concat, while
  small vectors stay on the simpler `mapv`/sequence path.
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
- `insert_repeat3` required preserving Python's implicit root-section order in
  CIWI's explicit graph representation. Focused target roots must stay ahead of
  dummy/free roots for node tuple ordering and attachment validation. Focused
  steps must also score only the primary target root, not the whole temporary
  graph including free anchors.
- Python's delayed DAG builder skips inverse-generated values already present
  in memory and returns only the first unseen materialization for each delayed
  attachment. CIWI now mirrors that behavior and de-duplicates materializations
  by whole root-set structure.
- `sprinkled` exposed a free-value accounting mismatch. Python treats
  synthetic default free values such as `1` and `1.5` as normal permeable
  values, not dummy values; if such a value is selected in the compression, its
  DL is charged. Existing task-tree leaves remain zero-DL anchors in CIWI's
  tree summary so shared leaves are not double-counted when a local replacement
  is spliced back.
- `map_negate` did not require a map or multiplication shortcut. Under the
  Python Alice basis it reaches the same cumsum/insert compression shape as
  Python WILLIAM.
- `increasing_runs` reaches the Python insert/cumsum/cumsum shape at the same
  compression rate. Delayed-builder de-duplication now preserves exact raw
  value data in result keys while using hash-first ordering for commutative
  child structural keys, avoiding unconditional stringification of large
  vector-containing keys. CIWI now pre-coerces task data once per task, avoids
  dense-to-native flattening for DJL concat dtype selection, caches repeated
  delayed-builder inverses across declaration/spec variants, and uses an
  integer-specific precision/Elias DL path. The DJL backend now also provides
  dense description-length summaries, a backend-level insert-frequency
  partition, primitive-buffer metadata for arrays created during partitioning,
  and a fast deterministic content hash. Alice's greedy target tree carries
  dense data internally and renders plain Clojure expressions only for public
  `:selected` output. Focused instrumentation shows `from-flat` is no longer a
  dominant cost for `increasing_runs`; the remaining gap is now mostly inverse
  execution plus generic MDL/cache/scoring overhead over candidate graphs.
  That remains an implementation performance target rather than a reason to
  add recognizer shortcuts.
- All Python `test_alice.py` sequence rows now have core CIWI compression
  behavior evidence. The remaining `insert_repeat3` runtime issue is not a DL
  or dense numeric gap: cumulative step timing shows steps 1-3 are cheap, step
  4 costs about 1.1 s, and step 6 is the main cliff. A six-step structural
  profile processed 7,741 frontier items, expanded 2,651 materialized graphs,
  and ran about 3.3 million attachment-validity checks. Next work should
  either optimize this structural frontier/materialization path or broaden
  parity beyond these sequence tasks.

Project sequencing and next implementation steps live in `PLAN.md`.
