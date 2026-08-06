# Implementation plan — 2026-08-05 tri-lens review fixes

Companion to `docs/planning/codebase-review-2026-08-05.md`. Scope: **all 14 verified
findings** (F1–F5, P1–P4, H1–H5) **plus the four doc fixes** (D1–D4). The six
unverified items (P5–P7, H6–H7) are deliberately out of scope — they were never traced
by the orchestrating session, P5 sits on heavily pinned pacing semantics, and none is a
behavior defect.

Branch: `fix/review-round-2026-08-05` off `main`. One PR; commits grouped per area
(client, store, server probe/save-hook, Paper lifecycle, docs). Gauntlet before PR:
Tier 1 (`:fabric:test -x runGameTest -x runClientGameTest`), Tier 2 (`:fabric:runGameTest`),
`:paper:test`, and Tier 3 (`:fabric:runClientGameTest` — client scanner/state map are
touched, so Tier 3 earns its cost this round).

---

## Client (fabric/networking/client)

### F1 — exclusion-radius shrink must reset the confirmed ring (`SpiralScanner`)

**Defect recap:** `scan()` lets vanilla-rendered (excluded) positions confirm their ring
(`SpiralScanner.java:457`), and nothing resets `confirmedRing` when the exclusion radius
shrinks — a stationary player dropping render distance 32→8 leaves rings 9–32 newly
LOD-needing but below the confirmed prefix, structurally unreachable until movement.

**Edit:** new field `private int lastExclusionRadius = -1;`. At the top of `scan()`
(before the `hasActionableRetries` reset at `:429`):

```java
if (this.lastExclusionRadius >= 0 && exclusionRadius < this.lastExclusionRadius) {
    // Vanilla view shrank: rings inside the OLD exclusion are newly LOD-needing but may
    // sit below the confirmed prefix — restart the walk from ring 0 (cheap, once per change).
    this.confirmedRing = 0;
}
this.lastExclusionRadius = exclusionRadius;
```

Reset `lastExclusionRadius = -1` in `reset()` (`:519`) alongside the other session state.
A *grow* needs nothing: newly excluded positions are skipped without breaking
confirmation, and the shrink-back is what this fix catches. Also extend the `:419-428`
comment ("a mark whose position slipped INSIDE the exclusion…") to note the shrink case
is now handled here.

**Tests:** new Tier 1 test next to `lodDistanceShrinkThenGrowRescansTheOuterBandWithoutStranding`
(locate its class; same rig): satisfy a disc at exclusion radius 12, confirm rings, shrink
the passed `viewDistance` to 8, assert the next scan declares the 9–12 band without
movement. Plus a no-reset assertion for a *grow*.

**Pins:** the movement/retry/dirty reset family is untouched; `predictedWalkCost` is
unaffected (a shrink reset makes the next walk full-price — correct, it has real work).

### F2 — ingest-failure strikes must count deliveries, not consumer reports, on the clear path (`ColumnStateMap.onIngestFailed`)

**Defect recap:** the lost-*content* path absorbs sibling consumers' reports for the same
delivery via the `old == -1` guard (first report removes the stamp). The lost-*clear*
branch restores a >0 stamp (`put(packed, clearPreStamp)`, `:329`), so a sibling's report
increments `ingestFailures` again — 2 strikes per delivery with 2 consumers, parking
after 2 failed clear deliveries instead of 4. Worse (found while planning): a sibling
report arriving *after* the park re-enters the cap branch, finds `clearedResync` already
removed, and takes the lost-content park flavor — destroying the deliberately retained
pre-clear stamp (`persistentRemovals` + stamp drop), which recreates the permanent
ghost-terrain hole the stamp retention exists to prevent.

**Edits:** two guards in `onIngestFailed` (`:283`):

