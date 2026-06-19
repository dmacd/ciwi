# CIWI Plan

Last updated: 2026-06-19.

## Objective

Prove that CIWI implements Python WILLIAM/Alice compression behavior at least as
well as the Python project, then use the working core as the base for
resource-bounded local graph rewrites and later outer-loop learning mechanisms.

## Current Checkpoint

- Current local state has Python-scale core parity evidence for all sequence
  rows in Python `test_alice.py`, plus the first round of warm-runtime fixes
  from profiling the largest gaps, and full Python
  `test_bottleneck.py::test_min_desc_len` and `test_propagation.py`
  propagation parity, `test_delayed_builder.py` materialization parity, and
  the serial `test_wunderbaum.py::test_wunderbaum_iteration` solution case
  plus the Python parallel bounded-drain shape,
  native fixture parity for Python `test_conditions.py` condition shapes, and
  representative `test_composite.py` behavior slices including the full
  `co0`-`co21` graph commutativity table, selected callable/sequence-edit
  inverse rows, conversion-map inverse rows, `cumop`/`div`/`table` fixture
  rows, `getitem`/`setitem` composite rows, and native composite spec
  synchronization.
- The implementation includes Python-scale core Wunderbaum parity rows, greedy
  Alice/Wunderbaum task runs, lazy best-first node tuple enumeration, a
  Python-compatible value DL model, Alice operator DL alignment, per-run DL and
  value-content caching, deferred selected-expression realization,
  threshold-aware candidate yielding, and several translation/performance
  fixes. The DJL backend now provides dense DL summaries, dense
  insert-frequency partitioning, primitive-buffer metadata for arrays created
  during partitioning, and a fast deterministic content hash. Alice's greedy
  tree keeps dense data internally and renders plain expressions only for
  public results. Optimizer-backed Wunderbaum candidates now cover the Python
  matrix-regression single-compression-step and greedy task shapes with an
  exact NumPy fixture. Graph-level optimizer parity now also covers the
  clustering `try_to_optimize` worker shape, the first Iris classifier
  `try_to_optimize` debug row, the Iris classifier direct
  single-compression-step row, and the Iris classifier single-factor greedy
  rows with and without a solution hint at Python scale. Iris-specific rows
  now live together in `ciwi.iris-classification-test` because they are
  experimental application/debug evidence, not core Alice proof rows. CIWI
  also covers Python `test_wunderbaum.py` optimizer helper behavior for
  extracting scalar/small-array leaves, skipping large arrays, and applying
  mixed scalar/float-array/integer-array optimizer coordinates. The
  JVM-threaded `wunderbaum/iterate-parallel` path is opt-in through
  `:parallelism` or `:num-workers`, is wired through Alice's candidate
  context without changing serial defaults, and now covers Python-scale
  sequence-task completion, Python `reg_only_y` regression completion, and the
  exact matrix-regression greedy row through the parallel Alice path.
  A first CIWI-vs-Python parallel scaling sweep, plus the first coordinated
  global queue prototype results, is recorded in
  `parallel-performance-scaling.md`.
- CIWI now has an opt-in generic graph rendering and tracing layer for demo
  and debugging work. `ciwi.render.graph` writes deterministic DOT and PNG
  through Graphviz when available, following the Python renderer's reading
  convention: value boxes, operator option nodes/tables, value-to-option-to-child
  edges, frontier leaf boxes, and a top DL statistics table.
  `ciwi.render.movie` provides stable frame names and optional ffmpeg MP4 assembly, and
  `ciwi.wunderbaum`/Alice greedy options accept sampled `:observer` callbacks
  for frontier materialization, accepted candidate, and greedy-step events.
  These hooks are inactive unless supplied by the caller, and the no-observer
  path now short-circuits before constructing event payload maps. Graph
  rendering now computes graph-level MDL description once per frame and reuses
  it for selected highlighting and DL labels, so completed house movies can
  use full Python-style graph frames without per-root recomputation.
- A Cursive-first Clojure port of Python
  `minimal_classifier_2d_three_cluster_onehot_sweep.ipynb` now lives under
  `notebooks/ciwi/notebook/`. The public notebook namespace keeps the same
  cell-level workflow split as the Python notebook, while the companion utils
  namespace owns deterministic three-cluster data generation, notebook-local
  classifier operator declarations, recompression scoring/sweeps, progress
  snapshots, inline SVG/HTML plots, and cached selected-expression rendering.
  The visible cells now use one `(cell ...)` macro form inside each `comment`
  block, so Cursive can evaluate the whole cell in place and show output inline
  without running it at namespace load time. The HTML views set explicit light
  backgrounds and dark foregrounds for readability in IDE render panes.
  `sweep-config` is the single source of truth for notebook sweep defaults.
  Sweep score rows and progress views now report heap-used/total/max MiB
  snapshots, including best-effort failure snapshots and sampled in-search heap
  observations, and cached graph inspection embeds successful Graphviz PNG
  renders while suppressing raw Clojure graph expressions by default.
  The port uses native deterministic Clojure samples rather than byte-identical
  NumPy PCG64 samples, uses CIWI's fraction rate semantics throughout, and its
  Python-grid constants remain opt-in through REPL evaluation of `(comment
  ...)` forms.
