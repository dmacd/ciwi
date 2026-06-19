# House Demo Status And Plan

Last updated: 2026-06-19.

## Purpose

The house demo is the recognizer-free image compression milestone for CIWI. The
target is Python WILLIAM's legacy noisy 50x50x3 RGB house fixture: red
background, blue triangular roof, green rectangular body, `RandomState(42)`
Gaussian noise at scale 20, rounded to two decimals.

The proof target is not a house recognizer. The intended path is ordinary
Alice/Wunderbaum graph search over low-level rendering primitives:

- point/vector arithmetic,
- `line`,
- `fill`,
- `concat`,
- `dye`,
- `draw`,
- image residual `add`.

Guided runs may use a native solution-prefix predicate to demonstrate that the
primitive basis and renderer can express the solution. Unguided runs must not
use the guide, region proposals, recognizers, or task-specific operators.

## Current State

The fixture and primitive basis live in `ciwi.demos.house`. The fixture matches
the Python construction and seeded noise prefix. The demo-local specs are
`:point`, `:vector`, `:point-list`, `:color`, `:colored-point-list`, and
`:rgb-image`. Colored point lists are dense `[row col r g b]` arrays, which
keeps hashing and memory behavior tractable compared with large vectors of
maps.

Generic graph rendering and movie support are in place. House artifacts can
write Python-style graph frames, image preview/reconstruction frames, optional
MP4s, stats, and an artifact README.

The guided demo is complete as a milestone. It reaches the full 18-step
expression:

1. roof lines, roof concatenation, roof fill, roof dye,
2. body lines, body concatenation, body fill, body dye,
3. colored-list concat,
4. draw base image,
5. residual add to the noisy target.

The default bounded guided run reaches compression rate `0.1136`, writes 18
graph frames and 18 image frames, and demonstrates that the primitive basis can
express a strongly compressive house solution.

The unguided runner now uses the standard Alice `task-search-context`. That
means it shares Alice value wrapping, cache setup, candidate transforms,
parallel dispatch plumbing, optimizer wiring, and injected operator registries.
The custom guided runner remains separate because it owns prefix collection,
the solution-prefix predicate, preferred scheduling, and preview artifacts.

## Mechanisms Added During This Work

- `draw` inverse: from an output image, infer all non-background colored
  points. This exposes the expected degenerate
  `draw(full-target-colored-points)` candidate with a large `[2500 5]` leaf.
- `dye` inverse: when points are known and colored points are uniformly colored,
  infer the RGB color.
- Optional learned color leaves: unguided experiments can add one or more
  permeable RGB color values and remove the baked-in red/green/blue house
  colors.
- Optimizer-backed candidate transform through Alice context: candidates with
  small permeable slots can run graph optimization and then be rescored.
  Candidates with no slots are left unchanged.
- Large dense arrays are encountered as permeable values, but the existing
  optimizer slot limit prevents optimizing `[2500 5]` colored-point arrays and
  `[50 50 3]` images. Actual optimized slots seen in the learned-color probes
  were RGB color arrays of size 3.
- Weak dense cache keys and compact materialized-result de-duplication reduce
  retained heap during long house probes.
- Opt-in serial lazy frontier reduces retained heap and runtime for equal
  candidate counts, while preserving eager best-first ordering. It is
  deliberately disabled for partitioned/global parallel search.

## Probe Results

The unguided baseline is not solved.

Representative results:

| Probe | Result |
|---|---|
| Bounded 10-yield baseline | Only negative-compression line roots. |
| 1000-yield slot probe with learned color | Found 30 optimizer slots, all RGB color arrays; large arrays were skipped. |
| Focused `dye -> draw -> add` color probe | First color-slot candidate at 318 yielded candidates; first useful local color move at 361; still non-compressive. |
| 8 GiB lazy-frontier long probe | Stopped around 104,725 candidates when GC-bound; best remained `[:add :line :dye :draw]`. |
| 16 GiB lazy-frontier long probe | Reached 213,209 candidates, then throughput collapsed near heap cap; best unchanged. |
| 30-minute 16 GiB learned-color run | Timed out at 7,575 candidates. 374 optimizer-eligible, 266 slotted, 138 locally improved. Best graph remained `[:add :line :dye :draw]` with compression rate `-0.000981`. |

