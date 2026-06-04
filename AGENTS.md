# AGENTS.md

This project is a Clojure proof of concept for WILLIAM/Alice, with a long-term
goal of resource-bounded local graph rewrites for continual incremental
learning. The near-term goal is more conservative: prove that CIWI implements
Python WILLIAM/Alice at least as well as the Python project before adding new
mechanisms.

## Working Principles

- Treat compression behavior parity as the proof target. Do not chase private
  helper routine parity unless it is needed for propagation, graph search,
  bottleneck/MDL selection, delayed building, or Alice task behavior.
- Do not add task-specific recognizers, special cases, or extra operators to
  make parity tests pass. If Python Alice does not use that mechanism, it is not
  evidence for Alice parity.
- Keep recognizer templates opt-in. They are useful local proposal operators and
  debugging baselines, but they must not operate in the default Alice parity
  harness.
- Port Python Wunderbaum/Alice directly first. Do not prematurely force it into
  the local bounded rewrite interface. Once parity is credible, adapt the
  working core into a bounded local `RewriteOperator`.
- Operator registries and operator sets must be injected by callers. Do not
  hardcode `ciwi.alice/basic-operator-registry` inside Wunderbaum or lower-level
  search machinery.
- This is greenfield. Do not preserve compatibility aliases or legacy codepaths
  for surfaces we fully control. Refactor all uses and remove stale paths.
- Prefer Clojure-native data and pure transforms, but do not use that as an
  excuse to change the algorithm being validated against Python.

## Test Discipline

- Use Python-scale Alice fixtures when claiming parity. If a test must be
  scaled down temporarily, document that explicitly in code and docs as a
  performance gap or implementation gap.
- Compare selected compression structure and achieved compression rate. Exact
  Python floating DL constants are not a CIWI parity requirement because CIWI
  uses a simpler Clojure-native value codec.
- When a performance gap appears, diagnose the root cause before changing the
  algorithm. Valid fixes are translation fixes, missing Python mechanisms, data
  structure issues, or implementation performance issues. Invalid fixes are
  ad hoc recognizers or shortcuts.
- Run tests with the repo-local tooling:

```bash
./bin/test
```

Do not assume a global `clojure` binary is available.

## Roadmap

1. Maintain the no-default-recognizer Alice harness.
2. Port Python Wunderbaum and Alice orchestration faithfully:
   operator/count inputs, typed operator specs, generalized conditions,
   node-tuple enumeration, delayed DAG building, propagation/inversion,
   bottleneck/MDL scoring, and Python `test_alice.py` task behavior.
3. Fill basic operator/spec/inverse gaps only when they are required by the
   Python Alice parity tests.
4. After parity is established, adapt Wunderbaum into local resource-bounded
   graph rewriting over focused neighborhoods and explicit budgets.
5. Only after that, revisit learned composites, learned rewrite templates,
   amortized proposal mechanisms, and neural/specialized proposal generators.

## Documentation

- Honor the style preferences noted in `style.md`
- Keep `design.md` current when architecture changes.
- Keep `alice-test-parity.md` honest about what is parity, what is pending, and
  what is merely an opt-in recognizer baseline.
- When adding a new search mechanism, state whether it is a parity port,
  infrastructure, a local rewrite operator, or an outer-loop learning mechanism.

## Git And Workspace

- The worktree may be dirty with unrelated user changes. Do not revert or stage
  unrelated files.
- Commit only explicitly intended files.
- Avoid destructive git commands unless the user explicitly requests them.