- A first native house-demo scaffold now lives in `ciwi.demos.house`. It
  includes the Python legacy fixture geometry, a small RandomState-compatible
  MT19937 normal generator for the seeded noisy 50x50x3 RGB task, demo-local
  low-level geometry/rendering primitives (`point-add`, `line`, `fill`,
  `concat`, `dye`, `draw`, and image residual `add`), PNG image output, and a
  guided partial-expression entry point with an injected primitive registry
  and native solution predicate. Colored point lists are represented as dense
  `[row col r g b]` arrays so the demo guide does not repeatedly hash large
  map vectors. The guided run now reaches the full 18-step expression through
  `draw` and residual `add`, hits a `0.1136` compression rate under default
  bounded guided settings, and writes stats, 18 graph frames, 18 image frames,
  MP4s, and an artifact README. The unguided house runner now goes through the
  standard Alice `task-search-context`, so it shares Alice value wrapping,
  cache setup, parallel candidate dispatch, and optimizer candidate-transform
  wiring while still using the house primitive registry and artifact-facing
  result shape. The guided runner remains custom because it owns prefix
  collection, image previews, solution-prefix predicates, and demo artifacts.
  The unguided runner removes the solution predicate, frontier predicate, and
  preferred-node scheduling while keeping the same primitive basis. CIWI now
  matches Python's delayed-builder
  duplicate-value guard for forward-generated values as well as inverses, and
  the house runner can opt into generic Wunderbaum frontier stats. A bounded
  10-yield unguided baseline previously yielded only negative-compression line
  roots because raw-yield mode stopped before composition. The house primitive
  inverses now also cover exact `dye` color inference from known uniform
  colored points and output-only `draw` inversion into all non-background
  colored pixels. This exposes the expected degenerate first candidate
  `draw(full-target-colored-points)` with a `[2500 5]` colored-point leaf; it
  is not compressive. A 1000-yield/5k-pop probe reaches legitimate
  `line -> dye -> draw -> add residual` shapes, but the best candidate remains
  negative compression (`-0.0021`). A heap-capped 30-minute serial probe was
  stopped deliberately after about 4 minutes to avoid repeating an OOM: it
  reached 43,292 emitted candidates and 93,604 frontier pops, used about
  7.5 GiB of an 8 GiB heap cap, and still had the same best
  `line -> dye -> draw -> add residual` shape at `-0.0018` compression. That
  memory pressure came from search-scoped caches and seen keys retaining
  generated dense values by identity/raw data. Dense value-DL and
  delayed-builder value-content caches now use weak identity keys, and
  materialized-result de-duplication stores compact fingerprints instead of raw
  value data. Weak dense-cache key equality now explicitly returns a boolean
  when a weak referent has been cleared, after a long house probe exposed a
  Java primitive-unboxing `NullPointerException` under GC pressure. The same
  4-minute heap-capped probe now reaches similar work
  (43,607 candidates, 94,417 pops) at about 5.4 GiB used, with the same best
  negative-compression shape. A compact-frontier cursor experiment packed
  sibling delayed-build descriptors sharing one graph/memory pair and realized
  one build item at pop time, but it was removed after measurement because it
  slowed the core path and did not materially reduce retained heap. The initial
  time-boxed comparison overstated the memory win because compact mode
  completed less work in the same wall time. A corrected fixed-work probe with
  the live queue retained during post-GC measurement showed identical search
  work at 10k/25k/50k pops and identical logical pending frontier sizes, while
  physical queue entries shrank from 208k/584k/1.28M concrete build items to
  4.6k/11.9k/25k cursors. Post-GC heap only dropped from 477/1232/2595 MiB to
  466/1197/2516 MiB. CIWI now has the first opt-in serial
  `:lazy-frontier?` implementation of that next idea: expansion cursors keep a
  resumable node-tuple enumerator and an internal child heap, expose a build
  item only when it is no later than the unscanned lower bound, and use
  `[expansion-order local-order]` ties to preserve eager block ordering. It is
  intentionally disabled for the partitioned/global parallel paths. Current
  tests verify tuple-cursor/eager tuple parity, range completion under lazy
  mode, eager-vs-lazy candidate-prefix equality, eager-vs-lazy
  frontier-materialization order equality over a 250-pop Python Wunderbaum
  prefix, and serial-only rejection for multi-worker modes. Fixed-candidate
  house probes show lazy frontier reducing both retained heap and runtime at
  equal yielded-candidate counts; the detailed memory-growth and timing tables
  live in `lazy-frontier-optimization.md`. A longer lazy-frontier house probe
  on an 8 GiB heap did not hit a formal OOM, but became GC-bound around
  100k emitted candidates and was stopped at 18.1 minutes after 104,725
  candidates, 222,786 frontier pops, 542,839 kept frontier entries, and
  8,181 MiB used, with the same best negative-compression shape. A follow-up
  lazy-frontier probe with a 16 GiB heap and no practical search timeout
  reached 213,209 emitted candidates, 433,777 frontier pops, and 1,042,670
  kept frontier entries after 21.1 minutes, then was stopped once throughput
  fell below about 500 candidates/minute near the heap cap; the best graph was
  still unchanged. The observed multi-core activity during these serial probes
  is consistent with JVM GC/JIT work; the lazy frontier implementation still
  rejects multi-worker partitioned/global paths. A thresholded 5k-pop probe now gets
  past the line-only phase and materializes fill, dye, concat, draw, and add
  work, but still finds no 1% compression candidate; a 50k-pop serial probe
  hit a 60s diagnostic timeout. The house runner now honors the existing Wunderbaum
  parallel strategy options, and a 20k-pop global-best-first probe on 4 workers
  completed in about 27s without a compression candidate. A
  larger 50k-pop global-best-first probe completed in about 60s and still
  sampled the same low build-DL bucket (`1.2`), while the guided full solution
  has build-DL `6.6`. Unguided experiments can now add learned permeable
  `:color` leaves without the baked-in house colors; a small graph optimizer
  test shows a learned color leaf moving through `dye -> draw -> add residual`
  with lower DL when a joint RGB optimizer is supplied through the standard
  context. The Alice optimizer transform now leaves no-slot candidates
  unchanged instead of attaching an empty optimizer result and recomputing DL.
  A 5-yield learned-color unguided probe exercised the new path but only
  reached `draw` candidates with no color slots. A 1000-yield slot probe with
  learned colors and baked-in colors disabled found 30 actual optimizer slots,
  all `:color` dense arrays of size 3; it also encountered large permeable
  `:colored-point-list` `[2500 5]` and `:rgb-image` `[50 50 3]` leaves, but
  those were skipped by the existing large-array optimizer limit and were not
  optimized. A focused gated probe that only optimized slotted `dye -> draw`
  candidates found the first color-slot candidate at 318 yielded candidates
  and the first useful one at 361 yielded candidates, with graph ops
  `[:add :line :dye :draw]`; the learned color moved from `[128 128 128]` to
  `[128 193 63]` and reduced candidate DL by about `2.46` bits, but the graph
  remained non-compressive. A deeper 1200-yield optimized probe was stopped
  after about two minutes because optimizing all eligible color-slot candidates
  is too expensive without a gating strategy. A 30-minute 16 GiB learned-color
  run through the standard Alice context used two learned color leaves, removed
  the baked-in house colors, kept lazy frontier enabled, and optimized only
  candidates containing `dye`, `draw`, and residual `add`. It timed out after
  1,801s with 7,575 emitted candidates, 18,227 frontier pops, 99,626 frontier
  items considered, 47,781 kept frontier entries, and about 1.7 GiB heap used.
  The gate still optimized too much low-depth work: 374 candidates were
  optimizer-eligible, 266 had slots, and 138 improved locally. The best
  optimizer-local improvement was about `50.8` bits on an
  `[:add :add :line :dye :draw]` variant, but the best overall graph remained
  the same negative-compression `[:add :line :dye :draw]` shape with build-DL
  `1.15` and compression rate `-0.000981`. A concurrent no-optimizer
  rank-depth sampler with the same learned-color free values reached 5,000
  emitted candidates and was still in the build-DL `1.20` bucket; the guided
  full solution has build-DL `6.60`. This confirms that color fitting removes
  a candidate-scoring blocker but does not address the topology-ordering
  blocker, because the Wunderbaum queue is still ranked by `[build-dl, order]`
  and optimized candidate memories do not reprioritize or seed descendant
  expansion. `HOUSE-DEMO-PLAN.md` is now the focused checkpoint for this demo's
  state, probe results, blockers, and resume plan. A direct CIWI-vs-Python raw-image
  DL check on the legacy house fixture now matches Python exactly for the red
  field, noiseless house, noisy red field, noisy house, noise-only residual,
  and shape residual diagnostics. Under the current Python-compatible
  channel-wise Gaussian array codec, the noisy house target is
  `110668.334` bits, the noisy red field is `97818.280` bits, and the full
  true-house residual is `97787.868` bits. Removing only the roof lowers the
  residual by about `2499.7` bits, removing only the body by about `6492.7`
  bits, and removing the full house by about `12880.5` bits before expression
  cost. The corresponding low-level expression costs are small under the
  current demo DLs, but the codec only sees per-channel marginal variance and
  not spatial arrangement, so partial shape rewards are much weaker than a
  human visual-complexity intuition would suggest. The current unguided
  baseline is therefore not
  practically close; the next task is using the operator/spec stats to tune
  generic bounds, ordering, and DL costs so useful roof/body compositions
  surface much earlier. Unguided recognizer-free house discovery remains
  pending.
