# Full-codebase tri-lens review — 2026-08-05

Three independent full-codebase reviews (correctness / performance / concurrency +
resource lifecycle), each reading the production sources of all three modules after
CLAUDE.md, findings then verified against the cited code by the orchestrating session.
**Headline: 20 findings, zero critical or major from any lens.** All three reviewers
independently concluded the prior review rounds (M1–M4, R1/R2, C3, #62, #69, #71, the
store cap rework) already closed the big items. Every finding the orchestrator traced
directly (14 of 20) was accurate as reported; the remaining 6 are low-severity and
marked unverified below.

Status: **IMPLEMENTED 2026-08-06** for all 14 verified findings + D1–D4, on branch
`fix/review-round-2026-08-05` per `docs/planning/review-fixes-2026-08-05-plan.md`
(which also records the plan-review round). P5–P7 and H6–H7 remain open (unverified;
re-verify before fixing). The fix-bundle grouping below is superseded by the plan.

---

## Tier 1 — worth fixing (verified, real behavior defects)

### F1. Render-distance shrink strands a never-served LOD annulus (client)
- **Severity:** minor · **Confidence:** high (verified)
- **Where:** `fabric/src/main/java/dev/vox/lss/networking/client/SpiralScanner.java:414`
  (excluded positions confirm their ring), `:435` (prefix confirmation),
  `LodRequestManager.java:162` (`getEffectiveRenderDistance()` re-read per tick, never
  change-detected)
- **Defect:** vanilla-excluded (in-view) positions do not break ring confirmation, and
  nothing resets `confirmedRing` when the exclusion radius *shrinks*. A stationary
  player dropping render distance (e.g. 32 → 8) leaves rings ~9–32 newly LOD-needing
  but below the confirmed prefix — structurally never walked. Heals only on chunk
  crossing, an actionable retry mark, or reconnect.
- **Verified:** the reset inventory is movement/retry/dirty/session only. The in-code
  comment about a "SHRUNK lodDistance" flavor (SpiralScanner.java:383-385) covers a
  different case (LOD-distance shrink, which heals via retry marks). The existing test
  `lodDistanceShrinkThenGrowRescansTheOuterBandWithoutStranding` covers LOD-distance
  shrink only — exclusion-radius shrink has no test and no heal path but movement.
- **Direction:** detect exclusion-radius shrink in the scan (previous vs current
  radius) and reset `confirmedRing`; cheap, once per change.

### F2. Ingest-failure strikes double-count on rejected clears (client)
- **Severity:** minor · **Confidence:** high (verified)
- **Where:** `fabric/src/main/java/dev/vox/lss/networking/client/ColumnStateMap.java:320-333`
  (`onIngestFailed`, lost-clear branch)
- **Defect:** the pinned invariant (`ingestFailureCapCountsDeliveriesNotConsumerReports`)
  is "strikes count deliveries, not consumer reports." The lost-*content* path holds it:
  the first report removes the stamp, so sibling consumers' reports for the same
  delivery hit the `old == -1` guard and are absorbed. The lost-*clear* branch instead
  *restores* a >0 stamp (`put(packed, clearPreStamp)`), so a sibling's report is not
  absorbed and increments `ingestFailures` again — with two consumers, 2 strikes per
  delivery, parking after 2 failed clear deliveries instead of the intended 4.
- **Verified:** traced both branches. The pinning test covers only the content path.
- **Direction:** dedupe sibling reports per delivery on the clear path (e.g. a
  per-delivery marker mirroring what the stamp-removal accidentally provides on the
  content path). Park itself stays honest either way — impact is a halved re-serve
  allowance for clears.

### F3. `SqliteLodStore.readerConnection()` — two independent leak paths
- **Severity:** minor (unbounded flavor on a degraded box) · **Confidence:** high (verified)
- **Where:** `common/src/main/java/dev/vox/lss/common/store/SqliteLodStore.java:553-575`
  vs `shutdown()` at `:793-825`
- **Defect (a) — pragma-throw leak:** after `ds.getConnection()` succeeds (line 560), a
  throw from the setup pragmas (561-565) lands in the method-level catch, which returns
  null **without closing** the connection — never registered in
  `readerConn`/`allReaderConns`, so nothing ever closes it. Because `readerConn` was
  never set, *every subsequent read on that thread* re-opens and re-leaks a native
  SQLite handle — unbounded on exactly the box already in trouble (locked/damaged
  file, resource exhaustion).