The 30-minute learned-color run showed that color fitting works locally but is
too expensive when applied broadly. It spent substantial time optimizing
low-depth one-line renderings. The best local optimizer delta was about `50.8`
bits on an `[:add :add :line :dye :draw]` variant, but that did not improve
the overall best candidate.

## DL Findings

CIWI's raw image description lengths match Python WILLIAM for the legacy house
fixture and related diagnostics.

Under the Python-compatible channel-wise Gaussian array codec:

| Value | Gaussian DL |
|---|---:|
| noisy red field | `97,818.280` |
| noisy house target | `110,668.334` |
| full true-house residual/noise | `97,787.868` |

Residual savings before paying expression cost:

| Predicted structure removed | DL saved |
|---|---:|
| roof only | `2,499.7` bits |
| body only | `6,492.7` bits |
| full house | `12,880.5` bits |

The complete expression is compressive in principle: current low-level
expression costs are small compared with the residual savings. The mismatch is
not CIWI-vs-Python DL parity. The issue is that the Gaussian codec only sees
per-channel marginal variance, not spatial arrangement, so partial visual
structure is rewarded much less strongly than human intuition suggests.

Changing this codec would no longer be Python parity evidence. Any
spatial/image-code experiment should be treated as a separate research branch
or explicitly documented non-parity variant.

## Main Blocker

The current blocker is topology ordering, not color scoring.

Wunderbaum's frontier rank is effectively:

```clojure
[build-dl order]
```

In the house prior, `line` costs `1.0` while `concat`, `fill`, `dye`, `draw`,
and residual `add` cost `0.05`. This lets the queue explore many one-line
graphs with cheap wrappers before it is forced to consider more independent
lines. A no-optimizer rank-depth sampler with two learned colors reached 5,000
emitted candidates and was still in the build-DL `1.20` bucket. The guided full
solution has build-DL `6.60`.

Color optimization improves candidate DL only after a candidate topology has
already materialized. It does not reprioritize the frontier and does not seed
optimized descendant expansion. Therefore learned color leaves remove a
candidate-scoring blocker but do not solve the search-order blocker.

## Next Steps When Resuming

1. Measure rank depth directly.

   Run an unguided no-optimizer sampler that stops at build-DL milestones
   rather than wall time. Record candidate counts, frontier pops, kept frontier
   size, and elapsed time for build-DL `2.0`, `3.2`, and `6.6`. This will give
   a real lower bound for reaching the second line, roof-complete structure,
   and full guided-solution depth.

2. Narrow optimizer gating.

   Do not optimize every `dye -> draw -> add` candidate. Use generic gates such
   as minimum colored-point support, minimum number of independent primitive
   geometry roots, or a cheap pre-score proximity threshold before fitting
   color slots. The optimizer should be a refinement/acceptance mechanism, not
   the inner loop for one-line exploratory candidates.

3. Revisit generic operator ranking.

   The current prior makes composition/rendering wrappers too cheap relative to
   adding more geometry. Consider a generic staged or balanced ordering rule
   that prevents cheap closure operators from swamping structure-building
   operators. Keep this recognizer-free and document how it relates to Python
   parity.

4. Keep lazy frontier as an opt-in performance tool.

   Lazy frontier improves memory and runtime for serial search at equal
   candidate counts, but it does not improve search quality. Do not treat it as
   the house solution. Parallel lazy frontier remains unimplemented.

5. Do not change the image DL model inside the parity path.

   The current Gaussian DL exactly matches Python for this fixture. If we want
   an image-specific or spatial residual code, make it a separate experiment
   with separate acceptance criteria.

6. Once ranking/gating changes are ready, rerun the house acceptance probes.

   The near-term acceptance bar is not necessarily full unguided success in one
   run. A useful next milestone is that unguided search reaches multi-line
   roof/body filled-region candidates much earlier and produces positive local
   compression before the previous 100k-200k candidate range.

## Useful Commands

Guided completion smoke run:

```bash
./bin/clojure -M:dev -e "(require '[ciwi.demos.house :as h]) (prn (h/run-guided-compression {:max-yields 18})) (shutdown-agents)"
```

Targeted house tests:

```bash
./bin/clojure -M:dev:test -e "(require '[clojure.test :as t] '[ciwi.demos.house-test]) (t/run-tests 'ciwi.demos.house-test)"
```

Long unguided probes should be run with an explicit heap cap and
`-XX:+ExitOnOutOfMemoryError`.
