# Disk-read concurrency gate — decoupling server-CPU limiting from bandwidth — plan

**Status: PLANNED, unimplemented** (2026-08-12). Mechanism selected by the user from a
reviewed options brief: **an expensive-path concurrency cap** (option A below).

**Reviewed 2026-08-12** (2 Fable subagents — mechanism lens + accounting/harness lens):
both verdicts IMPLEMENT WITH FIXES, all findings folded into this revision. The two
reviews CONVERGED independently on the headline MAJOR: an unconditional half-pool
AUTO would bind on store-OFF servers, where no cheap path exists to protect and the
plan's motivating asymmetry doesn't apply — fixed with **store-conditional AUTO**
(no store attached → K = pool = no-op). Other headlines: the mechanism review
verified the central pool-reservation claim holds (emergent invariant: ≤K threads in
the expensive phase ⇒ the rest drain the queue at cheap-task speed); the bounce now
REUSES the `saturated` result flavor (zero processor-side diff); the timeout/permit
prose was corrected to per-path reality; and the checker-registration list was
replaced with the real one (the plan's KNOWN_SERVER_KEYS item was a no-op —
`SERVER_CONFIG_INT_KEYS` is the registration that actually gates the pinning
strategy).

## Context — the problem

The bandwidth caps (`mbPerSecondLimitPerPlayer`/`Global`) are RAW-byte-denominated
because they bound **client decode work** — deliberately, and that remains correct.
But since the LOD store landed they are also the only throughput governor on the
serve path, and the two serve flavors they gate have wildly different **server CPU**
costs:

- **Store serve**: SQLite blob read + frame reuse — measured ~44 µs
  (`avg_read=44us` on the live rig), no inflate, no parse, no recompress.
- **Disk-read serve**: region read → zlib inflate → NBT parse → transcode → zstd
  compress — milliseconds of real CPU per column on the reader pool.

An operator who raises bandwidth so warm store serves flow fast (the store's whole
point) also uncaps the disk path: a player walking into a cold-but-generated region
turns the raised limit into an unbounded CPU bill. There is no mechanism to limit
the expensive path without also limiting the cheap one.

**Goal**: bound the disk-read path's CPU independently, with a default that needs no
operator tuning (auto-derived), plus a manual override.

## Options brief (presented 2026-08-12; user selected A)

- **A. Expensive-path concurrency cap (SELECTED)** — K permits gating the
  store-miss → region-read boundary; a miss with no permit resolves as a silent
  drop healed by re-declaration. Direct CPU ceiling (≤K threads of
  inflate/parse/transcode at any instant), self-adapting to per-column cost,
  platform-uniform (no tick signal needed), smallest diff. Trade-off: permits are
  held across IO wait too, so a slow disk throttles harder than CPU alone requires
  — which doubles as protection against the documented A7 read-timeout storms.
- **B. Rate cap (columns/sec)** — same seam; rejected: auto-derivation needs a
  per-column cost assumption (only aggregate wall-clock `avg_read_time_ms` exists),
  bursts less bounded, strictly weaker than A for a CPU goal.
- **C. MSPT-feedback governor** — rejected as the core mechanism: NO production
  tick-health signal exists on Paper/Folia (only Fabric's
  `getCurrentSmoothedTickTime`; the soak `mspt_avg_window` is harness-only,
  computed from wall clock in the exporters). Kept as a compatible **future phase**:
  Fabric-only modulation of K (generous when healthy, tighter under tick pressure)
  — the `AdaptiveReadThrottle` AIMD class is already a generic scalar controller
  that could drive it.
- **D. Split cheap/heavy pools with a bounded queue** — retention instead of
  drop-churn, but a real refactor of in-flight accounting, dedup groups, and
  shutdown for the same ceiling A gives. Rejected on risk/benefit.
- **E. Source-weighted bandwidth buckets** — dead on arrival: bandwidth is charged
  at flush AFTER serialization (`AbstractPlayerRequestState.flushSendQueue`,
  `recordSend` at `:747-748`), so the CPU is already spent, and `QueuedPayload`
  carries no source field at all (the `COLUMN_SOURCE_*` byte is inside the opaque
  serialized body).

