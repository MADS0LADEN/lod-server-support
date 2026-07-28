# Review-round MAJORs M1–M4 — fix plan (v0.8.1, all three lines; M2 main-only)

**Status:** planned · **Source:** 4-agent Opus review of main `8c601c1` (2026-07-28), all four
findings independently re-verified against code/bytecode before this plan. Ships in v0.8.1
together with the issue-#62 fix already on main.

Branch: `fix/review-majors` off main. **Four logical commits, M2 isolated** — M2's mixin
retarget is MC-26.2-specific (26.1/1.21.11 have different `publishServer` shapes and working
hooks), so the support-line backport cherry-picks M1/M3/M4 only.

---

## M1 — implicit-sky fill dead on resync deliveries (client, all lines)

**Bug:** `ClientColumnProcessor.drainColumnQueue` runs `withAirFilledAbsentSections` (resync)
BEFORE `withImplicitSkyAbove`. The air-fill covers every section-Y, so the sky pass hits its
`top >= levelTop` early return; even without it, its loop appends (`y > top`, skips
present) and never replaces. Its own comment ("air-fill runs FIRST … re-created here
bright; order matters") describes behavior the code does not have. Every re-served column
(dirty edit, warm-rejoin re-serve, ingest retry) dispatches dark above-band air —
`VoxyCompat` H-12 turns null sky into explicit zeros — regressing the v0.8.0
black-boundary-faces fix on second serves.

**Fix:** swap the two passes — implicit-sky FIRST (computes `top` from the genuinely served
sections), resync air-fill SECOND (fills the remaining below/among-band Ys with dark air,
which is correct — unserved air inside/below terrain is dark). The air-fill's `seen` check
already skips the sky-filled Ys, so no duplicates. Delete the now-provably-dead `byY` map +
`existing != null` check in `withImplicitSkyAbove` (nothing present can be above `top` by
definition) and rewrite both stale comments to describe the real order.

**Deliberately unchanged:** a resync 0-section CLEAR still air-fills all-dark (the sky pass's
`present.length == 0` early return is the pinned "a CLEAR stays a clear"). That is today's
shipped, pinned semantics; whether a sky-dimension clear should be bright is a separate
question, flagged to the plan reviewer, not smuggled into this fix.

**Tests:**
1. Drain-level composed pin (the gap that let this ship): `hasSkyLight=true` + resync
   delivery with a low served band → every section above the served top carries FULL_BRIGHT
   sky, every filled section below/among the band is dark air, served sections untouched.
2. Unit compose: `withImplicitSkyAbove` then `withAirFilledAbsentSections` on the same
   input → identical section set to the drain-level expectation (order pinned at the unit
   level too).
3. Existing pins stay green: the non-resync `assertSame` early-return pin, the CLEAR pins,
   `MaskedWireRoundTripTest`, receiver-glue 0-section clear tests.

## M2 — "Open to LAN" never starts the service on MC 26.2 (Fabric, main line ONLY)

**Bug:** `IntegratedServerLanHook` pins the 4-arg
`publishServer(MultiplayerScope, GameType, boolean, int)` — but 26.2's LAN screen
(`MultiplayerOptionsScreen.changeMultiplayerScope`) invokes the 2-arg
`publishServer(MultiplayerScope, int)` directly (bytecode-verified), and the 4-arg is a thin
wrapper delegating to it. The GUI path never fires the hook; only `/publish` works.

**Fix:** retarget the `@Inject` to
`publishServer(Lnet/minecraft/server/MinecraftServer$MultiplayerScope;I)Z` (handler params
`(MultiplayerScope, int, CallbackInfoReturnable<Boolean>)`). One injection covers both entry
points — the 4-arg delegates into it. Additionally wrap the hook body in
`server.execute(() -> startServiceForLan(server))`: the GUI calls `publishServer` on the
render thread, and service construction does thread starts + a blocking
`ColumnTimestampCache.load()` (the reviewer's NIT that becomes live the moment the hook
fires again). `startServiceForLan` stays `synchronized` + volatile-published; running it on
the server thread also matches the dedicated-server construction context.

**Tests:** no tier drives a real LAN publish. New Tier-1 `LanHookContractTest`: parse the
`@Inject.method` descriptor off `IntegratedServerLanHook` reflectively and resolve it
against `net.minecraft.client.server.IntegratedServer`'s declared methods (direct class
literal — Fabric remapping rule) — pins that the descriptor names a REAL overload, the
regression class that bit here (it goes red at the next MC bump if the signature drifts,
instead of silently never firing). If client classes turn out not to load under
fabric-loader-junit, fall back to pinning the descriptor string + a release-notes manual
LAN smoke step. Manual verification: one Open-to-LAN smoke on the multi-test round
(documented as a step there).

