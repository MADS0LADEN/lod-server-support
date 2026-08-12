# `dirtyBroadcastIntervalSeconds: 0` = disable client dirty pushes — plan

**Status: PLANNED, unimplemented** (2026-08-12). No code has changed; this documents the
agreed design for supporting `0` as an "off" value for the dirty-broadcast interval.
**Reviewed 2026-08-12** (1 Fable subagent, all citations re-verified against the code):
verdict IMPLEMENT WITH FIXES, 0 MAJOR / 7 MINOR — all folded into this revision. The
review independently confirmed the per-player clears are REQUIRED (not optional) for
the mid-session heal promise, via the duplicate-rung-before-timestamp-rung order.

## Context

Today `dirtyBroadcastIntervalSeconds` is clamped to `[1, 300]`
(`ServerConfigBase.validate()`, `MIN_DIRTY_BROADCAST_INTERVAL = 1`), so a configured `0`
silently becomes `1` — the *fastest* broadcast cadence, the opposite of what an operator
writing `0` means. The goal: `0` becomes a supported "off" value — the server stops
pushing `DirtyColumnsS2CPayload` to clients, and clients pick up changed terrain only
through their natural re-asks (fresh rejoin, a re-declaration after
movement/clearcache/ingest-failure, etc.).

The trap this plan is built around: **the dirty-broadcast tick is not just a send.** Its
drain carries the entire server-side invalidation fan-out — one call,
`offThreadProcessor.invalidateTimestamps(dim, dirty)` (`DirtyColumnBroadcaster.java:94`,
`PaperDirtyColumnBroadcaster.java:65`), which downstream (in
`OffThreadProcessor.applyInvalidations`) invalidates LOD-store rows (the store's
correctness backstop), invalidates the `ColumnTimestampCache`, taints in-flight
reads/generations, and marks stale generations. If `0` skipped the whole tick:

- stale LOD-store rows would keep serving until an independent mechanism catches them
  (Fabric's save-hook DELETE-only bridge closes the store hole broadcast-independently;
  Paper's periodic resweep bounds it at ~lodStoreResweepSeconds — but the tscache leg
  below has no such backstop, so the fan-out must still run);
- `ts>0` re-asks would answer `up_to_date` off stale tscache stamps for the rest of the
  boot (the shutdown `drainAll()` invalidates before the final tscache save, so the
  damage is until-restart, not forever) — still breaking the "heals on rejoin"
  semantics the feature is for;
