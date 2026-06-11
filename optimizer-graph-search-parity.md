# Optimizer And Numeric Graph Search Parity

Last updated: 2026-06-11.

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
- `william/tests/test_classification.py`, staged after matrix regression

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
| `test_discrete_optimizer.py::test_adaptive_optimizer_example` | Behavior covered | `ciwi.optimize-test/adaptive-grid-optimizes-python-scale-residual-dl-slope` | Uses a deterministic 1000-point CIWI fixture, signal-only Elias residual DL, and the Python assertion shape against a brute grid optimum. Exact NumPy fixture capture remains pending before calling this full fixture parity. |
| `test_discrete_optimizer.py::test_adaptive_grid_mixed_float_int` | Behavior covered | `ciwi.optimize-test/adaptive-grid-optimizes-python-scale-mixed-residual-dl` | Uses a deterministic 1000-point CIWI fixture, mixed float/int dimensions, joint sampling, and Python-style slope/bias tolerances. Exact NumPy fixture capture remains pending. |
| `test_alice_pipeline.py::TestMatrixRegressionDebugPipeline::test_optimizer` | Behavior covered | `ciwi.optimize-test/adaptive-grid-optimizes-python-scale-matrix-regression` | Exercises dense `dot`, rounded predictions, signal-only residual DL, rounded weight `Value.desc_len`, and Python-style improvement/closer-to-true-weight assertions on a deterministic `1000 x 10` fixture. Exact NumPy fixture capture remains pending. |
| `test_alice_pipeline.py::TestMatrixRegressionDebugPipeline::test_try_to_optimize` | Behavior covered | `ciwi.graph-optimize-test/try-to-optimize-improves-matrix-regression-weight-leaf` | Uses graph-level `try-to-optimize` over a permeable dense weight leaf with explicit matrix-regression section ids. It verifies finite DL, improvement, fixed matrix preservation, re-inferred residual precision, and movement toward true weights on a deterministic `1000 x 10` fixture. Exact NumPy fixture capture remains pending. |
| `test_alice_pipeline.py::TestMatrixRegressionDebugPipeline::test_single_compression_step` | Covered with supplied solution | `ciwi.alice.matrix-regression-test/matrix-regression-compression-step-finds-python-dot-add-solution` | Uses the exact NumPy `default_rng(123)` fixture, a native structural solution-prefix predicate, symbolic `Dot`/`Add` Wunderbaum search, and optimizer-backed improvement of the permeable `w` leaf. |
| `test_alice_pipeline.py::TestMatrixRegressionDebugPipeline::test_greedy_with_solution` | Covered | `ciwi.alice.matrix-regression-test/matrix-regression-greedy-with-solution-reaches-threshold` | End-to-end greedy task over `[y, x_mat]` with Python step-0 target ordering, native supplied-solution predicate, symbolic `Dot`/`Add` search, and optimizer-backed `w` improvement. |
| `test_alice_pipeline.py::TestMatrixRegressionDebugPipeline::test_greedy_without_solution` | Covered | `ciwi.alice.matrix-regression-test/matrix-regression-greedy-without-solution-reaches-threshold` | End-to-end greedy task over the exact NumPy fixture without a solution predicate; the injected operator set is only `[Dot, Add]`. |
| `test_alice_pipeline.py::test_run_clustering_try_to_optimize_worker` | Behavior covered | `ciwi.graph-optimize-test/try-to-optimize-improves-clustering-centroid-and-radius` | Uses a deterministic Python-scale `1000 x 2` five-cluster fixture and the native graph shape `union(getitem(x, lessthan(sum1(mult(sub(x, c), sub(x, c))), s)), rest)`. It verifies finite DL, at least 1% DL improvement against the Python-style original section DL, inferred residual/rest rows, and movement of centroid or radius. Exact NumPy fixture capture remains pending. |
| skipped clustering Alice pipeline rows | Deferred | Pending after classifier `try_to_optimize` unless clustering Alice search becomes the next application target | Python marks these skipped. Treat as optional application evidence, not a near-term core gate. |
| `test_classification.py::TestIrisClassificationDebugPipeline::test_try_to_optimize` | Active next | Pending after clustering worker | Python currently skips this row. Useful as the first classifier debug target because it isolates `try_to_optimize` over a scalar threshold leaf. |
| `test_classification.py::TestIrisClassificationDebugPipeline::test_single_compression_step` | Deferred | Pending after classifier `try_to_optimize` | Python currently skips this row. Uses only `SetItem` and `LessThan` over the single-factor Iris fixture. |
| `test_classification.py::TestIrisClassificationDebugPipeline::test_greedy_single_factor_with_solution` | Deferred | Pending after classifier compression step | Python currently skips this row. End-to-end Alice run with the supplied single-factor solution. |
| `test_classification.py::TestIrisClassificationDebugPipeline::test_greedy_single_factor_without_solution` | Deferred | Pending after with-solution single-factor run | Python currently skips this row. Same operator basis, but requires search to find the structure. |
| `test_classification.py::TestIrisClassificationDebugPipeline::test_greedy_full` | Deferred | Pending after single-factor rows | Python currently skips this row. Uses the full four-feature Iris task and residual-DL classification evaluation. |
| skipped brute-force Iris classifier rows | Deferred | Application demo, not core parity | Python marks these skipped. Treat as later application evidence once optimizer-backed Alice is stable. |

