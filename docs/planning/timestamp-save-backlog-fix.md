# Timestamp-save backlog fix (issue #62) — coalesced saves + buffered IO

**Status:** planned · **Target:** v0.8.1 on all three lines (main / support/mc26.1 / support/mc1.21.11)

## The bug (confirmed against the reporter's MAT dump)

`OffThreadProcessor` schedules timestamp-cache saves onto a single-thread executor with an
unbounded queue, and `ColumnTimestampCache.save()` writes through an **unbuffered**
`DataOutputStream` over `Files.newOutputStream` — every `writeLong` is one 8-byte syscall
through NIO's `ChannelOutputStream`. At the default 32 MB/dimension cap (~524k entries/dim,
three dimensions) a save issues ~3M syscalls for a ~25 MB file and can take multiple seconds.

The trigger cadence is not the ~5-min periodic save but the **invalidation debounce**: on an
active server with continuous dirty broadcasts, a save is enqueued every ~2 s
(`INVALIDATE_SAVE_MAX_CYCLES = 40`). Each enqueued task closure captures its own full
`snapshotForSave()` deep copy (~42 MB at cap with three dimensions). Once one save takes
longer than 2 s, the queue grows without bound: the reporter's heap dump shows ~42 backlogged
tasks retaining 1.76 GB (46% of live heap), the save thread caught mid-`writeLong` in an
8-byte `FileChannelImpl.write`. Frame line numbers match v0.6.2 exactly; the code is
unchanged on main, so every current line carries the bug.

Two defects, two fixes. They close different halves: **coalescing bounds memory no matter how
slow the disk is** (the correctness fix); **buffering removes the pathological save duration**
(the cause of the imbalance, and a large wasted-IO reduction on its own).

## Fix 1 — latest-wins save coalescing (`TimestampSaveScheduler`)

New package-private class `dev.vox.lss.common.processing.TimestampSaveScheduler`, following
the codebase's latest-wins mailbox idiom (`AbstractPlayerRequestState.pendingBatch`):

```java
final class TimestampSaveScheduler {
    private final AtomicReference<ColumnTimestampCache> pending = new AtomicReference<>();
    private final ExecutorService executor;   // MUST be the single-threaded FIFO saveExecutor
    private final Path dataDir;

    /** Latest-wins: publish the snapshot; queue a drain only on the empty→full transition. */
    void schedule(ColumnTimestampCache snapshot) {
        if (pending.getAndSet(snapshot) == null) {
            try {
                executor.execute(this::drain);
            } catch (RejectedExecutionException e) {
                // Only reachable when the processing thread outlived the shutdown join —
                // the same corner where today's code also drops the periodic save (and the
                // final save is skipped). Status-quo-equivalent loss, not a regression.
                pending.set(null);
                LSSLogger.debug("Skipped periodic timestamp cache save — save executor is shutting down");
            }
        }
    }

    private void drain() {
        var snap = pending.getAndSet(null);
        if (snap != null) snap.save(dataDir);
    }

    /** Shutdown: the final save supersedes anything pending; a queued drain then no-ops. */
    void discardPending() { pending.set(null); }
}
```

