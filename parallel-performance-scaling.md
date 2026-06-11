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

Rows marked `false` in the threshold column did not reach the task threshold.
Their timing is time to the implementation's current stop condition, not
time-to-solution.

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
- Rows below the task threshold are not solution timings. They are still useful
  because they show how worker-local ordering changes search outcomes under the
  same threshold.

## Follow-Up

- Rerun the solved rows with `--runs 3` or `--runs 5` and report medians once
  the next parallel implementation change lands.
- Add a globally coordinated threshold-stop path or shared frontier before
  treating 4/8-worker results as expected wins.
- Keep `reg_only_y` in the suite as overhead calibration; do not use it as
  evidence that parallel search improves useful work.
- Revisit `increasing_runs` after the planned dense primitive backend cleanup,
  because the current large-vector special cases are transitional.
