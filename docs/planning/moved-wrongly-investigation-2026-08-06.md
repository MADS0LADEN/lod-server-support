# "moved wrongly!" on the live server — investigation + instrumentation plan (2026-08-06)

Fresh analysis, from scratch, per user request — prior elytra-wall conclusions deliberately
set aside. The 2026-08-01 doc is cited only for raw measurements, never conclusions.

Evidence base:
- The **complete** live-server log archive (46 files, 2026-07-21 → 2026-08-06 — this covers
  the server's whole lifetime, nothing rotated away).
- Live config + `/lsslod diag` over RCON, deployed jar identity (v0.9.1+26.2).
- Decompiled MC 26.2 (mapped `minecraft-merged-deobf-26.2.jar`, Vineflower):
  `ServerGamePacketListenerImpl`, `LocalPlayer`, `ClientLevel`, `BlockCollisions`,
  `PlayerChunkSender`, `GameRules`.

## 1. Event census (all of it — the server's entire log history)

79 joins total. Movement-check warnings:

| warning | count | players | days |
|---|---|---|---|
| `moved wrongly!` | **3** | Roihesse611, Voximus_Maximus, batidak | 08-04, 08-05, 08-06 |
| `moved too quickly!` | **51** | Voximus_Maximus (24), AnonyBrave (25), GameSuchtYT (2) | 07-29, 08-01 (43!), 08-03 |
| `Can't keep up` (server tick behind) | **0** | — | — |

The three `moved wrongly` events in full context:

| player | event time | after LSS registration | others online | what followed |
|---|---|---|---|---|
| Roihesse611 | 08-04 12:34:40 | 3 m 12 s | **nobody** | `lost connection: Disconnected` **2 s later** |
| Voximus_Maximus | 08-05 17:32:38 | 1 m 54 s | **nobody** (Chunky nether pregen running at ~110 cps) | stayed 7 more min |
| batidak | 08-06 13:02:00 | 1 m 28 s | **nobody** | `lost connection: Disconnected` **13 s later** |

The `moved too quickly` storms likewise start moments after join: Voximus 08-01 first trip
**19 s** after joining; AnonyBrave first trip **36 s** after joining, then ~25 trips over
5 minutes until they quit. Both were alone on the server.

Load-bearing observations:

1. **Every event happened with exactly one player online.** Multi-player bandwidth
   contention and global-cap effects are ruled out. This is a single-client phenomenon.
2. **Zero `Can't keep up` in 2.5 weeks.** The server tick loop was never meaningfully
   behind — sustained server overload is effectively ruled out as the driver. (Fine-grained
   MSPT spikes below the 2 s-behind threshold remain possible; the tracer below samples
   MSPT to close that residual.)
3. **Every affected session is a modded v19 (0.9.x) client** (`capabilities=3` handshakes)
   in its **first ~3 minutes** — i.e. during the LOD backfill flood, when the server streams
   at the full per-player cap and the client decodes/ingests hardest.
4. **The retuned caps did not fix it**: batidak's event is from *today*, on v0.9.1 with the
   15 MB/s per-player / 60 MB/s global caps.
5. Two of three `moved wrongly` events were followed by a disconnect within seconds
   (`Disconnected` = the TCP connection closed — covers both a deliberate quit and a client
   crash; a hung-but-alive client would show `Timed out`, of which there is exactly 1 all
   month).
6. The `moved too quickly` deltas are diagnostic: 17–19 blocks *per packet*, i.e. right at
   the elytra threshold √300 ≈ 17.3 (see §2). At ~1.7 blocks/tick elytra speed that is
   **half a second or more of flight claimed in one movement step** — the signature of the
   client's tick loop stalling and catching up, or of a network burst.
7. Footnote (unchased): Roihesse611's and batidak's *first* sessions of the day each show
   **no LSS handshake at all** and lasted under 2 minutes; both rejoined and handshook
   normally. Possibly a client-side first-join init race in our client mod — worth an
   eventual look, unrelated to movement.

## 2. What the two warnings actually mean on 26.2 (decompiled, not folklore)

From `ServerGamePacketListenerImpl.handleMovePlayer` (26.2 mojmap):

**`moved too quickly!`** — cumulative distance this tick vs an allowance of
`300 (elytra) / 100 (normal)` **per move packet**, with a burst clamp: if more than **5**
move packets arrive in one server tick, the packet count is **clamped to 1**, so a burst of
queued packets gets a single packet's allowance. Consequence: *any* client stall (or network
stall) > ~0.5 s during fast elytra flight trips it almost by construction — the queued
packets arrive as a burst, the clamp strips their allowance, and the cumulative delta
(~18 blocks) exceeds √300. The server teleports the player back (rubber-band). This check —
and only this check — is gamerule-gated: `player_movement_check`, `elytra_movement_check`.

