# AGENTS.md

This file is for agent workflow and project-maintenance guidance. CIWI is a
Clojure proof of concept for WILLIAM/Alice with a long-term goal of
resource-bounded local graph rewrites for continual incremental learning. The
near-term bar is stricter and simpler: prove that CIWI matches Python
WILLIAM/Alice compression behavior before adding new mechanisms.

## Document Roles

- `PLAN.md` is the live development roadmap. Update it after each turn that
  changes implementation status, next steps, known gaps, or priorities. Keep it
  concrete and current.
- `DESIGN.md` records durable technical design choices: graph semantics,
  search interfaces, MDL behavior, parity interpretation, loading boundaries,
  and other architecture. It is not a task list.
- `style.md` holds coding style and readability preferences. Keep coding rules
  there instead of duplicating them here.
- `alice-test-parity.md` is the evidence matrix for Python
  `william/tests/test_alice.py`: Python and CIWI rates, solutions, timings,
  status, and debugging notes. It should not become the roadmap.
- User-facing introductory material belongs in `README.md`, `tutorial.md`, or
  notebooks, not in the internal planning/design docs.

## Working Principles

- Treat compression behavior parity as the proof target. Do not chase private
  helper routine parity unless it is needed for propagation, graph search,
  bottleneck/MDL selection, delayed building, or Alice task behavior.
- Do not add task-specific recognizers, shortcuts, or extra operators to make
  parity tests pass. If Python Alice does not use the mechanism, it is not
  Alice parity evidence.
- Keep recognizer templates opt-in. They are useful local proposal operators
  and debugging baselines, but they must not operate in the default Alice
  parity harness.
- Port Python Wunderbaum/Alice directly first. Do not prematurely force it into
  the local bounded rewrite interface. Once parity is credible, adapt the
  working core into a bounded local `RewriteOperator`.
- Operator registries and operator sets must be injected by callers. Do not
  hardcode `ciwi.alice/basic-operator-registry` inside Wunderbaum or
  lower-level search machinery.
- This is greenfield. Do not preserve compatibility aliases or legacy
  codepaths for surfaces we control. Refactor all uses and remove stale paths.
- Prefer Clojure-native data and pure transforms, but do not use that as an
  excuse to change the algorithm being validated against Python.

## Test Discipline

- Use Python-scale Alice fixtures when claiming parity. If a test must be
  scaled down temporarily, document that explicitly in code and docs as a
  performance gap or implementation gap.
- Compare selected compression structure and achieved compression rate under
  the Python-compatible DL model.
- Treat performance timings as a diagnostic lens for catching implementation
  problems, not as cold-start product benchmarks. Collect and report Python and
  CIWI timings only under warm-start conditions: dependencies loaded, runtime
  initialized, and first-run/JIT/import effects excluded. Do not add separate
  cold-start columns unless explicitly requested.
- When a performance gap appears, diagnose the root cause before changing the
  algorithm. Valid fixes are translation fixes, missing Python mechanisms, data
  structure issues, or implementation performance issues. Invalid fixes are
  ad hoc recognizers or shortcuts.
- Run tests with the repo-local tooling:

```bash
./bin/test
```

Do not assume a global `clojure` binary is available.

## Git And Workspace

- The worktree may be dirty with unrelated user changes. Do not revert or stage
  unrelated files.
- Commit only explicitly intended files.
- Avoid destructive git commands unless the user explicitly requests them.
