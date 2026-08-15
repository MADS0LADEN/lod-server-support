# Version-Port Isolation Plan — making the 26.2 mainline cheaper to carry to older MC lines

**v1.1 — 2026-08-14. Status: PROPOSAL, awaiting user decision (no work scheduled).**
v1.0 was reviewed by three Opus reviewers (evidence/value, seam soundness,
pipeline/process — all MERGE WITH FIXES); every finding is folded here, §9 records
the round. Evidence base: two research passes over the real port record —
(A) forensics on the actual support-branch deltas (`support/mc26.1-v0.10`,
`support/mc1.21.11-v0.10`, skims of `mc1.21.8`/`mc1.20.1`, the tri-release +
backport reports), and (B) a classified inventory of mainline's MC-volatile
surface cross-referenced with what historically churned.

## 0. The corrected cost model (read this before judging any item)

The naive measurement (`git diff main...support/<line>`) **overstates the port
surface 2-3×**: the v0.10 branches received post-cut mainline work as cherry-picks,
which three-dot diffs count as branch carry forever (measured: 2.97× on 26.1; the
apparent ~592-line "config hotspot" is byte-identical files counted as carry).
Measured against the contemporaneous tag (`git diff v0.10.0 origin/support/<line>`),
the TRUE port surface (reviewer-reproduced to the line):

| Line | True delta | Dominant buckets |
|---|---|---|
| 26.1 (minor) | 31 files, +357/−708 | **release.yml + ReleaseWorkflowContractTest = 180 ins = 50.4%**; metadata/toolchain pins 11%; harness retarget 10%; exactly ONE MC API (publishServer mixin descriptor) 10% |
| 1.21.11 (major) | 85 files (incl. 34 regenerated goldens), +995/−671 | count-short wire shape 28% — of which S1's code reach is ~134 and the rest is tooling/tests T1/T4 own; mechanical renames 17% (dominated by gametests); toolchain/Java-21 13%; release flavor 18% |

**Denomination caveat (review MAJOR):** these numbers describe the v0.10-era tree.
Stage N (2026-08-14) landed `xplat/` + `neoforge/` on main, which (a) moved most
§3 file paths into `xplat/`, and (b) added per-line surface that no historical port
priced: `neoforge.mods.toml` (main + gametest twin), `neoforge/build.gradle` pins,
the NeoForge Modrinth step, `NeoForge*ContractTest` constants,
`XplatJava21SurfaceTest`'s exclusion list — estimated +30-60 lines/line. The plan
prices it inside P1-P3 (the NeoForge rows are the same data classes) and §8
excludes first-port-of-a-new-module surface from its targets.

Two corollaries that shape everything below:

1. **The biggest lever is not MC code.** Half of an easy port and ~a third of a
   hard one is release-pipeline and metadata flavoring a data file can carry — and
   at stage G that bucket multiplies to four lines × three loaders.
2. **The genuine MC churn is concentrated and enumerable.** Four recorded ports
   agree on the same short list: the serialization cluster (native section shape),
   mixin injection targets, section construction, mechanical renames, Sodium's
   config API. Everything else (payloads since 1.20.5, DFU wrappers, the whole
   `common/` wire/store/governor stack) is measured-stable.

**Honest ROI statement (review):** the P-workstream buys down a *permanent
per-release tax*. The S-workstream buys down the two cheap re-cuts and makes the
expensive one (1.21.1, spike-priced 12-18 days) *safer* — its dominant drivers
(serializer rework, FarPlayerRenderer rework, bulk renames) are reduced barely or
not at all. Nobody should read §6's ~7 days as buying down the 12-18.

## 1. Goals and non-goals

**Goals.** (1) Shrink the true per-line port delta, prioritized by measured cost.
(2) Where a delta is irreducible (real MC API differences), make it *isolated*
(one file / one data row) and *obvious* (a contract test or checklist row names it
before the port starts). (3) Make the port *process* self-measuring and non-lossy.

