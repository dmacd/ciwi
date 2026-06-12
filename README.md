# CIWI

CIWI is a Clojure proof of concept for WILLIAM-style incremental compression,
using [Python WILLIAM](https://gitlab.com/occam_ua/william) as the behavioral
baseline. It is not a Python API clone. The goal is to make the core machinery
easy to inspect, test, and evolve: values, operators, graph construction, MDL
selection, propagation, delayed graph materialization, Wunderbaum/Alice search,
bounded rewrite, and exhaustive rewrite are represented with ordinary Clojure
data and small explicit interfaces.

For more context, see [DESIGN.md](./DESIGN.md) for the current architecture and
[PLAN.md](./PLAN.md) for the active implementation roadmap.

## Status

CIWI is pre-alpha research code and still very much under construction. It is
now past the first useful Python WILLIAM/Alice baseline: the active
`ciwi.alice.wunderbaum` path has Python-scale compression evidence for all
sequence rows in Python `william/tests/test_alice.py`, using the Python Alice
operator basis and Python-compatible value description length model.

That does not mean CIWI is a full WILLIAM replacement.

- Core Alice sequence tasks are covered through the straight Wunderbaum port:
  `simple_repeat`, `insert_repeat`, `insert_repeat2`, `insert_repeat3`,
  `repeat_with_noise`, `simply_linear`, `sprinkled`, `increasing_runs`, and
  `map_negate`.
- Core WILLIAM machinery has fixture coverage for bottleneck/MDL selection,
  propagation, delayed graph building, standalone Wunderbaum iteration,
  condition extraction, and most compression-relevant composite behavior.
- Optimizer-backed graph search covers the matrix-regression Alice pipeline
  rows and several supporting optimizer/helper behaviors. Some standalone
  numeric rows are still behavior-level CIWI fixtures rather than exact NumPy
  fixture captures.
- Parallel Wunderbaum/Alice is opt-in. The Python-shaped partitioned path has
  completion coverage for the sequence rows plus deterministic regression and
  matrix rows, but this is not a broad performance claim or full Python
  `all_tasks` parallel parity claim.
- The `:global-best-first` parallel strategy, Iris classifier rows, dense DJL
  backend, and local bounded rewrite machinery are active experiments or
  supporting infrastructure, not baseline parity claims unless called out in
  the evidence docs.

APIs, data shapes, search behavior, and performance characteristics may still
change quickly.

The main evidence records are:

- [alice-test-parity.md](./alice-test-parity.md) for plain Alice/Wunderbaum
  sequence compression.
- [PYTHON-TEST-ROADMAP.md](./PYTHON-TEST-ROADMAP.md) for the Python test-suite
  parity map and out-of-scope decisions.
- [optimizer-graph-search-parity.md](./optimizer-graph-search-parity.md) for
  numeric optimizer-backed graph search.
- [parallel-performance-scaling.md](./parallel-performance-scaling.md) for
  early parallel timing and scheduler experiments.

## Terminology

- `ciwi.alice` is shared Alice task/domain data and operator-basis plumbing.
- `ciwi.alice.wunderbaum` is the active Python Alice parity path.
- `ciwi.alice-legacy` is the old local exhaustive/bounded baseline harness; it
  is retained to prove local compression does not use recognizer shortcuts by
  default.
- Primitive recognizer templates are opt-in proposal/debugging tools, not
  default rewrite machinery and not Alice parity evidence.

## Local Tooling

This checkout uses a repo-local Clojure CLI install under `.tooling/clojure`.
Use the wrapper scripts so Clojure config and Maven dependencies stay inside
this workspace.

```bash
./bin/test
```

For an interactive development REPL:

```bash
./bin/clojure -M:dev
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

## Current Shape

The current prototype mirrors WILLIAM's bipartite graph shape:

- value nodes hold `ciwi.value/Value`
- operator nodes hold `ciwi.operator/Operator`
- operators point up to one parent value and down to child values
- propagation can fire up from known inputs or fire down from a known output
  plus invertible inputs

On top of that graph model, CIWI has MDL selection, graph-backed composites,
local rewrite operators, bounded and exhaustive compression loops, and the
Alice/Wunderbaum path that is currently driving parity work. The graph rewrite
operator is the active local bounded rewrite mechanism; the older standalone
enumerative rewrite path has been removed.

The active Python baseline path is:

```text
ciwi.alice task data
  -> ciwi.alice.wunderbaum greedy runner
  -> ciwi.wunderbaum frontier/materialization/search
  -> ciwi.mdl Python-style graph/value scoring
```

`ciwi.alice-legacy` is retained only as a local no-recognizer baseline harness.
Recognizer templates are proposal/debugging tools and are disabled by default;
they are not Alice parity evidence.

## License

CIWI uses the same terms as WILLIAM: Creative Commons
Attribution-NonCommercial 4.0 International (CC BY-NC 4.0). You may use, share,
and modify this code for non-commercial purposes with attribution. See
[LICENSE.md](./LICENSE.md) for the license notice and canonical legal terms.


## But ... _why_?

I'll admit, this started as one of those late-night "I wonder what would happen
if I asked Codex to ..." experiments. I didn't even look at the
results for three weeks. Then I got curious about it, and a little
voice told me to actually pick it up and play with it. I was instantly
smitten again with the elegance and joy of clojure, and became determined to
finish the port and see what we could build on top of it. As a bonus, I
tend understand things better by building them, or at least coaching someone 
else to build them.

So that's the emotional rationale. The more technical motivation is Clojure 
seems like a more natural fit for experimenting
with more sophisticated local, incremental, and bounded graph rewrites due
to its emphasis on immutability and use of efficient persistent data
structures, and the avenues for scalability that those properties unlock.
Plus it's just a lot more fun to read and write than python. The reasons
that's true for humans happen to mostly map on to LLMs as well.

The main barriers to using clojure for ML research in the past (for me
anyway) have been lack of mature integrations with ML frameworks and
associated tools. However given how thoroughly excited coding agents are to
write and test reams of boring interop code, this issue no longer drives my
language preferences the way it once did.
