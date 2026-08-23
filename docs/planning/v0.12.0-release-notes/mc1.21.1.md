### New Features

- **Warm rejoins stop re-downloading terrain you already have** — Before reading or re-sending a column, the server now checks the region file's on-disk freshness (and, with the LOD store on, the store's own acquisition stamps) and answers "still current" instead. A rejoin whose freshness cache has aged out no longer re-streams gigabytes. This half is server-side: every client benefits, including ones still on v0.11.
- **Region summaries: one small frame replaces a million re-checks** — With client and server both on v0.12.0, entering a dimension now costs one compact frame (a few KB) listing which regions are unchanged; the client validates the clean bulk of its cached terrain from it instead of re-declaring every column over several minutes. Verify with `/lsslod diag`'s new `Summary:` line.
- **Stamped up-to-date responses** — The server timestamps its "your copy is current" answers, so terrain verified in one session stays verified in the next. This closes the loop where the same regions were re-checked on every single rejoin forever. Like the summary exchange, the stamps need both halves on v0.12.0 — they ride the client's summary subscription, so an older client (or one with `enableRegionSummarySync` off) simply keeps plain per-column revalidation. (On servers with the LOD store enabled, the stickiness lands one session later — the store's honest, older stamps take one extra verified rejoin to ratchet forward.)

- **Xaero's World Map fills in beyond render distance (opt-in)** — With Xaero's World Map installed on the client, downloaded LOD terrain can also be written into the world map, so the map records distant terrain instead of stopping at vanilla render distance. Off by default — enable it with the "Write LODs to Xaero's Map" toggle (see below). Works even without a LOD renderer: Xaero's Map plus this mod alone will download and map the terrain once enabled (that download is new for such installs). Client-side only, any server version, no hard dependency — the bridge switches off cleanly when Xaero isn't installed or its internals change.

### Performance

- **Far lighter client memory** — The client's terrain-tracking state moves to a compact section-leaf layout: roughly 6-10× smaller at the default 512-chunk LOD distance, with smoother scanning and fewer render-thread hitches when teleporting or changing dimensions.

### Bug Fixes

- **Replacing the mod jar on a running server no longer risks an unclean shutdown (Fabric)** — An internal diagnostics class could load for the first time during shutdown and fail if the jar had been swapped underneath, aborting the orderly stop and the final world save. It now loads only when that diagnostic is actually enabled.
- **The join slow start now finishes on fast connections** — The client's adaptive transfer ramp could park permanently below full speed on healthy links: its own request cycle, not the network, was the bottleneck, and the ramp misread that ceiling as the link's. The client now recognizes the pattern and completes the ramp within about 40 seconds of joining, restoring the full request budget (roughly 25% more LOD throughput on affected sessions). Client-side — works against any server version.

### Configuration

- **`enableRegionSummaries`** (server, default on) and **`enableRegionSummarySync`** (client, in `lss-client-config.json`, default on) — kill switches for the new summary exchange.
- **`enableQuadtreeScan`** (client, in `lss-client-config.json`, default on) — the new fast ring-scan path; disable to restore the per-position walk.

- **`enableXaeroMapBridge`** (client, in `lss-client-config.json`, default OFF; also the "Write LODs to Xaero's Map" toggle on the Sodium options page) — the Xaero map bridge above is opt-in: map writes are saved map data (chunks near you stay Xaero's own and Xaero redraws its tiles whenever you revisit an area, but distant LOD-drawn tiles persist until you do), so nothing is written until you turn the toggle on. On servers explored before this update, enable it and run `/lss clearcache` while connected to re-stream the terrain and backfill the map (a full re-download; the map fills progressively while it runs).

### Compatibility

- **The summary exchange needs BOTH halves on v0.12.0** — Mixed versions are fully compatible and quiet: a v0.12.0 client on an older server gets no answer and falls back to per-column revalidation, and an older client on a v0.12.0 server still gets the server-side freshness checks above — it just never asks for a summary.

### NeoForge notes

- This line (including the NeoForge build) is supported at the best-effort tier. No features were cut in this release.
- The NeoForge build carries the client half of the new behaviors too — region summaries and stamped verification work on NeoForge clients exactly as on Fabric.
- The Xaero map bridge works on NeoForge clients as well (with the NeoForge build of Xaero's World Map), but there is no in-game toggle there — enable it with `enableXaeroMapBridge` in `lss-client-config.json`.
