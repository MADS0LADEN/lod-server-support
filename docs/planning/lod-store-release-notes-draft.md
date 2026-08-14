# LOD store — release notes DRAFT (for the eventual tag; user-facing format)

**Status: SUPERSEDED — v0.9.0 shipped its own notes** (v0.9.0-release-notes.md is the
authority; `lodStoreMemoryMB` advertised below was RETIRED 2026-08-03 and `lodStore`
defaults changed again 2026-08-08). Kept as a draft artifact only.

### New Features

- **Persistent LOD store (opt-in)** — New `lodStore` server option (`"off"` by
  default): `"full"` keeps served LOD columns in a SQLite database inside the world
  folder, so rejoining players get their distant terrain served from the store at
  microsecond latency instead of re-reading and re-serializing region files —
  measured ~18-25× faster per column with ~96% of the disk-path CPU eliminated on
  warm joins. The store is derived data: it rebuilds itself automatically on any
  version/mask change, and deleting `world/lss-lod/` is always safe.
- **Background store backfill (opt-in, Fabric)** — `lodStoreBackfill` (default off)
  or `/lsslod store backfill start|stop|status` walks the whole world at low
  priority and pre-warms the store, yielding to players and tick health (measured
  dropping to a third of its rate cap under load). Resumes where it left off across
  restarts. Paper does not have the backfill yet.
- **Store admin commands** — `/lsslod store status` (one-line health) and
  `/lsslod store invalidate all` (drop every stored row AND the backfill progress;
  the remediation lever if LODs ever look stale — the store re-warms from normal
  serving, and a re-run backfill re-walks the world). Both platforms; the backfill
  verbs are Fabric-only.

### Configuration

- **`lodStore`** ("off") — off / memory / full. **`lodStoreMemoryMB`** (64) — the
  memory-mode cache size. **`lodStoreMaxMB`** (0 = **no cap, the default**) — expect
  the store to grow to ≈2× your world folder when fully warmed; set a value in MB
  (64..32768) to bound it instead — above the cap the oldest entries are evicted
  automatically (one log notice per boot; running totals in `/lsslod store status`)
  and the background backfill stops at the cap rather than churn it.
  **`lodStoreBackfill`** (false, Fabric).
  **`lodStoreBackfillColumnsPerSecond`** (100, clamped 10..1000, Fabric) — how fast
  the background backfill walks the world; raise it on idle servers to warm the
  store sooner, lower it to reduce IO pressure. **`lodStoreBackfillTickCeilingMillis`**
  (45, clamped 20..50, Fabric) — the backfill pauses whenever the server's average
  tick time exceeds this. **`lodStoreResweepSeconds`** (0 on Fabric, 300 on Paper) —
  Paper's periodic freshness re-check for edits its events cannot see.

### Notes for admins

- Backups: the store lives at `world/lss-lod/` and can be excluded from backups —
  it rebuilds from your region files. A restored backup is detected automatically
  (region-header timestamps) and stale entries are dropped at startup.
- Paper: edits made without Bukkit events (console `setblock`, some plugins) are
  caught by the periodic re-sweep within ~one autosave + one sweep cycle.
- Adding, removing, or updating mods/datapacks that register blocks or biomes
  rebuilds the store automatically on the next start (stored data encodes registry
  ids, which such changes shift) — expect one cold rejoin after a mod change.
  Upgrading from an earlier in-development store build also rebuilds once (the
  registry fingerprint format changed to close a same-size mod-swap blind spot).
- Folia: the store is UNTESTED on Folia — leave `lodStore` at `"off"` there. (There
  is no engine-level Folia gate; on this Minecraft version Folia refuses the plugin
  jar entirely, but do not carry this setting onto a future Folia build.)

(Also carry the standing backlog when tagging: #70 Moonrise retarget note, #73
`enableIngestBackpressure`, #74 transcode + sendQueue 1024, #75 Moonrise reads.)
