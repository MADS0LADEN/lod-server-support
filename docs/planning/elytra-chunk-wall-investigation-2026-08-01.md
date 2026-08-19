# Elytra chunk-wall investigation (2026-08-01)

Live-server incident, root-caused same day. Recorded here because the diagnosis
inverted twice, and both inversions carry reusable lessons (plus the profile-decode
tooling trick in §6).

## 1. Symptom

On the hosted Fabric 26.2 server (Moonrise + Lithium + FerriteCore + LSS, LOD store
`full`, backfill complete, 256-chunk LOD distance): flying fast by elytra during LOD
fill, the player "runs into" not-yet-loaded vanilla chunks and is stopped mid-air.
Server CPU reads high on the host panel while it happens. Server log shows
`<player> moved too quickly!` warnings bracketing each episode.

At the time of the incident the per-player LSS bandwidth cap had been raised
100 MB/s (global 300 MB/s) — up from the 20 MB/s default.

## 2. What the server-side evidence ruled OUT

All from one repro window (14:21:36–14:22:06 server log + spark profile
`https://spark.lucko.me/HRyZaISSkd`):

- **Main-thread CPU**: spark (main thread only — default dumper) showed **94.7% of
  the 30 s window parked** in `LockSupport.parkNanos`; total tick work ~1.4 s ≈
  **2.4 ms/tick** against the 50 ms budget. Zero `Can't keep up!` lines. The tick
  loop was nearly idle *during the wall*.
- **LSS server pipeline**: `send_queue=0`, `qpeak=0`, `saturated=0`, `pending=0`
  throughout — nothing queued server-side.
- **Disk**: `avg_read` 1.1–7.5 ms, `errors=0`, `read_path=moonrise-low` (reads
  correctly deferring at Moonrise LOW priority). Store hits ~73 µs.
- **LSS generation**: `gen=0` submitted/active — the flight was over
  already-generated terrain; worldgen starvation not involved.
- **Store backfill**: completed before the repro (676 regions, 465k columns,
  21 MSPT-gate pauses) — not a factor in the repro window.

## 3. What confirmed the cause

- Client A/B: **`"receiveServerLods": false` ⇒ no repro** (user-run). LSS's stream
  is the cause; the only question was which resource it exhausts.
- Throughput during the repro: **~21–25 MB/s of counted column bytes sustained for
  2m23s (2.96 GB)** to one player, `rate=699 sections/s`.

## 4. Root cause (as currently understood)

The LOD stream overwhelms the **client side** of the connection, and vanilla's own
chunk delivery is the casualty. Mechanism:

1. LOD payloads and vanilla chunk packets share one TCP connection with no
   prioritization.
2. Vanilla paces chunk delivery via the chunk-batch ack loop
   (`PlayerChunkSender.desiredChunksPerTick`, fed by
   `ServerboundChunkBatchReceivedPacket`): a client that is slow to *process* what
   it receives acks slowly, and the server voluntarily throttles vanilla chunk
   sends.
3. Decode/ingest work on the client scales with **raw** (uncompressed) bytes:
   ~25 MB/s of section data to decompress (connection zlib), decode, and hand to
   Voxy's mesher — concurrently with applying vanilla chunks — drags the ack rate
   down. Vanilla chunk delivery collapses, the client runs out of received chunks
   ahead of the flight path, client-side physics stops the player at the edge, and
   the server logs the desync as `moved too quickly`.
4. The "high server CPU" observation was a passenger, not the driver: worker
   threads + network stack pumping the stream (and, earlier in the day, the store
   backfill). The tick loop was healthy the whole time.

**Correction logged during the analysis** (the second inversion): the initial
"~200 Mbps pipe saturation" read was wrong — the LSS limiter counts *uncompressed*
bytes, and connection zlib compresses this corpus ~6–7:1 (verified after the fix:
5 MiB/s counted ⇒ ~6 Mbps observed on the wire). Actual wire rate during the
incident was ~30–40 Mbps: raw-pipe saturation implausible on this link, which is
what tilted the verdict from "bandwidth" to "client processing budget".

## 5. Fix applied + lesson on the cap's unit

