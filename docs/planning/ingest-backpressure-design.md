# Ingest-pressure want-set scaling (issue #71)

## 1. Problem

Issue #71: a weak client (2-core i3-2120, 6 GB heap, Sodium+Voxy) on a default-config
v0.8.1 server froze during LOD backfill and dropped with "Connection reset". The v17/v18
model serves continuously at the server's configured bandwidth; nothing on the client
paces that to what the client can actually *absorb*.

The client's existing backpressure (decode-queue taper + the ¾-full halt + the
edge-triggered clear batch) watches only LSS's OWN decode queue. That queue's drain is
cheap — parse sections, hand off to consumers — so it stays near-empty whenever the
consumer's hand-off is non-blocking. Voxy's is: `VoxelIngestService.rawIngest` appends to
an **unbounded** `ConcurrentLinkedDeque` and returns `true` immediately (verified against
Voxy 0.2.11-alpha, 0.2.17-alpha/MC 26.2, 0.2.18-beta/MC 26.1.2, and dev). The heavy work —
voxelization, mip building, world-section insert (`WorldUpdater.insertUpdate` runs
synchronously *inside* the ingest job) — happens later on Voxy's service threads, and each
queued `IngestSection` holds strong refs to the `LevelChunkSection` + two `DataLayer`s.
On a machine that ingests slower than the server sends, that queue is exactly where the
heap goes: GC stall → frozen socket → RST. LSS never felt a thing.

## 2. The signal (Voxy research result)

Voxy exposes the backlog directly, and the surface is stable across every version we
target:

```
me.cortex.voxy.commonImpl.VoxyCommon.getInstance()        // static → VoxyInstance | null
        .getIngestService()                               // public → VoxelIngestService
        .getTaskCount()                                   // public → int (Service.numJobs(),
                                                          //   semaphore permits = pending jobs)
```

- One job == one queued SECTION (`rawIngest0` enqueues per section; `enqueueIngest` — the
  vanilla-chunk path — also enqueues per section). So `getTaskCount()` is the pending
  ingest backlog in **sections**.
- It counts ALL producers (LSS's rawIngest AND Voxy's own vanilla-chunk ingest). That is a
  feature: on join, vanilla view-distance chunks flood the same service, and pacing LOD
  asks down while the client chews vanilla ingest is exactly the right behavior.
