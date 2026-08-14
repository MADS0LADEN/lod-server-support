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

**Why fail-fast-drop, not retain** — *the drop half of this rationale is OVERTURNED
by Amendment 2 below (router-level retention on gate saturation); the pre-submit
part stands — parking/retention still happen only after the store lookup ran*:
retention would have to happen at the router's
pre-submission gates — but store hit/miss is unknowable until the store lookup runs
INSIDE the pool task (`AbstractChunkDiskReader.readAndDeliver:430`), so pre-submit
retention would hold back would-be store hits, recreating the exact problem this
plan fixes. The drop-churn cost is bounded: a re-declared position re-runs a ~44 µs
store lookup once per scan, and the adaptive scan cadence already holds 1 Hz when
drops exceed 5% of a declaration.

> **AMENDED at implementation (2026-08-13, v0.11.0 stage B — deviation pair, see the
> progress doc's decisions log):** the PURE fail-fast bounce failed its own live
> scenario — a permit-LESS pool worker empties the shared queue at bounce speed
> (µs/task), so the permit HOLDER got one read per queue refill and starved
> (measured: 1.6% permit utilization, ~8 reads/s at 2 ms reads decaying to ~1.5/s;
> the disk-read-gate soak could not converge in 340 s). The shipped mechanism adds a
> **bounded PARK list at the post-store-miss seam** (capacity = the pool queue's,
> threads×32): a permit-less store MISS parks instead of bouncing, and every permit
> release drains parked work first — permit holders run expensive reads
> back-to-back while the other workers keep serving store hits, which is this
> plan's own stated reservation intent and what the sizing model ("K=1 serializes
> the annulus") already assumed. Park OVERFLOW bounces exactly as specified below
> (saturated flavor, counted `gated`, drop-heal unchanged), so `disk.gated` becomes
> the overflow counter. This is a lightweight subset of rejected option D (the
> bounded queue WITHOUT the pool split or in-flight accounting refactor); the
> pre-submit-retention rejection above stands untouched — parking happens after the
> store lookup, so store hits are never held back.
>
> One documented interplay narrowing (stage-B review B-5): on the C2ME-latched
> fallback (AdaptiveReadThrottle engaged) WITH a store armed, parking tasks return
> their pool slot in µs, so `tasksInFlight` — the throttle's `canSubmit` input —
> undercounts buffered expensive demand, and drains never consult the throttle: the
> effective read-concurrency floor becomes K (half pool) rather than the AIMD floor
> of 1. Store-off C2ME (the common case) is bit-identical (K = pool → no parking);
> K still bounds pressure. Accepted — revisit only if a live C2ME+store server
> shows IO distress the throttle used to absorb.

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
- Release notes (Configuration + Performance) — **SUPERSEDED BY AMENDMENT 2:
  do not copy this draft; the live tag drafts in release-tag-v0.11.0*.txt carry
  the current wording** ("store-served LODs never consume its capacity"): "Disk-read
  CPU is now bounded independently of bandwidth — `maxConcurrentDiskReads` (default
  auto: half the reader pool) caps concurrent expensive region reads. Raise
  bandwidth freely on store-heavy servers."

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

## Amendment 2 (2026-08-13, user direction at the v0.11.0 F pause): router-level retention replaces park-overflow drops

**Live evidence that prompted it**: the v0.11.0 rig deploy's first warm join produced
thousands of park-overflow drops (`gated` WARN deltas of 767/668/173 per minute
against K=2) — each one a wasted full cycle (router admission → SYNC slot → dedup
group → pool-queue trip → **a store lookup** → drop → re-declaration ≤1 s later →
repeat), plus a WARN whose "raise maxConcurrentDiskReads" advice misread the design
working as the design failing. The drop tier also violates the architecture's own
idiom: it is the only place an ADMITTED ask is dropped for pure capacity below the
router ("nothing is bounced": slot-cap full → retain and continue; no disk headroom
→ retain and stop).

**The change**: when the gate is SATURATED (permits exhausted AND the park full),
the ROUTER retains the entry and stops the pass — the exact `hasHeadroom()`
semantics — so pending asks stay in the backlog and are replaced/reprioritized
wholesale by the next want-set declaration instead of burning drop-and-re-ask
cycles.

- `DiskReadGate.isSaturated()`: true iff no permit is available AND the park is
  full. Cheap atomic reads; volatile-composition tolerant (a stale read admits or
  holds one entry for one pass — both self-healing).
- `AbstractChunkDiskReader.gateSaturated()` accessor; the ROUTER checks it as a
  SEPARATE conjunct beside `hasHeadroom()` at the same site (deliberately NOT
  folded into `hasHeadroom()` — the AdaptiveReadThrottle composes with headroom
  independently and must stay orthogonal): saturated → retain the entry, STOP the
  pass, count ONE `gate_stops` event per stopped pass.
- The reader-side park is UNCHANGED (it is the holder-feeding mechanism, the
  amendment-1 deviation). The overflow-drop path REMAINS as race armor only
  (submissions already in flight when the park filled) — still counted
  `disk.gated`, expected ~0 in steady state.
- **Store-hit cost, accepted**: while saturated, the router holds ALL submissions
  (hits included) for ≤1 pass — at park-full there are ≥K running + threads×32
  parked misses, the marginal submission is overwhelmingly another miss, and hits
  already in the pool queue keep draining permit-free.
- **Conservation**: retained entries carry NO disposition (they simply stay in the
  backlog); when the next declaration replaces them they count `superseded` like
  any replaced entry — law A5's partition is UNCHANGED (`disk.gated` keeps its
  never-submitted slot, now near-zero).
