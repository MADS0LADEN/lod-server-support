# Plan: DH-style quadtree/section aggregation for the client's column state and scan walk

**Status:** v1.2 2026-08-19 — **IMPLEMENTED on the 26.2 line**
(`feat/quadtree-client-state`; user-directed, phases 0–2 with the deviations recorded
in §12 — READ §12 FIRST: the implementation found a strictly simpler shape than §3.2/
§3.3 that dissolves both review MAJORs, and defers the v5 file format). Originally a
research draft against main @ 79e49951 (post-v0.11.1). **Review round 1 (2-Fable)
FOLDED — see §11** for the findings record; the two MAJOR decision points are resolved
in §3.3 (cadence shadow) and §3.4 (eviction granularity) as *planned*, and superseded
by §12's simpler resolution as *built*. Sources: the local checkouts
`research/distant-horizons-core` (@ ed0e94ccb) and `research/distant-horizons`, the
prior DH server-plugin review (serve-path-efficiency-brainstorm.md §7), and the
2026-08-19 3-Fable client-perf review (findings cited as clusters A/B/C — in-session
report, no doc artifact; the A-cluster magnitudes are ESTIMATES, reproduced by
phase 0 before any commitment).

---

## 1. What DH's quadtree actually is (verified from source, not the wiki)

Three layers, all in `research/distant-horizons-core/core/src/main/java/com/seibel/distanthorizons/core/`:

- **`pos/DhSectionPos`** — a *packed long* position: 8-bit detail level + 28-bit x +
  28-bit z (sign-extended). "Section" = a 64×64-datapoint tile at some detail level;
  the smallest render section is detail level 6 = 4×4 MC chunks. All parent/child/
  containment/distance math is static bit math on the long — zero allocation. This is
  the same discipline as LSS's `PositionUtil` packing, extended with a scale axis.
- **`util/objects/quadTree/QuadTree<T>` + `QuadNode<T>`** — a *pointer* quadtree: heap
  nodes with 4 child references and a `value`, rooted in a **`MovableGridRingList`** of
  root nodes (roots at `treeRootDetailLevel ≈ log2(diameter)`). Recentering
  (`setCenterBlockPos`) is `moveTo` on the ring list with a **removed-item callback**;
  mechanically `moveTo` is an O(grid + evicted) scan (it re-walks the grid array and
  fires the callback per evicted subtree), which is cheap in DH only because the root
  grid is tiny (~3×3) — and would be cheap in LSS because our grid is ≤ ~19k
  references. The transferable property is "eviction rides the recenter with a
  callback", not a literal O(evicted) bound.
- **`render/QuadTree/LodQuadTree`** — the policy layer. Once per tick (`tryTick`,
  try-lock so a busy tick is skipped), it recenters, then **recursively walks from
  each root, descending only while a node's detail level exceeds the
  distance-derived expectation**: `expectedDetail = log_base(blockDistance /
  dropoffUnit)` (`calcExpectedDetailLevel`, `updateDetailLevelVariables`). Far nodes
  therefore stop at shallow depth; the walk touches O(active nodes), and because
  detail halves as distance doubles, active nodes per distance-doubling annulus are
  ~constant — the whole visible world is a few thousand nodes regardless of render
  distance. Work queues (`loadQueuedSections`, `startQueuedRetrievalTasks`) sort
  near-to-far by Manhattan distance each tick over those bounded lists.

**The transferable insight is not "multi-resolution rendering"** (LSS deliberately has
no detail levels on the wire — every column ships full-fidelity MC-native sections and
Voxy does its own downsampling). It is: *represent the tracked area hierarchically so
that (a) uniform regions cost O(1) to skip, (b) recentering evicts by region instead
of by per-entry iteration, and (c) any full re-evaluation costs O(active frontier),
never O(disc).* Everything below adapts that insight; nothing below adopts DH's
render pipeline, detail-level policy, or code (see §9, licensing).

## 2. What it would replace in LSS, and the exact costs it attacks

Current client state at the shipped `lodDistanceChunks` 512 (disc ≈ 1.05M columns,
prune radius 544 → up to ~1.19M in-range):

| Structure | Type | Worst-case size |
|---|---|---|
| `ColumnStateMap.timestamps` | `Long2LongOpenHashMap` | ~1.2–2M entries ≈ 32–64 MB (+ ~127 MB transient rehash garbage during a bulk load) |
| `ColumnStateMap` marks: `dirty`, `retry`, `validated`, `sessionSatisfied`, `staleInFlight`, `persistentRemovals` | 6 × `LongOpenHashSet` | `validated` alone approaches disc size on a converged session; the rest are usually small |
| `ColumnStateMap.ingestFailures`, `clearedResync` | Long2Int / Long2Long | small (bounded by failures/clears) |
| `InFlightTracker.awaiting` | `LongOpenHashSet` | ≤ `WANT_SET_BUDGET` 800 — already fine |
| `SpiralScanner` | `confirmedRing` prefix + `long[33]` reopened-ring bitset | tiny — but its **fallbacks** walk O(disc) |
| `ColumnCacheStore` file | v4: `count × (long pos + long ts)` = 16 B/entry, cap `MAX_CACHE_ENTRIES` 2M | 32 MB file; loaded via per-entry `put()` |

Costs this plan attacks (all from the 2026-08-19 review, with its labels):

- **A1** — cache-load apply: up to 2M un-presized hash inserts in ONE render-thread
  tick (`LodRequestManager.tickCacheGatePhase` → `ColumnStateMap.loadFrom`), est.
  100–300 ms, fires at join and at every dimension re-entry.
- **A2** — dimension-change save snapshot: `new Long2LongOpenHashMap(columns)` of
  1–2M entries on main (`ColumnCacheStore.mergeSaveAsync`), est. 50–150 ms,
  compounding with A1 on a portal transit.
