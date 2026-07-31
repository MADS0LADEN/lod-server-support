# Timestamp persistence unification — implementation plan (v1, 2026-07-31)

Companion to `timestamp-store-unification-design.md` (the WHY and the semantics; this
doc is the HOW). Scope: three phases on one branch family, each phase gated and
subagent-reviewed before the next begins, per the repo's plan-execution convention.
**Phase 0 is deliberately first and deliberately test-only** (user decision): the
client resync path is the thinnest-covered area this work later touches, and the
persistence swap must be caught by tests that existed BEFORE the swap.

Prerequisites: `feat/lod-store` merged to main. Branch: `feat/stamps-unification`
(Phase 0 may land earlier as its own PR — pure test additions are safe on main and
valuable regardless of whether the swap ever ships).

---

## Phase 0 — client resync-path regression net (test-only, no production changes)

### 0.1 What the resync path IS (the inventory the tests must cover)

`LodRequestManager` owns the client cache lifecycle:
- **Join / dimension change:** `pendingCacheLoad = ColumnCacheStore.loadAsync(server,
  dim)` — async load, adopted on completion into `ColumnStateMap` (stamps seed the
  classify ladder: cached ts>0 re-declares "send if newer"; uncached → -1; legacy
  0-stamps classify as "no data" → -1).
- **Steady state:** received columns stamp the state map; `removeAsync` forgets
  single positions (ingest-failure reports, ghost clears); dirty broadcasts revive
  via `markDirtyIfKnown`; `NOT_GENERATED` parks in `sessionSatisfied` WITHOUT a
  cache stamp.
- **Departure:** `mergeSaveAsync(server, dim, snapshot)` — MERGE-save (union with
  the on-disk map, eviction-capped) so a short session cannot clobber a long one's
  stamps; `clearForServer` on server-initiated resets; `clearAll` behind
  `/lss clearcache`; `flushPendingIo` (soak/benchmark sync flush).
- **On disk:** `config/lss/cache/<sanitized-server>/<dim>.bin`, v4 header,
  temp+atomic-rename saves, corruption/oversize/plausibility guards,
  `MAX_CACHE_ENTRIES` 2M with `mergeEvictionCap` eviction.

Existing coverage: `ColumnCacheStoreTest` (format guards), `ColumnStateMapTest`
(classify ladder), `LodRequestManagerTest` (request-loop contract). The GAP is the
*integration seam*: load→seed→declare→answer→merge-save round-trips, the async
adoption windows, and any single suite that pins persistence SEMANTICS independently
of the container.

### 0.2 New test deliverables

1. **`ClientStampsPersistenceContractTest` (the keystone).** A parameterized/abstract
   contract suite written against a narrow seam interface extracted in this phase
   (`ClientStampsPersistence`: load / mergeSave / remove / clearForServer / clearAll
   — the exact surface `LodRequestManager` already consumes, made injectable). The
   suite pins SEMANTICS, not format: merge-save unions and never clobbers; remove is
   durable across a reload; eviction cap honored with newest-retained; a second
   server's stamps are invisible to the first; load of nothing is empty, never null;
   concurrent mergeSave calls for different dims don't interleave corruptly; a torn/
   truncated backing store loads as empty (fail-open), never throws into the caller.
   Phase 0 runs it against the BIN implementation; Phase 2 runs the identical suite
   against the sqlite implementation — that is the regression net for the swap.
2. **`ClientResyncRoundTripTest`.** Drives `LodRequestManager` through the injectable
   seam with a scripted fake: join → async load completes → first scan declares
   cached ts>0 / uncached -1 (pin the exact want-set); `UP_TO_DATE` consumes;
   a dirty broadcast revives and re-declares; `reportIngestFailure` forgets the
   stamp AND the persisted entry (`removeAsync` observed); dimension change swaps
   maps without cross-dim leakage; disconnect triggers exactly one merge-save whose
   snapshot equals the state map's stamps. Also the RACE pins: a load completing
   AFTER the first scan (slow disk) must merge without clobbering fresher
   session-received stamps; a load completing after a dimension change is discarded.
