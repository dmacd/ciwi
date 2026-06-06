# CIWI Design

CIWI is a Clojure proof of concept for WILLIAM-style compression. The design is
not a transliteration of the Python object graph. CIWI uses persistent values,
pure graph transforms, explicit candidate data, and narrow search interfaces
that can be parallelized or replaced without changing graph semantics.

## Scope

The current proof target is compression behavior parity with Python
WILLIAM/Alice, especially the behavior exercised by
`william/tests/test_alice.py`. Routine-by-routine helper compatibility is not a
goal unless the helper is required for graph compression, propagation,
bottleneck/MDL selection, delayed graph construction, operator inversion, or
Alice task behavior.

The Alice parity operator basis is the Python `test_alice.py` basis: `Map`,
`Fix`, `BRange`, `Add`, `Mult`, `Negate`, `Concat`, `Repeat`, `GetItem`,
`Insert`, `CumSum`, `LessThan`, and `Equal`. Operators outside that basis can
exist for infrastructure tests, but they are not evidence for Alice parity.

## Basic Data Structure Examples

These examples show the concrete Clojure shapes that the design prose refers to.
The printed record names are not contractual APIs; the map keys and constructor
functions are the important part.

A value is data plus optional metadata used by search and propagation:

```clojure
(value/value [0 1 2 3]
             {:name "target"
              :spec :array-int
              :permeable? false})

;; #ciwi.value.Value{:data [0 1 2 3],
;;                   :name "target",
;;                   :spec :array-int,
;;                   :permeable? false,
;;                   :dummy? false}
```

An operator is a pure callable plus its inversion cases and description length:

```clojure
(op/operator
 {:id :double
  :conditions [[]]
  :commutative? false
  :call (fn [[x]] (* 2 x))
  :inverse (fn [output _cond-inputs cond]
             (when (= [] cond)
               [[(/ output 2)]]))
  :dl 1.0})
```

A graph is a `Graph` record whose `:nodes` map contains value-node and
operator-node maps. This graph says the target can be described either as its
raw value or as `(brange 0 4)`:

```clojure
(-> (graph/empty-graph)
    (graph/add-value :target [0 1 2 3])
    (graph/add-value :start 0)
    (graph/add-value :stop 4)
    (graph/add-operator :target-brange op/brange :target [:start :stop]))

;; Shape of (:nodes graph):
{:nodes
 {:target {:id :target
           :kind :value
           :value (value/value [0 1 2 3])
           :parents []
           :options [:target-brange]}
  :start {:id :start
          :kind :value
          :value (value/value 0)
          :parents [:target-brange]
          :options []}
  :stop {:id :stop
         :kind :value
         :value (value/value 4)
         :parents [:target-brange]
         :options []}
  :target-brange {:id :target-brange
                  :kind :operator
                  :operator op/brange
                  :parent :target
                  :children [:start :stop]}}}
```

Propagation memory is keyed by graph node id. Each entry carries the known value
at that node; `nil` data means the value is unknown, not that the node is absent:

```clojure
{:target (propagation/entry [0 1 2 3])
 :start (propagation/entry nil)
 :target-brange (propagation/->MapEntry false op/brange)}
```

A rewrite candidate is explicit edit data. Child refs can materialize raw
values, reuse existing local nodes, or inline nested generated edits:

```clojure
{:node-id :target
 :op op/brange
 :child-refs [(rewrite/value-ref 0)
              (rewrite/value-ref 4)]
 :before 18.0
 :after 7.0
 :delta -11.0
 :reason :brange}

{:kind :node :node-id :start}

{:kind :edit
 :op op/mult
 :child-refs [(rewrite/node-ref :range)
              (rewrite/value-ref 6)]
 :value [0 6 12 18]
 :dl 12.0}
```

A rewrite search result is structured data, not just a candidate vector:

```clojure
{:node-ids [:target]
 :requested-node-ids [:target]
 :rewrite-operator-ids [:bounded-enum]
 :candidates [...]
 :resource {:rewrite-operators-considered 1
            :nodes-requested 1
            :nodes-considered 1
            :candidates-proposed 12
            :candidates-accepted 1
            :generated-edits 64}
 :trace [{:kind :rewrite-operator
          :rewrite-operator-id :bounded-enum
          :resource {...}}]}
```