- **Defect (b) — shutdown check-then-act race:** a reader/backfill thread passes
  `if (shutdown.get()) return null` (556), gets descheduled; the shutdown thread runs
  the whole shutdown (batcher join up to 5 s, then the close loop clearing
  `allReaderConns`); the reader then creates the connection and adds it to the
  now-cleared list. Nothing ever closes it. Note `isBackfillRegionDone()` (:747)
  checks only `latchedOff` — neither `serving` nor `shutdown` — so backfill
  enumeration reaches this with only the single check-then-act guard.
- **Consequence:** the file's own `dropDimensionRows` comment documents why held
  handles matter — on Windows a held handle can fail a later same-JVM
  drop-and-rebuild (Fabric singleplayer re-entry, Paper `/reload`), degrading the
  store to off for that session (fail-safe direction, but silently missing store).
- **Direction:** one fix in one function — close `c` on any throw after creation, and
  re-check `shutdown` after registration (close + deregister if it flipped).

### F4. Store size-cap eviction is not "oldest across all dims"
- **Severity:** minor · **Confidence:** high (verified)
- **Where:** `common/src/main/java/dev/vox/lss/common/store/SqliteLodStore.java:1499-1540`
  (`evictOldestBatch`)
- **Defect:** the javadoc/config docs promise oldest-ts eviction; the code iterates
  `dimIds` in HashMap order against one shared `remaining` budget — the first
  dimension absorbs the entire deficit; later dimensions are touched only once it is
  empty (per pass and across passes). No cross-dimension ts merge.
- **Live relevance:** only matters with a nonzero cap (default uncapped); the Modrinth
  server runs `lodStoreMaxMB=10240`. Under sustained cap pressure one dimension's
  genuinely-warm rows are hollowed out while others keep everything — warm-miss
  misallocation + backfill un-mark churn concentrated on one dim. Never a stale serve.
- **Verified:** all existing cap pins are single-dimension, so nothing contradicts.
- **Direction:** needs a small design decision — k-way merge on ts across the per-dim
  SELECTs, or proportional per-dim budgets. Own follow-up, not a drive-by.

### F5. Writer-failure rollback poisons the `sweepReopened` memo
- **Severity:** minor · **Confidence:** medium-high (mechanism verified; four-condition
  trigger, writer failures are exceptional)
- **Where:** `SqliteLodStore.java:1093-1108` (memoized `DELETE FROM regions` riding the
  shared txn), `:890-907` (failure handler re-queues only `DeleteRows`, never resets
  the memo), `runSweep` (~:1629) is the only memo reset
- **Defect:** the B13 seen_mtime clear is deduped by `sweepReopened.add(rpos)` and
  rides the shared 64-row txn. A rollback undoes an *applied* clear but leaves the
  memo, so a later same-region deposit skips the clear while the stale `seen_mtime`
  row survives — the region then skips every future sweep via `seen == mtime` until
  its next real save. This re-enters the "unbounded cross-restart staleness" shape the
  seen_mtime rule exists to prevent. The C3 fault-seam pins cover delete/latch
  invariants but not this memo interaction.
- **Direction:** reset/prune `sweepReopened` in the rollback handler, or commit the
  regions-DELETE immediately like the R1-M1 row deletes.

---

## Tier 2 — performance (verified mechanisms; fix opportunistically)

### P1. Probe pass re-serializes already-served loaded columns every tick until the next declaration
- **Impact:** medium — per tick × per player, ~1-2 ms/tick bursts for up to 1 s per
  episode (warm rejoin, teleport into loaded terrain, dirty re-serve storms); bounded
  by the 512/player, 2048/global probe caps
- **Where:** `fabric/.../RequestProcessingService.java:791-833` (filter at :814), Paper
  twin `PaperRequestProcessingService.java:1014+`
- **Verified:** the served-head filter checks only `hasEnqueuedColumn` (in-pipeline).
  After send SUCCESS the position leaves `enqueuedColumns` but stays in the published
  want-set until the next declaration (≤1 s fallback, ≤250 ms adaptive) — a served,
  still-loaded column is fully re-serialized on the main thread every tick of that
  window and discarded by the router as a duplicate. Positions resolved `up_to_date`
  (warm-rejoin resyncs) never enter `enqueuedColumns` and get zero filtering. The
  javadoc (:786-787) says "one re-probe"; the real shape is one re-serialization *per
  tick* for up to 20 ticks.