**`moved wrongly!`** — the server replays the client's claimed delta through real collision
(`player.move(MoverType.PLAYER, …)`) and compares. The vertical residual is always forgiven
(the long-standing `yDist > -0.5 || yDist < 0.5` tautology zeroes it), so on 26.2 this
warning fires **only for a horizontal residual > 0.25 blocks** — i.e. *the client claims it
flew through space the server considers solid*. Sleeping/creative/spectator/dimension-change
and the **post-firework-impulse grace** are exempt — so these events are not the rocket
boost itself. Not gamerule-gated.

**The silent third path** — even when the distance residual passes, a move that ends
overlapping *new* server-side collision (`isEntityCollidingWithAnythingNew`) is rejected
and teleported back **with no log line at all**. The logs therefore *undercount*
player-felt rubber-banding; "a lot of players" complaining is consistent with 3 logged
events + 51 too-quickly events + an unknown number of silent rejections.

### 2.1 The 26.2 regression that makes this possible

**26.2 removed the client's unloaded-chunk movement freeze.** On earlier lines,
`LocalPlayer.tick()` refused to run physics unless the chunk at the player's own position
was loaded (`hasChunkAt`) — a player who outran chunk delivery froze mid-air (the "wall").
On 26.2, `LocalPlayer.tick()` gates only on `connection.hasClientLoaded()` (a one-time
login/respawn flag), `ClientLevel` has no per-chunk entity-tick gate, and `BlockCollisions`
**skips unloaded chunks entirely** (null chunk → no collision). A 26.2 client that outruns
vanilla chunk delivery keeps flying, with **no collision**, through terrain it has never
received.

The server, meanwhile, *does* have those chunks loaded (they're inside the player's ticket
radius; server-side loading is fast — Moonrise, avg 0.5 ms reads, 110+ cps under Chunky).
So the moment the flight path intersects server-solid terrain the client hasn't received:
horizontal residual > 0.25 → **`moved wrongly!`** → teleport-back. If the client is only
*near* terrain, the silent `collidingWithAnythingNew` rejection fires instead.

### 2.2 The feedback loop that starves the client of terrain

Vanilla chunk sending (`PlayerChunkSender`) is throttled by the **client's own ACKs**:
`desiredChunksPerTick` comes from the client's `ChunkBatchReceived` reply (its self-measured
processing rate, clamped 0.01…64, start 9), and at most 10 unacknowledged batches
(1 until the first ACK) may be in flight. A client whose main thread is stalling —
GC pauses, decode/apply work — ACKs late and reports a collapsed rate, so **the server cuts
vanilla chunk delivery to a crawl, potentially 0.01 chunks/tick, while the player keeps
flying at ~33 blocks/s.** Client-side stalls don't just cause rubber-bands directly; they
actively starve the client of the terrain it is about to fly into.

**Correction (2026-08-06, same day, verified against Moonrise-Fabric 1.1.0 bytecode):
the ACK feedback loop above applies only to vanilla-chunk-system servers — NOT the live
server.** Moonrise's `RegionizedPlayerChunkLoader$PlayerChunkLoaderData` schedules chunk
sends itself (own priority `sendQueue` + `StaggeredRateLimiter`, `MAX_RATE` 10000/tick)
and calls the *static* `PlayerChunkSender.sendChunk` per chunk — vanilla's batch/ACK flow
control (`sendNextChunks`, `desiredChunksPerTick`, `unacknowledgedBatches`) never runs,
and the per-player `chunkSender` instance state is inert. On Moonrise, a stalling client
cannot slow the sender; the backlog lands in the **Netty outbound buffer / TCP window**
instead — which shifts the starvation mechanism for the live server toward transport
head-of-line (LOD bytes queued ahead of chunk packets in the shared obuf) and client-side
apply lag, and *strengthens* the case for the M1 transport-yield mitigation and for obuf
data in every tracer row. Consequence for instrumentation: the tracer must read
**Moonrise's** `sentChunks`/`sendQueue` (public `getSentChunksRaw()`, interface-injected
`moonrise$getChunkLoader()`) on Moonrise servers and vanilla's `PlayerChunkSender` only as
the fallback rung — reading vanilla state on the live server would produce confident
nonsense. Plans: `move-desync-tracer-plan.md`, `vanilla-first-lod-yield-plan.md`.

## 3. Causal model (primary hypothesis)