Alice tasks are plain records over target values, optional free values, and
threshold metadata:

```clojure
(def small-range-task
  (alice/compression-task
   [[0 1 2 3 4]]
   {:name "small-range"
    :threshold-rate 80.0
    :free-values []
    :solutions {:python "(Array[int] (cumsum ...))"}}))

(alice/task-domain "alice-core" [small-range-task])
```

Wunderbaum receives an injected registry and explicit operator declarations.
The declaration table is the near-term Clojure equivalent of Python operator
spec indexing:

```clojure
(wunderbaum/wunderbaum
 {:registry {:brange op/brange}
  :ops-with-counts [{:op :brange
                     :count 0
                     :input-specs [:int :int]
                     :output-spec :array-int}]})

{:dl 11.0
 :graph ...
 :selected {:target0 [:brange 0 4]}
 :memory {:target0 (propagation/entry [0 1 2 3])}}
```

## Graph Model

The graph keeps WILLIAM's bipartite shape:

- value nodes contain `ciwi.value/Value`
- operator nodes contain `ciwi.operator/Operator`
- a value node has zero or more operator `:options`
- an operator has one parent value and zero or more child values

Nodes are maps inside an immutable `Graph` record. Edges are ids rather than
object references. A graph edit returns a new graph.

`:options` are alternative descriptions for a value, not simultaneously chosen
subgraphs. The selected graph is derived by MDL selection. A global shared-DAG
minimizer must walk the selected option tree and charge shared selected value
nodes once; it must not trim by naively keeping every option under every node.

## Propagation

Propagation treats an entry whose value data is `nil` as unknown, matching
Python WILLIAM's `Value(None)` behavior. Such entries may be present for
shape/spec bookkeeping, but they do not trigger upward execution or downward
inversion. Nested propagation branches over operators that can fire, and partial
propagation can return the best currently known memory when no remaining
operator is executable.

Primitive operators are immutable `ciwi.operator/Operator` records with pure
forward and inverse functions. Conservative inverse behavior is preferred:
unknowns stay unknown, constraints validate known values, and unresolved local
equations produce no inverse result rather than fabricating symbolic values.

## Core Operators

Alice-basis sequence semantics follow Python `test_alice.py`: `brange` is
`(start stop)`, `repeat` is `(repetitions motif)`, `map` accepts an operator
keyword/record as its callable child, `insert` partitions output into inserted
indices/content/rest, and `cumsum` inverts by first differences.

CIWI also contains broader sequence-edit and boolean infrastructure where it is
useful for graph rewrite tests. Those operators are available to callers that
inject them, but they are not part of the default Alice parity claim unless the
Python Alice basis uses the same mechanism.

## Description Length

`ciwi.mdl/node-dl` computes the best local description for a value node:

```text
min(raw-value-dl,
    op-dl + sum(best child value dls) for each operator option)
```

This is the Clojure analogue of WILLIAM's bottleneck/minimum-description
selection, expressed as a memoized pure dynamic program. `ciwi.mdl/graph-dl`
projects selected descriptions from all graph roots and charges each selected
value node once, so shared selected sub-DAGs reduce global DL across a set of
roots.

Description-length caching is caller-owned rather than record-mutable. Python
WILLIAM caches `Value.desc_len()` on the `Value` object; CIWI keeps values
immutable and passes explicit cache atoms through scoring contexts instead.
`ciwi.value/desc-len-cached` memoizes existing `Value` records by identity,
matching Python's per-instance memoization and avoiding repeated large-vector
hash work on cache hits. Raw non-`Value` inputs keep value-based cache keys.
`ciwi.mdl/scoring-context` adds graph-local node-DL memoization. Wunderbaum
shares the value-DL cache across a candidate stream but creates fresh node
caches per graph.

