# v0.7.3 backport report — MC 26.1 + MC 1.21.11 (SUPERSEDED by the v0.8.0 tri-release)

> **2026-07-27:** the v0.7.3 tags below were never cut. Both branches were re-versioned as
> `v0.8.0+mcX` (with main's v0.8.0 fixes merged in) — see `v0.8.0-tri-release-report.md` for
> the current validation state and the live tag command blocks. Everything else in this file
> (branch surgery, toolchains, compat research, review rounds, soak matrix) remains the
> foundation record for these branches. Do NOT run the v0.7.3 tag blocks at the end.

**Date:** 2026-07-26 · **Prepared by:** Claude (task #1, goal: prepare fully, draft report, do not release)

Two release-ready support branches were cut fresh from main's v0.7.3 state (`10f6ca7`), replacing
the stale v0.5.x-era support branches. Everything below is prepared and validated; **no `v*` tag
has been created or pushed, nothing has touched GitHub Releases or Modrinth.** The one remaining
mechanical step per line is the tag command block at the end of this report.

| | `support/mc26.1` | `support/mc1.21.11` |
|---|---|---|
| Target | Minecraft 26.1.2 (Fabric jar spans 26.1–26.1.2) | Minecraft 1.21.11 |
| Replaces (published) | mainline v0.5.1 (26.1.2 was main until v0.6.0) | v0.5.0+mc1.21.11 |
| Java | 25 (unchanged) | **21** (options.release + CI workflows) |
| Tag to cut | `v0.7.3+mc26.1` | `v0.7.3+mc1.21.11` |
| Fabric Tier 1 | 800 tests, 0 failed | 799 tests, 0 failed |
| Tier 2 gametests | All 58 required passed | All 58 required passed |
| Paper Tier 1 | 294 tests, 0 failed | 297 tests, 0 failed |
| `release_check.py --version 0.7.3` | OK (52 selftest cases green) | OK (52 selftest cases green) |
| Soak: `SOAK_PLATFORM=paper fresh-backfill` | **PASS** — 40 windows, 0 violations, 0 warnings | **PASS** — 40 windows (39 client-laws), 0 violations, 0 warnings |
| Soak: `SOAK_PLATFORM=folia fresh-backfill` | **PASS** — 40 windows, 0 violations, 0 warnings (real Folia 26.1.2, STABLE ch.) | **PASS** — 40 windows, 0 violations, 0 warnings (real Folia 1.21.11 build 14, STABLE ch.) |
| Branch CI (build.yml: T1+T2+Paper, T3 job) | **green** (run 30226222120 + tip run 30226383308, both jobs) | **green** (run 30226752721, both jobs — Tier 2 "All 58 required tests passed", clean Tier 3 first attempt) |
| Folia upstream builds confirmed | 3 builds for 26.1.2, latest STABLE | 14 builds for 1.21.11, latest STABLE |

## Branch surgery

- Old branches archived first (guardrail): `archive/mc26.1-pre-0.7.3`, `archive/mc1.21.11-pre-0.7.3`
  pushed to origin **before** `origin/support/mc26.1` + `origin/support/mc1.21.11` were deleted.
- New branches cut from `main` (v0.7.3, `10f6ca7`) and re-pointed into the existing worktrees
  (`~/projects/lss-support-mc26.1`, `~/projects/lss-support-mc1.21.11`).
- The archived branches served as recipes: the 26.1 retarget is the reverse of the v0.6.0 26.2
  port (`4095ba4`); the 1.21.11 retarget re-applies the archived line's researched rename set
  (`7a47f5b`) plus `support/mc1.21.8`'s modern-code precedents to today's sources.

## support/mc26.1 — 11 commits vs main (post-review rounds)

`fcdd371` toolchain (gradle.properties 26.1.2 / fabric-api 0.151.0+26.1.2, sodium mc26.1.2-0.8.12,
modmenu 18.0.0-beta.1, paperweight `26.1.2.build.69-stable`, `IntegratedServerLanHook` back to the
bare `publishServer` selector — 26.2's `MultiplayerScope` doesn't exist on 26.1 — and the matching
gametest call; test-server.sh 26.1.2 pins with legacy LSS = v0.5.1) · `8f92e36` Folia re-enable
(below) · `9026ad9` release.yml (below) · `eb9e938`+`1983b3b` docs + notes · build.yml `support/**`
trigger · PREV_TAG line-scoping.

Decisions worth review:

- **`fabric.mod.json` now pins `">=26.1 <26.2"`** (the old line shipped an unbounded `>=26.1`).
  The 26.2 jar and this jar carry mutually incompatible `publishServer` mixin descriptors, so the
  bound prevents a wrong-line install from crashing at startup in both directions.
- **`xray-masked.bin` golden regenerated on 26.1.2** (both modules, byte-identical pair, 6196 B):
  the mask replacement palette entry's registry id shifts between 26.1.2 and 26.2. The other 7
  corpus bins are untouched — 26.1.2↔26.2 wire bytes are identical for them (the v0.6.0 port
  regenerated nothing, which the port diff confirms).
- **The TwoPlayer flake-fix and `AbstractPlayerRequestState.getIncomingRequestCount()` from the
  v0.6.0 port are feature work, not 26.2-specific — kept.**

## support/mc1.21.11 — 15 commits (post-review rounds)

`fc40a50` Folia re-enable · `06e6ada` toolchain (fabric-loom-remap + `officialMojangMappings()` +
modImplementation deps — 1.21.x needs the classic remap toolchain, unlike 26.x's fabric-loom;
sodium mc1.21.11-0.8.12, modmenu 17.0.0, paperweight `1.21.11-R0.1-SNAPSHOT`, **Java 21**
everywhere, `vssJar` re-pointed at `remapJar` whose output is the real release jar under
loom-remap) · `8912a79` source renames (playC2S/playS2C, ClientCommandManager, ChunkPos
`.x`/`.z` fields + `new ChunkPos(BlockPos)` + `asLong`, `_` → `ignored`, publishServer arity,
corrupt-region label tolerance) · `dcda09d` AntiXray rework (below) · `0d7af32` corpus +
waterlogged tolerance + bundle-scheme contract · `0509844` split-world soak staging + base-world
mc-version marker · `8417177` CI (Java 21 + VSS disabled) · docs/notes · PREV_TAG scoping ·
release_check wording.

Decisions worth review:

- **AntiXray crash shim is a deliberate pass-through on this line** (`dcda09d`). `ScopedValue` is
  a preview API on Java 21 (the 26.x shim cannot even compile), and the mod's actual 1.21.11 build
  was downloaded and inspected (`antixray-fabric-1.4.14+1.21.11`): every `Arguments` field is a
  **ThreadLocal**, whose unset `.get()` returns null — precisely the "no packet context" value the
  26.x shim binds. The unbound-ScopedValue crash cannot occur here. `callSerializing` keeps the
  choke-point API (one place to reinstate a binding if the mod changes); `serializeProbeContained`
  stays the generic crash floor. The **engine probe — the actual x-ray masking feature — is
  unchanged**: its full reflective surface (`Util.getBlockController`, `obfuscateGlobal`,
  `maxBlockHeight`) was javap-verified identical in the 1.21.11 jar, and test-server.sh pins the
  native AntiXray 1.4.14+1.21.11 for the `run-fabric-antixray` live gate.
- **Corpus provenance:** the 7 golden bins are the archived line's 1.21.11 fixtures, copied
  per-module (fabric's and paper's waterlogged.bin legitimately differ — see next item). They stay
  byte-valid for v0.7.3 code because the serializer wire output never changed between v0.5.1 and
  v0.7.3 on the primary line (corpus diff v0.5.1..main is empty apart from the added xray-masked).
  `xray-masked.bin` was regenerated on 1.21.11 (both modules byte-identical, 6190 B).
- **Waterlogged count-hint tolerance** (from the archived line, re-applied to the current Paper
  parity test): on 1.21.11, vanilla counts a waterlogged block twice toward the section's leading
  `nonEmptyBlockCount` short (block + fluid) while Paper's bundle patches the fluid increment out.
  The cross-module parity test zeroes that 2-byte >0 hint before comparing and asserts every other
  byte exact — upstream MC divergence, decode-irrelevant, drift detection intact.
- **Corrupt-region label**: 1.21.11 vanilla propagates the ZipException (read resolves ERROR)
  where 26.1.2 swallows it (NOT-FOUND). Both land on the identical contained empty result;
  `RegionFaultGameTests` now accepts either label (archived line's fix, unchanged rationale).
- **Soak staging**: 1.21.11 Bukkit platforms use the legacy split `world_nether`/`world_the_end`
  layout (26.x is unified) — staging/saving now uses the `world*` glob, and base worlds carry an
  `mc-version` marker so a stale 26.x base clears itself before the auto-run check.

## Legacy-client compatibility (the "do we need v15?" research)

**No v15 shim is needed — settled empirically before the backport began:**

- The latest previously published release on each line speaks **protocol 16**: mainline v0.5.1
  (26.1.2) and v0.5.0+mc1.21.11 both have `PROTOCOL_VERSION = 16` at their tags. Protocol 15 last
  shipped in v0.3.0 (also 26.1-era) — those clients already cannot connect to v0.5.1 today, so the
  backport regresses nothing for them, and they stay out of scope per the goal ("latest previously
  published versions").
- Therefore v0.7.3's existing default-on shims are the whole compat story, both directions:
  server-side `enableV16Compat` (a v0.7.3+mcX server serves v0.4.x–v0.6.x-protocol clients) and
  client-side `enableV16ServerCompat` + Tier-B generation (a v0.7.3+mcX client against a v0.5.x
  server). All shim unit tests are inside the green Tier-1 suites on both branches.
- **Staged for the live smoke** (the one step needing a human at a real Minecraft client):
  each branch's `test-server.sh` legacy block now pins the line's own last release
  (`LEGACY_LSS_VERSION=0.5.1`/`0.5.0`, asset names verified against the GitHub releases). Run
  `./test-server.sh run-fabric-legacy` for the new-client→old-server direction, and join
  `./test-server.sh run-fabric` with the old published client jar for the old-client→new-server
  direction (expect the `/lsslod diag` `V16Compat` line).

## VSS publishing — disabled on these lines

`release.yml` on both branches: the two "Upload Voxy Server Side" Modrinth steps are **deleted**
and the two `voxy-server-side-*.jar` globs are **removed from the GitHub-release assets**; a
comment block marks the channel as 26.2-line-only. Deliberately kept (per goal decision): the
`vssJar` build tasks and every release_check VSS pair/wire-identity gate still run locally — the
1.21.11 build in fact caught and fixed a real loom-remap issue through them (vssJar must copy
`remapJar`'s output there). Also per support-line precedent: `make_latest: false`, per-line
Modrinth names/versions (`v0.7.3+fabric+mc26.1`, `v0.7.3+paper+mc26.1.2`, `v0.7.3+fabric+mc1.21.11`,
`v0.7.3+paper+mc1.21.11`), Folia loader added to the Paper steps, `MOD_VERSION` derived by
stripping the tag's `+mcX` suffix, and build.yml runs on `support/**`.

## Validation summary

Per the support-line effort budget (correct, not perfect — smoke, don't gauntlet):

- **26.1:** full pre-flight release build green (`CI=true :fabric:build -x runClientGameTest
  :paper:test :paper:shadowJar -Pmod_version=0.7.3` → Fabric T1 800/0, T2 58/58, Paper 294/0)
  + `release_check.py --version 0.7.3` OK · `SOAK_PLATFORM=paper fresh-backfill` PASS (40/40
  windows, 0 violations) · `SOAK_PLATFORM=folia fresh-backfill` PASS (40/40, 0 violations, real
  Folia 26.1.2) · branch CI green including the Tier 3 client-gametest job (run 30226222120).
- **1.21.11:** same pre-flight green (Fabric T1 799/0, T2 58/58, Paper 297/0) + release_check OK ·
  `SOAK_PLATFORM=paper fresh-backfill` PASS (40 windows / 39 client-law windows, 0 violations) ·
  `SOAK_PLATFORM=folia fresh-backfill` PASS (40/40, 0 violations, real Folia 1.21.11 build 14) ·
  branch CI green on all tiers (run 30226752721).

One CI-only issue was caught and fixed after the local pre-flight: `lss.mixins.json` declared
`compatibilityLevel: JAVA_25`, which the local Java 25 JVM accepted but the branch's Java 21 CI
JVM refused (Mixin fails at loader boot → Tier 1 via fabric-loader-junit AND Tier 3 both red,
run 30226541776). Fixed to `JAVA_21` — the same change `support/mc1.21.8` carries — and the
re-run is the green run above. This is exactly why the branch CI runs on 21: local Java-25
validation cannot catch this class of drift.

## Review round (2026-07-27): two subagent reviews, findings fixed, new test pins

Two independent subagent reviews ran over both branch diffs after the initial preparation —
an adversarial code review and a test-gap hunt. Everything actionable is fixed and pushed;
both branches' CI re-verified green afterwards.

**Code-review findings and their fixes:**
- *(resolved as a snapshot race)* The review's top finding — "the 26.1 forward-merge guard
  tests exist only uncommitted" — observed the worktree mid-implementation of the test-gap
  work; those files were committed and pushed minutes later (`c8af775`, `d190e48`).
- **1.21.11 `run-fabric-legacy` 404 (real bug):** the legacy download URL used tag path
  `v0.5.0`, but this line's only prior release lives under the dual-line tag
  `v0.5.0+mc1.21.11`. Fixed with a `%2B`-suffixed tag path and verified resolving (HTTP 302
  to the asset). The v16-compat live-smoke rig would otherwise have failed at setup.
- **False release-notes claim (1.21.11):** the "Fixes server crash with the AntiXray mod"
  bullet was dropped — that crash never existed on this line (ThreadLocals, see the
  AntiXrayCompat section); the masking *feature* bullet stays.
- **26.1 soak base-world marker:** the mc-version stale-base guard was policy, not
  version-specific — ported to 26.1's soak.sh (a 26.2 base world will not downgrade).
- Comment/docs staleness: ChunkDiskReader's IOWorker-ordinal provenance now names each
  line's jar; test-server.sh's `setup_legacy`/AntiXray comments no longer tell the 26.2
  story; ProbeContainmentTest's comment marks the unbound-ScopedValue shape as 26.x-only;
  26.1's README lists the sibling 1.21.11 row at v0.7.3.
- Verified clean by the review (no action): full release-pipeline simulation on both lines
  (jar-name globs, Modrinth version uniqueness vs published history, first-of-line PREV_TAG
  emptiness, compare-URL `+` handling), exhaustive missed-rename sweeps, the
  zeroSectionBlockCounts walker, the AntiXray pass-through's callers, both CLAUDE.md
  banners' claims, and the protocol-16 span claims.

**Test-gap findings → new pins (all implemented, green locally and in CI):**
- `ReleaseWorkflowContractTest` (paper module, both branches; branch-tuned constants): no
  VSS steps/assets, `make_latest: false`, MOD_VERSION suffix strip, line-scoped tag globs,
  line Modrinth coordinates + no other-line MC token outside comments, folia advertised,
  per-line game-versions, build.yml `support/**` trigger — and on 1.21.11, every workflow
  `java-version` pinned to 21. Runs inside `:paper:test`, which release.yml executes before
  any publish step, so a forward-merge regression physically blocks the tag run.
- `ToolchainContractTest` (1.21.11, fabric + paper): the compiled class-file major must be
  65 (`--release 21`) and `lss.mixins.json`'s `compatibilityLevel` must match it — the
  exact JAVA_25-passed-locally-failed-CI drift class, now a 7-second Tier 1 failure.
- `FabricModJsonContractTest` (both): `depends.minecraft` pinned exactly (the 26.1 UPPER
  bound is load-bearing — the jar's bare publishServer mixin breaks on 26.2) + the
  gradle.properties line lockstep.
- `NbtSectionSerializerTest.sectionBlockCountHintStaysSane` (1.21.11): the parity test's
  count-short exemption gave up real strength (`hasOnlyAir()` keys off that hint — a
  serializer emitting 0 for content sections would render them as air client-side with
  every golden green). Restored by pinning the hint's SIGN against the decoded palette
  across BOTH corpora, tolerating the platform divergence.
- `SoakStagingContractTest` (1.21.11): world* glob staging, split-dir clears, marker-guard
  ordering.
- `release_check.py` `--version` matching now requires the full `-<version>+<mc>.jar` name
  (mc from gradle.properties): support branches make the same mod_version exist on several
  lines, so a stale other-line jar of the right version could previously pass the gate and
  ship. +2 selftest cases (50 total). Applied to both branches.

**Final numbers after the review round:** 26.1 — Fabric 800/0, Paper 293/0; 1.21.11 —
Fabric 799/0, Paper 296/0; release_check selftest 50/50 both; branch CI green on all tiers
on both tips.

## Full soak matrix (post-Opus-round): `soak.sh all` on Paper AND Folia, both branches

Run in response to the runtime reviewers' shared finding that fresh-backfill alone never
exercised the disk-read success path, dimension-trip's split-dir plumbing, or the v0.7.3
Folia held-batch machinery. 16 scenario runs (fresh-backfill, warm-rejoin, dimension-trip,
paper-dirty-falling-block × {paper, folia} × {26.1, 1.21.11}):

- **15 of 16 PASS** with 0 violations, 0 warnings — including every previously-unrun
  scenario on both platforms and both lines (warm-rejoin proves `disk.successful > 0`;
  dimension-trip proves the split-world staging and the Folia dimension guards live).
- **1 initial FAIL, diagnosed as a NEW Folia-only run-start race; scenario re-run PASSED (0 violations), confirming the timing diagnosis:** 1.21.11
  Folia paper-dirty-falling-block hit law A1 in the run-start window with a deficit of
  exactly one want-set (800): the client's FIRST declaration arrived before the global pump
  drained the player's lifecycle registration, found no state, and was dropped UNCOUNTED
  (server `requests_received` short by exactly 800; every later window clean; self-heals in
  ≤1 s by re-declaration). This is a previously undocumented sibling of the deferred Folia
  items in `v0.7.1-candidates.md` — same class (experimental-path-only, rare, self-healing),
  now catalogued in both branches' CLAUDE.md flake sections with its decisive signature.
  **Mainline candidate:** reply SessionConfig from the registration drain on the pump (or
  count the pre-registration drop) — a handshake-ordering change that belongs on main first.

## Compatibility report: how far back each backport reaches

Protocol history across every published tag (read from each tag's `LSSConstants` +
`gradle.properties`): **protocol 15** = v0.2.1–v0.2.3 (MC 1.21.11, Mar 2026) and v0.3.0
(MC 26.1.2, Apr 2026) · **protocol 16** = v0.4.0–v0.6.2 and every dual-line tag
(v0.5.0+mc1.21.11, v0.6.1+mc1.21.8, v0.5.0+mc1.20.1), Jun–Jul 2026 · **protocol 18** =
v0.7.0+ (the v17 design line; 17 never shipped). Both compat shims gate on exactly
protocol 16 (`HandshakeGate`'s rung and `ClientSessionGate`'s re-handshake both compare
`== V16_COMPAT_PROTOCOL_VERSION`), so **16 is the hard floor in both directions** on every
v0.7.x build. Minecraft's own version gating means cross-MC-line combinations never arise —
each table below is entirely within its MC version.

### v0.7.3+mc26.1 (MC 26.1.x — prior releases on this MC: v0.3.0, v0.4.0–v0.5.1)

| Peer | As SERVER (old client joins it) | As CLIENT (it joins old server) |
|---|---|---|
| v0.7.3+mc26.1 | native protocol 18 | native |
| **v0.4.0 – v0.5.1** (protocol 16, back to **2026-06-14**) | **full LODs** via `enableV16Compat` (default on) — legacy drip-feed pace, `/lsslod diag` shows the V16Compat line | **full LODs** via `enableV16ServerCompat` (default on) — timeout discovery re-handshakes as 16; `enableV16Generation` (default on) drives cold-terrain generation on the old server |
| v0.3.0 and older (protocol 15) | no LOD session — handshake gets no reply, old client stays vanilla-only (graceful) | no LOD session — graceful: the v15 server answers with its 10-field config, and the modern codec's explicit unknown-version rung drains it and disables LSS with one log line (no decoder kick) |

**Floor: v0.4.0 (June 2026) in both directions** — which is every release that Modrinth
still lists as supporting 26.1.x except v0.3.0. v0.3.0 users were already incompatible
with v0.5.1 (protocol 15 vs 16, no shim existed then), so the backport regresses nothing.

### v0.7.3+mc1.21.11 (MC 1.21.11 — prior releases on this MC: v0.2.1–v0.2.3, v0.5.0+mc1.21.11)

| Peer | As SERVER | As CLIENT |
|---|---|---|
| v0.7.3+mc1.21.11 | native protocol 18 | native |
| **v0.5.0+mc1.21.11** (protocol 16, **2026-07-02**) | **full LODs** via `enableV16Compat` | **full LODs** via `enableV16ServerCompat` (+Tier-B generation) |
| v0.2.1–v0.2.3 (protocol 15, the original Mar-2026 1.21.11 releases) | no LOD session (graceful no-reply; the v0.2.x client just never activates) | no LOD session (graceful drain-and-disable, as above) |

**Floor: v0.5.0+mc1.21.11 (July 2026) in both directions.** The March-2026 v0.2.x builds
sit below the protocol-16 floor — same status they already had against v0.5.0+mc1.21.11
(which superseded them with the same 15→16 break). A v0.2.x user gets vanilla render
distance and no error, and should update the client jar (either to v0.5.0+mc1.21.11 or
straight to v0.7.3+mc1.21.11 — the server serves both simultaneously).

### Caveats that apply to both lines

- The protocol-16 path is the **legacy pace**: old clients are drip-fed through the
  synthetic want-set (server-side) or the old server's own limiter (client-side) — correct
  but slower than two v0.7.3 peers.
- Shim provenance: both shims shipped in v0.7.0 and were live-validated on the mainline
  26.x releases (server shim against a real v0.6.2 client on Fabric+Paper; client shim
  live-confirmed against v0.4.x–v0.6.2 servers). On these lines the identical code paths
  are covered by the Tier-1 shim suites in both branches' green runs; the staged
  `test-server.sh` legacy rigs (each pinned to its line's real last release asset,
  URLs verified resolving) are the recommended one-time live eyeball per line.
- Same-line MC patch spread (a 26.1 client on a 26.1.2 server) is governed by Minecraft's
  own network compatibility, not LSS; the Paper plugin additionally requires a 26.1.2
  server (`api-version` is a minimum), while the Fabric jar spans 26.1–26.1.2.
- Both platforms (Fabric server and Paper/Purpur/Folia) share the same `HandshakeGate` +
  wire codecs, so the tables apply identically to all server types on each line, and
  `enableV16Compat: false` / `enableV16ServerCompat: false` turn the respective rows off.

## Review round 3 (six Opus subagents, three focus areas x two branches) — findings verified and fixed

Six independent Opus reviews (release-pipeline / runtime-correctness / tests-goldens-docs, per
branch) ran over the post-round-2 branches; every finding was then re-verified inline and either
fixed, or explicitly accepted with rationale. Both branches re-validated green afterwards
(suites, release gates, CI) and the extended soak matrix below was run in response.

**Fixed (the substantive set):**
- **Fabric mapping-namespace gate** (best catch of the round): nothing pinned the Fabric jar's
  mapping namespace, so a forward merge restoring plain `fabric-loom` on the 1.21.11 branch
  would ship a mojang-mapped jar — right name, every gate green, unloadable on a real server.
  `release_check.py` now pins `Fabric-Mapping-Namespace` per line ('official' on 26.1,
  'intermediary' on 1.21.11), mirroring the existing Paper namespace gate.
- **First-of-line PREV_TAG**: the line-scoped tag glob (round 2's own fix) left the first tag
  of a line with NO previous tag — the lightweight-tag notes fallback would dump the full
  342-commit history into the release body and both Modrinth changelogs, and even the
  happy-path Full Changelog link degraded to a full-history listing on 26.1. Both PREV_TAG
  sites now fall back to the mainline anchor tag (`git describe --exclude 'v*+mc*'` → v0.7.3),
  with a last-resort `-n 50` bound.
- **release.yml triggers scoped to the line's tags** (`v*+mc26.1*` / `v*+mc1.21.11*`): a
  mistagged bare `v*` pushed from a support branch previously WOULD have published.
- **Workflow contract test rewritten with per-step scoping** after three constructed
  false-greens (a half-merge could drop the Paper jar from the release assets, relocate
  `make_latest`, or regress the Paper game-versions — all green before); it now also pins the
  Modrinth `version:` ids, `fail_on_unmatched_files`, the scoped trigger, and the exact CI
  branches lists — and the workflow files are declared `:paper:test` inputs, closing the
  UP-TO-DATE staleness that would have skipped the contract in a local pre-flight.
- **release_check hardening**: `--version` fails CLOSED if `gradle.properties`' line pin is
  unreadable; stale versioned jars beside the selected release build are flagged (the upload
  globs are greedy); the suffixless same-version escape is gone (selftest now 52 cases).
- **soak.sh marker version now read from gradle.properties** on both branches (a hardcoded
  guard/stamp pair that drifts on a patch bump would silently clear the base world every run).
- **1.21.11 block-count sanity test strengthened**: `maybeHas` answers from palette ENTRIES
  (constant-true on a global palette) — it now recomputes real non-air/fluid cell counts and
  pins `exact <= hint <= exact + fluid`; both wire walkers assert full drain so a grammar
  desync can never silently over-exempt bytes from the parity comparison.
- **RegionFault doc corrected** (1.21.11): the two miss flavors are NOT identical —
  `notFoundFromError` never seeds the miss memo and each corrupt-chunk read logs the
  unthrottled read error; CLAUDE.md's soak-triage recipes now carry that caveat.
- **Docs/runbook truth pass**: support-line release runbook override in both CLAUDE.md files
  (the mainline steps, followed verbatim, would have merged the backport into main and/or
  burned a mainline tag name); folia-presence wording everywhere; the 1.21.11 banner now names
  the AntiXray pass-through; notes fixes (Paper needs 26.1.2 exactly — `api-version` is a
  minimum; Fabric API floor 0.146+ per the manifest; the read-priority flag governs reads
  only); README sibling rows point at currently PUBLISHED versions (bump them after both tags
  exist); LanHook pins the full `publishServer` descriptor.

**Verified clean by the runtime agents** (evidence-based, not assumed): all 51 imported MC
classes bytecode-diffed 26.1.2↔26.2 (zero behavioral drift on LSS surfaces); the static mixin
remap verified in the shipped 1.21.11 jar (intermediary field/method ids present); the v16 shim
verified against the ACTUAL archived v0.5.0+mc1.21.11 peer (byte-identical payload layouts, the
no-reply mismatch behavior the discovery timer needs); Paper/Moonrise/Folia surfaces identical
across both dev bundles; the xray-masked regeneration semantically guarded (mask assertions run
before the byte compare) and its 2-byte delta explained (one palette varint, DEEPSLATE's id).

**Accepted with rationale (no branch change) — mainline findings and known tradeoffs:**
- **Masked sections keep hidden-block ids in the PALETTE** (cells are masked; a client reading
  the palette learns "this 16³ contains diamond ore" at section resolution). Version-invariant
  — identical on main, and the reference engines leave the same residue in their own packets.
  Filed as a mainline candidate (palette repack at the mask choke point); fixing it only on
  the support branches would fork wire behavior from v0.7.3 mainline.
- **1.21.11 fluid-count divergence** (vanilla counts a waterlogged block's fluid on recalc,
  not on incremental set): traced to ONE benign extra dirty broadcast per waterlogged
  gen-served chunk per session (the filter re-baselines after it). Documented in the branch
  banner; a code change (recalc-before-serialize) was judged out of support-line budget.
- Paper masks 4 blocks deeper than Paper's own engine when `max-block-height` isn't
  section-aligned (engine rounds down, LSS adopts raw — fail-safe direction; mainline
  candidate). · Paper adopts `hiddenBlocks` but not mode-2 `replacementBlocks` (protective
  direction; mainline). · `DataLayer.getData()` lazy-materialization race (theoretical,
  version-invariant, mainline). · Per-read `PalettedContainerFactory.create` allocation churn
  (mainline perf nit). · The `>=26.1 <26.2` bound admits 26.2 PRE-releases under SemVer
  (unverified loader normalization; snapshots transient — not worth gambling on undocumented
  range syntax). · The 1.21.11 paperweight bundle is an upstream SNAPSHOT coordinate (frozen
  since May, same coordinate the archived line shipped). · release.yml actions are @v4 vs
  build.yml's @v5 (inherited from main; not churning action majors on a support branch).
  · Docs-only release commits get no CI run (`paths-ignore`, inherited; the pre-flight covers
  it). · `intendingToBlock=false` is inert on this Moonrise (doc claim only, inherited).

## Open risks / manual steps for the reviewer

1. **Live v16-compat smoke is staged, not run** — needs a human at a real client (both directions
   per line; see the compat section). Analysis + unit tiers say it works; the goal calls this out
   as the acceptable manual step.
2. **AntiXray live gates (5-minute checks, per line):** 26.1 — `./test-server.sh
   run-fabric-antixray`, expect the "AntiXray detected — serialization crash shim active" boot
   line, join, `/lsslod diag` shows the Xray line, zero `NoSuchElementException` in the log.
   1.21.11 — same rig, but expect NO shim line (pass-through); the gate observable is engine
   adoption ("LOD x-ray masking active") + clean serves. ThreadLocal caveat (1.21.11): a leaked
   context on an LSS serialize thread would mis-mask a column, not crash — AntiXray's bug class.
   Also worth one C2ME check per line (`run-fabric`, expect the one-shot background-read
   fallback WARN + `read_throttle` engaging in `/lsslod diag`, then the `/setblock` dirty
   smoke), and a Voxy config check: `VoxyCompat` resolves `sectionRenderDistance` as float —
   if the line's real Voxy build declares it int, slider adoption silently falls back to the
   config distance (benign; verify `/lss diag` tracks the Voxy slider).
3. **Fabric 26.1/26.1.1 game-versions on Modrinth** are inherited from the old line's metadata;
   the jar was only *tested* on 26.1.2 (same as the old line's practice — `>=26.1 <26.2` floor).
4. **Folia stays experimental on both lines** (single-player soak validated, concurrent
   multi-region ingress untested) — release notes and plugin.yml comments say so explicitly.
5. **This report + the two notes files are the only uncommitted-to-main artifacts** — the notes
   live ON their branches (`docs/release-notes-v0.7.3+mcX.md`); this report is uncommitted in the
   main worktree (main is branch-protected; PR it in or leave it local, your call).

## Ready-to-run release commands (OBSOLETE — superseded by the v0.8.0 blocks; kept for the record)

Pre-verified: both branches' tips already passed the exact release build + `release_check
--version 0.7.3` locally, and Folia upstream builds exist for both lines. Publish order between
the two lines doesn't matter. `make_latest: false` protects mainline's Latest badge.

### MC 26.1 (`v0.7.3+mc26.1`)

```bash
cd ~/projects/lss-support-mc26.1
git fetch origin && git checkout support/mc26.1 && git pull --ff-only
# CI on the branch tip must be green first: gh run list --branch support/mc26.1 --limit 1

git tag -a v0.7.3+mc26.1 -F docs/release-notes-v0.7.3+mc26.1.md --cleanup=verbatim
# verify the ### headers survived:
git for-each-ref --format='%(contents)' refs/tags/v0.7.3+mc26.1 | head -5

git push origin v0.7.3+mc26.1
# headBranch filter: immediately after the push, --limit 1 can return the PREVIOUS
# release run (already green) and watch would exit 0 on the wrong run.
sleep 15 && gh run watch --exit-status $(gh run list --workflow=release.yml --json databaseId,headBranch \
    --jq '[.[]|select(.headBranch=="v0.7.3+mc26.1")][0].databaseId')
# then verify BOTH surfaces (never `gh run rerun` after a partial publish):
gh release view v0.7.3+mc26.1        # notes rendered, exactly 2 LSS jars, NOT marked Latest
# Modrinth: v0.7.3+fabric+mc26.1 (26.1/26.1.1/26.1.2) + v0.7.3+paper+mc26.1.2 (paper/purpur/folia)
```

### MC 1.21.11 (`v0.7.3+mc1.21.11`)

```bash
cd ~/projects/lss-support-mc1.21.11
git fetch origin && git checkout support/mc1.21.11 && git pull --ff-only
# CI on the branch tip must be green first: gh run list --branch support/mc1.21.11 --limit 1

git tag -a v0.7.3+mc1.21.11 -F docs/release-notes-v0.7.3+mc1.21.11.md --cleanup=verbatim
git for-each-ref --format='%(contents)' refs/tags/v0.7.3+mc1.21.11 | head -5

git push origin v0.7.3+mc1.21.11
sleep 15 && gh run watch --exit-status $(gh run list --workflow=release.yml --json databaseId,headBranch \
    --jq '[.[]|select(.headBranch=="v0.7.3+mc1.21.11")][0].databaseId')
gh release view v0.7.3+mc1.21.11     # notes rendered, exactly 2 LSS jars, NOT marked Latest
# Modrinth: v0.7.3+fabric+mc1.21.11 + v0.7.3+paper+mc1.21.11 (paper/purpur/folia)
```

Both release.yml files publish **only** the LSS pair to Modrinth project `lKiXKLvv` — no VSS
step exists on these branches, so the `84zcagOb` membership/403 concern doesn't apply.
