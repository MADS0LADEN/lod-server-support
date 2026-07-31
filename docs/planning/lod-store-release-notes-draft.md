# LOD store — release notes DRAFT (for the eventual tag; user-facing format)

### New Features

- **Persistent LOD store (opt-in)** — New `lodStore` server option (`"off"` by
  default): `"full"` keeps served LOD columns in a SQLite database inside the world
  folder, so rejoining players get their distant terrain served from the store at
  microsecond latency instead of re-reading and re-serializing region files —
  measured ~18-25× faster per column with ~96% of the disk-path CPU eliminated on
  warm joins. The store is derived data: it rebuilds itself automatically on any
  version/mask change, and deleting `world/lss-lod/` is always safe.
- **Background store backfill (opt-in)** — `lodStoreBackfill` (default off) or
  `/lsslod store backfill start|stop|status` walks the whole world at low priority
  and pre-warms the store, yielding to players and tick health (measured dropping to
  a third of its rate cap under load). Resumes where it left off across restarts.
- **Store admin commands** — `/lsslod store status` (one-line health) and
  `/lsslod store invalidate all` (drop every stored row; the remediation lever if
  LODs ever look stale — the store re-warms from normal serving).

### Configuration

- **`lodStore`** ("off") — off / memory / full. **`lodStoreMemoryMB`** (64) — the
  memory-mode cache size. **`lodStoreMaxMB`** (2048) — on-disk size cap; above it the
  oldest entries are evicted automatically. **`lodStoreBackfill`** (false).
  **`lodStoreResweepSeconds`** (0 on Fabric, 300 on Paper) — Paper's periodic
  freshness re-check for edits its events cannot see.

### Notes for admins

- Backups: the store lives at `world/lss-lod/` and can be excluded from backups —
  it rebuilds from your region files. A restored backup is detected automatically
  (region-header timestamps) and stale entries are dropped at startup.
- Paper: edits made without Bukkit events (console `setblock`, some plugins) are
  caught by the periodic re-sweep within ~one autosave + one sweep cycle.
- Folia: the store is NOT yet validated on Folia and stays effectively off there.

(Also carry the standing backlog when tagging: #70 Moonrise retarget note, #73
`enableIngestBackpressure`, #74 transcode + sendQueue 1024, #75 Moonrise reads.)