- **Observability**: `disk.gate_stops` joins both exporters + the contract literal
  + `KNOWN_SERVER_KEYS`/`SERVER_MONOTONIC` + soak_report (the R-6 same-commit
  rule); diag gains `gate_stops=` beside `gated=`. The once-a-minute WARN re-keys
  to `gate_stops` deltas (sustained router holds = the capacity-pressure signal;
  the remedy text is unchanged and now honest) — the overflow-drop WARN text stays
  on the armor path.
- **Scenario re-pipe**: `disk-read-gate`'s premise becomes `disk.gate_stops > 0`
  (the gate BOUND via retention) + `disk.saturated == 0` + the superseded floor;
  `disk.gated` is left unpinned (race armor may legitimately read 0 or small).
  Checker + selftest rows updated with the key registrations. The no-op-pinned
  scenarios (K=pool) must show `gate_stops == 0` — their baselines stay gate-free.
- Backfill bypasses by construction — unchanged. Both platforms ride the one
  common router implementation.
- **Found-bug loop**: this re-opens the stage-B gates — Tier 1 gate/router pins
  (new: saturated→retain+stop, unsaturated→normal, armor-drop still counted),
  Tier 2, the re-piped `disk-read-gate` scenario, `fresh-backfill` +
  `disk-saturation` under the no-op pins, release_check.

## Amendment 2 revision (2026-08-13, two-reviewer design round folded in — both PROCEED WITH CHANGES)

The concurrency-lens and harness-lens reviews corrected four claims above and
pinned the exact semantics. Where this section disagrees with Amendment 2's
first draft, THIS section governs.

**R-MAJ-1 — the saturation predicate must count permit-less in-flight work, and
lives on the READER, not `DiskReadGate`.** The first draft's bare
"no permit AND park full" lags by one pool-queue depth: the router iterates at
~µs/entry while a parking worker pays a store lookup first, so the router pumps
the pool queue full before the park count moves, and the queued tasks then
overflow-drop at the same rate as the motivating incident — retention would not
deliver its own premise. The concurrency reviewer's fix (add `tasksInFlight`)
overshoots in the other direction: `hasHeadroom()` is false only at a COMPLETELY
full queue, so at K=pool (`disk-saturation`, threads:1) the queue can sit at
capacity−1 with `inUse == cap` and `tasksInFlight >= parkCapacity` — false
saturation, `gate_stops` on a non-opted baseline. The synthesis counts only
permit-LESS in-flight work:

    gateSaturated() ≡ readGate.inUse() >= readGate.capacity()
                   && gateParkedCount + (tasksInFlight − readGate.inUse())
                      >= gateParkCapacity

