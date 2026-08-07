# Movement desync tracer — implementation plan v2.1 (2026-08-06)

Implements §5 of `docs/planning/moved-wrongly-investigation-2026-08-06.md`: server-side
observability for the three movement-rejection paths in 26.2's
`ServerGamePacketListenerImpl.handleMovePlayer`, plus per-flight telemetry, so organic
events on the live server arrive labeled with the data that decides the hypothesis.

**v2 (same day): reworked after a two-Opus review round** (mixin/bytecode feasibility +
scientific utility; findings and dispositions in §7). The v1 draft's mixin design was
verified sound at the bytecode level where it mattered (INVOKE census, post-`move()`
position semantics, the full Moonrise bridge), but it carried one real blocker — the
accessor mixins would have hard-crashed *tracer-disabled* servers on a future MC field
rename — and its success criterion licensed wrong conclusions in both directions from
the first captured event. v2 fixes the crash surface, replaces the verdict logic with a
three-valued criterion backed by new fields, and adds the operational hardening a
~weekly event cadence demands. **v2.1: one MAJOR + four small fixes from the follow-up
single-Fable full-plan review** (§7.1) — chiefly that the REFUTE arm's retro-dating
evidence must live in the 5 Hz ring, not only the 1 Hz rows: at elytra speed the 1 Hz
rows' ±2-chunk mask anchors ~1.5–2 chunks behind the event position, so the collision
chunk falls outside exactly when it matters, collapsing every `sent:true` event into
INDETERMINATE for want of data the tracer could cheaply have written.

**Activation: `-Dlss.moveTrace=true`, OR the presence of a marker file
`config/lss-move-trace.enable` (content ignored).** No config key, nothing in
`lss-server-config.json`, absent from the documented config surface (user decision).
The marker file is not configuration — nothing is read from it; it exists because the
only guaranteed channels to the live host are SFTP and RCON, and the Modrinth panel's
support for custom JVM flags is unverified (review U-9). Default absent → fully off: no
file, no thread, hook bodies no-op after one static boolean check (the
`-Dlss.admissionTrace` precedent).

**Scope: Fabric only.** The repro server is Fabric; Paper cannot mixin. Explicitly out
of scope until a Paper server exhibits events. The **vehicle path**
(`handleMoveVehicle` has its own `moved wrongly!`/`moved too quickly!` warns and its
own silent rejection) is also out of scope — stated so a log-census grep is never
misattributed to the player path (review F-13).

## 0. Design constraints

1. **Must observe every client** — modded, vanilla, cheating — with zero client
   cooperation.
2. **Must work with LSS disabled** and for unregistered players (the E2 control
   flights): tracer lifecycle hangs off Fabric server lifecycle events in `LSSMod`,
   never off `RequestProcessingService`, and **every transport field lives in the row
   envelope, not the `lss` block** (reviews F-4/U-6 — v1 put obuf inside `lss`, which
   is null in exactly the control arms that need a baseline).
3. **Inert when off, near-free when on.** Event paths fire a few times per session;
   telemetry is cheap per §1.5. Captures assemble a record and `offer()` it to a
   bounded queue; drops are counted, never waited on.
4. **Diagnostic code must never take the server down — including when OFF.** This is
   why the mixins get their own **non-required config** (§1.2): `@Accessor` has no
   soft-fail path, and accessor mixins apply at classload regardless of the property
   gate. A failure anywhere degrades to missing fields, warned once.