3. **`ColumnCacheStoreTest` additions (bin-specific, kept until Phase 2 deletes the
   bin).** Crash-shape cases: intact main + leftover `.tmp` loads main; truncated
   main discards cleanly; merge-save onto a corrupt file replaces it.
4. **Tier 3 extension (one assertion, not a new test):** `LSSClientGameTests` gains a
   post-flush reload check — after the existing flow, flush, reload the cache for
   the server+dim, assert the received columns' stamps are present (the e2e "it
   actually persisted" pin the tier currently lacks).
5. **Soak lens (no new scenario):** `warm-rejoin` and `clearcache-mid-session`
   already exercise this live; add a `report.md`-level note only if Phase 1/2
   recalibration needs it.

### 0.3 Phase 0 exit gate

All new tests green against CURRENT code (they pin today's behavior — any red is
either a test bug or a live find, triaged before proceeding); the seam extraction is
behavior-neutral (`LodRequestManager` diff is constructor-injection only); 2-subagent
review of the suite's blind spots. Deliverable is valuable standalone even if the
rest of this plan never runs.

---

## Phase 1 — server stamps table (the correctness phase)

Design authority: the design doc §3-§7. Steps, each with its own gate:

- **1a. Stamps machinery inside `SqliteLodStore`.** DDL (`stamps_<dimId>` WITHOUT
  ROWID beside each blob table — created in `dimIdFor` and `loadDims`), new ops
  `Op.StampPut(dim, long[] pos, long[] ts)` / `Op.StampDelete(dim, long[] pos)` on
  the CONTROL queue (never shed; deletes commit immediately, same R1-M1 discipline —
  extend that pin), `loadStamps(dim, capRows)` reader API (`ORDER BY ts DESC LIMIT`),
  stamps arms in `Op.DropAll`. Unit tests: round-trip, delete durability across
  reopen, drop-all clears stamps, cap-limited load returns newest.
- **1b. `ColumnTimestampCache` dirty-delta + flush.** Per-dim dirty `LongOpenHashSet`
  under the existing lock; `drainDirtyDeltas()` seam consumed by
  `OffThreadProcessor`'s repurposed invalidation debounce → one batched StampPut per
  flush; `invalidate()` fan-out extends the existing blob `DeleteRows` op to carry
  stamp deletion; `invalidateStamps()` → `StampDelete`. Shutdown: final drain
  enqueued before `store.shutdown()`. The miss memo untouched (never persisted —
  re-pin). Unit tests: delta drain exactness (put-put-invalidate nets to nothing),
  flush idempotence, memo survives StampDelete.
- **1c. Stamps-mode factory.** `LodStores`/`SqliteLodStore` gain a stamps-only open
  (no blob tables/serving/backfill/eviction; gauges honest — no blob-tier fields
  invented). `lodStore=off` servers construct it; native-failure degrade = RAM-only
  session stamps + one warn. Tests: mode matrix (off/memory/full × native-ok/fail),
  stamps-mode accepts no deposits, diag renders truthfully.