- Targeted Wunderbaum tests pass locally with 17 tests and 43 assertions, and
  targeted house-demo tests pass locally with 18 tests and 59 assertions after
  moving unguided house onto the Alice context and adding the learned-color
  optimizer checks. Targeted delayed-builder tests pass locally with 11 tests
  and 39 assertions after the weak dense-cache-key equality fix. Targeted
  graph-optimizer tests pass locally with 5 tests and 19 assertions, targeted
  Alice Wunderbaum tests pass with 14 tests and 134 assertions, and targeted
  matrix-regression Alice tests pass with 4 tests and 19 assertions after
  wiring `:optimizer-fn` through the Alice context. The default vector-backend
  suite was not completed after those changes; the last completed full run
  before them passed with 200 tests and 1013 assertions. The opt-in DJL backend
  was not rerun in this turn; the last recorded DJL suite remains 8 tests and
  43 assertions.

## Current State

- CIWI has immutable graph/value/operator data structures, propagation, MDL
  selection, graph-backed composites, Fix, local rewrite operators, bounded
  compression loops, a Clojure-native graph literal DSL, and a `ciwi.dense.*`
  dense boundary. The default numeric graph value representation is the vector
  backend; an opt-in `ciwi.dense.djl` backend now exercises the same boundary
  against DJL/PyTorch CPU arrays.
