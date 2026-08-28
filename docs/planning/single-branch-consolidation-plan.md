# Single-branch consolidation plan — one branch, five MC lines, three loaders (v2, 2026-08-28)

Retires the support-branch model: every supported MC line (26.2, 26.1,
1.21.11, 1.21.10, 1.21.1) and every loader (Fabric, NeoForge, Paper) builds
from ONE branch (`main`). Inputs: the measured divergence analysis
(`single-branch-divergence-analysis-2026-08-27.md`) and the options research
(`single-branch-options-research-2026-08-27.md`). v1 was reviewed by a
2-Fable + 4-Opus panel (all six APPROVE-WITH-FIXES); v2 IS the fold — §13
records the verdicts and the load-bearing findings. Several §3 mechanisms
were EMPIRICALLY spiked by the panel (Gradle 9.5.1, this repo's wrapper);
where a claim below says "verified", it means run, not reasoned.

## 1. Why this is tractable (the measured shape)

- Of main's 590 Java files, 395 (67%) never diverge on any line. Lag-corrected
  (the branches trail main by ~212 commits; the v0.14 backports erase that),
  the version-attributable surface is ~11 files (1.21.11), ~64 (1.21.10),
  ~114 (1.21.1); 26.1's divergence is almost entirely lag + line data, with a
  small REAL flavor set (the `publishServer` LAN-hook census, 2 xray goldens,
  the far-player render phase — enumerate at Phase 0, do not round to zero).
- 44 of 1.21.1's diverging files (33 of 1.21.10's) differ ONLY by the
  mechanical `Identifier`→`ResourceLocation` rename. A word-bounded rewrite
  reproduces the branch bytes EXACTLY for all 44 (panel-verified). The
  post-rename residual is ~105 files on the worst line — and 65% of those
  differ by <5% of their lines. **The drift is line-shaped, not file-shaped**:
  ~1,550 genuinely differing lines; whole-file overlays for all of it would
  fork ~40k LOC (a 26× amplification). This is why §3.2 is SEAM-FIRST.
- Divergence sets are almost perfectly nested (1.21.1 ⊇ 96% of 1.21.10 ⊇
  1.21.11; 83 files/31k LOC are byte-identical between 1.21.10 and 1.21.1).
- Root `build.gradle`/`settings.gradle`/wrapper are byte-identical on all five
  refs; EVERY plugin version is fleet-identical (loom 1.17.13, paperweight
  2.0.0-beta.21, shadow, moddev, run-paper — verified); the only plugins-block
  divergence in the whole repo is the loom marker id, dissolved in §3.4.
- Exactly TWO real feature cuts exist (Tier 3 on 1.21.1; the Sodium 0.8+
  config walker on 1.21.10 — the latter cascades to ~26 files), both
  expressible as line data + exclusions (§3.2c).
- Main already practices the needed absorption techniques: line-level
  constants (`NativeSectionShape`), single-file seams (`BackgroundIoSubmit`,
  `TestPositions`, `SectionConstruction`), same-FQN twins pinned by
  `VersionVolatileFileListTest` — four of five on main; the fifth (`Gt`, 30
  lines absorbing 550 call sites) is proven on the 1.21.10 branch.

## 2. The decision

**Chosen: a property-switched single-branch build** — one invocation builds
ONE line (`./gradlew -PmcLine=26.1 :fabric:build`; no `-P` = 26.2). The
module layout survives verbatim. The version axis becomes (a) per-line DATA
(`lines/<line>/`), (b) a small deterministic RENAME TABLE for the mechanical
axis, and (c) same-FQN source OVERLAYS **as the bounded exception, not the
default** — the seam-first doctrine (§3.2). No new third-party build
machinery.

Why not the alternatives (full option space in the research doc):
- **Stonecutter** (the strong runner-up): rejected on two grounds that
  survived review — (1) its multi-module composition over a 5-module repo
  WITH a paperweight module is unproven pathfinding by a single-maintainer
  project; (2) the active-version whole-tree comment rewrite is hostile to
  this repo's agent-driven, source-scanning, contract-test-pinned workflow.
  (v1's third ground — "drift is file-shaped" — was measured FALSE by the
  panel and is withdrawn; the seam-first §3.2 is the consequence.) Revisit
  triggers in §12.
- **Prism**: no cross-version sharing (Foxy's trees are ~95% duplicated
  files), no Paper story, bus-factor-1 personal-maven dependency.
- **ReplayMod preprocessor**: its remap machinery is dead weight under
  Mojmap-everywhere; GPL; Stonecutter dominates it.
- **Architectury/MultiLoader**: the loader axis xplat already implements.
- **DH/Manifold**: DH's 21-version release proves PROPERTY-SWITCHING (which
  we adopt); its sharing mechanism is Manifold line-conditionals, NOT
  overlays — the precedent does not transfer to overlay mechanics, which is
  exactly why §3.2 leans on seams and §6 gates the overlay burden.
