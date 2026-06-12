# Parallel Performance Scaling

This document records the first CIWI-vs-Python WILLIAM parallel scaling pass.
The goal is diagnostic: identify where parallel search helps, where overhead
dominates, and where worker-local search changes the first accepted candidate.

## Methodology

Date: 2026-06-11.

Implementations were run separately, not concurrently. Timings are warm-start
diagnostics: dependencies and runtimes are loaded, one warmup run is discarded,
and one measured warm run is reported for each point. This is not a statistical
benchmark; rerun with `--runs 3` or higher before using the numbers as stable
performance claims.

CIWI command:

```bash
./bin/clojure -M:dev -m ciwi.bench.parallel-scaling \
  --tasks insert_repeat3,increasing_runs,reg_only_y \
  --scales small,medium,large \
  --workers 1,2,4,8 \
  --warmups 1 \
  --runs 1
```

Python command:

```bash
../william/ve/bin/python scripts/python_parallel_scaling.py \
  --tasks insert_repeat3,increasing_runs,reg_only_y \
  --scales small,medium,large \
  --workers 1,2,4,8 \
  --warmups 1 \
  --runs 1
```

Python was run through WILLIAM's virtualenv because the system Python does not
have WILLIAM's dependency set installed. The Python script sets
`NUMBA_CACHE_DIR=/tmp/william-numba-cache` if the caller has not already set it.

Worker count `1` means serial search: Python `num_workers=None`, CIWI without
`:num-workers`. Worker counts `2`, `4`, and `8` use Python multiprocessing and
CIWI's scoped JVM fixed-thread executor.

Rows marked `false` in the threshold column did not reach the task-level
compression target. This does not mean the run crashed. It means Alice accepted
whatever local compression steps it found above the per-step minimum, then
stopped because the task threshold was still unmet and no remaining leaf was
worth searching under the current `worthy-dl`/frontier limits. Their timing is
time to that stop condition, not time-to-solution. They are useful diagnostic
rows, but they must not be compared as successful time-to-solution results.

## Task Scales

`insert_repeat3` uses the Python `test_alice.py` threshold `0.93`.

| Scale | Target construction | Length |
| --- | --- | ---: |
| small | `25 * [45] + 62 * [87, 62] + 152 * [164]` | 301 |
| medium | `50 * [45] + 125 * [87, 62] + 305 * [164]` | 605 |
| large | `100 * [45] + 250 * [87, 62] + 610 * [164]` | 1,210 |

`increasing_runs` uses the Python `test_alice.py` threshold `0.999`.

| Scale | Runs | Length |
| --- | ---: | ---: |
| small | 150 | 11,325 |
| medium | 300 | 45,150 |
| large | 500 | 125,250 |

`reg_only_y` uses the Python `test_alice.py` threshold `0.98`.

| Scale | Target | Length |
| --- | --- | ---: |
| small | `3 * arange(1000) - 5` | 1,000 |
| medium | `3 * arange(5000) - 5` | 5,000 |
| large | `3 * arange(10000) - 5` | 10,000 |

## Results

Each cell is `milliseconds / threshold?`.

### insert_repeat3

| Impl | Scale | w1 | w2 | w4 | w8 |
| --- | --- | ---: | ---: | ---: | ---: |
| CIWI | small | 103.243 / false | 56.177 / false | 1114.162 / false | 1112.975 / false |
| Python | small | 2070.042 / false | 1947.025 / false | 5272.753 / false | 256.584 / false |
| CIWI | medium | 110.337 / true | 530.757 / false | 492.916 / false | 612.426 / false |
| Python | medium | 2482.296 / true | 7484.576 / false | 2655.053 / false | 906.054 / false |
| CIWI | large | 3661.882 / true | 439.539 / true | 547.836 / true | 654.619 / true |
| Python | large | 10345.756 / true | 1747.415 / true | 2062.684 / true | 989.838 / true |

### increasing_runs