**Non-goals.** No single-branch multi-version preprocessor (fights the
branch/soak/baseline model). No "VersionServices" god-interface (churn is
concentrated; targeted micro-seams win). No payload/networking-layer work (stable
since 1.20.5). No `common/` changes beyond S1's cursor field. Wire compatibility
stays never-tiered; nothing here touches wire semantics.

## 2. Workstream P — pipeline & metadata parameterization (the 51% bucket)

**P1. Line-parameterized release.yml.** Data source: **`.github/line.env`**,
sourced into `$GITHUB_ENV` (`cat .github/line.env >> "$GITHUB_ENV"`; multi-line
values via the heredoc delimiter syntax; `with:` inputs read `${{ env.X }}`).
Deliberately NOT `gradle.properties` — see §7 (release_check/CI-naming blast
radius; `minecraft_version` stays frozen in name and position). Inventory
(~10-11 values, three types): tag suffix; fabric MC token; **paper MC token
(differs from fabric's on the same line** — `+paper+mc26.1.2` vs `+fabric+mc26.1`);
neoforge MC token; three game-versions LISTS (multi-line); `make_latest`;
display-name suffixes; the NeoForge version-name PROSE (per-line — main's
"server-side, no community Voxy build" caveat is factually wrong on 1.21.1 where
the j-shelfwood fork exists; the labrinth 64-char pin must compute over the
*resolved* name); the guard token.

Mechanism decisions (review MAJORs, decided here, not left to the implementer):
- **The `on.push.tags` filter cannot be parameterized** (GitHub evaluates `on:`
  before any context; repo variables are not branch-scoped). Instead the trigger
  becomes *invariant by broadening*: `tags: ['v*']` on every line, with 100% of
  scoping in the guard. This deliberately REVERSES the support-branch decision
  "a bare v* tag pushed from this branch must not publish": under the new guard a
  wrong-line tag produces a **red guarded run** (before checkout of nothing — see
  next bullet) instead of silence — strictly louder, publishes nothing.
- **The guard moves to immediately-after-checkout** (a data file is only readable
  post-checkout) and the pinned ordering invariant is re-stated as "before any
  build, gate, or publish step" (checkout publishes nothing). This is a deliberate
  re-pin, recorded here so the contract-test edit is not an accident under
  pressure. Guard form: extract the tag's `+mc…` suffix (empty on main) and
  require equality with `$LINE_TAG_SUFFIX` — one predicate, all lines, both
  polarities of today's inverted guards.
- **Main adopts the support lines' structural shape** (the support release.yml is
  not "main + data"): the `Derive mod_version` step (`${TAG#v}`, `%%+*` — a no-op
  on main), the `PREV_TAG` fallback ladder (`git describe --exclude 'v*+mc*'`),
  `make_latest` from data. Steps then reference `v${MOD_VERSION}` uniformly.
- **A pinned dry-run lever** (review MAJOR — an irreversible-publish pipeline has
  no throwaway-tag test path, and its first real run must not be stage G's
  12-artifact round): `workflow_dispatch` with a `dry_run` input **default
  `true`**, `if:`-gating the gh-release + Modrinth steps. Every branch can then
  exercise the fully-resolved env end-to-end. P2 pins the default.
- `release_check.py`'s `FORBIDDEN_LINE_TOKENS` goes vacuous once release.yml
  carries no MC tokens. AS BUILT (round-3 review reconciliation): the data-file
  validation landed in `ReleaseWorkflowContractTest` (which runs via `:paper:test`
  before every publish), NOT in release_check — nothing is unguarded; revisit a
  release_check data-file arm at G when the token list grows to four entries.

