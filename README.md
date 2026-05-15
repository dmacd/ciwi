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

