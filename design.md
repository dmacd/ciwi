# CIWI Design Notes

CIWI is a Clojure proof of concept for WILLIAM-style incremental compression.
The goal is not to transliterate the Python object graph. The Clojure version
uses persistent values, pure graph transforms, explicit candidate data, and
parallelizable search stages.

## Porting Scope

The Python WILLIAM tree contains several layers:

- library values, operators, inverses, and description lengths
- bipartite value/operator graphs
- propagation through known outputs and inputs
- graph description length / bottleneck selection
- enumerative compression and higher-level ALICE tasks

CIWI is being built from the core outward:

1. values/operators/propagation
2. persistent graph MDL
3. bounded local graph rewrites
4. exhaustive search as a reference implementation
5. mirrored WILLIAM core tests and incremental convergence tests

The current implementation focuses on 1-4 for simple numeric and sequence
operators. Larger WILLIAM domains such as canvas geometry, type unification,
classification, and rendering should be layered on top after the rewrite engine
stabilizes.

## Graph Model

The graph is still WILLIAM's bipartite shape:

- value nodes contain `ciwi.value/Value`
- operator nodes contain `ciwi.operator/Operator`
- a value node has zero or more operator `:options`
- an operator has one parent value and zero or more child values

Unlike Python WILLIAM, graph nodes are maps inside an immutable `Graph` record.
Edges are ids, not object references. A rewrite returns a new graph.

## Propagation

Propagation treats an entry whose value data is `nil` as unknown, matching
Python WILLIAM's `Value(None)` behavior. Such entries may be present in memory
for shape/spec bookkeeping, but they do not trigger upward execution or downward
inversion. Nested propagation still branches over operators that can fire, and
partial propagation can return the best currently known memory when no remaining
operator is executable.

## Core Operators

Primitive operators are ordinary immutable `ciwi.operator/Operator` records with
pure forward and inverse functions. The current sequence-edit set includes
`getitem` and `setitem` for Clojure sequences/vectors with nonnegative integer
indices, explicit index vectors, and boolean masks. Their inverses return
partially known source templates using `nil`, `""`, or `false` as lightweight
missing sentinels depending on value type. This is enough for the core WILLIAM
sequence rewrite tests without importing Python/NumPy slicing semantics.
Shape-rich indexing should be added later as data-driven operators or composed
rewrite rules rather than hidden mutable array behavior.

## Description Length

`ciwi.mdl/node-dl` computes the best local description for a value node:

```text
min(raw-value-dl,
    op-dl + sum(best child value dls) for each operator option)
```

This is the Clojure analogue of WILLIAM's bottleneck/minimum-description
selection, but expressed as a memoized pure dynamic program. Current parity tests
assert selected structure, selected operator order, alternative-option choice,
and relative DL behavior. They intentionally do not assert Python's exact
floating DL constants because CIWI uses a simpler Clojure-native data codec.
Root-summed `graph-dl` also charges a selected shared sub-DAG once per root; a
future global shared-DAG minimizer should account for selected shared structure
only once across the projected solution.

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
materializes a fresh child value node. `{:kind :node ...}` reuses an existing
local value node, including repeated references to the same child for DAG-style
sharing. Node refs are valid only when they keep the value dependency graph
acyclic. Applying the rewrite does not replace or destroy the original value. It
adds a new operator option under that value. The original raw value remains
available, and MDL decides whether the new option is better.

`ciwi.delayed-builder` covers the complementary graph-construction path used by
Python WILLIAM's delayed DAG builder tests. It attaches a selected graph element
to conditioned value nodes, either above known inputs or below a known output via
operator inversion. Conditioned nodes may repeat, so graph construction supports
DAG-shared inputs such as `mult(d, d)` without special mutable clone logic.

This is important for incremental learning: every local proposal is reversible
by selection, and graph history can be kept or pruned separately.

## Incremental Bounded Mode

The bounded mode takes target value nodes and a `re-eval` budget. For each
target it builds a breadth-first neighborhood capped by that budget, generates
candidate rewrites only for value nodes in that neighborhood, and applies the
best DL-decreasing candidate. Repeating this process is the local analogue of
exhaustive compression.

The candidate scorer only inspects the target value and template-local predicted
DL. Compound templates may use a recursive predicted DL for their own introduced
children, but they do not run the full decoder. Work scales with:

- number of target leaves
- neighborhood budget
- rewrite operators enabled for the local search

not total stream history.

## Parallel Search

Candidate generation is embarrassingly parallel over value nodes. The search
namespace exposes `:parallel? true`, currently implemented with a short-lived
Java executor over bounded candidate work items. The interface is deliberately
narrow so it can be replaced with reducers, agents, virtual-thread executors,
or core.async workers without changing rewrite semantics.