**P2. ReleaseWorkflowContractTest reads the same data** (+110/+91 of duplicated
constants deleted), with an `IS_MAINLINE` switch for mainline-only pins (VSS
absence, tri-line tag scoping) and new pins: the guard's position + predicate,
`dry_run: true` default, the resolved-name 64-char computation.
**Anti-merge armor, strongest form (review):** the risk is a forward merge
clobbering line.env while workflow AND test read it — green-but-wrong. Mitigation
is a three-link chain where each link reds independently: line.env ↔
`gradle.properties`' `minecraft_version` ↔ **the resolved MC artifact itself**
(`SharedConstants.getCurrentVersion()` at test time — the artifact-level pin lives
in the hoisted `ToolchainContractTest`, T3c, fabric + paper twins). A human
resolving gradle.properties as "theirs" now reds against the actual jar on the
classpath. Host coupling stays documented: this test lives in `:paper:test`
*because* both workflows run `:paper:test` before any publish step — if a line
ever ships without Paper, the gate must be re-homed (D2 records it).

**P3. Single-source version metadata — plugin.yml half killed, fabric half
relocated.** `plugin.yml`'s `api-version` equals `minecraft_version` on all
recorded lines → template via `processResources` (keep `filesMatching` narrow;
the files are `$`-free today, keep them so). The `fabric.mod.json` `minecraft`
depends range is **not derivable** (three recorded forms: `>=26.2 <26.3-`,
`>=26.1 <26.2-`, exact `"1.21.11"`) → it becomes an explicit `minecraft_dependency`
data value, and `FabricModJsonContractTest` keeps a per-line pin on the FORM
(a copied range template on an exact-pin line ships a jar that loads on
wire-incompatible MC). Same treatment for `neoforge.mods.toml`'s MC dependency.
Templating on main must produce byte-identical resources (Phase-1 gate).
Scope extension (review): the same data-class rows exist in `build.yml`
(`java-version`, `support/**` trigger), `test-server.sh` (21-24 lines of pure
per-line data — concentrate into one clearly-marked LINE DATA block at the top),
and `release_check.py`'s per-line constants (`FABRIC_MAPPING_NAMESPACE`,
folia-direction). These stay per-line but become *labeled single blocks* the
runbook points at — relocation and labeling, not elimination.

**P4. Process hygiene.** (a) Sibling re-ports cut from the SAME main commit — the
70-minute skew at v0.10 silently cost the 26.1 line 275 lines of test coverage.
(b) **Measurement is tag-relative, never merge-driven** (v1.0's routine
forward-merge ritual is DROPPED per review: a content merge into feature-cut
lines re-creates keep-ours on feature files, `-s ours` poisons future merges, and
the major lines' 34 byte-different goldens conflict on every main-side regen; §0's
tag-relative diff already measures honestly). (c) One tag-text convention:
per-line notes files live on main under `docs/planning/`; branches don't fork the
convention. All three rules live in the runbook (D2).

## 3. Workstream S — code seams for the recorded MC-churn sites

Ordering principle: every S-item is a *pure refactor on main*. **Gates, corrected
per review:** on main, the unchanged golden corpora + cross-module parity tests
prove bytes identical. At PORT time the goldens are regenerated and the corpus
becomes a closed loop (natives derive from v20), so the real cross-line anchors
are the **vanilla-anchored tests** — `headlessWriteMatchesLevelChunkSectionWrite…`
(fabric + paper `NbtSectionSerializerTest`) and
`SerializerParityGameTests.diskReadBytesMatchLiveBytes…` — which compare our bytes
against MC's own `LevelChunkSection.write` and self-adapt per line (the fabric
test needed ZERO flavoring on 1.21.11). `check_wire_identity_*` proves
brand-neutrality of a single build, never wire stability; it is not a gate here.

