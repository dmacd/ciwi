# CIWI Plan

Last updated: 2026-06-04.

## Objective

Prove that CIWI implements Python WILLIAM/Alice compression behavior at least as
well as the Python project, then use the working core as the base for
resource-bounded local graph rewrites and later outer-loop learning mechanisms.

## Current Checkpoint

- Code checkpoint: `45cd03a Add initial Wunderbaum port`.
- Tests at that checkpoint: `./bin/test` passed with 109 tests and 490
  assertions.
- Current documentation work splits planning, design, parity evidence, agent
  workflow, and code style into separate files.

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
- `ciwi.alice-wunderbaum` adds an Alice-facing declaration table and runner
  over that core. It is separate from the default `ciwi.alice` no-recognizer
  harness.
- Python-scale Alice rows remain pending for the core enum path. The live
  evidence matrix is `alice-test-parity.md`.

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

- Compare the current `ciwi.alice-wunderbaum` runner against the simplest
  Python Alice rows and identify the first missing Python mechanism.
- Expand the Wunderbaum/Alice port until simple range, repeat, insert, and
  cumsum-style solutions arise from the Alice operator basis rather than local
  recognizer templates.
- Update `alice-test-parity.md` as soon as a core CIWI row has measured rate,
  solution, and timing data.