```java
long old = this.timestamps.get(packed);
if (old == -1L) return;
// Parked: any further report is a sibling echo of the capping delivery (a parked
// position is never re-declared; revival via markDirtyIfKnown removes the mark first).
// Absorbing it protects the clear-flavor park's retained pre-clear stamp.
if (this.sessionSatisfied.contains(packed)) return;
long clearPreStamp = this.clearedResync.getOrDefault(packed, -1L);
// Sibling echo of an already-handled lost clear: the first report already restored the
// pre-clear stamp, so stamp == pre-clear + a live retry mark identifies "this delivery
// was processed". A fresh clear delivery always sets the stamp to the CLEAR's ts
// (≠ pre-clear except same-second edit collisions, which the retry term disambiguates:
// delivery consumes the mark via onReceived before any report can arrive).
if (clearPreStamp > 0 && old == clearPreStamp && this.retry.contains(packed)) return;
```

then the existing `addTo` and branches unchanged (the clear branch re-uses the
`clearPreStamp` local). Note `onReceived` (`:190-202`) removes `retry` and `clearedResync`
on every delivery, so the guard state cannot leak across deliveries: a re-served clear
re-arms `clearedResync` via `markAuthoritativeClear` after `onReceived` cleared the retry
mark, making `retry.contains == false` at the next first report.

**Tests (Tier 1, `ColumnStateMapTest` or wherever `ingestFailureCapCountsDeliveriesNotConsumerReports`
lives):**
1. clear-path twin of the pinned content-path test: two consumer reports for one rejected
   clear delivery = ONE strike (park still requires MAX_INGEST_FAILURES distinct deliveries).
2. post-park sibling echo leaves the retained >0 stamp and `sessionSatisfied` intact (the
   stamp-destruction bug above).
3. a *re-served* clear delivery followed by a report still counts (the guard must not absorb
   legitimate next-delivery strikes — drive via `onReceived` + `markAuthoritativeClear` + report).

**Pins:** the pinned content-path test is untouched and must stay green. Park semantics
(honest stamp retention/drop split) unchanged.

### P2 — movement-prune hysteresis (`LodRequestManager.tickMovementPhase`)

**Defect recap:** every chunk crossing (~2.7 Hz in flight) iterates the entire per-column
state (~263k timestamps + sibling sets + tracker + RTT stamps at distance 256) even when
nothing is out of range — plausibly a dropped frame per crossing. The prunes are
memory-bounding, not latency-critical.

**Edits:** in `LodRequestManager`: new fields `lastPruneChunkX/Z` + constant
`static final int PRUNE_HYSTERESIS_CHUNKS = 8;`. In `tickMovementPhase` (`:253`):
`recenter()`, the trace event, and `lastChunkX/Z` update keep firing on **every**
crossing (scan-correctness + pinned cadence semantics untouched); the three prunes
(`columns.pruneOutOfRange`, `tracker.pruneOutOfRange`, `metrics.pruneRttStampsOutOfRange`)
run only when `chebyshev(lastPruneChunkX/Z, playerCx/Cz) >= PRUNE_HYSTERESIS_CHUNKS`,
then update the anchor. Teleports (distance ≥ 8) prune immediately by construction.
`onDimensionChange` resets the anchor beside its other resets. Prune calls keep passing
the *current* center — deferral means out-of-range entries linger ≤8 chunks past
`getPruneDistance()`, a bounded memory slack, while `trim()`'s rebuild condition
(`removed > remaining`) actually benefits from batched prunes.

**Tests (Tier 1, `LodRequestManagerTickTest`):** crossing 1 chunk does not prune (an
out-of-range entry survives), accumulated ≥8 chunks prunes it, teleport prunes
immediately, recenter/trace still fire per crossing. **Two existing pins must be
MODIFIED, not preserved verbatim** (plan review finding 2):
`movementPruneDropsOutOfRangeColumnsAndTrackingTogether` (:162-178) and
`movementPruneStillRunsOnABackpressuredTick` (:355-366) both move 3 chunks and assert
the prune fired — below the hysteresis. Their spirit (prune drops columns+tracking
together; prune still runs on a backpressured tick) survives at a ≥8-chunk move; update
both to cross the threshold. `firstTickOutsideChunkOriginKeepsThePrimedImmediateScan`
and `teleportDropsStaleWantsFromTheNextBatch` stay green as-is (10 and 300 chunks).

**Pins:** the elytra investigation pinned scan cadence + the trim() rebuild — both
preserved. The `(0,0)` init pin is untouched (anchor initialized alongside `lastChunkX/Z`).

