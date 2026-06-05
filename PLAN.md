# CIWI Plan

Last updated: 2026-06-05.

## Objective

Prove that CIWI implements Python WILLIAM/Alice compression behavior at least as
well as the Python project, then use the working core as the base for
resource-bounded local graph rewrites and later outer-loop learning mechanisms.

## Current Checkpoint

- Last committed code checkpoint: `98702de` (`Align Wunderbaum parity
  ordering`). The current working tree adds `insert_repeat2` parity.
- Current working tree has the first Python-scale core Wunderbaum parity rows,
  greedy Alice/Wunderbaum task runs, lazy best-first node tuple enumeration, a
  Python-compatible value DL model, Alice operator DL alignment, per-run DL
  caching, deferred selected-expression realization, and several translation
  fixes; tests pass locally with 120 tests and 570
  assertions.

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
  `repeat_with_noise`, and `simply_linear` pass through the injected Alice
  operator basis via `ciwi.alice-wunderbaum`, with no recognizer templates. The
  live evidence matrix is `alice-test-parity.md`.
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

- Proceed to `insert_repeat3`, keeping every fix tied to a Python mechanism
  rather than a CIWI-only recognizer.
- If runtime parity becomes the immediate priority, profile delayed
  materialization, remaining repeated MDL work, and Clojure vector/numeric
  paths on `simple_repeat`, `insert_repeat`, and `simply_linear`, where CIWI
  still has a warm-runtime gap despite matching compression behavior.
- Keep updating `alice-test-parity.md` with measured core CIWI rate, exact
  selected solution, timing, and Python comparison for each row.