- **Direction:** extend the probe filter with `state.isWithinDepartureGrace(packed)`
  (departure-stamp map already documented any-thread-safe,
  `AbstractPlayerRequestState.java:656`); the `up_to_date` case needs a similar cheap
  any-thread mark.

### P2. Client prunes its entire per-column state on every chunk crossing
- **Impact:** medium — per crossing (~2.7 Hz in elytra flight) × O(entire disc state):
  ~263k timestamp entries + similar-size `validated`/`sessionSatisfied` at default
  distance 256, across ~9 structures — plausibly 5-15 ms on the render thread, i.e. a
  dropped frame per crossing
- **Where:** `LodRequestManager.java:253-269` (`tickMovementPhase`),
  `ColumnStateMap.java:346-392`, `InFlightTracker.java:57-64`, `RequestMetrics.java:91-102`
- **Verified:** full-map iteration regardless of how few entries are out of range; the
  work is memory-bounding, not latency-critical.
- **Not pinned:** the elytra investigation pinned scan *cadence* + the trim() rebuild,
  not per-crossing prune frequency; `tickMovementPhase`'s pinned property is "does not
  touch the scan cadence", which hysteresis preserves.
- **Direction:** movement hysteresis — prune only after N (e.g. 8) chunks of
  accumulated travel, or amortize across ticks.

### P3. Dirty-content hash runs on every chunk save even with zero LSS clients, under one global monitor
- **Impact:** medium-low — per chunk save server-wide (Fabric only), ~30-60 µs each on
  the save path; a `save-all` of hundreds of chunks costs 10-40 ms; paid even when no
  player has ever handshaken
- **Where:** `LSSServerNetworking.java:105-129` (`onChunkSaveData`),
  `DirtyContentFilter.java:111-140`
- **Verified:** gates only on `service != null && enabled`. Serialization also runs
  inside the filter's class monitor, single-filing C2ME/Moonrise parallel save workers
  (that half is soft-pinned in-code: "accepted for now — hoist if it ever measures hot").
- **Direction:** short-circuit the hash when no handshaken players are connected AND
  the position holds no cached stamp/store row (fall back to a bare dirty mark);
  and/or hoist serialization out of the monitor. Care: skipped hash updates mean a
  later join can see one spurious dirty broadcast per edited-while-empty column —
  correct direction, verify against the filter pins.

### P4. Store read rung re-prepares its SELECT on every `get`/`getFrame`
- **Impact:** low-medium — every disk submit while `lodStore=full` (hits and misses);
  ~5-20 µs prepare vs ~100 µs hit
- **Where:** `SqliteLodStore.java:453-454`, `:515-516`; contrast the writer's cached
  `insertByDim` (:217, :1068-1070)
- **Verified:** fresh `prepareStatement` per call in try-with-resources. Readers are
  thread-confined (`readerConnection()`), so a per-connection per-dimension statement
  cache is race-free by construction.
- **Direction:** cache prepared SELECTs per reader connection per dimension,
  invalidated with the connection.

### P5–P7. Lower-impact (agent-reported, spot-checked plausible, not fully traced)
- **P5.** Generation pacing gates rescan the whole pending map per admission attempt
  (`AbstractPlayerRequestState.java:464-501`, called from `OffThreadProcessor.java:1190,1266`)
  — bounded ≤216 entries; maintained nearest-ring aggregates would be O(1). **Caution:**
  pacing/spread semantics heavily test-pinned (`spreadGateAnchors…`,
  `generationRefillMustNotRace…`) — any change must be observation-equivalent.
- **P6.** Paper dirty-event path allocates a fresh dimension string per marked block
  (`PaperWorldHandler.java:128,140,146-166`) and funnels all region threads through
  `DirtyColumnTracker`'s single monitor — memoize World→dimension-string; striping
  only if Folia multi-region soak ever shows contention.
- **P7.** Resync air-fill builds a boxed `HashSet<Integer>` of section-Ys per resync
  column (`ClientColumnProcessor.java:367-385`) — an int/long bitmask suffices; the
  `ColumnStateMap.loadFrom` transient double-residency (:418-427) is not worth acting
  on without client heap evidence.

