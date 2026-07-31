# Timestamp persistence unification — SQLite `stamps` table replaces the bin file

**Status: DESIGN ONLY — not scheduled.** Depends on `feat/lod-store` merging first; must
ride its own branch with a full soak gauntlet (this touches protocol-load-bearing state
on EVERY server, store-enabled or not). Written 2026-07-31 out of the post-review
brainstorm: "do we still need the separate timestamp cache now that SQLite ships?"

## 0. The one-paragraph answer

The in-memory `ColumnTimestampCache` stays exactly what it is — the RAM-authoritative
up-to-date rung, consulted at ~50 ns on the processing thread; SQLite must never enter
that path. What this design replaces is its **durability layer**: the hand-rolled
`lss-timestamps.bin` snapshot file and the `TimestampSaveScheduler` die, and stamps
persist as tiny rows in `store.db` written through the LOD store's existing batcher.
The payoff is not elegance — it is (a) closing a real, currently-open crash-staleness
hole by giving stamps the same boot-time region-header verification the store's blob
rows already get, and (b) structurally deleting the whole-file-snapshot save model that
produced issue #62 (deltas cannot OOM; there is no snapshot).

## 1. The hole this closes (the motivating correctness bug)

Today's crash window, never yet seen live but structurally present:

1. Player edits a column at T1; the chunk save flushes to the region file (header
   stamp T1) and the dirty pipeline invalidates the RAM stamp.
2. The tscache save is debounced (`INVALIDATE_SAVE_MAX_CYCLES` ≈ 2 s cadence); the
   server crashes before `lss-timestamps.bin` rewrites.
3. Reboot loads the PRE-edit bin file: the column's stamp is back to T0 < T1.
4. A client that received the pre-edit column re-declares `ts = T0`; the up-to-date
   rung answers `T0 >= T0` → `UP_TO_DATE` → **stale seal**. Nothing consults the
   region header on this path; the client keeps pre-edit LOD until the NEXT edit of
   that same column or a cache-clearing rejoin.

The bin file is trusted as-loaded — it has no self-verification. The store, by
contrast, re-validates every row against region-header timestamps at each boot
(`src_stamp` sweep). Stamps deserve the same treatment, and once they live in the same
DB they get it from the same sweep pass at near-zero marginal cost.

Both sides of the seal close (this is the elegant core):

- **Crash BEFORE the stamp-delete persists:** the edit's chunk save moved the region
  mtime → the boot sweep re-examines that region → header T1 > stamp ts T0 → the
  stamp row is dropped → the position re-resolves honestly.
- **No crash:** the edit's invalidation fan-out (which already carries the store-blob
  `Op.DeleteRows`, immediately committed since the 4-agent round R1-M1 fix) ALSO
  deletes the stamp rows in the same op — as durable as the blob delete.

## 2. Current-state inventory (what exists and what it costs)

| Piece | Role | Fate under this design |
|---|---|---|
| `ColumnTimestampCache` (RAM maps, per-dim) | The up-to-date rung's authority; hosts the miss memo | **KEPT** — RAM authority unchanged; gains a dirty-delta set |
| `ColumnTimestampCache.save()/load()` + bin format + atomic-save + 64 KB buffered IO | Durability via whole-file snapshots of `world/data/lss-timestamps.bin` | **DELETED** |
| `TimestampSaveScheduler` (+ test) | Issue #62: latest-wins slot, one-drain bound, shutdown rejection — exists ONLY because snapshot saves could stack into ~42 MB closures | **DELETED** — the failure class is structural-deleted (no snapshots exist) |
| `OffThreadProcessor.invalidationDirty` / `invalidationCountdown` debounce | Triggers the snapshot save ~2 s after invalidations | **REPURPOSED** — same debounce now triggers the delta FLUSH |
| `dataDir` plumbing (both platforms → `OffThreadProcessor`) | Locates the bin file | **DELETED** (one ctor param, both services) |
| `perDimensionTimestampCacheSizeMB` + age eviction | Bounds RAM | **KEPT** — RAM cap still needed; eviction no longer costs durability (row stays in DB; a RAM-evicted stamp is a rung miss → re-resolve → store hit re-stamps) |
| Miss memo (`missMemoTtlSeconds` sibling map) | TTL'd authoritative-miss cache | **KEPT, RAM-ONLY** — persisting memoized misses across restart is semantically wrong; explicit non-goal |
| `invalidate()` vs `invalidateStamps()` split | Full clear vs stamps-only (memo preservation on the not-found drain) | **KEPT** — the op shapes below mirror it exactly |