- Recognizer templates are disabled by default and must be explicitly injected.
  They are not Alice parity evidence.
- `ciwi.wunderbaum` contains the first straight-port slice of Python
  Wunderbaum with injected registries, operator/count declarations,
  generalized conditions, node-tuple enumeration, delayed graph building,
  operator inversion, usage-biased DL, MDL-selected materialized results, and
  an opt-in partitioned parallel iterator for Python's parallel-drain and
  Alice parallel completion shapes. It also exposes opt-in sampled observer
  events for materialized frontier items and emitted candidates; Alice greedy
  emits matching accepted-step events. The observer path is a debugging/demo
  surface and does not alter search results when absent. Absent observers are
  checked before event payload construction, so rendering/tracing costs stay
  opt-in. Serial searches can opt into lazy frontier cursors with
  `:lazy-frontier?` or `:frontier-mode :lazy`; default eager expansion remains
  unchanged. Non-parity demos can additionally opt into pre-materialization
  frontier predicates and preferred tuple nodes; those hooks are caller-owned
  scheduling controls and are not enabled by the Alice parity harness.
- `ciwi.render.graph` is the generic graph visualization surface. It renders
  value boxes, operator option nodes/tables with argument ports, explicit
  roots, selected options, frontier leaves, graph DL statistics, and compact
  value summaries to DOT using Python-compatible visual conventions, and
  shells out to installed Graphviz `dot` for PNGs. `ciwi.render.movie` builds
  stable graph-frame paths and optionally invokes `ffmpeg` for MP4s.
- `ciwi.demos.house` is the staged house-image demo area. Its fixture and
  primitives are intentionally demo-local and are not part of the default
  Alice parity basis. Current coverage verifies deterministic fixture
  generation, primitive rendering behavior, PNG writing, bounded guided
  partial-expression discovery, partial image preview changes, guided artifact
  writing, full guided threshold completion, the first bounded unguided
  no-compression baseline through the standard Alice context, and learned-color
  optimizer behavior on a small `dye`/`draw` residual graph. Removing the guide
  and still discovering a
  recognizably house-shaped compressive graph remains the next open demo
  milestone.
- `ciwi.alice.wunderbaum` adds an Alice-facing greedy runner over that core.
  It is the main Alice parity path. `ciwi.alice` still supplies shared task
  records, the Alice operator registry, constructors, and compression-rate
  helpers. `ciwi.alice-legacy` contains the old local no-recognizer runner,
  retained only as a baseline harness.
- `ciwi.value` now ports Python WILLIAM's scalar, structural, array, and
  Gaussian value description length model, with a faster 1D homogeneous-vector
  scoring path that preserves the Python DL formula. Alice/Wunderbaum
  declarations also preserve Python `TaskDomain` operator DL in materialized
  graphs.
- Large-vector primitive probes now use strict vector loops where that is
  faster and preserve the original simple sequence paths for small vectors.
  This is an execution optimization for the Python Alice operator basis, not a
  new recognizer or shortcut. Revisit these paths after CIWI has a proper dense
  primitive array layer; they may become unnecessary complexity.
- Python-scale `simple_repeat`, `insert_repeat`, `insert_repeat2`,
  `insert_repeat3`, `repeat_with_noise`, `simply_linear`, `sprinkled`,
  `increasing_runs`, and `map_negate` pass through the injected Alice operator
  basis via `ciwi.alice.wunderbaum`, with no recognizer templates. The live
  evidence matrix is `alice-test-parity.md`, which now records both default
  vector-backend and opt-in DJL-backend warm timings for the core path.
- `ciwi.alice.wunderbaum/run-greedy-task` now mirrors Python GreedyAlice's
  outer loop: choose the current task leaf, accept the first candidate above
  the `0.01` step threshold, splice it into the task tree, and repeat until
  the task threshold is reached. `simple_repeat`,
  `insert_repeat`, `insert_repeat2`, and `simply_linear` now reach the same
  Python-shaped solutions.
- Each greedy compression step now passes other current task-tree leaves as
  zero-cost free values, matching Python's current-graph free-value reuse.
  Wunderbaum iteration also supports Alice's `threshold_dl` behavior, yielding
  only the first graph below the step threshold while continuing to explore
  non-threshold frontier items internally.
- Wunderbaum candidate summaries defer selected-expression realization until a
  candidate is accepted or explicitly inspected. Value DLs are cached across
  each candidate stream, existing `Value` records use identity cache keys, and
  delayed-builder generated-value de-duplication uses caller-owned
  value-content caches keyed by deterministic content fingerprints with exact
  comparison inside matching buckets. These caches are explicit and
  caller-owned, so parallel local searches can choose private caches for low
  contention or shared value caches for reuse over the same immutable graph
  values. The delayed builder also has an explicit caller-owned inverse cache
  keyed by runtime operator, condition, output content, and known input
  content, so duplicate declaration/spec variants do not recompute identical
  inverse calls.
