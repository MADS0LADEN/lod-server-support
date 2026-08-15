# LOD Server Support

Enables players with [Voxy](https://modrinth.com/mod/voxy) to see fully rendered terrain out to hundreds of chunks on multiplayer servers without needing to explore the world first. Also includes **Far Players**: players far beyond normal render distance appear in the LOD terrain with name tags, equipment, and mounts.

**Try it live**: join `lod-server-support.modrinth.gg` with Voxy and this mod installed. Supports Minecraft 26.2, 26.1, 1.21.11, and 1.21.1.

https://github.com/user-attachments/assets/721fb344-890e-4e03-ab36-539444427f7b

## Compatibility

Clients use the Fabric mod on every version; on 1.21.1 a NeoForge client works as well. Supported servers:

| Minecraft | Fabric | Paper / Purpur | Folia | NeoForge |
|---|---|---|---|---|
| 26.2 | ✅ | ✅ | ✅ (experimental) | - |
| 26.1 | ✅ | ✅ | ✅ (experimental) | - |
| 1.21.11 | ✅ | ✅ | ✅ (experimental) | - |
| 1.21.1 | ✅ | ✅ | - | ✅ |

On NeoForge (1.21.1) the recommended client path is the community [Voxy NeoForge port](https://github.com/j-shelfwood/voxy-neoforge) with [Forgified Fabric API](https://modrinth.com/mod/forgified-fabric-api) in place of Fabric API. Tested working with Forgified Fabric API 0.116.15, Sodium 0.6.13, and Voxy NeoForge port 0.2.9-alpha.

Compatible with [AntiXray](https://modrinth.com/mod/anti-xray), [Moonrise](https://modrinth.com/mod/moonrise-opt), [C2ME](https://modrinth.com/mod/c2me-fabric), [ViaVersion](https://modrinth.com/plugin/viaversion)/[ViaBackwards](https://modrinth.com/plugin/viabackwards), and most other mods. Can be run alongside Distant Horizons on the same server to support DH clients and Voxy clients simultaneously. 

LOD Server Support is backwards and forwards compatible from v0.4.0 through the current version. Server operators can freely update to take advantage of improvements without breaking clients on older versions, and clients can update without breaking compatibility with older servers.

[Voxy Server Side](https://modrinth.com/plugin/voxy-server-side) is the same mod. Voxy Server Side clients are compatible with LOD Server Support servers and vice versa.

## Installation

Install **LOD Server Support** on **both** the **server** (LOD Server Support Fabric mod, Paper plugin, or NeoForge mod on 1.21.1) and **every client** (LOD Server Support Fabric mod + Voxy). Without LOD Server Support on both the connecting client and on the server it will not function.

## Commands

### Server (Fabric, NeoForge, and Paper)

- `/lsslod stats` - Show per-player transfer statistics
- `/lsslod diag` - Show detailed diagnostics (config, bandwidth, queue depths)
- `/lsslod set <setting> <value>` - Change common settings live, no restart needed
- `/lsslod store status` - Show LOD store status (state, hit/miss counters, size)
- `/lsslod store backfill start|stop|status` - Control the background pre-warm walk (not on Paper)
- `/lsslod help` - List all commands

### Client (Fabric only)

- `/lss clearcache` - Clear the local column cache, forcing all chunks to be re-requested from the server
- `/lss reset` - Wipe this server's LODs (local cache and Voxy's stored data) and re-stream them fresh
- `/lss diag` - Show client-side diagnostics (connection, throughput, scan progress, request budget)

## Configuration

Config files are generated during first run at `config/lss-server-config.json` on Fabric and NeoForge or `plugins/LodServerSupport/lss-server-config.json` on Paper.

| Setting | Default | Description |
|---------|---------|-------------|
| `enabled` | `true` | Enable LOD distribution |
| `lodDistanceChunks` | `512` | Max LOD distance in chunks |
| `mbPerSecondLimitPerPlayer` | `25.0` | Per-player bandwidth cap in MiB/s (decimals like `12.5` work), counted **before** compression |
| `mbPerSecondLimitGlobal` | `75.0` | Total bandwidth cap across all players in MiB/s, counted **before** compression |
| `enableChunkGeneration` | `true` | Generate missing chunks on demand, so players see terrain nobody has visited |
| `generationConcurrencyLimitGlobal` | `40` | Max chunks generating server-wide at once |
| `generationConcurrencyLimitPerPlayer` | `40` | Max concurrently generating chunks per player |
| `maxConcurrentDiskReads` | `0` | Max LOD disk reads running at once. `0` = auto (half the reader threads while the LOD store is on, all of them otherwise). See **Server Performance Tuning** |
| `dirtyBroadcastIntervalSeconds` | `10` | Interval for pushing dirty column notifications to clients. `0` disables the pushes entirely |
| `farPlayers` | `"on"` | Show distant players as player models in the LOD terrain. `"opt-in"` shows only players who opted in, `"off"` disables. A per-player exclude list, a hide permission, and vanish plugins are honored |
| `farPlayersMaxDistanceBlocks` | `2048` | Max distance in blocks at which far players are visible |
| `lodYieldsToVanillaTransport` | `true` | Pause LOD sending to a player while their connection is backed up, so vanilla packets always go first |
| `enablePingBackstop` | `true` | Cut a player's LOD rate when their ping spikes, keeping gameplay responsive on slow connections |
| `enableSendPacing` | `true` | Smooth LOD sending into small per-tick slices instead of bursts |
| `lodStore` | `"on"` (new installs) | Keeps a compressed copy of every served LOD column in `<world>/lss-lod/` and serves repeat requests from it, which is far less CPU and disk work per chunk. The cost: it roughly doubles your world folder. Generated as `"on"` for brand-new servers; on an upgraded server whose config file doesn't have the key, it stays `"off"` until you enable it. See **Server Performance Tuning** |
| `lodStoreBackfill` | `true` | Pre-warms the store with a low-priority background walk of your existing world, so the first player to arrive already gets fast serves. Inert unless `lodStore` is on. Yields to players, pauses under load, resumes across restarts. Not available on Paper |
| `lodStoreMaxMB` | `0` | Size cap for the store. `0` = uncapped; set a value to bound it, and the oldest columns are evicted first |
| `enableV16Compat` | `true` | Serve legacy v0.4.x-v0.6.x clients through a built-in translation layer. `false` requires every client to match the server's protocol |
| `enableV18Compat` | `true` | Serve v0.7.x-v0.8.x clients natively, minus only the features their client predates. `false` drops them to the `enableV16Compat` fallback |
| `enableV19Compat` | `true` | Serve v0.9.x clients natively. `false` drops them to the `enableV16Compat` fallback |
| `xrayObfuscation` | `"auto"` | Anti-xray masking for LOD data. `"auto"` mirrors your anti-xray engine's own hidden-block list and height cutoff whenever one is detected (Paper's built-in, per world; the DrexHD AntiXray mod on Fabric). `"on"` forces masking, `"off"` disables it, in which case LOD data carries real ore locations even on anti-xray servers |
| `xrayHiddenBlocks` / `xrayMaxBlockHeight` | ore list / `64` | Fallback list and Y cutoff, used only when no engine settings can be adopted |


### Server Performance Tuning

**Turn the LOD store on.** `"lodStore": "on"` is the single biggest CPU performance win available, it caches the preprocessed LOD data to disk with the drawback of **roughly doubling (+70%) the size of your world directory**.

When the LOD store is enabled a backfill task will populate it. Maximum CPU savings are only achieved after the store is populated. Check the progress of the task with `/lsslod store backfill status`. If your world is much larger than the area players commonly visit you can save on disk space by disabling the backfill task with the `"lodStoreBackfill": false` config.

**Use the bandwidth and generation limiters to limit CPU.** LOD Server Support's CPU cost is essentially how many columns per second it serves plus how many chunks it generates, and these configs cap exactly that:

- `mbPerSecondLimitPerPlayer` / `mbPerSecondLimitGlobal` bound the chunk serve rate. They count **uncompressed** bytes on purpose so compression doesn't quietly raise the real ceiling. The actual max network utilization will be approximately 1/8th of these limits.
- `generationConcurrencyLimitGlobal` / `generationConcurrencyLimitPerPlayer` bound new chunk generation, by far the most expensive thing LOD Server Support can trigger. On a server exploring fresh terrain this dominates, and lowering it is the single biggest saving. `enableChunkGeneration: false` removes it entirely.
- `maxConcurrentDiskReads` bounds how many LOD disk reads run at once, so LOD traffic can't monopolize disk I/O that vanilla chunk loading needs. The `0` auto default is right for most servers; lower it to `1` or `2` if gameplay chunk loading stutters while LODs stream, raise it if LOD loading feels slow on fast NVMe storage.

**Disable LOD store resweep on Paper.** `"lodStoreResweepSeconds": 0` This will reduce CPU utilization at a slight cost to correctness, on Paper its possible to miss chunk updates so old LODs could be served.

## Redistribution

This mod is MIT-licensed, redistribution with attribution is welcome, and modpacks can reference the official Modrinth project directly. Per Modrinth's reupload policy: [XANTHA](https://modrinth.com/user/XANTHA) via [Voxy Server Side](https://modrinth.com/plugin/voxy-server-side) has the copyright holder's explicit permission to distribute this mod, and derivatives of it, on Modrinth.