Structurally false at K=pool: the park is pigeonhole-empty (a classifying thread
always finds a permit) and `tasksInFlight − inUse` = queued < queueCapacity =
parkCapacity whenever `hasHeadroom()` passed. In the K<pool miss storm it binds
BEFORE the queue can overflow the park (parked + queued + permit-less runners),
making retention dominant and overflow drops true race armor. Hit-heavy
over-conservatism (queued store hits inflate the term near queue-full) is
accepted — re-evaluated per pass at ~20 Hz. Both comparisons `>=` (transient
over-capacity shapes: lowered K, park claim-then-back-out). Composed as
`AbstractChunkDiskReader.gateSaturated()` (the park is reader state;
`DiskReadGate` stays a pure permit counter, gaining at most accessors); the
`OffThreadProcessor` accessor null-guards like `hasDiskHeadroom` (null reader →
never saturated). Pin `gateParkCapacity >= queueCapacity` with a ctor comment —
the structural-false argument needs it.

**R-MAJ-2 — evaluation is PER ENTRY at the same `!attached` site, headroom
FIRST.** Not a pass-head check: per-entry re-observation collapses the race to
classification latency (a park refill mid-drain is seen by the next entry) and a
mid-pass saturation flip stops admission at the flip (pinned). `hasDiskHeadroom`
is checked first so `gate_stops` counts only gate-attributable stops and
pool-full behavior stays byte-identical. New `AdmitResult.GATE_SATURATED`
handled exactly like `NO_DISK_HEADROOM` — same SYNC-slot + dedup-group unwind,
retain + stop THIS PLAYER's pass (routeAll continues to the next player — the
existing headroom semantics; M4 rotation keeps fairness) — plus one `gate_stops`
increment per stopped player-pass. Memo rung stays above (generation never
gated); dedup attaches ride through saturation; the frontier stamp already
happened upstream.

**R-MAJ-3 — honest store-hit-cost wording.** The first draft's "held ≤1 pass" is
wrong: the hold is re-imposed every pass for the DURATION of the saturated
episode (park drains at expensive-phase rate — worst case, an A7 IOWorker
stall, minutes), and the predicate is global across players. Mitigations that
keep it acceptable — ALL scoped to entries AHEAD OF the stopped head (the 3-Opus
implementation round's correction: once a pass stops, nothing BEHIND the head is
polled, so timestamp/probe/duplicate/memo resolution continues only for the
nearer prefix; closest-first ordering makes that the right prefix to keep
serving, and re-declaration heals the tail): ts>0 rejoins resolve via the
timestamp rung without submitting, `restoreBacklog` republishes the want-set so
probe coverage is computed (consumed only up to the head), the memo rung keeps
escalating ahead of the head, and the held-hit subset is only
ts<=0-no-server-stamp asks. Practical magnitude on a slow-IO box (A7-class
stall): ALL per-player serve progress paces at the expensive-read rate for the
episode — the old drop tier did not do that; accepted with eyes open. Recorded,
not built: a store-membership pre-check remains rejected. Release-note/README
wording "store-served LODs are never throttled by it" → "store-served LODs never
consume its capacity".

**R-MIN-1 — WARN: latched once per session** (the store-eviction precedent —
the once-a-minute re-key would fire 3–5 times during one legitimate distance-300
cold join, the exact noise this amendment removes). One WARN on the first
sustained saturation episode, naming `gate_stops=` in diag and the
`maxConcurrentDiskReads` remedy; totals live in diag. The armor-drop WARN keeps
its throttle and "dropped" wording but LOSES the remedy sentence (overflow now
indicates a burst race, not capacity).

**R-MIN-2 — registration corrections.** `KNOWN_SERVER_KEYS` needs NO change
(top-level registry; `disk` already present) — the real registration is
`SERVER_MONOTONIC += "disk.gate_stops"`, which auto-propagates to
`GLOBAL_SERVER_FIELDS` required-presence. Counter home:
`DiskReaderDiagnostics`, beside `gated` (diag adjacency free). Full sweep:
exporters ×2, the ONE shared contract literal (`disk.gate_stops=long`, sorted),
the A7 arm + `OPT_INS["disk-read-gate"] = {"gated", "gate_stops"}` (KEEP
`gated` — armor fires legitimately), `_srv` fixture `"gate_stops": 0`, the
selftest A7 pair, `soak_report SERVER_MECHANISM["router gate stops"]`, diag
token order `read_gate=, gate_parked=, gate_stops=, gated=` (append preserves
greps), stale-comment sweep (checker opt-in/monotonic/A7 comments,
soak_report armor comment, OffThreadProcessor saturated-flavor comment, reader
readAndDeliver + diag comments).

