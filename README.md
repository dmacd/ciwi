# CIWI

Prototype workspace for a Clojure implementation of incremental WILLIAM.

## Local Tooling

This checkout uses a repo-local Clojure CLI install under `.tooling/clojure`.
Use the wrapper scripts so Clojure config and Maven dependencies stay inside
this workspace.

```bash
./bin/clojure -M -m ciwi.core
./bin/test
```

If `.tooling/clojure` is missing, run:

```bash
./scripts/bootstrap-clojure
```

## Prototype Slice

The first prototype model mirrors WILLIAM's bipartite graph shape:

- value nodes hold `ciwi.value/Value`
- operator nodes hold `ciwi.operator/Operator`
- operators point up to one parent value and down to child values
- propagation can fire up from known inputs or fire down from a known output
  plus invertible inputs

The initial operator set is deliberately small: `add` and `negate`. The tests
include a small golden case transcribed from
`../william/william/tests/test_propagation.py`.