These caches are explicit, caller-owned atoms rather than globals. Atomic
updates make them safe to share across threads, and duplicate races are
semantically harmless because cached values are deterministic. For parallel
Wunderbaum this gives two valid modes: each search can own a private cache to
avoid contention, or several searches can share a read-through value cache when
they operate over the same immutable `Value` objects. Node-DL caches remain
graph-local and should not be shared across graph versions. Future bounded
local Wunderbaum runs on a large graph should preserve `Value` object identity
for unchanged leaves, use per-search local caches by default, and optionally
layer a shared value-analysis/value-DL cache above them if recomputation
dominates contention.

CIWI's value description length is a direct port of Python WILLIAM's
`Value.desc_len(mode="use_gaussian")` model. Clojure vectors that are
rectangular and homogeneous in numbers, booleans, or strings are treated as
Python `np.ndarray` values; other sequential values are structural lists.
The port includes Python's continuous Elias helper `jelias`, scalar
integer/float precision handling, numeric-array Elias coding, 1D Gaussian
array coding, 2D multivariate Gaussian point coding, simple 3D channel-wise
Gaussian coding, boolean-array coding, string-array coding, and the default
non-Gaussian mode used by Python's lower-level `description.desc_len`.
The 1D vector path avoids flattening/copying already-flat vectors and computes
numeric Gaussian statistics with direct passes over the vector rather than
lazy filtered sequence chains. This is an implementation optimization, not a
symbolic summary: values are still fully realized and scored with the same DL
formula.

The exact Elias delta helper remains available as `ciwi.value/elias-discrete`
for enumerator index ordering. Alice/Wunderbaum operator declarations use
Python `TaskDomain`'s operator cost convention: all operator classes in the
injected Alice basis receive `ceil(jelias(number-of-operator-classes))` bits,
and delayed materialization preserves that declaration cost in the graph.

## Hashing

`ciwi.hashing` provides deterministic ordering and positive stable hashes for
native Clojure data. It handles nils, booleans, numbers, strings, keywords,
symbols, vectors, lists, sets, maps, records, and classes with type-aware
recursive keys. Value description length uses this ordering for maps and sets
so DL is independent of hash-map or set iteration order.

The same stable identity machinery is intended for library persistence,
successful-history deduplication, and learned template/composite identity.

## Rewrite Model

A rewrite is explicit data:

```clojure
{:node-id :out
 :op ciwi.operator/brange
 :child-refs [{:kind :value :value 0}
              {:kind :value :value 5}]
 :before 20.0
 :after 8.0
 :delta -12.0
 :reason :brange}
```

`child-refs` are normalized local graph edit operands. `{:kind :value ...}`
materializes a fresh raw child value node. `{:kind :node ...}` reuses an
existing local value node, including repeated references to the same child for
DAG-style sharing. `{:kind :edit ...}` is a generated child edit that is
materialized recursively when the parent rewrite is applied. Node refs inside
nested edit refs are checked against the original parent so a generated local
DAG cannot introduce a cycle.

Applying a rewrite adds a new operator option under the target value. It does
not replace or destroy the raw value. MDL selection decides whether the new
option is better.

## Search Interfaces

`ciwi.search/rewrite-search` composes explicit
`ciwi.rewrite/RewriteOperator` values and returns structured search data. If
`:rewrite-operators` is omitted, the operator set is empty. Callers must opt
into recognizers, graph-edit enumeration, Wunderbaum/Alice enumeration, or
future learned proposal operators explicitly.

Candidate generation is parallelizable over focused value nodes. The public
interface exposes resource data and traces rather than a bare candidate vector
so later template extraction, performance diagnosis, and amortization code can
inspect successful and failed searches without rerunning them.

Convergence keeps `:history` as the sequence of applied candidates and
`:steps` as structured per-step search records. Fixed-point runs retain the
terminal no-candidate search and resource summary.

## Bounded Local Mode

The bounded mode takes target value nodes and a `re-eval` budget. For each
target it builds a breadth-first neighborhood capped by that budget, generates
candidate rewrites only for value nodes in that neighborhood, and applies the
best DL-decreasing candidate. Repeating this process is the local analogue of
exhaustive compression.

