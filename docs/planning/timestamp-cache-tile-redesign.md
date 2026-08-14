# ColumnTimestampCache tile redesign — ~10x+ memory reduction at large lodDistance

Status: IMPLEMENTED — shipped in v0.10.0 (D0; `ColumnTimestampCache` cites this as the as-built design). Reviewed 2026-08-08 by two subagent review rounds (adversarial
correctness + integration/completeness); all findings incorporated below, each marked
"review round 1" (correctness) or "review round 2" (integration). Not implemented.
Target branch: a fresh `feat/tscache-tiles` off `main` AFTER the protocol-20 C6 work
merges (this PR edits `ExporterContractTest.java`, which C6 also touches).

## 1. Problem

`ColumnTimestampCache` stores one entry per served column in **two parallel
`Long2LongOpenHashMap`s** (`timestamps` + `insertionTimes`). The class's own honest model
is `HEAP_BYTES_PER_ENTRY = 64` (~43 B typical, ~85 B right after a resize). The tracked
area scales with the square of `lodDistanceChunks`:

| lodDistance | disc columns | current heap (64 B model, per dim) |
|---|---|---|
| 256 (default) | 513² ≈ 263k | ~16 MB |
| 512 | 1025² ≈ 1.05M | ~64 MB |
| 1024 | 2049² ≈ 4.2M | ~256 MB |

…times dimensions, times roaming. On top of that, `snapshotForSave()` deep-copies the
timestamp maps, transiently adding ~half of that again on every save.

