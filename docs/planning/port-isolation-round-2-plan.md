# Port-isolation round 2 — hindsight hardening after the v0.11.0 ports

**Status: EXECUTED (v1.2 complete, 2026-08-15 — all ten items landed on main:
R2-1/2/3/5/6/7 via PR #198, R2-4/8/10 via PR #199, the fan-out timing fix the
round surfaced via PR #197. R2-9 resolved on the NOT-EQUAL arm: the live 26.2
probe showed `getWorldFolder()` returns the per-dimension SUBFOLDER on unified
layouts, so the per-level form was NOT adopted (it would break the 26.x sweep
the same way the unified form broke the 1.21.x port's) — surfaces row 17 records
the axis. Gates: T1/T2/paper/selftest green; every M3 byte-diff clean; the R2-3
Paper fresh-backfill smoke PASSED. Original header: v1.2, 2026-08-15 — the post-release re-review: every item
re-validated against the changes landed after v1.1 (the LINE_SHIP_NEOFORGE ship
gate, the jarJar sqlite fix + its release_check hardening, the NeoForge metadata
parity fix, the LINE_NEOFORGE_NAME flip, the README replacement, and the v0.11.0
release itself — all four tags published, so the G-4 precondition is satisfied and
the branches are frozen at v0.11.0+hotfixes). All ten items stand; three
amendments marked [v1.2] inline. v1.1 history: v1.0 reviewed by a 1-Fable
adversarial pass, 3 MAJOR / 9 MINOR, "fit to execute after fixes", all folded.
Executes on MAIN; the payoff lands on the next port and every future main→branch
patch flow.)**

Round 1 (version-port-isolation-plan.md, stages V-1/V-2/V-3) ran BEFORE the ports on
predicted churn. This round runs AFTER them, on the recorded churn of three real
ports — g26/26.1 (29 files, +171/−114 vs 9cb32ade), g21/1.21.11 (85 files,
+480/−660), g211/1.21.1 fresh cut (185 files, +2003/−2338) — plus the 2026-08-15
6-Opus review round's failure catalog (6 MAJORs dispositioned — 5 fixed: the
render-phase trap, the store split-dir sweep, the workflows' red-by-construction
CI, the missed test-server.sh retarget, the mislabeled cut set; 1 refuted with jar
evidence — plus the Folia-absent line fact found during the fixes).

## §0 The core lesson (binds every item below)

Across three ports, the outcome split cleanly by mechanism:

- **Derivation + contract test HELD**: line.env→release.yml (zero churn on the
  delta-ports), the S1 descriptor (G-2's goldens passed UNREGENERATED under the
  dormant fold arm), ScopedCarrier, the V-3 seams, the two-stage regen rule.
- **Label + runbook pointer LEAKED**: test-server.sh's LINE DATA banner (file missed
  entirely on the fresh cut — review MAJOR), build.yml's java-version (MAJOR),
  soak.sh's staging flavor (lost because one runbook sentence overclaimed — MAJOR),
  surfaces hand-row 7 (skipped — the folia direction shipped wrong).

Round 2 therefore converts the leaked class to derivation wherever a data source
already exists, and gives the irreducible hand judgments a forcing function. The §7
abstraction budget of round 1 binds unchanged: every item names its recorded churn,
and a seam that wants a subsystem is a stop sign.

## §1 Work items (ranked: churn absorbed × failure severity / size)

**R2-1. Tier-3-cut invariance — the poison-pill task stub + the compile/entrypoint
arms.** `fabric/build.gradle` gains one LINE DATA boolean (`tier3 = true`) driving
THREE things (the plan-review MAJOR: the boolean alone does not absorb the recorded
cut): (a) `enableClientGameTests`, plus a stub `runClientGameTest` task registered
when false whose `doFirst` throws `GradleException("Tier 3 is CUT on this line —
pre-authorized-cuts.md")` — `-x` removes a task from the execution plan without
running its actions (Gradle-semantics verified), so every one of main's 13
`runClientGameTest` mentions across 9 machinery files (build.yml ×4, release.yml,
soak.sh, test-server.sh, benchmark.sh, benchmark_compare.sh, profile_disk_read.sh,
backfill_profile.sh ×2, release_check.py docstring) becomes line-invariant, and a
forgotten direct invocation reds with an explanation instead of `Task not found`;
(b) a gametest source-set EXCLUDE of `dev/vox/lss/test/LSSClientGameTests.java`
when false — the class cannot compile on a cut line (no
`fabric-client-gametest-api-v1` in that line's fabric-api, per the recorded g211
cut); (c) processResources filtering of the `fabric-client-gametest` entrypoint
out of the gametest `fabric.mod.json` when false — a compile-exclude WITHOUT the
resource edit breaks Tier 2 at entrypoint resolution. Guard:
`GameTestEntrypointContractTest` becomes line-neutral by pinning CONSISTENCY (the
entrypoint lists exactly the client-gametest classes present in the source set)
instead of presence; runbook step 8 names the boolean as the only build edit
(the build.yml Tier-3 job deletion stays a one-hunk hand edit). ~40 lines.
[v1.2] The "13 mentions across 9 files" census predates the ship-gate/jarJar
release_check rounds — re-count at execution; the mechanism is count-independent.
**Evidence:** review MAJOR "CI red by construction" + the recorded 10-file 1.21.1
tail incl. the file deletion + resource edit the v1.0 design missed; the
dormant-arm pattern's payoff record.