The local candidate scorer only inspects focused values and candidate-local
predicted DL. Compound candidates may carry recursive predicted DL for
introduced children, but they do not run the full decoder. Work is intended to
scale with target leaves, neighborhood budget, enabled rewrite operators, and
explicit generation limits rather than total stream history.

## Opt-In Recognizer Templates

Local recognizer templates implement `ciwi.rewrite/RewriteTemplate` and can be
wrapped with `rewrite/template-operator`. They are intentionally disabled by
default. Tests that exercise them pass `(rewrite/primitive-template-operator)`
explicitly.

Recognizer templates are useful proposal operators and debugging baselines, but
they are not evidence for Alice parity. In particular, the primitive template
sweep must not invent unconditioned `map :negate` or arbitrary `concat` splits,
because Python `Map` requires a conditioned callable and Python `Concat`
requires a conditioned side.

## Clojure Graph Literals

CIWI does not port Python's string sexpr parser directly. Graphs can be built
from Clojure data:

```clojure
[:add 3 4]
[:concat [:brange 0 3] [:repeat 2 [:x]]]
```

A vector or list is an operator form only when its head resolves through the
operator registry. Other vectors are literal data. Use `ciwi.dsl/literal` to
force literal interpretation of operator-looking data.

## Conditions And Composites

Python WILLIAM's condition machinery is represented as pure Clojure data in
`ciwi.conditions`. Conditions are vectors of root-leaf indices with set
semantics for redundancy checks. The port keeps WILLIAM's redundancy behavior
where it is required for comparable search behavior, but graph extraction walks
CIWI graphs directly instead of reconstructing Python sexprs.

Composite operators are graph-backed `ciwi.operator/Operator` values. A
composite captures a CIWI graph, its root, and optionally a set of constant leaf
indices. Calls and inverses run through the existing propagation engine, while
the public operator interface remains the same as primitive operators.

Composite literals support `[:input id sample]` placeholders. Reusing an input
id ties multiple graph leaves to one operator argument, which gives CIWI a
native way to express DAG-style composites such as `x*x + y`. Non-input leaves
in a placeholder template are captured as constants. Composite inversion also
uses these input groups: all leaves in a repeated group must infer the same
value.

`ciwi.fix/fix-first` is the Clojure equivalent of Python WILLIAM's `Fix`
operator. It captures the first input of any runtime `Operator` and returns a
new runtime `Operator`, charging the captured value in the returned operator's
DL.

## Library Loading

Learned and built-in artifacts should share native-looking runtime interfaces,
but their persistence and loading paths are separate.

Composite definitions are graph-shaped EDN maps that hydrate into
`ciwi.operator/Operator` values. Rewrite-template definitions hydrate into
`ciwi.rewrite/RewriteTemplate` values. A small `load-library` orchestrator can
load both, but the internal branches stay separate because composites and
templates have different functional character.

The inner rewrite loop receives runtime `RewriteOperator` values, so it does not
care whether a rule was built in, hand-written, loaded from EDN, or produced by
an outer library-compression/amortization phase. Later source rendering,
compilation, and dynamic loading should preserve that boundary.

## Enumeration Components

`ciwi.enumerative-rewrite` is a local forward-expression enumerator. For each
focused value node, it enumerates expression trees over a configured operator
set and literal generator, bounded by `:max-depth`, `:max-generated`, and
`:beam-width`. The beam keeps the cheapest expressions by predicted expression
DL with deterministic form tie-breaking. Local neighborhood values can seed the
beam so accepted candidates reuse existing nodes instead of rematerializing
duplicate children.

`ciwi.graph-rewrite` is the graph-native bounded rewrite operator. It enumerates
local edits directly by choosing a focused parent, a root operator, child
operands from DAG-safe local node refs and literal value refs, and optional
nested generated edits. It emits normal rewrite candidates with `:child-refs`
and resource metadata.

`ciwi.enumerator/effective-dl` ports the useful Dirichlet-process posterior
predictive score from Python WILLIAM's DAG enumerator. It is a pure
usage-adjusted ranking helper and does not couple local graph rewriting to
Python's mutable DAG heap.