| Impl | Scale | w1 | w2 | w4 | w8 |
| --- | --- | ---: | ---: | ---: | ---: |
| CIWI | small | 99.420 / false | 93.100 / false | 95.115 / false | 497.830 / false |
| Python | small | 41.814 / false | 173.950 / false | 1161.038 / false | 1340.773 / false |
| CIWI | medium | 388.997 / true | 290.501 / true | 326.807 / true | 3052.820 / false |
| Python | medium | 65.925 / true | 332.403 / true | 10089.463 / false | 10592.432 / false |
| CIWI | large | 993.977 / true | 755.828 / true | 829.441 / true | 6071.189 / true |
| Python | large | 85.483 / true | 398.031 / true | 10759.256 / true | 12162.848 / true |

### reg_only_y

| Impl | Scale | w1 | w2 | w4 | w8 |
| --- | --- | ---: | ---: | ---: | ---: |
| CIWI | small | 8.039 / true | 8.427 / true | 9.143 / true | 18.114 / true |
| Python | small | 13.032 / true | 183.511 / true | 269.868 / true | 495.853 / true |
| CIWI | medium | 29.236 / true | 32.782 / true | 40.883 / true | 78.548 / true |
| Python | medium | 20.725 / true | 196.394 / true | 278.293 / true | 478.080 / true |
| CIWI | large | 57.442 / true | 111.820 / true | 64.029 / true | 120.279 / true |
| Python | large | 46.477 / true | 233.300 / true | 304.028 / true | 501.866 / true |

## Interpretation

- Parallel speedup is not monotonic because both implementations use
  worker-local search frontiers rather than a globally ordered parallel
  best-first queue. Different worker counts can accept different first
  above-threshold candidates, or fail threshold at smaller scales.
- `insert_repeat3` large is the clearest useful parallel case. CIWI improves
  from `3661.882 ms` serial to `439.539 ms` at 2 workers. Python improves from
  `10345.756 ms` serial to `989.838 ms` at 8 workers.
- `reg_only_y` has too little search work to amortize parallel overhead.
  Serial is best or close to best in both implementations; Python's
  multiprocessing overhead is especially visible.
- `increasing_runs` exposes a known CIWI/Python hot path around large-array
  scoring and search-order sensitivity. CIWI is slower than Python serially on
  the large row, but moderate 2-worker parallelism helps. Higher worker counts
  can push both implementations into longer multi-step paths.
- Rows below the task threshold are not solution timings and do not mean the
  solution is absent from the search space. They mean the greedy run accepted
  one or more local candidates above the per-step threshold, then stopped
  before the aggregate task compression rate reached the task threshold.
  Partitioned parallel runs are especially order-sensitive because each worker
  owns a local frontier and the first above-step-threshold candidate can differ
  by worker count and scheduler timing.
- A focused medium `insert_repeat3` trace showed this directly. Serial and
  coordinated global-best-first search reached `0.962651994` via the
  insert/cumsum/getitem decomposition. Partitioned `w2` and `w4` alternated
  between successful rates (`0.962651994` or `0.948612960`) and the failing
  `0.921616734` path. The failing path starts with a locally compressive
  `cumsum` rewrite, continues compressing its nested leaves, then stops with
  `:leaf-below-worthy`: the remaining total DL is still above the task target,
  but no individual remaining leaf is large enough for Alice's default
  `worthy-dl` gate.

## Coordinated Global Queue Prototype

CIWI also has an experimental coordinated strategy that does not replace the
Python-parity partitioned path. It is selected with:

```bash
./bin/clojure -M:dev -m ciwi.bench.parallel-scaling \
  --tasks insert_repeat3,increasing_runs,reg_only_y \
  --scales large \
  --workers 2,4,8 \
  --strategy global-best-first \
  --warmups 1 \
  --runs 1
```

Additional medium rows were collected for the cases where the partitioned path
had surprising threshold failures:

```bash
./bin/clojure -M:dev -m ciwi.bench.parallel-scaling \
  --tasks insert_repeat3,increasing_runs \
  --scales medium \
  --workers 2,4,8 \
  --strategy global-best-first \
  --warmups 1 \
  --runs 1
```

Each cell is again `milliseconds / threshold?`.

### CIWI Global Best-First

| Task | Scale | w2 | w4 | w8 |
| --- | --- | ---: | ---: | ---: |
| `insert_repeat3` | medium | 130.237 / true | 91.144 / true | 1442.798 / true |
| `insert_repeat3` | large | 2928.537 / true | 3054.152 / true | 1772.129 / true |
| `increasing_runs` | medium | 465.614 / true | 456.743 / true | 1295.652 / false |
| `increasing_runs` | large | 1179.023 / true | 1272.274 / true | 11240.027 / true |
| `reg_only_y` | large | 62.963 / true | 68.028 / true | 80.761 / true |

