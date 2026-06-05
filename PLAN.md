# CIWI Plan

Last updated: 2026-06-05.

## Objective

Prove that CIWI implements Python WILLIAM/Alice compression behavior at least as
well as the Python project, then use the working core as the base for
resource-bounded local graph rewrites and later outer-loop learning mechanisms.

## Current Checkpoint

- Last committed code checkpoint: `7ed04fc` (`Add insert repeat three
  parity`). The current working tree adds `increasing_runs` parity; the
  immediately preceding commits added `sprinkled`, `map_negate`, and the
  Python-aligned synthetic free-value accounting fix.
- Current working tree has the first Python-scale core Wunderbaum parity rows,
  greedy Alice/Wunderbaum task runs, lazy best-first node tuple enumeration, a
  Python-compatible value DL model, Alice operator DL alignment, per-run DL
  caching, deferred selected-expression realization, and several translation
  fixes; tests pass locally with 122 tests and 598 assertions.

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
  Gaussian value description length model. Alice/Wunderbaum declarations also
  preserve Python `TaskDomain` operator DL in materialized graphs.
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
  candidate is accepted or explicitly inspected, and value DLs are cached
  across each candidate stream.
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
  delayed DAG builder more closely.
- CIWI now distinguishes existing task-tree free anchors from synthetic/default
  free values. Existing leaves used as local anchors stay zero-DL in the tree
  summary to avoid double-counting shared leaves; synthetic defaults such as
  `1` and `1.5` are normal permeable values and are charged if selected, as in
  Python's bottleneck accounting.

## Roadmap

1. Keep the documentation split clean. `PLAN.md` stays current after each turn,
   `DESIGN.md` records technical decisions, `AGENTS.md` records workflow rules,
   `style.md` records coding style, and `alice-test-parity.md` records evidence.
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

- Finish and commit the current `increasing_runs` parity tranche after tests
  pass.
- Decide the next parity target after sequence rows: either profile the
  remaining warm-runtime gaps in the core Alice/Wunderbaum path, or broaden
  parity beyond `test_alice.py` sequence compression tasks.
- If runtime parity becomes the immediate priority, profile `insert_repeat3`
  first: the latest warmed full local run reached the Python rate and seven
  accepted steps, but CIWI is still substantially slower than Python.
- For performance profiling, start with delayed materialization, remaining
  repeated MDL work, and Clojure vector/numeric paths. `insert_repeat3` is now
  the largest measured gap; `simple_repeat`, `insert_repeat`, and
  `simply_linear` remain useful smaller probes.
- Keep updating `alice-test-parity.md` with measured core CIWI rate, exact
  selected solution, timing, and Python comparison for each row.