- **Scope**: all five lines fold, BUT the Phase 1→2 boundary is an explicit
  go/no-go (§6) decided on MEASURED overlay/sync burden from Phases 0-1 —
  the honest form of "maybe stop at the 80/20" (26.1+1.21.11 are nearly
  free; 1.21.10+1.21.1 carry ~95% of the overlay mass and all novel risk).

## 3. Target build architecture

### 3.1 The line data plane

```
lines/<line>/line.env         # CI/release identity (today's .github/line.env)
lines/<line>/line.properties  # build inputs: minecraft_version/_dependency,
                              # fabric_version, neoforge_version, paperweight
                              # bundle, sodium_*, modmenu/moonrise/c2me pins,
                              # line_java_version, mapping_namespace,
                              # tier3_client_gametests, has_modern_sodium, ...
```

- Ownership split is explicit (one live copy per FACT): line.env = workflow/
  release identity; line.properties = Gradle inputs. Values needed by both
  (java version, ship_neoforge) live in ONE of them with a cross-pin test.
- `line.properties` is TOTAL over a fixed key set with EXPLICIT booleans
  (`has_modern_sodium=false`), never key-absence — a contract test asserts
  key-set equality across all `lines/*/line.properties` (absence today
  silently flips dependency/task-input/test arms; verified inventory in the
  panel record). `LINE_FABRIC_MAPPING_NAMESPACE` becomes REQUIRED (no
  default): it is the one artifact-level check that catches a wrong loom arm.
- Root build loads `lines/${mcLine}/line.properties` into ext before modules
  configure (verified: root ext resolves in subprojects, even inside a
  subproject `plugins {}` version string). CLI `-P` wins over line data
  (read `gradle.startParameter.projectProperties` to distinguish). Standing
  invariant: NO line-varying value may be needed inside a `plugins {}` block
  (after §3.4 none is).
- Every `Test` task gets `systemProperty 'lss.line', mcLine` (+
  `inputs.property`) — the channel by which contract tests learn the line
  (in-repo precedent: `lss.sodiumModernGoldenExpected`).