- `DirtyColumnTracker` would grow unboundedly (marks never drained — matches the
  tracker's own in-source wording);
- `dirty.pending` is a `SERVER_DRAINS` member in `check_soak.py` — quiescence would
  break in any future scenario running interval 0.

(Review note, 2026-08-12: the per-player clears are additionally REQUIRED for this
plan's own "mid-session re-ask heals" promise — the router's duplicate/done-bit rung
fires BEFORE the timestamp rung, so an uncleaned done-bit answers a returning player's
`ts>0` re-ask with a stale `up_to_date` even though the tscache was invalidated.)

## Chosen semantics

**`0` disables ONLY the wire send. The drain, the invalidation fan-out, and the
per-player done-bit/probe-stamp clears all keep running on an internal fallback
cadence.** The only behavior change is that no `DirtyColumnsS2CPayload` leaves the
server. A rejoin heals fully (per-player state dies at disconnect; tscache/store were
invalidated), and any mid-session re-ask re-resolves honestly (done-bits were cleared).

Accepted, documented consequences:

- mid-session, connected clients keep stale LOD until they re-ask;
- `NOT_GENERATED`-parked positions lose their one mid-session revival path (the dirty
  broadcast) and heal only on reconnect.

Negative values normalize to `0` (the `lodStoreMaxMB` "0 and negative nonsense" idiom);
`1` stays the *nonzero* floor and `300` the ceiling.

## Changes

### 1. Constants — `common/.../LSSConstants.java`
Next to `MIN_DIRTY_BROADCAST_INTERVAL`/`MAX_DIRTY_BROADCAST_INTERVAL` (both unchanged):
add `DIRTY_DRAIN_ONLY_INTERVAL_SECONDS = 10` — the drain cadence used when sends are
disabled (matches the field's default, so "off" costs the same server-side as default).

### 2. Config — `common/.../config/ServerConfigBase.java`
- Clamp switches to the `lodStoreMaxMB` idiom:
  `dirtyBroadcastIntervalSeconds = dirtyBroadcastIntervalSeconds <= 0 ? 0 : Math.clamp(..., MIN, MAX)`.
- Field javadoc: `0` = dirty pushes disabled; the drain + invalidation fan-out still run
  every `DIRTY_DRAIN_ONLY_INTERVAL_SECONDS`; clients refresh only on rejoin/re-request;
  note the `NOT_GENERATED` revival consequence AND the flip-back consequence: edits
  drained during an off window left the tracker — re-enabling never retroactively
  pushes them (they surface via re-ask only).
- One `LSSLogger.info` in `validate()` when the value is 0 (precedent: PaperConfig's
  Folia store warn) so an operator sees the mode in the log. (This line also fires
  during the config test suites' extreme-value clamp sweeps — harmless, expected.)

### 3. Fabric broadcaster — `fabric/.../server/DirtyColumnBroadcaster.java` (tick)
- Read the config field **once per tick** into a local (avoids a torn read between
  cadence and send-gate; the live-read-per-tick contract is pinned by
  `midRunConfigIntervalChangeTakesEffectImmediately` on the Paper twin).
- `boolean sendsEnabled = intervalSeconds > 0; int cadence = sendsEnabled ? intervalSeconds : DIRTY_DRAIN_ONLY_INTERVAL_SECONDS;`
  — note today `0` would compute `intervalTicks = 0` and drain *every* tick, so the
  fallback cadence is required, not optional.
- Leave the rest untouched (drain, `invalidateTimestamps`, per-player gates, range
  filter, pagination, `clearDiskReadDone`, `clearProbeSuppress`). Gate **only** the
  `playerView.send(...)` (and its failure handling — `failedPlayers` is send-scoped) on
  `sendsEnabled`.
- Do not move the tick call site — `flushSendQueues → dirtyBroadcaster.tick` ordering is
  load-bearing (`AbstractPlayerRequestState.clearProbeSuppress` javadoc).

### 4. Paper broadcaster — `paper/.../PaperDirtyColumnBroadcaster.java` (tick)
Textual twin of change 3. Same single-read, same gate placement.

### 5. Client comment fix — `fabric/.../client/LodRequestManager.java` (onDirtyColumns)
Comment-only: the handler reasons about "the legal `dirtyBroadcastIntervalSeconds` floor
(1 s)". Reword to "the floor for a *sending* interval (1 s); 0 disables sends entirely
(this handler simply never fires then)".

## Tests

### Config
- `fabric/src/test/.../config/ConfigValidationTest.java`
  - Rewrite `dirtyBroadcastIntervalSecondsClamped` (currently asserts `0 → 1`): `0`
    stays `0`, `-5 → 0`, `1` stays `1` (nonzero floor), `9999 → 300`. Mirror the
    naming/shape of `lodStoreMaxMBZeroStaysUncappedAndNonzeroFloorsAt64`.
  - Add `"dirtyBroadcastIntervalSeconds"` to the 0-floor `case` list in the reflective
    sweep's `switch` (`everyNumericServerFieldClampedAtIntExtremes`) with a one-line
    rationale in the comment block above it.
- `paper/src/test/.../PaperConfigValidationTest.java`
  - `SHARED_BOUNDS` row → `new Bounds(0, MAX_DIRTY_BROADCAST_INTERVAL)` + the same
    comment pattern the `lodStoreMaxMB`/`outboundBufferCeilingKB` rows use ("real floor
    applies to nonzero only, pinned by named test"). The sweep's "exact minimum kept"
    leg then passes with 0.
  - Add a named test pinning `0` kept / negative → 0 / nonzero floor 1 (twin of the
    Fabric named test).

### Broadcasters (the behavior pins)
- `fabric/src/test/.../DirtyColumnBroadcasterTest.java` — the existing `Rig` +
  `RecordingProcessor`/`FakeView` seams record `invalidateTimestamps`,
  `clearDiskReadDone`, and sends in one ordered log. NOTE (review): neither broadcaster
  suite currently observes `clearProbeSuppress`, and `probeSuppressCountForTest()` is
  package-private to `common.processing` — pin it through the PUBLIC state instead
  (`stampProbeSuppress(...)` beforehand, then assert `isProbeSuppressed`/`skipProbe`
  false after the fallback tick), or extend the rig's recording. Add:
  1. interval 0: after `DIRTY_DRAIN_ONLY_INTERVAL_SECONDS * 20` ticks, the tracker is
     drained (`pendingCount() == 0`), `invalidateTimestamps` fired, `clearDiskReadDone`
     + `clearProbeSuppress` applied for the in-range player — and **zero sends**
     recorded.
  2. interval 0 fires on exactly the fallback-cadence tick, not before (twin of
     `firesOnExactlyTheIntervalTickAndCounterRestartsFromZero`).
  3. mid-run flips BOTH ways: `0 → 1` resumes sends on the next interval, and
     `nonzero → 0` stops sends while the drain continues at the fallback cadence
     (live-read pins, twin of the Paper mid-run test).
- `paper/src/test/.../PaperDirtyColumnBroadcasterTest.java` — same three, using its
  Mockito rig + `setDirtySender` recorder. `zeroEligiblePlayersStillInvalidatesTimestamps`
  is the shape to copy for test 1.
- `fabric/src/gametest/.../LSSGameTests.java:137` (`allConfigFieldsInValidRange`) —
  asserts the loaded interval is within `[MIN, MAX]` = `[1, 300]`; stays green today
  (gametest run dirs ride the default 10) but must learn the new value set:
  `== 0 || (>= MIN && <= MAX)`.

### Untouched by design (sanity, not edits)
- Tier 2 `TwoPlayerGameTests` fan-out and all soak scenarios run at explicit/default
  nonzero intervals — no changes needed; they must stay green as the proof the nonzero
  path is byte-identical.
- `ExporterContractTest`'s `marked_total == broadcast_positions + pending` identity
  holds (the drain still runs; `broadcast_positions` is `getTotalDrained()` — a drain
  counter, so it keeps climbing with sends off; no counter/schema changes).
- `scripts/check_soak.py` needs nothing: defaults unchanged, and 25 of 26 scenario
  configs pin the interval explicitly (`store-offline-mutate-config.json` omits it and
  rides the unchanged default; the checker's window math falls back to
  `DEFAULT_DIRTY_BROADCAST_SECONDS = 5`). Caveat for the future: the dirty-family
  named checks size their windows as `2 * interval` — any FUTURE scenario that stages
  interval 0 needs checker work first, not just the quiescence note above.

## Docs
- CLAUDE.md: in the Configuration section's server-config bullet, note "dirty broadcast
  interval (`0` = disable client dirty pushes; the server-side invalidation drain still
  runs)".
- `docs/planning/config-defaults-and-clamps-review-2026-08-02.md:159` documents the
  clamp as `1..300` and CLAUDE.md cites that doc as the full clamp audit — append an
  erratum (the doc already carries a 2026-08-08 one for the D0 tile redesign).
- Release-notes item for the next release (Configuration category): `0` now disables
  dirty broadcasts; previously it clamped to 1 s. Mention the rejoin-only refresh
  tradeoff.

## Verification
1. `./gradlew :fabric:test -x runGameTest -x runClientGameTest` — Tier 1 incl. the new
   broadcaster + config pins (note: no CI retry on Tier 1).
2. `./gradlew :paper:test` — Paper twins.
3. `./gradlew :fabric:runGameTest` — Tier 2 unchanged-behavior proof
   (TwoPlayerGameTests fan-out still green at default interval).
4. Optional live smoke: `./test-server.sh run-fabric` with
   `dirtyBroadcastIntervalSeconds: 0` — NOTE (review): `/lsslod diag` does NOT render
   the interval (`collectDiagData` takes no dirty-related config), so the receipt is
   the new `validate()` info line in the boot log. Then a `setblock` near a joined
   client: no client re-serve mid-session; rejoin shows the edit; `/lsslod store
   status` confirms the store row for the edited column was invalidated (re-deposited
   on the rejoin serve).
