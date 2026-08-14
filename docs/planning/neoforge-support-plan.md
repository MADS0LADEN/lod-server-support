# NeoForge support plan — client + server, best-effort tier (2026-08-14, v1.0)

**Status: PLANNED — folded into the v0.11.0 mega plan as stage N (post-pause,
pre-G), with MC 1.21.1 added as a stage-G backport target.** Research basis:
docs/planning/neoforge-1.21.1-port-spike.md (+ its all-four-lines addendum) —
availability, API-drift, renderer, and precedent facts live there and are not
re-argued here.

## 0. Scope decisions (user directives, 2026-08-14)

1. **Client AND server ship on NeoForge.** The client half assumes the player
   runs *some* Voxy variant exposing the normal Voxy API surface (the
   j-shelfwood-class community ports — probed: `commonImpl.VoxyCommon`,
   `WorldIdentifier.of`, `rawIngest(...)`, `getTaskCount`,
   `getStorageBasePath` all match our bridge's shapes). **Graceful degradation
   is the contract, not renderer availability**: `VoxyCompat` already treats
   every unresolvable handle as no-sink (warn-once, no capability bit, inert
   client) — that existing ladder IS the acceptance bar. No new degrade
   machinery is needed; the gate is a test that the ladder works under the
   NeoForge classloader.
2. **Best-effort support tier** for BOTH the NeoForge variant and the MC 1.21.1
   line (CLAUDE.md + README get a support-tier table): feature cuts to make
   1.21.1 work are acceptable; imperfect automated coverage for NeoForge is
   acceptable. Concretely pre-authorized: no Tier 3 on NeoForge (the framework
   does not exist), gametest smoke subset instead of the full 71, the
   abbreviated soak (§5.3) instead of `soak.sh all`, and the 1.21.1 line drops
   Tier 3 + ships `useBackgroundReadSplit`/`useSelectiveNbtParse` flag-off +
   the Voxy reset ladder degraded (per the spike's feature-drop list).
3. **Sequencing**: stage N lands on MAIN after the F-pause sign-off and before
   G, so the backport process carries the module (the user's main-first
   directive). Schedule consequence stated honestly: G (and all v0.11.0 tags)
   moves out by N's duration (~2-3 weeks). 1.21.1 joins G as a NEW branch
   (Fabric + NeoForge only — no Paper module on that line, spike decision).
4. **Soaks**: NeoForge gets `SOAK_PLATFORM=neoforge` running an ABBREVIATED
   smoke set (§5.3), not the full scenario suite. Skippable entirely if the
   plumbing fights back (best-effort), with the manual smoke as the floor.
5. **VSS**: the branded pair gains a `vssJarNeoforge` (same zip-repackage
   pattern, TOML rewrite instead of fabric.mod.json). XANTHA's manual publish
   flow (`vssJars`) extends; release.yml still never publishes VSS.

## 1. Architecture

### 1.1 Module layout (the Sodium/Iris/Lithium shape)

```
lod-server-support/
├── common/    unchanged (pure Java, zero MC imports — verified)
├── xplat/     NEW source set: MC-logic shared by fabric/ + neoforge/
│              (compiled per-loader via srcDir inclusion, NOT a Gradle project —
│               it has MC deps, so each loader module compiles it against its
│               own toolchain; Mojang mappings both sides make this source-safe)
├── fabric/    thins to loader glue + mixins + ALL existing test tiers
├── neoforge/  NEW: loader glue + AT + contract tests (MDG 2)
└── paper/     unchanged
```

- **What moves to xplat** (~16k lines): the client stack (SpiralScanner,
  ColumnStateMap, LodRequestManager, TransferRateGovernor, ClientNetTrace,
  ColumnCacheStore, ResetCoordinator, far-player client tracker/support logic),
  the server stack (ChunkDiskReader core, SectionSerializer,
  NbtSectionSerializer, generation service core, dirty pipeline core), payload
  codecs, VoxyCompat/MoonriseReadCompat/AntiXrayCompat, LSSApi.
- **What stays per-loader**: entrypoints, event/payload registration, mixin
  classes + configs (near-verbatim copies, different wiring), the far-player
  RENDERER (26.2 Fabric = COLLECT_SUBMITS; 26.2 NeoForge = the state-based
  RenderLevelStageEvent/ExtractLevelRenderStateEvent pair), config-screen glue
  (Fabric-only — Sodium's config API; NeoForge v1 is config-file + `/lsslod
  set` only), LAN hook (Fabric-only), move-tracer bootstrap (mixin-loading
  differs; hook bodies live in xplat).
- **`LoaderServices` seam** (xplat interface, per-loader impl): isModLoaded,
  configDir, gameDir, modVersion, payload send. The ~14 `FabricLoader` call
  sites route through it; NeoForge maps to `ModList`/`FMLPaths`/
  `PacketDistributor`(+`ClientPacketDistributor`).

### 1.2 Non-negotiable invariants (carried from the existing architecture)

- **Wire bytes identical across loaders by construction**: payload classes are
  vanilla `StreamCodec` + `CustomPacketPayload`, shared via xplat verbatim —
  the same guarantee the Fabric/Paper pair already pins (`WireParityTest`).
  A NeoForge server serves Fabric clients and vice versa with zero shims.
- **Every `lss:*` payload registers `.optional()`** on NeoForge (a mandatory
  payload refuses vanilla/Fabric clients at login — the fork's mistake).
- **NeoForge throws on sends to unannounced channels** (Fabric no-ops): all
  send paths are already handshake-gated by design; the flush/broadcast paths
  additionally get containment so a race lands as a contained send failure
  (the existing send-failure ladder), never a tick crash.
- **C2S ≤ 32 KiB on NeoForge**: the want-set batch maxes ~16.5 KiB
  (1024 × 16 B + envelope). A build-time pin
  (`WantSetBudgetInvariantTest` sibling) asserts
  `MAX_BATCH_CHUNK_REQUESTS * 16 + ENVELOPE_MARGIN < 32768` so a future budget
  raise cannot silently cross the loader bound.
- **Brand/branding**: `Brand.load` stays each entrypoint's first act;
  `lss-brand.properties` rides xplat resources.

## 2. Stage N — NeoForge on main (26.2), phased

### N-1: xplat extraction (no behavior change)

Pure file moves + the LoaderServices seam. The fabric module gains
`sourceSets.main.java.srcDir("../xplat/src/main/java")` (and resources); the
jar layout, mixin configs, and every test are unchanged. **Gate: the full
existing suite green (T1 both platforms, T2, T3, release_check, one
fresh-backfill soak) + a jar-diff sanity check** — the Fabric jar before/after
N-1 differs only in metadata (class bytes identical modulo compile order).
This is the blast-radius stage: land it alone, first, in a quiet window.

### N-2: neoforge module, server half

MDG 2 (`net.neoforged.moddev` 2.0.14x), NeoForge 26.2.0.x, Java 25.
`@Mod` entrypoint (Brand first), `RegisterPayloadHandlersEvent` registrar
(all 10 channels, `.optional()`, `executesOn` matching each receiver's current
thread contract), event wiring (ServerStarted/Stopping, ServerTickEvent.Post,
PlayerLoggedOut), commands via `RegisterCommandsEvent`, the dirty hook +
disk-read accessor mixins (same targets as Fabric — vanilla classes; AT file
for the 2-line accessWidener), natives packaging (jarJar with module metadata;
**fallback pre-authorized: Paper-style shading** — known-good in-repo,
no-relocate-org.sqlite rule applies). Server parity gates: the shared
`WireParityTest` pattern extended to a neoforge contract suite (payload
census, registrar census via source-regex, mods.toml pins), the gametest
smoke subset (§5.2), the abbreviated soak (§5.3).

### N-3: neoforge module, client half

Client events (LoggingIn/Out, ClientTickEvent.Post), client commands
(`RegisterClientCommandsEvent`), `VoxyCompat` under the NeoForge classloader
(no runtime remapping — the direct-class-literal rule relaxes, but keep the
code identical; the graceful-degrade ladder is the contract), `/lss reset`
(Voxy half degrades per its existing ladder when the fork's holder interfaces
are absent), the far-player renderer on the state-based
RenderLevelStageEvent/Extract pair (**best-effort: if the 26.2 NeoForge
render-submit surface fights the proxy-entity idiom, CUT far-player rendering
on NeoForge v1** — the tracker/wire stay, the renderer no-ops with an INFO;
the capability bit still composes from consumers only). Client gate: manual
smoke with a community Voxy build (LODs render, reset works, far players
render-or-degrade) + the no-Voxy degrade smoke (clean logs, no capability).

### N-4: CI + release + VSS + docs

Details §4/§6. CLAUDE.md support-tier table, README version table row,
release-notes draft line.

## 3. Stage G additions — 1.21.1 as a backport target

G currently delta-ports onto `support/mc26.1-v0.11` and `support/mc1.21.11-v0.11`.
Added: **cut `support/mc1.21.1` FRESH from main-at-G** (no `-v0.10` ancestor
exists), then apply the spike's MC-retarget recipe (templates:
`support/mc1.20.1` for the old-API family, `support/mc1.21.11-v0.10` for
modern-on-Java-21): dirty hook → `ChunkSerializer.write` (bytecode-verify
1.21.1 Moonrise/C2ME call it), IOWorker `ProcessorMailbox` submit shape, NBT
serializer old-API translation, far-player renderer → 1.21.1 immediate-mode
idiom (old `RenderLevelStageEvent` semantics on the NeoForge side), golden
regen (keep `xver-live-corpus` un-regenerated — the XVER proof), Java 21
(`ScopedValue` → the 1.21.11 line's AntiXray pass-through). Line scope:
**Fabric + NeoForge, no Paper, no Tier 3, best-effort tier** — feature cuts
per the spike's drop list are pre-authorized. The other two support lines
receive the neoforge module through the normal delta-port (their NeoForge
majors: 26.1.2.x and 21.11.x; expected drift is the documented rename set,
each line pins its own MDG/NeoForge pair).

## 4. CI + release workflow modifications

### 4.1 build.yml (main + support flavors)

- The main build job gains: `./gradlew :neoforge:build` (compiles xplat under
  MDG + runs the neoforge contract tests) and the gametest smoke step
  (`:neoforge:runGameTestServer`, failure-exit-code gated) with the same
  retry-once + evidence-artifact pattern as the existing tiers.
- Tier 1/2/3 stay on the fabric module unchanged. Docs-only skip rules
  unchanged. The support-branch build.yml flavors gain the same step with
  their pinned NeoForge versions.

### 4.2 release.yml (main flavor; support flavors mirror per line)

- Build step: add `:neoforge:build -Pmod_version=…` beside the existing two.
- `release_check.py`: new `check_neoforge_jar` family — dev-package exclusion
  (benchmark/soak classes absent), `neoforge.mods.toml` pins (mod id `lss`,
  display name, version expansion), nested-natives presence, the
  wire-identity check extended (the xplat/common class bytes shared with the
  Fabric jar — SHA where packaging allows, else a class-digest comparison),
  the VSS-neoforge pair checks. RELEASE_GLOBS gain the neoforge jar; selftest
  fixtures extended same-commit (the R4/S-8 rule).
- GitHub release `files:` gains `neoforge/build/libs/lod-server-support-neoforge-*.jar`.
- **New Modrinth step** (mirroring the existing two):
  ```yaml
  - name: Upload NeoForge to Modrinth
    uses: Kir-Antipov/mc-publish@v3.3
    with:
      modrinth-id: lKiXKLvv
      modrinth-token: ${{ secrets.MODRINTH_TOKEN }}
      files: neoforge/build/libs/lod-server-support-neoforge-*.jar
      name: ${{ github.ref_name }} - NeoForge (MC 26.2)
      version: ${{ github.ref_name }}+neoforge+mc26.2
      version-type: release
      loaders: neoforge
      game-versions: |
        26.2
      changelog: ${{ steps.release_notes.outputs.notes }}
  ```
  Support-line flavors: game-versions/name per line; the 1.21.1 flavor
  publishes fabric+neoforge (no Paper step).
- `ReleaseWorkflowContractTest` (+ per-line twins): pins gain the neoforge
  build step, the release-files glob, the Modrinth step census (three uploads
  on main; two on 1.21.1), and VSS-publish absence unchanged.

### 4.3 The irreversibility discipline (unchanged, restated)

Tags publish irreversibly; the neoforge jar joins the same pre-flight
(`CI=true ./gradlew :fabric:build … :neoforge:build … && release_check.py
--version`) BEFORE tagging; never re-run a partially published release —
recovery is hand-uploading the GitHub-attached jar to the channel that missed.

## 5. Testing strategy (best-effort tier, made concrete)

### 5.1 Tier 1

Stays on the fabric module (fabric-loader-junit compiles xplat + common —
~1250 tests unchanged). The neoforge module gets CONTRACT tests only
(JUnit, no MC boot): mods.toml pins, registrar/payload census (source-regex),
AT-file presence + content, `.optional()` census, the C2S-bound pin,
LoaderServices completeness (reflective: every interface method has a
neoforge impl).

### 5.2 Tier 2 smoke subset (NeoForge gametests)

~8-12 tests, not 71: service activation + config load, handshake→register→
serve round-trip (crafted frames), disk-read byte parity vs the live
serializer (THE cross-loader correctness pin), generation serve, dirty
broadcast, idempotent shutdown. Registered via `RegisterGameTestsEvent`
(the 26.x data-driven idiom); run as `runGameTestServer` in CI. Full-suite
parity is explicitly NOT a goal (best-effort tier).

### 5.3 Abbreviated soak ("smoke soak")

`SOAK_PLATFORM=neoforge ./scripts/soak.sh smoke` = TWO scenarios only:
`fresh-backfill` (generation + serve + all conservation laws once) and
`dirty-broadcast` (the NeoForge dirty hook end-to-end). Unchanged Fabric soak
client + checker — the run itself is the Fabric-client↔NeoForge-server interop
proof (the Paper/Folia precedent). Pre-authorized fallback: if driver plumbing
under NeoForge exceeds ~2 days, SKIP the soak platform entirely and gate on
the manual smoke checklist (best-effort).

### 5.4 What NeoForge explicitly does NOT get

Tier 3 (no framework), the full soak suite, benchmark harness arms, Folia-class
platform validation, per-release live-rig burn-in (the Modrinth rig stays
Fabric). Recorded in CLAUDE.md so nobody chases the gap as a regression.

## 6. Support-tier documentation (CLAUDE.md + README)

New CLAUDE.md section (and README version-table column):

> **Support tiers.** Fabric 26.2 + Paper 26.2 (main) and the 26.1/1.21.11
> support lines are FULL tier: complete test gauntlets, soaks, live-rig
> burn-in. **NeoForge (all lines) and the MC 1.21.1 line are BEST-EFFORT
> tier**: they ship client+server and track the mainline feature set, but
> feature cuts are acceptable where the platform/version fights (each cut
> documented in the line's release notes), automated coverage is reduced
> (contract tests + gametest smoke + abbreviated/skipped soak on NeoForge; no
> Tier 3 on either), and issues specific to these variants are triaged at
> lower priority. Wire compatibility is NEVER tiered — every jar speaks the
> same protocol at full fidelity.

## 7. Risks

1. **The xplat extraction (N-1) is the program's blast radius** — every open
   branch crosses it. Mitigation: land alone, first, gated by the full suite +
   jar-diff; support branches take it via the normal G delta-port.
2. **Natives under NeoForge's module layer** (sqlite/zstd via jarJar) — least
   charted; shading fallback pre-authorized.
3. **Renderer reality on NeoForge clients**: community Voxy forks only
   (1.21.1 fork; Foxy shim at 26.1.2; nothing on 26.2/1.21.11 today). The
   client half ships anyway per the user directive — VoxyCompat's degrade is
   the contract; the release notes state plainly which renderer builds exist
   per line at publish time.
4. **NeoForge API drift on support lines** (21.1 old render/gametest idioms vs
   26.x) — bounded by the spike's drift map; the glue is thin by design.
5. **Schedule**: N delays every v0.11.0 tag by ~2-3 weeks; G grows by the
   1.21.1 line (~12-18 d). The alternative (release v0.11.0 first, NeoForge as
   v0.12.0) was considered and rejected by the user's make-it-part-of-the-
   backport-process directive — revisit only if the pause uncovers urgent
   fixes needing a fast tag.
6. **Send-throw semantics** (NeoForge throws where Fabric no-ops) — contained
   at the flush/broadcast sites; the smoke soak + a contract test pin it.

## 8. Effort

| Phase | Estimate |
|---|---|
| N-1 xplat extraction | 3-4 d |
| N-2 server half | 4-6 d |
| N-3 client half | 3-5 d |
| N-4 CI/release/VSS/docs | 2-3 d |
| **Stage N total (main)** | **~12-18 d** |
| G increment: 26.1 + 1.21.11 neoforge carry | ~5-9 d |
| G increment: the 1.21.1 line (fabric+neoforge) | ~12-18 d |
| **Program total added** | **~29-45 d** |

(Consistent with the spike's 27-42 matrix estimate + N-4's release machinery.)