`bytesPerSecondLimitPerPlayer` cut 100 MB/s → 5 MiB/s (smooth flight confirmed at
low rate), then raised to **40 MB/s** (2026-08-01, user decision) once the unit was
understood. The cap's real semantic is **client decode-work admission, not network
utilization** — read "40 MB/s" as "40 MB/s of raw section bytes the client must
process", ~45–50 Mbps on the wire. Note: the incident fired at ~21–25 MB/s counted
on this client, so 40 MB/s re-admits the incident range during heavy fill — if the
wall returns, the number to move is this one, downward.

## 6. Tooling banked

- **spark** 1.10.173 installed on the server (survives in `mods/`). Next repro
  should use `/spark profiler start --thread * --timeout 30` — the default dumper
  profiles the main thread only, which this investigation had to learn the hard way.
- **Headless spark-profile reading**: the viewer is a JS app, but the raw sampler
  protobuf is at `https://bytebin.lucko.me/<id>`. Layout (26.2-era spark): top-level
  field 2 = thread nodes; thread: f1 name, f4 packed-double window times, f5 root
  refs, f3 = flat node pool; pool node: f3 class, f4 method, f7 desc, f8 packed
  window times, f9 children refs. A generic protobuf walker + hottest-path descent
  reproduces the flame graph in a terminal.
- RCON-driven live testing pattern (used for the daykeeper verification, reusable
  for repro scripting on the throwaway local server).

## 7. Follow-ups spawned

