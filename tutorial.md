# CIWI Clerk Notebook Tutorial

This project includes a Clerk notebook that walks through the core CIWI data
structures and rewrite machinery. Clerk is a Clojure-native notebook tool: the
notebook is an ordinary Clojure namespace with markdown comments, evaluated from
top to bottom in the same Clojure process as the project code.

The notebook lives at:

```text
notebooks/ciwi/notebook/core_machinery.clj
```

There is also a Cursive REPL-oriented Alice testbed:

```text
notebooks/ciwi/notebook/alice_machinery.clj
```

## What It Covers

The notebook is meant to be read and edited interactively. It demonstrates:

- `ciwi.value/Value` records and description lengths
- `ciwi.operator/Operator` records and operator application
- graph construction through `ciwi.dsl/from-expr`
- the raw graph node shape for value and operator nodes
- MDL selection with `ciwi.mdl/node-dl` and `selected-expression`
- primitive template rewrite candidates
- exhaustive convergence with `ciwi.search/exhaustive-converge`
- bounded convergence with `ciwi.search/bounded-converge`
- compression wrappers in `ciwi.compress`
- bounded enumerative rewrite with `ciwi.enumerative-rewrite`
- graph rewrite with local DAG node reuse through `ciwi.graph-rewrite`

## Running Clerk

Use the repo-local wrapper:

```bash
./bin/clerk
```

Then open:

```text
http://localhost:7777
```

The runner starts Clerk, watches `notebooks` and `src`, and shows
`notebooks/ciwi/notebook/core_machinery.clj` by default.

Useful variants:

```bash
./bin/clerk --browse
./bin/clerk --port 7878
./bin/clerk notebooks/ciwi/notebook/core_machinery.clj
```

The first run may download Clerk and its transitive dependencies into the
repo-local Maven cache under `.m2/repository`.

## Project Setup

The Clerk setup is wired through the `:clerk` alias in `deps.edn`:

```clojure
:clerk {:extra-paths ["notebooks" "test"]
        :extra-deps {io.github.nextjournal/clerk {:mvn/version "0.18.1158"}}
        :main-opts ["-m" "ciwi.clerk"]}
```

The normal development REPL also includes notebooks:

```clojure
:dev {:extra-paths ["dev" "notebooks" "test"]}
```

The launcher is:

```text
bin/clerk
```

The server entrypoint is:

```text
notebooks/ciwi/clerk.clj
```

Generated Clerk and clj-kondo cache directories are ignored by git:

```text
.clerk/
.clj-kondo/.cache/
```

## Cursive Setup

In Cursive, open the Deps tool window and enable the `:dev` alias for ordinary
REPL exploration. That puts `dev`, `notebooks`, and `test` on the IDE classpath.
Enable `:clerk` only when you want Cursive to resolve Clerk itself.

If Cursive asks for a Clojure executable, use the repo wrapper:

```text
/home/daniel/projects/snet/ciwi/bin/clojure
```

If `defn`, `ns`, or project namespaces are unresolved, check these in order:

1. A Project SDK/JDK is selected in `File -> Project Structure`.
2. `deps.edn` is added as a Deps project.
3. The Deps project has been refreshed.
4. The `:dev` alias is enabled when editing/running the REPL notebooks.
5. The `:clerk` alias is enabled if you want Clerk-specific symbols resolved.

## Editing Workflow

Edit `notebooks/ciwi/notebook/core_machinery.clj` in Cursive or another editor.
Clerk will re-evaluate the notebook when the watched files change.

For Cursive-only notebook work, open
`notebooks/ciwi/notebook/alice_machinery.clj`, load the namespace in the REPL,
and evaluate forms inside the `(comment ...)` blocks one at a time. That file
defines `case-names`, `case-data`, `compare-case`, `inspect`, `summary`,
`step-rows`, `history-rows`, `graph-state`, and `pp` locally in its own
namespace, so nothing needs to be added to `dev/user.clj`.

Good experiments:

- lower `:max-depth` in the square enumerator from `2` to `1`
- reduce `:max-generated` until a rewrite disappears
- change `:re-eval-budget` and inspect bounded neighborhoods
- add `{:op :add :arity 2}` to the enumerator operator list
- replace the sample vectors with your own data and compare candidate deltas

## Verification Commands

Run the regular tests:

```bash
./bin/test
```

Load the notebook namespace without starting Clerk:

```bash
./bin/clojure -Sdeps '{:aliases {:notebook-check {:extra-paths ["notebooks"]}}}' \
  -M:notebook-check \
  -e "(require 'ciwi.notebook.core-machinery)"
```

Load the Alice REPL notebook namespace:

```bash
./bin/clojure -M:dev -e "(require 'ciwi.notebook.alice-machinery)"
```
