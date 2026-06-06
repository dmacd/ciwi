# Optimizer And Numeric Graph Search Parity

Last updated: 2026-06-06.

This file tracks Python WILLIAM parity for optimizer-backed graph search:
standalone discrete optimizers, `try_to_optimize`, and Alice pipeline tests
where symbolic graph search proposes a structure and numeric search improves
permeable leaves.

The goal is behavior parity for compression/search, not a clone of Python's
NumPy object model. CIWI should keep numeric storage behind a backend-neutral
interface so graph semantics, value description length, propagation, and search
operators do not depend directly on one dense library.

## Scope

Target Python files:

- `william/tests/test_discrete_optimizer.py`
- `william/tests/test_alice_pipeline.py`
- Later: optimizer-backed rows in `william/tests/test_classification.py`

Out of scope unless they block compression behavior:

- AIM/logging instrumentation around optimizer metrics.
- Python multiprocessing timeout harnesses.
- Python S-expression parsing used to construct fixture graphs.
- Exact NumPy storage identity or dtype object parity.

## Evidence Matrix

| Python test | CIWI status | CIWI evidence | Notes |
| --- | --- | --- | --- |
| `test_discrete_optimizer.py::test_newton_optimizer` | Covered | `ciwi.optimize-test/newton-optimizer-finds-integer-bowl-minimum` | Confirms integer Newton-style search finds `[20 -15]` with score `3.0`. |
| `test_discrete_optimizer.py::test_mixed_int_float_newton_optimizer` | Covered | `ciwi.optimize-test/newton-optimizer-handles-mixed-int-float-dimensions` | Confirms mixed int/float search reaches the same bowl minimum shape. |
| `test_discrete_optimizer.py::test_adaptive_optimizer_sampling_finds_joint_improvement` | Covered | `ciwi.optimize-test/adaptive-grid-joint-sampling-finds-non-coordinate-improvement` | Confirms joint sampling finds an improvement when coordinate-only moves do not. |
| `test_discrete_optimizer.py::test_adaptive_optimizer_example` | Not yet covered | Pending | Needs a residual-DL objective wired to CIWI's Python-compatible value description length helpers. |
| `test_discrete_optimizer.py::test_adaptive_grid_mixed_float_int` | Partial | Existing adaptive-grid infrastructure only | Need the Python-scale residual-DL objective over generated arrays and mixed float/int parameters. |
| `test_alice_pipeline.py::TestMatrixRegressionDebugPipeline::test_optimizer` | Not yet covered | Pending | First target in this tranche. Needs dense dot, residual DL objective, and adaptive optimizer parity on the Python-scale matrix regression fixture. |
| `test_alice_pipeline.py::TestMatrixRegressionDebugPipeline::test_try_to_optimize` | Not yet covered | Pending | Needs `try_to_optimize` over a graph with permeable numeric parameters. |
| `test_alice_pipeline.py::TestMatrixRegressionDebugPipeline::test_single_compression_step` | Not yet covered | Pending | Needs Alice/Wunderbaum to compose symbolic `Dot`/`Add` search with optimizer-backed parameter improvement. |
| `test_alice_pipeline.py::TestMatrixRegressionDebugPipeline::test_greedy_with_solution` | Not yet covered | Pending | End-to-end matrix regression with provided solution graph. |
| `test_alice_pipeline.py::TestMatrixRegressionDebugPipeline::test_greedy_without_solution` | Not yet covered | Pending | End-to-end matrix regression from search without a solution hint. |
| `test_alice_pipeline.py::test_run_clustering_try_to_optimize_worker` | Not yet covered | Pending after matrix regression | Needs `Sub`, `Mult`, `Sum1`, `LessThan`, `GetItem`, `Union`, and optimizer-backed centroid/radius improvement. |
| skipped clustering Alice pipeline rows | Deferred | Pending after `try_to_optimize` clustering | Python marks these skipped. Treat as optional application evidence, not a near-term core gate. |

## Dense Numerics Decision

Near-term recommendation: introduce a small CIWI dense numeric protocol before
porting the matrix-regression pipeline, but implement only the operations needed
for the next tests. Start with a pure Clojure/vector backend for correctness and
optionally add one native backend behind the protocol once the behavior is
green.