---

## Tier 3 — accept, or trivial hygiene fixes

### H1. `republishHeldBatch` retract-failure edge can resurrect a superseded batch (Folia)
- **Severity:** minor · **Confidence:** verified real, nanoseconds-wide
- **Where:** `common/.../AbstractPlayerRequestState.java:265-283`
- **Interleaving:** pump reads offerGeneration (unchanged) → network offer B1 →
  processing thread takes + applies B1 → pump's CAS(null, held) succeeds on stale data
  → *processing thread takes the republished held batch before the retract* → retract
  CAS fails, method returns true — and the stale batch's backlog replace lands ON TOP
  of newer B1's. The in-code comment ("the pass-through's backlog replace supersedes
  them") has the ordering inverted: that replace happened *before*.
- **Mitigation already present:** accounting stays balanced; the client's next
  declaration (≤1 s) re-supersedes. Impact = one interval of routing a stale want-set.
- **Action:** fix the comment at minimum (it currently mis-documents the invariant); a
  third guard may not pay for its complexity. CLAUDE.md pins "never resurrected" — if
  left as-is, the pin's wording should acknowledge this window.

### H2. v16 compat session leaks on the lifecycle-sweep removal path (Paper)
- **Where:** `PaperRequestProcessingService.java:816-826` — the sweep calls
  `v18Compat.onDisconnect(uuid)` with an in-code comment explicitly scoping the v16
  twin out ("the v16 manager's identical inherited shape stays as-is, out of scope" —
  execution-review finding 2). For a v16 client whose quit event never fired, the
  `V16CompatSession` survives until a same-UUID rejoin: bounded memory + inflated
  `V16Compat: clients=` diag count. The symmetric one-liner is trivial.

