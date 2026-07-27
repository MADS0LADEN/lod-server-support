### Bug Fixes

- **Anti-xray masking no longer leaks hidden ore ids in the raw data** — Masked LOD columns replaced hidden blocks visually but still carried their ids inside the chunk data's palette, so a modified client could read the ore list back out. Masked sections are now rebuilt from scratch and ship without any trace of the hidden blocks.
- **LOD sessions start reliably on Paper** — A client's very first chunk-request batch could arrive before the server finished registering the player and be silently dropped (self-healed within a second, but measurable at every join). The server now completes registration before inviting requests, so the first batch always lands.

### Compatibility

- **v0.8.0 releases for three Minecraft versions at once** — Alongside this MC 26.2 release, the same feature set ships as `v0.8.0+mc26.1` (MC 26.1–26.1.2, with Folia support) and `v0.8.0+mc1.21.11` from their support branches. Install the build matching your Minecraft version.
- **No protocol change** — v0.8.0 speaks the same LOD protocol as v0.7.x: clients and servers on v0.7.0–v0.7.3 keep working with v0.8.0 peers in both directions, and the compatibility layer for v0.4.x–v0.6.x clients is unchanged.
- **Future Voxy versions** — The Voxy render-distance detection now tolerates Voxy changing its internal config field type, so a Voxy update can no longer silently break the automatic LOD distance match.

### Performance

- **Faster LOD disk reads** — Chunk parsing on the disk-read path reuses its serialization setup instead of rebuilding it per chunk read.