## M3 — `diskReadDone` unbounded per-session growth (common, all lines)

**Bug:** every served position adds to the per-player `LongOpenHashSet` forever; only
dirty-clears and ts≤0 honest re-resolutions remove. A roaming session accretes tens of MB
per player, freed only at disconnect/dimension change.

**Why sweeping is semantically free:** ingress range-filters every declaration at
`lodDistanceChunks + LOD_DISTANCE_BUFFER` from the player's CURRENT chunk (both platforms),
so an out-of-range done-bit can never be consulted again while the player stays away. If the
player returns: a ts>0 re-declaration still short-circuits on the timestamp-cache rung
(cheap, unaffected); a ts≤0 re-declaration re-resolves honestly — one extra disk read, which
is precisely the designed self-heal path. Nothing in the delivery-honesty or
duplicate-serve-grace model depends on out-of-range done-bits (`departedColumns` is a
separate, already-swept map).

**Fix:** `AbstractPlayerRequestState.sweepDiskReadDoneOutsideRange(int radiusChunks)` —
iterate `diskReadDone` with the fastutil iterator, remove entries whose Chebyshev distance
from `playerChunkPacked` (the existing volatile stamp the spread gate already reads on the
processing thread) exceeds `radiusChunks`; no-op when the stamp is `NO_PLAYER_CHUNK`.
Called from `OffThreadProcessor`'s existing `EVICTION_INTERVAL_CYCLES` (~60 s) block for
every registered player. Radius = `lodDistanceChunks + LOD_DISTANCE_BUFFER + SWEEP_MARGIN
(16)` — strictly wider than the ingress filter so a position can never be swept while still
declarable; the radius reaches the processor via a new constructor parameter (both platform
subclasses pass their config value). Post-sweep steady state ≈ the converged disc
(~263k entries at range 256 — the legitimate served set); the once-a-minute iteration of
that is single-digit ms on the processing thread.

**Tests:**
1. Unit: seed near + far done-bits, sweep, far removed / near kept; exact Chebyshev
   boundary (radius kept, radius+1 swept); `NO_PLAYER_CHUNK` no-ops; the packed-coordinate
   sign cases (negative chunk coords).
