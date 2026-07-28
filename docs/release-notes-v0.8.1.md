### Bug Fixes

- **Fixes a server memory leak that could crash busy servers** — Cache saves could queue up faster than they finished writing, each holding a full in-memory copy (reported as an out-of-memory crash within hours — issue #62). Saves now coalesce to the newest state, so memory stays bounded.
- **Fixes dark faces returning after block edits near LOD terrain** — Re-served columns lost their above-terrain sky light, quietly undoing the v0.8.0 black-faces fix. Sky light now survives every re-serve, and a WorldEdit-cleared column renders bright open sky instead of a black volume.
- **LOD now works in "Open to LAN" worlds** — Opening a single-player world to LAN never started the LOD service on MC 26.2; only the `/publish` command worked.
- **Bounded memory on long flights** — Per-player served-chunk tracking no longer grows for the whole session; far-behind entries are swept periodically.
- **Fair disk scheduling between players** — Under heavy disk load, one player could monopolize LOD disk reads while everyone else's terrain stalled. Service now rotates fairly every cycle.

### Performance

- **Much faster cache saves and loads** — Cache files now use buffered IO instead of one tiny write per entry; large saves drop from seconds to milliseconds, which also speeds up server shutdown and client disconnect.