**R2-2. build.yml reads `.github/line.env`.** Both jobs gain release.yml's env
source step; `java-version: ${{ env.LINE_JAVA_VERSION }}` replaces the hardcoded 25
(lines 22/151). Guard: a `ReleaseWorkflowContractTest` pin that build.yml contains
no literal `java-version:` value. ~10 lines + one test method. **Evidence:** MAJOR 3
(red CI ×2 jobs on the Java-21 line); 1.21.11 carried the same 2-line edit.

**R2-3. soak.sh world staging goes line-invariant.** Adopt the branch shape on main
unconditionally: `rm -rf world world_nether world_the_end` + `cp -r world*` at all
four staging sites + the stale-nest guard — a strict superset on 26.x (no split dirs
exist; the glob matches only `world/`). The flavor stops existing; delete the
runbook's "per-line forever" layout paragraph. Gate: 26.2 fresh-backfill smokes on
BOTH platforms — the Paper staging is the only realistic glob-risk surface, so the
`SOAK_PLATFORM=paper` arm is the one that matters (plan review m9). Bonus specimen
for R2-8: main's soak.sh Step-5a comment still says "Paper on MC 26.1.2 uses the
vanilla unified layout" on the 26.2 tree. ~10 lines. **Evidence:** the flavor was carried on v0.10, lost in
the v0.11 delta-port off one overclaiming runbook sentence (review MAJOR), and
re-derived twice (g21 17/11, g211 18/12).

**R2-4. Surfaces rows 15/16 — the two semantic traps.** Row 15: *far-player render
phase* — the registered event must fire BEFORE the line's submit-storage drain,
verified against LevelRenderer bytecode; the event NAME is not the invariant, the
drain ordering is (26.2 `COLLECT_SUBMITS` ↔ 1.21.11 `BEFORE_ENTITIES`, while
`AFTER_ENTITIES` is CORRECT on 1.21.1's immediate-mode renderer — the same name
flips meaning per line; shipped wrong once). Row 16: *Moonrise IO entry class is
per-platform* — verify Fabric against the moonrise-opt jar and Paper against the
dev bundle, never transfer between them (Moonrise-Fabric 1.21.1 =
`MoonriseRegionFileIO`; Paper 1.21.1 = `RegionFileIOThread`; a reviewer's
cross-platform "fix" was refuted only by downloading the real jar). Plus one
invariant-naming comment at each registration/resolution site. 2 rows + 2 comments.

**R2-5. `folia-supported` direction derived from line.env.** Single source: `folia`
membership in `LINE_PAPER_LOADERS` (already per-line, already correct everywhere).
plugin.yml templates the flag conditionally via processResources (the file already
templates `${version}`/`${api_version}`); `PluginYmlContractTest` and
`release_check.py` read line.env and flip assertion polarity off the same datum; one
new pin asserts the expanded yml agrees with line.env in both polarities. [v1.2]
The same line.env read ALSO absorbs `SHIP_NEOFORGE`: the ship-gate round (post-v1.1,
pre-release) landed it as a hand-mirrored constant with a
`neoforgeShippingIsGatedPerLine` cross-pin because it shipped same-day; deriving it
from `LINE_SHIP_NEOFORGE` retires the mirror and its forward-merge drift vector
outright (the contract test keeps pinning line.env's per-line VALUE — the judgment —
while release_check stops carrying a copy). This is §0 verbatim: the mirror is a
label, the read is a derivation. The
judgment ("does Folia exist upstream?") stays a judgment — the derivation's honest
value is SINGLE-POINT-OF-EDIT plus adjacency: the field lives in the one file a
fresh cut must touch anyway (the MC rows are cross-pinned), not a hard forcing
function (plan review m2 — a wrongly inherited `folia` would still expand
consistently and pass). Execution notes (review m6): `release_check.py` greps the
JAR's EXPANDED plugin.yml, so the source-literal pin splits into a placeholder-form
pin on the source + an expanded-copy pin (the expanded resource is on the
`:paper:test` classpath); the 26.2-provenance comment above the flag rewords
line-neutral. GATE (review MAJOR M3, the V-1 precedent): a pre/post byte-diff of
the expanded plugin.yml on main — the templating change must be byte-identical
before it merges. Fold `FABRIC_MAPPING_NAMESPACE` into the same line.env read —
round 1's "revisit at four entries" trigger has fired. ~50-60 lines once.
**Evidence:** row 7 skipped by the fresh cut (hand row, no forcing function),
direction wrong-by-inheritance, ~60-line flip cost ×4 files, 3+ historical flips.

