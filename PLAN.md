# CIWI Plan

Last updated: 2026-06-11.

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
  the serial `test_wunderbaum.py::test_wunderbaum_iteration` solution case,
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
  experimental application/debug evidence, not core Alice proof rows.
  Tests pass locally with 163 tests and 853 assertions on the default vector
  backend, plus 8 tests and 43 assertions on the opt-in DJL backend.

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
  operator inversion, usage-biased DL, and MDL-selected materialized results.
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
  Python checks structural resemblance to the solution graph.
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

- The active macro step is optimizer-backed numeric graph-search parity,
  continuing from the classifier single-factor greedy rows to the full Iris
  row. Use
  `optimizer-graph-search-parity.md` as the evidence matrix for
  `test_discrete_optimizer.py`, matrix regression, clustering, and later
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