## Wunderbaum

Python WILLIAM's Wunderbaum is the mechanism CIWI needs for core Alice parity.
It is not a local value recognizer. It is an operator-DAG enumeration and
propagation path: enumerate candidate DAG shapes from the Alice operator basis
under resource bounds, respect operator conditions and inverses, reuse
conditioned or local values where possible, delay graph materialization until a
candidate is selected, and score candidates by graph DL.

The first CIWI Wunderbaum slice lives in `ciwi.wunderbaum` and intentionally
stays outside `ciwi.search/RewriteOperator` while the straight parity port is
being validated. It uses injected operator registries, operator/count
declarations with explicit input/output specs, Python-style generalized
conditions, effective operator DL from usage counts, graph-wide node-tuple
enumeration, delayed DAG build, operator inversion, and MDL-selected
materialized results. The implementation should remain functional and avoid
mutable frontier/state machinery unless a concrete performance case is made and
approved.

Python's DAG enumerator adds deterministic sub-microbit jitter to operator
description lengths when building task-domain graph elements:
`default_rng(42).random() * 1e-6`. CIWI keeps those values in the Alice
declaration table and passes them through `ciwi.enumerator/effective-dl`.
This does not change the DL model in any meaningful compression sense, but it
does change the order of otherwise-tied candidates. It is therefore part of the
straight parity port, not a CIWI-specific heuristic.

Node tuple enumeration follows Python's best-first ordering by tuple index DL,
but uses persistent Clojure data. It starts with the all-zero tuple for each
allowed tuple length, pops the cheapest tuple, and enqueues the one-index
successors that stay within the current graph's value-node order. This avoids
building and sorting the full cartesian product for every graph expansion.

`wunderbaum/iterate` yields scored candidate summaries without selected target
expressions. Most candidates are only compared and discarded, so selected
expressions are computed explicitly with `wunderbaum/realize-selected` when a
caller needs to inspect or accept a candidate.

When `:threshold-dl` is supplied, `wunderbaum/iterate` mirrors Python's
`threshold_dl` behavior: it still expands materialized graphs that do not meet
the threshold, but it does not yield them to the caller, and it stops after the
first yielded graph below the threshold. Alice uses this for one-percent
compression steps so yielded candidate counts correspond to accepted
compression candidates rather than every explored frontier materialization.

`ciwi.alice-wunderbaum` is the Alice-facing greedy runner over that core with
an explicit declaration table for the Python `test_alice.py` operator basis. It
requires an injected registry and does not change the default `ciwi.alice`
no-recognizer harness.

`run-greedy-task` mirrors Python `GreedyAlice`: sort raw leaves by DL, compress
the largest worthy leaf, accept the first Wunderbaum candidate above
`:min-compression-rate` (default 1%), splice that selected expression into the
task tree, and repeat until the task threshold is reached or no worthy leaf can
improve. `run-compression-step` exposes the same mechanism capped at one greedy
step for diagnostics.

For each leaf-local compression step, the other current task-tree leaves are
passed to Wunderbaum as dummy free values. This matches Python
`initialize_free_values`, where graph leaves other than the focused leaf can be
used as zero-cost anchors and then glued back into the task graph. In CIWI's
current tree summary this appears as a zero-DL raw reference with the same
concrete value; it is the near-term equivalent of Python's shared-DAG sexpr
variables such as `_1`.

Only existing task-tree leaves get that zero-DL anchor treatment. Explicit task
free values and Alice's synthetic defaults (`1` and `1.5`) are normal permeable
values: they are skipped when computing the original focused target DL, but if
the selected compression uses them as leaves, their value DL is charged. This
matches Python's local bottleneck accounting and matters for rows such as
`sprinkled`, where the scalar `1` is introduced by the default free-value
mechanism rather than by an existing graph leaf.