**Why fail-fast-drop, not retain**: retention would have to happen at the router's
pre-submission gates — but store hit/miss is unknowable until the store lookup runs
INSIDE the pool task (`AbstractChunkDiskReader.readAndDeliver:430`), so pre-submit
retention would hold back would-be store hits, recreating the exact problem this
plan fixes. The drop-churn cost is bounded: a re-declared position re-runs a ~44 µs
store lookup once per scan, and the adaptive scan cadence already holds 1 Hz when
drops exceed 5% of a declaration.

## Ground truth (exploration 2026-08-12, verified file:line)

- The enforcement seam is single and clean: `readAndDeliver` — store rung first
  (`storeServedHit`, `AbstractChunkDiskReader.java:430`), store MISS falls through
  to the NBT path at `:432-438` (`recordSubmitted` at `:438` — "the NBT path begins
  here — store hits never count"), `operation.read()` at `:442` (read + inflate +
  parse + transcode, one aggregate `recordRealCompletion` window). All on
  reader-pool threads — the gate must be atomic (CAS), not single-writer.
- **The `saturated` outcome is the routing template** for the new deferred flavor:
  `deliverDiskResult` routes `saturated()` to `addSuperseded(1)` + silent drop
  (`OffThreadProcessor.java:977-986`) — no memo seed, no generation escalation, no
  wire answer, no timestamp stamp/store deposit (the `:904` gate), dedup fan-out
  handled per-attachment. Pinned by
  `OffThreadProcessorDiskResultTest.saturatedResultDropsSilentlyAndCountsSuperseded:241`.
- **Counter identities**: law A5's second clause derives
  `successful == completed − not_found − all_air − errors − saturated`
  (`check_soak.py:779-786`), and `successful` is an explicit counter — so a gated
  read **must not count into `disk.submitted`/`completed` at all** (the store-hit
  exclusion precedent, `AbstractChunkDiskReader.java:55-58`), only into a new
  dedicated counter. `superseded` is NOT an A5 term (deliberately, `:751-753`), so
  the drop side is free. Law A1: a dropped-no-wire-answer entry needs exactly the
  `superseded` disposition it will get.
- **Backfill auto-bypasses**: `readColumnBytesSyncForBackfill` is a separate entry
  point that never touches `submitRead`/`readAndDeliver`
  (`ChunkDiskReader.java:150-166`); its restraint stays `hasHeadroom` +
  MSPT-ceiling, unchanged.
- **`hasHeadroom()` must NOT be narrowed** by this feature — it gates submissions
  pre-classification (both cheap and expensive), and the C2ME
  `AdaptiveReadThrottle` already narrows it on that path; the two mechanisms
  compose (throttle bounds total submissions on degraded IO stacks; the gate bounds
  the expensive phase everywhere).
- Auto-with-override precedent: the three-part `0 = AUTO` pattern
  (`diskReaderThreads` field doc + `effectiveDiskReaderThreads(runtime param)` +
  validate clamps-nonzero-only, `ServerConfigBase.java:392-407,577-580`), with the
  resolved value logged in the startup summary (`:451-466`).

## Design

### The gate

`DiskReadGate` (new, `common/processing/`): an `AtomicInteger` permit counter with
`tryAcquire()`/`release()`, capacity K, plus a monotonic `gated` counter and an
in-use gauge for diag. Placement in `readAndDeliver`, immediately after
`storeServedHit` returns false and BEFORE `recordSubmitted`:

- `tryAcquire()` fails → deliver a bounce result and return. The read never starts;
  `disk.submitted`/`completed` untouched (store-hit exclusion precedent);
  `DiskReadGate` bumps its own `gated` counter → `DiskReaderDiagnostics.recordGated()`.
- Acquired → `try { existing :432-504 body } finally { release() }`.

**Permit coverage, per read path (review-corrected — the first draft's uniform
"spans read+inflate+parse+transcode" and "timeout keeps the permit held" were wrong):**
- Every read shape blocks on `future.get(DISK_READ_TIMEOUT_SECONDS)`; a 10 s
  timeout THROWS, the pool thread unblocks, and the `finally` releases the permit
  at error triage — while the orphaned downstream task keeps running OUTSIDE the
  permit. The escape is bounded (the vanilla IOWorker executor is single-threaded;
  Moonrise self-prioritizes at LOW), but during an A7-class storm permits recycle
  every ≤10 s while orphan work accumulates downstream — the gate does not bound
  that queue. Documented, accepted.
