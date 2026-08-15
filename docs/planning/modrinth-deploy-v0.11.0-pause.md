# Modrinth test-server deploy — v0.11.0 dev build + manual-testing pause (stage F→G)

**Status: PREPARED, deploy pending user action** (2026-08-13). The archon panel
token is expired (401 — see the checklist escalation), so this deploy is
user-driven with everything below agent-prepared. The program PAUSES here; stage G
starts on explicit user sign-off (mega plan v1.4, the F→G pause row).
**v1.5-v1.7 addendum (2026-08-14): stage N (NeoForge, neoforge-support-plan.md
v1.2) is planned and the §0.6 sequencing is DECIDED — N precedes G, and v0.11.0
releases all four MC lines × three loaders simultaneously (1.21.1 incl. Paper).
Sign-off here covers the manual-testing pause only; stage N follows it, N-4
re-arms these gates (fresh per-line pre-flights + a fresh rig deploy +
re-validation window), then G tags all four lines in one session.**

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

1. `lsslod diag` — expect the v0.11.0 shape: `read_gate=<in>/<K>, gate_parked=,
   gate_stops=, gated=` always rendered (K = half the reader pool — the store is
   on; `gate_stops` is the retention counter since Amendment 2), `Dialects:`
   line, `store=full`,
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
  warm terrain, K-bounded on cold flights, `gate_stops=` climbing only under cold
  flood — `gated=` stays ~0, it counts rare overflow races since Amendment 2's
  router retention).
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

## 4b. Stage-N re-arm deploy (2026-08-14 — supersedes any earlier staged jar)

Stage N (NeoForge, PRs #167-#171) merged AFTER the original pause package, so
the rig should re-deploy from post-N main before the re-validation window:

- Jar: `fabric/build/libs/lod-server-support-fabric-0.11.0+26.2.jar` from the
  F-gate re-arm pre-flight (main @ cd905979, `CI=true`, `-Pmod_version=0.11.0`)
  — sha256 `8dfe11e9848646afa3181e0e58820429d9dbf0edf9086af1f7e8651b7306dc80`,
  7,910,986 B. Upload over `mods/lod-server-support-fabric.jar`, verify the
  byte size, restart (archon token EXPIRED — panel Start is the user's; RCON
  `stop` may not auto-restart, see CLAUDE.local.md).
- The N changes are wire-inert on Fabric by construction (xplat srcDir moves +
  delegating statics; N-1a's jar-diff proved class-byte identity vs main), so
  the §4 checklist above is unchanged — the re-arm run is a regression sweep,
  not a new-feature gate. Server-side expectations identical: boot clean,
  `read_path=moonrise-low`, store warm, `lsslod set` lists both boolean rows,
  `pingf=`/`paced=` in diag.
- Six-family `release_check.py --version 0.11.0` was green on this tree
  (2026-08-14) — the release pre-flight now REQUIRES `:neoforge:build` (the
  gate hard-fails without the neoforge LSS+VSS pair).

## 4c. Post-#179 re-pin (2026-08-14 — join slow start; supersedes §4b's jar)

Two pause-time merges landed after the stage-N re-arm: V-1 (PR #177 —
jar-byte-identical, no re-pin needed, proven 1419/1419) and **join slow start
(PR #179 — jar-AFFECTING, client half of the Fabric jar)**. Re-pinned package:

- Jar: `fabric/build/libs/lod-server-support-fabric-0.11.0+26.2.jar` from the
  post-#179 pre-flight (main @ 6aecd489, `CI=true`, `-Pmod_version=0.11.0`) —
  sha256 `1128a60b3500af18950fb6043ae223db4b13940b1c7e5a664befbf92ff728509`,
  7,916,654 B. Six-family `release_check.py --version 0.11.0` green on this
  tree. Same deploy mechanics as §4b (panel Start is the user's).
- The SERVER half is unchanged by #179 (the governor is client-side) — §4b's
  server expectations carry over verbatim.
- **Client-side receipts added to the checklist** (plan §6's obligation; the
  same jar goes in the Prism instance): the Sodium screen shows "Slow Start
  on Join" (default on); a rig join walks `governed=ramp@…` → `open` in
  ~35 s on the fast path (`/lss diag`); elytra-from-join (the spawnkit case)
  must NOT park the ramp — the 62 ms jitter-gated movement hold is the
  specific check; a warm rejoin revalidates promptly (the byte-free answered
  rung — not parked at 2 col/s).

## 5. Found-bug loop (from the plan — verbatim rules)

A fix re-opens the owning stage's gates (its tier set + its soaks), redeploys,
and re-enters the pause. Stage G's 26.1 base re-anchors to main at the last
pre-G merge (the "F merge" pin is a floor, not a fixture). The F gauntlet
re-runs only if the fix touched server serve paths (the stage-owner's call,
logged).

## 6. Sign-off

Stage G (the support-line delta-ports + tri-release) does NOT start until the
user signs off this pause on the manual-verification checklist.