During those focused steps, Wunderbaum also carries an explicit root order.
The primary target leaf is first and dummy/free values follow it. Python keeps
this order implicitly through `Graph.nodes` section order; CIWI cannot rely on
hash-map or root-set iteration for the same semantics. Attachment validation
therefore receives `:primary-root-id`, `:free-root-ids`, and `:root-order`
explicitly. Output-conditioned attachments must be below the primary target,
input-conditioned attachments must be above a free root, and the special
Python rule for an already operator-carrying free root is preserved. Each graph
expansion computes an attachment context once, including primary descendants
and free-root ancestors, so attachment checks do not repeatedly walk the same
graph.

Focused Alice steps score only the primary target root, not the whole temporary
graph containing dummy/free values. CIWI exposes this as Wunderbaum's
`:score-target-count`, which `ciwi.alice-wunderbaum` sets to `1` for a
leaf-local compression step. Without this, a candidate can look worse or better
for the wrong reason because free anchors are included in the temporary graph's
DL.

Delayed materialization treats non-executable operator calls and inverses as no
candidate, matching Python's `exec_errors` behavior. Operator inverses must
therefore be conservative: a shape mismatch or impossible condition yields no
inverse rather than a `nil` child. Unknown `nil` values can exist in propagation
memory for bookkeeping, but they are not a valid way to make a selected
compression artificially cheap.

Python's delayed DAG builder also filters inverse-generated values that already
exist in memory and yields only the first unseen materialization for a delayed
attachment. CIWI's delayed builder mirrors that by comparing generated values
using stable value keys and de-duplicating by the whole root set rather than by
only the newly generated root. Stable value keys are cached by `Value` identity
inside a Wunderbaum candidate stream, matching Python's cached `Value.hash`
behavior without mutating the records.

Delayed materialization also validates declaration specs after inversion or
forward execution. A generated inverse child must conform to the selected
declaration's corresponding input spec, and a forward-produced value must
conform to the declaration output spec. This mirrors Python's delayed DAG build
check that inverse outputs match node specs. The check matters because one
runtime operator can have several declarations: for example, the generic
`getitem` inverse can produce an integer index vector, but that result must not
be accepted through the cheaper boolean-mask declaration.

Materialized forward outputs and inverse-generated children carry inferred
specs on their immutable `Value` records. This is the non-mutating equivalent
of Python `Value.spec` caching and prevents repeated full-array scans during
node-condition indexing. The unconditioned `getitem` inverse computes its
source vector and inverse index vector in one pass, which is the same general
inverse as Python's `np.unique(..., return_inverse=True)` path but avoids the
old quadratic `.indexOf` scan.

After the straight port proves parity, the same core can be adapted into a
local resource-bounded `RewriteOperator` with explicit budgets for frontier
pops, materializations, candidate count, propagation work, and local graph edit
size. That adaptation is a separate design phase from the parity port.

## Numeric Search Operators

Optimizers implement `ciwi.optimize/SearchOperator`. The first port keeps a
Newton/pattern-search style optimizer and an adaptive grid optimizer close to
Python WILLIAM for comparison, but callers see them as recursive search
operators over explicit state. This lets later work replace numeric search with
graph rewrite search, gradient descent on differentiable subgraphs, or mixed
specialized search without changing the surrounding compression loop.

## Structural Graph Operations

Graph comparison is based on canonical structural keys over immutable node ids.
Commutative operators sort child structural keys, while noncommutative
operators preserve child order. This gives Clojure-native replacements for
Python `resembles`, `subgraph`, `depth`, and sexpr round-trip tests without
adopting Python's mutable object identity assumptions.

## Compression API

`ciwi.compress` is the public compression loop over the lower-level search
machinery. `compress-exhaustive` searches every value node until a fixed point.
`compress-bounded` searches only bounded neighborhoods around target value
nodes. Both return the final graph, history, DL, stop reason, and selected
expressions derived from the MDL choice tree.

`ciwi.alice/run-task-comparison` lifts those loops to Alice-style tasks by
running both modes over the same task graph and comparing selected target
expressions and global DL. The parity-focused Alice/Wunderbaum path remains
separate so the Python port can be validated before it is folded into the local
bounded rewrite model.
