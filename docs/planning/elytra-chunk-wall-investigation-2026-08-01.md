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
  investigation a round.