### H3. Non-volatile diagnostics counters read from Folia region threads
- **Where:** `TickDiagnostics.java:31-33` (plain `long` totals, "single-writer: main
  thread"), `getWindowBytesPerSecond()` :70-82 (unsynchronized ring reads),
  `SharedBandwidthLimiter.java:22-25` — read by `PaperCommands.java:131-133`, which on
  Folia dispatches on the invoking player's *region* thread.
- **Verified.** JMM-visible only to `/lsslod stats|diag` output (stale/torn longs).
  `PaperChunkGenerationService` (:91-94) already made its equivalents volatile with a
  comment stating the house rule — this is drift from that convention. Trivial fix.

### H4. `MemoryLodStore` deposit shed loops miss the in-loop shutdown check
- **Where:** `MemoryLodStore.java:179-184` (`depositFrame` — no in-loop check at all),
  `:193-204` (`deposit` — pre-loop only); contrast `SqliteLodStore.deposit`/`depositFrame`
  which re-check `shutdown || latchedOff` every shed iteration.
- **Verified twin-drift.** After shutdown (batcher dead, queue cleared) a racing
  deposit parks entries in the dead queue permanently — bounded at QUEUE_CAPACITY
  (1024) frames, reachable only on the SQLite-init degrade tier.

### H5. `LSSPaperPlugin.onDisable` unregisters plugin channels after service shutdown
- **Where:** `LSSPaperPlugin.java:182-196` — field nulled first (good), but a frame
  already dispatched into `onPluginMessageReceived` proceeds with the captured
  `service` concurrently with `shutdown()`. Everything touched is individually
  thread-safe; worst case one stray re-attach prompt during `/reload`. Unregistering
  the channels *before* shutdown closes it outright.

### H6–H7. Weakest tier (agents' own low confidence; not independently traced)
- **H6.** `OffThreadProcessor.getHarnessInternals()` (:1417-1434) iterates the
  processing-thread-owned `DedupTracker` HashMap cross-thread; the "CME → -1"
  containment doesn't cover garbage reads during resize. Dev-harness/soak-gauge only.
- **H7.** `StoreBackfill` cap stats vs the batcher's WAL truncate
  (`approxSizeBytes`, `SqliteLodStore.java:1447-1457`) can double-count by up to the
  64 MB WAL bound and stop the backfill a region or two early on small caps —
  conservative direction by design; arguably inside the documented envelope.

---

## Documentation fixes (each points at a hazard)

- **D1 (most important).** `LSSPaperPlugin.java:263-273` — the `HandshakeRegistrar`
  javadoc still describes the pre-round-3 design ("V16 creates the compat session
  identity FIRST, directly, not mailboxed"). Production correctly routes every dialect
  mark through the pump's `dialectFlipFor` precisely because a direct region-thread
  mark is the hard-kick race (review F1). A maintainer following the interface doc
  could reintroduce it.
- **D2.** `ClientColumnProcessor.java:32` — `MAX_QUEUED_BYTES` comment cites the
  retired "20 MB/s default" (now 15 MiB); sizing rationale unaffected.
- **D3.** `PaperConfig` — validate() comment "Paper writes lodStore=full into the file
  on every run" is stale (default is now `off`); the Folia warn condition itself is
  correct.
- **D4.** `RequestProcessingService.java:786-787` — probe javadoc's "one re-probe"
  understates the actual per-tick residual (see P1); fix alongside or instead of P1.

---

## Verified non-findings (do not re-chase)

Each reviewer listed what it checked and found sound. Condensed union, kept here so
future sessions don't re-litigate:

- **Wire/protocol:** parity held on every payload including the v16/v18 splice offsets
  and the zstd codec-byte gating (five-term AND + guarded egress on both platforms, no
  bypass send site); packing/sign-extension correct everywhere including the store's
  region math at negative coords; retired byte 0 inert.
- **Conservation:** A1/A5 terms balance on every disposition path traced (the dedup
  fan-out and permanent-gen-failure imbalances are already documented latents in
  `check_soak.py`); miss-memo seeding/clearing, duplicate-serve grace, served-set
  sweep, and generation pacing gates all match their pins; timeouts/capacity stay
  transient and NOT_GENERATED fires only on permanent unservability on both platforms.
- **Concurrency:** the latest-wins mailbox + offerGeneration guard and republish
  CAS/retract ladder (modulo H1's window); Folia hold-release release-then-take
  ordering; lifecycle mailbox with deferred SessionConfig reply and quit-race v18
  drain; `regionProbeResults` publish/consume atomicity + late-publish sweep;
  per-player generation stale-guard swept on removal; `requeueLosslessEvents`
  phase-completion flags; `TimestampSaveScheduler` one-drain bound (no lost-update
  interleaving exists); store tombstone identity-based expiry floor,
  immediately-committed deletes, drop-barrier + `droppingDims` suppression, DropAll
  fence-first; disk-reader shutdown/`hasHeadroom` single-submitter contract;
  `ChunkSaveDataHook` off-main body correctly synchronized for C2ME/Moonrise callers;
  x-ray mask managers' owner-guarded static holders; compat/Moonrise/AntiXray lazy
  resolution; client `reportIngestFailure` main-thread hop; `ClientColumnProcessor`
  epoch-terminated drain; both shutdown sequences' ordering.
- **Performance:** the 1 Hz full-disc walk + ring-127 fast-path limit (pinned),
  `snapshotForSave` deep copy + 2 s debounce (#62 pin), eviction sort, diskReadDone
  sweep/trim (M3), router rotation (M4), NBT transcode (round 2), frontier damping,
  `ColumnBytes` compress-once/decompress-once sharing (verified single-pass across
  dedup fan-out), debug-gated logging, `SendActionBatcher` primitive reuse, both
  bandwidth limiters allocation-free.

---

## Proposed fix bundles (nothing implemented yet)

1. **Bundle A — small, high value-per-line, no pinned semantics touched:**
   F1 (scanner shrink reset), F2 (clear-strike dedupe), F3 (readerConnection
   close-on-throw + shutdown re-check), H2 (v16 sweep one-liner), D1 (HandshakeRegistrar
   javadoc), plus D2-D4 riding along.
2. **Bundle B — own follow-ups, need a design choice:**
   F4 (cross-dim eviction fairness — merge vs proportional budgets), F5 (sweepReopened
   rollback reset vs immediate commit), P1 (probe filter reach — departure grace +
   up_to_date mark), P2 (movement-prune hysteresis).
3. **Bundle C — hygiene, batch whenever convenient:**
   H3 (volatile diag counters), H4 (MemoryLodStore in-loop shutdown checks), H5
   (onDisable ordering), H1 comment fix, P4 (reader statement cache), P6-P7 micro-items.