- **1d. Boot-sweep extension.** Examined regions drop stamp rows with `header > ts`
  (strict — the design's boundary decision), vanished-region/absent-chunk drops
  mirror blob rows. Tests: both boundary sides, the seal-closure scenario end-to-end
  (edit → crash-simulated unflushed delete → reopen → sweep drops → re-resolve),
  gen-cohort survival when the write PREdates the serve.
- **1e. Migration.** `lss-timestamps.bin` present → import rows → delete file (the
  bin READER survives one release as import-only; writer dies now). Test: import
  round-trip incl. the buffered-IO boundary sizes the old suite pinned.
- **1f. Dead-code deletion.** `TimestampSaveScheduler` + test, cache save/load +
  bin-writer + atomic-rename code + their tests, `dataDir` plumbing (both service
  wirings + both `OffThreadProcessor` subclasses). CLAUDE.md + exporter docs updated
  in the same commit (the R4 lesson: docs drift red-flags reviews).
- **1g. Live gates.** `cold-restart-resync` recalibrated FROM A RECORDING (the
  design's §3.4 accepted cost: the save-trailed cohort re-resolves once, as store
  hits where enabled — law A3's inequality absorbs it; verify, never hand-wave);
  `dirty-while-offline` (should get STRONGER — offline edits now heal stamps by
  sweep); kill -9 leg extended to assert stamps consistency; full `soak.sh all` both
  platforms, plus one `lodStore=off` (stamps-mode) fresh-backfill + warm-rejoin pair
  — the configuration every default server runs.

Phase 1 exit: all above green, 2-subagent review, progress-file record.

---

## Phase 2 — client cache on the proven machinery

- **2a. `ClientStampsDb`** (new, `common/store` or client package — decide at
  review): a deliberately small standalone class (own file
  `config/lss/lss-client-cache.db`, own single writer thread, WAL, busy_timeout,
  meta version row, rows `(server TEXT, dim TEXT, pos INTEGER, ts INTEGER, PRIMARY
  KEY(server, dim, pos))`). It implements the Phase 0 `ClientStampsPersistence` seam
  — the contract suite is its acceptance test, byte-for-byte the same expectations
  the bin implementation passed. Shares PATTERNS with `SqliteLodStore` (and any
  trivially-extractable static helpers), not its lifecycle — the server class stays
  untouched.
- **2b. Swap the seam.** `LodRequestManager` constructor-injects the sqlite
  implementation in production; `mergeSaveAsync` becomes delta-marking + debounced
  flush (positions dirty since last flush) with a full merge-save retained ONLY as
  the disconnect flush; `removeAsync`/`clearForServer`/`clearAll` map to keyed
  deletes. The lazy native load happens at first LSS-server join; a native failure
  degrades to session-RAM (cold rejoins) with one client-log line.
- **2c. Migration + pruning.** First load per (server, dim): if the old bin file
  exists, import → delete. Recency eviction: global row cap (2M, matching today)
  evicting oldest-ts; dead-server pruning (rows for servers unseen in N days —
  default 90, constant not config) at open, batched.
- **2d. Deletion.** Bin writer/reader, path-sanitization, per-file guards, the
  `config/lss/cache/` tree handling (after migration support ages out — keep the
  import one release, same policy as Phase 1e).
- **2e. Gates.** Contract suite green on sqlite; `ClientResyncRoundTripTest`
  unchanged and green; Tier 3 incl. the 0.2(4) persistence assertion; soak
  `warm-rejoin` + `clearcache-mid-session` + `cold-restart-resync` (client side);
  a manual crash test (kill the client mid-session, rejoin, verify stamps survived
  to the last flush) recorded in the progress file.

---

## Cross-cutting

- **Progress file:** `docs/planning/stamps-unification-progress.md`, same running-
  record discipline as the store plan.
- **Review cadence:** 2-subagent review per phase boundary (this plan itself gets a
  3-subagent review before any code).
- **Rollback levers:** Phase 1 ships behind nothing (it replaces plumbing) — the
  rollback is the branch, so phase-boundary merges are NOT tagged releases; only the
  complete, soaked branch merges. Phase 2's swap point (2b) is a one-commit revert
  by construction (seam injection).
- **Explicit non-goals re-stated:** miss memo never persists; the up-to-date rung
  and the client classify ladder never read sqlite; no wire changes; no Paper/Folia
  divergence (stamps-mode is platform-shared `common/` code).
- **Support lines:** 26.2 only, like the store (recorded).

## Open decisions for the plan review to challenge

1. Phase 1b flush cadence: reuse the ~2 s invalidation debounce vs a dedicated
   stamps cadence (the debounce currently fires only after INVALIDATIONS — puts with
   no invalidations may need their own timer; verify against the real trigger before
   coding).
2. Stamps-mode diag surface: new `store=stamps` token vs rendering as off-with-
   stamps — pick whichever cannot mislead an admin reading `/lsslod diag`.
3. `ClientStampsDb` package placement (common/store vs client) and whether ANY code
   is genuinely shareable with `SqliteLodStore` or only the pattern.
4. Client flush cadence + the disconnect-flush blocking budget (the soak client's
   synchronous flush must stay possible).
5. Whether Phase 0 lands on main ahead of the branch (recommended: yes).