- **A3** — movement prune: `pruneOutOfRange` iterates every structure (~2–3M visits)
  each 8 chunks of travel, est. 10–60 ms; worst right after a cache load.
- **B1** — the 64-ring dirty valve (`REOPENED_RING_VALVE`) full reset → the next 1 Hz
  walk is the measured 30–90 ms full-disc walk ×1–3 frames; plausibly recurring every
  10 s (dirty cadence) on busy servers at 512.
- **B2** — every remaining full-reset path (LOD shrink, exclusion shrink, kill-switch
  flush, `recenter(d ≥ RECENTER_FULL_RESET_DELTA=8)` teleports) pays the same
  30–90 ms ×1–3, and short-range teleports + dynamic-view-distance servers recur.
- **Memory/GC** — the tables above; plus fastutil rehash storms on bulk operations.

**What it does NOT attack:** the steady-state moving-client stutter — v0.11.1's
prefix retention (PR #204) already fixed that (spark 6HZTTXT5pn → UC19aREuOo:
552 ms over an 18 s profile → 136 ms over a 51 s profile, ~12× as a per-second rate).
This plan's honest framing is *episodic-hitch elimination + memory*, not steady-state
fps. Cluster C (far-player renderer) is orthogonal and untouched here. **A1/A2 are
attackable WITHOUT the quadtree** — split out as the standalone phase 0.5 (§6), so
the biggest single hitches do not wait on, or die with, this program.

## 3. Proposed design

### 3.1 Core: `SectionStateStore` — dense 8×8-chunk leaves + hierarchical summaries

Not DH's pointer tree. LSS's state per column is a long + ~6 booleans, which fits
dense leaf arrays far better than per-node heap objects:

- **Leaf = 8×8 chunks (64 columns)** — chosen so every boolean state is exactly ONE
  `long` bitmask per leaf. Leaf struct: `long[64] timestamps` (lazily allocated;
  absent = -1), plus one long each for `dirtyMask`, `retryMask`, `validatedMask`,
  `sessionSatisfiedMask`, `staleInFlightMask`, and a derived **`needsMask`**
  (bit set ⇔ `classify()` would return non-SATISFIED), maintained incrementally on
  every state transition. ~590 B per fully-populated leaf.