---

## Store (common/store)

### F3 — `readerConnection()` twin leak fix (`SqliteLodStore:553-575`)

**Edits:** restructure so (a) any throw after `ds.getConnection()` closes the connection,
and (b) registration re-checks `shutdown` under the `allReaderConns` lock (shutdown's
close loop holds the same lock, so any interleaving either self-closes or is closed by
the loop):

```java
private Connection readerConnection() {
    Connection c = this.readerConn.get();
    if (c != null) return c;
    if (this.shutdown.get()) return null; // closing conns; a new one would leak
    Connection created = null;
    try {
        var ds = new org.sqlite.SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + this.dbPath);
        created = ds.getConnection();
        try (Statement st = created.createStatement()) { ...pragmas... }
        synchronized (this.allReaderConns) {
            // Re-check under the lock shutdown's close loop holds: a shutdown that ran
            // completely between the entry check and here already cleared the list —
            // registering now would leak a native handle past close (Windows
            // drop-and-rebuild hazard, see dropDimensionRows).
            if (this.shutdown.get()) {
                closeQuietly(created);
                return null;
            }
            this.allReaderConns.add(created);
        }
        this.readerConn.set(created);
        return created;
    } catch (Throwable t) {
        closeQuietly(created); // pragma-throw must not leak the unregistered handle
        this.diag.recordError();
        return null;
    }
}
```

(`readerConn.set` moves after registration so a self-closed connection is never
published to the thread-local.) Small private `closeQuietly(Connection)` helper.

**Tests (Tier 1, `SqliteLodStoreTest`):** deterministic half — after `shutdown()`, a
reader-thread `get()` returns null and registers nothing (likely already covered;
strengthen with an `allReaderConns`-empty assertion via existing test seams if present).
The interleaved half (flag flips between entry check and registration) is covered by the
lock-ordering argument above; if a cheap package-private seam exists for the registration
step, add a direct test with the flag pre-set asserting the connection ends closed and
unregistered — otherwise document in the PR that this path is inspection+review verified
(it is 6 lines under a lock).

**Pins:** none — `isBackfillRegionDone`'s `latchedOff`-only guard (`:747-751`) is
*unchanged*; it reaches `readerConnection()` whose own guards now cover the race.

### F4 — cross-dimension oldest-first eviction (`evictOldestBatch:1499`)

**Decision (the review left it open):** k-way merge on `ts` across the per-dim candidate
SELECTs — the honest "oldest across all dims" the javadoc/config docs already promise.
Proportional budgets were rejected: they still hollow a dim under skewed age
distributions and change no documented promise.

**Edits:** per-dim SELECT gains the `ts` column
(`SELECT pos, length("blob"), ts … ORDER BY ts ASC LIMIT EVICTION_MAX_ROWS_PER_DIM`),
collected into per-dim candidate lists (bounded 512/dim — worst-case pass work is today's
worst case, preserving the "one pass cannot monopolise the batcher" property and the
cap-BINDING throughput the v0.9.0 rework measured). Merge by ascending ts — a single
sorted merged list is fine at ≤512×dims entries (plan review finding 9; no PriorityQueue
needed) — and **replicate the exact consumption rule** (`while (remaining > 0)` checked
BEFORE decrementing, so the last victim may overshoot the deficit, exactly like `:1509-1511`
today; single-dim behavior stays bit-identical). Group victims per dim, then run the
existing per-dim ladder **unchanged in order**: tombstones → backfill un-mark →
commit → `deleteRows` (crash-ordering comment at `:1519-1524` stays valid). Update the
method javadoc.

**Tests (Tier 1, `SqliteLodStoreTest` cap-eviction group):** two dims with interleaved
`ts` ages and a deficit that today would be absorbed entirely by the HashMap-first dim;
assert survivors are the globally newest rows regardless of dim, and backfill un-marks
fire in both dims. Existing single-dim cap pins (batch sizing, one-shot log, un-mark
before delete, treadmill fix) must stay green — single-dim behavior is bit-identical by
construction (same SELECT bound, same ladder).

### F5 — writer-failure rollback must un-poison the `sweepReopened` memo (`:1093-1108` / `:890-907`)