- Delayed graph materialization now skips non-executable operator
  calls/inverses, matching Python's invalid-probe behavior, and numeric inverse
  shape mismatches no longer generate `nil` children.
- Alice Wunderbaum declarations now carry Python's deterministic operator-DL
  jitter, and delayed materialization validates inverse/forward values against
  declaration specs. These were required to match Python candidate ordering and
  reject invalid concrete variants such as boolean-mask `getitem` with an
  integer index vector.
- Node tuple enumeration now uses a persistent best-first successor queue
  rather than generating and sorting the full tuple product.
- Focused Alice/Wunderbaum runs now preserve Python root-section semantics:
  the focused target is the primary root, free values are ordered after it, and
  attachment validation uses those explicit roles instead of relying on Clojure
  map/root iteration order.
- Focused compression steps score only the primary target root, matching
  Python's candidate scoring against the focused leaf plus free values. This
  prevents dummy/free roots from deciding whether a local candidate is accepted.
- Delayed materialization now skips inverse-generated values already present in
  memory and de-duplicates by whole materialized root sets, matching Python's
  delayed DAG builder more closely. Whole-result de-duplication keeps exact raw
  value keys while using hash-first commutative child ordering to avoid
  unconditional stringification of large vector-containing structural keys.
- Delayed materialization carries inferred specs on generated `Value` records,
  avoiding repeated Python-scale vector scans during node-condition indexing.
  Wunderbaum graph expansion computes attachment reachability context once per
  expansion, and the unconditioned `getitem` inverse now builds source/inverse
  index vectors in one pass.
- CIWI now distinguishes existing task-tree free anchors from synthetic/default
  free values. Existing leaves used as local anchors stay zero-DL in the tree
  summary to avoid double-counting shared leaves; synthetic defaults such as
  `1` and `1.5` are normal permeable values and are charged if selected, as in
  Python's bottleneck accounting.
- Graphs now carry explicit scored roots. Root-ness is not inferred from
  parent links, so a target value can also be reused as a child in another
  target's selected expression.
- `ciwi.mdl/graph-description` now implements Python-style cross-section MDL
  selection. It jointly enumerates raw-vs-option choices across a section,
  shares already-seen value descriptions, and propagates traces to stop cycles.
  Python `test_bottleneck.py::test_min_desc_len` fixtures now match Python DL
  constants and selected operators, including `mult_negate`,
  `mult_negate_add`, and `regression` at Python scale. The large bottleneck
  golden cases are expressed as native EDN value/operator/root graph specs, not
  as DOT imports.
- Native propagation parity tests now cover Python `co2`, `co3`, `co4`,
  `matching/set_mean_add`, and `composite/trees2` behavior without DOT parsing.
  These cases exercise partial propagation, multiple inverse branches,
  `trange`, `mean`, cyclic value dependencies, and dense numeric arrays with
  NaN-backed missing slots for Python's internal unknown sentinels.
- Delayed-builder parity now covers Python's `Array[int]` simple and
  `with_mult` fixtures as exact native `brange` output-conditioned
  materializations, plus the same-node binary input regression and
  description-length ordering behavior from `test_delayed_builder.py`.
- Standalone Wunderbaum parity now covers the Python
  `setitem(repeat(...), negate(...), negate(...))` solution shape from
  `test_wunderbaum.py::test_wunderbaum_iteration` using an injected registry
  and explicit declarations for that test's operator set. The test compares
  native graph option expressions rather than MDL-selected expressions because
  Python checks structural resemblance to the solution graph. CIWI also covers
  the Python parallel-drain variant with `iterate-parallel`, worker-local
  queues, and `threshold-dl 0`.
- Parallel Alice coverage now runs the Python-scale sequence rows through the
  opt-in `:num-workers 8` path. These tests assert bounded completion, sane
  nonnegative compression, and at least one accepted compression step. They do
  not assert serial selected expressions because worker-local parallel search
  can accept a different first candidate above the step threshold, matching the
  spirit of Python's completion-only `test_single_task_parallel`.
- Non-sequence parallel Alice coverage now includes Python's deterministic
  `reg_only_y` regression row through the basic Alice operator basis and the
  exact matrix regression greedy row through the optimizer-backed `[Dot, Add]`
  path. The other stochastic Python regression rows still need exact NumPy
  fixture capture before CIWI should claim direct fixture parity.
