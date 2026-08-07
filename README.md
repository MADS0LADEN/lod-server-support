# LOD Server Support

Streams LOD (Level of Detail) chunk data from your server to connected clients, so [Voxy](https://modrinth.com/mod/voxy) can render terrain hundreds of chunks out — including terrain the player has never visited.

Supports **Fabric** clients, and **Fabric**, **Paper**, **Purpur** and **Folia** servers.

https://github.com/user-attachments/assets/721fb344-890e-4e03-ab36-539444427f7b

**Try it live:** join `lod-server-support.modrinth.gg` (Minecraft 26.2) with [Voxy](https://modrinth.com/mod/voxy) and this mod installed.

## Installation

> [!IMPORTANT]
> LSS goes on **both** the server **and** every client. Clients also need [Voxy](https://modrinth.com/mod/voxy) and Fabric API.

| | File | Goes in | Config generated at |
|---|---|---|---|
| **Fabric client** | `lod-server-support-fabric.jar` | `mods/` | `config/lss-client-config.json` |
| **Fabric server** | `lod-server-support-fabric.jar` | `mods/` | `config/lss-server-config.json` |
| **Paper / Purpur / Folia** | `lod-server-support-paper.jar` | `plugins/` | `plugins/LodServerSupport/lss-server-config.json` |

Restart after installing. Downloads are on [Modrinth](https://modrinth.com/plugin/lod-server-support); GitHub Releases mirror every version.

## Version Compatibility

Each Minecraft version has its own build, versioned `v<x.y.z>+mc<version>`; only the latest of each is listed. Folia uses the same JAR as Paper (experimental).

| Minecraft | LSS Version | Fabric | Paper | Folia | Voxy | Java |
|---|---|---|---|---|---|---|
| **26.2** | v0.9.0+mc26.2 | ✅ | ✅ | ✅ | 0.2.17-alpha+ | 25+ |
| **26.1.x** | v0.8.1+mc26.1 | ✅ | ✅ | ✅ | 0.2.14-alpha+ | 25+ |
| **1.21.11** | v0.8.1+mc1.21.11 | ✅ | ✅ | ✅ | 0.2.15-beta+ | 21+ |

> [!IMPORTANT]
> **Mixed versions are fine back to v0.4.x.** LSS translates between protocol versions in both directions, so old clients keep working against new servers and vice versa. Against anything older — or with the compat layers turned off — you simply get vanilla render distance and no error.

## How It Works

Voxy on its own can only build LOD data from chunks the client has already loaded, so distant terrain appears only where the player has been. LSS moves that work to the server: the client asks for the chunks it wants, the server reads them from disk (generating them if they don't exist yet) and streams the raw section data back, and Voxy renders it. The server then pushes updates as chunks change, so distant terrain stays current.

## Commands

**Server** — `/lsslod stats` for per-player transfer statistics, `/lsslod diag` for detailed diagnostics. With the LOD store: `/lsslod store status` (state, hit/miss counters, size), `/lsslod store invalidate all` (drop every stored column — they re-warm from normal serves), and on Fabric `/lsslod store backfill start|stop|status` to control the background pre-warm walk. Requires operator status (Fabric: gamemaster level; Paper: the `lss.admin` permission, default op).

**Client** (Fabric only) — `/lss clearcache` re-requests every chunk, `/lss diag` shows connection and throughput, `/lss trace` toggles a debug log under `logs/`.

## Configuration

Config is generated on first run at the paths in the install table above. The generated file documents **every** setting; the ones most worth knowing are:

| Setting | Default | Description |
|---------|---------|-------------|
| `enabled` | `true` | Enable LOD distribution |
| `lodDistanceChunks` | `256` | Max LOD distance in chunks |
| `bytesPerSecondLimitPerPlayer` | `15728640` | Per-player bandwidth cap (15 MiB/s), counted **before** compression |
| `bytesPerSecondLimitGlobal` | `62914560` | Total bandwidth cap across all players (60 MiB/s), counted **before** compression |
| `enableChunkGeneration` | `true` | Generate missing chunks on demand, so players see terrain nobody has visited |
| `generationConcurrencyLimitGlobal` | `32` | Max chunks generating server-wide at once |
| `generationConcurrencyLimitPerPlayer` | `16` | Max concurrently generating chunks per player |
| `lodStore` | `"off"` | **Recommended: set to `"full"`.** Keeps a compressed copy of every served LOD column in `<world>/lss-lod/` and serves repeat requests from it — far less CPU and disk work per chunk. Off by default only because it roughly doubles your world folder, which you should agree to rather than discover. See **Tuning** |
| `lodStoreBackfill` | `true` | Pre-warms the store with a low-priority background walk of your existing world, so the first player to arrive already gets fast serves. Inert unless `lodStore` is on, so enabling the store gets this too. Yields to players, pauses under load, resumes across restarts. Fabric only |
| `lodStoreMaxMB` | `0` | Size cap for the store. `0` = uncapped; set a value to bound it, and the oldest columns are evicted first |
| `useCompressedColumns` | `true` | Send LOD columns pre-compressed, which cuts CPU on both server and client. Clients that do not support it are served the old format automatically; `false` disables it entirely as a rollback |
| `useBackgroundReadPriority` | `true` | LOD disk reads yield to normal chunk loading, so streaming distant terrain doesn't delay the chunks players are actively walking into |
| `useBackgroundReadSplit` | `true` | Fabric: LOD reads only fetch raw bytes on Minecraft's shared chunk-IO thread; decompression and parsing run on LSS's own reader threads (set `false` to restore the old single-thread behavior) |
| `enableV16Compat` | `true` | Serve legacy v0.4.x–v0.6.x clients through a built-in translation layer. `false` requires every client to match the server's protocol |
| `enableV18Compat` | `true` | Serve v0.7.x–v0.8.x clients natively — a full session, minus only the column compression their client predates. `false` drops them to the `enableV16Compat` fallback |
| `xrayObfuscation` | `"auto"` | Anti-xray masking for LOD data. `"auto"` mirrors your anti-xray engine's own hidden-block list and height cutoff whenever one is detected (Paper's built-in, per world; the DrexHD AntiXray mod on Fabric). `"on"` forces masking, `"off"` disables it — LOD data then carries real ore locations even on anti-xray servers |
| `xrayHiddenBlocks` / `xrayMaxBlockHeight` | ore list / `64` | Fallback list and Y cutoff, used only when no engine settings can be adopted |

Masking applies to columns served after it activates — columns already cached by clients are not recalled, as with any anti-xray retrofit. Cave shapes and lighting are not hidden, matching packet-level anti-xray.

### Tuning

The defaults suit a typical server and most admins never need this section.

**To limit CPU, use the bandwidth and generation limiters.** LSS's cost is essentially how many columns per second it serves plus how many chunks it generates, and those two families cap exactly that:

- `bytesPerSecondLimitPerPlayer` / `bytesPerSecondLimitGlobal` bound the serve rate. They count **uncompressed** bytes on purpose, so compression doesn't quietly raise the real ceiling.
- `generationConcurrencyLimitGlobal` / `generationConcurrencyLimitPerPlayer` bound generation — by far the most expensive thing LSS can trigger, since it is worldgen. On a server exploring fresh terrain this dominates, and lowering it is the single biggest saving. `enableChunkGeneration: false` removes it entirely.

Lowering either costs *speed*, not correctness: LOD fills in more slowly, nothing breaks. Most other settings change *how* the work is done rather than how much, so they are the wrong lever for a CPU problem.

**Turn the LOD store on if you can spare the disk.** It is the single biggest performance win available here, and it is off by default only so that upgrading never doubles your world folder without you agreeing to it.

```json
"lodStore": "full"
```

That one key also enables the background warm-up. A repeat request is then answered from `<world>/lss-lod/` instead of re-reading and re-serializing the chunk: about 99% less read-and-serialize work, roughly 80% less total LSS CPU per served column, and ~29µs to serve where a disk read takes ~2ms.

The cost is disk — the store grows to roughly the size of your region files, so a 10 GB world adds about 7 GB. If that is too much, `lodStoreMaxMB` bounds it (oldest columns are evicted first and re-warm on demand). It is derived data, so deleting `lss-lod/` while the server is stopped is always safe.

## Redistribution

MIT-licensed — redistribution with attribution is welcome, and modpacks can reference the official Modrinth project directly. Per Modrinth's reupload policy: **[XANTHA](https://modrinth.com/user/XANTHA) on [Voxy Server Side](https://modrinth.com/plugin/voxy-server-side) has the copyright holder's explicit permission to distribute this mod, and derivatives of it, on Modrinth.**

## License

MIT