**R2-6. Third-party coordinates become data keys; test-server.sh derives its
MC/Java axes.** gradle.properties gains additive keys (`sodium_version`,
`modmenu_version`, `moonrise_modrinth_version`, `c2me_modrinth_version`,
`suggests_sodium`, `suggests_voxy`) consumed by fabric/build.gradle and templated
into fabric.mod.json's `suggests` — the exact mechanism that already worked for
`minecraft_dependency`/`fabric_api_dependency`. test-server.sh derives
`FABRIC/PAPER/FOLIA_MC_VERSION` from gradle.properties `minecraft_version` and the
Java gate from `LINE_JAVA_VERSION`, keeping only the CDN URLs + legacy-LSS pin as
genuine LINE DATA — with a startup self-check failing loudly when a kept URL's MC
token disagrees with `minecraft_version` AND when the C2ME URL's version-ID
substring disagrees with `c2me_modrinth_version` (review m7 — the same ID lives in
both files today and can drift internally). Two sub-items from the review: adopt
g211's `"fabric-api": "${fabric_api_dependency}"` templating on main (m3 — the
mechanism exists only on that tree; main/g26/g21 carry hand literals), and derive
the neoforge.mods.toml loader floor `[X.Y,)` from `neoforge_version`'s first two
components (m4 — a labeled hand row that churned on all three ports, the exact
"label without derivation" class §0 indicts). [v1.2] neoforge.mods.toml has since
gained `logoFile` + the fabric-matching description (the metadata-parity fix) —
the byte-diff gate simply runs against the current content. GATE (review MAJOR M3): pre/post
byte-diff of the expanded fabric.mod.json + neoforge.mods.toml on main —
byte-identical before merge. Guard: FabricModJsonContractTest extension (suggests
expands non-empty). ~80 lines. **Evidence:** compat-arm IDs stale on ALL THREE
lines; suggests stale on two; test-server.sh missed entirely (MAJOR) despite its
LINE DATA banner — labeling failed, derivation is the fix. Derivation source is
gradle.properties `minecraft_version`, NOT `LINE_MC_FABRIC` (which is `26.1` while
the line builds `26.1.2` — review-verified).

**R2-7. release.yml's last per-line tokens.** The Paper Modrinth display name
composes its loader suffix from `LINE_PAPER_LOADERS` (or a new `LINE_PAPER_DISPLAY`
row). With R2-1, release.yml returns to ZERO per-line hunks. Guard: extend the
no-hardcoded-line-tokens pin to loader prose; GATE (review MAJOR M3): the composed
display name must byte-match the current literal on main before merge. ~5 lines.
**Evidence:** exactly 2 release.yml hunks on the fresh cut; this is the second.

**R2-8. Provenance hygiene — `LINE-FACT` markers + a sweep step.** One pass over
main tagging comments that state per-line facts with `LINE-FACT(26.2):` (VoxyCompat
rebuild call, LanHook overload census, Paper world layout, Moonrise/tracer
verification provenance, …), each naming its surfaces row where one exists. Runbook
step 8.5: on the branch, `git grep -nE '\b26\.2\b|LINE-FACT' -- '*.java' '*.sh'
'*.gradle'` — every hit re-derived, re-worded line-neutral, or recorded in the port
PR. ~30-40 comment edits + 1 runbook row. **Evidence:** 15-54 stale "26.2"-mentioning
removed lines per port (g21=15, g26=48, g211=54 — the higher counts include
version-token code; review m5); most of the 26.1 review commit was exactly this
class.