- **Per-player flow control** (brainstormed, not yet designed in full): the static
  cap is one-size-fits-all but the budget is per-client. Candidate signals, best
  first: vanilla's own `PlayerChunkSender.desiredChunksPerTick` /
  `unacknowledgedBatches` / `pendingChunks` (verified present in 26.2 — the exact
  "is vanilla chunk delivery straining for THIS player" measurement, no wire
  change); netty channel writability (pipe-only signal); a client-side
  vanilla-hole-ahead detector feeding the existing issue-#71 want-set taper/halt
  plumbing (client-only release, helps on any server); an explicit client flow
  report (protocol change, last resort). Control law: deference gate ("LOD as
  scavenger traffic") first, per-player AIMD ceiling inside `SharedBandwidthLimiter`
  if needed. The v17 silent-drop + re-declaration architecture makes aggressive
  deferral safe by construction.
- **End-to-end zstd columns**: `compressed-columns-design.md` — kills the
  double-compression on store hits and the counted-vs-wire confusion that cost this
  investigation a round. **Shipped** on `feat/compressed-columns` and live-validated
  2026-08-01 (protocol 19; 8.63 GB raw → 1.38 GB wire over a full session). Its
  relevance here is §8.7: it moved client decode cost DOWN and wire bytes slightly UP,
  which makes it a free controlled A/B for the question below.

---

# Part II — nailing down the transport hypothesis (opened 2026-08-01, post-zstd)

§4's verdict ("client processing budget, not the pipe") was reached by *elimination* —
server-side evidence ruled out everything server-side, and the remaining candidate was
named. Nothing measured the transport directly. The working suspicion now is that the
TCP connection itself is congested. This part is the plan to settle it with
measurements instead of elimination.

## 8. What "congested" could mean — three hypotheses, different fixes

The word covers three distinct mechanisms. They stack, they look alike from the server,
and only one of them is fixed by lowering the byte cap.

**H1 — Send-queue head-of-line blocking (server-local, LSS's own doing).**
LSS sends through `ServerPlayNetworking.send(player, payload)` → `Connection.send` →
netty channel write. **There is no writability check anywhere in the LSS send path**
(verified: no `isWritable` / `ChannelOutboundBuffer` reference in the codebase). Every
byte LSS hands netty enters the *same* `ChannelOutboundBuffer` that vanilla's chunk
packets enter — and netty's outbound buffer is unbounded. Whatever the downstream
bottleneck is, a vanilla chunk packet written after 20 MB of LOD payloads waits for
20 MB to drain. Vanilla chunk latency = queue depth ÷ drain rate, and the per-tick flush
tops up the queue faster than a constrained socket empties it. **This mechanism needs no
network fault at all** — it converts *any* downstream limit into a multi-second vanilla
chunk stall.

**H2 — Path congestion (true TCP congestion).** cwnd limited by loss or AQM somewhere
between the host and the player. Signature: retransmissions, dup-ACKs, cwnd collapse,
RTT inflation *without* a closed receive window.

**H3 — Receiver-limited.** The client's netty event loop and/or render thread falls
behind, the client stops reading the socket, the receive window closes, and the server's
send path backs up. This is §4's current verdict.

The trap: **H1, H2 and H3 all look identical from the server** — its buffer backs up in
every case. That is precisely why the last round could not distinguish them, and why the
new instruments below are deliberately split between *both ends* of the connection.

## 8.1 Discriminator table

| Signal | Where | H1 queue | H2 path | H3 receiver |
|---|---|---|---|---|
| netty outbound pending bytes | server | **large** | large | large |
| `channel.isWritable()` | server | false | false | false |
| TCP zero-window advertised | client capture | no | no | **yes** |
| retransmits / dup-ACK / cwnd collapse | client capture | no | **yes** | no |
| kernel `Recv-Q` on the client socket | client | ~0 | ~0 | **high** |
| `ChunkBatchSizeCalculator.getDesiredChunksPerTick()` | client | **stays high** (starved) | stays high | **collapses** (busy) |
| `PlayerChunkSender.unacknowledgedBatches` | server | pegged | pegged | pegged |
| vanilla ping RTT (1 Hz, same connection) | client | **inflates** | inflates | inflates |
| wire bytes/s vs link capacity | client | below | **at/near** | below |

Read the table by columns, not rows: the *three* decisive cells are the client's
zero-window flag (H3), the retransmit counters (H2), and `desiredChunksPerTick` — which
splits "the client is busy" from "the client is starved" in one number, from inside the
client, with no capture at all.

## 8.2 Instrument A — vanilla's own numbers in `/lss trace`

This is the "add vanilla data to lss trace" idea, and it lands better than expected:
**vanilla already computes every number we want on the client.** One new `net` event per
second, all behind the existing `ClientTraceLog.enabled()` gate, hooked in
`LodRequestManager.tick()` (`LodRequestManager.java:151`):

| Field | Source (all verified present in 26.2) | What it settles |
|---|---|---|
| `ping_ms` | `Minecraft.getDebugOverlay().getPingLogger()` — public getter on `DebugScreenOverlay`, `LocalSampleLogger.get(i)` reads the ring | App-level RTT **on the same TCP connection**. The queueing-delay probe: if vanilla's ping goes 30 ms → seconds while LSS streams, the shared byte stream is backed up. |
| `wire_bps` | `getBandwidthLogger()` — fed by `BandwidthDebugMonitor.onReceive`, installed in the **frame decoder**, so it counts *compressed wire* bytes off the socket | Real pipe utilization. Pair with LSS's raw counter for a live wire/raw ratio — the exact confusion that cost §4 a round. |
| `dcpt` | `ClientPacketListener.chunkBatchSizeCalculator` (private → accessor mixin) → `getDesiredChunksPerTick()` | **The cleanest H3 discriminator.** Vanilla's own measurement of how fast *this client* can apply chunks (`aggregatedNanosPerChunk`). Collapses ⇒ client-bound; stays high while chunks don't arrive ⇒ starved. |
| `runway` | `ClientChunkCache.getLoadedChunksCount()` + presence probes along the velocity vector | Turns "the wall" into a continuous number: loaded vanilla chunks ahead, and seconds-of-runway at current speed. Lets onset be time-aligned against everything else instead of depending on the player noticing. |
| `tick_ms`, `fps` | `getTickTimeLogger()`, `Minecraft.getFps()` | H3's other half — render-thread saturation from Voxy meshing. |
| decode queue / bytes / ingest backlog | already in LSS (`ClientColumnProcessor.getQueuedBytes()` etc.) | LSS-side pressure, for correlation. |

**Gotcha, verified in bytecode:** `ClientPacketListener.tick()` calls
`pingDebugMonitor.tick()` **only while `getDebugOverlay().showNetworkCharts()` is true**
— the ping samples do not accumulate otherwise. So the trace must call
`toggleNetworkCharts()` on start (or refuse to start without it and say so). Nice side
effect: the player then *sees* the ping and bandwidth charts live while flying, which is
a zero-code readout on its own. Whether the client installs the `BandwidthDebugMonitor`
unconditionally is **not yet verified** — if it turns out to be chart-gated too, the same
toggle covers it; if it is absent entirely, LSS can add its own counting handler at the
head of the client pipeline.

**Measurement hazard to fix first:** `ClientTraceLog.event` flushes per line, and the
`col` event fires **per column** — at the ~700 columns/s of a wall episode, the trace
itself becomes client I/O load and perturbs the very thing being measured. Before this
investigation runs, either buffer the writer (flush on a timer) or add a sampled/aggregate
mode for `col`. The per-second `net` event is fine either way.

### 8.2.1 Built — the `net` event (2026-08-01)

Shipped on `feat/compressed-columns`; client-only, no protocol change, no server redeploy.
`ClientNetTrace` emits one line per second while `/lss trace` is on:

```json
{"t":"net","ms":123456,"ping":42,"dcpt":11.250,"runway":18,"runway_s":"3.60",
 "miss_view":37,"loaded":1024,"wire_bps":4210233,"raw_bps":26314561,"fps":118,
 "q":312,"qb":18874368,"ingest":2048,"inflight":640,"spd":"79.94"}
```

- `ping` — ms; `-1` until the first pong lands.
- `dcpt` — vanilla chunks/tick the client believes it can apply; `-1` if the accessor is
  unreachable (a future MC could move the field — the trace degrades, it never throws).
- `runway` / `runway_s` — loaded chunks ahead before the first hole, and seconds at
  current speed; `-1` when standing still (no meaningful heading).
- `wire_bps` / `raw_bps` — LSS column bytes as shipped vs raw; `-1` across a reconnect
  (the session-gate counters reset, so the delta would be negative).
- `spd` — blocks/s.

**Reading it:** the wall is `runway` → 0. Look at what the *other* fields were doing in
the seconds before: `dcpt` collapsing ⇒ H3; `ping` inflating with `dcpt` steady ⇒ H1/H2;
`wire_bps` at the link's ceiling ⇒ H2. `raw_bps / wire_bps` is the live compression ratio
that §8.8's cap ladder needs.

The F3 dependency is designed out: vanilla only *sends* its ping while the network charts
are open, so LSS sends its own `ServerboundPingRequestPacket` at 1 Hz — vanilla's pong
handler (`ClientPacketListener.handlePongResponse`) logs the sample unconditionally,
verified in bytecode.

**Flush hazard fixed in the same change:** `ClientTraceLog` flushed per line (one write
syscall per event) and `col`/`col_light` fire per column — at wall rates the trace was
loading the client it measured. Now a 64 KB buffer, flushed at most every 200 ms.

## 8.3 Instrument B — the shared queue + vanilla's sender, in `/lsslod diag`

Per player, sampled per tick with a high-water mark (matching the existing `*_hw` gauges):

- **`ChannelOutboundBuffer.totalPendingWriteBytes()`**, `channel.isWritable()`,
  `channel.bytesBeforeUnwritable()` — reachable via two accessor mixins
  (`ServerCommonPacketListenerImpl.connection`, `Connection.channel`; both verified
  private fields). **This is the H1 measurement and the single highest-value addition** —
  it is also exactly the signal §7's flow-control follow-up would consume, so the
  diagnostic is not throwaway.
- **`PlayerChunkSender`** — `desiredChunksPerTick`, `unacknowledgedBatches` /
  `maxUnacknowledgedBatches`, `pendingChunks.size()`, `batchQuota` (accessor mixin; all
  verified present in 26.2). `unacknowledgedBatches` pegged at max = vanilla has stopped
  sending and is waiting on the client's ack, i.e. delivery is being throttled *by the
  ack loop* rather than by bytes.
- **`ServerCommonPacketListenerImpl.latency()`** — public, no mixin. Keepalive RTT, 15 s
  cadence: coarse, free, and enough to confirm a multi-second inflation.
- **`Connection.getAverageSentPackets()`** — public.

**The falsifiable prediction:** if the outbound buffer stays small (< ~1 MB) through a
wall episode, **H1 is dead** and writability gating is not the fix. If it sits at tens of
MB, H1 is proven — and is worth fixing regardless of what is downstream, because it is
LSS unilaterally injecting seconds of latency into vanilla's chunk delivery.

## 8.4 Experiment 1 — external ground truth (no code)

The managed host gives no shell, so the client end is the only capture vantage. Wireshark
on the client machine, filtered to the server address, then:

```
tshark -r cap.pcapng -q -z io,stat,1,"COUNT(tcp.analysis.zero_window)tcp.analysis.zero_window","COUNT(tcp.analysis.retransmission)tcp.analysis.retransmission","SUM(tcp.len)tcp.len"
```

- **zero-window events** ⇒ H3 (the client's socket buffer filled; the app is not reading)
- **retransmissions / dup-ACKs** ⇒ H2 (real path loss)
- **neither, but the ping trace inflates anyway** ⇒ H1 (a server-side queue, not the wire)

If the client ever runs on Linux, `ss -tin dst <server>` polled at 1 Hz gives the same
verdict far more cheaply — it prints `Recv-Q`, `cwnd`, `rtt`, and `retrans` directly.

## 8.5 Experiment 2 — burstiness at constant mean (the causal test for H1)

Hold `bytesPerSecondLimitPerPlayer` fixed and change only the *shape* of the writes:
spread each tick's allocation across sub-flushes, or simply gate sends on
`channel.isWritable()`. Mean throughput identical, queue depth much lower. If the wall
disappears at unchanged throughput, H1 was the mechanism and the fix is nearly free. If
nothing changes, the queue was not the problem and the byte cap really is the lever.

## 8.6 Experiment 3 — the zstd natural A/B (free, already deployed, do this first)

The compressed-columns deploy is a controlled change already in production: client decode
cost went **down** (zstd in place of connection-zlib, and no double-compression on store
hits), while wire bytes went **up ~11.5%** (measured, §5.3 of the progress doc). So flying
the same route at the same 40 MB/s cap and comparing against the pre-zstd episode splits
the hypotheses directly:

- wall **materially better** ⇒ receiver-processing-bound (H3 — §4's verdict holds)
- wall **unchanged or worse** ⇒ pipe/queue-bound (H1/H2 — §4's verdict was wrong)

Zero code, one flight. It should be the first thing run.

### 8.6.1 RESULT — run 2026-08-01, and it is clean

The cap was put back to **exactly the incident's 100 MB/s** (per-player 104,857,600;
global 300 MB/s unchanged) and the same flying was repeated. **No wall.** Live counters
14 minutes in, one player, `feat/compressed-columns` deployed:

```
Bandwidth: 14.6 MB/s / 300.0 MB/s global (7.65 GB total, 1.98 GB wire)
Throughput: rate=301 sections/s (9.2 MB/s)
Voximus_Maximus: sq=0/2000, psync=0, pgen=0
DiskReader: saturated=0, pending=0, avg_read=1.7ms, store h=248894 m=9865 avg_read=24us
Sources (tick): sent=0, qpeak=0/2000
```

**What this settles:**

- **H2 (path congestion) is falsified.** The zstd deploy made the wire ~11.5% *more*
  expensive per raw byte. If the pipe had been the binding constraint, the wall should
  have gotten **worse** at the same cap. It vanished instead.
- **H1 is not active at these rates.** `sq=0/2000` and `qpeak=0/2000` — the server's send
  queue is empty; there is no LSS backlog for vanilla's chunk packets to queue behind.
  (This does *not* retire H1 as a mechanism — it says the queue never builds at ~15 MB/s.
  H1 remains the amplifier that would convert any future downstream limit into a wall,
  which is why the writability gate in §8.9 is still worth doing.)
- **H3 (receiver-limited) is what §4 got right.** The one thing that improved is the cost
  of *receiving*: zstd decompression instead of connection-zlib inflate, and no
  double-compression on store hits. Making the receiver cheaper removed the wall.

So §4's verdict now rests on a measurement rather than on elimination.

**The question that replaces it:** raising the cap 5× (20.9 → 100 MB/s) barely moved
throughput — it settles around **9–15 MB/s**, well under both the old cap and the new one.
The cap has not been the binding constraint at any point in this range. With the server
demonstrably idle (empty send queue, no disk saturation, 96% store hits at 24 µs), the
new ceiling is **client-side**, and the `net` event names which resource:

| Observation in the `net` event | Ceiling |
|---|---|
| `ingest` at/near 6144 | Voxy's mesher — LSS is correctly tapering the want-set to it (issue #71, working as designed) |
| `q` near 1500 or `qb` near 48 MiB | LSS's own single-threaded column decode |
| both low, `inflight` low, `runway` high | neither — the request loop simply is not asking for more (want-set budget / cadence) |

Note the incident sustained **21–25 MB/s** while the current session settles lower, so the
comparison is not throughput-for-throughput; a hard flight over cold terrain is what the
trace should capture.

### 8.6.2 The new ceiling is the request loop, not a resource (client diag, 19:05)

Client-side diagnostics 5 s into a fresh join, alongside the server's:

```
client  Queue: queued=0/8000                     <- decode queue EMPTY
        Budget: used=792/792, ingest_backlog=497 <- want-set 100% used; backlog 8% of the 6144 halt
        Throughput: received=5505 (172.2 MB), dropped=0, recv_rate=1.0K/s, req_rate=1.1K/s
        Requests: send_cycles=8, total_requested=5968
        Scan: confirmed=37, scanning=40/256, fast=5
server  Voximus_Maximus: sq=0/2000, psync=0, pgen=0
        saturated=0, pending=0, store h=456597 m=16608 avg_read=24us
```

Every backpressure gauge on both sides is idle — decode queue empty, ingest backlog at 8%
of its halt threshold (the taper it produces is 800 → 792, ~1%), zero drops, zero ingest
failures, empty server send queue, no disk saturation. **Nothing is exhausted.** The one
saturated number is `used=792/792`: the want-set budget itself.

Throughput therefore decomposes as `budget × re-declaration cadence`:
**792 × ~1.6 Hz ≈ 1270 columns/s**, against an observed 1100/s. The 8 send cycles in 5 s
put the cadence at ~1.6 Hz against the adaptive ceiling of 4 Hz, so the fast path is
firing (`fast=5`) but not at its 250 ms floor.

Two levers, and they are very unequal:

- **budget** — `WANT_SET_BUDGET` is 800, wire-capped by `MAX_BATCH_CHUNK_REQUESTS` at
  1024: **+28% at most** without a protocol change.
- **cadence** — 1.6 Hz against a 4 Hz ceiling: **+150%**. This is the lever worth pulling.

What holds the cadence below 4 Hz is the ≥95%-answered gate, and the likely reason is
architectural rather than accidental: **silently-dropped asks never leave the client's
awaiting set** (CLAUDE.md's want-set section states this explicitly — drops above the 5%
threshold hold the cadence at 1 Hz by design). The server shows `order_gated=67645`
generation-admission refusals against only 160 generations submitted, fed by
`memo_hits=72280` — a large population of declared positions that miss, get refused by the
frontier/pacing gate, and are never answered. Those stragglers sit in `InFlightTracker`
holding the completion ratio under 95%.

**Unverified — this is a 5 s join snapshot against 19 min of cumulative server counters.**
The `net` event's `inflight` series is the direct test: if it plateaus at a nonzero floor
while `q` and `ingest` stay near zero, the straggler hold is confirmed and the cadence
gate is the thing to fix.

### 8.6.3 ANSWER — 26 s flight trace: movement disarms the adaptive cadence

`lss-trace-20260801-190539.jsonl`, 26 s of sustained elytra flight at ~33 blocks/s
(26 `net`, 30 `scan`, 67 `move`, 23,935 `col`).

**The straggler hypothesis in §8.6.2 is WRONG.** `inflight` is **0** at 18 of 26 samples —
the client answers its whole batch and then sits idle waiting for the next scan. Nothing
is held.

**Transport is healthy.** `ping` 20–26 ms for the entire flight, two blips (39 ms, 57 ms).
A queued or congested shared connection reads in the hundreds of ms. **H1 and H2 are
conclusively dead**, not merely inactive.

**Nothing is saturated.** `q` 0 (transient spikes ≤ 203 of 8000), `qb` ≤ 7 MB of 48 MiB,
`ingest` 0 for most samples (peak 3379 in the first 3 s), `fps` 60 flat, `dcpt` a constant
3.500 — vanilla's chunk-apply capacity never wavers, so **H3 is not active either**.
`runway` holds 9–14 chunks (4.4–6.8 s of clear terrain ahead) the whole time: **no wall**.

**The limiter is the scan cadence, and the trace shows exactly what pins it:**

```
ms=269   center=[-1,0]  confirmed=60  fast=true   ring 60..61
ms=618   center=[-1,0]  confirmed=61  fast=true   ring 61..63
ms=1119  center=[-1,0]  confirmed=63  fast=true   ring 63..64
ms=1617  center=[-1,0]  confirmed=64  fast=true   ring 64..66
ms=2619  center=[-2,-2] confirmed=9   fast=false  ring  9..67   <-- first movement
ms=3618  center=[-3,-4] confirmed=9   fast=false  ring  9..68
...      every subsequent scan: confirmed=9, fast=false, gap exactly 1.000 s
```

Scan gaps: `0.35, 0.5, 0.5` while stationary, then `1.0` twenty times over. Stationary the
fast path runs at **2–3 Hz**; the instant the player moves it drops to the **1 Hz fallback
and never recovers for the rest of the flight**.

> **2026-08-18 amendment (scanner-reopened-rings-plan.md):** `recenter(d)` no longer
> zeroes the confirmed prefix — prefix retention decrements it and reopens a ring
> bitset, so the WALK COST paragraph below describes the retired behavior. The CADENCE
> conclusion stands unchanged: `predictedWalkCost` still prices the movement window at
> the bare from-zero cost (deliberately — see its javadoc), so the flight regime and the
> ring-127 cliff documented here are preserved.

**Mechanism (verified in code, not inferred).** `SpiralScanner.recenter()` sets
`confirmedRing = 0` on every chunk-boundary crossing — deliberate and correct, since the
confirmed prefix was derived for the old center (its comment says so). But
`fastRescanDue()` requires `confirmedRing > 0`, and nothing re-derives the prefix until the
next *walk*. At 33 blocks/s the player crosses a chunk every ~360 ms while scans run every
1000 ms, so the first crossing after each scan zeroes the prefix and the fast path is dead
until the next 1 Hz scan re-walks. The fast window is the ~110 ms between the 250 ms floor
and the next crossing — and the batch is rarely 95% answered that early.

**Consequence: the adaptive scan cadence is structurally inert during sustained
movement** — precisely the regime the elytra wall lives in. It only ever runs fast while
standing still.

**Throughput follows directly:** 800 positions × 1 Hz ≈ 800 columns/s; the trace measures
920 columns/s and **25–27 MB/s raw / ~4 MB/s wire (6.2:1)**. That is why raising the cap
from 20.9 to 100 MB/s changed nothing — the client only ever asks for ~26 MB/s while
flying.

**Do not "fix" this without care.** 26 MB/s sits exactly in the 21–25 MB/s band that
produced the original wall. The cadence gate is currently the thing holding flight
throughput at a level this client handles smoothly; lifting it to the stationary 2–3 Hz
would put the flight regime at 50–75 MB/s and walk straight back toward the wall. Any
change here should be paired with the §8.3 outbound-buffer gauge and the §8.9 writability
gate, so the system has a real backpressure signal before it is allowed to go faster.

## 8.7 Experiment 4 — packet count vs byte volume

A **warm** flight (client cache populated ⇒ the server answers `up_to_date`) has the same
request/response *packet* churn at near-zero bytes. Smooth warm flight + walled cold
flight ⇒ the wall is driven by byte volume, not by the request loop's packet rate or
round-trip structure. The last live session already produced both regimes in one sitting
(198,572 `up_to_date` vs 283,731 sent) — this may be answerable from an existing trace.

## 8.8 Experiment 5 — cap ladder, with the knee measured in *both* units

Sweep the cap (5 / 10 / 20 / 40 MB/s raw) and record the wall onset — but convert each
onset to wire bytes using the live ratio, and repeat over terrain with very different
compressibility (open ocean/superflat vs cave-riddled/varied). Terrain compressibility is
the lever that separates the units:

- knee constant in **wire MB/s** across terrain ⇒ the pipe/queue binds (H1/H2)
- knee constant in **raw MB/s** ⇒ client processing binds (H3)

This also produces the number the cap should actually be set to, in whichever unit turns
out to be the real one — closing §5's open question ("40 MB/s re-admits the incident
range").

## 8.9 Order of work

1. **§8.6** — the zstd A/B. Free, already deployed, splits H3 from H1/H2 in one flight.
2. **§8.2 + §8.3** — the two instruments. ~1 day; both are also the inputs the §7
   flow-control design needs, so nothing is throwaway. Fix the trace flush hazard first.
3. **§8.4** — packet capture on the next repro, with the instruments running so the
   timelines can be aligned.
4. **§8.5** — burstiness / writability gate. Likely the *fix*, not merely a test.
5. **§8.8** — cap ladder to set the final number in the unit that turns out to matter.

**Independent of the outcome:** LSS should not enqueue into a channel that is already
unwritable. That is true under all three hypotheses, it is the cheapest possible
mitigation, and §7 already names netty writability as a flow-control signal. The v17
silent-drop + re-declaration architecture makes deferring a send free by construction —
a skipped column is re-declared within a second.