- Fabric split path (`useBackgroundReadSplit`, the default): fetch on the IOWorker
  executor, inflate+parse+transcode on the permit-holding pool thread — full span.
- Moonrise rung (Paper default; Fabric-with-Moonrise): inflate+parse run on
  Moonrise's IO threads; the permit covers the synchronous wait + pool-side
  transcode, bounding Moonrise-side work transitively (≤K blocked waiters ⇒ ≤K
  outstanding `loadDataAsync`).
- Non-split rollback: parse concurrency is 1 by construction (single IOWorker
  thread), permit or not.
The CPU ceiling holds on every path; `DiskReadGateTest`'s timeout case pins
release-at-triage-while-the-fetch-continues, not "thread stays blocked".

### The bounce outcome — REUSE the `saturated` flavor (review simplification)

The bounce delivers the existing `ChunkReadResult.saturated(...)` flavor — the
mechanism review established the entire processor-side diff then disappears:
`deliverDiskResult` already routes it to `addSuperseded(1)` + silent drop with
dedup fan-out per recipient, the `:904` stamp/deposit gate already excludes it, no
16th record component, and the existing pins
(`OffThreadProcessorDiskResultTest.saturatedResultDropsSilentlyAndCountsSuperseded:241`,
`DedupFanoutTest:510` per-recipient) cover it for free. Counter distinctness is
preserved because `disk.saturated` is recorded at the SUBMIT bounce site — never
derived from the flavor — so the gate site records `gated` instead and
`disk.saturated` stays 0. The saturated branch's debug message gets a wording tweak
to cover both bounce sources. (Recorded alternative: a distinct `gated` component
buys only debug clarity, at the cost of constructor sprawl and making the
`:904 && !gated` edit a store-corruption single point of failure — a missed edit
would deposit a FABRICATED all-air store row per gated position. Not worth it.)

Explicitly NOT: memo-seeded (the data likely EXISTS on disk — a memo entry would
falsely skip to generation), generation-escalated, `NOT_GENERATED`-answered, or
`diskReadDone`-stamped — all inherited from the saturated routing. Heal: the
position stays in the client's want-set and re-declares within ≤1 s.

### Auto-derivation of K — STORE-CONDITIONAL (both reviews' convergent MAJOR)

`effectiveMaxConcurrentDiskReads(int resolvedReaderThreads, boolean storeAttached)`
on `ServerConfigBase` (three-part pattern; the resolver takes runtime-discovered
parameters like `effectiveDiskReaderThreads` does):

- Override: `maxConcurrentDiskReads > 0` → `clamp(value, 1, resolvedReaderThreads)`.
- AUTO (0, the default), **no store attached** → `resolvedReaderThreads` (a no-op
  gate). With `lodStore=off` there are no store lookups to reserve threads for and
  the plan's motivating asymmetry does not exist — and per the split default,
  every UPGRADING server (key absent from an existing file) runs store-off, so an
  unconditional half-pool would hand that population pure downside on exactly the
  workloads where disk reads dominate (fresh worlds, elytra over
  generated-but-unvisited terrain). Mirrors the absent-key-never-arms philosophy.
- AUTO, **store armed** → `clamp(ceil(resolvedReaderThreads / 2.0), 1,
  resolvedReaderThreads)`: pool 8 (Moonrise auto) → 4, pool 3 (vanilla auto) → 2,
  pool 1 → 1. Deriving from the RESOLVED pool inherits the read-path-aware sizing
  and reserves the other half for store lookups — the structural fix for
  "expensive reads starve the cheap rung on the shared pool". (Note at pool 1 and
  at override ≥ pool there is NO reservation — exactly today's behavior; nothing
  regresses.)
- Disable idiom: set the override ≥ `diskReaderThreads` (e.g. 64). Spelled out in
  the javadoc because 0=OFF keys live in the same file (`outboundBufferCeilingKB`,
  `missMemoTtlSeconds`); the correct large-value-inert precedent is the client
  `lodColumnsPerSecondLimit` (~3200+ inert), NOT `lodStoreMaxMB` (whose uncapped is
  a first-class 0). The adjacent `diskReaderThreads` is the 0=AUTO precedent, and
  its "negative normalizes to AUTO, not 1" test is mirrored.