- CIWI now has an experimental `:parallel-strategy :global-best-first` path
  with one coordinated frontier, dynamic worker batches, shared pop/yield
  counters, concurrent delayed-result admission, ordered commit,
  candidate-sensitive cancellation at safe boundaries, and deferred descendant
  expansion through first-class expansion queue items. It is not the
  Python-parity path. The ordered-commit implementation keeps
  threshold candidates from committing while earlier-ranked frontier work is
  still queued or active, and it exposes opt-in scheduler stats through
  `:wunderbaum-stats-atom` and Alice's `:collect-wunderbaum-stats?`.
  A full warm `insert_repeat3`/`increasing_runs`/`reg_only_y` ordered-global
  matrix with `--runs 3 --stats true` is now recorded in
  `parallel-performance-scaling.md`. Deferred expansion is the first clear
  scaling win for the ordered global path: large `insert_repeat3` now improves
  from `3825 ms` at one worker to `796 ms` at eight workers, and its large-case
  frontier enqueues drop from about `160k` to about `20k`.
  `increasing_runs` and `reg_only_y` remain weak scaling cases: extra workers
  can still over-speculate on narrow paths where materialization/scoring
  dominates useful search work. The next scheduler work should add adaptive
  useful-width control, candidate-sensitive dispatch width, and finer
  cancellation inside long inversion/materialization paths where possible.
  Medium `insert_repeat3` partitioned threshold failures are now understood as
  greedy path/order sensitivity, not absence of a solution: worker-local
  frontiers can accept a cumsum-first local compression that later stops below
  the task threshold with `:leaf-below-worthy`, while serial/global-best-first
  reach the insert/cumsum/getitem path.
- Cache contexts now use caller-owned `ConcurrentMap` stores through
  `ciwi.cache` helpers instead of atom-wrapped persistent maps. Value DL,
  delayed-builder value fingerprints/inverse results, MDL node DL, and
  optimizer-backed value DL scoring use the same cache boundary.
- Native condition extraction now covers Python `test_conditions.py` fixture
  shapes `co0`-`co21` and `dag0`-`dag7`, including the `co15` order-only
  fixture. These are expressed as native graph/composite specs instead of DOT
  imports. The condition implementation now mirrors Python's callable
  condition adoption for `map`-style operators and its repeated-leaf DAG
  projection rules.
- Native composite behavior parity now covers Python `test_composite.py`
  shared-DAG execution for `dag4` and `dag5`, the `dag5` extra-branch inverse,
  exact inverse rows for `co2`, `co3`, and `co4`, and the full `co0`-`co21`
  graph commutativity table. It also covers `co0` insert inversion, `co12`
  repeat inversion, `co7` nested `abs` inversion, and `trees13`
  `map(abs, x)` inverse branching/branch-cap behavior. It now also covers
  `trees0` `concat` inversion, `trees12`/`trees17` conversion-map inversion,
  `trees7` `bmap(add, ...)` inversion through `urange`, the invalid `co10`
  `urange/insert` row, `co11` `trange/repeat/listwrap/insert` inversion,
  `trees6`/`trees14` `cumop`, `trees15` `bmap(div, ...)`, `trees18`
  invalid nested conversion, `trees2`/`trees9`/`trees10` insert/table/repeat
  inversions, the remaining expected-empty inverse rows, and `cl_func`,
  `insert`, and `dec` `getitem`/`setitem` composite rows. Native spec
  synchronization now enumerates concrete CIWI keyword signatures over
  graph-backed composites from injected operator declarations. The broader
  `zip2d`, `abs`, `toint`, `tofloat`, `urange`, `listwrap`, `bmap`, `cumop`,
  `div`, `table`, `dec`, and related non-Alice operators used by these cases
  remain test-local fixture operators. `union` and `sum1` now also exist as
  runtime primitives for optimizer-backed numeric graph fixtures. They are not
  added to the Alice `test_alice.py` parity basis.
- `map` inversion now mirrors Python WILLIAM's elementwise fallback more
  closely: when a callable cannot invert the whole output directly, CIWI groups
  scalar inverse branches by type, takes Cartesian products per type, and drops
  a type branch whose product exceeds 100 alternatives.
- Composite inverse requests now accept non-minimal condition sets only when
  they cover at least one advertised composite condition, matching Python cases
  such as `dag5` while rejecting unsupported condition sets.
- Numeric graph array values now cross the value boundary through the
  `ciwi.dense.core` API. `ciwi.dense.protocols` defines the backend contract,
  `ciwi.dense.vector` is the current default pure Clojure implementation, and
  `ciwi.dense.djl` is an opt-in real backend under `:djl`. Alice-basis
  operators, propagation, MDL/raw expression rendering, rewrite enumeration,
  graph rewrite, library matchers, delayed-builder materialization, and
  Wunderbaum inspection are dense-aware while symbolic vectors/lists, graph ids,
  search state, and optimizer coordinate machinery remain native Clojure data.
  Dense numeric missing values use `NaN`, and tests normalize dense outputs
  only at assertion boundaries. Dense first-axis selection and axis-0
  concatenation support clustering-style graph fixtures. General dense
  elementwise ops still reject mismatched array shapes; Python `Sub` gets an
  explicit broadcast helper for the centroid case without changing `Mult` or
  other Alice operator semantics.
- The current performance pass fixed several Python-vs-CIWI execution-layer
  mismatches without adding recognizers: task targets/free values are
  pre-coerced once per `CompressionTask`, DJL concat promotes dtype from dense
  metadata instead of flattening operands, repeated delayed-builder inverses
  are cached per candidate stream, unconditioned insert frequency partitioning
  uses strict two-pass loops, and integer array DL uses the integer
  precision/Elias specialization instead of the generic floating round loop.
  Fresh warm medians are recorded in `alice-test-parity.md`. `increasing_runs`
  is still materially slower than Python but is parked for now.
- CIWI rate values are fractions everywhere. `:threshold-rate`,
  `:min-compression-rate`, and reported `:compression-rate` all use values in
  `[0, 1]`; `0.01` is the standard small-step threshold. The constructors
  reject out-of-range rates instead of preserving Python's mixed whole-number
  and fraction conventions.
