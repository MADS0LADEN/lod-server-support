# Plan: backport the stutter-fix pair to the three support lines

**Status:** DRAFT 2026-08-18 — awaiting (a) the user's live confirmation of the fixes
on the 26.2 repro rig and (b) the 3-Fable since-release review verdict, both in
flight. Execution is user-gated on (a) by explicit instruction ("we will backport
the fix to the other branch after I confirm it").

## 1. What is being backported

The two merged main fixes (companions — the shared root shape "a reset costs
O(disc)"):

| PR | Side | Commits (cherry-pick order) |
|---|---|---|
| #203 gen acquisition-frontier anchor | server (`common/`) | `19f1f805` (impl), `f373a7d8` (review fold) |
| #204 scanner prefix retention | client (`xplat/`) | `fbba674e` (impl), `ac61a642` (review fold) |

Merge commits (`3112d8b5`, `72a9d526`, `267c446e`) are NOT picked — the four
linear commits carry the whole change. `-x` on every pick so the port records its
main provenance (the stage-G convention).

Everything else on main since v0.11.0 is **out of scope**: #194 (NeoForge
metadata) is main/1.21.1-line-relevant but a separate concern, #197 (fan-out wall
race) is **already ported to all three branches** (`3fda59c1` / `b041ff68` /
`a7ee3a55`), #198-201 are main-line CI/docs.

## 2. Targets — THE BRANCH-NAME TRAP

The v0.11 lines live on:

- `support/mc26.1-v0.11` (delta-port, base `9cb32ade`)
- `support/mc1.21.11-v0.11` (delta-port)
- `support/mc1.21.1` (the stage-G fresh cut — unsuffixed)

**`support/mc26.1` and `support/mc1.21.11` (unsuffixed) are v0.8.1-era branches —
do not touch them.** (Verified: their `SpiralScanner` is ~956 lines behind; a pick
onto them would half-apply into pre-adaptive-cadence code.)

## 3. Measured drift (why the picks are expected near-clean)

Verified 2026-08-18 against the `v0.11.0` tag across all 13 touched
production/test/contract files:

- **26.1-v0.11 / 1.21.11-v0.11: ZERO drift** in every touched file. Only branch
  commit since the tag is the #197 port (touches `TwoPlayerGameTests` only).
- **1.21.1:** one touched file drifts — `LodRequestManager.java`, 3 lines, all MC
  API renames (`net.minecraft.util.Util`, `ResourceKey.identifier()` vs
  `.location()`), **none inside the fix's hunks** (ping probe + dim-change trace,
  not `tickMovementPhase`/`onDirtyColumns`/`consumeStaleCrossing`). Also
  `LodRequestManagerTest`/`TickTest` drift 4 lines each (same rename family) —
  possible fuzzy-context conflicts in the test picks, resolve by keeping the
  line's rename inside the incoming hunk.
- **CLAUDE.md drifts on all three** (line-specific facts: 6-17 line deltas). The
  two commits that touch it (`19f1f805` spread-gate clause, `fbba674e` want-set +
  SpiralScanner bullets) may conflict on context; resolution = apply the same
  clauses into the line's variant wording (keep the line's MC-version facts).
- `docs/planning/miss-memo-design.md` + the elytra doc exist on all three ✓;
  the amendment hunks should apply clean.

**Known structural conflict (will happen on every branch):**
`docs/planning/scanner-reopened-rings-plan.md` add/add between `f373a7d8` (sweeps
in v1.1) and `fbba674e` (adds its own copy). Resolution: take the incoming side at
each step; `ac61a642` lands the final v1.2. `f373a7d8` also sweeps in
`docs/planning/voxy-sodium8-bridge-spike.md` — harmless, keep it (it is on main).

Line-specific code facts checked: the 1.21.1 fourth descriptor axis
(NATIVE_LONG_ARRAY_PREFIXED) is NBT-side — untouched by either fix. The scanner
fix deliberately adds no Sodium/config-screen surface, so the per-line Sodium
version differences are irrelevant. Both fixes are wire-silent (no payload/codec/
channel change), so cross-line wire compatibility (never tiered) is unaffected.

## 4. Procedure (per branch; order 26.1-v0.11 → 1.21.11-v0.11 → 1.21.1)

Support branches take direct pushes (the stage-G/v0.8.1 pattern — only `main` is
PR-protected). Per branch:

1. `git fetch origin && git checkout support/<line> && git pull`.
2. `git cherry-pick -x 19f1f805 f373a7d8 fbba674e ac61a642` — resolve per §3
   (expected: the plan-doc add/add + CLAUDE.md context; on 1.21.1 possibly the two
   manager test files).
3. Build + test at the support tier ("correct, not perfect" — full builds, T1/T2,
   no exhaustive gauntlets):
   - `./gradlew :fabric:build -x runClientGameTest` (Tier 1 + Tier 2)
   - `./gradlew :paper:test :paper:shadowJar`
   - 1.21.1 additionally: `:neoforge:build` (contract suite — the line that ships
     NeoForge; the fix touches xplat, which NeoForge compiles).
   - Toolchain gotcha (mc-version-backport memory): on the 1.21.x lines the paper
     tasks need Java 21 locally (paperweight codebook cannot parse Java 25).
