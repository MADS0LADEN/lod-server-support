# v0.13.0 — port the 2026-08-24/25 stack to every line and prep the release (v1.1 — Fable review folded)

**Status: PLANNED** (user directive 2026-08-25: plan → 1-Fable review → implement →
2-Opus review per support branch → stage the manual-test rigs; the RELEASE RUN
itself is the user's, after their manual pass). **v0.12.1 is DROPPED**: its five
tags exist only locally (never pushed — verified via ls-remote), every line's tip
already CONTAINS its content, and v0.13.0 releases everything as one version.

## §0 Scope — what v0.13.0 ships (delta vs the released v0.12.0)

Two buckets, both already fully reviewed and gated on their source branches:

**(a) The staged v0.12.1 content — ALREADY COMMITTED on every line's tip** (no
porting needed; it ships when the line ships): the Sodium options-page
generations (legacy 0.6/0.7 + 0.8+ renderers), the Xaero saver crash fix
(coalesced updateBuffers under isResting), the Xaero hardening sweep, the §17.1
per-frame rebuild slice, and the §18 dropped-tile heal — **which bucket (b)
then DELETES** (the §12 round removed the ledger heal; on lines this nets out
to "heal code arrives in the line history and is removed by the port" — the
released artifact never contains it).

**(b) The main-line stack `origin/main..feat/hybrid-scan` — 18 commits, the
PORT SURFACE** (~5.3k insertions; all client-side except a 2-line
RequestProcessingService touch):
- Region-major walk: 31cfefe6/438fa612/7c5e319e (docs), e46782f1 (impl),
  765731a8 (5-agent fold).
- Hybrid plan docs: 479a7cce, 4834f3aa, 1cc60398.
- §12 Xaero backpressure + §18-heal REMOVAL: 4b681d83, e7706744 (4-Opus fold).
- Hybrid walk: 1194a9c0 (impl), e04a8a6f (3-agent fold), 982d680b (flake
  catalog), 710c95cb (hybrid-boundary soak wiring/sizing), ab2b72c5 (docs).
- §12.8/§12.9 blocked-pump inversion: 3347e3d4, aea0a077 (3-Opus fold),
  de34c616 (profile record).
Everything is live-validated on main: the 15-min zero-drop session, the spark
profile (all of LSS = 0.95% of the render thread), fresh-backfill +
hybrid-boundary soaks green.

**User-facing feature list for the notes** (§7): concentric near-fill + efficient
far region fill (the hybrid walk), the map-keeps-up Xaero backpressure (no more
overflow holes; brief designed pauses), the Sodium page on legacy Sodium, the
Xaero saver crash fix, the rebuild-stutter fix. Config keys added since v0.12.0:
`enableRegionScan` (client, default true), `enableXaeroMapBackpressure` (client,
default true, composes under `enableIngestBackpressure`).

## §1 Line topology (verified 2026-08-25)

| line | canonical branch (v0.12.1 tag sits at its tip) | port worktree | tier |
|---|---|---|---|
| 26.2 (main) | `main` @ 6a1df19b | ~/projects/lss-main-deploy (feat/hybrid-scan) | full |
| 26.1 | `support/mc26.1-v0.12` @ 1548d852 (= port/sodium-26.1) | ~/projects/lss-port-26.1 | correct-not-perfect |
| 1.21.11 | `support/mc1.21.11-v0.12` @ 83562227 (= port/sodium-1.21.11) | ~/projects/lss-port-1.21.11 | correct-not-perfect |
| 1.21.10 | `support/mc1.21.10` @ 98550065 | ~/projects/lss-port-1.21.10 | correct-not-perfect |
| 1.21.1 | `support/mc1.21.1` @ 751555fb | ~/projects/lss-port-1.21.1 | best-effort (ships NeoForge; NO Tier 3) |

The unqualified `support/mc26.1`/`support/mc1.21.11` branches are STALE
(v0.8.1-era) — never target them. **§1 SHAs are ORIGIN refs (review MAJOR 1):
the LOCAL same-named branches are v0.12.0-era and LOCKED by leftover
`/tmp/.../scratchpad/port-*` worktrees from a prior session. Pre-step before
any port: remove those four /tmp worktrees (`git worktree remove --force` +
`git worktree prune` in the primary repo), fast-forward the four local branches
to origin, and base every `port/v0.13-<line>` branch explicitly on
`origin/<canonical>`.** Divergence check: SpiralScanner +
XaeroMapCompat are byte-identical to main's base on 26.1/1.21.10/1.21.11
(cherry-picks expected clean); **1.21.1's XaeroMapCompat is DIVERGED** (the
line's level-height/Identifier adaptations) — §12/§12.8 picks WILL conflict
there and must be resolved preserving the line flavor (§3.1).