## 3. Design

### 3.1 Schema

Per-dimension stamp tables beside the blob tables, reusing the existing `dims` mapping:

```sql
CREATE TABLE stamps_<dimId> (pos INTEGER PRIMARY KEY, ts INTEGER NOT NULL) WITHOUT ROWID;
```

~16-byte rows. `WITHOUT ROWID` is correct here (the plan's warning against it applied
to BLOB tables — index payload limits; stamps have no payload). No `src_stamp` column:
for a stamp, `ts` IS the content-time being compared, so the sweep rule is simply
`header > ts → drop` (see §3.4 for the boundary decision).

### 3.2 Write path — RAM first, deltas behind

- `put()` writes RAM exactly as today, and adds the position to a per-dim **dirty
  set** (a small `LongOpenHashSet` under the same lock discipline as the map).
- The existing ~2 s invalidation debounce (repurposed) drains the dirty set into ONE
  batched `Op.StampPut(dim, long[] positions, long[] ts)` on the store's **control
  queue** (unbounded, never shed). Batching means a fresh-backfill wave is a handful
  of array-carrying ops per flush, not thousands of rows of queue traffic.
- `invalidate()` (edits): the existing fan-out already enqueues the blob
  `Op.DeleteRows`; that op gains stamp-row deletion for the same positions (same
  immediate-commit discipline — R1-M1). RAM clear stays synchronous, unchanged.
- `invalidateStamps()` (the not-found drain, memo-preserving): enqueues a stamps-only
  delete op (`Op.StampDelete`) — the blob half of the not-found path already rides
  the `authoritativeMiss` ghost delete, unchanged.
- Shutdown: one final dirty-set flush enqueued BEFORE `store.shutdown()`, which
  already drains the control queue and commits on the graceful path.

Nothing on the write path blocks the processing thread beyond today's map/set writes.

### 3.3 Load path