**Decision:** track-and-prune (per-txn list), not immediate commit — an immediate commit
per first-deposit-per-region would break the 64-row txn batching that deposit throughput
relies on, for a failure-path-only defect.

**Edits:**
- new batcher-thread field `private final ArrayDeque<long[]> txnReopened = new ArrayDeque<>();`
  (`{dimId, rpos}` pairs). In the deposit path, when `sweepReopened…add(rpos)` returns
  true, append to `txnReopened` **BEFORE the `DELETE FROM regions` executes**
  (`:1099-1107`) — if `executeUpdate()` itself throws, the failure handler must already
  hold the entry to prune the memo (plan review finding 6).
- `commitTxn()` (`:1173`): clear `txnReopened` after a successful commit (covers the
  shared-txn commit *and* the mid-deposit immediate commits, which flush the same txn).
- extract the two rollback sites (`:897`, `:920`, plus any other `writer.rollback()`
  found by grep) into a private `rollbackTxn()` that rolls back, zeroes `txnRows`, and
  drains `txnReopened` removing each `rpos` from `sweepReopened.get(dimId)` — so the next
  same-region deposit re-executes the regions-DELETE instead of skipping on the memo.
- `runSweep`'s full `sweepReopened.clear()` (`:1631`) is unchanged (a failed sweep leaving
  entries behind is conservative — costs one redundant delete, never staleness).

**Tests (Tier 1, `SqliteLodStoreTest` C3 fault-seam group):** using the existing
injected-writer-failure seam: deposit to region R (memo set, DELETE applied in txn) →
force the next op to fail (rollback) → deposit to R again → assert the regions-DELETE
re-executed (observable via the seam's statement log if it has one, else via sweep
behavior: the region must be re-judged — seed a stale `seen_mtime`, verify the follow-up
sweep re-examines instead of `==`-skipping).

**Pins:** the C3 delete/latch invariants (rollback-surviving deletes, re-queued failed
deletes, the latch ladder) are untouched — `rollbackTxn()` is a pure extraction plus the
memo prune.

### P4 — cached reader SELECTs (`get:453` / `getFrame:515`)