2. Wiring pin (the #62 lesson): drive a processor through `EVICTION_INTERVAL_CYCLES`
   cycles with a stamped player chunk and a far done-bit → the bit is gone and a ts≤0
   re-declaration of that position re-submits a disk read (the honest heal, proving no
   false up-to-date).
3. Existing duplicate-serve-grace + honest-re-resolution pins stay green.

## M4 — disk-read starvation by fixed player order (common, all lines)

**Bug:** `routeAll` iterates `snapshot.playerDimensions().entrySet()` — same
UUID-hash-bucket order every tick — against the GLOBAL `hasHeadroom()` gate, and
`NO_DISK_HEADROOM` retains-and-stops per player. Under pool saturation (routine with
background-priority reads parked behind vanilla loads: 5 threads × 32-deep queue) the first
player absorbs every freed slot; later players get no disk reads — and no generation, since
the disk miss is the trigger — until earlier players converge.

**Fix:** rotate the drain starting point per cycle, mirroring the existing probe-budget
rotation (`RequestProcessingService:277`): copy the entries to a list, start at
`Math.floorMod(routeRotation++, size)`, wrap around. `routeRotation` is a plain int field
on `IncomingRequestRouter` (processing-thread only). Within-cycle relative order is
otherwise unchanged; dedup group leadership just moves with whoever drains first, which is
already order-agnostic (any member can lead).

**Tests:**
1. Unit (router seam, two players, injected `hasDiskHeadroom` that admits exactly one
   fresh submission per cycle): over 4 cycles, both players get ≥1 submission and the
   starter alternates — the pin that fixed-order starvation is gone.
2. Rotation advances even on cycles where the first-drained player has an empty backlog
   (no wedging on idle players).
3. Existing router conservation / retain-order / dedup pins stay green.

---

## Validation (beyond the unit tests above)

- Tier 1 + Tier 2 on main; **Tier 3 as well** (M1 changes client decode output — Tier 3
  asserts decoded content end-to-end).
- Soaks on main: `fresh-backfill` (M4 routing + M3 sweep under real load), `warm-rejoin`
  (resync-path accounting over M1), `dirty-broadcast` (drives resync deliveries).
- Support lines after backport: Tier 1 + 2 via the release build + `release_check
  --version 0.8.1` (support-line effort budget; the live multi-test round is the final
  gate).
- The multi-test round then covers: visual sky check on re-served columns (M1 — edit a
  block near a column, verify no dark band), an Open-to-LAN smoke on 26.2 (M2, manual),
  and normal backfill behavior (M3/M4 regressions would show as stalls).

## Amendments after the plan review (all applied to the implementation)

1. **M2 splits in two.** The descriptor retarget is main-only, but the render-thread
   construction hop belongs on ALL THREE lines — 26.1/1.21.11's single-overload hooks fire
   from `ShareToLanScreen` on the render thread today. Implementation: the hop moves INSIDE
   `LSSServerNetworking.startServiceForLan` (`server.execute(...)` with the started-check
   inside the task) — line-portable, mixin bodies stay synchronous, and `cir.getReturnValue()`
   is read in the callback frame (never captured into a deferred task). Accepted + documented:
   a ≤1-tick window between listener-up and service-up (a join cannot complete inside it).
2. **M3 sweep keeps in-pipeline and grace-window positions**: skip entries with
   `hasEnqueuedColumn(packed)` or `isWithinDepartureGrace(packed, now)` — both same-class,
   O(1) — so a teleport with a backed-up send queue cannot double-serve.
3. **M3 sweeps ONE player per eviction cycle** (round-robin), not all — bounds the
   processing-thread spike; inter-sweep growth at 20 players is ~7 MB transient, acceptable.
4. **M3 constructor churn contained**: the new `sweepRadiusChunks` lands on the full
   constructor; a delegating overload keeps the old signature (production sites pass the
   real radius; test harnesses stay untouched). Radius captured at construction — safe today
   (no config-reload path exists); one comment notes the assumption.
5. **M3 wiring pin mechanism**: a package-private `primeEvictionCounterForTest()` sets the
   counter to threshold−1 so ONE posted snapshot fires the real eviction block — no
   1200-cycle drive, no latest-wins coalescing hazard.
6. **`LanHookContractTest` via source regex** (the `GameTestEntrypointContractTest` idiom):
   parse `@Inject(method = "…")` out of the mixin SOURCE (mixin-package classes refuse
   classloading under fabric-loader-junit), resolve the descriptor against
   `IntegratedServer.class.getDeclaredMethods()` (client classes DO load at Tier 1).
7. **M1 test rig gains a mid-band section builder** so the composed pin asserts both halves
   (above-band bright AND below-band dark); new composed CLEAR pins (sky + non-sky resync
   0-section drains) fix the current clear behavior in place BEFORE the clear-bright change.
8. **CLEAR-bright ships as its own commit (all lines)** per the reviewer's verdict: a
   0-section clear in a sky dimension bright-fills (a `bright` flag on
   `withAirFilledAbsentSections`, gated on `hasSkyLight`) — vanilla sky light in an all-air
   column is 15 top-to-bottom, so dark was wrong (WorldEdit-cleared columns rendered as
   black volumes); the wire form and "a clear stays a clear" are untouched, and
   `withImplicitSkyAbove`'s empty-input early return (pinned) stays.
9. Wording/comment fixes folded in: below-band dark is correct for normal terrain but a
   pre-existing approximation for floating-island columns (band rule drops below-band sky
   air — unchanged here); the `byY` map was dead before the reorder too; the shared
   `FULL_BRIGHT_SKY` byte[] read-only contract gets a comment; M4's dedup-primary rotation
   (disconnect of a rotated leader cancels attached reads — self-heals, counted superseded)
   is documented; `SectionLightDefaultsTest`'s "statement-for-statement" comment updated.

## Release integration

- v0.8.1 notes gain the new bullets on every line (M1 phrased as "fixes dark faces
  returning after block edits near LOD terrain"; M2 main-notes only — "LOD now works for
  Open to LAN worlds"; M3 "bounded per-player memory on long roaming sessions"; M4 "fair
  disk scheduling between players"). CLAUDE.md Tier-1 blurb + the M1/M3/M4 architecture
  bullets updated; the v0.8.1 release report re-issued with new tips + validation rows.
- The already-cherry-picked #62 commits on the support branches stay; M1/M3/M4 land on top.