5. **Never confidently wrong.** Fields that cannot be captured are ABSENT, not zeroed;
   flags that could latch stale are self-invalidating (§1.2's packet-identity token);
   quantities that differ by rung carry different names (§1.3).

## 1. Components

### 1.1 `dev.vox.lss.trace.MoveDesyncTracer` (fabric module, production package)

The writer. Ships in the release jar (`release_check.py`'s forbidden globs cover only
`benchmark`/`soak` — verified untouched; the new top-level package is a deliberate
choice, noted here per review F-13).

- Bootstrap from `LSSMod` behind the §0 gate; **the marker file is read once at
  `SERVER_STARTING`** (Fable F2-11: the config dir is absolute via FabricLoader by
  then, the static gate is set strictly before any `handleMovePlayer` can run, and the
  upload-then-restart deploy procedure makes the check race-free by operation). When
  enabled, one INFO at server start: `Move desync tracer ACTIVE -> <path>` — a deploy
  missing the flag is visible in the first screenful. **The negative check is part of
  the deploy procedure** (§4).
- Output: `<serverDir>/logs/lss-move-trace.jsonl` (override `-Dlss.moveTrace.file=`).
  Append across restarts; every row carries `bootId` + schema version (§1.6).
- Single daemon writer thread (`LSS-MoveTrace`), `ArrayBlockingQueue<String>` 4096,
  `offer()` from game threads, `dropped` counter carried in the next row. Buffered
  writer, flush per row (rows are sparse; a crash must not lose its own event).
- **Rotation, not truncation** (review U-13): at 128 MiB rotate once to
  `lss-move-trace.1.jsonl` (256 MiB total cap), `LSSLogger.warn` on rotation and on
  final cap — `latest.log` is where the operator greps; a sentinel row inside a file
  nobody is watching is not an alert.
- Shutdown: `SERVER_STOPPING` → drain, close.

### 1.2 Mixins — own config `lss-trace.mixins.json` with `"required": false`

**The v1 blocker (review F-1):** `@Accessor` has no `require` member and throws
`InvalidAccessorException` unconditionally on a missing field; under `lss.mixins.json`'s
`"required": true` that is a hard crash at class transform **for every server,
including tracer-disabled ones**, on any MC bump that renames `firstGoodX` — and plain
reflection cannot substitute (production fields are intermediary-named; the same rule
that bans `Class.forName` on MC types). Fix: the trace mixins live in their own
`lss-trace.mixins.json` with `"required": false` (registered alongside the main config
in `fabric.mod.json`), so a failed accessor downgrades to a mixin WARN; the
`((AccessorServerGamePacketListener) listener)` cast then throws `ClassCastException`
into the tracer's catch-all — the `FabricChannelPressure` degrade shape. A Tier 1
reflective existence pin for every accessed field (§3) turns the same drift into a red
test at build time.

**`MovementRejectHook`** (`@Mixin(ServerGamePacketListenerImpl.class)`) — **four**
injects into `handleMovePlayer`, every body delegating to
`dev.vox.lss.trace.MoveDesyncHooks` (the `ChunkSaveDataHook` pattern), all `require=0`,
callbacks `lss$`-prefixed per repo convention:

| inject | target | purpose |
|---|---|---|
| `lss$onMoveHead` | `@At("HEAD")` | stores pre-move `player.getX/Y/Z()` into `@Unique lss$startX/Y/Z` (review F-5: at HEAD the entity has not moved — `resetPosition()` reseeds `firstGood*` only — so these equal the method's `startX/Y/Z` locals exactly, making claimed-target recomputation exact for `Rot`/`StatusOnly` packets whose `packet.get*(default)` falls back to the *current* position); updates the per-player move-packet gap clock (review U-5: `move_gap_ms` / `move_gap_max_5s_ms` — the only server-side client-stall measurement, and it works identically in the LOD-off control arms) |
| `lss$onMovedTooQuickly` | the `moved too quickly!` warn INVOKE — targeted by **descriptor alone**, `warn(String, Object[])`, `remap = false` on the slf4j `@At` (reviews F-6/F-7: the two warns have distinct descriptors, so no ordinal is needed) | reconstructs the check's inputs exactly: cumulative deltas from `firstGood*`, `expectedDist` from `getDeltaMovement()`, and **both** `delta_packets` (raw burst size) and `delta_packets_used` (the post-clamp value the check applied — review F-8: a >5 burst is *penalized* to 1, and an analyst given only the raw count computes the wrong threshold) |
| `lss$onMovedWrongly` | the `moved wrongly!` warn INVOKE — descriptor `warn(String, Object)`, `remap = false` | at this point `player.getX/Y/Z()` IS the post-`move()` simulated stop (bytecode-verified); stores the packet reference into `@Unique lss$wronglyPacket` (review F-3: a packet-identity token, not a boolean — no HEAD clear needed, self-expires with the packet, and a partially-applied mixin degrades to `logged_wrongly:false` instead of latching true) |
| `lss$onMoveRejected` | the rejection `teleport(DDDFF)V` INVOKE, anchored **semantically** by a `@Slice(from = the wrongly-warn INVOKE)` + ordinal 0 within the slice — "the first teleport after the `moved wrongly` warn site" (review F-6: a bare ordinal silently retargets if vanilla reorders; the slice encodes what the code means). The teleport `@At` keeps default `remap = true` — it is a target-class method | fires for BOTH the logged rejection and the **silent** `isEntityCollidingWithAnythingNew` rejection (zero observability today); `logged_wrongly = (lss$wronglyPacket == packet)` |

`clampHorizontal`/`clampVertical` are private statics (`Mth.clamp` ±3.0E7/±2.0E7) and
are replicated in the hook bodies. No `@Local`/LocalCapture anywhere (MixinExtras
`@Local` is on the classpath as a fallback if the HEAD capture ever proves
insufficient — review F-5 — but v2 does not need it). `lss$wronglyPacket` retains a
strong reference to the last rejected packet for the listener's lifetime — a few
hundred bytes that die with the connection; deliberate (Fable F2-11).

**Accessors** — `AccessorServerGamePacketListener` (`firstGoodX/Y/Z`, `lastGoodX/Y/Z`,
`receivedMovePacketCount`, `knownMovePacketCount`, `awaitingPositionFromClient`), in
the non-required config. **`AccessorPlayerChunkSender` is dropped** (review F-13):
`PlayerChunkSender.isPending(long)` and `chunkSender` are public, which covers the
vanilla rung's one keystone question with zero added crash surface; the rung's
remaining private gauges are low-value on a deployment target where the rung is inert.

### 1.3 Chunk-delivery state — two-rung resolver, `dev.vox.lss.trace.ChunkSendState`

Rung order resolved once per JVM (lazy holder, `MoonriseReadCompat.build()` seam
pattern — verified as the right precedent shape, F-10):

1. **Moonrise rung** (the live server): `Class.forName` on
   `ca.spottedleaf...ChunkSystemServerPlayer` (non-MC package — precedent-consistent),
   MethodHandle `moonrise$getChunkLoader()` — **null-checked into "none" per call**
   (F-10: it returns null in the dimension-change window) — then the public
   `getSentChunksRaw()` plus reflective `Field` reads of `sendQueue`,
   `chunkTicketStage`, `lastSendDistance`, `lastChunkX/Z`. All reads are server-thread
   (Moonrise asserts `TickThread` on every mutator — thread-safety verified stronger
   than v1 claimed). Captured per queried chunk / row (fields per review U-1/U-2):
   - `sent_mask_5x5`: 25-bit membership bitmask of `sentChunks` around the anchor +
     `anchor:[cx,cz]` — a **mask, not a count**, so the 1 Hz flight rows retro-answer
     "when was this specific chunk first sent" at 1 s resolution (the send-time bound
     Moonrise itself cannot give — it does not timestamp sends);
   - `sent_r1`: 9-bit mask (a diagonal-chunk collision is otherwise unattributable);
   - `stage`: the queried chunk's `chunkTicketStage` byte (0 NONE / 1 LOADING /
     2 LOADED / 3 GENERATING / 4 GENERATED / 5 TICK) — distinguishes "**the server
     hadn't loaded it either**" (falsifies the wire hypothesis for that event; wire
     mitigations would do nothing) from "server had it and hadn't sent it";
   - `send_radius` (`lastSendDistance`) + `loader_center` (`lastChunkX/Z`) — `sent:
     false` is evidence only *inside* the radius, and a lagging loader center is its
     own starvation mechanism;
   - `send_queue` size + `send_head_stage`: the stage of `sendQueue.firstLong()` —
     Moonrise's drain `break`s at a head whose neighbors aren't generated, so a large
     queue with a not-ready head is **server-side head-of-line blocking** (a Chunky-
     pregen-compatible mechanism the investigation never listed — review U-2).