The coordinated queue improves outcome stability on medium `insert_repeat3`:
the single-run partitioned table above happened to miss the threshold at
2/4/8 workers, while the global queue reached it. Follow-up traces showed that
partitioned `w2` and `w4` can also succeed; the important point is that their
first accepted greedy path is not stable. It does not automatically improve
throughput. On large
`insert_repeat3`, the partitioned 2-worker result was faster (`439.539 ms`)
than global 2-worker (`2928.537 ms`) because the partitioned strategy happened
to find a good above-threshold path quickly. On `increasing_runs`, the global
queue keeps 2/4 workers on the stable 3- or 4-step path, while 8 workers can
still create a longer path. The right next optimization is therefore not just
"use a shared queue"; it is coordinated stopping and better control over how
many workers are allowed to race past the current best frontier level.

## Ordered Global Queue Update

The coordinated strategy now uses ordered commit rather than first-worker-wins
emission. Workers may speculate on later frontier items, but a threshold
candidate is emitted only after earlier-ranked queued or active frontier work
has cleared. The scheduler also uses concurrent delayed-result admission and
opt-in stats:

```bash
./bin/clojure -M:dev -m ciwi.bench.parallel-scaling \
  --tasks insert_repeat3,increasing_runs,reg_only_y \
  --scales small,medium,large \
  --workers 1,2,4,8 \
  --strategy global-best-first \
  --warmups 1 \
  --runs 3 \
  --stats true
```

Date: 2026-06-12. Each cell is `median milliseconds / threshold?`.

### Ordered Global insert_repeat3

| Scale | w1 | w2 | w4 | w8 |
| --- | ---: | ---: | ---: | ---: |
| small | 104.937 / false | 73.008 / false | 50.425 / false | 55.628 / false |
| medium | 114.980 / true | 106.309 / true | 87.064 / true | 89.577 / true |
| large | 3711.626 / true | 3681.209 / true | 2960.311 / true | 2819.673 / true |

### Ordered Global increasing_runs

| Scale | w1 | w2 | w4 | w8 |
| --- | ---: | ---: | ---: | ---: |
| small | 101.423 / false | 92.604 / false | 99.302 / false | 279.148 / false |
| medium | 389.542 / true | 346.357 / true | 434.797 / true | 1521.719 / true |
| large | 1019.790 / true | 823.582 / true | 990.535 / true | 3407.078 / true |

### Ordered Global reg_only_y

| Scale | w1 | w2 | w4 | w8 |
| --- | ---: | ---: | ---: | ---: |
| small | 13.711 / true | 8.549 / true | 9.072 / true | 18.683 / true |
| medium | 29.350 / true | 27.749 / true | 55.693 / true | 87.844 / true |
| large | 59.455 / true | 60.060 / true | 58.882 / true | 180.484 / true |

Ordered global search fixes the important stability problem: every medium and
large row that should reach the task threshold does so, and `insert_repeat3`
medium/large converge to the stable ordered path. It does not yet scale well.
The best rows are modest: `insert_repeat3` large improves only from
`3711.626 ms` to `2819.673 ms` at 8 workers, and `increasing_runs` large
improves from `1019.790 ms` to `823.582 ms` at 2 workers before getting worse.
`reg_only_y` remains overhead calibration; there is too little search work for
threads to amortize.

The stats columns explain the gap. With default `:frontier-batch-size 4`,
`max_active_frontier_items` rises to `8`, `16`, and `32` at `w2`, `w4`, and
`w8`. That means the ordered scheduler is allowing up to four speculative
frontier items per worker. On `increasing_runs` large, summed
`materialization_ms` grows from `1177.987` at `w2` to `3171.197` at `w4` and
`8796.617` at `w8`; `commit_wait_ms` also grows from `0.037` to `71.229` and
`257.672`. These are summed worker-time counters, so they can exceed wall time,
but the trend shows real wasted speculative work. The next scaling step should
therefore focus on throttling speculation and cancellation, not on adding more
workers.

## Per-Step Ordered Global Diagnostics

Per-step rows can be collected with:

```bash
./bin/clojure -M:dev -m ciwi.bench.parallel-scaling \
  --tasks insert_repeat3,increasing_runs,reg_only_y \
  --scales large \
  --workers 2,4,8 \
  --strategy global-best-first \
  --warmups 1 \
  --runs 1 \
  --report steps
```

Representative large-scale rows show that every accepted compression step
consumed exactly one yielded candidate. The useful parallel work is therefore
not "compare many yielded candidates"; it is proving enough earlier-ranked
frontier work that the first threshold candidate can commit.

| Task | Workers | Dominant Step Pattern |
| --- | ---: | --- |
| `insert_repeat3` large | 2 | Step 3 took `526 ms` after `2,972` pops; step 5 took `3,032 ms` after `4,200` pops and `128,185` enqueues. |
| `insert_repeat3` large | 4 | Step 5 improved to `2,392 ms`, but summed expansion work rose to `6,479 ms`. |
| `insert_repeat3` large | 8 | Step 5 improved only to `2,225 ms`, while summed expansion work rose to `11,529 ms`. |
| `increasing_runs` large | 2 | Three narrow steps took `130/399/265 ms` with only `12/48/48` pops. |
| `increasing_runs` large | 8 | The same three steps worsened to `1088/1862/1871 ms`; the scheduler popped more work, but not useful work. |
| `reg_only_y` large | 2 | Two tiny steps took `41/11 ms`; there is not enough search work to amortize threads. |
| `reg_only_y` large | 8 | The same steps took `83/78 ms`; this is scheduler overhead and speculation. |

`insert_repeat3` is the only one of these tasks with plausibly parallel slow
compression steps. Even there, the speedup is bounded because the slow steps
are ordered-prefix proof problems: workers must clear thousands of
earlier-ranked frontier items before the first threshold candidate can be
accepted. `increasing_runs` and `reg_only_y` mostly have narrow early winners,
so extra workers create redundant materialization and expansion.

The first speculation knob, `:frontier-batch-size`, is exposed through:

```bash
./bin/clojure -M:dev -m ciwi.bench.parallel-scaling \
  --tasks insert_repeat3 \
  --scales large \
  --workers 2,4,8 \
  --strategy global-best-first \
  --warmups 1 \
  --runs 1 \
  --stats true \
  --frontier-batch-size 1
```

For `insert_repeat3` large this produced `3721/2902/2728 ms` at `w2/w4/w8`.
It caps `max_active_frontier_items` to `2/4/8`, but it does not materially
reduce the total frontier proof: the run still popped about `7,500` items and
enqueued about `156k` descendants. This means batch throttling is useful but
insufficient. The bigger waste is active work that keeps materializing or
expanding after a pending threshold candidate has already made later-ranked
work unlikely to matter.

The next implementation work should reduce wasted speculation in four ways:

- Candidate-sensitive dispatch: keep small batches in threshold mode until the
  scheduler has evidence that no candidate is near. This prevents simple tasks
  from turning one early winner into many active frontier items.
- Active-work cancellation: once a pending candidate exists, active workers
  whose item rank is not earlier than that candidate should stop at
  materialization, scoring, and expansion boundaries.
- Lazy descendant expansion: do not eagerly expand descendants of a
  non-emitting materialized graph when a pending candidate may commit before
  those descendants can be relevant. This directly targets the huge expansion
  counters on `insert_repeat3` step 5.
- Adaptive useful-width control: use per-step stats such as popped/emitted
  ratio, commit wait, and duplicate rate to reduce worker width on narrow
  steps and open it only on steps like `insert_repeat3` step 5 where thousands
  of earlier-ranked items really must be processed.

## Follow-Up

- Continue tuning the ordered global queue before treating 4/8-worker results
  as expected wins. It now has ordered commit and concurrent result admission,
  but not hard cancellation inside already-running materializations.
- Add a thresholded-search scheduling mode with `:frontier-batch-size 1` or an
  adaptive batch policy so workers do not pop far past the earliest pending
  threshold candidate. Re-run the same matrix after that change.
- Keep `reg_only_y` in the suite as overhead calibration; do not use it as
  evidence that parallel search improves useful work.
- Revisit `increasing_runs` after the planned dense primitive backend cleanup,
  because the current large-vector special cases are transitional.