Constants: `MIN_MAX_CONCURRENT_DISK_READS = 1`, max rides
`MAX_DISK_READER_THREADS`; `AUTO_DISK_READ_GATE_DIVISOR = 2` with the rationale
comment. Startup summary APPENDS the resolved K (the config echo is a fixed-order
append contract with exact-string pins in both platform config tests — those pins
get updated; appending is script-safe, per-key substring matching).

### Config

`maxConcurrentDiskReads = 0` (AUTO) in `ServerConfigBase` — shared by both
platforms; validate clamps nonzero to `1..MAX_DISK_READER_THREADS`; field javadoc
states the CPU-vs-bandwidth separation ("bandwidth bounds the client; this bounds
the server") and the OFF idiom. Test-table entries: Fabric reflective sweep's
0-floor `case` list + Paper `SHARED_BOUNDS` row (`Bounds(0, MAX...)`) + the named
auto/override resolver tests + clamp-audit doc erratum.

### Observability

- DiskReader diag line gains `read_gate=<inuse>/<K> gated=<n>` (formatter golden
  update).
- Exporters (both platforms) gain `disk.gated` — full registration set in the
  Harness section (SERVER_MONOTONIC makes it a required field; contract literal +
  selftest fixture land in the same commit).
- **Operator signal (review)**: a pegged gate emits one THROTTLED WARN naming the
  remedy (the saturation-bounce WARN precedent) — "disk reads are being
  concurrency-gated (read_gate=K/K); raise maxConcurrentDiskReads if server CPU
  headroom allows". README documents the client-visible symptom: LOD holes filling
  at a bounded rate while `read_gate=K/K, gated=` climbs.
- **Fairness, accepted behavior**: permits are global first-come; fairness inherits
  the M4 router rotation + pool-queue interleaving, and a losing entry re-declares —
  no structural single-player starvation, but no per-player permit accounting
  either.
- **A7 flake-catalog note**: the catalog's live-triage signatures key timeout-storm
  magnitudes to the pool size ("exactly +5 = diskReaderThreads — one stall expiring
  all five blocked readers"); with the gate, at most K readers can be blocked, so
  live signatures become "+K". Direction is favorable and worth claiming: gated
  asks never enter the IOWorker queue during a gen-save flood, and at most K
  expiries per stall event — the gate likely REDUCES timeout storms.

## Interactions (each verified against the exploration)

- **Miss memo**: synergy, not conflict — memo hits skip reads entirely at the
  router rung, so gen-waiting positions don't churn the gate; gated results never
  seed the memo (authoritative-only rule preserved).
- **AdaptiveReadThrottle / C2ME**: composes; two independent upper bounds (throttle
  pre-submit on `tasksInFlight`, gate in-task); gated bounces never call
  `recordRealCompletion`, so the throttle's EWMA is unpoisoned.
- **Generation DISCOVERY rides through the gate** (review — the first draft's "out
  of scope" understated the coupling): the disk miss IS the generation trigger, and
  a gated bounce produces neither a miss nor a memo entry, so a needs-generation
  position cannot be DISCOVERED while permits are busy with real reads of existing
  chunks. Mitigations are real — authoritative misses are cheap reads (fast permit
  recycle), the memo suppresses repeat discovery reads, re-declaration heals — so
  this is throughput shaping in mixed terrain, not a stall. Under store-conditional
  AUTO it also mostly evaporates at defaults (store-off fresh worlds run K = pool).
  Generation EXECUTION stays out of scope (its own concurrency caps).
- **Backfill**: bypasses by construction; its pacing already has MSPT + headroom
  gates.
- **Dedup attachments**: a gated result fans out to attachments as superseded drops
  — the saturated path already does this.
- **Duplicate-serve grace / probeSuppress**: untouched — gated positions were never
  served, no stamps exist.

## Harness / baseline protection

- **All existing soak scenario configs pin the gate to a no-op** (explicit
  `maxConcurrentDiskReads` = that scenario's `diskReaderThreads` value, or the
  resolved default pool size when unset) — the `lodStore: "off"` pinning rationale
  verbatim: their law baselines and churn ceilings (e.g. rate-limit-storm's 1500)
  were calibrated without gating, and re-baselining buys nothing.
  **Pinning-necessity analysis (review)**: 24 of 26 configs pin
  `diskReaderThreads: 5`, where auto-K would be 3 — a REAL behavior change, so the
  pins are genuinely needed, not ceremony; `disk-saturation` runs threads:1 where
  K=1 is structurally a no-op (one pool thread serializes `readAndDeliver`, so
  `tryAcquire` can never contend — its pin is belt only); `store-offline-mutate`
  has no client traffic. Recorded so a future "simplification" doesn't delete the
  wrong pin.
- **Checker/registry work — the ACTUAL set (review-corrected; the first draft's
  KNOWN_SERVER_KEYS item was a no-op — that list holds top-level row keys only and
  `disk` is already known):**
  - `maxConcurrentDiskReads` joins `SERVER_CONFIG_INT_KEYS` — without it
    `--validate` REJECTS every pinned scenario config (the thrice-burned "R4
    lesson" in the checker's own comments; this is the registration the whole
    pinning strategy gates on).
  - `disk.gated` into `SERVER_MONOTONIC` — which makes it a REQUIRED snapshot field,
    so both exporters, the `_srv` selftest base fixture, AND the shared exporter
    contract literal (`fabric/src/test/resources/exporter-contract/
    server-snapshot.contract`, byte-asserted by both platform contract tests) must
    land in the SAME commit.
  - **A7 anomaly with a `gated` opt-in, opted in ONLY by the new scenario** (review
    — the `saturated` precedent verbatim: "the gate should hold it at 0, so a hit
    is a stronger signal"). This makes every pinned no-op scenario SELF-VERIFY its
    pin — any `gated > 0` under a no-op pin is a red — answering "should the
    checker validate pin presence" behaviorally.
  - `soak_report.py`: `disk.gated` into `SERVER_MECHANISM` (beside "gen-miss
    drops") or the digest never surfaces it.
  - The in-use permit gauge stays diag-line-only — NEVER in `SERVER_DRAINS`, where
    a nonzero gauge would kill quiescent windows during gating (the store.queue
    trap documented in the checker).
- **New soak scenario `disk-read-gate`**: prebuilt world (fresh-backfill base,
  built at distance 24), `lodStore: "off"`, `lodDistanceChunks: 24` (stay inside
  the base — review), `diskReaderThreads: 2`, `maxConcurrentDiskReads: 1`, duration
  budgeting a ≥25 s converged tail (the MIN_CLIENT_WINDOWS floor needs ≥4 quiescent
  5 s pairs). Asserts `disk.gated > 0` (premise), `disk.saturated == 0`, laws A1/A5
  green, convergence by scenario end, and a `superseded >= floor` term proving the
  drop-heal loop ran. Convergence is self-consistent: a 2112-column annulus at K=1
  on prebuilt superflat converges in well under a minute, and gating is
  self-limiting (K ≥ 1 always drains). Registrations: `ALL_SCENARIOS`, the soak.sh
  scenario case + `CLIENT_RUNS`/`EXPECTED_SECONDS`, base-world staging,
  `ANOMALY_OPT_INS`, `MIN_CLIENT_WINDOWS`, a CHECKS-registry named check with
  `required_fields`. Noted follow-up: a store-ON variant (store-second-join staging
  + K=1) would pin the headline "store hits keep flowing while the gate binds"
  end-to-end; at ship time it's pinned at unit level.
- **Gametest run dirs**: the fabric/build.gradle `doFirst` staged config (which
  already pins `lodStore: "off"`) additionally pins `maxConcurrentDiskReads` to a
  large no-op value — Tier 2 parity/fault tests expect every submitted read to
  resolve, and a surprise gate drop would flake them.
- **benchmark.sh neutral staging**: same no-op pin, so CPU-optimization baselines
  stay comparable across the change.
- **The three perf-profile harnesses (review MAJOR — missed by the first draft)**:
  `profile_disk_read.sh`, `compress_gate.sh`, and `backfill_profile.sh` all stage
  `diskReaderThreads: 5` and A/B against pre-gate reference runs — un-pinned, their
  arms silently run at auto-K=3-of-5 and every ref-vs-ref comparison is invalidated
  (the exact failure mode the effective-config echo contract exists to catch). Pin
  the no-op in all three staged configs and assert the new echo key tolerantly (the
  "ref predates the key" pattern already in profile_disk_read.sh).

## Tests

- **`DiskReadGateTest`** (Tier 1, common): capacity semantics, CAS under
  concurrent acquirers, release-on-every-outcome — the timeout case pins
  **release-at-triage-while-the-fetch-continues** (per the corrected coverage
  prose), not "thread stays blocked"; fail-fast delivers the `saturated`-flavor
  bounce without touching submitted/completed while `gated` increments (and
  `disk.saturated` does NOT); gauge/counter accounting.
- **`AbstractChunkDiskReaderTest`**: gate wired at the post-store-miss seam — a
  store HIT never consumes a permit (the load-bearing property); zero-permit
  scenario delivers bounces while store hits keep flowing.
- **`OffThreadProcessorDiskResultTest` / `DedupFanoutTest`**: with the flavor
  reuse, the existing saturated pins (`:241` silent-drop + `:510` per-recipient
  fan-out) already cover routing — add one gate-site wiring pin (a gated bounce
  reaches the processor AS the saturated flavor) rather than a parallel suite.
- **Config**: resolver table (store-conditional auto per pool size incl. pool 1;
  override clamp; override-above-pool = no-op; negative normalizes to AUTO — the
  `diskReaderThreads` test mirror), both platform clamp-table updates, the
  **config-echo exact-string pins on both platforms** (append contract),
  `JsonConfigLoadTest` default.
- **Diag/exporter**: formatter golden with the `read_gate=` token; exporter
  contract twins + the shared contract literal file; `check_soak.py --selftest`
  cases (config key validation, A7 `gated` opt-in, monotonicity).
- Paper twin coverage rides the shared `common/` classes (the gate and routing are
  platform-agnostic; `PaperChunkDiskReader` inherits the seam) — one Paper config
  test + the exporter twin suffice.

## Docs / release notes

- CLAUDE.md: Configuration bullet + a line in the disk-reader architecture section
  (the seam, the store-hit exclusion, the drop-heal).
- `config-defaults-and-clamps-review-2026-08-02.md` erratum.
- Release notes (Configuration + Performance): "Disk-read CPU is now bounded
  independently of bandwidth — `maxConcurrentDiskReads` (default auto: half the
  reader pool) caps concurrent expensive region reads; store-served LODs are never
  throttled by it. Raise bandwidth freely on store-heavy servers."

## Verification

1. Tier 1 both platforms; Tier 2 (`:fabric:build -x runClientGameTest`).
2. New + existing soaks: `./scripts/soak.sh disk-read-gate`, then `fresh-backfill`
   and `disk-saturation` (pinned no-op — must be byte-identical behavior).
3. Benchmark arms (review-corrected — the first draft never measured the shipped
   default anywhere):
   a. no-op-pinned `no-cache` — must match baseline (proves the pin).
   b. **store-OFF true defaults** — with store-conditional AUTO this must resolve
      K = pool (echo shows it) and match baseline exactly; a deviation means the
      conditional AUTO is broken.
   c. **store-ON + AUTO K** (the arm where halving actually binds — a store-armed
      run dir on the no-cache world): record `sections_per_second` vs baseline
      with a stated acceptance threshold; this is the number that justifies the
      half-pool divisor, or forces revisiting it.
   d. `maxConcurrentDiskReads: 1` — shows the bounded-CPU trade visibly.
4. Live on the test rig (`run-fabric-store`, store warm): raise
   `mbPerSecondLimitPerPlayer` high, rejoin for a warm burst (store serves flow at
   full rate — `read_gate` in-use stays ~0), then fly into a cold-but-generated
   region: `read_gate=<K>/<K> gated=` climbing, server CPU bounded (compare `top`
   with a control run), client convergence still completing via re-declaration,
   and the pegged-gate WARN fires once.
5. Optional Folia spot-check (`SOAK_PLATFORM=folia` fresh-backfill with the no-op
   pin) — the gate classes are common-side and pump-free, but the experimental
   label rules apply to the release note.

## Future phase (recorded, not planned)

Fabric-only MSPT modulation of K: feed `getCurrentSmoothedTickTime` into an
AIMD controller (the `AdaptiveReadThrottle` class is already sample-in/limit-out
generic) so K rides between auto-K and the pool size when the tick is healthy, and
below auto-K under tick pressure. Needs its own design round (recovery clocking —
the throttle only re-opens on samples — and a Paper story if Bukkit's
`getAverageTickTime` is ever adopted).