4. Flake discipline: a 1.21.1 Tier-2 fan-out red is NOT the old catalog entry
   (the wall race is closed by #197's port) — diagnose, don't re-run. The new
   SpiralScannerTest cases include a ~1M-position seeding test; expect Tier 1
   runtime +~5-10 s per line, not minutes.
5. Push the branch. No tag — see §6.

Do not run the ports while a soak or the live repro test is using the box.

## 5. Per-line validation beyond the tiers

- **26.1-v0.11**: the anchor's discovery line. Optional but recommended: repeat
  the 35 s stationary cold-backfill client trace on the local 26.1 test server
  (`./test-server.sh run-fabric` on that branch's build; `/vss trace` from the
  lss-test-26.1 Prism instance) — success = no multi-second generation gaps
  aligned to the 10 s dirty cadence.
- **1.21.1**: the user's Create+ Prism instance (Voxy 0.2.15-beta via Connector)
  is a natural live smoke for the client half once the line builds — the scanner
  fix is exactly the machinery under that instance's large-modpack client load.
- **1.21.11-v0.11**: tiers only (no live rig on this line — support-line effort
  budget).
- The soak harness only gates main; per the review round, no soak exercises the
  retention path anyway (stationary clients) — do not read soak-green as crescent
  coverage on any line.

## 6. Release (separate, user-gated)

The backports land on the branches un-tagged. A patch release would be
`v0.11.1` + `v0.11.1+mc26.1` / `+mc1.21.11` / `+mc1.21.1` (the dual-line tag
convention), each from its branch, with the standard pre-flight
(`CI=true ./gradlew ... -Pmod_version=0.11.1 && release_check.py --version 0.11.1`
per line) — proposed only after the user confirms the live fix on 26.2 and
decides timing. Release-notes items (both fixes are user-visible: the stutter fix
is the headline, the anchor is a Performance item) come from the 3-Fable review's
notes draft. `LINE_SHIP_NEOFORGE` stays as-is per line (1.21.1 only).

### 6.1 Release-notes draft (from the 3-Fable since-release review, 2026-08-18)

```
### Performance

- **Fixes periodic client stutters while moving with large LOD distances** — The client no longer re-checks its entire LOD area on every chunk crossing or chunk-update notice, only the affected rings. Removes the few-dropped-frames hitch every 2-3 seconds on well-explored servers at high `lodDistanceChunks` (client-side).
- **Server LOD generation no longer stalls during block updates** — Change notices for chunks near players used to pause distant LOD generation for ~5 seconds each, costing up to ~40% of backfill throughput on busy servers. Generation now keeps running at the configured caps while changed chunks are re-sent; worlds fill in faster with no new CPU beyond those caps.

### Bug Fixes

- **NeoForge mods screen shows the mod description and icon** — The NeoForge jar's metadata now matches the Fabric listing. (NeoForge — include this item ONLY on the 1.21.1-line release; LINE_SHIP_NEOFORGE=false elsewhere, so a main-line tag ships no NeoForge jar.)

### Configuration

- **New client option `enableScanPrefixRetention` (default `true`)** — Controls the new incremental scanning above. Set `false` to restore the previous full-rescan behavior if you ever see missing LOD patches that a reconnect fixes.
```

Omit as internal-only: port-isolation machinery (#197-#200), the fan-out gametest
fix, README docs (#201), plan/CLAUDE.md edits.

### 6.2 Rollback levers (state in the notes/PR)

- Client half (#204): config, no jar swap — `"enableScanPrefixRetention": false`
  (VSS: same key in `vss-client-config.json`); a mid-session flip also works (the
  scan-head flush converts retained state into one legacy full re-walk).
- Server half (#203): jar downgrade only — deliberately no config key. Downgrading
  the server jar is safe against any client version (wire unchanged). Diagnose
  first with `-Dlss.admissionTrace=true` (`fsrc=`) + `/lsslod diag` order_gated.
- Either half rolls back independently; all four mixed pairings are wire-identical.

## 7. Risks

- **R1 — silent semantic drift on 1.21.1.** The fresh-cut line compiled from a
  different port lineage; a pick can apply cleanly yet sit beside subtly different
  callers. Mitigation: the fix's own 20+ new Tier-1 pins travel WITH the picks
  (mutation-hardened — they red on every meaningful divergence found in review),
  plus the tier runs.
- **R2 — CLAUDE.md conflict mis-resolution** burying a line-specific fact.
  Mitigation: resolve CLAUDE.md hunks last, diff the result against the branch's
  pre-pick CLAUDE.md and confirm only the two fix clauses changed.
- **R3 — engine-timing differences** (the old 1.21.1 fan-out lesson: different
  tick timing exposes tick-vs-wall races). Both fixes are wall-clock-free on the
  client (tick-counted cadence only) and the new tests assert values, not
  timing — but treat any 1.21.1-only test red as real until diagnosed.
- **R4 — the stale `mod_version`** (local dev builds currently stamp 0.9.1 in the
  plugin banner). Cosmetic on dev jars; the release flow overrides it via
  `-Pmod_version`. Fix gradle.properties opportunistically at release time.
