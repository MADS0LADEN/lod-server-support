# v0.11.0 release notes (tag annotation + Modrinth changelog)

Short by design (user decision 2026-08-15): one bullet per headline feature,
user/operator-facing only. Per-line tags may append line-specific caveats at
G-4 (the 1.21.1 NeoForge tier/cut rule).

---

### Highlights

- **Minecraft 1.21.1 and NeoForge support** - Adds the MC 1.21.1 line, including a NeoForge server build (1.21.1 only, best-effort tier).
- **Far Players** - See other players far beyond render distance as player models in the LOD terrain, with privacy controls on both server and client.
- **Much better on slow connections** - LOD streaming now paces itself to the connection and lets vanilla traffic go first, so slow links stay smooth and playable while LODs load.
- **Full backwards compatibility** - All servers and clients from v0.4.0 onward keep working with v0.11.0.
- **New commands** - `/lsslod set` changes server settings live without a restart; `/lss reset` on the client wipes this server's LODs and re-streams them fresh.
