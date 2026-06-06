# CIWI Plan

Last updated: 2026-06-06.

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
  the serial `test_wunderbaum.py::test_wunderbaum_iteration` solution case.
- The implementation includes Python-scale core Wunderbaum parity rows, greedy
  Alice/Wunderbaum task runs, lazy best-first node tuple enumeration, a
  Python-compatible value DL model, Alice operator DL alignment, per-run DL and
  value-key caching, deferred selected-expression realization, threshold-aware
  candidate yielding, and several translation/performance fixes; tests pass
  locally with 128 tests and 651 assertions.

## Current State

- CIWI has immutable graph/value/operator data structures, propagation, MDL
  selection, graph-backed composites, Fix, local rewrite operators, bounded
  compression loops, and a Clojure-native graph literal DSL.
- Recognizer templates are disabled by default and must be explicitly injected.
  They are not Alice parity evidence.
- `ciwi.wunderbaum` contains the first straight-port slice of Python
  Wunderbaum with injected registries, operator/count declarations,
  generalized conditions, node-tuple enumeration, delayed graph building,
  operator inversion, usage-biased DL, and MDL-selected materialized results.
- `ciwi.alice-wunderbaum` adds an Alice-facing greedy runner over that core.
  It is separate from the default `ciwi.alice` no-recognizer harness.
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
  basis via `ciwi.alice-wunderbaum`, with no recognizer templates. The live
  evidence matrix is `alice-test-parity.md`.
- `ciwi.alice-wunderbaum/run-greedy-task` now mirrors Python GreedyAlice's
  outer loop: compress the largest worthy raw leaf, accept the first candidate
  above the one-percent step threshold, splice it into the task tree, and
  repeat until the task threshold is reached. `simple_repeat`,
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
  delayed-builder stable value keys are cached by `Value` identity. These
  caches are explicit and caller-owned, so parallel local searches can choose
  private caches for low contention or shared value caches for reuse over the
  same immutable graph values.
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
  `trange`, `mean`, cyclic value dependencies, and the CIWI-native `nil` hole
  representation for Python's internal integer-array unknown sentinel.
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

## Roadmap

1. Keep the documentation split clean. `PLAN.md` stays current after each turn,
   `DESIGN.md` records technical decisions, `AGENTS.md` records workflow rules,
   `style.md` records coding style, `alice-test-parity.md` records Alice
   evidence, and `PYTHON-TEST-ROADMAP.md` records broader Python WILLIAM test
   parity sequencing.
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
   performance.
6. After parity is credible, adapt the working Wunderbaum core into a
   resource-bounded local `RewriteOperator` over focused neighborhoods and
   explicit budgets.
7. After the bounded rewrite operator is stable, revisit outer-loop learning:
   learned composites, learned rewrite templates, successful-history
   extraction, amortized proposal mechanisms, and neural or specialized
   proposal generators.

## Near-Term Next Tasks

- Move to composite/condition fixture expansion. The broader test map is
  `PYTHON-TEST-ROADMAP.md`.
- If runtime parity remains the immediate priority, focus on `increasing_runs`,
  `sprinkled`, `simply_linear`, and `map_negate`. `insert_repeat3` now reaches
  the Python rate in seven accepted steps and is faster than the recorded Python
  warm timing.
- For performance profiling, continue with residual pure Clojure large-array
  value-DL paths, materialization overhead, and any remaining repeated MDL
  scoring. The current 1D vector DL path has been tightened, delayed-builder
  de-duplication now avoids unconditional `pr-str` over large structural keys,
  and primitive vector probes avoid lazy pipelines on large vectors. The
  remaining `increasing_runs` gap is dominated by valid Python-basis probes over
  the large free root before the accepted `cumsum`. Do not solve these gaps with
  recognizer templates or task-specific shortcuts.
- Keep updating `alice-test-parity.md` with measured core CIWI rate, exact
  selected solution, timing, and Python comparison for each row.
