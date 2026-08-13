# Modrinth test-server deploy — v0.11.0 dev build + manual-testing pause (stage F→G)

**Status: PREPARED, deploy pending user action** (2026-08-13). The archon panel
token is expired (401 — see the checklist escalation), so this deploy is
user-driven with everything below agent-prepared. The program PAUSES here; stage G
starts on explicit user sign-off (mega plan v1.4, the F→G pause row).

## 1. What to deploy

The stage-F pre-flight jar (built `CI=true -Pmod_version=0.11.0` from the F tree):

    fabric/build/libs/lod-server-support-fabric-0.11.0+26.2.jar

Upload over SFTP to `mods/lod-server-support-fabric.jar` (overwrite; verify the
byte size matches the local file afterwards), then Restart via the panel:

    set -a; source <(tr -d '\r' < ~/.bot.env); set +a
    curl -sk -u "$MODRINTH_SFTP_USERNAME:$MODRINTH_SFTP_PASSWORD" \
      -T fabric/build/libs/lod-server-support-fabric-0.11.0+26.2.jar \
      "sftp://$MODRINTH_SFTP_HOST/mods/lod-server-support-fabric.jar"
    # Restart: panel Stop/Start button (the archon curl needs a fresh token/HAR)

## 2. Config refresh (R-8, amended by user direction 2026-08-13 — DELETE and regenerate)

The standing rig config predates two default rounds and would mask the shipped
v0.11.0 experience. Per the user's direction, DELETE
`/config/lss-server-config.json` on the server before the restart and let the
mod regenerate it — a brand-new file takes the full fresh-install defaults,
including `lodStore: "on"` via the fresh-create hook (distance 300, mb caps
25/75, gen caps 40/40, `maxConcurrentDiskReads` AUTO = half-pool, `farPlayers`
"on", `lodYieldsToVanillaTransport` true).

**Known delta vs the old rig config**: `lodStoreMaxMB` regenerates as `0` =
UNCAPPED (the rig previously capped at 10240 MB; the store DB is ~4 GB). The
2 GiB free-space floor still bounds the backfill, but re-add
`"lodStoreMaxMB": 10240` later if the host's disk quota matters. The old
`lodDistanceChunks: 256` becomes 300 (slightly larger discs), and the legacy
byte-denominated bandwidth keys are gone in favor of the new defaults.

## 3. After restart — verification (RCON, ~/rcon.py; no leading slash)

1. `lsslod diag` — expect the v0.11.0 shape: `read_gate=<in>/<K>` always rendered
   (K = half the reader pool — the store is on), `Dialects:` line, `store=full`,
   NO `FarPlayers:` line while nobody is subscribed (the conditional slot).
2. `lsslod store status` — `state=ok`, counters climbing on a warm rejoin.
3. `lsslod set` — lists 7 keys incl. `farPlayers` + `farPlayersMaxDistanceBlocks`.
4. Log check after pulling `latest.log` over SFTP:
   `grep -iE "warn|error" latest.log` — the store schema is already v4 (the rig
   ran v0.10.0), so NO migration walk should start; backfill should report its
   resume point or "0 region(s) to process".

## 4. Manual test content (the user's list, from the mega plan)

- **Warm-join LOD flow at the new defaults** — join with a Voxy+LSS client;
  store serves at full rate; `read_gate=` behaves under real play (in-use low on
  warm terrain, K-bounded on cold flights, `gated=` only under cold flood).
- **`/lsslod set` round-trips** — `set lodDistanceChunks 96` (live re-push —
  LODs shrink), back to 300; `set farPlayers off` then `on`; values persist in
  the config file; `/lsslod help` renders.
- **`/lss reset`** — LODs visibly disappear then rebuild live from the server
  re-stream (needs Voxy 0.2.18+ client-side).
- **Backfill status** — `/lsslod store backfill status` shows the remaining
  regions/columns estimate.
- **`/lsslod diag` line sanity** — every line renders, no `mem=` token (no
  degraded boot), bandwidth `total`/`wire` gap present (compression).
- **Far players ONLY IF a second player joins** — one player cannot observe
  proxies (the rig auto-pauses empty). E2/E3's rig sessions are the primary FARP
  evidence; this pause is defense-in-depth. With two players: proxies appear
  beyond render distance, name tags, Share-My-Position opt-out works, `/lsslod
  diag` grows the `FarPlayers:` line.

## 5. Found-bug loop (from the plan — verbatim rules)

A fix re-opens the owning stage's gates (its tier set + its soaks), redeploys,
and re-enters the pause. Stage G's 26.1 base re-anchors to main at the last
pre-G merge (the "F merge" pin is a floor, not a fixture). The F gauntlet
re-runs only if the fix touched server serve paths (the stage-owner's call,
logged).

## 6. Sign-off

Stage G (the support-line delta-ports + tri-release) does NOT start until the
user signs off this pause on the manual-verification checklist.
