# Tech-review fix plan — 2026-07-28 round (F1–F11)

Fixes for the 11 verified findings of the 2026-07-28 full technical review (3 MAJOR, 8 MINOR).
All findings were inline-verified against main @ 59d0e66 before this plan. Branch:
`fix/tech-review-fixes`, landing as one PR with grouped commits (M1–M4 precedent, PR #65).

Severity/finding index:

| # | Sev | One-liner | Files touched |
|---|-----|-----------|---------------|
| F1 | MAJOR | Dirty-clear/invalidation events (cycle-start take) race the phase-4 fresh batch take → stale `up_to_date` seals an edited column | `OffThreadProcessor` |
| F2 | MAJOR | Movement prune + whole-file overwrite truncates the persisted client cache to a sliding disc → ghost terrain + full re-downloads on revisit | `ColumnCacheStore`, `ColumnStateMap`, `LodRequestManager` |
| F3 | MAJOR | Generation-timeout path bulk-releases tickets inline, bypassing `DeferredTicketReleases` (the C2ME 60 s freeze shape) | `ChunkGenerationService` |
| F4 | MINOR | Shutdown interrupt can preempt the sentinel → final invalidation flush dropped → pre-edit stamps persisted | `OffThreadProcessor` |
| F5 | MINOR | `requeueLosslessEvents` phase granularity: mid-phase-3 throw replays consumed generation outcomes (can un-taint) | `OffThreadProcessor` |
| F6 | MINOR | Paper hostile-frame containment logs an unthrottled stack per frame — log-flood vector | `LSSPaperPlugin` |
| F7 | MINOR | `ColumnCacheStore.save` lacks the `AtomicMoveNotSupportedException` fallback its siblings have | `ColumnCacheStore` |
| F8 | MINOR | `ColumnTimestampCache` unique tmp files orphan permanently on hard crash; no sweep | `ColumnTimestampCache` |
| F9 | MINOR | `sanitizeForFilePath` passes a bare `".."`/`"."` segment | `ColumnCacheStore` |
| F10 | MINOR | An unconsumable (vanilla-view-excluded) retry mark pins `confirmedRing`=0 → full-distance re-walk every scan | `SpiralScanner`, `ColumnStateMap` |
| F11 | MINOR | `removeAsync` does a whole-file load+rewrite per position; bursts serialize on the IO thread ahead of the load gate | `ColumnCacheStore` |

Grouping rationale: F1+F4+F5 are three leaks in the same guarantee ("an edit always
invalidates before the next answer/save") — one commit. F2+F7+F9+F11 all live in
`ColumnCacheStore` — one commit. F3, F6, F8, F10 are independent — one commit each.

---

## Commit 1 — the edit-invalidation guarantee (F1, F4, F5)

All in `common/processing/OffThreadProcessor.java`; both platforms inherit.

### F1: late drain of dirty-clears + invalidations before routing

**Root cause recap.** `processCycle` applies dirty-clears/invalidations from the
cycle-start `MailboxTake` (`:377-384`, applied `:454`), but the router takes each
player's want-set batch at route time (`IncomingRequestRouter:108`) — a strictly
fresher read. A batch arriving mid-cycle is routed against pre-clear `diskReadDone`
bits and pre-invalidation timestamp stamps → `up_to_date` off stale state → the
client's `onUpToDate` consumes the dirty mark (`ColumnStateMap:203`) → position
parks SATISFIED; no in-session heal for a stationary player.

**Fix.** Insert a *late drain* step in `processCycle` between
`processGenerationReady` and `routeIncomingRequests`:

- Under `mailboxLock`, if `pendingInvalidations` or `pendingDirtyClears` is
  non-empty, swap both out into locals (fresh containers in). Removals and
  generation outcomes are deliberately NOT late-drained — they are phase-sensitive
  and non-idempotent (a late-applied removal could sweep a same-cycle-registered
  player's state; a generation outcome consumed twice un-taints).
- Apply them exactly as `applyEvents` does. Extract the invalidation-apply and
  dirty-clear-apply bodies of `applyEvents` into private helpers
  (`applyInvalidations(List)`, `applyDirtyClears(Map)`) shared by both call sites
  so the two paths cannot drift.
- Invariant that makes this safe: any event enqueued before the batch was offered
  is now applied before that batch is routed, because the late drain happens after
  every possible batch-offer instant that routing can observe. Both event kinds are
  idempotent (re-clearing a cleared done-bit and re-invalidating an invalidated
  stamp are no-ops), so double-apply across the cycle-start take and the late take
  is harmless.

**Error/requeue handling.** Stash the late take in fields
(`lateInvalidations`/`lateDirtyClears`) with a `lateEventsApplied` flag set after
the apply completes; `requeueLosslessEvents` re-adds them to the pending buffers
when the flag is false (safe: idempotent). Clear the fields at cycle start.

**Residual window (documented, not fixed).** An event enqueued after the late
drain but before this cycle's route of a specific player is still skewed by up to
one cycle — but the batch it could stale-answer must have been offered *after* the
late drain too, i.e. the client re-asked before receiving the dirty notice that
follows the clear event. The broadcaster enqueues `clearDiskReadDone` *before*
sending the `DirtyColumnsS2CPayload` (`DirtyColumnBroadcaster:137→139`), so a
re-ask *caused by* the notice can no longer be routed ahead of its clear. That is
the actual correctness contract; state it in the comment replacing the
broadcaster's now-stale ordering comment (`DirtyColumnBroadcaster:132-136`).

**Test seam + pins.** Add a `protected void beforeRouteHook()` no-op invoked just
before the late drain. New Tier-1 pins in `OffThreadProcessorDiskResultTest` (or a
new `OffThreadProcessorLateEventTest`):
1. `dirtyClearEnqueuedMidCycleAppliesBeforeRouting` — hook enqueues
   `clearDiskReadDone` + offers a ts>0 batch for a done-marked position; assert the
   route does NOT answer up_to_date (a disk read is submitted instead).
2. `invalidationEnqueuedMidCycleAppliesBeforeRouting` — same shape via
   `invalidateTimestamps` + a stamp that would otherwise satisfy
   `resolvedFromTimestamp`.
3. A requeue pin: hook's apply throws (seam) → late events re-queued, applied next
   cycle (assert eventual clear).

**Pinned-decision check.** `resolvedAsDuplicate` deliberately not clearing
done-bits (duplicate-serve grace) is untouched — the grace path answers only
in-pipeline/ts≤0 shapes; this fix changes *when* clears become visible, not what
they mean. `TwoPlayerGameTests` fan-out keeps its retries (they now should never
fire; a follow-up could tighten, but do not weaken the pin in this round).

### F4: interrupt must not skip the sentinel's invalidation flush

**Fix.** Two catch-site changes in `processingLoop`:
- Wait-site (`:373-375`): on `InterruptedException`, re-check `pendingSnapshot`
  (we hold `mailboxLock`): if non-null, fall through and process it normally (the
  sentinel branch already flushes invalidations); if null, flush
  `pendingInvalidations` to `timestampCache` and return.
- Backoff-sleep site (`:417`): flush pending invalidations (under `mailboxLock`,
  swap-out then apply) and return. Note the failed cycle's `requeueLosslessEvents`
  ran before the sleep, so its unapplied invalidations are back in the pending
  buffer and covered by this flush.

Both flushes run on the processing thread — `ColumnTimestampCache` stays
single-owner. `shutdown()`'s save already waits for `!isAlive()`, so the save
happens after the flush.

**Pins.** Extract `flushPendingInvalidationsOnExit()` package-visible and pin it
directly (queued invalidations reach the cache); add a lifecycle test in
`OffThreadProcessorLifecycleTest`: post invalidations while the thread is parked
in the error backoff (drive 10 seam-forced cycle failures), call `shutdown()`,
assert the final snapshot has the stamps invalidated. If the backoff drive proves
too slow/flaky for Tier 1, pin the helper + the wait-site fall-through only, and
note the sleep-site as covered by the helper pin.

### F5: per-outcome containment in `processGenerationReady`

**Fix.** Wrap the body of the per-outcome loop (`:959-1055`) in `try/catch
(Throwable)`. On catch: log via `LSSLogger.error` (rare — no throttle), best-effort
release the entry's pending slot (`removePendingByPosition` on the holder state if
present — mirror the cleanup the normal path performs), count the drop as
`superseded` (the standard transient-drop counter; the client re-declares and the
miss re-escalates — same heal as every other silent drop), and continue with the
next outcome. The failed outcome is consumed-by-drop: `requeueLosslessEvents` will
never replay it because the phase now always completes.

**Soak-law note for the reviewer.** The drop happens after
`ChunkGenerationService` counted the outcome (completed/timeout books already
balanced); it touches no disk counters, so laws A4/A5 are neutral. `superseded`
is monotonic-required — incrementing is safe.

**Pins.** Extend `OffThreadProcessorDiskResultTest`:
1. `midListOutcomeFailureDoesNotReplayConsumedOutcomes` — 3 outcomes, #2's payload
   build throws (existing `throwOnNextSubmit`-style seam, new
   `throwOnPayloadBuildFor(cx,cz)` seam): #1 and #3 delivered exactly once, #2
   never delivered, nothing re-delivered next cycle.
2. A tainted variant: the thrown outcome is stale-tainted; assert the taint is not
   consumed-then-resurrected (no stamp, no `diskReadDone` for it).

---

## Commit 2 — client column cache lifecycle (F2, F7, F9, F11)

### F2: merge-on-save with honesty removals + cap eviction

**Design constraints discovered in review:**
- Naive union (file ∪ memory) is WRONG: it resurrects honesty removals — the
  ingest-failure unstamp (`ColumnStateMap:292,322`) and the legacy-0 purges
  (`onUpToDate:219`, `onNotGenerated:245`) remove stamps precisely so the client
  stops claiming data it doesn't hold. Resurrecting them re-opens the
  delivery-honesty hole (false `up_to_date` next session).
- The loader caps files at `MAX_CACHE_ENTRIES` (2M) and discards oversized files
  wholesale — a merged file must never exceed the cap or the whole cache dies at
  next load.

**Fix.**
1. `ColumnStateMap` gains `persistentRemovals` (a `LongOpenHashSet`), recorded at
   the four honesty-removal sites above (NOT at `pruneOutOfRange` — range pruning
   is exactly what must stop implying file deletion). Accumulates for the session
   (tiny: ingest failures are capped at 3/position, legacy purges are one-time);
   cleared by `clear()`. Accessor `snapshotPersistentRemovals()`.
2. `ColumnCacheStore.mergeSaveAsync(serverAddress, dimension, columns, removals,
   centerCx, centerCz)` replaces `saveAsync` for the `saveCache` path. On the IO
   thread: `existing = load(...)` (the existing load already degrades corrupt /
   wrong-version / oversized files to an empty map — merge then degrades to
   overwrite, never fails the save); delete `removals` from `existing`; overlay
   the memory snapshot (`existing.putAll(memoryCopy)` — memory wins); if
   `size > MAX_CACHE_ENTRIES`, evict farthest-first from `(centerCx, centerCz)`
   (sort packed keys by Chebyshev distance, keep nearest `MAX_CACHE_ENTRIES`;
   in-memory entries are within prune radius of the center so the hot disc always
   survives); write via `save()`. Empty-result handling mirrors `removeAsync`
   (delete the file rather than skip).
3. `LodRequestManager.saveCache()` passes `lastChunkX/lastChunkZ` and the removal
   snapshot. Both call sites (dimension change, disconnect) get merge semantics.
4. `ColumnStateMap.pruneOutOfRange` calls `trim()` on the map and the seven
   sets/maps when a prune removed more entries than remain — the client twin of
   the M3 server-side sweep-trim (fastutil never shrinks; a 2M-entry load pruned
   to a disc otherwise leaves a ~40 MB backing array resident all session).

**Interactions checked:** `flushCache` clears memory + files → next save writes
fresh (merge against nothing). `removeAsync` (cross-dim) already writes through
to the file directly and stays as-is (plus F11 coalescing). The async-snapshot
contract (`saveAsyncSnapshotsBeforeCallerMutates` pin) must hold for the removal
set too — snapshot both under the caller's thread before queueing.

**Pins (new, `ColumnCacheStoreTest` + `ColumnStateMapTest`):**
- merge preserves a file-only (range-pruned) entry; memory wins on common keys
- an ingest-failure unstamp is NOT resurrected by the next merge-save
- a legacy-0 purge is NOT resurrected
- cap eviction keeps the nearest entries, output ≤ `MAX_CACHE_ENTRIES`, file loads
  cleanly afterward
- corrupt existing file degrades merge to plain overwrite (save still succeeds)
- `persistentRemovals` recorded at all four sites; cleared by `clear()`
- prune-trim: backing capacity shrinks after a large prune (assert via
  fastutil `trim()` return or a capacity proxy)

### F7: atomic-move fallback (one-liner)

`save()` catches `AtomicMoveNotSupportedException` → plain
`REPLACE_EXISTING` move, mirroring `JsonConfig.save:52` /
`ColumnTimestampCache.save:289` (same comment). No test (untestable on a normal
FS; parity with tested siblings).

### F9: sanitizer bare-dots edge

`sanitizeForFilePath`: after `replaceAll`, map a result that is empty or
all-dots to `"_"`. Test rows: `".."` → `"_"`, `"."` → `"_"`, `"..."` → `"_"`,
embedded case unchanged (existing pin `hostileServerAddressCannotEscapeCacheDir`
stays green).

### F11: coalesced `removeAsync`

Static pending map `HashMap<removalKey(serverAddress, dimension),
LongOpenHashSet>` guarded by a private lock. `removeAsync` adds to the set; only
the call that *creates* a dimension's set submits the drain task. The drain (on
the single FIFO IO thread) removes the set under the lock, then does ONE
load-modify-write applying every queued position (empty-file delete preserved).
Burst of N same-dimension calls in one tick → one rewrite. Pin: burst test
asserting single rewrite via a package-visible write counter on `save()` (plus
final-state correctness); existing
`removeAsyncUnstampsOnePositionAndDeletesTheFileWhenEmptied` must stay green.

---

## Commit 3 — F3: defer generation-timeout ticket releases

`ChunkGenerationService.tick()` timeout branch (`:150`): replace the inline
`removeTicketWithRadius` with
`this.deferredReleases.defer(entry.getKey(), () -> level.getChunkSource().removeTicketWithRadius(LSS_GEN_TICKET, pos, 0))`
(effectively-final locals), exactly mirroring `removePlayer` (`:231-232`). The
machinery already composes: the admission path cancels-and-reuses a pending
release for the same key (`:110` — a re-declared timeout that re-admits skips the
add/remove churn entirely, a bonus), `drain` runs at tick top even when idle
(`:134`), and `shutdown()`'s `flush()` (`:242`) covers deferred timeout releases.
The completion path's per-chunk release (`:194`) is event-paced and stays inline.

**Books check:** defer either drains (one removal) or is cancelled by re-admission
(ticket reused; the eventual completion/timeout removes it once) — add/remove
stays 1:1.

**Pins.** Extend `DeferredTicketReleases`' existing Tier-1 coverage if a
`ChunkGenerationService` seam exists; otherwise pin at Tier 2: the existing
`GenerationLifecycleGameTests` timeout test must stay green (a deferred release
lands ≤ `ceil(n/4)` ticks later — verify no assertion demands same-tick release).
Also verify no gametest counts tickets immediately after a timeout tick.

---

## Commit 4 — F6: throttle Paper hostile-frame logging

`LSSPaperPlugin`: static `LogThrottle HOSTILE_FRAME_LOG = new LogThrottle(60_000)`.
In `dispatchPluginMessage`'s catch: `long n = HOSTILE_FRAME_LOG.recordAndTryAcquire(System.nanoTime()/1_000_000)`;
log (with stack) only when `n > 0`, appending `(+<n-1> more suppressed)` when
`n > 1`. First frame still logs immediately (LogThrottle contract), so the
existing `LSSPaperPluginGlueTest` containment pin ("caught and logged, later
frames still dispatch") stays green. Pin: extract the decision as a seam or test
via two dispatches with an injected... `LogThrottle` takes caller-supplied time
only through `recordAndTryAcquire` — simplest pin: a glue test asserting the
second hostile frame within the window does not log (observable via a
package-visible `LogThrottle` instance swapped in a test, or by asserting on the
throttle object's behavior directly). The per-handshake INFO (`:308`) is left
as-is (single line, no stack, operationally useful) — considered, out of scope.

---

## Commit 5 — F8: sweep orphaned timestamp-cache tmp files

`ColumnTimestampCache.load(dataDir)`: before reading, list `dataDir` for names
starting `FILE_NAME + ".tmp."` with `lastModified` older than 1 hour and
delete them (try/catch → warn). The age guard protects the documented
`/reload` overlap window (an old instance's final save may still be writing its
unique tmp while the new instance loads — a fresh tmp must survive). Pin:
`ColumnTimestampCacheTest` — stage an old-mtime tmp + a fresh tmp, `load()`,
assert old deleted / fresh kept / cache content unaffected.

---

## Commit 6 — F10: park vanilla-view-excluded retry marks

`SpiralScanner.scan:106`: replace `columns.hasRetries()` with
`columns.hasActionableRetries(playerCx, playerCz, exclusionRadius)` — true only
if some retry mark lies OUTSIDE the buffered-Euclidean vanilla-view exclusion.
Extract the exclusion predicate (`:134-136`) into a shared static helper
(`isVanillaRendered(cx, cz, playerCx, playerCz, exclusionRadius)`) used by both
the walk and the new check so the two tests cannot drift. `ColumnStateMap` gains
the iteration (O(|retry|), retry sets are small).

Heal path unchanged: movement calls `recenter()` which re-walks from ring 0, so a
mark whose exclusion moves off is picked up by the movement rescan; a stationary
player's excluded mark is correctly parked (vanilla renders that chunk).

**Pins.** Existing `retryMarkInsideConfirmedDiscForcesRescanFromRingZero` must
stay green (verify its mark sits outside the test's exclusion; adjust the test's
viewDistance only if it accidentally sits inside — do not weaken the assertion).
New: `retryMarkUnderVanillaExclusionDoesNotResetConfirmedRing`, and a heal pin
(mark under exclusion + recenter → next scan reaches it once exclusion moved).

---

## Validation

1. Tier 1: `./gradlew :fabric:test -x runGameTest -x runClientGameTest` and `./gradlew :paper:test`
2. Tier 2: `./gradlew :fabric:runGameTest`
3. Full: `./gradlew :fabric:build -x runClientGameTest` + `./gradlew :paper:shadowJar`
4. Tier 3 once at the end: `./gradlew :fabric:runClientGameTest` (client cache + scanner changes)
5. Targeted soaks (sequential, never concurrently with builds):
   `./scripts/soak.sh fresh-backfill` (rebuilds base), then `warm-rejoin`,
   `dirty-broadcast` (F1 territory), `cold-restart-resync` (F2/F4 territory).
   Soak-law expectations unchanged — any new red is a regression in this round,
   except the documented A7/coverage environmental entries.
6. Big subagent implementation review (separate stage, after green tests).

## Plan-review amendments (applied — these refine the sections above)

A single adversarial plan-review pass verified every file:line claim and required six
changes, all incorporated into the implementation:

1. **F1 — honest residual-window comment + independent requeue flag.** The claim that a
   batch offered after the late drain "implies the client re-asked before the notice" is
   overstated: an event enqueued between the late drain and that player's route, plus a
   notice→scan→re-ask fitting inside the remaining phase-4 time, still races — the fix
   SHRINKS the window from a full cycle + alignment to intra-phase-4 milliseconds. The
   broadcaster comment must state that contract, not an absolute. Alternative considered
   and rejected: taking all batches at cycle start closes the race absolutely but adds ≤1
   cycle want-set latency and disturbs the router's dimension-mismatch leave-batch-untaken
   guard. `lateEventsApplied` must gate the requeue independently of `phase1EventsApplied`.
2. **F2 — the empty-map save guard resurrects removals.** `saveCache`'s
   `!isEmptyMap()` guard (and `save`'s empty-skip) means a session whose memory ends empty
   but whose `persistentRemovals` is non-empty never saves → the file keeps dishonest
   stamps. Guard becomes "non-empty map OR non-empty removals"; `mergeSave` handles the
   empty-memory case (delete-file when the merge result is empty).
3. **F2 — `persistentRemovals` is NOT pruned in `pruneOutOfRange`** (pruning it would
   resurrect removals for positions walked away from before the save). Explicit comment.
   Eviction impl: primitive sort (composite distance|index keys or fastutil LongArrays),
   never boxed. Update the stale comment in `ColumnStateMapTest`
   `ingestParkDoesNotPersistAFalseStampAndReAsksNextSession` (calls `save()` directly).
4. **F3 — one gametest DOES assert same-tick release.**
   `GenerationLifecycleGameTests.generationTimeoutFailsEveryCallbackAtExactBoundaryAndReleasesTicket`
   asserts `lssTicketCount == 0` immediately after the timing-out tick; pump one more
   `gen.tick()` before that assert (1 pending ≤ 4/tick drains next tick) — pin spirit
   ("never leak") unweakened. Completion-path releases (:194) stay inline (event-paced,
   live-validated on C2ME) — say so in the commit message.
5. **F5 — catch restructure.** Realistic throw sites sit AFTER `removePendingByPosition`
   (:1001), so the catch must NOT remove pending (no-op at best; strips a re-registered
   session's fresh pending at worst — the `inflight-guard-per-player` hazard class). Wrap
   only the post-guard section; in the catch do `state.clearDiskReadDone(packed)` (a
   delivered-branch throw after `markDiskReadDone` (:1025) otherwise leaves an orphaned
   done-bit that answers a ts>0 re-ask `up_to_date` — stale seal) and count `superseded`
   only when a disposition was owed.
6. **F6 — the throttle must be a resettable package-visible seam.** `LSSPaperPluginGlueTest`
   has TWO containment tests each asserting exactly one ERROR row; a bare static 60 s
   throttle reds whichever runs second. `@BeforeEach`-resettable throttle instance.

Additional review notes incorporated: F2+F11 (merge-save and coalesced-removal
load-modify-writes are safe ONLY because both live on the single FIFO IO thread — state
the invariant in code comments); F5→F1 (with per-outcome containment,
`generationReadyApplied` is effectively always set — comment, keep the flag); F10 (a
retry mark beyond a SHRUNK lodDistance is a separate, deliberately-unfixed flavor —
comment it so it isn't rediscovered); F11 (key the pending-removal map on sanitized
(server, dimension-id) strings so key equality matches file identity); F2 (the four
honesty-removal sites were verified to be the complete set; the only production
`saveAsync` caller is `saveCache`, so no dual-path save exists).

## Out of scope (explicit)

- The `TwoPlayerGameTests` retry scaffolding stays (pin unweakened).
- No wire/protocol changes; no config knobs added.
- The Paper handshake INFO line; Fabric-side handshake logging.
- `ColumnTimestampCache` fsync-on-save (documented deferred decision).
- Backporting to support lines (1.21.8/26.1/1.21.11) — separate decision after
  this lands on main (same policy as M1–M4: v0.8.1 shipped main-line first).
