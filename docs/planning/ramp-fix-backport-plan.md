# Ramp-fix backport plan — PR #221 onto the three v0.12.0 support lines

**Date:** 2026-08-22. **Context:** the window-limited Row-4 bypass
(docs/planning/ramp-window-limited-credit-plan.md, merged to main @ 9c7c81a8 as
PR #221) folds into v0.12.0 by user decision (release plan §12.7). The three
support-line release tips predate it and must carry the identical fix before
tagging. This plan is the execution record; per-line results are appended in §5.

## 1. Scope — what backports

The three PR #221 commits plus the closing-panel fold commit, cherry-picked as
a series (provenance and fold structure preserved in the messages):

- `d69e0f57` fix(client): window-limited Row-4 bypass — the slow-start ramp
  completes on serve-rate-clocked loops
- `0b4c30c2` fix(client): implementation-panel folds — one governed
  composition, latch-scoped forgiveness
- `61cef3b4` fix(client): dynamics-lens folds — fast-fire latch conjunct,
  marginal-pressure provenance, diag receipt
- `a79d950e` fix: v0.12.0 closing-panel folds (release plan §12.7 panel:
  RegionSummaryService quit-race fixes + pins, the real event-blind pin,
  wall-denominated freshness gametest, the `check_stamp_heal_rejoin` premise
  leg — selftest 265 — and the comment corrections). Portable by
  construction: common/xplat/test/scripts surfaces only. NOTE: each line's
  CLAUDE.md may cite the selftest count — check and bump per line if present.

Files touched (7 code/test + the plan doc): xplat
`TransferRateGovernor`/`SpiralScanner`/`LodRequestManager`; fabric Tier-1
`TransferRateGovernorTest`/`SpiralScannerTest`/`LodRequestManagerTickTest`/
`QuadtreeWalkDifferentialTest` (accessor rename only);
`docs/planning/ramp-window-limited-credit-plan.md` (new — rides along so each
line's tree documents the behavior it ships).

**NOT backported:** the release-notes bullets (notes live on MAIN only —
release plan §12.3: tags take notes via `git show main:...`) and plan §12.7
(main-only record).

## 2. Why the backport is near-mechanical

Drift audit (2026-08-22, `git diff 9c7c81a8~1 <line> -- <the 7 files>`):

- **support/mc26.1-v0.12** (tip 24e7440b): ZERO drift on all seven files.
- **support/mc1.21.11-v0.12** (tip 7f77a42f): ZERO drift on all seven files.
- **support/mc1.21.1** (tip 55d16afc): 8 drifted lines in
  `LodRequestManager.java` + `LodRequestManagerTickTest.java`, all of them the
  line's known API renames (`Identifier`→`ResourceLocation`,
  `net.minecraft.util.Util`→`net.minecraft.Util`, `.identifier()`→
  `.location()`). None of the drifted lines are inside the ramp hunks; at
  worst a context-line conflict resolves by keeping the line-local API name
  and taking the ramp logic verbatim. The fix itself is MC-API-free (governor
  arithmetic, scanner flag, manager latch).

The ramp fix is CLIENT-side xplat with zero wire footprint, so the lines'
wire/protocol surfaces are untouched and no platform twin work exists
(NeoForge compiles the same xplat source on 1.21.1).

## 3. Per-line execution

Worktrees (already present from the port round): `port-261` →
support/mc26.1-v0.12, `port-12111` → support/mc1.21.11-v0.12, `port-1211` →
support/mc1.21.1, all under the session scratchpad.

Per line, in order:

1. `git cherry-pick d69e0f57 0b4c30c2 61cef3b4` (resolve 1.21.1 context
   conflicts per §2 if any).
2. Targeted Tier-1 first (fast signal): the three ramp suites
   (`--tests` filtered `:fabric:test`).
3. The line pre-flight, matching §12.1's discipline: `./gradlew clean`, then
   the line-correct release build under `CI=true -Pmod_version=0.12.0`:
   - 26.1 / 1.21.11: `:fabric:build -x runClientGameTest :paper:test
     :paper:shadowJar :neoforge:build`
   - 1.21.1: `:fabric:build :paper:test :paper:shadowJar :neoforge:build`
     (no Tier-3 task on this line — plain `:fabric:build`)
   then `python3 scripts/release_check.py --version 0.12.0` → OK.
4. Push the branch; build.yml covers `support/**` — watch the run green.
5. **2-Opus review of the backport** (user-directed): reviewer A = diff-of-
   diffs fidelity (the line's cherry-picked change vs main's, byte-level
   modulo the §2 renames — nothing dropped, nothing extra, plan doc present);
   reviewer B = line-context correctness (the latch conjuncts against the
   LINE's scanner/cadence/config reality — e.g. Tier-3 absence, NeoForge
   compilation of the xplat source on 1.21.1, no line-local feature the
   conjuncts mis-handle). Findings folded before the line is declared done.

Rollback per line: `git reset --hard <pre-backport tip>` (24e7440b /
7f77a42f / 55d16afc) + force-push — safe while nothing is tagged.

## 4. Order and gating

26.1 first (closest twin of main), then 1.21.11, then 1.21.1 (the one with
conflicts). Execution starts only after the main-changeset 5-reviewer panel
(release plan §12.7 gate 2) returns with MAJORs resolved — if the panel
changes the ramp code on main, the backport picks the amended commits instead.

## 5. As-built record (appended per line)
