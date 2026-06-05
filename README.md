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

To run the Clerk notebook:

```bash
./bin/clerk
```

Open `http://localhost:7777`. The runner watches `notebooks` and `src`, and
shows `notebooks/ciwi/notebook/core_machinery.clj` by default. Use
`./bin/clerk --browse` to let Clerk open the browser, or
`./bin/clerk --port 7878` if port 7777 is already in use.

For Cursive-driven REPL notebooks, start a REPL with the `:dev` alias and open:

```text
notebooks/ciwi/notebook/core_machinery.clj
notebooks/ciwi/notebook/alice_machinery.clj
```

The `:dev` alias includes `notebooks`, so those namespaces can be loaded and
their `(comment ...)` forms can be evaluated directly in Cursive.

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