## Matrix Regression Fixture

Python's `TestMatrixRegressionDebugPipeline` is now covered through its direct
optimizer, graph optimizer, single compression-step, and greedy rows. The
fixture is deliberately small in operator vocabulary but large enough to stress
dense numeric scoring:

- RNG seed `123`
- `x_mat`: `1000 x 10` Gaussian matrix, scaled by `10`, rounded to 3 decimals
- `w_init`: length-10 Gaussian vector, rounded to 3 decimals, permeable
- `w_true`: length-10 Gaussian vector scaled by `2.0`, rounded to 3 decimals
- `y`: `round(dot(x_mat, w_true) + 0.85 + noise, 3)`, with noise from
  `Normal(0, 0.2)` rounded to 3 decimals
- graph shape: `(add (dot x_mat w) residual)`, where `w` is optimized and the
  residual leaf is inferred/provided according to the pipeline stage
- task/domain shape: target values `[y, x_mat]`, `free_values=[w_init]`,
  `threshold_rate=0.01`, `max_dag_dl=20`, operator set `[Dot, Add]`

The direct optimizer test scores:

```text
_jdesc_len_array_elias(y - round(dot(x_mat, w), 3), 3)
+ Value(round(w, 3)).desc_len()
```

That is the first parity gate. CIWI should not substitute mean squared error,
least squares, or a closed-form regression shortcut. Those can be future
specialized search operators, but they are not evidence that the Python Alice
pipeline has been ported.

Current CIWI status: the direct optimizer behavior is covered on a deterministic
Python-scale CIWI fixture. The fixture uses the same dimensions, dense
operations, rounded prediction scoring, signal-only residual DL, and rounded
weight value DL, but the standalone direct optimizer rows are not yet exact
NumPy fixture captures.

The graph-level `try_to_optimize` behavior is also covered on the same
deterministic fixture. CIWI uses explicit `section-ids` to mirror Python's
cross-section graph semantics: `root`, `x`, and permeable `w` are copied into
each trial memory, while the residual leaf is re-inferred by propagation.

The Alice single-compression-step row now uses an exact checked-in capture of
Python's NumPy fixture at `test/ciwi/fixtures/matrix_regression.edn`. CIWI
searches the native graph shape with declarations for `Dot` and `Add`, filters
already materialized candidates through a native structural solution-prefix
predicate corresponding to Python's supplied solution, optimizes `w`, projects
the improved memory back into the graph, and scores the candidate with graph
DL. The optimized `w` vector matches Python's `try_to_optimize` result within
the test tolerance.

The greedy matrix rows use the same exact fixture. CIWI now preserves Python's
task leaf control flow through explicit
`:leaf-selection-policy :python-test-parity`: step 0 attempts target roots in
task order, then later steps sort current leaves by descending DL. With the
fixture task `[y, x_mat]`, that means the first greedy step focuses `y`, as in
Python. This is test parity policy, not the preferred future scheduler. The
with-solution row supplies the same native solution-prefix predicate as the
direct compression-step row; the without-solution row runs unconstrained over
`[Dot, Add]` and still reaches the task threshold in one step.

## Clustering Fixture

Python's clustering debug worker builds a fixed graph and only tests
graph-level optimization, not Alice enumeration. CIWI mirrors that row with a
native graph fixture instead of Python S-expression parsing:

```text
union(getitem(x, lessthan(sum1(mult(sub(x, c), sub(x, c))), s)), rest)
```