```
LOD stream at the per-player cap (15 MB/s raw today; 21–25 MB/s in the 08-01 incident band)
        │  client must decompress (zstd) + decode + ingest into Voxy
        ▼
client main-thread stalls / GC pauses           ← directly evidenced by the √300-edge
        │                                          burst deltas in `moved too quickly`
        ├───────────────► burst arrivals + the >5-packet clamp → `moved too quickly!`
        ▼                                          → teleport-back rubber-bands
slow ChunkBatchReceived ACKs, low desiredChunksPerTick
        ▼
vanilla chunk delivery collapses (§2.2) while flight continues at full speed
        ▼
client crosses into terrain it never received — 26.2 no longer freezes it (§2.1)
        ▼
client physics: no collision. server physics: mountain.
        ▼
horizontal residual > 0.25 → `moved wrongly!` (+ silent rejections nearby)
        ▼
rubber-banding player quits (2 of 3 disconnected within seconds) — or the client
was already dying (OOM/crash) and the bad movement was its death throes
```

What the evidence already supports vs. what still needs proof:

- **Supported**: the 26.2 mechanics (decompiled, §2); events cluster in the backfill-flood
  window on modded clients with nobody else online; server tick and disk exonerated; client
  stalls of ≥0.5 s provably occurred (burst deltas); the prior doc's raw measurements put
  the incident at 21–25 MB/s *raw* sustained to one client (a client-processing-bound
  signature, ~3–4 MB/s on the wire after zstd).
- **Not yet proven** (instrumentation targets): (a) that the collision point of a
  `moved wrongly` event lies in a chunk the client hadn't received/ACKed — the keystone
  claim; (b) that the client stalls are LOD-caused rather than the player's machine being
  generally weak (A/B settles this); (c) whether the wire share (head-of-line behind LOD
  bytes) contributes materially vs. pure client CPU/GC (obuf data settles this).

## 4. Alternatives kept open (and how each gets decided)

| alternative | current standing | decided by |
|---|---|---|
| Vanilla-inherent 26.2 problem, LSS only amplifies | plausible — any slow chunk delivery + elytra now clips terrain | E2c control flight (clean vanilla client, LOD server off) |
| Fly-hack / cheat clients | unlikely (timing tied to backfill phase, all modded handshakes) | tracer records speed/route per event |
| Weak client hardware, LOD irrelevant | possible for individual players | E2b (`receiveServerLods=false` same client, same route) |
| Client crash garbage (dying client sends junk) | consistent with 2-of-3 instant disconnects | tracer event-vs-disconnect timestamps |
| Server MSPT spikes below the warn threshold | mostly excluded (0 warnings, 1-player load) | tracer samples MSPT at event time |
| Chunky pregen load | present in exactly 1 of 3 events | tracer + no-Chunky periods |

## 5. Instrumentation plan — the "movement desync tracer"

The decisive property: **server-side instrumentation sees every client, modded or not,
with no client cooperation.** Ship it in LSS (config-gated), deploy to the Modrinth server,
and let organic traffic generate labeled events.

### 5.1 Server side (LSS, new; works for all players)

Config: `moveDesyncTrace` (default **true** — it is one JSONL line per event plus a 1 Hz
per-flying-player sample; negligible), writing `logs/lss-move-trace.jsonl` (size-capped ring).

1. **Event rows** — mixin `@Inject` at the three rejection points in
   `ServerGamePacketListenerImpl.handleMovePlayer` (`moved wrongly` warn, `moved too
   quickly` warn, and the **silent** `isEntityCollidingWithAnythingNew` teleport-back,
   which today has no observability at all). Capture:
   - claimed position/delta, simulated residual vector (the server already computed all of
     it — vanilla just throws the numbers away and logs only the player name),
   - `deltaPackets` (burst size), `isFallFlying`, server-side speed, post-impulse state,
   - **chunk-delivery state at the collision point** (accessor mixin into
     `PlayerChunkSender`): `pendingChunks` size, whether the collision chunk is
     pending-to-send, `unacknowledgedBatches`, `desiredChunksPerTick` — this single row
     proves or refutes the keystone claim (§3a),
   - LSS per-player state: send-queue depth, bandwidth-window bytes this second, obuf
     snapshot (`FabricChannelPressure` already reads `bytesBeforeUnwritable` — plumbing
     exists), columns in flight, seconds since LSS registration,
   - server MSPT window average.
2. **Flight telemetry rows** — 1 Hz per player, only while `isFallFlying` or speed > ~10
   blocks/s: position, speed, `PlayerChunkSender` state, obuf, LSS send rate. Gives every
   event a trailing context window (was chunk delivery already collapsing? for how long?).
3. **Silent-rejection counter** in `/lsslod diag` — measures the real player-facing
   rubber-band rate the logs currently hide.

### 5.2 Client side (our client builds — for controlled repro, not the fleet)