The protocol should cover at least:

- construction from native vectors/matrices
- shape, dtype/category, scalar extraction
- conversion back to plain CIWI data for value DL and expected-value tests
- elementwise `add`, `sub`, `mult`, `div`, comparison, boolean masking
- matrix/vector `dot`
- `sum1` over rows
- selection and union-like operations needed by clustering

Do not let graph nodes, values, operators, or Wunderbaum declarations depend
directly on Neanderthal, DJL, JAX, or any other concrete dense class. Use a
small data/backend boundary such as:

```clojure
{:kind :dense
 :backend :ciwi.vector
 :dtype :float64
 :shape [1000 10]
 :data ...}
```

or a record implementing a protocol:

```clojure
(defprotocol DenseBackend
  (dense-shape [x])
  (dense-dtype [x])
  (dense-dot [a b])
  (dense-add [a b])
  (dense-sub [a b])
  (dense-mul [a b])
  (dense-sum1 [x])
  (dense-mask [x mask])
  (dense->plain [x]))
```

## Backend Tradeoffs

| Option | Pros | Costs/Risks | Fit |
| --- | --- | --- | --- |
| Pure Clojure vectors first | Minimal dependency risk; easiest to debug; preserves deterministic parity work; no native install issues. | Slow for large matrix ops; encourages temporary vector-specific code if not isolated. | Best for first correctness slice if hidden behind `DenseBackend`. |
| Neanderthal first | Native BLAS/LAPACK performance from Clojure; existing CPU/GPU story; good fit for linear algebra such as matrix regression. | Couples early data model to BLAS-style dense matrices unless abstracted; native packaging and backend setup complexity; less direct ML/model ecosystem. | Good first native backend after protocol exists. |
| DJL first | Engine-agnostic Java NDArray/model abstraction; can switch among engines by dependencies/config; closer to JVM ML deployment. | Heavier dependency surface; engine/version management; may be more framework than needed for initial dot/residual tests. | Good candidate when we want ML model interop or GPU tensors, not necessary for the first parity slice. |
| JAX bridge/custom backend now | Aligns with future differentiable/compiled backend ambitions; strong long-term story for gradient search and JIT-able kernels. | Highest integration complexity from Clojure/JVM; Python boundary or custom backend work could dominate the parity effort. | Do not start here. Design interfaces so this can be added later. |

Current decision: define the CIWI dense boundary now, keep the first
implementation simple, and defer committing to a native backend until the
matrix regression graph-search path is behaviorally correct.

## Implementation Order

1. Add `ciwi.dense` protocol and pure Clojure/vector implementation.
2. Port only the dense ops required for matrix regression: `dot` and broadcast
   `add` over vector outputs.
3. Add residual-DL adaptive optimizer tests from `test_discrete_optimizer.py`.
4. Implement graph-level `try_to_optimize` as a composable recursive graph
   search operator over permeable leaves.
5. Add matrix regression `test_optimizer` and `test_try_to_optimize` parity.
6. Wire optimizer-backed candidates into Alice/Wunderbaum compression step.
7. Only then decide whether to add a Neanderthal or DJL backend for performance
   and broader ML domains.

## Sources

- Python reference tests: `../william/william/tests/test_discrete_optimizer.py`
  and `../william/william/tests/test_alice_pipeline.py`.
- Neanderthal: <https://neanderthal.uncomplicate.org/>. The project describes
  itself as native-speed Clojure matrix/linear algebra built around BLAS/LAPACK
  and CPU/GPU backends.
- DJL overview and engine docs: <https://docs.djl.ai/master/index.html> and
  <https://docs.djl.ai/master/docs/engine.html>. DJL presents a Java
  engine-agnostic ML framework with selectable engines and NDArray/model
  abstractions.
- JAX extension docs: <https://docs.jax.dev/en/latest/extensions.html>. JAX has
  extension APIs for custom interpreters and backend-adjacent integration, which
  is useful context for a future backend but too large a dependency direction
  for this parity tranche.