Acceptance bar (user decisions, 2026-08-08): **~10x per-column reduction is good enough;
do not add complexity beyond that** — and AUTO sizing should spend part of that win on
**coverage** (track ~5-10x the lodDistance disc instead of today's 1.5x) rather than
returning it all as RAM. Net effect at default AUTO: ~2.5x less heap while tracking
~5.3x more columns (§5). The watermark/collapse tier, mmap, and SQLite options from the
brainstorm are explicitly DEFERRED (§12).

## 2. Chosen design

Replace the two per-column hash maps with **per-region flat tiles**:

- Key: packed **region** coordinate (32×32 chunks — same regioning as `.mca` files and
  `SqliteLodStore.regionOf`).
- Value: one `Tile` = `int[1024]` of stamp offsets + an `int liveCount` + a
  `long lastTouchEpochSeconds`.
- Per-dimension container: `Long2ObjectOpenHashMap<Tile>`.

A column's stamp is stored as a **u32 offset in epoch seconds** from a fixed constant:

```java
/** 2025-01-01T00:00:00Z — every legitimate LSS stamp postdates it. */
public static final long TS_EPOCH_SECONDS = 1_735_689_600L;
```

Slot value `0` = absent (sentinel). Stored value = `clamp(ts - TS_EPOCH_SECONDS, 1, 0xFFFF_FFFFL)`
kept in the int as unsigned; `get()` returns `slot == 0 ? 0 : TS_EPOCH_SECONDS + (slot & 0xFFFF_FFFFL)`
(the `slot == 0 → 0` branch is load-bearing: without it an absent slot would decode to a
positive 2025 stamp and grant `up_to_date` for a column the server never stamped — the
forbidden direction. Pinned in the clamp truth table.)

**Class invariant (review round 1): no tile with `liveCount == 0` may ever exist in the
map.** Every decrement site — the `ts <= 0` put-clear (§2.2), `invalidateStamps`, and
eviction — removes the tile when its last slot clears, and every mutation site (including
tile eviction) maintains the `liveSizes` mirror. `save()` additionally skips an empty
tile defensively (belt), because the v2 loader treats `liveCount == 0` as corruption
(§6) — without the invariant, two plan rules would interact into a self-inflicted
discard-the-rest on reload. Both directions are pinned: the writer never emits an empty
tile; the loader rejects one.

### 2.1 Memory arithmetic (the honesty check)

Per tile: 4096 B array + ~16 B array header + ~32 B Tile object + ~29 B map slot ≈
**~4.2 KB**; model constant `TILE_HEAP_BYTES = 4352` (slack included).

- Dense tile (1024 live): ~4.25 B/column → **~15x** vs 64 B.
- A 513² spiral disc covers 289 tiles at ~88.9% average fill (interior tiles full,
  boundary partial): ~4.8 B/column → **~13x**.
- Break-even honesty bound: the design beats 10x whenever average tile fill ≥ ~66%.
  Spiral scans, backfill, and resyncs are dense discs by construction, so the real
  workload sits far above that.
- Degenerate sparse case (1 column per region — no real workload produces it): 4.2 KB per
  column, *worse* than today, but bounded by the byte cap (§5): pathological sparsity
  evicts more, never OOMs.

Secondary wins, free with the structure:

- The parallel `insertionTimes` map is **deleted** (eviction age becomes per-tile
  `lastTouch`), along with the whole O(n log n) `evictOldest` threshold-selection
  machinery.
- `snapshotForSave()` becomes per-tile `int[].clone()` — ~13x cheaper, shrinking the
  transient save-time memory spike by the same factor.
- The persisted file shrinks ~3.5x (§6) and holds ~1000x fewer records.

### 2.2 Correctness frame: which direction is safe

The router's rung is `up_to_date iff cachedTs > 0 && cachedTs <= clientTimestamp`.
**Overstating** a stored stamp (returning a value ≥ the true last-write) only makes the
test harder to pass → a redundant re-serve → safe. **Understating** can grant
`up_to_date` against a stale claim → stale LODs kept → the one forbidden direction
(delivery-honesty family). Every clamp below rounds UP or refuses to fabricate:

- `ts == TS_EPOCH_SECONDS` exactly → offset 0 collides with the sentinel → clamp to 1
  (overstates by 1 s — safe). Pinned.
- `ts < TS_EPOCH_SECONDS` (pre-2025 server clock): clamp to 1, **with a warn-once**.
  Safe (overstated cache stamps only cause re-serves), but review round 1 flagged the
  honest operational cost: the WIRE still carries the unclamped pre-epoch stamp (the
  cache is not in that path), so the client's claim stays pre-epoch, `epoch+1 <=
  clientTs` fails forever, and a skewed-clock server (no-RTC SBC booting at 1970) loses
  warm resyncs entirely — every rejoin re-serves the disc, permanently, where today's
  code converges. That regression is accepted (such clocks are broken in many other
  ways), but it must be *visible*: warn once — "system clock predates 2025-01-01 —
  timestamp-cache warm resyncs disabled until the clock is sane". Pinned with a
  skewed-clock test.
- `ts <= 0` at `put()` → **clear the slot instead of storing**, and still run the
  `clearMiss` choke point exactly as every put does today (pinned). The current code
  stores the raw 0/negative, which `get()` reports as "absent-equivalent" since the
  router requires `cachedTs > 0`; mapping it to any positive offset would fabricate a
  claim — the 0-stamp ghost history forbids that. Router-equivalent to today, but NOT
  observably identical: a 0-stamp put no longer counts in `size()`/`sizesPerDimension()`,
  and a cache holding only 0-stamps now reads `isTimestampCacheEmpty() == true` — which
  feeds the review-P3 save-hook skip gate's third conjunct. Documented harmless (no
  production path puts a non-positive stamp — every producer is `epochSeconds()`-derived;
  a 0-stamp cache carries no claims; the gate has two other conjuncts) but stated here
  deliberately rather than claimed away. Pinned as a truth-table test.
- Offset overflow (`ts` past year ~2161): clamp to `0xFFFFFFFF`. Understates only when
  the true stamp postdates 2161 — documented unreachable; comment at the constant.

For every **in-range** stamp (any real time strictly between 2025 and 2161 — the scope
of the claim, per review round 1) the u32 offset is **lossless**: `get()` after `put()`
returns the exact same second, no quantization, router semantics bit-identical to today.
Out-of-range stamps follow the clamp rules above, which differ from today only in the
directions documented as safe.

## 3. Region/slot math

```java
int cx = PositionUtil.unpackX(packed), cz = PositionUtil.unpackZ(packed);
long regionKey = ((long)(cx >> 5) << 32) | ((cz >> 5) & 0xFFFF_FFFFL);  // floors negatives
int slot = ((cx & 31) << 5) | (cz & 31);                                 // always 0..1023
```

New `PositionUtil.packRegionOf(long packed)` and `PositionUtil.tileSlotOf(long packed)`
helpers with unit pins **including negative coordinates and the ±(2^31) extremes**
(`cx >> 5` arithmetic shift floors correctly; `cx & 31` is non-negative). The
implementation must be verified equivalent to `SqliteLodStore.regionOf` (which stays
private — adopting the shared helper there is optional cleanup **scoped to `regionOf`
ONLY**: the store's persisted backfill-mark bitset uses the TRANSPOSED slot formula
`(cx & 31) + ((cz & 31) << 5)` at `SqliteLodStore.java:~2257`, and "unifying" it onto
`tileSlotOf` would scramble persisted done-marks — review round 1).

## 4. API surface — preserved verbatim

Public/package API is unchanged so no caller outside the class is touched except the
constructor call site:

- `get(dim, packed)` → exact stamp or 0. Router (`resolvedFromTimestamp`) untouched.
- `put(dim, packed, ts, now)` — writes slot, bumps `liveCount` if the slot was empty,
  sets tile `lastTouch = now` (epoch seconds — `cycleNow`'s existing unit), updates the
  `liveSizes` mirror, and calls `clearMiss` exactly as today.
- `invalidate` / `invalidateStamps` — clear slots (decrement `liveCount`), preserve the
  memo-vs-stamps split verbatim. **A tile whose `liveCount` reaches 0 is removed from the
  map** (dimension-trip sweeps must actually free memory).
- `size()`, `sizesPerDimension()`, `getEvictionCount()`, `isTimestampCacheEmpty()`
  (the review-P3 booted-empty conjunct) — all derived from running live counts.
  Semantics identical for positive stamps; the one delta is the `ts <= 0` put rule
  (§2.2), which no production path can reach.
- **Miss memo: completely untouched.** It stays a sibling `Long2LongOpenHashMap`
  (seconds-fresh, TTL-bounded, never persisted, tiny). Its wholesale-clear-on-overflow
  rule keeps the same numeric cap as today (§5).

Threading contract unchanged: processing-thread-confined, `liveSizes` ConcurrentHashMap
mirror + `evictionCount` AtomicLong for cross-thread observability.

## 5. Sizing, cap, and eviction

- Constructor changes from `maxEntriesPerDimension` to **`maxBytesPerDimension`**
  (`mbToEntries` deleted; the only production call site is `OffThreadProcessor:203`,
  which passes `effectiveTimestampCacheMB() * 1024L * 1024L`).
- Per-dimension accounting: `tileCount * TILE_HEAP_BYTES`. `evictIfOversized()` evicts
  the **oldest-`lastTouch` tile** repeatedly until under budget (linear scan per pick —
  tile counts are hundreds-to-thousands, trivial), reporting the summed `liveCount` so
  the existing "Evicted N oversized timestamp cache entries" debug line and
  `evictionCount` keep their meaning.
- Eviction granularity coarsens to a tile (1024 columns). `lastTouch` = most recent put,
  so any tile with recent activity is protected; the cost of the approximation is
  redundant re-serves in a region idle long enough to be the LRU victim — the same class
  of cost eviction always had.
- Miss-memo overflow cap keeps its current formula: `maxBytesPerDimension / 64` entries
  (identical to today's `mbToEntries` result **for the same MB**). Caveat (review round
  1): because AUTO's resolved MB shrinks ~3x (§ below), the memo's wholesale-clear cap on
  default configs drops from ~500k to ~190k entries at distance 256. Harmless in practice
  (memo population ≈ miss-rate x 30 s TTL, orders of magnitude below either cap) — noted
  so a future memo-overflow investigation doesn't chase a phantom.
- **AUTO sizing** (`effectiveTimestampCacheMB`) — user decision 2026-08-08: spend part
  of the per-column win on **coverage**, not all of it on RAM. Replace
  `TIMESTAMP_CACHE_HEAP_BYTES_PER_ENTRY = 64` with
  `TIMESTAMP_CACHE_HEAP_BYTES_PER_COLUMN = 5` (honest for ≥85% fill — `TILE_HEAP_BYTES /
  1024 = 4.25 B`; at 75% fill the true figure is 5.67 B) AND raise the area slack from
  `1.5x` the lodDistance disc to a named `TIMESTAMP_CACHE_AUTO_COVERAGE_FACTOR = 8.0` —
  AUTO now provisions ~8x the disc (~5.3x today's tracked-column coverage), so roaming
  players and multi-player spread stop thrashing eviction. Numbers **including the
  existing `LOD_DISTANCE_BUFFER = 32` term** (review round 1 corrected all three): at
  distance 256 the side is `2*(256+32)+1 = 577` → old AUTO ~31 MB, new ~12.7 MB per
  dimension (~2.5x less RAM, 5.3x more columns); at 512 (side 1089): ~109 MB → ~45 MB;
  at 1024 (side 2113): ~409 MB → ~170 MB (neither hits the 512 MB ceiling). Explicit-MB
  users keep their configured RAM but it now covers ~13x the columns. Clamp band
  (`MIN 1 MB` / `MAX 512 MB`) unchanged. `ConfigValidationTest.
  timestampCacheAutoSizeTracksLodDistance` currently pins 28..36 MB at distance 256 with
  a "historic hand-tuned 32 MB" rationale — it is REWRITTEN (new band ~11..15 MB, new
  rationale text), not loosened; `at512 > at256 * 2` still holds. A new
  WantSetBudgetInvariantTest-style coherence pin ties the constants together:
  `TILE_HEAP_BYTES / 1024.0 <= TIMESTAMP_CACHE_HEAP_BYTES_PER_COLUMN` at the assumed
  fill (the drift guard the old constant's comment claims but which never existed —
  review round 1).

## 6. Persistence — format v2 + v1 migration

File stays `<dataDir>/lss-timestamps.bin`, atomic tmp+rename write, orphan-tmp sweep,
buffered IO — all unchanged.

**v2 layout:**

```
int    FORMAT_VERSION = 2
int    dimensionCount
per dimension:
  UTF  dimension key
  int  tileCount
  per tile:
    long regionKey
    int  liveCount            // sanity: must be 1..1024 and match nonzero slots
    int[1024] slots           // u32 offsets, 0 = absent (dense; no sparse encoding — §12)
```

~4.1 KB/tile → ~1.2 MB per 513²-disc dimension (today: ~4.2 MB). Load sanity bound
becomes `fileSize / TILE_RECORD_BYTES (4108)` on `tileCount`, mirroring today's
`maxPlausibleEntries` defense. A `liveCount` outside **1..1024** or disagreeing with the
scanned slots aborts the load of the remainder ("discarding rest"). Honest justification
(review round 1 — this is a *stricter* policy than today's, not the same one): today's
abort exists because a bad entry count makes the stream unpositionable; v2 tile records
are fixed-size, so the loader *could* skip just the bad tile — we abort anyway because a
liveCount/slot mismatch impeaches the writer, not just the record. Note `liveCount == 0`
is in the reject set — the writer-side invariant (§2) guarantees it never occurs in a
legitimate file.

**Tile load discipline:** each tile is **built and verified before insertion** — an EOF
or corruption mid-tile discards that partial tile whole (never a half-populated tile
with inconsistent liveCount/liveSizes); the truncation pin becomes "complete tiles kept"
(today's: "complete entries kept").

**Migration (load ladder):**

- `version == 2`: native load.
- `version == 1`: **migrate in place at load** — stream the old
  `(packed long, ts long)` records under today's `maxPlausibleEntries` defense
  (retained verbatim on this path), bucket each into its tile, and convert stamps with
  the rules **in this order** (review round 1+2 — §2.2's clamp rules alone are ambiguous
  here): (1) `ts <= 0` → **skip the record entirely** (today such an entry is stored but
  router-inert; migrating it through the pre-epoch clamp would mint a positive claim —
  the forbidden fabrication); (2) otherwise apply the §2.2 clamps. Set every loaded
  tile's `lastTouch = now` (exactly today's behavior of resetting `insertionTimes` to
  load time). One INFO line: `"Migrated timestamp cache v1 → v2 (N entries → M tiles)"`.
  The next periodic/debounced save writes v2; the v1 file is never rewritten in place.
  Known observable corner: a v1 file consisting SOLELY of `ts <= 0` records migrates to
  an empty cache, flipping `isTimestampCacheEmpty()` true and arming the review-P3
  skip-gate conjunct where today it would read false. Accepted and documented: no known
  producer of such a file exists (every stamp producer is `epochSeconds()`-derived), and
  a 0-stamp cache carries no claims.
- Any other version: warn + discard (today's behavior). **Downgrade path:** an old build
  reading a v2 file hits its own `version != 1` guard, warns, and starts cold — the cache
  is an optimization, a cold start self-heals via re-serves. No dual-format writing.
- `load()` keeps its additive merge semantics (existing entries preserved/overwritten) —
  it runs once at construction in production, but the property is test-pinned today.

**Operational notes (live Modrinth deploy, review round 2):** migration cost at boot is
trivial — a v1 file is ~4.2 MB per 513²-disc dimension, streamed once through the same
buffered reader as today — and the migration INFO line doubles as the deploy-verification
receipt for the CLAUDE.local.md log-grep ritual. Paper `/reload` across the upgrade
(old-code instance's final v1 save racing the new instance's v2 save) is last-writer-wins
with a complete file either way (atomic rename; the unique-tmp-name rationale is
unaffected) — worst case is one extra migration on the next boot.

`snapshotForSave()` returns the same type (`ColumnTimestampCache` with cloned tiles), so
`TimestampSaveScheduler` — `AtomicReference<ColumnTimestampCache>`, issue-#62 coalescing,
its whole test suite — is untouched.

## 7. What is deliberately NOT changing

- `IncomingRequestRouter.resolvedFromTimestamp` and every router rung.
- `OffThreadProcessor` call sites (`put`/`invalidate`/`invalidateStamps`/`evictIfOversized`
  cadence, `snapshotForSave` → scheduler wiring) — only the constructor argument
  conversion at line ~203.
- The miss memo (structure, TTL, `putMiss`/`isFreshMiss`/`clearMiss`/`missCount`, the
  authoritative-only seeding rules, the `invalidate` vs `invalidateStamps` split).
- `TimestampSaveScheduler` + its tests.
- The exporter/soak schema: `tscache.size_per_dimension` and `tscache.evictions` keep
  their names and meanings (`check_soak.py` requires the `tscache` section — no schema
  change, no checker change).
- The client (`ColumnCacheStore` is a different, client-side file — untouched).
- Wire format, protocol, configs' external shape (`perDimensionTimestampCacheSizeMB`
  keeps its name, 0=AUTO semantics, and clamp band).

## 8. Test plan

Tier 1 (rewrite/extend `ColumnTimestampCacheTest`; keep every current behavior pin
EXCEPT the three explicitly retired below):

- **Model-differential fuzz**: random put/get/invalidate/invalidateStamps sequences
  (no eviction) against a reference `Long2LongOpenHashMap` model — results must match
  exactly. Spec precision (review round 2): fuzz stamps are drawn from the in-range band
  (post-2025, pre-2161) so "match exactly" is satisfiable; the `ts <= 0` and clamp arms
  are covered by the truth table below, not the fuzz. This is the
  semantics-preservation proof.
- Clamp truth table: absent slot decodes to 0 (the §2 branch), `ts == epoch`,
  `ts < epoch` (skewed-clock case + the warn-once), `ts <= 0` (slot cleared,
  router-equivalent, **`clearMiss` still fires**, `size()`/booted-empty delta asserted),
  overflow clamp.
- Region/slot math pins in `PositionUtil`: negative coords, ±2^31 extremes, agreement
  with a reference floor-div implementation.
- Tile lifecycle: liveCount up/down, **the no-empty-tile invariant at all three
  decrement sites** (§2), `liveSizes` maintained at every mutation incl. eviction,
  `size()`/`sizesPerDimension`/booted-empty parity with the model.
- Eviction: tile-granular oldest-`lastTouch` victim selection, byte-budget loop, evicted
  count reporting (summed liveCount), memo-clears-first ordering (existing pin,
  re-expressed). Re-expressed at tile granularity (review round 1+2):
  `evictionIsScopedToTheOversizedDimension` (per-dimension budgets preserved),
  `loadOverCapButFileValidCountLoadsThenIsEvictable` (load may overshoot the byte
  budget; the next eviction cycle trims — the exact-trim-to-cap assertion becomes
  trim-to-byte-budget), and a NEW pin stating the deliberate weakening: a re-put
  protects its whole TILE from eviction (per-entry age is gone).
- **Retired pins, by name** (cannot be re-expressed under tiles — replaced by the
  analogues above, listed so nobody silently weakens or vainly preserves them):
  `rePutRefreshesInsertionAgeSoTheReputEntrySurvivesEviction`,
  `evictIfOversizedRemovesOldestEntries` (exact-count oldest-ENTRY selection), and
  `loadOverCapButFileValidCountLoadsThenIsEvictable`'s exact-trim-to-cap arm.
- Persistence: v2 round-trip (incl. the IO-buffer boundary case currently pinned),
  save atomicity + orphan sweep (unchanged code, keep pins), save-skips-empty-tiles
  (belt pin), **v1→v2 migration golden** (hand-written v1 byte stream → assert exact
  stamps + tile shapes; MUST include a 0-stamp and a negative-stamp record asserting
  both are ABSENT after migration), v1 truncation (complete records kept — today's pin,
  now exercised via the migration path), v2 truncation (complete TILES kept), v2
  corruption ladder (bad tileCount / liveCount 0 / liveCount-slot mismatch →
  discard-rest), unknown-version discard.
- Existing miss-memo suite: must pass UNCHANGED (it is the proof the memo was untouched).
- **Other direct constructors/callers needing mechanical conversion** (review round 2 —
  the §4 "only one call site" claim was production-only): `IncomingRequestRouterTest`
  (~lines 123, 1097 — `mbToEntries(1)` seeds), `TimestampSaveSchedulerTest` (~lines 60,
  66, 226), and `ExporterContractTest:~430`, whose
  `timestampCacheEvictionCounterAndPerDimensionSizesTrackMutations` pins ENTRY-granular
  eviction (cap 2, 3 puts, exactly 1 evicted) — that one needs a semantic rewrite:
  drive eviction with tiles in DISTINCT regions and assert summed-liveCount reporting.
  The Paper exporter twin needs nothing (it stubs `HarnessInternals` via Mockito).
- `ConfigValidationTest.timestampCacheAutoSizeTracksLodDistance`: REWRITTEN per §5 (new
  band, new rationale). There is NO Paper AUTO twin today (the Paper config suite only
  lists the key in derived-key exclusion sets) — no twin work.
- The new constants coherence pin (§5).
- Must-pass-UNCHANGED regression set (named so a v2 write/load bug is caught here
  first): `OffThreadProcessorLifecycleTest.timestampCacheSurvivesRestartAndShortCircuitsWarmRequests`
  and `...invalidationTriggersADebouncedSaveWellBeforeThePeriodicInterval`, the
  `OffThreadProcessorDiskResultTest` waits on `tscacheSizePerDimension`/`missCount`, and
  the miss-memo processor pins.

Tier 2/3: no changes expected — nothing at the gametest layer observes cache internals.

Soak (both as regression gates and live migration proof):

- `fresh-backfill` + `warm-rejoin` (the persistence path live: run 2 must converge via
  `up_to_date` with the A-laws green) on Fabric; `SOAK_PLATFORM=paper warm-rejoin`.
- **`cold-restart-resync`** (review round 2 — the strongest automated persistence gate:
  a brand-new server JVM must resync the client almost entirely via `up_to_date` off the
  persisted file; its premise IS `lss-timestamps.bin` surviving a restart).
- **Explicit migration arm**: BEFORE regenerating any base world, run `warm-rejoin` or
  `cold-restart-resync` once against the preserved PRE-upgrade `soak-worlds/base` (which
  carries a v1 `lss-timestamps.bin`), asserting the migration INFO line appears AND the
  up_to_date convergence still holds. (`./scripts/soak.sh all` runs fresh-backfill
  first, which overwrites the base with a v2 file — without this arm the gauntlet never
  exercises migration.)
- `dirty-broadcast` + `dirty-while-offline` (invalidation correctness — the dangerous
  direction: an edit must never be answered `up_to_date`).
- Manual: `./test-server.sh run-fabric` against a world carrying a real v1
  `lss-timestamps.bin` → confirm the migration INFO line and a warm rejoin converging.

## 9. Observability & docs

- Diag/exporter fields unchanged (§7). The eviction debug line keeps its shape.
- CLAUDE.md: update the `ColumnTimestampCache` bullet (tile structure, epoch-offset
  stamps, v2 file), the config section's AUTO note, the Tier 1 test-list phrase
  "eviction age" (now tile-granular last-touch), and the issue-#62 "~42 MB snapshots"
  figure (shrinks ~13x).
- Stale in-source/doc references (review round 2 sweep): `ServerConfigBase.
  effectiveTimestampCacheMB` javadoc ("AUTO provisions 1.5x… reproduces the historic
  hand-tuned 32 MB"); the `LSSConstants` comment claiming the constant "mirrors
  ColumnTimestampCache.HEAP_BYTES_PER_ENTRY" with "a drift guard" that never existed
  (replaced by the real coherence pin, §5); `docs/planning/
  config-defaults-and-clamps-review-2026-08-02.md` §3.3/§7.3 (AUTO rationale — add an
  erratum note pointing here, since CLAUDE.md calls it the full audit);
  `docs/planning/miss-memo-design.md` line ~207's `mbToEntries` reference.
- Release notes (Performance): "Timestamp cache stores ~13x more columns per MB — the
  default (auto) sizing now uses ~2.5x less memory while remembering ~5x more terrain,
  so large `lodDistanceChunks` values and roaming players stop thrashing it; existing
  cache files migrate automatically." (No claim of universally identical behavior — the
  skewed-clock corner in §2.2 is a documented regression.)

## 10. Rollout & risk

- **No config kill switch.** Justification: router-visible semantics are proven
  bit-identical by the differential fuzz + the clamp truth table; a flag would mean
  keeping both implementations and doubling the test surface. The failure direction that
  matters (stale `up_to_date`) is covered three ways: clamp-up-only rules, the fuzz, and
  the dirty-* soak scenarios. Worst-case residual bug class (lost stamps) self-heals via
  re-serve — the cache is advisory by design.
- Downgrade = cold cache (§6), accepted.
- Sequencing: after C6 merges; single PR; no release coupling (internal change, but ship
  it in the next minor with the release-notes item). Verified compatible (review round
  2): the C6 branch's exporter changes are committed and touch zero
  `tscache`/`ColumnTimestampCache` lines — but this PR edits `ExporterContractTest.java`,
  which C6 just modified, so branch off main only after the C6 merge to avoid a
  needless conflict.

## 11. Implementation order

1. `PositionUtil` region/slot helpers + pins.
2. `ColumnTimestampCache` internals: Tile structure, clamp rules, live counts, eviction;
   keep the class single-file. Delete `evictOldest`, `mbToEntries`,
   `DISK_BYTES_PER_ENTRY`/`HEAP_BYTES_PER_ENTRY` (superseded by `TILE_*` constants).
3. Persistence v2 + v1 migration loader.
4. Constructor conversion in `OffThreadProcessor` + `effectiveTimestampCacheMB` constant.
5. Test suite per §8; run Tier 1 + Tier 2.
6. Soak gauntlet per §8.
7. Docs (§9).

## 12. Deferred (recorded so nobody re-derives them)

- **Collapse-on-evict watermarks** (evicted tile → 8-byte region max-stamp): the next
  ~1000x far-field step if large-distance servers ever outgrow tiles. Requires the
  invalidation-poison flag analysis (a watermark minted over an invalidated slot can
  grant stale `up_to_date` — see the 2026-08-08 brainstorm) — real complexity, not
  needed at the 10x bar.
- **mmap/FFM persistence**: kills the save scheduler entirely; deferred for format/WSL2
  risk vs. a scheduler that already works.
- **SQLite backing tier**: only wins when RAM must be O(1) regardless of distance.
- **Sparse tile encoding / u16 quantization**: file and heap are already past the bar;
  quantization would break the lossless-stamp property for marginal gain.