At service construction (same point as today's `load()`): `SELECT pos, ts FROM
stamps_<dimId>` per known dim into the RAM maps, newest-first with the existing RAM
cap applied (a `ORDER BY ts DESC LIMIT <capRows>` keeps boot O(cap) even if the table
outgrows RAM — the disk table itself is unbounded and tiny). Load happens on the
caller thread before serving, mirroring today's semantics.

### 3.4 Boot-sweep extension (the verification stamps never had)

The existing sweep already stats every region and reads changed regions' headers. Add,
per examined region: `stamps` rows with `header > ts` are dropped (and rows for
vanished regions / `loc == 0` chunks drop like blob rows). Unchanged regions (mtime
gate) are trusted — identical trust model to blob rows, no extra IO.

**Boundary decision — `>` not `>=`:** a serve and a save in the same epoch second are
common during generation backfill (gen serves stamp `ts` at completion-serialization;
the chunk system's region write lands seconds later during the save flood). Strict
`>` still drops the gen cohort whose write landed in a LATER second than its serve —
accepted: those positions re-resolve once per restart, and on a store-enabled server
that wave is store hits (~100 µs each), re-stamping fresh. This is strictly MORE
conservative than today (the bin file never verified anything), and it is the price
of closing the seal. **Scenario impact to expect and recalibrate:**
`cold-restart-resync`'s counters currently assume bin-loaded stamps answer the whole
warm disc `up_to_date` with zero reads; under this design a save-trailed subset
re-resolves via store hits instead. The checker's expectations (and law A3's store
term) must be re-derived on a recording, not hand-waved.

### 3.5 Store-off servers — the DB becomes unconditional, blobs stay optional

Decision (option analysis in §6): the stamps machinery runs on EVERY server. When
`lodStore=off`, `store.db` opens in **stamps mode**: `meta`/`dims`/`regions`/
`stamps_*` tables only — no blob tables, no deposits, no serving rung, no backfill,
no size-cap eviction. `lodStore` becomes precisely "the blob tier switch", not "the
database switch". The sqlite native already ships in every release jar regardless of
config, so this adds no packaging surface.

Degrade ladder: if the native fails to load (the existing `createOrNull` path), stamps
are RAM-only for the session — one warning, cross-restart warmth lost, correctness
unaffected (cold rejoins re-resolve). This is a strictly better degrade than today's
"bin file works everywhere" ONLY in the sense that it is simpler; the honest trade is
recorded in §6.

### 3.6 Migration

First boot on the new code: if `world/data/lss-timestamps.bin` exists, import it into
the stamps tables (one pass through the existing reader, already written), then delete
the file. The imported stamps immediately face the §3.4 sweep, which retroactively
applies the verification they never had — including healing any pre-existing stale
seal. No version gymnastics: the bin reader is kept for exactly one release as
import-only code, then deleted.

## 4. Dead code deleted / net simplification

Deleted outright: `TimestampSaveScheduler` + `TimestampSaveSchedulerTest` (~2 files),
`ColumnTimestampCache.save()/load()` + bin-format constants + atomic-save-rename +
buffered-stream code + their dedicated tests (save atomicity, buffered-IO boundary
round-trip), the `dataDir` ctor plumbing through both `OffThreadProcessor` subclasses
and both service wirings, and the `lss-timestamps.bin` path handling. The #62 pin
("processor wiring reds a raw-execute revert") retires WITH the scheduler — the bug
class it pinned cannot be re-expressed when no snapshot save exists.

Kept-but-simplified: the invalidation debounce (fires a delta flush instead of a
snapshot schedule); `ColumnTimestampCache` itself shrinks to maps + dirty-set + memo.

New code: two small `Op` variants + the stamps-table DDL/apply/load/sweep arms inside
`SqliteLodStore` (all following existing patterns), and the stamps-mode gate in the
factory. Net line count goes DOWN; more importantly, the number of independently-aging
persistence mechanisms for column truth goes from two to one.

## 5. Explicit non-goals

- The miss memo never persists (unchanged rule).
- The up-to-date rung never reads SQLite — no on-miss DB fallback; a RAM-evicted
  stamp costs a re-resolve, same as today.
- No stamps for the CLIENT cache (`ColumnCacheStore` is client-side and out of scope).
- No change to the wire, the want-set model, or any law's terms (A3's store-hit
  inequality absorbs the restart re-resolve wave; verify, don't assume).
- v16 shim untouched (stamps are server-internal).

## 6. Decision log — options weighed

| Option | Verdict | Why |
|---|---|---|
| Full swap: rung reads SQLite | REJECTED | ~50 ns map read → 1-10 µs JNI point read on the processing thread's hottest rung; no read-your-writes from an async batcher |
| Stamps table only when `lodStore != off` | REJECTED | Store-off servers (the DEFAULT) would lose cross-restart up-to-date — a regression |
| Keep bin file as store-off fallback | REJECTED | Two durability paths forever; complexity strictly increases — the opposite of the goal |
| **Stamps-mode DB on every server (chosen)** | — | One path; native already ships; degrade = RAM-only session (benign); the sweep verification applies everywhere |
| Targeted fix only (boot-time suspicion of loaded stamps, keep bin) | VIABLE FALLBACK | A fraction of the work for most of the seal value; loses the #62-class deletion and the two-systems unification. If this design slips, do this instead |
| Sweep boundary `>=` instead of `>` | REJECTED | Would drop every same-second serve+save stamp — the whole gen cohort — for marginal extra conservatism |

## 7. Test plan

- **Unit (new):** stamp round-trip through the batcher (put-flush-load); the fan-out
  DeleteRows also kills stamp rows (and commits immediately — extend the R1-M1 pin);
  `invalidateStamps` op is stamps-only (memo survives — extend the existing memo
  twins); sweep drops `header > ts` and keeps `header <= ts` (boundary pin, both
  sides); vanished-region stamp drop; stamps-mode factory (lodStore=off opens no blob
  tables, accepts no deposits); bin import-then-delete; degrade to RAM-only.
- **Unit (retired):** the scheduler suite; the save/load bin suite.
- **Soak gates:** `cold-restart-resync` (the primary — recalibrate its zero-read
  expectation per §3.4), `warm-rejoin`, `dirty-while-offline` (offline edits now
  heal through the stamp sweep too — this scenario should get STRONGER), the kill -9
  leg (stamps must recover to a consistent-as-of-last-commit state), and one full
  `soak.sh all` on both platforms before merge.
- **Checker:** `tscache.*` snapshot fields keep their meaning (RAM-side counters);
  add a `store.stamp_*` group only if diagnosis demands it — resist schema growth.

## 8. Phase 2 (follow-on): the CLIENT cache is the same shape, simpler

`ColumnCacheStore` (client) is per-server-per-dimension stamp files under
`config/lss/cache/` — whole-map snapshot saves (temp + atomic rename), hand-rolled
corruption/plausibility/oversize guards, a 2M-entry cap, and PATH-SANITIZED filenames
derived from server addresses. It should become the second consumer of this design's
stamps machinery, as a follow-on phase once the server side proves the pattern:

- One `lss-client-cache.db` with rows keyed `(server, dim, pos) → ts`; the meta
  version row replaces the v4-header discard dance; WAL replaces the corruption
  guards; keyed rows DELETE the path-sanitization surface outright; recency eviction
  replaces the per-file cap AND prunes dead servers' stamps (today: unbounded file
  accumulation across visited servers).
- Strictly simpler than the server side: **no sweep** (the client cannot verify
  freshness locally — that is the server's job via re-declaration), no fan-out, no
  blob tier. RAM stays authoritative at runtime exactly as today (loaded at join).
- The win is durability for the crash-prone population: a client crash today loses
  everything since the last snapshot and re-declares `-1` — a full re-download of
  terrain it already had (expensive at 256 distance). Deltas shrink that to ~one
  flush interval. NOTE the one thing this does NOT fix: cache-vs-consumer divergence
  (LSS stamps claiming data Voxy's own store lost) is `reportIngestFailure`'s
  domain, identical in any container.
- Risk profile: the client resync path has THIN automated coverage (one Tier 3 e2e +
  the soak client) — the refactor risk lives there, not in sqlite. Extract the
  minimal stamps-writer as a shared `common/store` piece during Phase 1 so the
  client phase reuses proven code rather than re-implementing it. The sqlite native
  already ships in the (client+server) Fabric jar; load it lazily on first LSS-server
  join. `/lss clearcache` covers the lost delete-one-server's-file affordance.

## 9. Risks

- The batcher becomes load-bearing for protocol state on ALL servers (today it is
  opt-in machinery). Its failure latch must degrade stamps to RAM-only, never wedge
  serving — the existing `latchedOff` semantics already do this, but the stamps-mode
  tests must pin it.
- WAL churn from stamp deltas is negligible (tiny rows, batched, 2 s cadence), but
  the checkpoint/size-cap accounting must EXCLUDE stamps-mode DBs from blob-tier
  eviction logic entirely (there is nothing to evict; the gauge should not lie).
- Boot load of a very large stamps table: bounded by the RAM-cap `LIMIT` (§3.3).
- Schedule risk: this re-opens soak-validated ground for a correctness hole never yet
  observed live. That is exactly why it is a design doc and not a branch.
