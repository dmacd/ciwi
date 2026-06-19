# Lazy Frontier Optimization

Last updated: 2026-06-18.

## Purpose

This note records the lazy frontier experiment for serial Wunderbaum search,
motivated by memory growth in the unguided house demo. The goal is to avoid
materializing all delayed-build descriptors for an expanded graph up front,
while preserving the same best-first frontier order as the eager serial path.

The implementation is opt-in through `:lazy-frontier? true` or
`:frontier-mode :lazy`. It is currently serial-only; partitioned and global
parallel paths reject it explicitly.

## Mechanism

The eager frontier expands each materialized graph into all legal delayed build
items immediately. Each item is ranked by:

```clojure
[build-dl order]
```

where `build-dl` is the parent build DL plus operator declaration DL, and
`order` is the eager insertion tie-breaker.

Lazy frontier mode instead queues expansion cursors. Each cursor stores:

- the graph and memory for one expanded result,
- a resumable node-tuple enumerator,
- a heap of generated but not yet emitted child descriptors,
- a lower bound for unscanned children.

The cursor emits a concrete build item only when its current generated best
child is no later than the lower bound for every unscanned child. This keeps
the same best-first semantics. Ties use `[expansion-order local-order]`, so
children from an earlier expansion sort before same-DL children from later
expansions, and local order preserves tuple/operator scan order.

## Correctness Checks

Targeted tests currently cover:

- resumable tuple cursor output equals eager `node-tuples`,
- lazy mode finds the basic range inversion solution,
- eager and lazy candidate prefixes match on a Python Wunderbaum fixture,
- eager and lazy frontier materialization order matches over a 250-pop Python
  Wunderbaum prefix,
- lazy mode rejects multi-worker partitioned and global-best-first paths.

## House Probe Setup

The house probe used the unguided house options and the same target fixture as
the demo. Runs were sequential and heap-capped:

```bash
JAVA_TOOL_OPTIONS='-Xmx8g -XX:+ExitOnOutOfMemoryError'
```

The comparison is fixed by yielded candidate count, not wall-clock timeout.
At every checkpoint, eager and lazy runs had identical yielded-candidate counts
and identical popped-build counts.

The best candidate at every checkpoint was unchanged:

```clojure
{:ops [:add :line :dye :draw]
 :build-dl 1.15
 :compression-rate -0.0018258126678527553}
```

## Retained Heap

This table uses the memory probe that forces GC while retaining the live
frontier queue. It is the better retained-heap comparison, but its elapsed
times include measurement overhead and should not be used as the runtime
benchmark.

| Yielded candidates | Mode | Popped builds | Live queue shape | Frontier considered | Post-GC heap |
|---:|---|---:|---:|---:|---:|
| 5k | eager | 11,033 | 227,256 build items | 532,198 | 521 MiB |
| 5k | lazy | 11,033 | 4,999 cursors | 75,822 | 420 MiB |
| 10k | eager | 21,277 | 483,583 build items | 1,148,266 | 1,052 MiB |
| 10k | lazy | 21,277 | 9,999 cursors | 136,234 | 816 MiB |
| 25k | eager | 49,908 | 1,279,107 build items | 3,026,505 | 2,619 MiB |
| 25k | lazy | 49,908 | 24,999 cursors | 292,424 | 1,936 MiB |
| 40k | eager | 84,796 | 2,124,551 build items | 5,072,509 | 4,239 MiB |
| 40k | lazy | 84,796 | 39,999 cursors | 501,732 | 3,137 MiB |

At 40k yielded candidates, lazy frontier reduced retained heap by about
1.1 GiB and avoided scanning about 4.57M frontier attachments.

## Timing

This table uses a timing-only probe over the same checkpoints. It does not
force GC or traverse/count the live queue at checkpoints, so it is the better
runtime comparison. Memory values here are raw runtime snapshots, not retained
post-GC heap.

| Yielded candidates | Mode | Popped builds | Elapsed | Frontier considered | Heap used snapshot |
|---:|---|---:|---:|---:|---:|
| 5k | eager | 11,033 | 24.7s | 532,198 | 1,026 MiB |
| 5k | lazy | 11,033 | 21.8s | 75,822 | 856 MiB |
| 10k | eager | 21,277 | 50.8s | 1,148,266 | 1,244 MiB |
| 10k | lazy | 21,277 | 42.9s | 136,234 | 1,154 MiB |
| 25k | eager | 49,908 | 129.4s | 3,026,505 | 2,994 MiB |
| 25k | lazy | 49,908 | 105.8s | 292,424 | 2,198 MiB |
| 40k | eager | 84,796 | 216.7s | 5,072,509 | 4,744 MiB |
| 40k | lazy | 84,796 | 177.5s | 501,732 | 3,567 MiB |

