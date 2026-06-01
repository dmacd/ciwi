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

## Description Length

`ciwi.mdl/node-dl` computes the best local description for a value node:

```text
min(raw-value-dl,
    op-dl + sum(best child value dls) for each operator option)
```

This is the Clojure analogue of WILLIAM's bottleneck/minimum-description
selection, but expressed as a memoized pure dynamic program.

## Rewrite Model

A rewrite is explicit data:

```clojure
{:node-id :out
 :op ciwi.operator/brange
 :children [0 5]
 :before 20.0
 :after 8.0
 :delta -12.0
 :reason :brange}
```

Applying the rewrite does not replace or destroy the original value. It adds a
new operator option under that value. The original raw value remains available,
and MDL decides whether the new option is better.

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
- candidate templates enabled for each local value

not total stream history.

## Parallel Search

Candidate generation is embarrassingly parallel over value nodes. The search
namespace exposes `:parallel? true`, currently implemented with a short-lived
Java executor over bounded candidate work items. The interface is deliberately
narrow so it can be replaced with reducers, agents, virtual-thread executors,
or core.async workers without changing rewrite semantics.

## Current Rewrite Templates

The first templates are intentionally simple but useful for proving the loop:

- arithmetic integer ranges: `(brange start n)`
- constant repetitions: `(repeat value n)`
- sequence concatenation: `(concat left right)`
- scaled ranges: `(mult (brange 0 n) step)`
- affine sequences: `(add (mult (brange 0 n) step) start)`

Each template can be detected from a local value. Children introduced by one
rewrite can themselves be rewritten in later bounded passes, which is the basic
mechanism used by the convergence tests.

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
the public operator interface remains the same as primitive operators.

Composite literals support `[:input id sample]` placeholders. Reusing an input id
ties multiple graph leaves to one operator argument, which gives CIWI a native
way to express DAG-style composites such as `x*x + y` without porting Python's
sexpr parser. Non-input leaves in a placeholder template are captured as
constants.

The rewrite engine can opt into composite templates with
`:composite-templates? true`. The first such template is `:linear-sequence`, a
single graph-backed operator equivalent to `(add (mult (brange 0 n) step)
start)`. It demonstrates that composites can participate in the same candidate,
parallel search, and bounded convergence pipeline as primitives. This is a
deliberate stepping stone toward treating composites, local graph rewrites, and
specialized numeric optimizers as recursive graph search operators rather than a
separate mutable object hierarchy.

## Search Operators

Optimizers implement `ciwi.optimize/SearchOperator`. The first port keeps a
Newton/pattern-search style optimizer and an adaptive grid optimizer close to
Python WILLIAM for comparison, but callers see them as recursive search
operators over explicit state. This lets later work replace numeric search with
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