- **Leaf grid = a movable grid** (DH's `MovableGridRingList` concept): a dense array
  indexed by wrapped section coords, O(1) coordinate access, recenter = `moveTo`
  with an eviction callback. **Sized to the PRUNE radius, not the LOD radius**
  (`effective + LOD_DISTANCE_BUFFER` = 544 at distance 512 → 137×137 ≈ **18.8k**
  references, ~150 KB of pointers) — the store must hold everything the prune
  disc holds. **Window lifecycle:** effective LOD distance is dynamic
  (server SessionConfig re-push, client override edits, the re-polled Voxy view
  distance) — GROWTH reallocates/re-anchors the grid (≤19k pointer copy, O(ms),
  once per change); SHRINK releases the out-of-window annulus through the same
  eviction path the prune uses (state is freed, matching legacy prune semantics).
- **Summary hierarchy**: bitsets at 2×2-leaf, 4×4-leaf, … granularity holding
  "subtree has no needs-bits" — ≤ 4 levels, updated O(levels) per transition. This is
  the implicit quadtree; there are no node objects at all.
- **Side maps stay maps**: `ingestFailures`, `clearedResync`, `persistentRemovals`
  are genuinely sparse and semantically awkward to pack; they remain fastutil maps.
  Verified safe: `classify()` reads only timestamps + dirty + sessionSatisfied +
  retry + validated — all leaf-resident — so the side-map split cannot desync
  `needsMask` (review round 1, technical lens).
- **xplat constraints**: the new classes live in xplat → compiled by Fabric AND
  NeoForge on every line; `XplatLoaderPurityTest` (loader-pure) and
  `XplatJava21SurfaceTest` (constant-pool tripwire) apply. Pure-Java fastutil/array
  code passes trivially, but support-line picks must respect the Java-21 surface.

**`ColumnStateMap`'s public semantic surface survives unchanged** — `classify`'s
ladder (dirty > sessionSatisfied > unknown > retry > revalidation), `SATISFIED`,
`onNotGenerated` permanence, legacy-0 normalization, `noteStaleIfInFlight`, the
receivedCount/emptyCount derived counts, `hasActionableRetries` /
`collectActionableRetryRings`, and the manager-facing accessors (`timestampFor`,
counts, `mapForSave`) — reimplemented over the section store. Every existing Tier-1
pin that operates through that surface keeps passing verbatim. The store swap is NOT
kill-switched (one state owner; array indexing is strictly cheaper than hashing per
op) — the kill switch lives one layer up, on the walk (§3.2). **Triage consequence
(review round 1, C-MAJOR-4): the kill switch A/Bs the WALK, never the store.** A
"flipped the switch, still broken" field report does NOT exonerate this change; store
suspicion means jar rollback. This must be stated in the release notes' rollback
guidance and is recorded in R2/R3.

Memory estimate at 512: 18.8k leaves × ~590 B ≈ **10–12 MB fully populated** (vs
~60–100 MB worst-case today), with lazily-null ts arrays making a fresh session far
smaller. Bulk-load rehash garbage disappears (arrays are exactly sized). At SMALL
LOD distances the dense grid's fixed overhead is a few hundred KB — never worse than
hashing by more than noise.

### 3.2 The walk: banded needs-driven emission (kill-switched vs the legacy walk)

Replace the ring-bitset walk with a **leaf-band walk** that preserves the wire-visible
contract exactly (§5.1):

1. Iterate leaf *bands* (leaf-granular Chebyshev annuli) ascending from the center.
2. A band whose summary bit says "no needs" is skipped in O(1) — a converged disc
   walk is ~18.8k flag checks ≈ **50–150 µs**, and with the summary levels typically
   far fewer.
3. Leaves with needs-bits enumerate set bits of `needsMask ∧ inRangeMask ∧
   ¬exclusionMask`; each emitted position runs the classify ladder for its
   timestamp (now array reads).
4. **Exact ring-ascending emission is preserved** by buffering candidates across
   adjacent bands and merge-emitting per ring. Geometry (verified in review round 1):
   a Chebyshev ring `r = 8q+s` spans exactly leaf bands `{q}` (s=0) or `{q, q+1}`
   (s≥1), so a rolling 2-band buffer suffices — after completing band D, all rings
   ≤ 8D are complete. Within-ring order reproduces the legacy walk's clockwise
   `ringIndexToCoord` order via a per-ring index bucket (NOT naive bit-run order:
   only horizontal ring segments are contiguous runs in a row-major mask; vertical
   segments are strided and corners are L-shaped — still O(64)/leaf). Budget
   (`WANT_SET_BUDGET` 800, `WANT_SET_FRONTIER_RESERVE` 64 semantics) and MID-RING
   truncation behavior are unchanged.
5. The retention machinery's **walk-cost coupling** is what phase 1 deletes: no walk
   ever again costs O(disc), because no walk depends on a prefix that resets. The
   *bookkeeping* geometry (prefix counter, reopened bitset, crescent/shift math)
   survives phase 1 as the cadence shadow (§3.3) and is retired at phase 3. Event
   handling under the quadtree walk:
   - chunk crossing → grid `moveTo` (evicts out-of-window leaves) + needs-bits are
     positionally invariant (absolute-coordinate masks) — no walk-side crescent
     geometry; the moving vanilla-view EXCLUSION circle is a per-walk filter, and
     excluded-unsatisfied positions keep needs=1 so the walk picks them up
     automatically when the circle moves off them (verified: the legacy crescent
     machinery exists solely to patch the *prefix*, which this walk doesn't have).
     The crossing still notifies the shadow (§3.3) so the movement window opens
     exactly as today;
   - dirty broadcast → set dirty bit + needs bit + clear summaries up the path, per
     position O(levels); 10,240-position storms are O(n·levels) with no valve cliff;
   - LOD/exclusion shrink → range-mask change + annulus release (§3.1); no reset
     because there is no walk prefix to strand;
   - teleport any distance → `moveTo`; surviving leaves keep their state.
6. **Kill switch `enableQuadtreeScan`** (client config, default TBD at ship time):
   `false` = the legacy spiral walk (v0.11.1's prefix retention, kept verbatim)
   running over the same section store via the preserved `classify()` surface. Two
   walk engines, ONE state owner — the dual-engine surface is the walk only.
   **Flip semantics (review round 1, C-MINOR-1):** the flag is live-read per scan
   (matching `enableScanPrefixRetention`'s precedent). quadtree→legacy flip: the
   legacy walk starts from the shadow's prefix/bitset state (§3.3 keeps it current,
   so the flip is seamless); if phase 3 has retired the shadow, the flip takes one
   legacy full reset (a one-time B2-class walk — pinned by a
   `midSessionKillSwitchFlip`-style test, the true-control-arm rule). legacy→quadtree
   flip: trivially clean (the store is authoritative). **Flag interaction:**
   `enableScanPrefixRetention` retains its exact meaning for the legacy walk arm AND
   for the shadow (the shadow IS the legacy bookkeeping, so it honors the flag
   naturally — retention=false shadows the from-zero-per-crossing cadence regime);
   the quadtree walk's own emission ignores it. Document the 2×2 in the config notes.

### 3.3 Cadence: phase-1 shadow, phase-3 policy decision

**Resolution of review round 1's joint MAJOR (both lenses):** `fastRescanDue` /
`predictedWalkCost()` are not formulas over geometry — they read live legacy-walk
state: `confirmedRing`, `lowestReopenedBit()`, `lastWalkTruncated`,
`truncatedBelowPrefix`, `recenteredSinceLastFire`, and the F1 exclusion-shrink rung's
`lastExclusionRadius`. "Keep cadence bit-identical" therefore requires that state to
keep existing. Phase 1 does this by running the **legacy scanner's bookkeeping as a
shadow** — literally the existing code paths (recenter crescent/shift, reopenRing,
valve, prefix advancement), which are all O(1)–O(33) bitset ops, WITHOUT ever
running the legacy walk (the expensive part). The quadtree walk feeds the shadow the
inputs the legacy walk would have produced: per-ring satisfaction (computable from
band summaries during emission — advances the shadow's `confirmedRing`), truncation
outcomes (`lastWalkTruncated`/`truncatedBelowPrefix` from the band walk's budget
break), and crossings/dirties/shrinks flow to the shadow's existing entry points
unchanged (so `recenteredSinceLastFire` and the movement window open exactly as
today). Consequences, stated plainly:

- Cadence-observable behavior is bit-identical in phase 1 and every cadence pin
  passes — but the "deletes the trickiest geometry" claim is only fully realized at
  phase 3, when the shadow retires. Phase 1 *decouples walk cost from the geometry*;
  it does not yet delete it. (§4's aggregate claim is qualified accordingly.)
- The shadow also keeps `scan.confirmed`/`reopened=` diag/exporter/soak surfaces
  emitting real values (§5.1.5) — it is needed for that regardless.
- The §6 differential harness must pin **fire-tick equality** (drive the full
  `maybeScan` tick loop on both engines and assert identical fire decisions), not
  just emission-sequence equality.
- Phase-1 effort grows accordingly (§6).

**Phase 3 reframed (review round 1, T-MAJOR-1):** the elytra sustained-fast-rescan
change is a **cadence-policy + server-throughput decision, not a cost problem the
quadtree solves** — post-#204 movement walks are already cheap, and the expensive
movement-window pricing is a deliberate policy (`SpiralScanner`'s own comment: fast
fires under flight would lift the regime toward the stationary 2–3 Hz and the
50–75 MB/s consequence the elytra investigation argued against). One could flip that
policy today by changing `predictedWalkCost()`'s movement-window branch. What the
quadtree contributes to phase 3 is the removal of *residual* cost objections (no
valve cliffs or d≥8 resets mid-flight) and the retirement of the shadow. Phase 3's
real gates are: the throughput/bandwidth decision itself, soak churn-ceiling
re-baselines, AND the second-order windows (review round 1, C-MINOR-6): sustained
4 Hz shifts duplicate-serve-grace absorption (`duplicate_skips`/`grace_skipped` —
law A1 disposition terms) and probe-suppression hit rates — name them in the
re-baseline so an A1-margin drift isn't chased as a regression. Phase 3 also owes a
dated pin/doc-debt audit for the elytra pins it deliberately changes (house style).

### 3.4 Cache lifecycle and file format v5

**Split (review round 1, C-MAJOR-5): A1/A2 do not need the quadtree.** They ship
standalone as **phase 0.5** on today's v4 hash map:

- **A1-lite**: `loadAsync` already builds the map off-thread; the fix is building a
  PRESIZED map (or the final structure) on the IO thread and making the main-thread
  apply a reference-swap/merge instead of `loadFrom`'s per-entry un-presized `put`.
  Days-class, backportable, no format change.
- **A2-lite**: ownership transfer at the dimension-change/disconnect save sites —
  hand the live map to the IO thread and start fresh (the caller clears it
  immediately after anyway; includes the `persistentRemovals` hand-off, which today
  is also lost on a failed dim-change save). Days-class, backportable.

Phase 2 then carries what genuinely belongs to the section store:

- **File v5**: per-leaf records — section pos + presence mask + 64 timestamps (or
  delta/varint packing; ~8–9 B/column realistic vs 16 B), **leaf size encoded in the
  header** (R4). v4 is read-adopted (one-time convert on first load), v5 written.
  Downgrade to an older client = cold resync (the v4-bump precedent from the
  delivery-honesty refactor; the current reader degrades unknown versions to empty
  cleanly). Fix the reader's version log line, which would mislabel v5 as
  "< v4". `MAX_CACHE_ENTRIES` semantics kept (cap by column count);
  `mergeEvictionCap` eviction becomes leaf-granular farthest-first. The off-thread
  v5 build must reproduce `loadFrom`'s ts<-1 clamp and the
  `failuresDuringCacheLoad` replay.
- **A3 — eviction granularity (resolution of review round 1, C-MAJOR-3):** pruning
  stays **position-granular-equivalent**. A leaf entirely outside the prune radius
  evicts wholesale (identical outcome to per-position pruning); a BOUNDARY leaf
  clears only its out-of-range bits (one mask AND per state per leaf). The 8-chunk
  hysteresis and the honesty rules (`persistentRemovals` NOT range-pruned) carry
  over unchanged. This keeps the store-level differential fuzz (R2) exact — which
  stamps survive a movement script is bit-identical to today — so the ts>0/ts≤0
  declaration mix, and therefore the server-visible acquisition-frontier profile,
  cannot drift. Cost: O(boundary leaves) per prune ≈ ~550 leaves at 512 — still
  ~30× cheaper than today's full iteration.
- **Soak staging (review round 1, C-MINOR-2):** soak.sh stages base-world client
  caches and cold-restart-resync force-creates the legacy root; a v5-writing branch
  that re-rolls `soak-worlds/base` leaves snapshots the BASE branch reads as empty,
  silently breaking warm-rejoin/cold-restart-resync premises in the same-day
  branch-A/B workflow. Phase 2 must version the base-world snapshot (or document a
  mandatory reroll on both sides of any A/B).

### 3.5 Explicitly out of scope

- **Server-side quadtree** — server per-player state is already bounded (backlog
  ≤1024, swept served-sets); there is no O(disc) structure to fix. Not proposed.
- **Wire-level aggregated declarations** (want-set as section+mask entries): would
  cut declaration upstream bytes ~5–10× (~10 KB/s → ~1–2 KB/s at 1 Hz) but requires a
  protocol bump + a full `WireDialect` rung + client discovery fallback for old
  servers, for a benefit that matters on no measured link. Documented here so it is
  not re-invented; **rejected** — see §5.3.
- **Detail levels / far-ring downsampling** (the §7 brainstorm's v19 idea): the
  section-pos vocabulary this plan introduces is the natural substrate for it later,
  but it is a wire + LSSApi-profile change co-designed with Voxy. Future unlock,
  not part of this plan.

## 4. Expected improvement (honest estimates, main @ v0.11.1 as baseline)

| Cost | Today (post-v0.11.1) | With §3 | Confidence |
|---|---|---|---|
| Converged/steady scan walk | already ~fixed (retention) | ~same (µs-class) | high |
| Dirty-valve trip (B1) | 30–90 ms ×1–3, possibly per 10 s on busy servers | walk cost eliminated (no walk depends on the prefix); the shadow's valve still fires for cadence until phase 3 | high (mechanism); trip frequency itself still unmeasured |
| Teleport / LOD-shrink / kill-switch resets (B2) | 30–90 ms ×1–3 per event | ~0.1–1 ms | high |
| Cache-load apply (A1) | est. 100–300 ms, one tick | <5 ms main-thread — **phase 0.5, quadtree-independent** | medium (A1 magnitude itself is estimated) |
| Dim-change snapshot (A2) | est. 50–150 ms | ~0 (ownership transfer) — **phase 0.5, quadtree-independent** | high |
| Movement prune (A3) | est. 10–60 ms per 8 chunks | O(boundary leaves) ≈ <1 ms | high |
| Client memory (state) | ~60–100 MB worst | ~10–12 MB | medium-high |
| Cache file / load IO | 32 MB / 2M entries | ~14–18 MB | medium |
| GC transients (bulk ops) | ~127 MB rehash garbage per big load | ~0 | high |
| Steady-state fps | fixed by #204 | no further claim | — |

Aggregate honest claim: **turns every remaining episodic multi-frame hitch into
noise, cuts client LOD-state memory ~6–10×, and makes the walk cost independent of
history.** In phase 1 it *decouples* the client from the trickiest geometry (crescent
bands, valve, shift-carry math) — full deletion of that geometry lands only at
phase 3 when the cadence shadow retires. It does not make an already-converged,
stationary, post-#204 client measurably faster, and the biggest single hitches
(A1/A2) are claimable by phase 0.5 without any of this.

## 5. Backwards compatibility — the user's core question

### 5.1 Wire: ZERO change, by construction

The want-set model is scan-implementation-agnostic: the server receives batches of
(packedPos, clientTimestamp) and replaces the backlog; it cannot distinguish a
quadtree client from a spiral client. **No protocol bump, no new dialect rung, no
server change of any kind.** New client ↔ every old server (incl. v0.4.x via the
permanent v16 client shim — same walk output, same synthetic-cadence exclusions);
old client ↔ new server unaffected (server untouched). "Wire compatibility is never
tiered" is not implicated by a main-only rollout: there is no wire delta to tier —
client internals are precisely what tiers may vary.

The real compat surface is three *implicit* contracts plus in-process mechanics:

1. **Ordering contract (implicit wire contract, the one real risk).** Server pacing
   anchors on the client-declared acquisition frontier and enforces
   `MAX_GENERATION_RING_SPREAD` 2 + nearer-first pacing; `gen_order_gated` /
   `inversions` punish out-of-order declarations. §3.2's banded merge preserves
   exact ring-ascending emission AND legacy within-ring order — pinned by the
   differential harness (§6 phase 0): same inputs ⇒ byte-identical want-set
   sequences vs the legacy walk, fuzzed across movement/dirty/teleport/shrink
   scripts. §3.4's position-granular eviction keeps the ts>0/ts≤0 declaration MIX
   identical too, so frontier stamping cannot drift.
2. **Cadence/churn contract.** Soak ceilings and the elytra-wall pricing assume the
   current cadence regime; §3.3's shadow keeps it bit-identical in phase 1 (pinned
   by harness fire-tick equality) and defers any cadence change to the explicitly
   re-baselined phase 3.
3. **Client cache file.** v4→v5 with read-adoption; downgrade = cold resync
   (precedented). Old clients never see v5 (per-install file). Soak base-world
   snapshot versioning per §3.4.
4. **Kill switch + dual walk.** `enableQuadtreeScan` false = legacy walk over the
   new store, with the flip semantics and `enableScanPrefixRetention` 2×2 defined in
   §3.2.6. Maintenance cost: the legacy walk + its ~20 retention pins stay for at
   least one minor line. **The switch A/Bs the walk only — it cannot exonerate the
   store (§3.1); field triage guidance must say so.** Decommission decision after
   live validation, mirroring `useNbtTranscode`'s object-path retention.
5. **Diag/harness/tooling churn (all same-PR):** the shadow keeps `confirmed=` /
   `reopened=` client diag, the `/lss trace` scan-row fields (`confirmed`/`reopened`
   are embedded in trace rows — a documented diagnosis instrument), and the
   `scan.confirmed` / `scan.reopened` exporter fields emitting REAL values through
   phase 1-2. This matters concretely: **check_soak.py's fresh-backfill named check
   hard-requires `client.scan.confirmed > 24`** (a KeyError if absent — and
   fresh-backfill auto-runs for every base-needing scenario), and the exporter
   contract requires exact key-set equality including the manager-null zero-fill
   branch, so both walk engines must emit the full field union. New quadtree fields
   (`leaves=`, `needs=`, `summarySkips=`) are ADDED alongside. At phase 3 (shadow
   retirement) the named check gets a quadtree-native replacement in the same PR.
   **Harness flag pinning:** soak scenario configs, benchmark staging, and gametest
   run-dir configs must pin `enableQuadtreeScan` EXPLICITLY (the `lodStore`-key
   precedent: harness baselines never shift with a default).
6. **Support lines.** 26.2-main-only initially (the standing support-tier rule);
   backport only after live validation, as a v0.11.1-style picked set. Sequencing
   (review round 1, C-MINOR-7): the held PR #203/#204 retention backports land (or
   are explicitly subsumed) FIRST — the quadtree picks presuppose the retention
   code as their kill-switch arm. The 1.21.1 fresh-cut line compiles the same xplat
   sources; the differential harness rides the picks.

### 5.3 Why NOT wire-level aggregation (pre-empting the obvious "go further")

Declaring section+mask entries would need: protocol 21, a v20 dialect rung
(memoized per-session translation server-side), client discovery fallback, new
`release_check`/parity pins, and a second encoding of the timestamp semantics
(per-column ts inside an aggregate entry ≈ the same bytes as today, so the saving
is only on the fully-uniform interior — which converges to *empty declarations*
anyway at quiescence). The steady-state upstream saving is a few KB/s during
backfill on a path that is not bandwidth-bound. All cost, no measured benefit:
**rejected**; revisit only if declaration bytes ever show up in a real profile.

## 6. Phasing and effort

| Phase | Content | Est. |
|---|---|---|
| 0 | Measurement + harness: valve-trip diag counter on main (settles B1's real-world frequency — also useful standalone); differential walk harness (legacy vs quadtree: emission equality AND fire-tick/cadence equality, fuzzed) | 3–4 days-equiv |
| **0.5** | **A1/A2-lite standalone** (§3.4): presized off-thread cache build + swap; ownership-transfer saves. Shippable and backportable independently — proceeds even if the phase-0 gate refuses the rest | 2–4 days-equiv |
| 1 | `SectionStateStore` + grid recenter + banded walk behind `enableQuadtreeScan` + the cadence SHADOW (§3.3), ColumnStateMap surface preserved; store-level differential fuzz; full Tier-1 suite + new pins | 2–3 weeks-equiv |
| 2 | Cache v5 (leaf records, v4 adoption, soak base-world versioning) + A3 boundary-leaf pruning | 4–6 days-equiv |
| 3 (optional, separate release) | Cadence POLICY decision (elytra sustained fast-rescan — a throughput call, not a cost fix; §3.3) + shadow retirement + soak ceiling re-baseline incl. grace/probe-suppression windows + pin audit | 4–6 days-equiv + soak cycles |

Total for phases 0–2: **~4–5.5 weeks-equivalent** — the largest client-side change
since v17. Suggested gate: run phases 0 + 0.5 now (0.5 is justified on its own
evidence); commit to phases 1–2 only if B1 trips in the wild or the memory numbers
reproduce at the estimated magnitude on the repro rig — the steady-state stutter is
already fixed, so the program must be justified by the episodic/memory evidence,
not momentum.

## 7. Risks

- **R1 — ordering regression under the server's pacing gates.** Mitigated by the
  differential harness (exact-sequence + fire-tick equality) + the server-side
  `inversions` / `gen_order_gated` counters on the repro rig; any live divergence is
  visible in `/lsslod diag` without new instrumentation.
- **R2 — semantic drift in the classify-ladder reimplementation.** The ladder has
  subtle pinned corners (legacy-0 normalization, clearedResync pre-clear stamps,
  staleInFlight, persistentRemovals honesty). Mitigation: the surface is preserved,
  not redesigned; existing pins run against the new backing from day one; a
  store-level differential fuzz (hash backing vs section backing, random op
  sequences, state-equality after every op — made exact by §3.4's
  position-granular eviction) is cheap to write and merciless. **The store is not
  kill-switched: a field regression here is a jar rollback, not a config flip
  (§3.1).**
- **R3 — freshly-shipped machinery churn.** v0.11.1's retention shipped days ago and
  would become the legacy fallback + cadence shadow. Cost is real (reviewer
  attention, two walks); offset: the retention path stays live in BOTH arms (walk
  fallback + shadow), so its field validation is not wasted — but note the walk
  kill switch does not cover the store (R2).
- **R4 — leaf granularity mis-fit.** 8×8 chosen for the 1-long-per-state property;
  if profiling shows band-buffer overhead dominating at small LOD distances, 4×4
  (16-bit masks) fits small discs better. Decide in phase 1 with the harness, cheap
  to flip early, expensive later — the v5 file header encodes leaf size AND the
  grid handles window resize independently of leaf size (§3.1).
- **R5 — the movement window / elytra pricing accidentally changed in phase 1.**
  Guarded by the §3.3 shadow (the pricing code literally still runs) + the
  fire-tick-equality harness + existing cadence pins; phase 3 is where it changes
  on purpose.
- **R6 — shadow-divergence.** The shadow's inputs are now FED by the quadtree walk
  (per-ring satisfaction, truncation outcomes) instead of produced by walking; a
  feeding bug silently shifts cadence. Mitigated by the fire-tick-equality harness
  (which exists precisely to catch this) and by keeping the shadow's own state
  transitions untouched legacy code.

## 8. Test plan sketch

- Phase 0 harness as above (becomes a permanent Tier-1 property test): emission
  equality + fire-tick equality, fuzzed movement/dirty/teleport/shrink scripts.
- New Tier-1 pins: summary-bit invariants (needs-bit ⇔ classify non-SATISFIED,
  fuzzed), boundary-leaf prune equivalence, moveTo eviction honesty (evicted ≠
  persistentRemovals), band-merge ring order incl. mid-ring budget truncation,
  dirty-storm O-behavior (10,240 positions, no cliff), kill-switch flip semantics
  (both directions, the true-control-arm rule), cache v4 adoption + v5 round-trip +
  truncation/corruption (mirror the existing v4 corruption tests) + the ts clamp and
  failure-replay parity.
- Tier 3 unchanged (asserts consumer-side behavior, walk-agnostic).
- Soaks unchanged in phase 1 (laws are server-side; the client schema keeps
  `scan.confirmed`/`scan.reopened` real via the shadow, plus the new fields;
  harness configs pin `enableQuadtreeScan`); the fresh-backfill +
  dirty-during-backfill pair is the live ordering gate. NOTE (from the retention
  round): no soak exercises heavy client movement — the repro rig (movement + dirty
  + teleport script) is the real validation, same as v0.11.1's.

## 9. Licensing

DH core is **LGPL v3**. This plan adopts *concepts* (packed scale+pos longs, movable
grid recentering with eviction callbacks, summary-guided descent, near-to-far work
ordering) with an independent, structurally different implementation (dense mask
leaves + implicit-bitset hierarchy vs their pointer tree of render sections; no
detail levels). No DH code, comments, or constants are to be copied into LSS.
`research/` checkouts stay reference-only and outside the build.

## 10. Verdict

- **Perf/memory:** eliminates every *remaining* client hitch class (episodic 30–300 ms
  events) and ~6–10× the LOD-state memory; steady-state fps is already fixed and
  gains nothing further — and the two biggest single hitches (A1/A2) are claimable
  by the quadtree-free phase 0.5 alone.
- **Phase 3 honestly framed:** the elytra sustained-fast-rescan change is a cadence
  POLICY + server-throughput decision that could be taken on today's code; the
  quadtree removes the residual cost objections (valve cliffs, teleport resets
  mid-flight) and is the cleaner substrate for it, but does not gate it.
- **Backwards compat:** wire-free by construction — old spiral clients and servers
  interoperate with zero shims; the real compat work is the ordering/cadence
  contracts (harness-pinned, incl. fire-tick equality), the eviction-granularity
  equivalence, a cache-format bump (precedented, with soak-staging care), and
  carrying the legacy walk + cadence shadow for one line. LOW wire risk, MODERATE
  in-process engineering discipline.
- **Recommendation:** run phase 0 (cheap; the valve counter is wanted regardless)
  and phase 0.5 (justified standalone) now; commit to phases 1–2 as a v0.12-class
  program only on phase-0 evidence. Do not start phase 1 while the v0.11.1
  field-validation window is still open, and land/subsume the held retention
  backports before any support-line pick.

## 11. Review round 1 (2026-08-19, 2 Fable reviewers) — findings record

**Technical lens** (verdict: sound after fixes — architecture, band geometry ≤2-band
proof, side-map/needsMask safety, exclusion-filter reasoning, and legacy-walk-over-
new-store surface all verified):

- T-MAJOR-1 elytra unlock misattributed (policy, not cost) → §3.3/§10 reframed.
- T-MAJOR-2 cadence freeze needs the deleted state as a shadow → §3.3 shadow model
  (reused legacy bookkeeping, walk-fed), §4/§6 claims + effort adjusted, R6 added.
- T-MINOR-1 grid must size to prune radius 544 (137×137 ≈ 18.8k leaves) → §3.1 fixed.
- T-MINOR-2 DH moveTo is O(grid+evicted) → §1 corrected.
- T-MINOR-3 "552→136 ms, ~12×" self-inconsistent → §2 now carries the profile
  windows (18 s vs 51 s; ~12× is the per-second rate).
- T-MINOR-4 within-ring order needs per-ring bucketing (bit runs only horizontal) →
  §3.2.4.
- T-MINOR-5 no grid-resize story for LOD growth → §3.1 window lifecycle.
- T-MINOR-6 phase-1 save path still synthesizes the v4 map; loadFrom clamp +
  failure replay must carry to v5 → §3.4.
- T-MINOR-7 A-cluster magnitudes cite an artifact-less review → header note added.

**Compat/risk lens** (verdict: fixable inside the structure; §5.1 wire claim, v16
both directions, grace/probe windows, backpressure batch, VSS, and the §5.3
rejection all verified sound):

- C-MAJOR-1 = T-MAJOR-2 (plus: F1 `lastExclusionRadius` rung, movement-window
  opening on moveTo, fire-tick harness requirement) → §3.3.
- C-MAJOR-2 `client.scan.confirmed > 24` named soak check + exporter exact-key-set +
  no harness pinning of the new flag → §5.1.5.
- C-MAJOR-3 leaf-granular eviction breaks differential equality + shifts the
  server-visible ts mix → §3.4 position-granular boundary-leaf pruning.
- C-MAJOR-4 kill switch cannot exonerate the store → §3.1/R2/R3/§5.1.4 triage story.
- C-MAJOR-5 A1/A2 standalone split → phase 0.5 (§3.4, §6).
- C-MINOR-1 flip semantics + flag 2×2 → §3.2.6. C-MINOR-2 soak base-world cache
  versioning + version log line → §3.4. C-MINOR-3 = T-MINOR-1/-5. C-MINOR-4
  `/lss trace` scan-row fields → §5.1.5. C-MINOR-5 xplat purity/Java-21 surface →
  §3.1. C-MINOR-6 phase-3 blast radius (grace/probe windows, law A1 terms) + pin
  audit → §3.3. C-MINOR-7 backport sequencing after the held retention backports →
  §5.1.6.

## 12. Implementation record (2026-08-19, `feat/quadtree-client-state`, 26.2 line)

**The built shape is simpler than the planned one, with identical wins.** Reading the
shipped scanner closely showed the walk's cost was never in the prefix/valve/cadence
*bookkeeping* (O(1)–O(33) bitset ops) — only in the per-position classify iteration.
So instead of §3.2's parallel band-merge engine plus §3.3's cadence shadow:

- **The fast path is a ring-level skip INSIDE the legacy walk** (`SpiralScanner.scan`):
  before iterating a ring's positions, `ColumnStateMap.ringNeedsFree` checks the
  O(perimeter/8) leaves the ring crosses; all-clear ⇒ the ring's confirmation
  bookkeeping applies with zero position visits. Any needs bit anywhere in a crossed
  leaf falls through to the untouched per-position loop. Emission bytes, ordering,
  budget/truncation, confirmation, and every cadence input are therefore identical
  **by construction** — T-MAJOR-2/C-MAJOR-1 (the shadow) dissolve: the prefix,
  reopened bitset, valve, movement window, and `predictedWalkCost` all keep running
  unchanged and REAL (so `scan.confirmed`, the soak named check, diag, and trace keep
  live values with no shadow at all). One hoisted top-of-ring budget check replicates
  the old loop's i=0 break exactly.
- **The store**: `ColumnStateMap` rewritten over dense 8×8-chunk leaves
  (`long[64]` ts + one mask long per boolean state + the derived `needs` mask) in a
  **plain hash map of leaves + a one-entry memo** — NOT the movable grid: hash-of-
  leaves preserves the old backing's out-of-window semantics verbatim (no window
  lifecycle, no growth realloc, no eviction callback), and the prune stays explicit
  with §3.4's position-granular boundary-leaf masking. No summary hierarchy in v1 —
  leaf-flag checks alone hit the cost target (a full-disc SATISFIED reset walk drops
  from ~1M classify probes / 30–90 ms to ~131k map lookups (~67k loop iterations —
  review round 2 corrected the count; both `||` sides evaluate on clean leaves) /
  ~1.5–3 ms; steady-state walks are unchanged µs-class because the prefix already
  skips them; the post-cache-load first walk is NOT a fast-path case — adopted stamps
  are revalidation needs — its hitch is A1's off-thread build fix).
- **Cache lifecycle**: A1 = `ColumnCacheStore.loadStateAsync` builds `LoadedState`
  leaves on the IO thread; the gate adopts in O(leaves). A2 = `detachForSave()`
  ownership transfer (destructive by documented contract; both production callers
  clear/drop immediately after) feeding `mergeSaveDetachedAsync`, which synthesizes
  the overlay map on the IO thread and reuses the unchanged mergeSave body. A3 rides
  the leaf prune. **The v5 file format is DEFERRED** (v4 kept): the hitches are fixed
  without it, and deferring removes the soak base-world snapshot trap (C-MINOR-2)
  entirely for now.
- **Sanctioned divergences from the old backing** (documented in the class javadoc,
  normalized in the fuzz): timestamps below -1 clamp to absent at every write (the
  old backing kept inert entries); explicit -1 entries do not exist (absent ≡ -1).
  One REAL bug the fuzz caught pre-merge: `buildLoaded` initially *dropped* corrupt
  file entries, silently keeping a live stamp the old clamp would have overwritten —
  fixed via `LoadedState.clampedToAbsent` (file-wins preserved).
- **Tests**: `ReferenceColumnStateMap` (the v0.11.1 hash implementation, verbatim) +
  `SectionStateFuzzTest` (4-seed op-sequence differential over every observable +
  the `ringNeedsFree` soundness invariant + detach/adopt/merge-save lifecycle pins)
  + `QuadtreeWalkDifferentialTest` (dual-arm fire-tick + emission + state parity:
  cold backfill under truncation, movement/dirty/teleport/shrink script, the
  valve-overflow B1 shape with recovery, mid-session flip, seeded chaos). Phase 0's
  measurement landed as `valve_trips` + `quad_ring_skips` (diag `valve=`/`ring_skips=`
  on the Scan line, exporter `scan.valve_trips`/`scan.quad_ring_skips`, contract
  updated). All tiers green: full T1, T2, T3, `:paper:test`, `:neoforge:build`.
- **Config**: `enableQuadtreeScan` (client, default true) — gates the WALK only; the
  store is not switchable (field triage: the switch A/Bs the walk, store suspicion is
  a jar rollback). Stateless flips (no retained fast-path state), pinned by the flip
  differential. `enableScanPrefixRetention` is orthogonal and unchanged in meaning.
- **Not done**: v5 file format (above), phase 3 (cadence policy — untouched), soak
  client-config pinning of the new key (soak clients run shipped defaults; the
  differential guarantees make default-on the tested configuration), support-line
  backports (after live validation, retention backports first per §5.1.6).

## 13. Review round 2 (2026-08-19, 3 Fable reviewers on the implementation) — folded

Lenses: store rewrite vs the reference / scanner fast path + equivalence claim /
lifecycle + threading + integration. **Zero MAJORs on all three** — the equivalence
claims held under adversarial reading (ringNeedsFree leaf-coverage proof, the tsPut
transition matrix, every destructive-saveCache caller chain incl. the soak client's
disconnect row, which re-emits the last LIVE snapshot by pre-existing design). All
actionable MINORs folded:

- **Store MINOR-1 / lifecycle MINOR-2 (the one production fix):** explicit `-1` file
  rows (persistable by a v0.11.1 client's inert clamped entries) now join
  `clampedToAbsent` so the file-wins overwrite deletes a live stamp exactly as the
  reference's `put(-1)` did. The fuzz's loadFrom domain now generates exactly `-1`
  (it was the one value missing — would have caught this instantly).
- **Scanner MINOR-1:** the two `CountingColumnStateMap` retention pins (warm-disc
  hitch bound, steady-state zero-visit) now pin `quadtreeScanEnabled = false` — with
  the fast path on, a collapsed prefix would fast-skip uncounted and the classify
  count could no longer detect a retention regression (the pins are the retention
  arm's unit-level control).
- **Both reviewers' vacuous-probe finding:** the fuzz gained a converge-leaf bulk op
  + a per-seed assertion that the ringNeedsFree soundness probe's TRUE branch fired,
  plus a deterministic engagement/disengagement pin (converged 3×3-leaf block; dirty/
  retry/unstamp/absent/unvalidated flavors each disengage — including the
  adopted-stamps-never-skip property).
- **Scanner MINOR-3/4:** `lastWalkTruncated` got a test accessor + arm-parity assert;
  a new armed-cadence differential scenario compares FAST-fire decisions (fastScans
  parity + engagement premise); a new retention-OFF × quad-ON scenario covers the
  fourth seam quadrant (collapse-recovery walks, where the fast path engages hardest).
- **Fuzz domain widenings (store MINOR-3):** occasional `onReceived(ts=0)` (wire-legal
  hostile input), independent noteStale/resolveStale ops (staleInFlight bits now
  survive into prunes/clears).
- **Doc corrections:** ~65k → ~131k map lookups (~67k iterations) for the reset-walk
  cost (time claim unchanged, it fit the corrected count); the post-cache-load hitch
  re-attributed to A1's off-thread build (adopted stamps are revalidation needs and
  never fast-skip); `persistentRemovalsForSave`'s "not drained at save" reworded (the
  production path drains by ownership transfer — same failed-save exposure as the old
  copy-then-clear callers); `mergeSaveDetachedAsync`'s "no caller-thread copy" →
  "no per-entry copy" (the detach's O(leaves) shell copy is sub-ms).
- **Noted, deliberately not changed:** `hasActionableRetries`/`collect…` O(leaves)
  while a retry exists (documented, sub-ms, retryCount==0 short-circuits the common
  case); sub- -1 file garbage persists as file cruft until distance eviction
  (observationally harmless); ringNeedsFree corner leaves double-checked (constant-
  factor, correctness-neutral).