The fixture shape matches Python's scale and operator semantics:

- five clearly separated 2D Gaussian clusters
- `n = 1000`, rounded to 3 decimals
- permeable centroid `c`, a short length-2 dense float array
- permeable radius/threshold `s`, a scalar float
- immutable target/root matrix `x`
- `Sub` supports the Python broadcast case `1000 x 2` minus length-2 centroid
- `Mult`, `Add`, and other Alice-basis elementwise ops still reject
  mismatched dense array shapes, matching Python WILLIAM rather than general
  NumPy broadcasting
- `GetItem` selects rows for a 2D matrix when given a first-axis boolean mask
- `Union` concatenates 2D dense arrays along axis 0 and its inverse removes
  known rows from the output to infer the residual/rest rows

Current CIWI status: behavior covered on a deterministic Python-scale CIWI
fixture. Exact NumPy `default_rng(2026)` fixture capture remains pending before
claiming exact fixture parity. The runtime primitives `sum1` and `union` are
available for optimizer/classifier graph fixtures, but they are not part of the
Alice `test_alice.py` parity basis. Because the clustering graph reuses `x` as
both the root target value and a leaf below the root, CIWI's graph optimizer now
uses the Python-style cross-section bottleneck scoring path for this row rather
than the simpler matrix-regression leaf-sum path.

## Classification Fixture

The classifier path is broader and should come after matrix regression. The
debug fixture uses Iris data permuted with `RandomState(0)`, one feature column
for the early rows, an initial scalar threshold `4.8`, and the graph shape:

```text
(setitem rest (lessthan factor threshold) selection)
```

The minimal operator basis for the first classifier rows is `[SetItem,
LessThan]`. The full exploratory classifier grows to feature sections and
residual-DL evaluation over candidate labels; Python also contains skipped
brute-force compression classifier rows. CIWI should stage these as application
evidence after optimizer-backed graph search is already proven on matrix
regression.

## Dense Numerics Decision

Near-term recommendation: make numeric graph array values dense from the start.
Keep search infrastructure, optimizer coordinate vectors, graph ids, specs, and
symbolic data native, but route `:array-*` numeric graph values through a CIWI
dense boundary before porting the matrix-regression pipeline.

Start with a pure Clojure/vector backend for correctness and optionally add one
native backend behind the protocol once behavior is green.

The protocol should cover at least:

- construction from native vectors/matrices
- NumPy-style `shape`, `dtype`, `ndim`, `size`, `ravel`, and `tolist`
- explicit NaN missing-value support for dense numeric propagation
- backend-neutral value DL and hashing paths that do not repeatedly call
  `tolist` in hot loops
- elementwise `add`, `subtract`, `multiply`, `divide`, comparison, boolean
  masking, and `isnan`
- matrix/vector `dot`
- `sum` with axis support, including row-wise `axis=1`
- selection and union-like operations needed by clustering

Do not let graph nodes, values, operators, or Wunderbaum declarations depend
directly on Neanderthal, DJL, JAX, or any other concrete dense class. The first
backend can be a simple vector-backed `NDArray`:

```clojure
(dense/array [[1.0 2.0] [3.0 nil]])
;; => backend :ciwi.vector, dtype :float64, shape [2 2],
;;    flat [1.0 2.0 3.0 ##NaN]
```

The public API should stay close to NumPy where that is not painful:

```clojure
(dense/shape x)
(dense/dtype x)
(dense/ndim x)
(dense/size x)
(dense/ravel x)
(dense/tolist x)
(dense/dot a b)
(dense/sum x 1)
```

Current simplification: assume only one active dense backend at a time. Equality
and hashing must be deterministic within that backend and consistent for equal
contents under that backend. Cross-backend equality can wait until there is more
than one real backend.

## Backend Tradeoffs