Runtime reduction from eager to lazy:

| Yielded candidates | Runtime reduction |
|---:|---:|
| 5k | 12.0% |
| 10k | 15.5% |
| 25k | 18.2% |
| 40k | 18.1% |

## Long House Probe

After the fixed-work comparison, a longer lazy-frontier house probe was rerun
with the same 8 GiB heap cap. The first attempt exposed an unrelated
delayed-builder cache bug: `WeakIdentityKey.equals` could return `nil` after a
weak referent was cleared, which Java then unboxed as a primitive boolean and
reported as a `NullPointerException`. The equality method now returns an
explicit boolean and treats cleared referents as unequal cache keys.

The 8 GiB rerun did not reach a formal `OutOfMemoryError`, but it became
GC-bound near the heap cap and was stopped manually after about 18.1 minutes:

| Elapsed | Candidates | Popped builds | Frontier considered | Frontier kept | Heap used |
|---:|---:|---:|---:|---:|---:|
| 60s | 14,441 | 29,909 | 183,709 | 84,050 | 1,389 MiB |
| 120s | 27,408 | 54,620 | 317,513 | 150,218 | 2,430 MiB |
| 240s | 51,736 | 115,211 | 695,007 | 289,808 | 4,652 MiB |
| 360s | 80,927 | 176,558 | 1,022,606 | 433,105 | 6,854 MiB |
| 480s | 100,346 | 214,009 | 1,214,635 | 524,139 | 7,831 MiB |
| 602s | 103,324 | 220,134 | 1,238,822 | 536,962 | 8,066 MiB |
| 1,088s | 104,725 | 222,786 | 1,250,028 | 542,839 | 8,181 MiB |

The best candidate never improved beyond the same negative-compression
`add -> line -> dye -> draw` shape. The practical limit under this heap cap is
therefore around 100k emitted candidates, after which throughput collapses due
to GC pressure.

A follow-up run with a 16 GiB heap cap was started with no practical search
timeout. The machine already had limited free RAM, so the process used swap as
the heap grew. It again found no better candidate and was stopped once
throughput collapsed near the heap cap:

| Elapsed | Candidates | Popped builds | Frontier considered | Frontier kept | Heap used |
|---:|---:|---:|---:|---:|---:|
| 60s | 14,916 | 30,819 | 189,191 | 86,636 | 1,444 MiB |
| 300s | 66,008 | 147,129 | 885,904 | 365,424 | 5,845 MiB |
| 480s | 105,948 | 225,326 | 1,259,607 | 547,994 | 9,209 MiB |
| 720s | 163,027 | 335,918 | 1,807,369 | 810,991 | 14,031 MiB |
| 901s | 205,988 | 419,997 | 2,212,673 | 1,007,817 | 15,569 MiB |
| 1,084s | 211,107 | 429,465 | 2,264,888 | 1,031,812 | 15,991 MiB |
| 1,267s | 213,209 | 433,777 | 2,288,626 | 1,042,670 | 16,196 MiB |

The 16 GiB cap roughly doubled the practical emitted-candidate depth compared
with 8 GiB, but did not change the search result. Throughput fell below about
500 candidates per minute by 21.1 minutes, so the limiting factor is still
retained frontier/search state, not only the original 8 GiB cap.

Seeing multiple CPU cores active during this run is not evidence of a parallel
lazy-frontier search. This probe uses the serial `wunderbaum/iterate` path; the
multi-worker partitioned and global paths reject lazy mode. Multi-core activity
near the cap is consistent with JVM GC, JIT, and runtime helper threads.

## Interpretation

Lazy frontier is not only a memory optimization in this workload. It also
reduces runtime because it avoids doing attachment checks for hidden siblings
that are not yet needed by the best-first queue. At 40k candidates, the eager
path had considered about 5.07M frontier attachments, while the lazy path had
considered about 0.50M.

The effect should be strongest when many expanded graphs have large sibling
sets and the global best-first order only consumes an early prefix from each
set. If a search eventually drains most cursors, lazy mode may lose some of
this advantage because it adds cursor and internal-heap bookkeeping.

## Caveats

- These are single runs, not repeated warm benchmark medians.
- Runs were separate JVM processes under the same heap cap.
- The timing probe avoids forced GC, but normal JVM GC behavior still affects
  elapsed time.
- Lazy frontier is currently serial-only and should not be treated as evidence
  for partitioned or global parallel performance.
- The house search quality did not improve in these probes; the best candidate
  remained the same negative-compression `add -> line -> dye -> draw` shape.