**Edits:** per-instance `ThreadLocal<HashMap<Long, PreparedStatement>>` keyed by
`(dimId << 1) | kind` (kind 0 = get, 1 = getFrame), populated lazily beside
`readerConnection()`. Statements die with their connection (sqlite-jdbc closes statements
on `Connection.close()`), and the maps are per-store-instance so a drop-and-rebuild
(new instance) can never serve stale handles. On any `Throwable` in `get`/`getFrame`,
remove the cached statement for that key before the existing triage (a broken statement
must not wedge the thread's future reads). ResultSets stay try-with-resources; the
statements themselves are no longer closed per call.

**Tests:** existing read-path suite exercises this transparently (hits, misses,
integrity-purge, post-shutdown null). Add one test: two sequential gets on the same
thread + an interleaved `getFrame` all correct (exercises cache reuse across kinds).

### H4 — `MemoryLodStore` shed-loop shutdown checks (`:179-184`, `:197-204`)

**Edits:** match the SQLite twin's shape — both shed loops re-check `this.shutdown.get()`
each iteration and bail (`return false`) instead of parking entries in a dead queue.
Comment states the twin-consistency rationale. (The race narrows rather than closes —
an offer can still land just after the check — but the bounded-at-capacity residual is
the same one the SQLite twin accepts.)

**Tests:** existing `MemoryLodStoreTest` green; add shutdown-then-deposit-returns-false
if not already covered (the in-loop interleaving itself is not deterministically drivable
without a seam this tier doesn't have — twin-consistency change, inspection-verified).

---

## Server probe / save hook

### P1 + D4 — stop re-serializing served/answered columns in the probe pass

**Defect recap:** the probe filter checks only `hasEnqueuedColumn` (`RequestProcessingService.java:814`,
Paper twin `:1043`/`:1139`). After send success the position stays in the published
want-set until the next declaration (≤1 s), getting fully re-serialized on the main
thread every tick and discarded by the router as a duplicate; positions resolved
`up_to_date` (warm-rejoin resyncs) never enter `enqueuedColumns` and get zero filtering.

**Design:** one new any-thread map on `AbstractPlayerRequestState`:
`ConcurrentHashMap<Long, Long> probeSuppress` + constant
`PROBE_SUPPRESS_TTL_MILLIS = 1500` (covers the full 1 Hz declaration window with slack;
the 500 ms departure grace is deliberately NOT reused — its TTL and stamp discipline are
pinned for the router's duplicate-serve semantics, and reusing `departedColumns` for
up_to_date stamps would violate the stamp-on-send-success-only pin).

- **Stamp sites:** (a) `flushSendQueue` beside `stampDeparted(...)` (`:621`) — send
  success. The probeSuppress stamp is a **sibling call, never nested inside
  `stampDeparted`** (which early-returns when `departureGraceNanos <= 0` — grace-disabled
  rigs must not silently lose suppression; plan review finding 5). (b) ALL FIVE
  `ColumnUpToDate` emission sites via one shared helper: the router's three
  (`IncomingRequestRouter.java:231,321,340`) **plus `OffThreadProcessor.java:993`
  (disk-result all-air/oversized terminal up_to_date) and `:1366` (generation-flavor
  twin)** — review finding 4; all run on the processing thread, CHM-safe.
- **Read sites — three, not two** (review finding 5): Fabric
  `RequestProcessingService.java:814`, Paper pump sync-probe `PaperRequestProcessingService.java:1043`,
  and the Folia regionized hold path's `snapshotProbePositions` filter (`:1139`). Each
  becomes `if (state.hasEnqueuedColumn(packed) || state.isProbeSuppressed(packed)) continue;`.
  `isProbeSuppressed` mirrors `isWithinDepartureGrace`'s shape (expired-entry removal on
  read; any thread).
- **Clearing:** both `clearDiskReadDone` overloads (`:744`, `:751` — dirty-clear events
  and honest re-resolution) also remove the position from `probeSuppress`, so an edited
  column's re-serve is probe-eligible immediately; the map dies with the state
  (removal/dimension change). Sweep: piggyback `sweepDepartedColumns` (`:672`) with the
  probeSuppress TTL so unconsulted stamps can't outlive their window.

**Why over-filtering is acceptable (corrected by plan review finding 3 — NOT airtight):**
a re-declared ts>0 ask resolves at the timestamp rung, and a ts≤0 re-ask **inside** the
departure grace resolves at the duplicate rung — for those the probe result was
guaranteed-unused. But a ts≤0 re-ask landing in the **(grace, TTL] window** — the
genuinely-lost-delivery case (ingest failure → ts=-1 re-declare ~0.5–1.25 s after
departure) — clears its done-bit, declines the timestamp rung, finds no probe entry, and
takes a SYNC slot + disk read (possibly a generation ticket for a loaded-never-saved
chunk) where today it is served from the in-memory probe. This is an ACCEPTED cost/source
shift on a rare path: the result is still correct, and it is bounded to one cycle — the
honest re-resolution's `clearDiskReadDone(:751)` hook also clears the suppress mark, so
a retained entry probes again next cycle. Watch item: Tier 3's ingest-failure recovery
loop (`LSSClientGameTests`) — the re-serve may travel disk/generation with longer
latency; confirm its deadline tolerates it. The edit-within-TTL corner (dirty clear
un-stamps → probe-eligible) is handled by the clearing above. Send-FAILURE drops never
stamp (same site discipline as `stampDeparted`), keeping that loss class instant-heal.

**D4 rides along:** rewrite the `:786-787` javadoc sentence to describe the filter's
actual reach (enqueued + suppressed within TTL) instead of the false "one re-probe".

**Tests (Tier 1):** `AbstractPlayerRequestState` unit tests — stamp on send success,
stamp expiry, clear-on-dirty, clear-on-single-position; service-twin test (both
platforms' harnesses drive probes through injection seams): a position served and
flushed is not re-probed next tick while a re-listing want-set is published; an
up_to_date-resolved position likewise; after a dirty clear it probes again. **Plus a
Folia-path test** (review finding 5): the `snapshotProbePositions` filter honors the
suppress mark (`RegionProbeSchedulingTest` territory).
**Soak relevance:** none expected — filtered probes were unused by the router by
construction; `in_memory` serves for *unresolved* positions are unaffected. Watch the
Tier 2 `ServiceLifecycleGameTests` in-memory probe test (it probes never-served
positions — unaffected) and `TwoPlayerGameTests` fan-out (dirty-clear path — covered by
the clearing).

### P3 — skip the dirty-content hash while the server has never had an LSS client (Fabric)

**Decision (narrowed hard from the review's sketch, which underestimated the coupling):**
gate = `!service.hasEverRegisteredPlayer() && store inert`. The review's per-position
"no cached stamp/store row" checks are NOT implementable cheaply or safely: a store-row
probe from the save hook is IO on a foreign thread, skipping hashes while the store holds
rows lets an online edit leave a stale store row serving hits for the rest of the session
(the sweep only re-judges at boot on Fabric), and a "bare dirty mark" fallback per save
would gut store warmth by invalidating on metadata-only re-saves — the exact thing the
filter exists to suppress. The never-registered + store-off conjunction makes the skip
provably free: no tscache entries exist (only serves populate it), no client holds
anything, no store row can go stale (none is consulted), and dirty marks have no
audience — so *nothing* the hash maintains is observable.

**Edits:**
- `RequestProcessingService`: `private volatile boolean everRegisteredPlayer;` set in
  `registerPlayer`; accessor `hasEverRegisteredPlayer()`.
- "store inert" = the service's `LodStoreService` is null/off-mode (check the actual
  `getLodStore()` shape at the call site — `LodStores` factory's null-store degrade).
- `LSSServerNetworking.onChunkSaveData` (`:105`): after the existing service/enabled
  guards, `if (!service.hasEverRegisteredPlayer() && storeInert) return;` with a comment
  explaining the latch (flips once, first handshake) and the accepted cost: positions
  saved during the skip era have no stored hash, so their first post-registration save
  reads absent-hash → changed → one spurious dirty mark + broadcast each (bounded by
  loaded chunks, drained per broadcast interval).
- The filter-monitor serialization hoist stays NOT done (soft-pinned in-code as accepted).

**BLOCKER resolved in plan (review finding 1):** the Tier 2 test the gate would red is
NOT `SerializerParityGameTests` (its filter tests construct their own
`DirtyContentFilter` instances — safe) but
`TwoPlayerGameTests.editedColumnPropagatesToBothHoldersThroughBroadcastFanout`
(`:325`, `:341-343`, `:401-405`): it registers its mock players on its OWN service but
asserts the save hook marked dirty through the LIVE service's filter/tracker — the live
service has no registered player in that test, so the gate would skip and red the exact
test whose failures are documented "treat as a real regression, never re-run".
**Fix:** add a package-visible seam `RequestProcessingService.armSaveHookForTest()`
(sets the latch) and call it at the top of that gametest step with a comment. The latch
is one-way and the skip is an optimization no Tier 2 test pins, so arming the live
service mid-suite cannot invalidate other tests. Soak side verified by the plan review:
`dirty.*` are monotonic-only counters with no pre-join floor, and the dirty-broadcast
named check measures post-join rises — the skip is soak-safe.

**Tests (Tier 1):** the save-hook body is already driven via seams
(`SaveHookStoreBridgeTest` / networking tests): add — never-registered + store-off skips
the serializer (count invocations via the injected serializer), first registration
resumes hashing, store-on keeps hashing even when never-registered.

---

## Paper lifecycle

### H2 — v16 session leak on the lifecycle sweep (`PaperRequestProcessingService:816-826`)

**Edit:** beside `this.v18Compat.onDisconnect(uuid)` (`:824`) add
`this.v16Compat.onDisconnect(uuid);` — the sweep IS a disconnect (the in-code comment
already says so; it explicitly scoped the v16 twin out). Fabric needs no twin: its
disconnect event is connection-scoped and always fires (`LSSServerNetworking:289-290`
calls both managers).

**Tests:** the existing sweep test for the v18 half (execution-review finding 2's pin)
gets a v16 assertion beside it: register a v16 session, sweep-remove the player, assert
`V16CompatManager` no longer holds the session (its `sessions` map via the existing
`sessionForTesting` seam).

### H5 — `onDisable` unregisters channels before service shutdown (`LSSPaperPlugin:182-196`)

**Edit:** reorder to: null the field (unchanged, stops pump fires) → 
`getServer().getMessenger().unregisterIncomingPluginChannel(this)` → `service.shutdown()`.
Frames already *dispatched* into `onPluginMessageReceived` remain (nothing can stop
those; everything they touch is individually thread-safe — the existing worst case), but
no *new* frame can dispatch concurrently with shutdown. Keep/extend the ordering comment.

**Tests:** if the plugin-glue tests pin onDisable ordering via seams, extend; otherwise
inspection (3-line reorder).

### H3 — volatile diagnostics counters (Folia region-thread reads)

**Edits (house rule: `PaperChunkGenerationService:91-94`'s convention):**
- `TickDiagnostics`: `totalSectionsSent`, `totalBytesSent`, `totalWireBytesSent` →
  `volatile` (single-writer main/pump thread; read by `/lsslod stats|diag` on Folia
  region threads via `PaperCommands:131-133`).
- `TickDiagnostics` window state read by `getWindowBytesPerSecond()` (`ringPos`,
  `ringCount`, `windowByteSum`) → `volatile`, with a comment that cross-thread window
  reads are best-effort (array elements stay plain; a mid-reset read can misreport one
  command's rate — diag-only, bounded).
- `SharedBandwidthLimiter.totalBytesSent` → `volatile`; note on the class javadoc that
  the totals getter is the one sanctioned cross-thread read, everything else stays
  tick-thread-only.

**Tests:** none (no behavior change on the single-threaded paths; existing suites green).

### H1 — `republishHeldBatch` comment correction (+ CLAUDE.md pin wording)

**Edit (comment-only; the review judged a third guard not worth its complexity):**
rewrite `AbstractPlayerRequestState:271-276` — the current text claims a failed retract
is benign because "the pass-through's backlog replace supersedes them", but in the
retract-failure interleaving that replace happened *before* the stale take: the stale
batch's replace lands ON TOP, and the honest statement is "one interval of routing a
stale want-set, re-superseded by the client's next declaration (≤1 s); accounting stays
balanced." Also update CLAUDE.md's `republishHeldBatch` pin phrase ("never be
resurrected") to acknowledge the nanoseconds-wide retract-failure window so the pin and
the code agree.

---

## Documentation fixes

- **D1** (`LSSPaperPlugin:263-273`, `HandshakeRegistrar` javadoc): delete the "V16
  additionally creates the compat session identity FIRST (directly, not mailboxed…)"
  paragraph — production routes EVERY dialect mark through the pump's `dialectFlip`
  precisely because a direct region-thread mark is the hard-kick race (verify the
  production registrar lambda's actual shape first and describe it). This is the most
  important doc fix: a maintainer following the current text reintroduces a race.
- **D2** (`ClientColumnProcessor:32-33`): the `MAX_QUEUED_BYTES` comment's "default
  20 MB/s bandwidth cap" → the current 15 MiB default (v0.9.1); re-derive the "~13 s of
  backlog" arithmetic (~17 s at 15 MiB).
- **D3** (`PaperConfig` validate, ~`:79`): the Folia WARN message text still says "Paper
  writes lodStore=full into the file on every run" — stale (default is `off` everywhere
  since 2026-08-03). Rewrite the message (and any surrounding stale comment) to the
  current behavior: the warn fires only on an explicit `full` carried to Folia.
- **D4:** folded into P1 above.

---

## Sequencing

1. Branch `fix/review-round-2026-08-05`.
2. Commit 1 — client: F1, F2, P2 (+ their tests).
3. Commit 2 — store: F3, F5, P4, H4 (+ tests). F4 as its own commit 3 (largest single
   behavior change, isolated for revert-ability).
4. Commit 4 — server: P1+D4 (both platforms), P3 (+ tests).
5. Commit 5 — Paper lifecycle + diag: H2, H5, H3, H1 comment (+ tests).
6. Commit 6 — docs: D1–D3, CLAUDE.md pin wording (H1), review-doc status update.
7. Full gauntlet (Tier 1 + Tier 2 + `:paper:test` + Tier 3). Any red triaged against the
   Known Flakes catalog before touching code.
8. Implementation review (subagents), fixes, re-run affected suites.

## Explicitly not done (and why)

- P5 (pacing-gate aggregates), P6 (Paper dirty-event alloc/striping), P7 (boxed air-fill
  set), H6 (harness internals cross-thread), H7 (WAL double-count) — unverified by the
  orchestrating session; P5 sits on the most heavily pinned semantics in the codebase;
  none is a behavior defect. Re-verify before any future fix.
- The DirtyContentFilter monitor hoist (P3's second half) — soft-pinned in-code as
  accepted until it measures hot.
- A third `republishHeldBatch` guard (H1) — cost/benefit judged against; comment +
  pin wording instead.

---

## Plan review round (2026-08-06, one subagent, three lenses)

Verdict: line-accurate; all four contested design calls (F4 merge, F5 track-and-prune,
P1 dedicated map, P3 narrowed gate) confirmed right. Amendments applied above:

1. **BLOCKER → fixed:** P3's gate would have redded
   `TwoPlayerGameTests.editedColumnPropagatesToBothHoldersThroughBroadcastFanout`
   (live-service save-hook assertion with no registered player) — resolved with the
   `armSaveHookForTest()` seam. `SerializerParityGameTests` confirmed safe (own filter
   instances); soak dirty counters confirmed law-free.
2. **MAJOR → fixed:** P2 must modify (not preserve) the two 3-chunk-move prune pins.
3. **MAJOR → fixed:** P1's "always resolves at an earlier rung" claim was false in the
   (grace, TTL] window — re-framed as an accepted cost/source shift, bounded by the
   `:751` clearing; Tier 3 ingest-failure-loop deadline flagged as a watch item.
4. **MINOR → fixed:** P1 gains the two `OffThreadProcessor` up_to_date stamp sites
   (`:993`, `:1366`) and the Folia `snapshotProbePositions` read site + test; stamp is a
   sibling of `stampDeparted`, never nested (grace-disabled rigs).
5. **MINOR → fixed:** F5's `txnReopened` append moved BEFORE the DELETE execute.
6. F1, F2, F3, F4 (with the consumption-rule note), P4, H2, H4, H5, H1, D1–D3 verified
   sound as planned; F2 gained a comment-worthy note (a straggler report for delivery N
   landing after delivery N+1's `onReceived` counts against N+1 then absorbs N+1's first
   report — one-strike under-count, safe direction, narrow window).

## Implementation review round (2026-08-06, one subagent over the branch diff)

Zero blockers/majors. Two MINORs, both addressed in follow-up commits:

1. **P1 residual named honestly:** the (grace, TTL] window's disk-read shift has two
   sharper flavors the "still correct" framing hid — a loaded-never-saved chunk under
   generation-DISABLED parks session-permanent `NOT_GENERATED` (a new entry path into the
   documented `MAX_PROBES_PER_TICK_GLOBAL` accepted corner, same heals), and a
   loaded-with-unsaved-edits chunk serves pre-edit disk bytes (healed by its own save's
   dirty broadcast). Documented on the `probeSuppress` field. A one-cycle retain fix in
   `resolvedAsDuplicate` (clear the mark, retain the entry, let next tick's probe serve
   it) was sketched by the reviewer but NOT implemented — it edits the pinned honest
   re-resolution ladder; left as a recommendation for a future round.
2. **Probe-filter rungs now pinned:** the three `isProbeSuppressed` read sites had no
   test that would red on revert. Added `RegionProbeSchedulingTest` pins for the Paper
   pump rung and the Folia `snapshotProbePositions` rung (suppressed head skipped,
   sibling probes, dirty-clear un-suppresses). The Fabric rung has no seam (no Mockito in
   the fabric module; `probeLoadedChunks` needs a real `ServerLevel`) — it is textually
   identical to the Paper pump rung and covered by inspection + the shared state tests.

Everything else verified clean, including all five plan-review amendments, every
lifecycle path over the new fields, and the four commit messages vs the code.
