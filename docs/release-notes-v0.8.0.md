### Bug Fixes

- **Fixes black faces at chunk borders and treetops** — LOD data now carries the sky light for the air around terrain, so leaves and cliff sides no longer render black from one side at certain distances.
- **Anti-xray now fully covers LOD data** — On servers with anti-xray enabled, LOD chunk data could still be inspected to locate hidden ores. Masked LOD data now carries no trace of hidden blocks.
- **LOD loading starts reliably on Paper** — The first batch of LOD requests could be lost at join (recovered a second later). Sessions now start cleanly.

### Compatibility

- **Three Minecraft versions at once** — v0.8.0 also ships as `v0.8.0+mc26.1` (MC 26.1.x, experimental Folia support) and `v0.8.0+mc1.21.11` (experimental Folia support). Install the build matching your Minecraft version.
- **No protocol change** — v0.7.x clients and servers keep working with v0.8.0 unchanged, and the compatibility layer for v0.4.x–v0.6.x clients is untouched.