2. **Vanilla rung** (E6 local rig; unreachable on the live server): `chunkSender`
   public field + public `isPending(long)`. Field is named **`not_pending`** — NOT
   `sent` (review U-12): vanilla removes from `pendingChunks` at *collection* time and
   never-tracked chunks are also "not pending", so this is a strictly weaker predicate
   that cannot support the confirm criterion. Vanilla-rung rows are context, not
   keystone evidence, and the differing field name makes cross-rung aggregation a
   visible type error rather than silent nonsense.
3. **No signal** → `rung:"none"`, fields absent, warn once.

### 1.4 Row envelope and LSS context

Envelope (every row): `v` (schema int), `bootId`, `wallMs`, `tick`, player id/name/dim,
**`obuf`** (fresh read at capture time via the channel-pressure probe — widened via
**the same public-factory refactor the yield plan's §1.1 probe-snapshot extension
specifies; whichever PR lands first implements the combined shape** (Fable F2-8) — v1
had it package-private, a compile blocker — needs no LSS registration, so control
flights carry it),
`latency_ms` (`connection.latency()` — free, but 15 s-smoothed: a link-quality
baseline that sizes the sent-staleness allowance, NOT a stall detector — review U-1),
`mspt`, `online`, `dropped`.

`lss` block (null when unregistered/disabled — and that null is itself the A/B label):
`registered`, `since_s`, `caps`, `proto`, `dialect` (v18/v16 rung if any — review
U-16: "modded v19 client" is central to the census and must be machine-readable),
`send_queue`, `bw_window`, and `yielded` **when the transport-yield feature exists**
(phrased as a dependency; it is unimplemented today).