`ciwi.search/rewrite-search` composes explicit `ciwi.rewrite/RewriteOperator`
values and returns structured search data rather than a bare candidate vector:

```clojure
{:rewrite-operator-ids [:primitive-templates :bounded-enum]
 :candidates [...]
 :resource {:rewrite-operators-considered 2
            :nodes-considered 4
            :templates-considered 20
            :candidates-proposed 7
            :candidates-accepted 2
            :generated-expressions 128
            :generated-edits 64}
 :trace [{:kind :rewrite-operator
          :rewrite-operator-id :primitive-templates
          :resource {...}}
         {:kind :template-proposal
          :node-id :out
          :template-id :brange
          :candidate-count 1
          :accepted-count 1}]}
```

Convergence keeps `:history` as the sequence of applied candidates and adds
`:steps` for applied search steps. Each step contains the chosen candidate, the
full search result, per-step resource accounting, trace data, and DL before and
after applying the rewrite. Fixed-point runs also retain `:terminal-search` and
`:terminal-resource` for the final no-candidate search.

## Current Rewrite Templates

The first templates are intentionally simple but useful for proving the loop:

- arithmetic integer ranges: `(brange start n)`
- constant repetitions: `(repeat value n)`
- sequence concatenation: `(concat left right)`
- scaled ranges: `(mult (brange 0 n) step)`
- affine sequences: `(add (mult (brange 0 n) step) start)`

Each template can be detected from a local value. Templates are intentionally
small local candidate rules; search sees them through `rewrite/template-operator`.
Children introduced by one rewrite can themselves be rewritten in later bounded
passes, which is the basic mechanism used by the convergence tests.

## Clojure Graph Literals

CIWI does not port Python's string-based sexpr parser directly. Graphs can be
built from Clojure data:

```clojure
[:add 3 4]
[:concat [:brange 0 3] [:repeat :x 2]]
```

A vector or list is an operator form only when its head resolves through the
operator registry. Other vectors are literal data. Use `ciwi.dsl/literal` to
force literal interpretation of operator-looking data.

## Conditions and Composites

Python WILLIAM's condition machinery is now represented as pure Clojure data in
`ciwi.conditions`. Conditions are vectors of root-leaf indices with set semantics
for redundancy checks. The port keeps WILLIAM's redundancy behavior so existing
golden cases remain comparable, but graph extraction walks CIWI graphs directly
instead of reconstructing Python sexprs.

Composite operators are graph-backed `ciwi.operator/Operator` values. A
composite captures a CIWI graph, its root, and optionally a set of constant leaf
indices. Calls and inverses run through the existing propagation engine, while
the public operator interface remains the same as primitive operators. Two-input
composites infer `:commutative?` by comparing the symbolic graph before and
after swapping input placeholders; this uses operator-level commutativity and
does not depend on sample literal equality.

Composite literals support `[:input id sample]` placeholders. Reusing an input id
ties multiple graph leaves to one operator argument, which gives CIWI a native
way to express DAG-style composites such as `x*x + y` without porting Python's
sexpr parser. Non-input leaves in a placeholder template are captured as
constants. Composite inversion also uses these input groups: all leaves in a
repeated group must be inferred with the same value, and unresolved local
equations produce no inverse result rather than fabricating a symbolic solution.

`ciwi.fix/fix-first` is the Clojure equivalent of Python WILLIAM's `Fix`
operator. It captures the first input of any runtime `Operator` and returns a
new runtime `Operator`, charging the captured value in the returned operator's
DL. The higher-order `ciwi.fix/operator` lets Clojure graph literals produce
fixed operators directly, so tests can cover the old sexpr use case without
adopting the Python parser or mutable composite clone path.

Local template rules still implement `ciwi.rewrite/RewriteTemplate`, but the
search layer no longer treats templates as the top-level unit. Search composes
`ciwi.rewrite/RewriteOperator` values. `rewrite/template-operator` wraps any
set of local templates into one operator, while other mechanisms such as bounded
enumeration can implement `RewriteOperator` directly.

The first bundled composite template is `:linear-sequence`, a single
graph-backed operator equivalent to `(add (mult (brange 0 n) step) start)`. To
use it in search, callers load the template and explicitly wrap it with
`rewrite/template-operator`. Tests also inject a separate `:square-range`
composite template to keep the path generic rather than special-cased. This is a
deliberate stepping stone toward treating composites, local graph rewrites, and
specialized numeric optimizers as recursive graph search operators rather than a
separate mutable object hierarchy.

## Library Loading

