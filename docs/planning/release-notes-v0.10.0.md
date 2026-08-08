# v0.10.0 release notes — DRAFT (D1; the D2 tag text is cut from this)

Status: prepared during D1 (2026-08-08). The mega-plan §3.2 is the spec; numbers
cite the measurement ledger (B5 closing A/B, C0 settling, C6 arms, task-#8 gate).
Format per CLAUDE.md's release-notes rules (player-focused, present tense, no
version heading). The tri-line D3 re-ports carry their own flavor edits.
The move-desync tracer is deliberately ABSENT from these notes: the TRACER
plan's user decision gives it no documented surface (activation is a marker
file/system property only) — recorded here so the omission reads as chosen.

---

### New Features

- **Cross-version LOD serving (protocol 20)** — A client and server on different
  supported Minecraft versions can now exchange LODs: columns travel as
  version-independent block identities and translate to whatever your client
  runs. Supported pairs: any two of 26.2, 26.1, and 1.21.11 running v0.10.0.
  Blocks that don't exist on your version render via a configurable fallback
  (`unknownBlockFallback`, default stone). Older LSS clients keep working: v0.9.x
  gets a NEW native rung (`enableV19Compat`), and v0.7.x–v0.8.x keep their
  existing ones.
- **Via-proxy mismatch guard** — When ViaVersion/ViaBackwards lets a client on
  a different MC version join with an OLDER LSS installed, the server now
  detects the mismatch and declines the LOD session cleanly instead of serving
  columns that client would decode as garbage (a vanilla client has no LSS and
  never starts a session). **Known hole:** when Via runs on a proxy (Velocity/Bungee)
  in front of the server, the server cannot see the client's real version — such
  setups should not rely on the guard.
- **Vanilla-first transport yield** (`lodYieldsToVanillaTransport`, default
  `false`) — When enabled, LOD sending pauses while a player's connection is
  backed up, so LSS never deepens the queue ahead of vanilla's own chunk
  packets. Ships off pending live validation; behind a buffering proxy the gate
  is best-effort (it under-yields, never over-yields).

### Performance

- **LOD disk reads leave Minecraft's chunk-IO thread almost entirely** — On
  Fabric's vanilla read path, LSS now only fetches raw bytes on the shared IO
  thread; decompression and parsing run on LSS's own reader pool
  (`useBackgroundReadSplit`, default on). This removes the main historic cause
  of LOD serving slowing vanilla chunk loads under pressure.
- **The background store warm-up is cheaper net-of-everything** — the perf
  round cut the walk's parse/hash work by ~21%, the new cross-version encode
  added roughly 17% of it back, and deposit throughput is unchanged
  throughout: net, warming a store costs modestly less CPU than v0.9.x.
- **Selective chunk parsing (Fabric)** — disk serves now materialize only the
  chunk data LOD serving needs, cutting the targeted allocation-churn classes
  roughly in half on the read path. (Net per-column allocation is still higher
  than v0.9.x — the cross-version format costs more than the parsing saved;
  see the cost item below.)
- **The freshness cache tracks ~13× more terrain per MB** — its default (auto)
  sizing now uses ~2.5× less RAM while remembering ~5× more columns, so large
  view distances and roaming players stop thrashing it. Existing cache files
  migrate automatically at first boot.
- **The honest cost of cross-version columns** — Encoding version-independent
  identities makes serving a cold column cost roughly 12–16% more CPU than
  v0.9.x (composed from the separately measured arms; it was ~18% before a
  follow-up optimization) with per-column allocation and wire/store bytes
  ~10% higher. Warm store serves and backfill throughput are unaffected. This
  buys the entire cross-version feature.
- **Store rows are integrity-hashed with CRC32C** (hardware-accelerated),
  replacing a slower software hash on the store's write path.

### Configuration

- **`lodYieldsToVanillaTransport`** (server, default `false`) — the transport
  yield above.
- **`useBackgroundReadSplit`** (server, default `true`) — the Fabric read split
  above; `false` restores the old single-thread read.
- **`useSelectiveNbtParse`** (server, default `true`) — parse only the chunk
  data LOD serving needs; `false` restores the full parse.
- **`enableV19Compat`** (server, default `true`) — serve v0.9.x clients natively
  on their own wire format.
- **`enableViaMismatchGuard`** (server, default `true`) — the Via guard above.
- **`unknownBlockFallback`** (client, default `"minecraft:stone"`) and
  **`crossVersionBlockFallbacks`** (client, default empty) — what a
  cross-version column renders when a block has no local equivalent, globally
  and per-block.
- **`perDimensionTimestampCacheSizeMB`** keeps its `0` = auto default, but auto
  now derives from the new tile layout (~2.5× less RAM for ~5× the coverage at
  the same setting).

### Store

- **One-time background migration** — On first boot, existing LOD store rows
  migrate to the cross-version format in a paced background walk (this is a
  migration, NOT a rebuild: nothing is re-read from region files, and serving
  continues meanwhile). Store rows grow ~10% with the new format; if you run a
  `lodStoreMaxMB` cap, the same cap now holds ~10% fewer columns (evicted
  columns re-warm on demand, as always).
- A modded block/biome registry change still drops and rebuilds the store (the
  fingerprint guard) — migration never runs on drifted registries.

### Notes

- Folia support remains **experimental** (single-player soak validated;
  concurrent multi-region ingress untested).
