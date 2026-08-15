# LOD Server Support

Streams LOD (Level of Detail) chunk data from your server to connected clients, so [Voxy](https://modrinth.com/mod/voxy) can render terrain hundreds of chunks out — including terrain the player has never visited.

Supports **Fabric** clients, and **Fabric**, **Paper**, **Purpur** and **Folia** servers — plus **NeoForge** servers on the MC 1.21.1 line. NeoForge support is best-effort tier, and v0.11.0 ships it for 1.21.1 only (the one version where a community Voxy build exists for the client half).

https://github.com/user-attachments/assets/721fb344-890e-4e03-ab36-539444427f7b

**Try it live:** join `lod-server-support.modrinth.gg` with [Voxy](https://modrinth.com/mod/voxy) and this mod installed. The server runs Minecraft 26.2, but clients on **any supported version** — 26.2, 26.1.x, 1.21.11, or 1.21.1 — can join and get full LOD streaming (cross-version columns, with ViaVersion bridging the Minecraft protocol).

## Installation

> [!IMPORTANT]
> LSS goes on **both** the server **and** every client. Clients also need [Voxy](https://modrinth.com/mod/voxy) and Fabric API.

| | File | Goes in | Config generated at |
|---|---|---|---|
| **Fabric client** | `lod-server-support-fabric.jar` | `mods/` | `config/lss-client-config.json` |
| **Fabric server** | `lod-server-support-fabric.jar` | `mods/` | `config/lss-server-config.json` |
| **Paper / Purpur / Folia** | `lod-server-support-paper.jar` | `plugins/` | `plugins/LodServerSupport/lss-server-config.json` |
| **NeoForge server** (MC 1.21.1 only) | `lod-server-support-neoforge.jar` | `mods/` | `config/lss-server-config.json` |

Restart after installing. Downloads are on [Modrinth](https://modrinth.com/plugin/lod-server-support); GitHub Releases mirror every version.

## Version Compatibility

Each Minecraft version has its own build, versioned `v<x.y.z>+mc<version>`; only the latest of each is listed. Folia uses the same JAR as Paper (experimental).

| Minecraft | LSS Version | Fabric | NeoForge | Paper | Folia | Voxy | Java |
|---|---|---|---|---|---|---|---|
| **26.2** | v0.11.0+mc26.2 | ✅ | — | ✅ | ✅ | 0.2.17-alpha+ | 25+ |
| **26.1.x** | v0.11.0+mc26.1 | ✅ | — | ✅ | ✅ | 0.2.14-alpha+ | 25+ |
| **1.21.11** | v0.11.0+mc1.21.11 | ✅ | — | ✅ | ✅ | 0.2.15-beta+ | 21+ |
| **1.21.1** | v0.11.0+mc1.21.1 | ✅ | ✅ (server) | ✅ | — | community fork | 21+ |

> [!IMPORTANT]
> **Mixed versions are fine back to v0.4.x.** LSS translates between protocol versions in both directions, so old clients keep working against new servers and vice versa. Against anything older — or with the compat layers turned off — you simply get vanilla render distance and no error.

## How It Works

Voxy on its own can only build LOD data from chunks the client has already loaded, so distant terrain appears only where the player has been. LSS moves that work to the server: the client asks for the chunks it wants, the server reads them from disk (generating them if they don't exist yet) and streams the raw section data back, and Voxy renders it. The server then pushes updates as chunks change, so distant terrain stays current.

## Commands

**Server** — `/lsslod stats` for per-player transfer statistics, `/lsslod diag` for detailed diagnostics, `/lsslod help` (also the bare `/lsslod`) for the full verb list. **Runtime settings**: `/lsslod set` lists the runtime-settable config keys with current values; `/lsslod set <key> <value>` applies a change immediately AND persists it to `lss-server-config.json` — values are clamped exactly like the config file, and a `lodDistanceChunks` change is pushed to connected current-version clients live (older clients pick it up on rejoin). With the LOD store: `/lsslod store status` (state, hit/miss counters, size), `/lsslod store invalidate all` (drop every stored column — they re-warm from normal serves), and on Fabric `/lsslod store backfill start|stop|status` to control the background pre-warm walk (`status` shows progress plus a remaining regions/columns estimate). Requires operator status (Fabric: gamemaster level; Paper: the `lss.admin` permission, default op).

**Client** (Fabric only) — `/lss clearcache` re-requests every chunk, `/lss reset` wipes Voxy's LOD store AND the LSS cache for the current server (LODs visibly disappear, then rebuild live as the server re-streams everything; if Voxy's ingest is disabled — its config toggle or a replay — LODs stay empty until it is re-enabled), `/lss diag` shows connection and throughput, `/lss trace` toggles a debug log under `logs/`. Without an active LSS session `/lss reset` requires `/lss reset confirm` — there is no server to re-stream from, so the wipe only refills from vanilla chunk loading (and the LSS cache is then cleared for ALL servers, not just one).

The LSS client cache lives in a `.lss/` folder in the game directory on fresh installs (the `.voxy` convention); installs that already have a `config/lss/cache/` keep using it.

## Configuration

Config is generated on first run at the paths in the install table above. The generated file documents **every** setting; the ones most worth knowing are:

| Setting | Default | Description |
|---------|---------|-------------|
| `enabled` | `true` | Enable LOD distribution |
| `lodDistanceChunks` | `300` | Max LOD distance in chunks |
| `mbPerSecondLimitPerPlayer` | `25.0` | Per-player bandwidth cap in MiB/s (decimals like `12.5` work), counted **before** compression |
| `mbPerSecondLimitGlobal` | `75.0` | Total bandwidth cap across all players in MiB/s, counted **before** compression |
| `enableChunkGeneration` | `true` | Generate missing chunks on demand, so players see terrain nobody has visited |
| `dirtyBroadcastIntervalSeconds` | `10` | How often terrain edits are pushed to connected clients. `0` disables the pushes entirely — clients then refresh only on rejoin or their own re-requests |
| `generationConcurrencyLimitGlobal` | `40` | Max chunks generating server-wide at once |
| `generationConcurrencyLimitPerPlayer` | `40` | Max concurrently generating chunks per player |
| `lodStore` | `"on"` (new installs) | Keeps a compressed copy of every served LOD column in `<world>/lss-lod/` and serves repeat requests from it — far less CPU and disk work per chunk. The cost: it roughly doubles your world folder. Generated as `"on"` for brand-new servers; on an upgraded server whose config file doesn't have the key, it stays `"off"` until you enable it. See **Tuning** |
| `lodStoreBackfill` | `true` | Pre-warms the store with a low-priority background walk of your existing world, so the first player to arrive already gets fast serves. Inert unless `lodStore` is on. Yields to players, pauses under load, resumes across restarts. Fabric only |
| `lodStoreMaxMB` | `0` | Size cap for the store. `0` = uncapped; set a value to bound it, and the oldest columns are evicted first |
| `maxConcurrentDiskReads` | `0` (auto) | Caps how many expensive region-file reads (cold, un-stored chunks) run at once, so server CPU stays bounded no matter how high you raise bandwidth — store-served LODs never consume its capacity. Auto = half the reader pool with the store on, no limit with it off. Symptom when it binds: LOD holes fill at a steady bounded rate while `/lsslod diag` shows `read_gate=K/K` with `gate_stops=` climbing (`gated=` stays ~0 — it counts rare overflow races); raise the value if you have CPU headroom |
| `enableV16Compat` | `true` | Serve legacy v0.4.x–v0.6.x clients through a built-in translation layer. `false` requires every client to match the server's protocol |
| `enableV18Compat` | `true` | Serve v0.7.x–v0.8.x clients natively — a full session, minus only the features their client predates. `false` drops them to the `enableV16Compat` fallback |
| `enableV19Compat` | `true` | Serve v0.9.x clients natively. `false` drops them to the `enableV16Compat` fallback |
| `xrayObfuscation` | `"auto"` | Anti-xray masking for LOD data. `"auto"` mirrors your anti-xray engine's own hidden-block list and height cutoff whenever one is detected (Paper's built-in, per world; the DrexHD AntiXray mod on Fabric). `"on"` forces masking, `"off"` disables it — LOD data then carries real ore locations even on anti-xray servers |
| `xrayHiddenBlocks` / `xrayMaxBlockHeight` | ore list / `64` | Fallback list and Y cutoff, used only when no engine settings can be adopted |

**Far players (new in v0.11.0):** the server can stream distant players' positions to subscribed clients (`farPlayers` mode `off`/`opt-in`/`on`, max ring `farPlayersMaxDistanceBlocks` default 2048, plus a `farPlayersExclude` list and the Paper permission `lss.farplayers.hidden`). Privacy notes: even the 2048 default reveals player positions far beyond vanilla range — use `opt-in` mode if that matters on your server; and any player on an older LSS (or vanilla) client is a potential far-player *target* with no client-side opt-out — their protections are the server-side mode, exclude list, and permission node. The feature is **on by default** (server mode `on`, clients subscribe automatically); clients control their own participation with the "Share My Position" option (off = invisible beyond normal render distance while connected through LSS). Vanish plugins that publish the standard `"vanished"` metadata (SuperVanish, PremiumVanish, EssentialsX) are honored on Paper; plugins that hide players only via `hideEntity` are not detected — use the permission node or exclude list for those. Mounted players render with their mount (any vanilla entity type; modded mounts a client doesn't know degrade to an unmounted player model, never a crash). **SeeU coexistence:** if the SeeU mod is installed on the client, LSS stops drawing and receiving far players automatically to avoid showing every distant player twice (your own "Share My Position" setting still applies — SeeU's presence does not opt you out of being seen) — set `farPlayersWithSeeU: true` (or "Prefer LSS Far Players" in the Sodium screen) to use LSS instead; note that on an LSS-only server, SeeU alone shows no far players, so prefer LSS there.

Masking applies to columns served after it activates — columns already cached by clients are not recalled, as with any anti-xray retrofit. Cave shapes and lighting are not hidden, matching packet-level anti-xray.

Older config files keep working unchanged: the byte-denominated `bytesPerSecondLimitPerPlayer` / `bytesPerSecondLimitGlobal` keys are still honored (the `mb*` keys win if both are present) and migrate to the new keys on the next start, `"lodStore": "full"` means the same as `"on"`, and a file without a `lodStore` key keeps the store off — upgrading never changes your disk footprint without you asking.

### Tuning

**Disk: the LOD store.** It is the biggest performance win available — repeat requests are served from `<world>/lss-lod/` for a fraction of the CPU and disk work — so new servers generate with it on. On an upgraded server it stays off until you set `"lodStore": "on"` yourself. The cost is that a fully warmed store roughly doubles your world folder; `lodStoreMaxMB` bounds it (oldest columns evicted first, re-warmed on demand). It is derived data — deleting `lss-lod/` while the server is stopped is always safe.

**CPU: the bandwidth and generation limiters.** LSS's cost is essentially how many columns per second it serves plus how many chunks it generates:

- `mbPerSecondLimitPerPlayer` / `mbPerSecondLimitGlobal` bound the serve rate. They count **uncompressed** data on purpose, so compression doesn't quietly raise the real ceiling.
- `generationConcurrencyLimitGlobal` / `generationConcurrencyLimitPerPlayer` bound generation — by far the most expensive thing LSS can trigger, since it is worldgen. On a server exploring fresh terrain, lowering these is the single biggest saving; `enableChunkGeneration: false` removes it entirely.

Lowering either costs *speed*, not correctness: LOD fills in more slowly, nothing breaks.

**Old clients cost more.** Serving outdated LSS clients makes the server do extra translation work and forgoes the current protocol's efficiency wins. If everyone on your server runs a current client, setting `enableV16Compat`, `enableV18Compat`, and `enableV19Compat` to `false` can improve performance — players on old versions then simply get vanilla render distance.

**Network compression.** Vanilla deflates every packet above `network-compression-threshold`, including LSS's already-compressed column frames — measurable overhead for almost no size win. Don't raise the global threshold to avoid it (vanilla chunk bandwidth pays far more than you save); if it matters, terminate compression at a proxy like Velocity instead.

## License

MIT

## Redistribution

Redistribution with attribution is welcome, and modpacks can reference the official Modrinth project directly. Per Modrinth's reupload policy: **[XANTHA](https://modrinth.com/user/XANTHA) on [Voxy Server Side](https://modrinth.com/plugin/voxy-server-side) has the copyright holder's explicit permission to distribute this mod, and derivatives of it, on Modrinth.**