**Boot row** (review U-7 — without it E4's bandwidth sweep is unanalyzable):
`{"type":"boot"}` carrying schema version, `tz_offset_min` (log correlation needs the
server's TZ), LSS + MC versions, moonrise/c2me/chunky presence, the resolved rung, and
a snapshot of load-bearing config (`bytesPerSecondLimitPerPlayer`/`Global`,
`lodDistanceChunks`, `lodStore`, `outboundBufferCeilingKB`,
`lodYieldsToVanillaTransport` if present). Analysis keys on `v`.

### 1.5 Event rows and flight telemetry

Event rows (`too_quickly` / `wrongly` / `rejected`) — schema **split by type**
(review U-14: `too_quickly` returns before `move()` ever runs, so `simulated`/
`residual` are ABSENT there, not null/zero):

- all: `origin` (`lastGood*` for wrongly/rejected, `firstGood*` for too_quickly —
  review U-4: v1 captured it and never emitted it, leaving the swept segment
  unreconstructable), `claimed`, `fall_flying`, `speed`, `awaiting_tp`,
  `move_gap_ms`, `move_gap_max_5s_ms`, send-state block(s);
- `too_quickly`: `delta_packets`, `delta_packets_used`, `expected_dist`;
- `wrongly`/`rejected`: `simulated` (the collision point — `move()` sweeps, so the
  stop IS on the swept path), `residual`, `residual_h`, `restored` (the teleport
  destination = the pre-move position from `lss$startX/Y/Z` — the player-felt snap
  distance is `claimed − restored`, not `claimed − simulated`; reviews F-9/U-4),
  `logged_wrongly`, `entity_collide` (`!level.getEntityCollisions(player,
  sweptAABB).isEmpty()` — a boat/shulker produces the same residual as terrain and is
  otherwise invisible; review U-3), `stop_block` (the block state id at the collision
  face — names what the server thinks stopped them), and send-state for BOTH the
  simulated-stop chunk and the claimed chunk.

Flight telemetry (review U-11 — v1's 1 Hz/fast-players-only missed the population and
the timescale):

- **Arm condition**: `isFallFlying()` OR speed > 6 blocks/s OR **the player's LSS send
  queue is non-empty** (actively streaming LOD — the actual hypothesis population;
  walking players during the backfill flood were invisible to v1's trigger) OR an
  event in the last 30 s.
- **1 Hz `flight` rows** for armed players: position, speed, `sent_mask_5x5` +
  loader fields, `obuf`, `latency_ms`, `lss` block, `mspt`, and the level's loaded-
  chunk count (moves visibly under Chunky pregen — the "was pregen running" signal,
  review U-16).
- **5 Hz in-memory ring** (40 samples @ every 4 ticks, primitive fields only, no JSON
  off the event path): flushed as `flight_ring` rows ahead of any event row — 8 s of
  5 Hz trailing context, which 1 Hz structurally cannot give for a sub-second
  collapse, including for a first event with no prior 1 Hz rows. **Per-sample fields
  explicitly include `sent_mask_5x5` + anchor and the loader-center fields**
  (Fable F2-4: 25 hash-set membership tests at 5 Hz are negligible, and without the
  mask in the ring the §1.6 REFUTE arm is structurally unsatisfiable at flight speed —
  an elytra player moves ~2 chunks/s, so the 1 Hz rows' anchor trails 1.5–2 chunks
  behind the event position and the collision chunk exits the ±2 mask exactly in the
  regime the tracer exists for; at 5 Hz it stays comfortably inside for the whole
  latency+500 ms lookback), plus position, speed, `obuf`, and the gap clock.
- **Per-player state cleanup** (Fable F2-9): gap clocks, rings, and arm timers are
  cleared on disconnect via the lifecycle events the tracer already hangs off —
  trivial rates today, but a weeks-long campaign must not leak by construction.

### 1.6 The success criterion — three-valued, honestly (reviews U-1/U-2/F-4)

v1's binary confirm/refute licensed wrong conclusions in both directions. v2:

- **CONFIRMS** the missing-terrain clip: `sent:false` at the collision chunk AND
  inside `send_radius` AND `stage >= 2` (LOADED or beyond — the server had it and
  hadn't sent it). Note `stage <= 1` is its own decisive result: the *server* didn't
  have the terrain — a server-side loading/generation shortfall, not a wire problem.
- **REFUTES** it for that event: `sent:true` AND the preceding **`flight_ring` rows**
  (the 5 Hz ring flushed ahead of every event — the 1 Hz rows cannot carry this
  evidence at flight speed, Fable F2-4) show the chunk already sent ≥ (`latency_ms` +
  500 ms) before the event AND `obuf` small at event time — then the client had ample
  time to receive and apply, and attention moves to the residual vector
  (`entity_collide`, `stop_block`).
- **INDETERMINATE**: `sent:true` without that prior-row evidence. On Moonrise,
  `sentChunks` stamps at the instant of the Netty write and the sender is
  effectively unthrottled, so `sent:true` is expected to be common and **a deep
  `obuf` at event time with `sent:true` supports the same conclusion by the transport
  measurement** (packets written but queued behind LOD bytes). The server cannot
  prove the client *applied* a chunk — Moonrise has no chunk ACK at all — so the
  sent-but-not-applied direction is closable only client-side. **One indeterminate
  event is the trigger to build the client-side hole map** (investigation §5.2),
  which v1 deferred with no re-entry condition.

Also stated plainly (review U-15): a `wrongly` row without a paired `rejected` row in
the same tick was **accepted with a warning** — no rubber-band. Player-felt
rubber-bands = `too_quickly` + `rejected`. The investigation's census conflated these.

## 2. What the tracer deliberately does NOT do

- No config key, no `/lsslod` verb. **One diag line, present only while the tracer is
  active** (review U-10 — restoring investigation §5.1.3's promised counter):
  `MoveTrace: rung=moonrise rows=N drops=0 tooquick=A wrongly=B rejected=C silent=D`.
  This is simultaneously the post-deploy verification (`rung=moonrise` over RCON in
  one call, instead of discovering `rung:none` from a week of unlabeled events) and
  the on-demand silent-rejection rate the whole investigation was missing. Tracer
  off → the line is absent; the diag surface is unchanged.
- No client-side changes (the §5.2 hole map is the *named follow-up*, triggered by an
  indeterminate verdict).
- No packet capture beyond the HEAD gap-clock; no per-move rows.
- No soak/schema integration; the harness never sets the property.
- No vehicle-path coverage (§ preamble).

## 3. Tests

- **Tier 1 — `MoveDesyncTracerTest`**: constructor-injected sink/enablement; writer
  lifecycle, overflow drops counted, rotation at the cap + warn, JSON goldens per row
  type (absent-vs-null discipline pinned), disabled instance writes nothing.
- **Tier 1 — pure-core split** (review F-12): the hook bodies are a thin MC-typed
  capture layer over an MC-free core (`MoveEventMath` — residual/clamp/gap math,
  `MoveRow` — rendering); Tier 1 drives the core (a `ServerPlayer` cannot be
  constructed under fabric-loader-junit).
- **Tier 1 — `MoonriseSendStateCompatTest`**: resolution ladder against
  real-package-name stubs under `fabric/src/test/java/ca/spottedleaf/` (precedent:
  `MoonriseReadCompatTest`): resolve, mod-absent → vanilla rung, shape drift →
  no-signal + one warn, **null chunk-loader → "none" per call** (the dim-change
  window).
- **Tier 1 — field-existence pins** (review F-1): reflective `getDeclaredField` for
  every accessor target and every Moonrise `Field` read, against the real named
  classes (the test JVM runs in the named namespace — `LanHookContractTest` proves MC
  classes load there; note that test is *reflective*, not a bytecode scan — F-11).
- **Tier 1 — `MoveTraceHookContractTest`**: source-regex pins on the mixin
  (targets/`require=0`/delegation-only/`remap=false` on slf4j `@At`s/the
  `lss-trace.mixins.json` listing + its `"required": false`), PLUS an **ASM
  `ClassReader`/`ClassNode` scan** of the real `handleMovePlayer` (ASM is already on
  the test runtime classpath via fabric-loader; the `FoliaWiringContractTest`
  constant-pool idiom cannot count per-method INVOKEs — F-11) asserting: exactly one
  `warn(String,Object[])` and one `warn(String,Object)` INVOKE, exactly three
  `teleport(DDDFF)V` INVOKEs, and the **relative order** (the wrongly-warn offset
  lies between teleports #2 and #3) — the slice-anchor's tripwire.
- **Tier 2 — `MoveTraceGameTests`** (new class; MUST be listed in the
  `fabric-gametest` entrypoint — `GameTestEntrypointContractTest` enforces): via the
  test seam (`enableForTest(inMemorySink)` — the gametest JVM does not set the
  property). **The stock mock player cannot fire the `wrongly` warn — it is
  hard-coded CREATIVE** (review F-2: `GameTestHelper$3` overrides `gameMode()` to
  CREATIVE and the warn is gated `!isCreative()`). The test hand-rolls a survival
  player replicating the factory's steps (`new ServerPlayer(...)`, `new
  Connection(SERVERBOUND)`, `EmbeddedChannel`, `CommonListenerCookie.createInitial`,
  `placeNewPlayer`) — `hasClientLoaded()` is true out of the box and gametests run on
  the server thread, so `connection.handleMovePlayer(...)` is directly drivable.
  **One fresh player per assertion** (a rejection latches `awaitingPositionFromClient`
  and every later move early-returns; the mock connection never ticks, so
  `resetPosition()` reseeds per call and `delta_packets` climbs monotonically —
  budgeted, not fought). Assertions: (a) an 18.5-block claim → `too_quickly` row with
  both packet-count fields; (b) a claim ending inside placed blocks → `rejected` row
  with `logged_wrongly` matching whether the warn fired; (c) the `wrongly` row via
  the survival player. Documented fallback if the hand-rolled player fights back:
  keep (a)+(b) on the creative mock — both reachable — and drop only (c).
- **Tier 1 — diag golden** (Fable F2-10): the conditional `MoveTrace:` line in
  `DiagnosticsFormatter` gets golden coverage — present-when-active,
  absent-when-off — following the `V18Compat` conditional-slot precedent.
- **`scripts/check_move_trace.py`** (review U-14): stdlib validator, `--validate` +
  `--selftest`, enforcing required keys per row type, `v` compatibility, per-boot
  monotonic `wallMs`, monotone `dropped`, rung/field consistency (`sent_mask_5x5`
  only on `rung:"moonrise"`). Its selftest fixtures ARE the Tier 1 goldens (shared
  file under `scripts/testdata/`) so writer and reader cannot drift — the
  `check_soak.py --selftest` culture applied to this file.

## 4. Deployment & operation (Modrinth server)

1. Build from a branch **without the transport yield armed** (review U-8): the yield
   suppresses exactly the obuf signal that decides hypothesis (c); the tracer must
   collect the baseline first. (The yield plan v2 is default-off, which satisfies
   this — the constraint is recorded so a combined build doesn't flip it.)
2. SFTP the jar; `touch` the marker file over SFTP (or set the JVM flag if the panel
   allows); Restart via archon.
3. **Verify, in order**: `Move desync tracer ACTIVE` in `latest.log` (absent = the
   gate didn't take — do not wait a week to learn it from an empty file);
   `rcon.py "lsslod diag"` shows `MoveTrace: rung=moonrise` (`rung=none` = the
   reflective bridge failed and every event will arrive unlabeled); after the first
   flight, the JSONL is visible over SFTP.
4. Collect by SFTP; run `check_move_trace.py --validate` before analysis; delete
   after each pass. Rotation bounds disk (§1.1).
5. Events feed the investigation's E-series; the §1.6 verdict decides the next step
   (notably: indeterminate → build §5.2's client hole map). **Analysis must partition
   rows by the boot row's `lodYieldsToVanillaTransport` snapshot** (Fable cross-plan):
   an armed-yield collection period shifts the envelope `obuf` distribution by design,
   and mixing armed and unarmed boots corrupts the hypothesis-(c) baseline.

## 5. Effort (v2)

| piece | estimate |
|---|---|
| Tracer + hooks + pure core + schema | ~half a day |
| Mixins (own config) + accessors + contract tests + field pins | ~half a day |
| Moonrise rung (incl. stage/radius/center/head fields) + tests | ~half a day |
| Tier 2 (hand-rolled survival player) | ~half a day, the stated fallback bounds it |
| `check_move_trace.py` + shared fixtures | ~2 hours |
| Deploy + verification pass | ~an hour |

No wire changes, no protocol bump, no config-file surface, no Paper changes.

## 6. What the tracer can and cannot decide (kept honest)

- Hypothesis (a) — collision chunk not client-delivered: decidable per §1.6, with the
  indeterminate class explicitly named and its escalation path defined.
- Hypothesis (b) — stalls LOD-caused: `move_gap_*` measures the stall server-side on
  every row including control arms (the E2 A/B then attributes it); flat
  `latency_ms` alongside large move-gaps points at main-thread stalls over link
  degradation (the keep-alive reply is written off the client main thread — U-5).
- Hypothesis (c) — wire head-of-line: `obuf` in the envelope on all arms.
- Out of reach server-side, by construction: client *apply* confirmation (Moonrise
  has no chunk ACK). That is §5.2's job, triggered as above.

## 7. Review round record (2026-08-06, two Opus reviewers: feasibility + utility)

| finding | disposition |
|---|---|
| F-1 accessors can't soft-fail; required config would crash tracer-disabled servers on MC drift | v2 §1.2: own `lss-trace.mixins.json` `"required": false` + cast-degrade + Tier 1 field pins |
| F-2 gametest mock player is hard-coded CREATIVE — `wrongly` unreachable | §3 Tier 2: hand-rolled survival player, fresh per assertion, documented fallback |
| F-3 `loggedWrongly` HEAD-clear could latch true if partially applied | §1.2 packet-identity token, degrades false |
| F-4/U-6 obuf inside `lss` block = no control-arm baseline; keystone saturated on Moonrise | §1.4 envelope `obuf` (public probe access); §1.6 obuf as the Moonrise discriminator |
| F-5 claimed-target recompute diverges for non-`Pos` packets post-move | §1.2 HEAD capture of pre-move position; clamp replication; `@Local` noted as fallback |
| F-6/F-7 warn descriptors distinct (no ordinals needed); slice-anchor the rejection teleport; `remap=false` on slf4j `@At`s; count-scan can't catch reordering | §1.2 targeting scheme; §3 ASM scan asserts counts AND relative order |
| F-8 raw `delta_packets` is not the number the check used | §1.5 emits both raw + used |
| F-9/U-4 rubber-band destination + origin missing from schema | §1.5 `origin` + `restored` |
| F-10 null chunk-loader in dim-change window; thread-safety verified stronger | §1.3 null → "none"; noted |
| F-11 ASM available; constant-pool idiom insufficient; `LanHookContractTest` mis-described | §3 corrected |
| F-12 hook bodies untestable without a layer split | §3 pure core |
| F-13 drop `AccessorPlayerChunkSender` (public `isPending`); vehicle path; package naming | §1.2 dropped; preamble; §1.1 |
| U-1 `sent:true` cannot refute (no ACK anywhere on Moonrise); "no recent stall" wasn't captured | §1.6 three-valued verdict; §1.3 masks; `latency_ms`; move-gap fields |
| U-2 `sent:false` can false-confirm (server-unloaded / out-of-radius / head-of-line) | §1.3 `stage`/`send_radius`/`loader_center`/`send_head_stage`; §1.6 conjuncts |
| U-3 entity collisions indistinguishable from terrain | §1.5 `entity_collide` + `stop_block` |
| U-5 no server-side stall measurement existed | §1.2 HEAD gap clock; §6 |
| U-7 no boot row / schema version — E4 unanalyzable | §1.4 boot row + `v` |
| U-8 yield deploy would destroy the (c) baseline | §4.1 sequencing constraint |
| U-9 activation single-point-of-failure on an unverified panel feature | marker-file fallback (not a config key) |
| U-10 no post-deploy rung verification; §5.1.3's diag counter dropped | §2 active-only diag line |
| U-11 1 Hz misses sub-second collapse; speed trigger misses walking backfill players | §1.5 LSS-streaming arm + 5 Hz ring |
| U-12 vanilla-rung `sent` is a different quantity | §1.3 `not_pending` rename |
| U-13 silent stop at cap mid-campaign | §1.1 rotate + warn |
| U-14 nothing pins the reader; `too_quickly` carried meaningless fields | §3 validator + shared fixtures; §1.5 per-type schema |
| U-15 `wrongly` ≠ rubber-band | §1.6 stated; census corrected |
| U-16 caps/proto/dialect, `awaiting_tp`, loaded-chunk count, §5.2 re-entry trigger | §1.4/§1.5/§1.6 |

### 7.1 Review round record — round 2 (one Fable reviewer, full v2 pass, both plans)

Verdict: "ready after fixes — all round-1 dispositions faithfully implemented; every
bytecode-facing claim checked verifies against the decompiled 26.2 / Moonrise / C2ME
ground truth." Fixes applied in v2.1:

| finding | disposition |
|---|---|
| F2-4 MAJOR: REFUTE retro-dating unsatisfiable at flight speed — the 1 Hz rows' ±2 mask anchors 1.5–2 chunks behind the event; every `sent:true` would collapse to INDETERMINATE | §1.5: `sent_mask_5x5` + anchor + loader-center added to the 5 Hz ring's per-sample fields; §1.6 REFUTE keyed on `flight_ring` rows |
| F2-8: probe widening must be the same refactor as the yield plan's snapshot extension | §1.4 combined-shape reference; one owner, whichever PR lands first |
| F2-9: no per-player tracer-state cleanup | §1.5 disconnect sweep |
| F2-10: conditional diag line lacked golden coverage | §3 formatter golden (V18Compat precedent) |
| F2-11: marker-read instant unpinned; `lss$wronglyPacket` strong reference unstated | §1.1 `SERVER_STARTING`; §1.2 deliberate-retention note |
| F2 cross-plan: armed-yield collection shifts the `obuf` distribution | §4.5 partition-by-boot-flag requirement |