Invariant: **at most one drain task is queued per slot-fill** (each empty→full transition
queues exactly one drain; a drain's **first** action is consuming the slot). The producer is
single (the processing thread) and the executor is single-threaded FIFO, so the drain task
itself captures no snapshot (worst-case retention: 1 snapshot being
written + 1 in the slot ≈ 2 × ~42 MB at default config, vs unbounded before). A snapshot
overwritten in the slot is simply dropped — it was already superseded by newer state, the same
philosophy as the want-set's silent drops. No new counters: this is a hotfix crossing three
release lines; touching the exporter schema (both platforms + `check_soak.py`) is out of scope.

Concurrency audit of the three interleavings that matter:
- **Drain mid-save, new schedule:** drain consumed the slot before saving, so `getAndSet`
  returns null → a second drain is queued behind the running one (FIFO). Correct — newest
  snapshot still gets written.
- **Drain queued but not started, N schedules:** slot stays non-null → no new tasks; the one
  queued drain writes the newest snapshot. Correct — this is the coalescing.
- **Rejection at shutdown:** only reachable on the empty→full transition, so no drain exists
  to consume our snapshot; clearing the slot leaks nothing. This corner (processing thread
  outlived the shutdown join, so the final save was SKIPPED) loses the snapshot exactly as
  today's code does — status-quo-equivalent, not a regression.
- **`discardPending` + still-queued drain:** discard nulls the slot; the queued drain then
  no-ops. It runs only in shutdown's final-save branch, where the processing thread has
  already exited (no producer), so the "one drain per slot-fill" bound holds trivially.

**Paper `/reload` two-instance overlap:** the scheduler is per-instance (per-instance
executor, per-instance slot — the existing classloader-leak rationale at the executor field).
Cross-instance write ordering to the shared `lss-timestamps.bin` remains governed solely by
the unique tmp names + atomic rename, exactly as today; "single-threaded FIFO" is a
per-instance guarantee, never a cross-instance one. Buffered IO shrinks the overlap window.

### Wiring in `OffThreadProcessor`

- Field: `private final TimestampSaveScheduler saveScheduler` constructed with
  (`saveExecutor`, `dataDir`) when `dataDir != null`.
- `processCycle` (the periodic/invalidation-due branch): replace the raw
  `saveExecutor.execute(() -> cacheSnapshot.save(dataDir))` + `catch (RejectedExecutionException)`
  with `saveScheduler.schedule(cacheSnapshot)` (the rejection handling moves inside).
- `shutdown()`: **inside the `else if (dataDir != null)` final-save branch**, immediately
  before `saveExecutor.submit`, call `saveScheduler.discardPending()` — a queued stale drain
  then no-ops instead of doing a redundant full-file write ahead of the final save inside the
  `SHUTDOWN_JOIN_MS` window. NOT unconditionally at the top of shutdown(): in the
  thread-still-alive path the final save is skipped, and the graceful executor shutdown
  deliberately lets a queued periodic save run (the existing comment) — discarding there
  would lose state today's code persists. The final save keeps its direct
  `saveExecutor.submit(...).get(timeout)` (same executor → serialized after any running drain,
  and it must never be coalesced away). The scheduler field is only constructed when
  `dataDir != null`; both call sites sit behind that same guard.
- `saveExecutor` construction changes from `Executors.newSingleThreadExecutor(factory)` to the
  semantically identical `new ThreadPoolExecutor(1, 1, 0L, MILLISECONDS,
  new LinkedBlockingQueue<>(), factory)` so the wiring-pin test can observe the real queue
  depth (`getQueue().size()`); a package-private `saveExecutorForTest()` accessor exposes it.

## Fix 2 — buffered cache IO

- `ColumnTimestampCache.save()`: `new DataOutputStream(new BufferedOutputStream(
  Files.newOutputStream(tmpFile), 1 << 16))`. ~3M syscalls become ~400; save time drops from
  seconds to tens of ms. Close-chain flushes the buffer; an IOException on close still lands in
  the existing catch (tmp file deleted). Bytes on disk are identical — format untouched.
- `ColumnTimestampCache.load()`: same with `BufferedInputStream` (startup cost, same pattern).
- `ColumnCacheStore` (client, `fabric/.../networking/client/ColumnCacheStore.java`): the same
  two unbuffered constructions exist at its lines 44/95 — same two-line change. Client caches
  are the same scale, saved on disconnect/flush; no leak there (one-shot, not queued), but the
  flush blocks disconnect for the same seconds. Zero-risk rider, included on all lines.

## What deliberately does NOT change

- The invalidation-debounce cadence (~2 s) and the periodic 5-min save: durability semantics
  are pinned by soak laws and the WS4 rationale; coalescing preserves "newest state reaches
  disk as soon as the worker frees" without re-arming logic.
- `snapshotForSave()`'s deep copy per due-save: pre-existing accepted cost, bounded by the
  countdown floor; the slot needs an immutable snapshot, so copy-at-schedule is required.
- No exporter/soak-schema counters (see above).
- The save thread's inherited priority (NORM−1): with buffered IO the save is cheap, and a
  slightly deprioritized save thread deferring to gameplay is the right bias anyway.

## Tests (Tier 1, both existing suites run them via common)

New `TimestampSaveSchedulerTest` (fabric test tree, same package, manual-executor seam — a
fake `ExecutorService` that records runnables and runs them on demand):

1. **Coalescing:** schedule S1 → run drain → saves S1; schedule S2, S3 → exactly one more task
   queued; run it → file contains S3 (S2 dropped). Assert via `load()` into a fresh cache.
2. **Queue bound:** 10 schedules with the worker held → exactly 1 task ever queued.
3. **Rejection at shutdown:** executor throws `RejectedExecutionException` → `schedule` does
   not throw, slot is cleared (observable: the next schedule attempts `execute` again).
4. **discardPending:** schedule, discard, run drain → no file written.

`ColumnTimestampCacheTest` addition:

5. **Large round-trip:** > 4096 entries (12k across two dimensions — a 64 KB buffer holds
   exactly 4096 16-byte records, so "a few thousand" could fit in one flush and never cross
   the boundary) save+load identically — forces multiple buffer flushes on save and multiple
   refills on load.

Processor-level **wiring pin** (in the OffThreadProcessor test family — the scheduler unit
tests above all stay green if processCycle silently reverts to raw `saveExecutor.execute`,
e.g. in a support-line conflict resolution; this is the test that makes that revert red):

6. **Coalescing-through-the-processor:** block the real save worker (latch task submitted via
   `saveExecutorForTest()`), drive repeated due saves through processing cycles, assert the
   executor queue depth never exceeds 1 and, after releasing the latch, the file contains the
   newest snapshot.

Existing pins that must stay green: `OffThreadProcessorLifecycleTest`'s shutdown-save →
restart-load warm-resync test (covers the final-save path end-to-end), all
`ColumnTimestampCacheTest` persistence/corruption guards (format unchanged), Paper Tier 1
(shared common classes).

Validation beyond Tier 1: Tier 2 gametests via the release build command; one
`./scripts/soak.sh dirty-broadcast` on main (drives the invalidation-debounce save path
live); support lines get Tier 1+2 + release pre-flight only (per the support-line effort
budget — this is a `common/` change with no wire/routing surface).

## Out of scope, explicitly

- **`support/mc1.21.8` (and `support/mc1.20.1`) carry the identical bug** (verified: same
  unbounded `saveExecutor.execute` + debounce constants on 1.21.8). They are deliberately NOT
  in this v0.8.1 round — the user scoped the backport to the 1.21.11 and 26.1 lines (the
  v0.8.0 tri-release set), and the support-line effort budget applies. The fix commit is
  designed to cherry-pick clean if a 1.21.8 pick is wanted later; flagged for the user at
  sign-off.
- CLAUDE.md gets the customary Tier-1 blurb mention of the new test class.

## Backport + release plan (v0.8.1, three lines)

1. Fix lands on `main` via PR from `fix/timestamp-save-backlog` (merge commit, not squash —
   irrelevant for tags here but keeps the tri-line cherry-pick SHAs traceable).
2. Cherry-pick the fix commit(s) to `support/mc26.1` (worktree `~/projects/lss-support-mc26.1`)
   and `support/mc1.21.11` (`~/projects/lss-support-mc1.21.11`; Java 21 line). `common/` +
   client-file paths are line-identical here (no ScopedValue/mapping surface), so the pick
   should apply clean; the recurring keep-ours conflict set (release.yml, workflow contract
   test) is not touched by this fix.
3. Per line: `CI=true ./gradlew :fabric:build -x runClientGameTest :paper:test :paper:shadowJar
   -Pmod_version=0.8.1` + `python3 scripts/release_check.py --version 0.8.1` (delete stale
   local release jars first — the stale-jar gate fired at v0.8.0 on exactly this).
4. Release notes per line (`docs/release-notes-v0.8.1*.md`): Bug Fixes — the memory-leak fix
   (credit issue #62); Performance — buffered cache IO. Player-focused wording per the format.
5. Tags `v0.8.1` / `v0.8.1+mc26.1` / `v0.8.1+mc1.21.11` prepared as command blocks
   (annotated, `--cleanup=verbatim`, notes-file) in a `v0.8.1-release-report.md`, **held for
   user sign-off** — same protocol as v0.8.0. Suggested push order 26.1 → 1.21.11 → main.
6. CI (`build.yml`) must be green on all three tips before sign-off.