- Optimizer-backed candidate search is now wired into Alice/Wunderbaum as an
  opt-in candidate transform. A caller can also supply a generic
  `:candidate-predicate` to filter materialized candidate summaries before
  transformation and expansion. This is used by the matrix-regression parity
  test as a native structural solution-prefix filter, matching Python's
  provided-solution test setup without introducing a matrix-specific
  recognizer. Graph-level `try-to-optimize` now also mirrors Python's
  cross-section bottleneck scoring path when a section leaf below the root
  reuses the root target value, which is required by the clustering worker
  shape.
- `ciwi.alice.wunderbaum/compression-step-candidate` mirrors Python's direct
  `GreedyAlice.compression_step(target, free_values=...)` call for one target
  and explicit free values. This is deliberately distinct from
  `run-compression-step`, which mirrors Python's task-level greedy choice of
  the largest worthy leaf. That distinction matters for matrix regression:
  the Python single-step test focuses `y` while the task contains both `y` and
  `x_mat`.
- Matrix regression now has an exact checked-in NumPy fixture generated from
  Python `default_rng(123)`. CIWI's direct compression-step row finds the
  Python-shaped `(add (dot x_mat w) residual)` solution prefix and optimizes
  the permeable weight leaf to the Python `try_to_optimize` result. The `add`
  inverse now rounds the inferred residual to the output precision, matching
  Python's residual precision behavior and preventing high-precision optimized
  dot outputs from making the residual artificially expensive.
- Matrix regression greedy parity now covers both Python
  `test_greedy_with_solution` and `test_greedy_without_solution`. The parity
  runner explicitly uses `:leaf-selection-policy :python-test-parity`: step 0
  attempts target roots in task order, while later steps sort current leaves by
  descending DL. This is Python parity behavior, not the preferred future CIWI
  scheduling design. Non-parity callers can use `:largest-dl`, and future
  bounded local search should make leaf/neighborhood choice an outer-controller
  decision.
- Clustering `try_to_optimize` worker behavior is now covered on a
  deterministic Python-scale CIWI fixture. The native graph mirrors Python's
  `union(getitem(x, lessthan(sum1(mult(sub(x, c), sub(x, c))), s)), rest)`
  shape and verifies finite DL, at least 1% improvement, inferred residual/rest
  rows, and movement of the permeable centroid or radius. Exact NumPy
  `default_rng(2026)` fixture capture remains pending before claiming exact
  fixture parity.
