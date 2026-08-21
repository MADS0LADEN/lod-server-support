### New Features

- **Region summaries: warm rejoins no longer re-download the world** — When you reconnect to a server you've explored, the server now sends a compact freshness summary of each region (a few KB), and your client validates its cached terrain against it instead of re-requesting every chunk. A converged rejoin that used to re-transfer gigabytes now costs kilobytes.
- **Stamped up-to-date responses** — The server timestamps its "your copy is current" answers, so terrain verified on one session stays verified on the next. This closes the loop where the same regions were re-checked on every single rejoin forever.
- **Far lighter client memory** — The client's terrain-tracking state moved to a compact section-leaf layout: roughly 6-10× less memory at high LOD distances, and smoother scanning with fewer render-thread hitches when teleporting or changing dimensions.

### Bug Fixes

- **Exotic dimension no longer able to break server startup** — A dimension whose identity lookup throws could previously escape the safety net during service start. It now degrades only that dimension's freshness tracking.
- **Server shutdown hardened against mid-session jar swaps** — Diagnostic telemetry no longer loads classes during shutdown, which could abort the final world save if the mod jar had been replaced while the server ran.

### Configuration

- **`enableRegionSummaries`** (server, default on) and **`enableRegionSummarySync`** (client, default on) — kill switches for the new summary exchange.
- **`enableQuadtreeScan`** (client, default on) — the new fast ring-scan path; disable to restore the per-position walk.

### Compatibility

- **The new behaviors need BOTH halves on v0.12.0** — the summary exchange and stamped verification only run when client and server both carry this version. Mixed versions are fully compatible: a v0.12.0 client on an older server (or the reverse) simply doesn't exchange the new messages and behaves like v0.11. Folia support remains **experimental**. This line is supported at the "correct, not perfect" tier.