**R-MIN-3 — scenario floor goes static.** `superseded >= gated` is vacuous at
gated ~0; replace with the disk-saturation-precedent static `superseded >= 100`
(measured margin: 664 on the first passing retention run — 6.6× the floor; the
old drop-era runs measured higher because every drop was also a re-declared
miss). Premise `disk.gate_stops > 0`; `disk.saturated == 0` unchanged;
`disk.gated` deliberately UNPINNED (0-or-small legitimate). The named check's
required-fields list swaps to `server.disk.gate_stops`. NOTE for the A/B: the
efficiency win shows in convergence time and WARN noise, not in any conserved
counter — `disk.submitted` stays ≈ unique positions in both models.

**Implementation-round notes (3-Opus review, 2026-08-13):** the attribution
smear between `gate_stops` and `NO_DISK_HEADROOM` is bounded at T−K entries per
pass (an empty-park saturation read needs `queued >= queueCapacity − (T−K)`), so
the counter's meaning is tighter than "near queue-full". `gate_stops` scales with
the saturated episode's DURATION, not the flood size (measured 41 on the first
passing run vs 2945 drop-era `gated` on the same scenario) — the `> 0` premise
has ~40× headroom; revisit only if a much faster box shrinks it. Harness
back-compat: `disk.gate_stops` joining the required-presence set means archived
pre-Amendment-2 recordings now fail schema validation with a named violation —
the documented "re-record rather than debug" stance. `store-offline-mutate`'s
`maxConcurrentDiskReads: 3` (vs AUTO pool = 3 on vanilla-IO soak boxes) is K =
pool there, and its phase is `enabled: false` besides — inert, left as is. A K
LOWERED at runtime then raised back to pool can read saturated transiently until
the park residue drains (nothing refills a held park, so it self-clears within
one release cycle) — unreachable in any scenario, noted in the predicate's
javadoc.

**Verified no-change set (both reviewers):** law A5's fold contains no `gated`
term in either identity; A1 rides the documented `queue_full`
retained-no-disposition precedent; all 26 no-op scenario baselines are
structurally gate-free (the park-full conjunct is the carrier of that proof —
pinned in Tier 1 as the K=pool structural-false test); `disk-saturation`
unchanged; no permanent router wedge (the park drains monotonically under hold —
nothing refills it while the router holds — and every permit release drains);
oscillation fairness rides M4 rotation; v16 synthetic want-sets route through
the same mailbox/backlog and heal at their 1 Hz declarer; backfill untouched;
AdaptiveReadThrottle composes independently (EWMA fed by real completions,
which continue during saturation); parked entries hold pending slots so
re-declared duplicates resolve IN_FLIGHT and never double-admit.

## Future phase (recorded, not planned)

Fabric-only MSPT modulation of K: feed `getCurrentSmoothedTickTime` into an
AIMD controller (the `AdaptiveReadThrottle` class is already sample-in/limit-out
generic) so K rides between auto-K and the pool size when the tick is healthy, and
below auto-K under tick pressure. Needs its own design round (recovery clocking —
the throttle only re-opens on samples — and a Paper story if Bukkit's
`getAverageTickTime` is ever adopted).


## Amendment 3 (2026-08-13) — the park-overflow WARN is DELETED

The Amendment 2 text above kept the armor-drop WARN "with its throttle, without the
remedy sentence". Live operation falsified even that: the throttled WARN repeated once
per interval on a real server for a self-healing race (`read_gate=2/2, park full`),
and the operator-log-hygiene bar set by this project (see also the store-eviction
one-line latch) is that self-healing paths carry counters, not recurring log lines.
The overflow path is now LOG-FREE: `disk.gated` + the always-rendered `gated=` diag
token are the evidence, and the LATCHED gate-stop WARN (router retention) remains the
actionable capacity signal. Do not restore the overflow WARN.