Learned and built-in artifacts should share a native-looking runtime interface,
but they also need durable definitions. `ciwi.library` is the near-term shim for
that split. Composite definitions and rewrite-template definitions are ordinary
EDN maps with metadata, but their loading paths are separate: composite loading
hydrates graph-shaped definitions into `ciwi.operator/Operator` values, while
template loading hydrates local matcher/operator definitions into
`ciwi.rewrite/RewriteTemplate` values. A small `load-library` orchestrator wires
the two paths together when both definition types are present. The inner rewrite
loop receives runtime `RewriteOperator` values, so loaded templates are wrapped
with `rewrite/template-operator` at the search boundary. Search therefore does
not care whether a rule was built in, hand-written, or produced by an outer
library-compression/amortization phase.

The loader is deliberately small: it supports graph-backed composites, tagged
local matchers, EDN persistence, and caller-provided runtime templates. Later we
can add rendering to Clojure source, compilation, and dynamic loading without
changing the local search loop's contract.

## Bounded Enumeration

`ciwi.enumerative-rewrite` adds the first general local enumeration rule. It is
a first-class `RewriteOperator`: for each focused value node, it enumerates
expression trees over a configured operator set and literal generator, bounded by
`:max-depth`, `:max-generated`, and `:beam-width`. When an enumerated
expression evaluates to the focused node value, it emits a normal local rewrite
candidate using the expression's root operator and child values, with predicted
DL taken from the whole enumerated expression. Repeated bounded search can then
compress those introduced child values through ordinary local rewrites. The beam
keeps the cheapest expressions by predicted expression DL, with deterministic
expression-form tie-breaking.

When search supplies a local neighborhood, the enumerator also seeds the beam
with existing local value nodes. Matching candidates then reuse those nodes via
`:child-refs` instead of rematerializing duplicate children. Candidate metadata
records the enumeration resource usage, including generated expression count,
depth reached, beam width, and the literal/local seed counts used for that
bounded enumeration. The same resource map is surfaced at the operator, search,
and convergence-step layers so later template extraction and amortization code
can inspect successful and failed local searches without rerunning them.

This is intentionally not a corpus-level DreamCoder phase. Outer control loops
can decide which operators, composites, literal generators, and budgets to pass
to this local enumerator. `ciwi.enumerator/count-trees` mirrors Python
Wunderbaum's pure tree-counting helper for estimating typed expansion sizes from
plain spec transition maps.

## Graph-Edit Enumeration

`ciwi.graph-rewrite` is the graph-native bounded rewrite operator. Instead of
starting from expression trees and converting matches into graph edits, it
enumerates local edits directly:

1. choose a focused parent value node
2. choose a root operator
3. choose child operands from DAG-safe local node refs and literal value refs
4. evaluate the candidate edit and score its predicted DL
5. emit a normal rewrite candidate with `:child-refs`

The same depth, generation, and beam controls apply: `:max-depth`,
`:max-generated`, and `:beam-width`. Depth greater than one means generated edit
outputs can become operands for later generated edits. If such a generated edit
is used as a child, the emitted graph candidate uses a fresh value ref for that
child; later bounded passes can compress that child value through ordinary local
rewrites. If an existing local node is cheapest for a value, the candidate keeps
a `{:kind :node ...}` ref, preserving DAG sharing.

Graph-edit candidates carry `:edit-form`, `:edit-depth`, and candidate-local
resource metadata. Operator traces include generated edit count, matched edit
count, accepted count, and termination reason, which is the information later
outer loops need for extracting successful templates or training amortized
proposal mechanisms.

## Numeric Search Operators

Optimizers implement `ciwi.optimize/SearchOperator`. The first port keeps a
Newton/pattern-search style optimizer and an adaptive grid optimizer close to
Python WILLIAM for comparison, but callers see them as recursive search
operators over explicit state. Newton search supports mixed integer/float axes
with dimension-appropriate finite differences. Adaptive grid search carries its
scale in the search state and can optionally sample the joint Cartesian grid
when coordinate-wise moves stall, matching the Python optimizer's
joint-improvement behavior. This lets later work replace numeric search with
graph rewrite search, gradient descent on differentiable subgraphs, or mixed
specialized search without changing the surrounding compression loop.

## Structural Graph Operations

Graph comparison is based on canonical structural keys over immutable node ids.
Commutative operators sort child structural keys, while noncommutative operators
preserve child order. This gives Clojure-native replacements for the Python
`resembles`, `subgraph`, `depth`, and sexpr round-trip tests without adopting
Python's mutable object identity assumptions.


## Compression API

`ciwi.compress` is the public compression loop over the lower-level search
machinery. `compress-exhaustive` searches every value node until a fixed point.
`compress-bounded` searches only bounded neighborhoods around target value
nodes. Both return the final graph, history, DL, stop reason, and selected
expressions derived from the MDL choice tree.

Bottleneck-style tests now assert that exhaustive compression and repeated
bounded local compression converge to the same selected expressions for simple
range, repetition, and affine sequence cases.
