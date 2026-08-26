# v0.13.0 — manual-test run sheet + release staging (2026-08-26)

Everything below is STAGED; nothing has been tagged or published. The release
run is yours, after the manual pass. State at handover: main merged (PR #245);
four port PRs merged to the canonical line branches; all gates green per line
(T1/T2/checker/pre-flight/release_check/CI incl. Tier 3 where the line has it;
fresh-backfill smokes on 26.1, 1.21.11, 1.21.1; hybrid-boundary green on main);
2-Opus port reviews folded (0 code MAJORs on every line); the five local
v0.12.1 tags DELETED.

## 1. Manual test — one line at a time (server ports collide across lines)

Per line: start the test servers from that line's worktree, launch the matching
Prism instance, join :25564 (Fabric) and/or :25566 (Paper), fly around, run
`/lss diag`. Stop the servers (Ctrl+C) before switching lines.

The rigs are the dedicated `lss-test-*` Prism instances (correction 2026-08-26:
an earlier draft mapped your general-purpose instances — those are untouched;
`26.1.2` and `1.21.11` briefly got rc jars by mistake and were restored to
their original versions from the published releases).

| line | worktree (servers: `./test-server.sh`) | Prism instance(s), all carrying the 0.13.0 rc |
|---|---|---|
| 26.2 | ~/projects/lss-main-deploy | lss-test-26.2 (Fabric + Voxy 0.2.18) |
| 26.1 | ~/projects/lss-port-26.1 | lss-test-26.1 (Fabric + Voxy 0.2.18-26.1.2) |
| 1.21.11 | ~/projects/lss-port-1.21.11 | lss-test-1.21.11 (Fabric + Voxy 0.2.9) |
| 1.21.10 | ~/projects/lss-port-1.21.10 | lss-test-1.21.10 (Fabric + Voxy 0.2.9) |
| 1.21.1 | ~/projects/lss-port-1.21.1 | lss-test-1.21.1 (Fabric) AND lss-test-neo-1.21.1 (NeoForge + fork Voxy; the standard neoforge jar — the nosqlite workaround died with the v0.11.0 jarJar fix) |

### What to look for (the new surface)

- Near terrain fills in CONCENTRIC RINGS around you; far terrain arrives in
  region-sized blocks. `/lss diag` Scan line: `near_rings=` active during near
  fill then ~0, `region_span=` ≤2 during far fill, `audit_heals=0`.
- With Xaero's map on: the map fills with `dropped=0` and `drops_reported≈0`;
  `bp=` shows a fraction (a `(blocked)` suffix during heavy map activity is
  the mechanism working); `-1(wedged)` persisting >10 s at a time would be a
  real finding. Brief full pauses of the LOD download while the map is busy
  are DESIGNED (≤7 s per window).
- On a legacy-Sodium instance (26.1/1.21.11/1.21.1): open the LSS options page
  and HOVER THE XAERO TOGGLE — the rewritten tooltip is long (~767 chars) and
  the legacy renderer has a fixed pane (review flag; if it clips, I'll shorten
  the string per line).
- A/B lever if anything looks wrong: `enableRegionScan=false` in
  lss-client-config.json reverts to the legacy walk; `enableXaeroMapBackpressure=false`
  disarms the map pacing.

## 2. Release run (after your manual pass) — commands staged, NOT executed

Notes files (short user-facing bullets, per line):
`docs/planning/release-tag-v0.13.0.txt` + `-mc26.1/-mc1.21.10/-mc1.21.11/-mc1.21.1.txt`.

Per line, in its worktree, ON the canonical branch after the PR merge
(main / support/mc26.1-v0.13 / support/mc1.21.11-v0.13 / support/mc1.21.10 /
support/mc1.21.1):

```bash
# 1. re-run the line's CLAUDE.md pre-flight verbatim with -Pmod_version=0.13.0  (all green as of staging)
# 2. tag (annotated, verbatim cleanup):
git tag -a v0.13.0            -F docs/planning/release-tag-v0.13.0.txt            --cleanup=verbatim   # main
git tag -a v0.13.0+mc26.1     -F docs/planning/release-tag-v0.13.0-mc26.1.txt     --cleanup=verbatim
git tag -a v0.13.0+mc1.21.11  -F docs/planning/release-tag-v0.13.0-mc1.21.11.txt  --cleanup=verbatim
git tag -a v0.13.0+mc1.21.10  -F docs/planning/release-tag-v0.13.0-mc1.21.10.txt  --cleanup=verbatim
git tag -a v0.13.0+mc1.21.1   -F docs/planning/release-tag-v0.13.0-mc1.21.1.txt   --cleanup=verbatim
# 3. verify headers survived:  git for-each-ref --format='%(contents)' refs/tags/<tag> | head
# 4. push ONE TAG AT A TIME (>3 in one push fires ZERO workflow events):
git push origin v0.13.0        # wait for the run:  gh run watch --exit-status
git push origin v0.13.0+mc26.1 # ...one at a time, main first then the lines
# 5. after each publish: gh release view <tag>  (headers rendered?) + Modrinth check
```

Standing cautions: never `gh run rerun` a partially-published release; VSS
publish stays blocked on the expired MODRINTH_PAT (separate decision); the
Modrinth live-server deploy is a separate post-release step.
