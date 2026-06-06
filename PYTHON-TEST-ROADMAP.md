# Python WILLIAM Test Roadmap

This file maps the Python WILLIAM tests to CIWI parity work. The goal is to
prove compression/search behavior, not to clone incidental Python APIs,
GraphViz rendering, AIM logging, or the old S-expression parser.

## Current Baseline

CIWI currently has compression behavior evidence for all Python
`william/tests/test_alice.py` sequence tasks through the core
`ciwi.alice-wunderbaum` path:

- `simple_repeat`
- `insert_repeat`
- `insert_repeat2`
- `insert_repeat3`
- `repeat_with_noise`
- `simply_linear`
- `sprinkled`
- `increasing_runs`
- `map_negate`

The live evidence matrix is `alice-test-parity.md`.

## Priority 1: Core Graph/Search Semantics

These are the next tests that most directly demonstrate that CIWI implements
WILLIAM's core compression machinery.

| Python tests | CIWI target | Why next |
| --- | --- | --- |
| `william/tests/test_bottleneck.py::test_min_desc_len` | Covered. Small section fixtures use Clojure graph literals; larger Python-scale fixtures use native EDN value/operator/root graph specs plus expected selected expressions. Tests check Python DL constants and selected operator identities without GraphViz parsing. | This is the best direct proof that CIWI's global shared-DAG minimizer matches WILLIAM outside Alice. |
| `william/tests/test_propagation.py::test_propagate_up` and `::test_random_propagation` | Covered. CIWI uses native graph literals for `co2`, `co3`, `co4`, `matching/set_mean_add`, and `composite/trees2`. Expected memories are native node-id/value maps; Python's integer-array unknown sentinel is represented as CIWI `nil` holes. | Alice/Wunderbaum depends on propagation/inversion being correct under partial memory, unknown values, and multiple possible completions. |
| `william/tests/test_delayed_builder.py` | Covered. CIWI keeps regression coverage and now checks Python's simple/`with_mult` fixtures as exact native `brange` output-conditioned materializations, including generated start/stop memory values and graph structure. | Delayed building is where Wunderbaum candidates become actual DAGs; parity here prevents search-order wins from hiding materialization bugs. |
| `william/tests/test_wunderbaum.py::test_wunderbaum_iteration` | Serial solution case covered. CIWI finds the native graph option expression `setitem(repeat(3, [45]), negate([-1 -2]), negate([-87 -87]))` with an injected registry and explicit declarations for Python's standalone operator set. Parallel iteration remains future work. | This is the next end-to-end Wunderbaum case beyond Alice sequence compression, and it exercises a different operator family. |
| `william/tests/test_conditions.py` | Covered for condition fixture shapes. CIWI covers `co0`-`co21` and `dag0`-`dag7` using native graph/composite specs, including the `co15` order-only fixture. Missing Python-only checks are the Rust hotloop comparison and DOT import mechanics, which are not CIWI proof targets. | Composite conditions decide which inversions/search attachments are legal. This is high leverage for learned composites later. |
| `william/tests/test_composite.py` | Partial. CIWI now covers native shared-DAG execution for `dag4`/`dag5`, `dag5` extra-branch inversion, and exact `co2`/`co3`/`co4` inverse rows. Remaining useful slices are the full `co0`-`co21` commutativity table, more inverse rows around callable/map and sequence-edit behavior, and spec synchronization. Do not port S-expression reconstruction as parser parity. | Learned and builtin composites need to behave like native operators. This is central to the long-term library-compression plan. |

Recommended order inside Priority 1:

1. Continue composite behavior expansion from `william/tests/test_composite.py`.

## Priority 2: Numeric Optimization as Search

These tests matter because they connect WILLIAM's symbolic graph search to
continuous or mixed discrete/continuous parameter search.

| Python tests | CIWI target | Notes |
| --- | --- | --- |
| `william/tests/test_discrete_optimizer.py` | CIWI already covers Newton, mixed int/float, adaptive grid, and joint sampling. Add the residual-DL adaptive examples that score `_jdesc_len_array_elias` once CIWI's value-DL helper is wired cleanly into optimizer objectives. | Keep this as optimizer protocol evidence, not Alice evidence. |
| `william/tests/test_alice_pipeline.py::TestMatrixRegressionDebugPipeline` | Implement/verify `Dot` plus optimizer-backed `try_to_optimize`, then run matrix regression with `Dot` and `Add`. | This should be the first major post-core end-to-end demonstration. It proves graph search can produce a structure whose leaves are then optimized. |
| `william/tests/test_alice_pipeline.py::test_run_clustering_try_to_optimize_worker` | After matrix regression, add the clustering optimization worker with `Sub`, `Mult`, `Sum1`, `LessThan`, `GetItem`, and `Union`. | This is useful, but it pulls in more complex array operators and should not precede matrix regression. |

## Priority 3: Classification Demonstrations

`william/tests/test_classification.py` is better treated as an application
demo suite than as the next core parity target.

Candidate CIWI milestones:

- Single-factor Iris compression with `SetItem` and `LessThan`.
- Classification-by-propagated-residual-DL on a learned/found graph.
- Brute-force compression classifier smoke test only after Alice matrix
  regression and optimizer-backed graph search are stable.

These depend on the Priority 1 and Priority 2 work. They should not block core
Wunderbaum/Alice parity.

## Deprioritized For Now

Do not spend near-term parity effort on these unless they block a higher
priority target:

- `william/tests/test_rendering.py`: rendering/UI output, not compression.
- `william/tests/test_search_status.py` and `test_aim_tracking.py`: logging and
  process instrumentation.
- `william/structures/tests/test_sexpr_to_graph.py`: CIWI intentionally uses a
  Clojure-native graph DSL instead of porting Python's S-expression machinery.
- `william/structures/tests/test_dot_to_graph.py`: useful only as fixture
  import support; not part of the core proof.
- `william/library/tests/test_filling.py`: domain-specific image/filling
  operators, not on the current roadmap.
- `william/legacy/**`: historical implementation tests.

## Library Tests Policy

Python's `william/library/tests` should be used selectively:

- Port operator, type/spec, precision, description-length, and hashing cases
  only when they are required by the core parity targets above.
- Prefer behavior-level tests through graph search, propagation, MDL, or Alice
  tasks over routine-by-routine helper parity.
- Keep recognizer/template shortcuts disabled for Alice parity evidence.

## Dense Primitive Note

The current large-vector optimizations in `ciwi.operator` are pragmatic
optimizations over Clojure vectors. Revisit them when CIWI gains a proper dense
primitive array layer. At that point these transient/vector-loop fast paths may
be redundant complexity and should either collapse into the dense backend or be
removed.