- Iris classifier `try_to_optimize` behavior is now covered on the canonical
  sepal-length/label fixture with Python `RandomState(0)` permutation. The
  native graph mirrors Python's `setitem(rest, lessthan(factor, threshold),
  selection)` shape, infers `rest` and `selection`, and verifies finite
  optimized DL with scalar threshold movement. The Iris rows are grouped in
  `ciwi.iris-classification-test` rather than the generic graph optimizer or
  Alice namespaces because they are still experimental application/debug cases.
- Iris classifier direct single-compression-step behavior is now covered with
  the same shared fixture and the injected `[SetItem, LessThan]` operator
  basis. The native solution hint is a solution-prefix predicate: it admits
  the partial `lessthan(factor, threshold)` graph so Wunderbaum can expand
  toward the full `setitem(rest, lessthan(factor, threshold), selection)`
  structure, matching Python's supplied-solution subgraph filter. The
  classifier-local `SetItem` declaration accepts a numeric rest array because
  CIWI represents missing integer-label slots as dense `NaN` values.
- Iris classifier single-factor greedy behavior is now covered with and without
  a supplied solution hint.
  The task shape mirrors Python's `[target, factor]` targets,
  `free_values=[threshold]`, `threshold_rate=0.01`, and injected
  `[SetItem, LessThan]` domain. Both rows reach the task threshold in one
  greedy step and select the native
  `setitem(rest, lessthan(factor, threshold), selection)` target expression.

## Deferred Cleanup Decisions

These cleanup-review items are intentionally not active targets right now:

- Operator type specs remain in declaration tables rather than beside runtime
  operator definitions. This is acceptable for now because one runtime operator
  can have many typed Alice/Wunderbaum signatures, while Python count/jitter
  metadata is declaration-specific.
- Dense backend state should stay as-is unless it creates observable global
  state interference. DJL remains the default dense backend focus for the
  foreseeable future.
- Composite internals still use local mutable accumulators during template
  analysis. Composites are active parity/library machinery, so leave this alone
  unless we are specifically working on composite learning or library
  compression.
- `ciwi.enumerator` still contains reference/test helpers for tree counting and
  tuple enumeration. The live Wunderbaum path only depends on usage-biased DL,
  but the remaining helpers are low-priority reference machinery.
- `ciwi.optimize` owns mutable per-search eval/cache state. That is acceptable
  for now because it is scoped to a `NewtonSearch` instance and not shared
  globally.
- The local counter in `ciwi.dsl/from-expr` is not worth cleaning up right now.

## Roadmap

1. Keep the documentation split clean. `PLAN.md` stays current after each turn,
   `DESIGN.md` records technical decisions, `AGENTS.md` records workflow rules,
   `style.md` records coding style, `alice-test-parity.md` records Alice
   evidence, `optimizer-graph-search-parity.md` records optimizer-backed
   numeric graph-search evidence, `OPTIMIZATION-BACKLOG.md` records deferred
   semantics-preserving performance work, and `PYTHON-TEST-ROADMAP.md` records
   broader Python WILLIAM test parity sequencing.
2. Complete a straight Clojure port of the Python Wunderbaum/Alice path before
   adding resource-bounded local semantics. Preserve the Python search concepts:
   operator/count inputs, typed specs, generalized conditions, node-tuple
   enumeration, delayed DAG building, propagation/inversion, bottleneck/MDL
   scoring, and the Alice task loop.
3. Fill only the basic operators, specs, inverses, propagation behavior, and
   graph/MDL machinery required by Python `test_alice.py`. Do not add CIWI-only
   recognizer shortcuts to satisfy parity rows.
4. Run the Python-scale Alice tasks through the core CIWI path. Record CIWI
   compression rate, exact selected solution, Python solution, timing, and
   status in `alice-test-parity.md`.
5. Debug the worst compression or performance gaps one at a time. Root causes
   should land in Wunderbaum, Alice orchestration, operator semantics,
   propagation, delayed building, bottleneck/MDL, or data-structure
   performance. The current open gaps are runtime gaps, not compression gaps:
   `increasing_runs` matches Python's solution/rate at 178.3 ms with DJL
   versus Python's 88 ms, and can be left alone for now. `insert_repeat3`
   remains around 7.0 s because its full threshold run exercises much more
   nested candidate search. Focused profiling shows steps 1-3 are cheap, step
   4 costs about 1.1 s, and step 6 is the main cliff. A six-step structural
   profile processed 7,741 frontier items, expanded 2,651 materialized graphs,
   and ran about 3.3 million attachment-validity checks; direct DL/operator
   dense costs are not the dominant driver.
6. After parity is credible, adapt the working Wunderbaum core into a
   resource-bounded local `RewriteOperator` over focused neighborhoods and
   explicit budgets.
7. After the bounded rewrite operator is stable, revisit outer-loop learning:
   learned composites, learned rewrite templates, successful-history
   extraction, amortized proposal mechanisms, and neural or specialized
   proposal generators.

## Near-Term Next Tasks

- The active non-Iris cleanup step was parallel Wunderbaum/Alice parity. The
  current slices are implemented: `wunderbaum/iterate-parallel` partitions the
  delayed frontier across worker-local searches, the Alice context opts into it
  through `:parallelism` or Python-shaped `:num-workers`, tests cover a direct
  compression result plus the Python standalone bounded-drain shape,
  Python-scale sequence rows complete through `:num-workers 8`, and
  non-sequence `reg_only_y` plus matrix-regression rows now have parallel
  completion coverage. `parallel-performance-scaling.md` records the first
  diagnostic timing sweep and coordinated global-queue prototype. The next
  parallel deepening option is to improve the shared stopping/search-order
  story before expanding stochastic regression fixture coverage.
- Optimizer-backed numeric graph-search parity remains the next application
  tranche after this non-Iris cleanup. Continue from the classifier
  single-factor greedy rows to the full Iris row when classification becomes
  active again. Use `optimizer-graph-search-parity.md` as the evidence matrix
  for `test_discrete_optimizer.py`, matrix regression, clustering, and later
  classification rows.
- Residual-DL adaptive optimizer behavior is now covered on Python-scale
  deterministic CIWI fixtures, using signal-only Elias residual DL and the
  Python assertion shapes from `test_discrete_optimizer.py`. Exact NumPy
  fixture capture is still pending for those standalone optimizer rows before
  full fixture parity claims.
- Matrix regression direct optimizer behavior is now covered on a deterministic
  `1000 x 10` fixture using dense `dot`, rounded predictions, signal-only
  residual DL, and rounded weight `Value.desc_len`.
- Graph-level `try-to-optimize` behavior is now covered for the matrix
  regression weight leaf with explicit cross-section `section-ids`, precision
  preserving `dot`/`add` propagation, and a deterministic `1000 x 10` fixture.
- Python `test_wunderbaum.py` optimizer helper behavior is now covered in
  `ciwi.graph-optimize-test`: optimizer extraction includes permeable scalars
  and short dense float arrays, skips large dense arrays, and applies mixed
  scalar/float-array/integer-array trial coordinates with Python-style
  integer-array rounding.
- Matrix regression `test_single_compression_step`, `test_greedy_with_solution`,
  and `test_greedy_without_solution` are now covered on the exact NumPy fixture.
  Supplied-solution rows use a native structural solution-prefix predicate;
  the without-solution greedy row runs unconstrained over the injected
  `[Dot, Add]` operator set.
- Next stage classifier parity with the full Iris row. Treat the brute-force
  classifier rows as later application evidence.
- Keep the `insert_repeat3` performance audit parked unless performance becomes
  the active task again. The attachment-check reduction options are tracked in
  `OPTIMIZATION-BACKLOG.md` and should preserve Python Alice search semantics.
- Keep updating `alice-test-parity.md` only for plain Alice/Wunderbaum sequence
  compression, and update `optimizer-graph-search-parity.md` for numeric
  optimizer-backed rows.