- `getInstance()` returns null before world creation and after shutdown — a null anywhere
  in the chain means "no signal". (`VoxyCommon.INSTANCE` is a plain non-volatile static;
  a cross-thread stale read is deliberately tolerated — stale null → -1 fail-open, and the
  instance's fields are final.)
- The signal is the INGEST STAGE's backlog only — Voxy's downstream `SectionSavingService`
  has its own unbounded queue this cannot see. That is acceptable by structure: ingest is
  the dominant retained-memory stage (queued `IngestSection`s hold raw sections + light;
  saving holds compressed forms), and the stages share one small thread pool, so a
  downstream bottleneck occupies threads and backs up into the ingest count to first
  order. `numJobs()` is per-service (its semaphore is private to the service), so other
  services can never inflate it, and it undercounts only by the ≤ pool-size jobs currently
  executing (~3) — negligible against the thresholds below.
- Depth, not rate: Voxy has no completed-work counter, so we cannot read an ingestion
  *rate* directly — but we don't need to. Scaling the want-set budget linearly against
  backlog depth is a proportional controller: the budget shrinks until arrivals match the
  drain, i.e. the ask rate *converges to the real ingestion rate* with the standing
  backlog as the error term. This is the same control shape the decode-queue taper
  already uses.

## 3. Design

Three layers, all client-side, no wire change, no server change.

### 3.1 API: consumer-reported backlog (`LSSApi`)

`VoxelColumnConsumer` gains a default method:

```java
/** Pending ingest backlog in section units; -1 (default) = not reported. */
default int pendingIngestBacklog() { return -1; }
```

Default method ⇒ existing lambda/class consumers stay source- and binary-compatible
(`@FunctionalInterface` still holds — one abstract method).

Threading contract (this INVERTS the interface's existing "background thread only"
framing, so the Javadoc must say it explicitly): `pendingIngestBacklog()` is polled from
the MAIN CLIENT THREAD at up to 20 Hz. Implementations must be fast, non-blocking, and
thread-safe — return a cached/atomic gauge, never compute or lock. (Voxy's
`Semaphore.availablePermits()` satisfies this trivially.)

`LSSApi` (internal, `@hidden` like `dispatchColumn`) aggregates:

```java
public static int maxReportedIngestBacklog()   // max over consumers; -1 if none report
```

- Max, not sum: multiple consumers ingest the SAME columns in parallel fan-out; the
  slowest one must pace the stream (can't overrun the weakest consumer).
- Per-consumer containment: a throwing `pendingIngestBacklog()` contributes -1; warn once
  per JVM via a static latch (`LSSApi` has no session lifecycle to key on, and per-JVM is
  strictly quieter — matches the AntiXray once-bounded-logging convention). The latch is
  test-visible (package-private reset) so the warn-once contract is pinnable.
- Negative reports are "no signal"; 0 is a real report meaning "empty".

### 3.2 Voxy bridge (`VoxyCompat`)

The registered consumer becomes an anonymous class (not a lambda) overriding
`pendingIngestBacklog()` with the three-handle chain above. Handles are resolved in
`init()` in a SEPARATE try/catch from the ingest handles: a Voxy that renames the
backlog surface must not cost us the ingest bridge — resolution failure logs once (warn)
and the override permanently returns -1. At poll time, a null instance or any Throwable
returns -1 (fail-open, no logging — null-instance is a routine pre-join state).

### 3.3 Policy (`LodRequestManager` + `SpiralScanner`)

One new constant in `LodRequestManager` (which already owns halt policy):

```java
static final int INGEST_BACKLOG_HALT_SECTIONS = 6144;
```

- **Taper**: `maybeScan` gains the backlog pair (mirroring the queue pair). The budget
  scale becomes the MIN of the two independent factors — min, not multiply, because both
  gauge the same downstream pipe and multiplying would double-count:

  ```
  scale = min(1 - queueSize/haltThreshold, 1 - backlog/INGEST_BACKLOG_HALT_SECTIONS)
  budget = max(1, round(WANT_SET_BUDGET * max(0, scale)))
  ```

  A backlog ≤ 0 (including -1 = no signal) leaves the budget untouched — behavior is
  bit-identical to today whenever no consumer reports (soak, gametests, non-Voxy mods,
  Voxy incompatible, kill switch off).

- **Halt**: `haltedByBackpressure` gains the backlog term:
  `... || ingestBacklog >= INGEST_BACKLOG_HALT_SECTIONS`. Entering the halt rides the
  SAME edge-triggered clear-batch flag (`backpressureClearSent`) — one empty batch per
  halt CROSSING (the flag re-arms on any non-halted tick, so a backlog seesawing across
  the threshold during the post-halt tail re-sends a clear per re-cross; bounded, cheap,
  and byte-identical in shape to the existing decode-queue halt — deliberately no added
  hysteresis). The server backlog is replaced with nothing; already-admitted work still
  completes. Recovery is automatic when the backlog drains below the threshold.

- **Wiring + kill switch**: `tick()` polls
  `LSSClientConfig.CONFIG.enableIngestBackpressure ? LSSApi.maxReportedIngestBacklog() : -1`
  through a package-private `IntSupplier ingestBacklogSupplier` seam (production default =
  that expression) and passes it into `tickWithContext`. New client config field
  `enableIngestBackpressure`, default true — same kill-switch convention as
  `enableV16ServerCompat` / `missMemoTtlSeconds`.

- **Diagnostics**: the `scan` trace event gains `"ingest_backlog":N`; the manager exposes
  `getLastIngestBacklog()` for `/lss` diag output.

### 3.4 Choosing 6144

Halt point ≈ 6144 pending sections ≈ 750–1200 real terrain columns (~5–8 served sections
each) ≈ 20–60 MB of retained section data at steady state. Drain time at halt: ~3–6 s
for a weak client (~1–2k sections/s), <1 s for a strong one. The taper makes the halt a
backstop rather than the operating point: at equilibrium the budget settles where
arrivals ≈ drain, e.g. a client ingesting 1500 sections/s with ~6-section columns
settles near budget ≈ 250/s with a standing backlog ≈ ⅔ of the halt threshold — the
backlog only reaches the halt when the serve rate exceeds the drain rate even at
budget 1. Strong clients (>10k sections/s) never leave scale ≈ 1.

**Post-halt tail (the bound is halt + in-flight pipeline, not the constant alone).** The
clear batch empties only the server's BACKLOG; everything already past admission still
arrives and dispatches into the consumer: the server's send queue, admitted slot work,
and the client's own decode queue. With the taper active the realistic tail is ~one
want-set of in-flight work (≈ 5–8k further sections — roughly doubling past the halt);
the adversarial co-occurrence (a full 4000-payload send queue AND a near-full decode
queue at the moment of halt) is bounded but much larger (~100–400 MB) — the taper makes
it unlikely (reaching the halt requires the budget to have already collapsed toward 1,
which starves the send queue first). Still a one-shot, bounded overshoot versus today's
unbounded growth. The live acceptance run (§6) explicitly watches peak `getTaskCount()`
overshoot past the halt on a weak client.

The constant deliberately does NOT derive from any server cap (the retired Global
Constraint #28 class) and does not touch `WantSetBudgetInvariantTest`'s static
inequality — the taper only ever *shrinks* below `WANT_SET_BUDGET`.

## 4. Invariants preserved (checked against the pinned set)

- **No send-time suppression of awaited positions.** The taper shrinks the BUDGET; the
  ring walk still declares unsatisfied positions closest-first, awaited ones included —
  they are nearest, so they survive shrinking first. Positions beyond the shrunken budget
  are dropped by the server's backlog replace (counted superseded) and return when
  pressure drains — the identical shape the decode-queue taper already has.
- **No cadence debounce.** The 20-tick cadence is untouched; a halted tick returns
  without scanning but never resets the counter.
- **Convergence sends nothing.** Unchanged — taper only shrinks; the walked-but-empty
  scan still sends nothing.
- **The clear batch stays the only producer of empty batches**, still edge-triggered,
  now on the OR of the two halt conditions.
- **`NOT_GENERATED` permanence, replace semantics, dedup, dirty flow**: untouched.
- **Soak**: the headless soak client registers a recorder consumer that does not
  override the new default ⇒ -1 ⇒ zero behavioral change; no schema changes.
- **v16 compat (both shims)**: the budget/halt machinery is session-agnostic and already
  ran under both shims via the decode-queue signal; no new interaction is introduced.

## 5. Failure modes

| Condition | Behavior |
|---|---|
| Voxy absent / no consumer reports | -1 → bit-identical to current behavior |
| Voxy present, no instance yet (pre-join) | -1 → unscaled first scans, signal appears once the world engine exists |
| Future Voxy renames the chain | init-time resolution fails → warn once → -1 forever, ingest bridge unaffected |
| `getTaskCount` throws at poll time | contained → -1 |
| Consumer reports a huge/stuck value | halt persists until it changes — same trust level as a consumer rejecting every column today; kill switch (`enableIngestBackpressure=false`) restores current behavior |
| Two consumers, one slow | max() paces to the slower one (deliberate) |

## 6. Test coverage

Tier 1 (all new tests JUnit, existing stub/seam patterns):

1. **`VoxyCompatTest`** (stub classes in `fabric/src/test/java/me/cortex/voxy/...`; add
   `commonImpl/VoxyCommon` + `commonImpl/VoxyInstance` stubs, add instance
   `getTaskCount()` to the stub `VoxelIngestService` with settable state):
   backlog happy path returns the stub count; null instance → -1; missing
   method/class → init still registers the ingest consumer and backlog reports -1
   (the separate-try/catch pin); throwing `getTaskCount` → -1.
2. **`LSSApiTest`** (NEW file — only `LSSApiDispatchTest` exists today): no consumers →
   -1; non-reporting consumer → -1; one reporting → its value; two reporting → max;
   reporter throwing → contained to -1 + the other consumer's value still wins;
   warn-once latch (exactly one warn across repeated throws, reset seam restores).
3. **`SpiralScannerTest` / budget tests**: backlog 0 and -1 → budget unchanged; backlog =
   half of halt → ~half budget; min-composition with a nonzero decode queue (the smaller
   factor wins); `max(1, …)` floor preserved.
4. **`LodRequestManagerTickTest`**: ingest backlog ≥ halt → no scan + exactly ONE empty
   clear batch (edge-trigger), second halted tick sends nothing; recovery below halt
   resumes scanning and re-arms the edge; decode-queue halt paths byte-identical to
   before; kill switch: supplier seam returns -1 when config is off (config-gate pin);
   the production default of the supplier seam reads
   `LSSApi.maxReportedIngestBacklog` (wiring pin via a registered stub consumer).
5. **Config tests**: `enableIngestBackpressure` default true, save/load round-trip,
   malformed-file fallback.
6. **Existing-test migration IS the bit-identity pin.** The `maybeScan` /
   `tickWithContext` / `haltedByBackpressure` signature changes ripple through ~26
   existing call sites in `SpiralScannerTest`, `LodRequestManagerTest` (incl. its
   `maybeScanOnce` helper), and `LodRequestManagerTickTest`; every migrated site passes
   `-1` (no signal), so the whole existing suite becomes the proof that a non-reporting
   client is bit-identical to today. Mechanical, but in scope — no assertion changes.

Tier 2/3: no changes — gametests run without Voxy (default -1 keeps behavior identical);
`LSSClientGameTests`' consumer does not report, pinning the no-signal path end-to-end by
construction.

Live validation (manual, post-merge): `/lss trace` gains `ingest_backlog` in scan events;
on a real client with Voxy, watch the budget taper track `getTaskCount` during a fresh
backfill (the reporter's scenario at default server config is the acceptance case), and
record the peak backlog overshoot past the halt during the tail (§3.4).

Coordinated doc edit: CLAUDE.md's want-set invariant sentence ("a single edge-triggered
empty batch when the client's decode queue crosses into backpressure halt") must widen to
"decode queue or consumer-reported ingest backlog", and the Tier-1 inventory gains the
new tests — updated in the same PR so the pinned-invariant prose cannot drift.

## 7. Relationship to the parked defaults retune

`fix/issue-71-default-retune` (parked, unreleased) lowers SERVER-side default send rate.
The two are complementary layers: server defaults protect clients that haven't updated;
this change protects ANY client against ANY server config, and paces to actual capability
instead of a one-size guess. Whether the defaults retune still ships (and at what values)
is a separate decision at backport time.