Extend `/lss trace` JSONL with:
- **tick-stall events**: client tick gap > 100 ms, with decode-queue depth, consumer
  ingest backlog (`pendingIngestBacklog`), GC-time delta (GarbageCollectorMXBeans), heap;
  directly tests "the stalls are LOD-caused",
- **chunk-hole map**: 1 Hz, client-side loaded status of the 9×9 chunk area around the
  player (which columns are missing right now),
- **teleport-back events** (`ClientboundPlayerPosition` receipt) with the hole map at that
  instant — the client-side mirror of a server rejection.

### 5.3 Effort estimate

Server tracer: ~1 mixin + 2 accessor mixins + a JSONL writer (the soak exporter pattern,
reusable), a config key, ~a day incl. tests. Client additions: trace event types on
existing plumbing, ~half a day. No wire changes, no protocol bump.

## 6. Experiments — to run later (dev box is busy with profiling; nothing here has been run)

Ordered by information-per-effort:

- **E1 — deploy the tracer build to the Modrinth server and wait.** Organic events occur
  roughly weekly (3 in the last ~3 days as traffic picked up). Success criterion: for each
  `moved wrongly`, the event row shows the collision chunk pending/unACKed (hypothesis
  confirmed) or client-known (hypothesis dead, look at residual vectors instead).
- **E2 — controlled A/B, one route** (spawn → ~2000 blocks, low over terrain, full boost):
  (a) our client, LOD on; (b) same client, `receiveServerLods=false`; (c) clean vanilla
  client. Count logged + silent rejections per km. Separates LSS-caused from
  vanilla-inherent from machine-inherent.
- **E3 — arm transport deference live**: `outboundBufferCeilingKB=8192` on the Modrinth
  server, then watch per-player `obuf_hw`/`deferred=` during an E2a flight. Tests the wire
  head-of-line contribution. (Diag note: `obuf_hw` is per-player — an empty server shows
  nothing; someone must be flying.)
- **E4 — bandwidth knee sweep with tracer metrics** (5/10/20/40 MB/s raw): find the event
  onset; a knee constant in *wire* MB/s ⇒ pipe-bound, constant in *raw* MB/s ⇒
  client-processing-bound (the discriminator the 08-01 doc proposed but couldn't measure).
- **E5 — client spark profile** during an E2a repro on the user's machine: attributes the
  stalls (GC vs zstd/decode vs Voxy ingest vs meshing). Server-side spark is currently
  disabled (`spark-*.jar.disabled`) and isn't needed for this.
- **E6 — local shaped-link rig** (when the box frees up): real client, `tc netem` at
  ~20 Mbps, instrumented build, `run-fabric-store` server. The only fully controlled
  environment; also the regression harness for any fix.

## 7. Candidate mitigations (decide only after E1/E2 data)

- **M1 — vanilla-first deference (server, preferred direction):** pause a player's LOD
  column flush while their `PlayerChunkSender` shows pending chunks near the player or
  unACKed batches — retain-in-backlog semantics already exist (the obuf-ceiling path).
  Real terrain always outranks LOD on the shared channel; the client stops receiving LOD
  exactly when it's too busy to ingest it anyway.
- **M2 — arm `outboundBufferCeilingKB` by default** if E3 shows obuf buildup on real links — **RESOLVED 2026-08-13: the AUTO detour was live-falsified and deleted the same day; slow-link protection ships as the client transfer governor + server ping backstop instead (adaptive-transfer-rate-plan.md)**
  (the mechanism was measured absent on the LAN rig; the live internet is the test that counts).
- **M3 — speed-aware LOD throttle:** above ~15 blocks/s sustained, cut the per-player LOD
  rate to a trickle; restore on slowdown. Crude but targets the exact regime.
- **M4 — client stall governor:** on detected tick-gap stalls, proactively send the
  backpressure clear batch + shrink the want-set. Extends #71, which keys on queue depths
  and cannot see GC/render stalls.
- **M5 — NOT recommended:** `elytra_movement_check=false` silences the `moved too quickly`
  rubber-bands (it is gamerule-gated on 26.2) — but not `moved wrongly`, and it masks the
  data we're trying to collect.
- **M6 — upstream report:** if E2c shows a clean vanilla client clipping terrain under slow
  delivery, the 26.2 freeze-gate removal is a vanilla regression worth reporting with that
  repro.

## 8. Why logs alone can't settle it

Vanilla logs `moved wrongly!` with **only the player name** — no position, no residual, no
delivery state; the silent rejection path logs nothing; server MSPT isn't recorded
(tabtps is live-only, spark disabled); client chunk-delivery state exists nowhere
server-side today. Every one of those is a one-line capture at a choke point we can mixin —
that's §5.