| Option | Pros | Costs/Risks | Fit |
| --- | --- | --- | --- |
| Pure Clojure vectors first | Minimal dependency risk; easiest to debug; preserves deterministic parity work; no native install issues. | Slow for large matrix ops; encourages temporary vector-specific code if not isolated. | Best for first correctness slice if hidden behind `DenseBackend`. |
| Neanderthal first | Native BLAS/LAPACK performance from Clojure; existing CPU/GPU story; good fit for linear algebra such as matrix regression. | Couples early data model to BLAS-style dense matrices unless abstracted; native packaging and backend setup complexity; less direct ML/model ecosystem. | Good first native backend after protocol exists. |
| DJL first | Engine-agnostic Java NDArray/model abstraction; can switch among engines by dependencies/config; closer to JVM ML deployment. | Heavier dependency surface; engine/version management; may be more framework than needed for initial dot/residual tests. | Good candidate when we want ML model interop or GPU tensors, not necessary for the first parity slice. |
| JAX bridge/custom backend now | Aligns with future differentiable/compiled backend ambitions; strong long-term story for gradient search and JIT-able kernels. | Highest integration complexity from Clojure/JVM; Python boundary or custom backend work could dominate the parity effort. | Do not start here. Design interfaces so this can be added later. |

Current decision: the CIWI dense boundary is in place. The default backend is
vector-backed for correctness and stable test behavior; an opt-in DJL/PyTorch
CPU backend now validates the same protocol against a real native NDArray
engine. Numeric graph arrays use dense values with NaN missing slots. CIWI still
defers making a native backend the default until clustering/classifier work
exposes whether DJL's current boundary is enough or whether BLAS/LAPACK or a
JAX-like backend should move up the priority list.

## Implementation Order

1. Done: add the `ciwi.dense.*` boundary: `ciwi.dense.core` public API,
   `ciwi.dense.protocols` backend contract, and `ciwi.dense.vector` pure
   Clojure backend with NumPy-ish metadata/accessors, NaN missing-value
   normalization, elementwise arithmetic/comparison, sequence-edit helpers,
   `dot`, `sum`, and spec/value-DL/hash recognition.
2. Done: port the current numeric graph array operator basis to dense
   outputs/inputs while keeping symbolic vectors/lists native.
3. Done: add `ciwi.dense.djl` as an opt-in DJL/PyTorch CPU backend under the
   `:djl` dependency alias, with focused backend tests and a compression-level
   smoke run using DJL as the process default backend.
4. Done at behavior level: add residual-DL adaptive optimizer tests from
   `test_discrete_optimizer.py::test_adaptive_optimizer_example` and
   `::test_adaptive_grid_mixed_float_int`. These prove the optimizer is moving
   on the same compressed-residual objective Python uses. Exact NumPy fixture
   capture remains pending.
5. Done at behavior level: add matrix regression `test_optimizer` parity on a
   deterministic Python-scale fixture.
   This should exercise dense `dot`, residual DL, and `Value` DL for the
   optimized weight vector, still outside graph search. Exact NumPy fixture
   capture remains pending.
6. Done at behavior level: implement graph-level `try_to_optimize` as a composable recursive graph
   search operator over permeable leaves. It should take the operator registry,
   propagation strategy, dense backend/defaults, optimizer, and objective
   policy as injected dependencies rather than hardcoding Alice globals. CIWI's
   first implementation takes explicit `section-ids`, propagation options, a
   value-DL cache, and an optimizer factory.
7. Done at behavior level: add matrix regression `test_try_to_optimize`
   coverage on a deterministic Python-scale fixture. Exact NumPy fixture
   capture remains pending.
8. Done: wire optimizer-backed candidates into Alice/Wunderbaum and cover
   matrix regression `test_single_compression_step`,
   `test_greedy_with_solution`, and `test_greedy_without_solution` with the
   exact NumPy fixture.
9. Done at behavior level: add clustering `try_to_optimize` worker coverage
   from `test_alice_pipeline.py`. Exact NumPy fixture capture remains pending.
10. Active next: stage classifier debug parity: classifier `try_to_optimize`, single
   compression step, single-factor greedy with solution, single-factor greedy
   without solution, then full Iris. Keep brute-force classifier rows as later
   application evidence.
11. Only after behavior is green decide whether DJL should become the default
    backend, whether to add a Neanderthal backend for BLAS/LAPACK performance,
    or whether matrix/classifier work exposes protocol gaps.

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
- DJL API and PyTorch engine docs: <https://djl.ai/api/> and
  <https://djl.ai/engines/pytorch/pytorch-engine/>. These document the
  `ai.djl:api:0.36.0` API artifact and the PyTorch CPU engine/native artifact
  pairing used by CIWI's first real backend.
- JAX extension docs: <https://docs.jax.dev/en/latest/extensions.html>. JAX has
  extension APIs for custom interpreters and backend-adjacent integration, which
  is useful context for a future backend but too large a dependency direction
  for this parity tranche.