- **The armor chain is rebuilt against the NEW failure mode** ("`-PmcLine`
  says 1.21.1 but the assembly compiled 26.2 sources"): the build stamps a
  generated `line-id.properties` (line, minecraft_version, java target,
  mapping namespace, paperweight bundle) into main+test resources, and the
  toolchain contract tests assert it against OBSERVED facts — resolved MC
  version, compiled class-file major, the processed mixins-config
  `compatibilityLevel`, the accesswidener header token, the jar's
  mapping-namespace manifest attribute.

### 3.2 Sources: seam-first, then the table, then overlays

Order of preference, enforced at review + by the census (§12):
1. **Line-invariant code** (no change) — the 67%+.
2. **Seams**: extend the proven absorbers for drift that is expression-shaped
   — the NBT accessor-family flip gets a `Gt`-style shim; ticket/executor/
   world-height/light-opacity flavors go through `TestPositions`/
   `BackgroundIoSubmit`-class single files; new line facts become
   `NativeSectionShape`-style constants derived from line data.
3. **The rename table** (§3.3) for pure token renames.
4. **Whole-file overlays** — the bounded exception (~15-25 truly architectural
   files per line: FarPlayerRenderer, ScopedCarrier, ChunkSaveDataHook, the
   serializer pair if the accessor shim fails, Gt/TestPositions themselves,
   the line-only wiring files), plus the per-line halves of ~10 contract
   tests and the four hot gametest suites ONLY where shims + table cannot
   express them (each whole-suite overlay needs a fold-record justifying it).

Mechanics (panel-spiked):
- Overlay roots exist for all FIVE source trees: `common/ xplat/ fabric/
  neoforge/ paper/` + `src/line/<line>/{java,resources}` — xplat's overlay
  root is wired into BOTH consuming modules. A shared convention script
  (`gradle/line.gradle`, applied per module) owns the wiring once.
- **The naive "exclude the shared path" is BROKEN** (verified: source-set
  exclude patterns apply to every srcDir, deleting the overlay too; naive
  resource overlays HARD-FAIL on Gradle 9 duplicate handling, and
  `duplicatesStrategy=INCLUDE` silently ships the WRONG line's resources).
  The mechanism is a **per-line source ASSEMBLY**: one Sync task merges
  shared sources + rename-table output + overlays (overlay wins) into
  `build/generated/lineSrc/<sourceSet>/`, and non-default lines compile from
  the assembled root only. **The default line (26.2) is PINNED to an EMPTY
  overlay set and EMPTY rename table** (empty of java; per-line resources
  excepted), so 26.2 compiles from the plain `src/` roots — day-to-day DX
  and IDE behavior are bit-identical to today, and generated roots exist
  only for ported lines (this rule is what keeps jdtls sane; overlay dirs
  are ignore-by-path for diagnostics).
- The accesswidener is a COMMITTED per-line file, never templated — loom
  consumes `accessWidenerPath` during jar provisioning, before any resource
  task runs; the path is computed per line.
- **Cuts** are their own artifact: `lines/<line>/excludes.txt` (delete, not
  replace). Pinned: every excluded path exists in the shared tree AND traces
  to a `pre-authorized-cuts.md` row or a dated decisions-log entry — the cut
  protocol made mechanical.
- **Overlay provenance stamps** (the highest-value single addition, per two
  independent reviewers): every overlay file's header carries
  `// OVERLAY OF <shared path> @ <sha256-of-shared-file>`; a contract test
  reds when the shared file's hash moves without the stamp being refreshed.
  Body drift can no longer stale silently — the exact backport-debt failure
  this program exists to end.
- **OverlayParityTest** (respecified — reflective comparison is impossible,
  both files share one FQN): a SOURCE/BYTECODE-level check (parse both, or
  ASM over a throwaway compile of the shadowed file) comparing the seam
  surface CONSUMED BY SHARED CODE (public/protected members), with per-file
  waivers for legitimate surface changes (`SectionConstruction`'s ctor).
  Covers main, test, and gametest sets. Where an overlay is a serializer/
  wire surface, the existing BYTE-GOLDEN corpus discipline is the behavior
  pin, not signatures.
- **Nested-line sharing**: an overlay resolution chain
  (`src/line/1.21.1` → `src/line/java21` → shared, or per-file `SAME AS
  <line>` markers) so the 83 files byte-identical between 1.21.10 and 1.21.1
  and the six ScopedCarrier-class copies exist ONCE; a duplicate detector in
  the census reports any byte-identical pair.
- **Source-scanning tests become line-aware**: `SourcePaths` (and the ~31
  walkers, ~6 of them independent) resolve overlay-first for the active line,
  honor `excludes.txt`, and treat shared↔overlay as the one legal multi-tree
  pair; `XplatLoaderPurityTest`/`XplatJava21SurfaceTest` scan
  `xplat/src/line/*/java` too. Budgeted in Phase 0 (centralizable to
  SourcePaths for most).
- Binary goldens use **override-resolution, not whole-copy**:
  `.../resources/line/<line>/<corpus>/<name>.bin` wins over the shared file
  (26.1 then carries 1 file, not 28 duplicates that stale silently). The
  nbt-corpus and v20-corpus move TOGETHER (their set-equality/count pins are
  coupled); `xver-live-corpus` stays fully SHARED (byte-identical on all
  five refs — the cross-version claim) while its DECODE TEST is overlaid on
  1.21.1 (the +84-line divergence is the test, not the fixture). The
  count/set pins become resolver-driven. Golden REGENERATION becomes a
  `workflow_dispatch` matrix workflow (regenerate per line in CI, upload as
  artifacts) with the local five-toolchain loop as fallback.

### 3.3 The rename table

- Scope: **type-name renames only, plus the single verified member rename**
  — `net.minecraft.resources.Identifier`→`ResourceLocation` (FQN + bare
  token, word-bounded) and `.identifier()`→`.location()` — each entry
  verified byte-exact against the branch at fold time. Everything else in
  the drift catalogue (accessor families, arities, expression changes) is
  OUT of table scope — seams or overlays. The table never chains with an
  overlay for the same file (overlays are written in the line's dialect).
- Semantics: word-boundary Java-identifier rewriting applied uniformly to
  code, comments, and string literals, with an explicit deny-list; a pin
  asserts no shared file ever grows a bare table-token inside a string
  literal (silent runtime-string corruption otherwise).
- Discipline pins: (a) every entry occurs in ≥1 shared file (so entries are
  DELETED when main stops using a token — the table is NOT append-only);
  (b) idempotent (re-applying is a no-op); (c) non-overlapping (no entry's
  output is another's input); (d) generated dirs never committed; (e) the
  26.2 table is empty.
- Runs inside the §3.2 assembly Sync (one mechanism, not two).

### 3.4 Toolchains, loom, paperweight, build dirs

- **The loom axis is a solved one-liner, not a novel mechanism** (panel-
  verified from the loom 1.17.13 artifact): `fabric-loom`,
  `net.fabricmc.fabric-loom`, and `net.fabricmc.fabric-loom-remap` are three
  marker plugins in ONE jar; the no-remap/remap choice is
  `PluginManager.hasPlugin` + the `fabric.loom.dontRemap` property. One
  classpath entry (`apply false` at root), conditional `apply plugin:` per
  line; `-Pfabric.loom.dontRemap` is the belt. **v1's fallback ("unify on
  loom-remap everywhere") is STRUCK** — it would remap 26.x release jars out
  of the `official` namespace: right-named, gate-green, unloadable (the
  exact disaster release_check's namespace pin documents).
- The release-jar task name flips with the arm (`jar` vs `remapJar`); exactly
  one consumer exists today (`vssJar dependsOn`) — parameterize
  `releaseJarTask` in the convention script.
- `line_java_version` drives `options.release` / toolchain / mixin
  `compatibilityLevel` (templated into the line's mixins descriptors —
  runtime-read resources, templating is fine THERE; never the AW, §3.2).
- **Paperweight/codebook**: `scripts/line.sh` selects the DAEMON JDK per line
  via `-Dorg.gradle.java.home` (one daemon per JVM generation). A Java-25
  daemon on a 1.21.x line is a RECORDED silent-failure configuration
  (paperweightUserdevSetup silently failing, "paper compiles clean" false) —
  `javaLauncher`-only is an untested optimization to spike in Phase 1 with
  an accept/reject criterion, not the plan of record. In CI each matrix job
  runs setup-java with the line's JDK. Paperweight PLUGIN version is
  fleet-identical (verified); only the dev-bundle coordinate is line data.
- **Per-line build directories**: `layout.buildDirectory = build/<line>`.
  Required — a shared build dir makes `release_check`'s stale-jar guard red
  by construction on any multi-line loop, lets `release.yml` globs attach
  another line's jar, silently overwrites the unversioned dev jars the local
  rigs install, and hands 26.2 run-dir WORLDS to 1.21.x servers. Migration
  budget: ~22 `build/libs` references across scripts/workflows/docs, plus
  the gametest evidence-copy paths and `XplatJava21SurfaceTest`'s classes
  path. Decided and landed in Phase 0.
- ~10 per-line BUILD-SCRIPT arms exist beyond data (Tier-3 poison-pill block,
  `mappings loom.officialMojangMappings()`, `modImplementation` scopes, the
  three sodium arms, `neoforge_floor`, folia derivation, the 1.21.1 FML-4
  run wiring, the NeoForge gametest filter idiom, `jar`↔`remapJar`). §3
  carries a **build-script conditional inventory** with a pin per silent-
  failure-capable arm (the 1.21.1 `--tests` silent-no-op precedent: a
  gametest-count floor assertion).

### 3.5 What stays exactly as-is

The loader axis (xplat srcDir + LoaderServices + loader twins), the wire
surface (never tiered; xver corpus pinned shared), vssJars (one
`releaseJarTask` edit), soakShadowJar, the jarJar sqlite shape, and the
test-suite content outside the overlay/contract set.

## 4. Testing rework

- **Local**: `scripts/line.sh <line> <task...>` = JDK selection + `-PmcLine`
  + per-line build dir; bare `./gradlew` = 26.2, unchanged.
- **CI (build.yml)**: ONE workflow; **the matrix is GENERATED from `lines/`
  by a setup job** (line, java, tier3, ship_neoforge, paper_loaders as JSON
  outputs) — never a literal list; this keeps the no-hardcoded-MC-tokens pin
  in its strongest form and is cross-pinned (matrix source ⇔ `lines/*` set
  equality, so a new line dir cannot silently not-build). Per job: line JDK →
  gates (script selftests, T1, T2, `:paper:test`, `:neoforge:build` + its
  8-test gametest smoke, VSS families — the job list is build.yml's,
  verbatim) → Tier 3 only where the line data says so (job existence from
  the matrix payload, not step-skips). `fail-fast: false` (MANDATORY — the
  cross-line signal is the point). `concurrency` group per ref. Every
  artifact name gains `-<line>` (upload-artifact v4 rejects duplicates —
  the first matrix run reds without this). Gametest retries + flake evidence
  survive per job (evidence names line-suffixed; catalog guidance updated).
- **Cache reality** (measured): the repo's Actions cache is ALREADY over
  quota (12.57 GB active vs 10 GB), and five lines' gradle-homes ≈ 10.2 GB
  alone. Phase 0 MEASURES a genuinely cold non-default-line job (no cold
  datum exists — every branch run restores main's cache) and picks the
  strategy: trimmed `gradle-home-cache-includes`, `cache-read-only` on
  non-default lines, or an accepted cold slow-lane for the oldest lines.
  Wall-time today: ~325 s median per push; the matrix is ~36 runner-minutes
  but PARALLEL — PR latency stays ~7-11 min IF caches hold, which is why
  the cache strategy is a Phase-0 gate item, not a footnote.
- **Merge enforcement is a Phase-0 deliverable, not an assumption**: the repo
  currently has NO required status checks (verified — `require pr` with 0
  approvals only). Add an always-reporting aggregator job (`needs:` all
  matrix jobs, `if: always()`) as THE single required check, with the
  docs-only path filter moved into an always-running job so required checks
  never deadlock docs PRs. Fork PRs need the maintainer-approval note.
- **Contract pins: the three-disposition protocol** (recorded per pin at
  cutover): (a) AGREEMENT pins (datum vs independently-produced artifact —
  the folia derivation, ToolchainContractTest's chain) convert unchanged,
  keyed by `lss.line`; (b) VALUE pins (conscious-flip literals —
  ship_neoforge, folia, tier3, make_latest, java, namespace) move to ONE
  `line-expectations` table in the TEST tree, asserted fleet-wide by a new
  `LineMatrixContractTest` that reads ALL lines' data at once — a flip
  anywhere reds everywhere, strictly STRONGER than the branch model, and a
  flip stays a two-file two-directory diff; (c) RETIRED pins (the
  forward-merge armor: LINE_TAG_SUFFIX guard, line-scoped PREV_TAG, branch
  globs, double-clobber chain) are deleted BY NAMED DECISION with their
  threat models recorded — not by attrition.
- **Version-conditional tests**: the `SodiumLegacySurfaceResolvesTest`
  discipline is the house pattern — guard on a POSITIVE per-line datum
  (`lss.line`-derived), degrade to assumption off-line, and **escalate
  skips to hard failure under CI=true** (silent-skip is the recorded
  failure mode). Never branch expectations on sniffed runtime versions;
  sniffing to ASSERT AGAINST line data is the required pattern (the chain).
- **Hand rows become asserted line data**: the surfaces table's semantic
  per-line facts that compile identically everywhere (far-player render
  event, Bukkit world layout, Moonrise entry class) move into
  `lines/<line>/line.properties` (e.g. `far_player_render_event=
  COLLECT_SUBMITS`, `bukkit_world_layout=unified`) with per-line contract
  tests pinning source/bytecode against the datum — converting the port
  ritual's judgment rows into agreement pins. This is the ONLY mechanism
  that survives the loss of the per-port re-verification ritual (row 15
  shipped wrong once under human review).
- **Harnesses (honest sizing: 3-5 focused days, IN Phase 0** — both
  `soak.sh` and `test-server.sh` read `gradle.properties`/`line.env` on
  their first lines and break the moment the data plane lands): ~2,200
  lines of shell + five chained extras gain the line axis — per-line base
  worlds `soak-worlds/<line>/{base,base-paper,base-folia}` (~25 MB each;
  DataVersion-bound, and the existing wipe-on-mismatch guard would
  otherwise thrash), `(line, platform)` cache/world markers, per-line
  download tables and port offsets, folia REFUSED BY DATA on lines without
  a Folia build, dev jars line-stamped (or per-line dirs) so local rigs
  can't silently deploy the wrong line's jar.
- **Flake catalog**: `POLL_DEADLINE_NANOS` unifies at 60 s at cutover (the
  catalog's own escalation; a starvation deadline is never an overlay);
  Tier-1 runs 5× with NO retry — either add one retry or accept ~5× the
  Tier-1 flake-red rate and say so; the catalog is rewritten from branch
  vocabulary to line vocabulary at Phase 4 (line-conditional entries become
  matrix-job conditions).
- Support-tier budgets unchanged: full gauntlets/soaks/live rig remain
  26.2-only; the matrix guarantees BUILD + T1/T2 (+T3 where present) per
  line (see §10's heading).

## 5. Release rework

ONE annotated tag (`--cleanup=verbatim` unchanged) → a four-stage pipeline
(the current 3-publish-action release becomes ~15 irreversible actions on
one trigger; the stages exist to make that safe):

```
tag vX.Y.Z
 ├─ build        [matrix from lines/]: line JDK → jar families →
 │               release_check --line → upload-artifact (NO publishing)
 ├─ github-release  needs: build(full-tier lines)
 │               extract notes ONCE (the lightweight-tag force-fetch repair
 │               runs once, not five times) → create the release as DRAFT →
 │               attach ALL lines' assets → one body, one make_latest
 ├─ modrinth     [matrix]: needs github-release; per line×loader publish
 │               with a labrinth PRE-CHECK (GET the version_number,
 │               skip-if-present) — Modrinth accepts duplicates silently,
 │               so idempotency is OURS; this is what makes
 │               `gh run rerun --failed` safe for exactly the failed line
 └─ finalize     needs: modrinth; draft→published, latest set once
```

- **Tier rule on the line axis** (the release.yml ordering doctrine
  extended): full-tier lines (26.2/26.1) GATE the publish; correct-not-
  perfect and best-effort lines are `continue-on-error` with a loud summary
  — a 1.21.1 flake must never block 26.2's release. The line-axis twin of
  the NeoForge-step ordering pin goes into the contract tests.
- `make_latest` and the body become RELEASE-level facts set once in the
  fan-in (LINE_MAKE_LATEST retires as per-line data; its pin is rewritten,
  not re-homed). A wrong Modrinth changelog is now 15 hand-edits — the
  single-extraction design point is recorded.
- **`release_check.py`**: import-time line constants refactor into a
  resolved config (`--line` selects `lines/<line>/` + the per-line build
  dir); `--selftest` keeps covering both polarities via fixtures. The
  stale-jar guard is NOT taught to tolerate sibling lines — per-line build
  dirs (§3.4) are the fix. `scripts/preflight.sh` loops lines serially over
  per-line dirs.
- **`release-neoforge.yml`** (the backfill recovery tool — MORE necessary
  under one tag, not less): gains a `line` input reading `lines/<line>/`,
  and its dedupe matches the line's EXACT asset name, not the substring
  "neoforge" (which would refuse every line after the first).
- The `workflow_dispatch` + `dry_run` rehearsal lever SURVIVES the matrix
  (the only pre-tag rehearsal of an irreversible pipeline; cost ×5, value
  higher). The numeric-MOD_VERSION guard survives. `PREV_TAG` keeps
  `grep -v '+mc'` forever (the historical `+mcX` tag family stays).
- Hotfix consequence, accepted and recorded: under one tag, a one-line fix
  republishes all five lines (new Modrinth versions everywhere).
- VSS: `vssJars` per line in the matrix (artifact-only, release.yml stays
  VSS-free — pinned); the LOCAL VSS Modrinth process gains a per-line
  staging step (five trees became one; jars must be staged per line before
  the next line's build lands). Out of scope: VSS Modrinth publishing.

## 6. Migration plan (phased PR trains on main)

**Release-capability invariant (every phase's exit gate): at the end of the
phase, main can cut a COMPLETE release for every folded line, and each
folded line's branch has its release path FROZEN** (its release.yml
neutered/tripwired in a terminal commit) — otherwise main and the branch
can both publish `vX+mcY` and Modrinth accepts the duplicate silently.
Unfolded lines keep releasing from their branches (tag-triggered workflows
run the tag's own tree — works by construction, recorded here).

- **Phase 0 — scaffolding + the REAL spikes (26.1).** The data plane +
  assembly mechanism (§3.1/§3.2) + per-line build dirs + line-aware
  SourcePaths/walkers + the harness line axis (it breaks day one otherwise)
  + required-status aggregator + the CI matrix (26.2+26.1 jobs, generated)
  + cold-cache measurement + **the loom conditional-apply spike** (26.1
  exercises none of the structural axis — the spike compiles the fabric
  module against 1.21.11 pins with the remap arm, throwaway) + fold 26.1
  (line data, the enumerated small overlay set, 1-2 golden overrides,
  line-agnostic CLAUDE/README content). Gates: §8 for 26.1 AND the 26.2
  identity gate. DELIVERABLE GATE for the program: assembly mechanics,
  matrix, and the loom spike all green, else re-evaluate against
  Stonecutter (§12) — the spike must precede that evaluation to be
  meaningful.
- **Phase 1 — 1.21.11.** The Java-21 axis for real (daemon JDK discipline,
  ScopedCarrier via the java21 chain level, XplatJava21SurfaceTest extended
  to shared+overlay trees), the loom-remap arm in production, `remapJar`
  release-task parameterization + vssJar wiring, ~11 overlays/seams, full
  golden overrides, the javaLauncher spike (accept/reject).
- **GO/NO-GO** — measured overlay count + provenance-stamp churn from
  Phases 0-1 decide whether 1.21.10/1.21.1 fold or stay branches (the 80/20
  option, decided on data, recorded either way as a dated decision).
- **Phase 2 — 1.21.10.** The rename table live; the Sodium cut via
  excludes.txt (+~26 cascade files) + `has_modern_sodium=false` arms; the
  `Gt` shim adopted as the shared absorber (main's suites reroute — it is
  harmless on lines with the String overloads); ~30 overlays/seams;
  POLL_DEADLINE unification.
- **Phase 3 — 1.21.1.** The hard core: NBT accessor-family seam-or-overlay
  decision per file, FarPlayerRenderer/ChunkSaveDataHook overlays, Bukkit
  split-world seam datum, ticket API seam, Tier-3 cut via line data +
  excludes, FML-4 run wiring arm, gametest annotation dialect via the table
  (`structure=`→`template=` is expressible; spike it) or suite overlays
  with fold-records. Honest effort: this is a multi-week phase-equivalent
  of the original 1.21.1 port program, not "several days" — but it is a
  TRANSPORT of existing branch content into overlays/seams, not a
  re-derivation.
- **Phase 4 — decommission.** Archive branches (`archive/…`, terminal
  banner commits: "this line builds from main via -PmcLine"); align the
  dormant 1.21.8/1.20.1 branches with the same naming or record why not;
  build.yml `support/**` triggers removed; `release-neoforge.yml` line
  input; CLAUDE.md rewritten (tier/port/flake sections in line vocabulary;
  the `.github/line.env` path references swept); per-version-surfaces.md
  RESTRUCTURED to rows × 5 line columns (value, carrier file, pin-or-hand +
  verification date per cell) — this restructure replaces the branch
  banners AND the runbook's step-7 ritual; `port-runbook.md` replaced by
  `line-onboarding-runbook.md` (how a NEW MC line arrives: lines/ dir,
  base-line policy — the next base-line move re-roots shared sources and
  births a 26.2 overlay, decided there — overlay generation, fixture
  regeneration order incl. the v20-first rule, surfaces walk, matrix row);
  the backport-plan genre declared closed.
- Sequencing: each phase folds the line's post-v0.14.0 state; phases
  serialize behind that line's v0.14.0 release.

## 7. Developer & agent workflow after cutover

- `main` has everything; `rg`/reads see all lines' code as plain Java. A
  shared-file fix fixes every line; a line-behavior fix edits that line's
  overlay (and refreshes its provenance stamp — the test reminds you); the
  CI matrix arbitrates.
- 26.2 works exactly as today (plain src/ roots — §3.2's empty-default pin).
  jdtls: overlay dirs are excluded-by-path from diagnostics; only the
  assembled line you explicitly build resolves beyond 26.2.
- `scripts/line.sh 1.21.1 :fabric:test` is the whole per-line story locally.

## 8. Correctness gates (EVERY phase, per folded line)

1. The full build.yml job list green via `-PmcLine` (T1, T2, `:paper:test`,
   `:neoforge:build` + gametest smoke, VSS families, script selftests,
   + Tier 3 where the line has it).
2. `release_check.py --line <line> --version <next>` OK against the line's
   build dir.
3. Jar-content parity audit vs the branch at the PINNED equivalence commit
   (the line's v0.14.0 state; main built at the matching content point):
   class LIST + resource set for the jar families, and class BYTES for
   overlay-provided and rename-generated classes specifically (a stale
   overlay is exactly what the byte check catches; compiled-constant noise
   is why the rest is list-level). One-off scripted audit per fold.
4. The line's soak smoke per its support-tier budget (representative set;
   scenario lists named from the tier doc, folia only where the line has it).
5. **The 26.2 identity gate, every phase**: full 26.2 CI green AND
   class-digest identity of the 26.2 jar families (LSS + VSS) against
   pre-phase main — the production line is the one line with no migration
   gate otherwise (precedent: the port-isolation program's byte-identity
   gate).

## 9. VSS building

`vssJars` is line-agnostic except the one `releaseJarTask` dependency edit
(§3.4); release_check's VSS pins run per line over per-line build dirs; the
local Modrinth publish flow gains per-line staging. Nothing depends on VSS
Modrinth publishing (release.yml stays pinned VSS-free).

## 10. CLAUDE.md rule updates — full BUILD+TEST coverage on every line from the start

(The heading is precise: behavioral budgets — soaks, live rig — stay
tiered per the support-tier doc; the two axes, support commitment and
correctness confidence, both survive. The full-matrix merge gate is itself
a DELIBERATE tier change — best-effort lines move from "port later, cut
freely" to "resolve at PR time" — recorded as a dated decision with the
deferral valve below.)

Each rule names its enforcement: [CI] = mechanical matrix/check, [CT] =
contract test, [R] = review convention.

1. **Every PR is green on the full line matrix before merge** [CI: the
   required aggregator check]. A feature a line cannot carry takes, IN THE
   SAME PR, either a pre-authorized cut (`pre-authorized-cuts.md` — no new
   decision needed) or a dated decisions-log cut entry — **and cuts are
   legal only on best-effort lines**; a cut on a full or correct-not-perfect
   line is a tier decision for the program owner [CT: excludes.txt traces to
   a cut row; R: the tier judgment]. The deferral valve: a PR may park a
   best-effort line behind a cut entry rather than solve it, keeping the
   tier's economics.
2. **New MC-facing code goes through the ladder: invariant → seam → table →
   overlay** (§3.2's order) [R], with per-line SEMANTIC facts asserted as
   line data (§4's hand-row conversion) [CT]. The compiler + matrix catch
   API absence [CI]; the provenance stamp catches overlay staleness [CT];
   hand rows that remain hand (none should) carry a dated verification cell
   in the surfaces table [R].
3. **Tests ride the line axis via positive line-data guards** (the
   SodiumLegacySurfaceResolvesTest discipline: datum-guarded, assumption
   off-line, hard-fail on unexpected skip under CI) [CT]; never branch
   expectations on sniffed runtime versions — sniff only to assert against
   line data [R, pinned by example].
4. **The rename table is scoped data**: type-name renames (+ the verified
   `.identifier()` member rename) only; entries live-referenced, idempotent,
   non-overlapping; 26.2's table and overlay set stay EMPTY [CT: the
   RenameTableContractTest pins all five].
5. **Overlays carry provenance stamps and are written in the line's
   dialect**; generated dirs never committed [CT].
6. **Shared code targets the LOWEST supported line's Java level**; 25-only
   constructs live in overlays/seams [CI: three matrix jobs compile at
   release 21; CT: the Java-21 surface scan extended beyond xplat to every
   module's shared sources].

## 11. Risks and mitigations

- **Overlay sync burden** (the program's real risk — 30% of recent main
  commits touched the projected version-hot set): seam-first bounds the
  overlay count; provenance stamps make staleness loud; the census (§12)
  makes growth visible; the go/no-go makes the burden a decision input.
- **Actions cache quota** (already exceeded today): Phase-0 measured
  strategy; worst case, non-default lines run cold and slower — recorded,
  not discovered.
- **Tier-1 flake exposure ×5** with no retry: decided in Phase 0 (retry or
  accept + catalog note).
- **Release pipeline concentration** (15 irreversible actions, one tag):
  the four-stage draft/pre-check/finalize design + the tier rule + the
  backfill workflow are the mitigations; the dry-run rehearsal survives.
- **A future MC line with intra-file drift everywhere**: §12.
- The dormant 1.21.8/1.20.1 branches stay frozen (naming aligned at
  Phase 4).

## 12. Revisit triggers (measured, owned)

A `lineOverlayCensus` task prints per-line counts (java overlay files under
`*/src/line/<line>/java/**` — the denominator is java files only) in every
CI job summary and REDS above threshold with "revisit §12". Re-open the
Stonecutter option if: Phase 0's assembly/matrix/loom gate fails; any
line's java overlay census exceeds 150; or at a new line's onboarding >30%
of its would-be overlays differ from shared by <10 lines (the intra-file-
drift proxy, evaluated in the onboarding runbook).

## 13. Review fold record (2-Fable + 4-Opus panel, 2026-08-28)

All six: APPROVE-WITH-FIXES; every MAJOR folded above. Headlines per lens:
- **F1 (decision)**: the choice stands on grounds (a)+(b); ground (c)
  ("file-shaped drift") measured FALSE → §3.2 inverted to seam-first with
  overlays bounded (~15-25/line vs v1's 60-80); provenance stamps; nested-
  chain sharing; rename-table safety semantics (word-bounded rewrite
  verified byte-exact on all 44 pure-rename files; zero bare-token string
  literals today); the Phase 1→2 go/no-go; the §10 tier-change recording.
- **F2 (migration)**: the loom spike moved INTO Phase 0 (26.1 exercises no
  structural axis — the go/no-go was gating the wrong risk); gate 3 got the
  pinned-equivalence-commit discipline + overlay byte comparison; the
  per-phase 26.2 identity gate; Phase-4 completeness (banners, triggers,
  flake catalog, archive naming); effort honesty (Phase 0 carries more than
  v1 said; Phase 3 is multi-week; estimates otherwise calibrated against
  the repo's actual port history).
- **O1 (gradle, spiked)**: naive srcDir exclusion deletes the overlay too
  (verified) → the assembly mechanism; resource overlays hard-fail /
  silently pick the wrong line (verified) → same assembly + the committed
  accesswidener carve-out; the loom marker-plugin proof (one artifact,
  conditional apply, dontRemap belt) and the STRIKING of the
  unify-on-loom-remap fallback (wrong-namespace jars); line.properties
  totality + explicit booleans; the `lss.line` test channel + the line-id
  observed-facts armor chain; xplat overlay roots + the convention script;
  per-line build dirs with the 22-reference budget; the build-script arm
  inventory (the `--tests` silent-no-op precedent).
- **O2 (testing, measured)**: artifact-name suffixes (v4 rejects
  duplicates); the cache-quota numbers (12.57 GB > 10 GB today) + the
  cold-cache Phase-0 measurement; required checks + aggregator + fail-fast
  + the paths-ignore deadlock; the matrix generated from lines/ (saves the
  no-tokens pin); the three-disposition pin protocol + the fleet-wide
  LineMatrixContractTest (strictly stronger than branches); goldens
  override-resolution + coupled corpora + the dispatch regen workflow;
  SourcePaths line-awareness (31 walkers); the harness sizing (3-5 days,
  Phase 0) incl. per-line base worlds; POLL_DEADLINE unification; the
  hand-row → line-data conversion.
- **O3 (release)**: the four-stage pipeline (draft fan-in, labrinth
  pre-check idempotency, finalize); the tier rule on the line axis; the
  release-capability invariant + per-phase branch freeze (the silent
  Modrinth-duplicate window); release_check's import-time refactor +
  per-line dirs; the backfill workflow's line input + exact-name dedupe;
  notes extracted once; the retired-pin named list; make_latest as a
  release-level fact.
- **O4 (docs/rules)**: xplat unrepresentable in v1's layout; excludes.txt
  for cuts (pinned to the cut protocol); OverlayParityTest respecified
  (reflection impossible on one FQN); provenance stamps (independently of
  F1); SourcePaths multi-tree assertion; §10 rules 1/2/3/4 corrected
  (pre-authorized leg + best-effort scope; enforcement honesty; the
  sniff-to-assert pattern; table not append-only) + rules 5/6 added (Java
  floor; stamps); the surfaces-table restructure (rows × line columns) as
  the banners'/runbook's successor; the line-onboarding runbook + base-line
  policy; §12 made measurable; the numbers cross-check (nested 96%, Gt
  attribution, counts derivation, resource-empty caveat).
