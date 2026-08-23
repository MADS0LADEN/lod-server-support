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

**26.1 (support/mc26.1-v0.12).** Series applied CLEAN (5/5, zero conflicts).
Targeted suites green; selftest 265; full clean pre-flight at 0.12.0 +
release_check OK; CI green @ 338c3cdc. 2-Opus pair: ZERO MAJORs — fidelity
verified patch-identical with every executable surface byte-equal to main
(the one drifted file, ServiceLifecycleGameTests, carries pre-existing
TestPositions-helper drift the hunk landed cleanly around — the §2 audit
never covered the fold commit's files, corrected below); context verified
against the line's own recordings, including a ZERO-margin
stamp-heal-rejoin pass (clean=11/residue=5, both legs exactly on their
limits) that made the thin-margins flake entry more urgent here than on
main. Doc folds pushed: c20dc8ea (plan refresh + CLAUDE.md ×3) + 725ba1a8
(summary/stamped plan refreshes).

**1.21.11 (support/mc1.21.11-v0.12).** Series applied CLEAN (5/5).
Targeted suites green; selftest 265; full clean pre-flight + release_check
OK; CI green @ 043cc53b. 2-Opus pair: ZERO MAJORs — all five patches
content-identical; drift confined to ServiceLifecycleGameTests +
PaperRequestProcessingService (pre-existing, provably untouched: before-diff
== after-diff); the premise leg verified against the line's 095410Z
recording (no_region=9, one-tile margins both ways). The context reviewer
also caught the fold commit's §11-vs-§10 citation typo (fixed everywhere,
PR #226). Doc folds pushed: fdabd2d0 (three plan refreshes + CLAUDE.md ×3).

**1.21.1 (support/mc1.21.1).** Series applied CLEAN — §2's predicted
context conflicts never materialized (the 8 rename-drift lines all sit
outside the hunks' context windows; end-state diff vs main is exactly those
8 lines). Targeted suites green (`:fabric:test` without the absent Tier-3
task); selftest 265; full clean pre-flight (`:fabric:build` plain) +
release_check OK; CI green @ 34baca75. 2-Opus pair: ZERO MAJORs — patch-id
identity 5/5; NeoForge compiles the same purity-clean xplat source and
drives the identical client loop via ClientTickEvent.Post; the premise leg
verified inert against 14 recorded runs (all no_region==9). Doc folds
pushed: 584a71e5.

**Cross-line corrections from the pairs (all folded):**
- §2's "zero drift" claim was file-set-scoped too narrowly: the fold
  commit's ServiceLifecycleGameTests + PaperRequestProcessingService carry
  real pre-existing drift on 26.1/1.21.11 (TestPositions helpers / row-17
  split-dir re-root + ChunkPos accessors). End-state equality is NOT
  achievable for those two — future audits must compare drift-diff before
  vs after instead.
- The three panel-amended plan docs (ramp/§5, summary/clock-rewind,
  stamped/§10-monitor-bound) had gone stale on every line when main's docs
  half stayed main-only — refreshed to main's state on all three lines.
- The fold commit's Paper comment cited §11 for the UNCANARIED correction;
  it is §10 item 5 (PR #226, picked to all lines).
- PR #226's add -A swept three untracked jdtls .eclipse launch files into
  main and the picks — removed everywhere + .gitignore'd (PR #227).
- No `(cherry picked from …)` trailers on the series (line convention nit;
  provenance is unambiguous via patch-id + preserved authorship).

**Final line tips (after folds + nit picks):** 26.1 @ f266cb74,
1.21.11 @ b21a67fc, 1.21.1 @ 64c89e69 — each with the comment-nit and
gitignore commits above their CI-green backport tips; fresh CI runs
triggered on push, and final-tip clean pre-flights re-run (results in the
release plan §12.9).
