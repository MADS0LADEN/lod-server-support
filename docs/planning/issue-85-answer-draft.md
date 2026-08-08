# Issue #85 answer — DRAFT (post ONLY at the real tri-release; D3 prep)

v0.10.0 makes cross-Minecraft-version LOD serving real. The concrete pair you
need: the SERVER on v0.10.0 or later, and EVERY cross-version player's CLIENT
on its own Minecraft version's v0.10.0 build (all three lines — 26.2, 26.1,
1.21.11 — released together at the same feature level). A v0.9.x-or-older
client on a different MC version still cannot decode the columns; through Via
it is now cleanly declined instead of receiving garbage (with one documented
hole: Via running on a Velocity/Bungee proxy is invisible to the server-side
guard). Blocks that don't exist on the client's version render via the
configurable `unknownBlockFallback` (default stone) / `crossVersionBlockFallbacks`.