## §2 Main landing

0. **Version scrub (review MAJOR 2)**: commit on `feat/hybrid-scan` BEFORE the
   PR — `gradle.properties` `mod_version` 0.12.1→0.13.0 and README's Sodium-page
   sentence "from v0.12.1"→"from v0.13.0" (v0.12.1 never exists; without this
   every manual-rig jar self-reports 0.12.1 while carrying v0.13.0 behavior).
   The SAME scrub lands per line as a port adaptation commit (README is
   diverged per line, so the main hunk won't carry).
1. PR `feat/hybrid-scan` → `main`, merged with **`--merge`** (never squash —
   the port cherry-picks reference these SHAs, and squash orphans any
   already-picked state). Title: "v0.13.0: hybrid scan + Xaero backpressure
   (§12/§12.8/§12.9) + region walk".
2. Post-merge: rebuild + refresh the lss-test-26.2 jar from MERGED main (§8).
3. `main` then IS the v0.13.0 rc for 26.2 — no further code work.

## §3 Per-line port protocol

Order: 26.1 → 1.21.11 → 1.21.10 → 1.21.1 (hardest last, with three clean ports'
experience banked). Per line, in its existing `lss-port-<line>` worktree:

1. `git checkout -b port/v0.13-<line>` off the line's canonical-branch tip.
2. Cherry-pick the 18 commits IN ORDER (`git cherry-pick 31cfefe6^..de34c616`
   equivalent — pick the enumerated list; keep per-pick SHAs so the review can
   diff pick-vs-source). Docs commits ride along (plans travel with lines);
   CLAUDE.md hunks resolve toward the LINE's banner + the new content.
3. Resolve conflicts per §3.1; every non-mechanical resolution gets a
   `port(<line>):` note in the commit or a trailing adaptation commit.
4. Line gates (§4). 5. Push the port branch; merge to the canonical branch only
   AFTER its 2-Opus review folds (§5) — for 26.1/1.21.11 that means cutting
   `support/mc26.1-v0.13` / `support/mc1.21.11-v0.13` at the fold point
   (following the established -v0.12 naming); 1.21.10/1.21.1 merge into their
   unqualified branches.

### §3.1 Expected flavor points

- **1.21.1 XaeroMapCompat**: the diverged hunks are the v0.12.0 bridge port's
  adaptations (`getMinBuildHeight()/getMaxBuildHeight()` vs `getMinY()/
  getMaxY()+1`, ResourceLocation, the line's section family). §12 rewrote large
  spans of this file — resolve KEEPING the line's MC-API calls inside the new
  §12 structure; the §12.8/§12.9 hunks (reportBackpressure, offerColumn,
  pump) are MC-API-free and should apply after the §12 base resolves.
- **1.21.1 misc**: Tier 3 absent (no runClientGameTest anywhere in gates);
  NeoForge module MUST build (`:neoforge:build`) — the changed code is all
  xplat, so it flows to both loaders; the gametest smoke
  (`:neoforge:runGameTestServer`) is the NeoForge gate.
- **Scripts** (soak.sh / check_soak.py): hand-mirrored per line — the
  hybrid-boundary registrations (FOUR soak.sh points: ALL_SCENARIOS, the
  validity case, the duration table, FRESH_WORLD_SCENARIOS; THREE checker
  points: CHECKS, MIN_CLIENT_WINDOWS, ANOMALY_OPT_INS + the named check +
  selftest cases) must land in each line's flavor of those files. Selftest
  count on lines may differ from main's 270 — the gate is "selftest OK", not
  the number.
- **Exporter contract**: `scan.near_rings=int` must sit in ALPHABETICAL
  position (between missing_vanilla and quad_ring_skips) — the contract file
  is sorted and the test reds otherwise (found at the main port).
- **CLAUDE.md per line**: each line's copy gets the same three scanner-site
  updates + the bridge-paragraph §12.8/§12.9 rewrite + flake-catalog entries,
  merged into ITS banner/hand-mirrored facts — never wholesale-copied.
- **Expected pick conflicts beyond CLAUDE.md/scripts (review m3/n7)**:
  e7706744 edits the five `release-tag-v0.12.1*.txt` files which exist ONLY on
  main → modify/delete conflicts ×5 per line, resolve KEEP-DELETED; README is
  diverged on all four tips and the stack edits its Xaero paragraph → per-line
  manual merge; `RequestProcessingService` is diverged on 1.21.11/1.21.10/
  1.21.1 (the 2-line javadoc hunk lands, just not identically); the 10-line
  comment-only `AbstractPlayerRequestState` hunk is in the verify set too.

## §4 Gates per line (before its review)

- T1: `:fabric:test -x runGameTest` — full suite green.
- T2: `:fabric:runGameTest` green.
- Checker: `check_soak.py --selftest` OK + `--validate hybrid-boundary` PASS.
- Full pre-flight: **run each line's CLAUDE.md pre-flight command VERBATIM**
  with `-Pmod_version=0.13.0` + `release_check.py --version 0.13.0` OK (review
  m4: 26.1/1.21.11/1.21.10 carry `-x runClientGameTest`, ALL four lines
  include `:neoforge:build` for the contract suite — the uniform command in v1
  both launched unintended Tier 3 runs and dropped a gate).
  (JAVA_HOME: Java 21 on the 1.21.1 line, Java 25 elsewhere — per line docs.)
- Smoke soak: `fresh-backfill` on 26.1, 1.21.11 AND 1.21.1 (review m5 — the
  stack is a client scanner rewrite and 1.21.1 has no Tier 3; that line ran
  soaks for its v0.12.0 port too). 1.21.10: T1/T2 + its build.yml Tier 3 job.
  1.21.1 additionally: the NeoForge gametest smoke. hybrid-boundary runs on
  MAIN only (already green — 30 min/run is not a per-line smoke).
- **Port-branch CI green** (review m5): the §3 push fires build.yml per line —
  a green run (including the line's Tier 3 job where it exists) is a §4 gate,
  checked before the line's review folds.
- One soak red = consult the line's flake catalog FIRST (the WSL2 clock-step
  and A7 environmental entries apply on every line).

## §5 2-Opus review per support branch (user-directed)

Per line, TWO Opus reviewers in parallel, then fold + re-gate:
- **Lens A — pick fidelity/completeness**: diff every cherry-pick against its
  main-line source commit; every deviation must be a line adaptation with a
  recorded reason; no dropped hunks. The §18-heal deletion check names its
  TARGETS (review m6 — the kept DropReporter self-heal legitimately says
  "heal"): zero survivors of `enableXaeroMapBridgeHeal`, the ledger/owed
  structures and heal counters, and the ConfigValidationTest heal-key test
  renamed to the backpressure key. The seven scenario/checker registration
  points all present; the contract row sorted.
- **Lens B — line-flavor correctness**: the adaptations compile against the
  LINE's MC APIs and preserve its recorded flavor points (1.21.1's banner
  facts; each line's hand-mirrored script facts); CLAUDE.md's line banner
  intact and the new content merged, not clobbered; config keys registered in
  the line's config surface; NeoForge twins (1.21.1) wired.
Findings fold on the port branch; a MAJOR re-runs that line's gates.

## §6 Dropping v0.12.1

- Delete the five LOCAL tags: `git tag -d v0.12.1 v0.12.1+mc26.1
  v0.12.1+mc1.21.10 v0.12.1+mc1.21.11 v0.12.1+mc1.21.1` (primary repo — they
  were never pushed; verified ls-remote=0). Nothing to delete remotely.
- The five `release-tag-v0.12.1*.txt` files already carry SUPERSEDED headers;
  they are replaced by `release-tag-v0.13.0*.txt` (§7) and the old files
  DELETED in the same commit (the headers pointed at a decision; the decision
  is now made).
- The v0.12.1 content is simply released inside v0.13.0 — the notes fold its
  user-facing items in.

## §7 v0.13.0 release prep (staged — the RUN is the user's)

- Write the five notes files `docs/planning/release-tag-v0.13.0[-mc<ver>].txt`
  (dash-form filenames — the catalog convention, review n8). **STYLE (user
  directive 2026-08-25): SHORT — a few SINGLE-LINE bullets, player/server-
  operator voice, zero internal detail** (no section refs, counters, mechanism
  narrative). Content: hybrid scanning (near terrain fills in clean rings, far
  terrain streams efficiently), the Xaero map keeping up with big downloads
  (no more dropped tiles), the Xaero saver crash fix, the rebuild-stutter fix,
  the Sodium settings page on older Sodium versions, the two new config keys
  in one line — plus the standing one-line Compatibility boilerplate (mixed
  versions compatible both directions; Folia experimental — review n9).
  Platform qualifiers per the existing rules; 1.21.1 names NeoForge + tier.
- Stage (in each line's worktree, NOT executed): the pre-flight command line
  and the tag command `git tag -a v0.13.0[+mc<ver>] -F <notes> --cleanup=verbatim`.
- Release-run reminders for the user (from the catalog): push tags ONE AT A
  TIME (>3 in one push fires zero workflow events); verify rendered notes with
  `gh release view`; never re-run a partially-published release; VSS publish
  stays BLOCKED on the expired MODRINTH_PAT (separate user decision).
- Line release order at run time: main first, then 26.1, 1.21.11, 1.21.10,
  1.21.1 (v0.12.0 precedent).

## §8 Manual-test staging (the deliverable to the user)

- **Prism instances** (inventory each instance's mods/ first — never guess the
  MC version from the folder name; NO hot-swaps — check for running clients):
  26.2 → lss-test-26.2 (refresh from merged main); 26.1 → the 26.1 instance;
  1.21.11 / 1.21.10 / 1.21.1 → their instances (1.21.1 has the
  NeoForge+fork-Voxy stack — it may need the nosqlite variant per the P-1
  collision note; verify what jar it currently runs and match its shape).
- **Test servers**: in each line worktree run `./test-server.sh update` (or
  first-time setup) so the staged servers carry the rc jars. Ports collide
  across lines — the run sheet tells the user to test ONE LINE AT A TIME
  (stop the previous line's servers first). The 26.2 line can also be
  eyeballed against the live Modrinth server AFTER release (not part of this
  prep).
- **The run sheet** (hand to the user at the end): per line — start command,
  which instance to launch, and the signature checklist: concentric near fill;
  `/lss diag` shows `near_rings=` active→~0, `region_span<=2` far,
  `ring_skips` large, `audit_heals=0`; with Xaero: map fills with
  `dropped=0`/`drops_reported≈0`, `bp=` a fraction (possibly `(blocked)`)
  never a stuck `-1(wedged)`; brief fill pauses during flight are DESIGNED;
  the legacy A/B lever `enableRegionScan=false` reverts the walk per line.

## §9 Risks / rollback

- Every new mechanism has a config kill switch on every line:
  `enableRegionScan=false` (whole hybrid walk → legacy arm),
  `enableXaeroMapBackpressure=false` (taper off), plus the pre-existing
  `enableXaeroMapBridge` master. Defaults ship ON (matching main's reviewed
  defaults).
- The 1.21.1 XaeroMapCompat conflict resolution is the riskiest single step —
  hence last in order, the 2-Opus review, and the NeoForge build gate.
- The heal deletion on lines is verified by grep (zero heal symbols) in Lens A.
- If a line's gates cannot go green in reasonable effort, the line-tier
  doctrine applies (best-effort/correct-not-perfect) — a dated decisions-log
  entry + the user decides whether that line ships v0.13.0 later.

## §10 Execution checklist

1. Main PR + merge; refresh lss-test-26.2. 2. Ports ×4 with gates. 3. 2-Opus
review ×4 + folds + re-gates. 4. Tag/notes staging + v0.12.1 tag deletion.
5. Instance + server staging. 6. Hand over the run sheet. Memory updates at
each settled stage.

## §11 Plan-review fold (1-Fable, 2026-08-25)

2 MAJORs (origin-vs-stale-local canonical refs + the /tmp worktree locks — §1
pre-step; the unscrubbed `mod_version=0.12.1`/README phantom-version references
— §2 step 0 + per-line), 4 MINORs (the e7706744 notes-file modify/delete
conflicts + README in the expected-conflict list; per-line-verbatim pre-flights;
the port-branch CI gate + 1.21.1 fresh-backfill; the heal-deletion check named
by target symbols), 3 NITs (server-surface accounting, dash-form notes
filenames + the §12.5 pointer, the Compatibility boilerplate) — all folded
above. Verified sound by the reviewer: the 18-commit range is linear
(no merges), the stack is wire-neutral and line-portable (zero line-flavored
MC-API tokens in the new code), the 1.21.1 XaeroMapCompat divergence is ONE
call site (`getMinBuildHeight`/`getMaxBuildHeight` — far smaller than v1
feared), and the registration-point/contract/config claims all check out.
The user's release-notes style directive (short single-line user-focused
bullets) is folded into §7 and recorded in memory `release-notes-style`.