**S1. Native section shape as a first-class parameter** (~134 lines of recorded
code churn across `common/wire/WireSectionCursor.java` +
`xplat/.../NbtSectionSerializer.java` + `paper/.../PaperNbtSectionSerializer.java`
+ three relationship-pinning tests). The descriptor is **two independent fields**
(review MAJOR — one value cannot express the recorded flavor):
- `nativeCountShorts` (LINE-level, consumed by the cursor's parse+emit): 26.x =
  split pair, 1.21.x = one short.
- `headerDerivation` (PER-PLATFORM-FAMILY, consumed by each serializer's header
  sites + `emitV20Direct`): on 1.21.11 Fabric writes `nonEmpty + fluid` while
  Paper (Moonrise recalc) writes `nonEmpty` alone — same line, different fold.
The three tests that hardcode the count relationship
(`NativeToV20TranslatorTest`, Paper's cross-module corpus parity — which flips
strict-vs-normalized comparison off the platform field — and
`XverLiveCorpusDecodeTest`'s count assertions) derive from the descriptor, which
is what makes T4's "stops flavoring" true. **Scope hygiene pin:** the descriptor
carries NATIVE fields ONLY; `V20_BLOCK_MAX_BITS`/`V20_BIOME_MAX_BITS` are wire
spec, never descriptor-derived (a per-line edit there forks the never-tiered wire
silently — both ends of that line would agree), pinned by test. V20 layout
untouched.

**S2. Section-construction seam.** `PalettedContainerFactory` vs the older
`Registry<Biome>` ctor family churned 4+ files on the 1.21.8 port and returns for
1.21.1. One `SectionConstruction` helper per loader family (xplat + Paper twin;
AS BUILT the twins deliberately DIFFER — the per-family ctor variance IS the seam,
so no byte-identity pin exists for them, unlike the mask filters; recorded at the
V-2 execution review) owning create-empty,
rebuild-from-containers, biome-container access; consumers:
`ClientColumnProcessor`, both `XrayMaskFilter`s, both NBT serializers' object
path. **Constraint carried from the 1.21.11 branch (review):** the helper must
never use the `LevelChunkSection` DESERIALIZATION ctor — AntiXray 1.4.x's one
non-null-safe mixin wraps it (raw ThreadLocal NPE outside its scope) and no
automated test covers it (AntiXray is stubbed in Tier 1). Enforcement: a
source-regex pin that no file outside the helper constructs a section.

**S3 — CUT (review, two reviewers concurring).** `TicketType` has exactly one
main-source site; Paper's service never touches it; the spike prices tickets
"cheap/free"; and a named-parameter wrapper doesn't fix the positional-ctor
hazard anyway. Replaced by a one-line hardening that DOES: use the vanilla
constants (`new TicketType(TicketType.NO_TIMEOUT, TicketType.FLAG_LOADING)`) so a
signature reorder reds the compile, plus an optional reflective
record-component pin. Rides any Phase-2 PR; not a plan item.

**S4. Background-read submit seam — Phase 3, scope-capped.** No recorded churn
yet (3 comment-lines across both v0.10 ports) but the spike confirms 1.21.1's
`ProcessorMailbox` rewrite arrives. The full submit ladder is ~80 lines inside
documented mirror-invariants — past §7's abstraction budget — so the item is
capped (review): extract only the priority-ordinal + accessor-resolution site; if
the seam wants the ladder, stop. The byte-parity gametest stays its gate.

**S5. AntiXray ScopedValue-carrier split — the cleanest item (reviewer-endorsed
as written).** One invocation site; the 1.21.11 flavor is a pure pass-through
(−141 main-source, −93 test). Extracting `ScopedCarrier` doesn't just shrink the
`XplatJava21SurfaceTest` exclusion list — it **empties it** (the list is literally
the one-element set naming AntiXrayCompat), removing the single standing Java-25
exception every Java-21 line must otherwise carry through shared xplat source.
The 8 crash-shim tests move with the carrier (count corrected at execution); the
5 engine-probe tests (verified line-invariant) stay, plus a new delegate pin.

**S6. Version-volatile file rule — reframed as a REGRESSION GUARD (review).**
Stage N already put `FarPlayerRenderer`/`ChunkSaveDataHook` in per-loader trees,
and a same-FQN file in xplat + a loader tree is a duplicate-class compile error —
the naive violation self-reds. The pin's non-redundant target is the
legal-looking refactor: move into xplat AND delete the loader twins (compiles;
`check_cross_loader_classes` is satisfied by the single shared class). A
list-pin test (`XplatLoaderPurityTest` family) reds that move for listed files.
Zero port-line savings claimed; the twinned-file cost (a volatile+twinned file =
L replacements per line) is recorded as the rule's known price.

**S7. Save-hook contract test, ASM census form (review-verified implementable).**
The compile-time `@Mixin(SerializableChunkData.class)` literal already fails
LOUDLY on ≤1.21.1 (class absent), and `SaveHookContractTest` already pins the
method's existence. The uncovered gap is "class exists but the platform save path
no longer routes through it" (`require = 0` hides a dead hook — dirty detection
dies silently). Implementable form, per the `MoveTraceHookContractTest` pattern
(loads named-namespace MC bytecode via `getResourceAsStream`): an ASM
invoke-census over `ChunkMap.save` asserting exactly one
`SerializableChunkData.copyOf` INVOKESTATIC. The Moonrise/C2ME arms cannot be
censused from Tier 1 (reflective-only, off the classpath) — they stay
hand-verified per line as named D1 checklist rows (as issue #69 originally was).

## 4. Workstream T — test & tooling portability

**T1. Hoist `NativeCorpusRegenTool` to main** (117 lines, exists only on the
1.21.11 branch; descriptor-agnostic — its whole body is a `fromV20` call — so it
lands in Phase 1 BEFORE S1). Corrections from review: keep the branch's lever
name (`LSS_REGEN_GOLDENS` env var — the branch and the runbook must agree on day
one) and unify it with the test-side `-Dlss.regenGoldens` gate; document the
TWO-STAGE order (v20 corpus regenerates via the serializer test's lever FIRST,
then natives via the tool); and promote the two NON-mechanical fixtures to
explicit runbook rows — `duplicate-air.bin` (hand byte-fold, currently recorded
only in a branch test comment) and `xray-masked.bin` (own regen path). Standing
rule restated: `xver-live-corpus` is NEVER regenerated (decoding 26.2 columns on
an old line IS the cross-version claim).

**T2. Mechanical-rename concentration for gametests — corrected mechanism.** The
helper lives in the GAMETEST source set (beside `GameTestSeeding`) and returns
`ChunkPos` — NOT a `PositionUtil` overload (`common/` is MC-free; a `ChunkPos`
overload doesn't compile there), and packed-long-only helpers would strand the
~25 `addTicketWithRadius`-style call sites that need a real `ChunkPos`. Recorded
churn shapes it must absorb: `.containing(BlockPos)`→ctor (~20 sites),
`.x()/.z()` accessor→field (dominant), `pack`→`asLong` (1 site). Honest payoff
(review): `GenerationLifecycleGameTests` is 100% concentrable;
`RegionFaultGameTests`' branch churn is behavioral (T3b's item), not positional.
Value = conflict-surface deletion, not line-count.

**T3. Reverse-flow adoption (branch hardening → main).** (a) soak.sh's
mc-version base-world marker guard (independently re-invented on four branches).
(b) `RegionFaultGameTests`' either-label containment pin — absorbs main's own
documented WSL2 flake; the SAME PR must update the CLAUDE.md flake-catalog entry,
which otherwise mis-trains the next contributor to dismiss a now-real regression
shape (review). (c) A mainline-shape `ToolchainContractTest` (exists ONLY on the
1.21.11 line today — singular, another P4a artifact): artifact-level pins of
class-file major + mixin `compatibilityLevel` + (new, for P2) the
`SharedConstants`-vs-gradle.properties line-identity anchor.

**T4. Line-neutral cross-line corpus arm.** With S1's descriptor driving the
count assertions (26.1's flavor was wording; 1.21.11's was semantics — wording
alone is not enough, review), `XverLiveCorpusDecodeTest` reads its MC token from
build data and stops flavoring.

## 5. Workstream D — documentation & checklist artifacts

**D1. `docs/planning/per-version-surfaces.md` — the R-7 table.** Surface → what
to verify → which contract test pins it → per-line status, covering: IOWorker
priority ordinals, RegionFile record-resolution branches, NbtIo root protocol,
`handleMovePlayer` census, publishServer overloads, folia-supported direction,
corpus identity rule, the S7 Moonrise/C2ME hand-verification arms. The support
branches' CLAUDE.md banners become a POINTER to this table plus a line-specific
status column (review: two live copies drift within one port).

**D2. The port runbook.** The reconstructed 14-step process + this plan's
amendments: P4 rules, T1's two-stage regen + manual-fold rows, S7/D1 checklist
rows, the `:paper:test` gate-host coupling, and the retirement of the keep-ours
ritual for files P1-P3 made branch-invariant.

**D3. Consolidated pre-authorized cut list.** Tier 3, read-split/selective-parse
flag-off, Sodium config menu deletion, degraded reset ladder, AntiXray
pass-through — one list the best-effort tiers reference (the neoforge plan's
§6.2 protocol).

## 6. Sequencing, effort, and the stage-G interaction (rewritten per review)

Context that v1.0 missed: stage N is MERGED; the program is paused at the
Modrinth manual-testing gate, and every main change that alters the shipped jar
restarts that clock via an F-gate re-arm + user-driven rig deploy (archon token
expired — the program has paid this once already). So the phase split is by
**jar-byte-identity**, not by "risk":

**Phase 1 — safe during the pause (gate: the built jars are class-byte-identical
before/after, the N-1a jar-diff precedent; P3's templating must prove
byte-identical resources):** P1 + P2 (with the pinned dry-run), P3, P4, T1, T3,
T4-wording, S6's list-pin, D1-D3. ~3-4 days. No re-arm, no re-deploy, and G's
four-line release round inherits all of it.

**Phase 2 — EXECUTED 2026-08-14 (V-2, post-sign-off, before G's port work):**
S1 (`NativeSectionShape` — THREE-field descriptor as amended by the execution
review's MAJOR-1/2: NATIVE_COUNT_SHORTS + the LINE-level cursor fold (the recorded
1.21.11 cursor SUMS for both families' v20→native egress — a field the planned
two-field shape could not express) + the two family folds; consumed by the cursor
emit, both serializers' `writeNativeCountHeader` AND `emitV20Direct` count headers
AND the exact pre-size arithmetic; all three folds throw on 2-short lines; the
three relationship tests derived; `NativeSectionShapeTest` carries the
scope-hygiene pins incl. V20-never-descriptor-derived), S2 (`SectionConstruction`/`PaperSectionConstruction`
family helpers + `SectionConstructionPinTest` — zero main-source ctor sites
outside them), S5 (`ScopedCarrier` per-loader byte-identical twins — the
`XplatJava21SurfaceTest` exclusion list is EMPTY; twins pinned identical in
`NeoForgeModuleContractTest`; the 8 shim tests moved to `ScopedCarrierTest`),
S7 (the ASM census in `SaveHookContractTest` — exactly one copyOf INVOKESTATIC
in the real 26.2 `ChunkMap.save`), and the S3-replacement one-liner
(`TicketType.NO_TIMEOUT`/`FLAG_LOADING` constants, values verified 0L/2).
All golden suites passed WITHOUT regeneration — the byte-identity proof.

**Phase 3 — branch-first, back-flow after (review: "with the 1.21.1 port" IS
stage G, and a main-side refactor mid-G is the worst slot):** S4 (scope-capped)
and T2 are implemented ON the 1.21.1 branch during its port, where the real
variance validates them, then back-flowed to main as a follow-up PR — T3's own
reverse-flow discipline applied prospectively. ~1.5-2 days inside the port
budget.

## 7. Risks

- **P2's anti-merge armor** — mitigated by the three-link chain ending at the
  resolved MC artifact (§2 P2); a named review focus that survived.
- **The gradle.properties blast radius** (review): `release_check.py` line-prefix
  parses `minecraft_version=` (fails closed), CI jar naming and the neoforge TOML
  expansion consume it, and the six-family VSS checks key off jar names. Per-line
  release data therefore lives in `.github/line.env`; `gradle.properties` gains
  only additive, prefix-non-colliding keys; `minecraft_version` is frozen in name
  and position.
- **S1 touches the serve path.** Pure-refactor discipline on main (goldens +
  vanilla-anchored tests + parity gametests bit-identical) + a fresh-backfill
  soak; at port time the vanilla-anchored tests are the anchor (the corpus is a
  closed loop there — §3 preamble).
- **Descriptor scope creep**: NATIVE-only, v20 constants pinned out (§3 S1).
- **Seam rot / over-abstraction**: every seam ships with its pin in the same PR;
  the ~50-line indirection budget is a hard stop (S4 is pre-capped).
- **Dry-run default flip**: pinned by P2; a merge flipping it reds.

## 8. Acceptance evidence (re-denominated per review)

- The next minor-line re-cut lands in **≤ ~120 true-delta lines outside
  regenerated goldens and outside first-port-of-a-new-module surface** (v0.10
  actual: 357 on the pre-N tree; the reviewer's residual computation after all
  items is ~106-122, so this target has no slack — treat an overrun as a signal
  to re-measure, not to relax silently).
- The next major-line port's serialization bucket is **one two-field descriptor
  (per-platform fold) + regenerated goldens**, and its save-hook/LanHook rows are
  checklist walks with per-line contract flavors, not discoveries.
- Port-surface measurement is tag-relative by runbook rule; the three-dot metric
  is retired (was: 2-3× inflated).
- Zero keep-ours files remain for release.yml/ReleaseWorkflowContractTest; a
  support cut's release-pipeline work is editing `.github/line.env` (~10 values)
  plus the per-line build.gradle dep pins.

## 9. Review round record (v1.0 → v1.1, 2026-08-14)

Three Opus reviewers, all MERGE WITH FIXES. Dispositions:
- **Evidence lens** (all headline numbers independently reproduced): MAJORs —
  stale tree denomination (§0 caveat + path fixes), P1's "all data" overclaim +
  trigger-glob impossibility (P1 rewritten with the broadened-trigger mechanism +
  structural adoption), S3 evidence contradiction (CUT). MINORs — S1
  double-count (restated ~134), missing per-line data files (P3 scope extension),
  P4b golden conflicts (P4b dropped), S6 reframe (adopted), S5 undersold
  (strengthened), T3c singular (fixed).
- **Seam lens**: MAJORs — one-value descriptor cannot express the per-platform
  fold (two-field descriptor), wire-identity checks are not a stability gate +
  port-time corpus is a closed loop (vanilla-anchored tests named as the anchor),
  S3 wrapper fixes nothing (CUT + constants one-liner), T2's PositionUtil overload
  cannot compile (gametest-source helper returning ChunkPos). MINORs — three
  relationship tests derive from the descriptor, descriptor scope pin, S4 cap,
  S7 ASM-census form (verified against disassembled 26.2), S6 real failure mode,
  T1 lever/two-stage/manual-fold corrections, S2 AntiXray-ctor constraint.
  Endorsed unchanged: S5.
- **Pipeline lens**: MAJORs — guard-first invariant vs data-file readability
  (decided: guard immediately-after-checkout, invariant re-pinned), no safe
  validation path (dry-run lever, pinned), stale sequencing + unpriced re-arm
  (jar-byte-identity phasing), S1 mid-pause slot (moved to hard post-sign-off),
  P4b ritual hazard (dropped). MINORs — strongest-form armor chain (adopted into
  P2/T3c), P3 fabric range not derivable (data row + form pin), data inventory
  ~10-11 across three types (adopted), gradle.properties blast radius (§7),
  T3b CLAUDE.md edit (adopted), acceptance re-denomination (adopted). NITs —
  regen lever name, `:paper:test` coupling, banner-pointer rule, expand hygiene:
  all adopted.
