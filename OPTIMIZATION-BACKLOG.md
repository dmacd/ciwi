# Optimization Backlog

This document tracks performance work that should be easy to revisit without
mixing it into the roadmap or parity evidence tables. Items here are not new
features and are not licenses to change Alice/WILLIAM search semantics. Valid
optimizations preserve candidate ordering, attachment legality, operator
declarations, scoring, and accepted solutions unless a future task explicitly
decides otherwise.

## Current Priority: Wunderbaum Attachment Checks

`insert_repeat3` is the current structural runtime outlier. The full
Python-scale CIWI/DJL run reaches the Python-shaped solution and compression
rate, but costs roughly 7 seconds. Focused profiling showed this is not
primarily DL, dense numeric, hashing, or primitive operator time. It is late
frontier expansion and delayed graph construction:

- Steps 1-3 are cheap.
- Step 4 costs about 1.1 s.
- Step 6 is the main cliff.
- A six-step structural profile processed 7,741 frontier items.
- It expanded 2,651 materialized graphs.
- It ran about 3.3 million attachment-validity checks.

The relevant call path is:

```text
ciwi.wunderbaum/expand-graph
  -> ciwi.wunderbaum.tuples/node-tuples
  -> ciwi.wunderbaum.attachment/invalid?
  -> ciwi.delayed-builder/delayed-dag-build-with-seen
```

### Semantics-Preserving Options

1. Add a global early exit for impossible expansion.

`attachment/invalid?` currently rejects every attachment when there is more
than one op-carrying root. `expand-graph` can detect `>1` op roots from the
attachment context and return `[queue order]` before enumerating tuples. This
should be exactly equivalent to checking and rejecting every tuple/element
pair.

2. Memoize attachment validity inside one expansion.

Validity depends on the graph's attachment context, `gen-cond`, and
conditioned nodes. It does not depend on the specific operator declaration
except through `gen-cond`. A local cache in `expand-graph`, keyed by
`[gen-cond nodes]`, can avoid repeated validity checks across declarations
while preserving element order and queue order.

3. Precompute role sets in `attachment/context`.

Instead of re-evaluating role predicates for every `(position, node)` pair,
compute sets once:

- valid output nodes: primary descendants with no existing options
- valid input nodes: free ancestors excluding the primary root
- op-root count
- single op-root id, when present
- whether input attachments are allowed

Then `attachment/invalid?` becomes mostly set membership plus the existing
single-op-root rule.

4. Skip impossible `gen-cond` classes.

When context makes a whole class of input or output attachments impossible,
`expand-graph` can skip all elements with that `gen-cond` before iterating
matching declarations. This should be driven by the same precomputed role
information as item 3.

5. Generate constrained tuples by role.

Instead of generating generic node tuples and filtering them by attachment
legality, generate tuples whose positions are legal for the `gen-cond` roles.
This can preserve semantics, but it is more delicate because tuple order affects
search order. Only do this after items 1-4, and test by comparing the exact
build-info sequence emitted by optimized and unoptimized expansion on
representative graphs.

### Non-Goals For This Backlog Item

Do not change these while claiming an attachment-check optimization:

- `max-node-tuples`
- generalized condition semantics
- operator declaration order
- queue/build ordering
- attachment legality rules
- Python-compatible scoring thresholds
- accepted candidate selection rules

## Later Dense Numeric Cleanup

CIWI currently contains several large-vector optimizations in both generic
operator code and the DJL backend. Revisit them after the dense backend story
is more mature, especially if CIWI moves to a primitive dense library or a
custom JAX-like backend. Some current optimizations may become unnecessary
complexity once primitive buffers and backend summaries are first-class across
all dense values.
