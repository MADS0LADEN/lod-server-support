# LOD Server Support

Distributes LOD (Level of Detail) chunk data from servers to connected clients over a custom networking protocol. Built primarily as a multiplayer backend for [Voxy](https://modrinth.com/mod/voxy) — clients request distant chunks in batches, the server reads them from disk or memory and streams the data back, enabling Voxy to render terrain far beyond the vanilla render distance on multiplayer servers without the need to travel there first.

Supports **Fabric** clients and **Fabric**, **Paper**, **Purpur** servers (**Folia** on the older support lines).

https://github.com/user-attachments/assets/721fb344-890e-4e03-ab36-539444427f7b

**Try it live:** join our Minecraft 26.2 test server at **`lod-server-support.modrinth.gg`** with [Voxy](https://modrinth.com/mod/voxy) and this mod installed to watch the LOD terrain stream in.

## Downloads

All builds are on [Modrinth](https://modrinth.com/plugin/lod-server-support) — pick the file matching your Minecraft version: `lod-server-support-fabric` (the client/server Fabric mod) or `lod-server-support-paper` (the server plugin). GitHub Releases on this repository mirror every version.

### Redistribution

This mod is MIT-licensed — redistribution with attribution is welcome, and modpacks
can reference the official Modrinth project directly. Per Modrinth's reupload policy:
**[XANTHA](https://modrinth.com/user/XANTHA) on [Voxy Server Side](https://modrinth.com/plugin/voxy-server-side)
has the copyright holder's explicit permission to distribute this mod, and derivatives
of it, on Modrinth.**

## Installation

> [!IMPORTANT]
> Install **LOD Server Support (LSS)** on **both** the server (Fabric mod or Paper plugin) and **every client** (LSS Fabric mod + Voxy).

### Fabric Clients

1. Install [Voxy](https://modrinth.com/mod/voxy) and place `lod-server-support-fabric.jar` in the client's `mods/` directory (requires Fabric API)
2. Join a server running LSS — client config is generated at `config/lss-client-config.json`

### Fabric Server

1. Place `lod-server-support-fabric.jar` in the server's `mods/` directory (requires Fabric API)
2. Install the Fabric mod **and** [Voxy](https://modrinth.com/mod/voxy) on all clients
3. Restart the server — config is generated at `config/lss-server-config.json`

### Paper Server

1. Place `lod-server-support-paper.jar` in the server's `plugins/` directory (Paper or Purpur)
2. Install the Fabric mod **and** [Voxy](https://modrinth.com/mod/voxy) on all clients
3. Restart the server — config is generated at `plugins/LodServerSupport/lss-server-config.json`

## Version Compatibility

Each Minecraft version has its own build; only the latest is listed. Older-MC builds are versioned `v<x.y.z>+mc<version>` and carry the same feature set from long-lived support branches.

| Minecraft | LSS Version | Fabric | Paper | Folia | Voxy | Java |
|---|---|---|---|---|---|---|
| **26.2** | v0.8.2+mc26.2 | ✅ | ✅ | — | 0.2.17-alpha+ | 25+ |
| **26.1.x** | v0.8.1+mc26.1 | ✅ | ✅ | ✅ | 0.2.14-alpha+ | 25+ |
| **1.21.11** | v0.8.1+mc1.21.11 | ✅ | ✅ | ✅ | 0.2.15-beta+ | 21+ |

Fabric builds are client + server; the Paper plugin is server-only and also runs on Purpur. On the older support lines Folia uses the same plugin JAR (experimental). The 26.2 plugin does **not** declare Folia support — no Folia build exists for MC 26.2, and support returns once Folia ships 26.2 and validation passes.

> [!IMPORTANT]
> **Mixed versions are fine back to v0.4.x.** LSS versions a networking protocol with compatibility layers in BOTH directions: a v0.7.0+ server keeps serving older protocol-16 clients (v0.4.x–v0.6.x) via `enableV16Compat` (default on), and a v0.7.0+ client still gets LODs — including on-demand terrain generation — from v0.4.x–v0.6.x servers via `enableV16ServerCompat` (default on). Only against pre-v0.4 peers (or with the layers disabled) does no LOD session form: vanilla render distance, no error. Release notes call out which updates carry a protocol bump.

On 1.21.8 the in-game config screen is unavailable (it requires Sodium 0.8+, and 1.21.8's newest Sodium is 0.7.3); the JSON config files still work as normal.

## How It Works

Without LSS, Voxy can only build LOD data from chunks the client has already loaded — limiting distant terrain rendering to areas the player has personally visited. LSS moves this work to the server:

1. Client connects and performs a handshake with the server
2. Server sends session config (distance limits, generation settings)
3. Once a second, the client scans outward in an expanding spiral and declares the complete set of chunks it still wants, closest-first
4. The server replaces that player's queue with the new set, so it never works on chunks the player has already moved away from, and never rejects a request the client would just have to re-send
5. Server reads chunks from disk (or generates them on demand), serializes the raw MC section data (block states, biomes, lighting), and streams it back
6. Client receives the section data and feeds it directly into Voxy's rendering engine via `rawIngest`; served chunks drop out of the next second's set, so the request naturally stops repeating
7. After initial sync, the server pushes notifications when chunks change so clients stay up to date

The result: players see fully rendered terrain out to hundreds of chunks on multiplayer servers, without needing to explore the world first.

## Commands

### Server (Fabric and Paper)

- `/lsslod stats` - Show per-player transfer statistics
- `/lsslod diag` - Show detailed diagnostics (config, bandwidth, queue depths)

### Client (Fabric only)

- `/lss clearcache` - Clear the local column cache, forcing all chunks to be re-requested from the server
- `/lss diag` - Show client-side diagnostics (connection, throughput, scan progress, request budget)
- `/lss trace` - Toggle a per-event JSONL trace log (scans, movement, received columns and their serve source) under `logs/` for diagnosing LOD behavior; off by default

## Configuration

### Server (Fabric and Paper)

Server config is generated on first run:
- **Fabric:** `config/lss-server-config.json`
- **Paper:** `plugins/LodServerSupport/lss-server-config.json`

| Setting | Default | Description |
|---------|---------|-------------|
| `enabled` | `true` | Enable LOD distribution |
| `enableV16Compat` | `true` | Serve legacy v0.6.x (protocol 16) clients through a built-in translation layer at their slower pace. Set `false` to require every client to match the server's protocol (a v0.6.x client then gets no LOD session, like any other version mismatch) |
| `lodDistanceChunks` | `256` | Max LOD distance in chunks |
| `bytesPerSecondLimitPerPlayer` | `26214400` | Per-player bandwidth cap (25 MiB/s), counted **before** compression |
| `bytesPerSecondLimitGlobal` | `268435456` | Total bandwidth cap across all players (256 MiB/s), counted **before** compression |
| `diskReaderThreads` | `0` | Thread pool size for async disk reads. `0` = auto: sized from the read path actually in use (3 on vanilla single-threaded IO, up to 8 where reads are properly prioritised) |
| `useBackgroundReadPriority` | `true` | LOD disk reads yield to vanilla/gameplay chunk loading, so streaming distant terrain doesn't delay the chunks players are actively loading (Fabric: IOWorker BACKGROUND priority; Paper/Folia: Moonrise LOW priority). On Fabric servers running a chunk-IO-overhaul mod (e.g. C2ME) that replaces vanilla's IOWorker, LSS automatically switches to adaptive read throttling (self-restraint that still yields to gameplay), logging one warning. Set `false` to restore foreground reads with no read protection |
| `sendQueueLimitPerPlayer` | `1024` | Max queued column payloads per player (each carries a full chunk column of sections; = the wire batch cap — existing saved configs keep their value) |
| `generationConcurrencyLimitPerPlayer` | `16` | Max concurrently generating chunks per player — misses beyond it are retried automatically each second until a slot frees |
| `enableChunkGeneration` | `true` | Generate missing chunks on demand for LOD data |
| `generationConcurrencyLimitGlobal` | `32` | Max chunks generating server-wide at once |
| `generationTimeoutSeconds` | `60` | Timeout for pending chunk generation |
| `perDimensionTimestampCacheSizeMB` | `0` | Max timestamp cache per dimension in MB (used for up-to-date checks on reconnect). `0` = auto: sized from `lodDistanceChunks`, so raising the distance no longer silently under-provisions it |
| `dirtyBroadcastIntervalSeconds` | `10` | Interval for pushing dirty column notifications to clients |
| `lodStore` | `"full"` | Keep a compressed copy of every served LOD column in `<world>/lss-lod/store.db` and serve repeat requests straight from it. `"off"` disables it. See **Tuning** below for the CPU/disk trade |
| `lodStoreBackfill` | `true` | Pre-warm the store with a low-priority background walk of your existing region files, so the first player to arrive already gets warm serves. Yields to players and tick health, pauses under load, resumes across restarts. Fabric only |
| `lodStoreBackfillColumnsPerSecond` | `500` | Pace of that walk (clamped 10–1000). Lower it if the walk is noticeable on a busy server |
| `lodStoreMaxMB` | `0` | Size cap for the store. `0` = uncapped (grows to roughly the size of your region files). Set a value to bound it — oldest columns are evicted first and re-warm on demand |
| `lodStoreResweepSeconds` | `0` Fabric / `300` Paper | How often the store re-checks stored columns against the world files for staleness. Paper needs this because some world edits raise no event |
| `useCompressedColumns` | `true` | Compress LOD columns on the wire (about 6× smaller). Clients that don't support it are served uncompressed automatically; `false` disables it entirely as a rollback |
| `outboundBufferCeilingKB` | `0` | `0` = off. If set, LSS skips a tick's column flush when the connection's outbound buffer is above this, so it stops adding to a queue vanilla's own chunk packets share. Leave off unless `/lsslod diag` shows a high `obuf_hw` |
| `useNbtTranscode` | `true` | Serve disk chunks by transcoding region NBT straight to wire bytes (skips object construction); `false` restores the object path as a rollback |
| `missMemoTtlSeconds` | `30` | How long the server remembers "this chunk isn't generated yet" after a disk miss, so chunks waiting for generation don't re-check disk every second. Any serve, world edit, or finished generation forgets the entry immediately; `0` disables the memo (values are clamped to 0-60) |
| `xrayObfuscation` | `"auto"` | Anti-xray masking for LOD data. `"auto"` masks hidden ores in served LOD columns whenever an anti-xray engine is detected — Paper's built-in anti-xray (per world) or the DrexHD AntiXray mod on Fabric — mirroring that engine's exact hidden-block list and height cutoff. `"on"` forces masking everywhere; `"off"` disables it (LOD data then carries real ore locations even on anti-xray servers) |
| `xrayHiddenBlocks` | Paper's default ore list | Fallback hidden-block list, used only when no engine settings can be adopted (mode `"on"` with no engine, or a detection failure). Unknown ids are skipped with a warning |
| `xrayMaxBlockHeight` | `64` | Fallback masking cutoff: only blocks below this world Y are masked. Blocks at or above it already ship unobfuscated in vanilla chunk packets, so masking them would hide nothing |

**Anti-xray masking notes:** masking applies to columns served after it activates — columns already in client caches are not recalled (true of any anti-xray retrofit). Cave shapes and light data are not hidden (same as the packet-level anti-xray systems), and the `/lsslod diag` command shows an `Xray:` status line (active source + masked section count). On Fabric, LSS also ships a compatibility shim so the AntiXray mod no longer crashes servers running LSS.

**Paper-specific:** The config also includes an `updateEvents` list of Bukkit event class names used for dirty chunk detection.

`/lsslod` commands require operator status on both platforms (Fabric: gamemaster permission level; Paper: the `lss.admin` permission, default op).

### Tuning

The defaults are tuned for a typical server — most admins never need this section. If you do:

**To limit CPU, use the bandwidth and generation limiters.** LSS's CPU cost is essentially "how many columns per second does it serve, and how many chunks does it generate to do it". Those two families of setting cap exactly that:

- `bytesPerSecondLimitPerPlayer` / `bytesPerSecondLimitGlobal` bound the serve rate. They count **uncompressed** bytes on purpose, so enabling compression doesn't quietly raise the real ceiling. Halving them roughly halves LSS's steady-state CPU.
- `generationConcurrencyLimitGlobal` / `generationConcurrencyLimitPerPlayer` bound generation, which is by far the most expensive thing LSS can trigger — it is worldgen. On a server exploring fresh terrain this is the dominant cost, and lowering it is the biggest single CPU saving available. Setting `enableChunkGeneration: false` removes it entirely (players then only see terrain that already exists).

Lowering either costs *speed*, not correctness: LOD fills in more slowly, nothing breaks. Most other settings change *how* the work is done rather than how much, so they are the wrong lever for a CPU problem.

**The LOD store trades disk for CPU.** With `lodStore: "full"` (the default), a repeat request is answered from `<world>/lss-lod/store.db` instead of reading the region file and re-serializing the chunk:

| | CPU per served column | Disk |
|---|---|---|
| `"full"` | ~99% lower on warm serves (≈29µs vs ≈2ms), and ~99% of requests hit the store once warm | Roughly the size of your region files — a 10 GB world adds ~7 GB |
| `"off"` | Every serve pays a region read plus full re-serialization | Nothing |

So: **short on CPU, keep the store on. Short on disk, turn it off or bound it with `lodStoreMaxMB`.** The store is derived data — deleting `lss-lod/` while the server is stopped is always safe, and it re-warms on its own.

If disk space is tight, `lodStoreBackfill: false` also stops the initial background walk, and the store then warms only where players actually go — slower to become useful, but it never writes more than your players need.

### Client

Client config is generated at `config/lss-client-config.json` on first run.

| Setting | Default | Description |
|---------|---------|-------------|
| `receiveServerLods` | `true` | Enable receiving LOD data from the server |
| `lodDistanceChunks` | `0` | Max LOD request distance in chunks (0 = use server limit) |
| `enableAdaptiveScanCadence` | `true` | Request the next batch of LOD chunks as soon as the current one arrives (up to 4×/second) instead of once per second; `false` restores the fixed 1-second pace |

## License

MIT