**R2-9. Paper store-sweep per-level re-root adopted on main.** ⚠ CHANGES RELEASED
CODE — behavior equivalence verification-gated (review M3's relabel: the gate makes
this the one item DESIGNED to change code without changing behavior). Verify on a
live 26.2 Paper server that `level.getWorld().getWorldFolder().toPath().normalize()`
EQUALS `server.getWorldPath(ROOT).normalize()` per level — path equality, not root
shape (review m8; File-vs-Path normalization can differ even when the root looks
unified), with the plausible third outcome (per-dim subfolders under the unified
root) falling to the row-17 arm; if equal, adopt the per-level form on main and
delete the BACKPORT CAVEAT (a caveat comment demonstrably did not stop the broken
shape from shipping twice); if not, add surfaces row 17 ("Bukkit world layout —
consumers: store sweep + soak staging") so it is a checklist walk. Gate: the Paper
store soaks + dimension-trip; the sweep's own drop counters. ~5 lines + a
verification session. **Evidence:** review MAJOR 2 — the caveat comment predicted
the exact failure and the delta-port shipped it anyway.

**R2-10. Checklist rows (batch).** Runbook: step 12 gains "diff every release-notes
claim against the line's shipped ServerConfigBase defaults" (kills the 512→300
ghost class); step 11 gains "the banner's cut list is the TAKEN set, cross-checked
against build.gradle toggles" (MAJOR 5) and "README support-matrix row per line".
Plus the patch-flow row (review MAJOR M2): "the derivation files (build.yml,
test-server.sh, fabric/build.gradle's configureTests block, soak.sh staging)
conflict at the first post-round-2 merge into each branch — resolve TAKE-MAIN;
the branch's flavor value already lives in the data file". 4 lines.

## §2 Round-1 payoff record (the calibration this plan is built on)

PAID: line.env + branch-invariant release.yml (the 180-line/50.4% release bucket →
9-12 data lines; zero delta-port churn); the S1 descriptor (134-line serialization
churn → constant flips; dormant arm passed G-2's goldens unregenerated; the fresh
cut's FOURTH axis landed as one field + back-flow — the design working); S5
ScopedCarrier (clean priced swap, empty exclusion list); S7 + row 6 (the 1.21.1
save-hook retarget was a checklist walk); V-3 (TestPositions deleted ~100 accessor
sites via the record spelling + absorbed the unforeseen 48-site ticket delta;
BackgroundIoSubmit collapsed the ProcessorMailbox rework to one 69-line file; S4's
cap held); the T1 two-stage regen rule (no drift incidents).

UNDER-DELIVERED: labeled-single-blocks without derivation (failed both live tests:
test-server.sh, build.yml); the runbook as a guard (one overclaiming sentence cost a
MAJOR); hand rows without forcing functions (row 7 skipped); S2 partial (~40
residual consumer lines on the fresh cut — acceptable, no further seam).

## §3 DO-NOT list (hindsight says leave alone)

- No NBT/DFU shim for the serializer bodies (fresh-cut-only cost, byte-parity
  gated; a whole-subsystem seam — §7 stop sign verbatim).
- No gametest annotation abstraction (~85 uniform sites; frameworks require their
  own types; pure sed-recipe territory).
- Don't parameterize the loom plugin/toolchain lines (4-6 lines/line, already
  anchored by ToolchainContractTest; conditional plugin application is fragile).
- Don't abstract FarPlayerRenderer's body (S6/row-12 priced volatile file; only the
  R2-4 phase row is warranted).
- Leave the LanHook mixin per-line (the contract test is already the right shape).
- No Sodium config-API seam (too volatile; a pre-authorized cut already exists).
- No forward merges, ever (P4b stays dead; measurement stays tag-relative).
- Never regenerate `xver-live-corpus` (the cross-version claim itself).
- Don't lower the 1.21.1 fabricloader floor absent a real compatibility pass
  (2026-08-15 decision).
- Don't grow release_check.py into a general line.env engine beyond R2-5's one
  read consuming its three data (folia direction, mapping namespace, ship_neoforge
  — [v1.2] widened from "two targeted reads" to retire the SHIP_NEOFORGE mirror).

## §4 Sequencing and gates

One PR per item or small batches (R2-2+R2-7, R2-4+R2-10). Order: R2-1..R2-3 first
(the failure classes that produced MAJORs), then R2-5/R2-6 (derivations), then
R2-4/R2-8/R2-10 (docs), R2-9 last (behavior change, verification-gated). Every
machinery item runs the standard gates (T1+T2, paper tests, release_check
--selftest where touched); R2-3 additionally runs a 26.2 fresh-backfill smoke;
R2-9 runs the Paper store soaks. The support branches receive NOTHING from this
plan until their next scheduled patch flow. At that merge, the derivation files
CONFLICT textually (both sides edited the same lines) — but every conflict
resolves TAKE-MAIN by construction, because the branch's flavor value already
lives in the data file the main side reads (review MAJOR M2: this is "conflicts
resolve mechanically", NOT "clean merge" — the R2-10 runbook row records the
take-main rule so nobody keep-ours's a derivation away). Out-of-scope flag carried from the analysis: the 1.21.11
branch's gametests still hold the pre-T2 flavored shape — budget that one-time
conflict into the next patch flow, not a pre-emptive branch PR.
