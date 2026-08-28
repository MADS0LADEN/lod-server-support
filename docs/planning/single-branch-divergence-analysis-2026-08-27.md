# Support-line divergence analysis (2026-08-27)

> Produced by the v0.14.0 program's divergence-analysis agent as input to the
> single-branch consolidation plan. Read-only measurement of origin/main vs the
> four support line branches at v0.13.1+backport-lag state.

I have complete data. Here is the report.

---

# Support-line divergence analysis — `/home/vox/projects/voxel-server-support`

**Repo root:** `/home/vox/projects/voxel-server-support` (all paths below are absolute).
**Main:** `origin/main` @ `8856204f`, MC 26.2, 935 tracked files / **590 `.java`**.

## ⚠️ Framing correction that governs every number below

The four support branches are **not** simple ports of today's main. They share an **old merge-base** and both sides have moved:

| Branch | merge-base | mb date | main ahead | branch ahead | main-side churn since mb | branch-side churn since mb |
|---|---|---|---|---|---|---|
| `support/mc26.1-v0.13` | `9cb32ade` | 2026-08-15 | 212 | 108 | 400 files, +49,874/−1,592 | 317 files, +37,588/−1,229 |
| `support/mc1.21.11-v0.13` | `9cb32ade` | 2026-08-15 | 212 | 110 | 400 files, +49,874/−1,592 | 362 files, +37,793/−1,766 |
| `support/mc1.21.10` | `9cb32ade` | 2026-08-15 | 212 | 119 | 400 files, +49,874/−1,592 | 363 files, +37,478/−2,597 |
| `support/mc1.21.1` | `e4c0a4cc` | 2026-08-15 | 210 | 109 | 399 files, +49,813/−1,588 | 438 files, +39,360/−3,442 |

**Consequence:** the enormous `−12,625 … −15,579` deletion counts in `git diff main..branch` are **almost entirely main-side work not yet backported**, not feature cuts. I verified every "deleted" path against the merge-base:

| Branch | "deleted" files | existed at merge-base (**real** branch-side removal) | added on main after mb (**backport lag**) |
|---|---|---|---|
| mc26.1-v0.13 | 54 | **0** | 54 |
| mc1.21.11-v0.13 | 55 | **0** | 55 |
| mc1.21.10 | 98 | **1** | 97 |
| mc1.21.1 | 54 | **1** | 53 |

Only **two files in the entire fleet** are genuine feature cuts (§2d). Everything else labelled "deletion" is lag. I therefore report both the raw diff and a lag-corrected view throughout.

---

# 1. `git diff --stat` totals and per-top-level-dir breakdown

## Totals (`git diff --shortstat origin/main origin/<branch>`)

| Branch | files changed | insertions | deletions | M | D | A |
|---|---|---|---|---|---|---|
| `support/mc26.1-v0.13` | 164 | 702 | 12,625 | 110 | 54 | 0 |
| `support/mc1.21.11-v0.13` | 211 | 1,030 | 13,285 | 156 | 55 | 0 |
| `support/mc1.21.10` | 310 | 2,178 | 15,579 | 209 | 98 | 3 |
| `support/mc1.21.1` | 316 | 2,256 | 14,563 | 259 | 54 | 3 |

Monotone ladder: divergence grows strictly with age of the MC line.

## Per-top-level-dir (`files / +ins / −del`)

| dir | mc26.1-v0.13 | mc1.21.11-v0.13 | mc1.21.10 | mc1.21.1 |
|---|---|---|---|---|
| `(root)` | 4 / +82 / −97 | 4 / +90 / −103 | 4 / +134 / −110 | 4 / +148 / −116 |
| `.github/` | 3 / +19 / −40 | 4 / +21 / −160 | 4 / +22 / −161 | 4 / +32 / −212 |
| `common/` | 10 / +22 / −471 | 10 / +34 / −478 | 10 / +34 / −478 | 10 / +54 / −453 |
| `docs/` | 32 / +32 / −2,913 | 32 / +32 / −2,913 | 33 / +230 / −2,914 | 32 / +33 / −2,885 |
| `fabric/` | 54 / +237 / −4,250 | 77 / +432 / −4,596 | **124 / +1,115 / −5,965** | **122 / +852 / −5,345** |
| `neoforge/` | 10 / +21 / −182 | 13 / +45 / −311 | 33 / +107 / −1,086 | 24 / +150 / −422 |
| `paper/` | 22 / +101 / −2,034 | 39 / +177 / −2,076 | 46 / +220 / −2,108 | 58 / +540 / −2,348 |
| `scripts/` | 2 / +39 / −154 | 2 / +42 / −150 | 2 / +74 / −185 | 6 / +58 / −168 |
| `xplat/` | 27 / +149 / −2,484 | 30 / +157 / −2,498 | 54 / +242 / −2,572 | 56 / +389 / −2,614 |
| **gradle files** | **0 (identical)** | **0** | **0** | **0** |

`docs/` is ~2,900 deletions on *every* line — that is a single constant block of unbackported main planning docs (17 files under `docs/planning/`, 5 under `docs/planning/v0.12.0-release-notes/`), identical across all four. Same for the `common/` −471…−478 and `xplat/` −2,484…−2,614 floors: a shared lag baseline, not per-line drift.

**Root `build.gradle`, `settings.gradle`, `gradlew`, `gradlew.bat`, `gradle/wrapper/` are byte-identical on all four branches.**

Modified-file breakdown by extension:

| ext | 26.1 | 1.21.11 | 1.21.10 | 1.21.1 |
|---|---|---|---|---|
| `.java` | 80 | 90 | 143 | **188** |
| `.bin` (goldens) | 2 | 34 | 34 | 34 |
| `.md` | 10 | 10 | 10 | 10 |
| `.json` | 3 | 6 | 6 | 7 |
| `.gradle` | 3 | 3 | 3 | 3 |
| `.yml` / `.toml` / `.properties` / `.env` / `.contract` / `.py` / `.sh` / `.txt` / `.accesswidener` | 3/1/1/1/1/1/2/2/0 | 3/1/1/1/1/1/2/2/1 | 3/1/1/1/1/1/2/2/1 | 3/1/1/1/1/1/6/2/1 |

Whitespace check: `git diff -w --ignore-blank-lines` removes **zero** files on every line (80→80, 90→90, 143→143, 188→188). **No divergence is cosmetic whitespace.**

---

# 2. Categorization of the diverging files

## (a) Pure version-identity

Two files carry essentially all line identity, by explicit design:

**`/home/vox/projects/voxel-server-support/.github/line.env`** — diverges on all 4 (+15…+18 / −18…−21). Full value matrix:

| key | main | 26.1 | 1.21.11 | 1.21.10 | 1.21.1 |
|---|---|---|---|---|---|
| `LINE_TAG_SUFFIX` | *(empty)* | `+mc26.1` | `+mc1.21.11` | `+mc1.21.10` | `+mc1.21.1` |
| `LINE_MC_FABRIC` | 26.2 | 26.1 | 1.21.11 | 1.21.10 | 1.21.1 |
| `LINE_MC_PAPER`/`NEOFORGE` | 26.2 | 26.1.2 | 1.21.11 | 1.21.10 | 1.21.1 |
| **`LINE_JAVA_VERSION`** | **25** | **25** | **21** | **21** | **21** |
| `LINE_MAKE_LATEST` | true | false | false | false | false |
| `LINE_GAME_VERSIONS_FABRIC` | 26.2 | `26.1 26.1.1 26.1.2` | 1.21.11 | 1.21.10 | 1.21.1 |
| `LINE_PAPER_LOADERS` | `paper purpur folia` | `paper purpur folia` | `paper purpur folia` | **`paper purpur`** | **`paper purpur`** |
| `LINE_SHIP_NEOFORGE` | true | true | false | false | true |
| `LINE_FABRIC_MAPPING_NAMESPACE` | `official` | *(absent)* | *(absent)* | *(absent)* | *(absent)* |

**`/home/vox/projects/voxel-server-support/gradle.properties`** — diverges on all 4 (+8…+10 / −14…−21). Carries `minecraft_version`, `minecraft_dependency`, `fabric_version`, `neoforge_version`, `sodium_version`, `sodium_legacy_golden`, `modmenu_version`, `moonrise_modrinth_version`, `c2me_modrinth_version`, `suggests_sodium`, `suggests_voxy`. Note `loader_version=0.19.3` and `loom_version=1.17.13` are **identical on all five refs**.

Also version-identity:
- **`/home/vox/projects/voxel-server-support/CLAUDE.md`** — +90/−24 on 1.21.1: a ~60-line support-branch banner prepended, plus deletions of mainline-only prose. Diverges on all 4.
- **`/home/vox/projects/voxel-server-support/README.md`** — +2/−10 on 1.21.1: version table row removal, client-stack prose, and (crucially) **feature-doc removal that is really backport lag** (`/lss reset voxy-force`, `requireServicePermission`, client cache identity).
- **`plugin.yml` version** — *not* a diverging file: `api-version` is templated from `minecraft_version` by `paper/build.gradle`'s `processResources`. Identity is data, not a file edit. Same for `fabric.mod.json`'s `minecraft` depends (templated from `minecraft_dependency`).

**Verdict on (a): ~6 files, fully parameterized already.** Roughly 40 scalar values total.

## (b) Dependency pins

| file | 26.1 | 1.21.11 | 1.21.10 | 1.21.1 |
|---|---|---|---|---|
| `/home/vox/projects/voxel-server-support/fabric/build.gradle` | +10/−51 | +25/−59 | +42/−62 | +18/−51 |
| `/home/vox/projects/voxel-server-support/neoforge/build.gradle` | +1/−5 | +9/−10 | +9/−50 | +31/−9 |
| `/home/vox/projects/voxel-server-support/paper/build.gradle` | +2/−16 | +5/−17 | +5/−17 | +4/−17 |
| `/home/vox/projects/voxel-server-support/neoforge/src/main/resources/META-INF/neoforge.mods.toml` | +3/−3 | +3/−3 | +3/−10 | +3/−3 |
| `/home/vox/projects/voxel-server-support/fabric/src/main/resources/lss.accesswidener` | — | +1/−1 | +1/−1 | +1/−1 |

Concrete pins:
- **paperweight dev bundle** (`paper/build.gradle`): `26.2.build.84-stable` → `1.21.11-R0.1-SNAPSHOT` / `1.21.10-R0.1-SNAPSHOT` / `1.21.1-R0.1-SNAPSHOT`.
- **NeoForge loader floor** (`neoforge.mods.toml`): main **derives** it (`neoforge_floor` from `neoforge_version.tokenize('.').take(2)`); all four branches still carry the **hand-edited literal** `[26.1,)` / `[21.11,)` / `[21.10,)` / `[21.1,)`. That derivation (R2-6) is main-only and unbackported — a free win on collapse.
- **accessWidener header**: `accessWidener v2 official` → `v2 named` on all three 1.21.x lines (26.1 keeps `official`). One-token, mechanically derivable from the mapping namespace.
- **ModMenu / Moonrise / C2ME**: main uses `${modmenu_version}` / `${moonrise_modrinth_version}` / `${c2me_modrinth_version}` properties; the 1.21.x lines still hardcode literals (`modmenu:11.0.3`, `moonrise-opt:5IV5gcdA`, `c2me-fabric:gRm1ZAvc` on 1.21.1). Again: main already parameterized these post-branch (R2-6).

## (c) REAL MC-API divergence in Java sources

### Counts (modified `.java`, `--diff-filter=M`, whitespace-insensitive)

| line | modified `.java` | of which **pure temporal lag** (0 additions) | with real branch-side additions | **pure `Identifier`→`ResourceLocation` rename** | **hard core** (real additions AND beyond the rename) |
|---|---|---|---|---|---|
| mc26.1-v0.13 | 80 | 25 | 55 | 0 | **55** |
| mc1.21.11-v0.13 | 90 | 25 | 65 | 0 | **65** |
| mc1.21.10 | 143 | 23 | 120 | **33** | **87** |
| mc1.21.1 | 188 | 19 | 169 | **44** | **125** |

### The single biggest axis: the `Identifier` rename

`net.minecraft.resources.Identifier` ↔ `net.minecraft.resources.ResourceLocation` flips exactly at the **1.21.11 / 1.21.10 boundary**:

| ref | files using `Identifier` | files using `ResourceLocation` |
|---|---|---|
| `origin/main` (26.2) | 63 | 0 |
| `support/mc26.1-v0.13` | 61 | 0 |
| `support/mc1.21.11-v0.13` | 62 | 0 |
| `support/mc1.21.10` | 0 | 46 |
| `support/mc1.21.1` | 0 | 61 |

**44 of 1.21.1's 188 diverging `.java` files (23%) and 33 of 1.21.10's 143 (23%) differ by NOTHING ELSE.** Entirely mechanical. Examples (all under `/home/vox/projects/voxel-server-support/`): every one of the 13 payload records in `xplat/src/main/java/dev/vox/lss/networking/payloads/` (`BatchChunkRequestC2SPayload.java`, `BatchResponseS2CPayload.java`, `ClientInfoC2SPayload.java`, `ColumnStampsS2CPayload.java`, `DirtyColumnsS2CPayload.java`, `FarPlayerPrefsC2SPayload.java`, `FarPlayerRosterS2CPayload.java`, `FarPlayerUpdatesS2CPayload.java`, `HandshakeC2SPayload.java`, `RegionSummaryRequestC2SPayload.java`, `RegionSummaryS2CPayload.java`, `SessionConfigS2CPayload.java`), plus `xplat/src/main/java/dev/vox/lss/networking/server/FabricOffThreadProcessor.java`, `paper/src/main/java/dev/vox/lss/paper/PaperMemoizedNbtCodec.java`, `paper/src/main/java/dev/vox/lss/paper/PaperXrayMaskFilter.java`, `xplat/src/main/java/dev/vox/lss/networking/client/ClientIdentityResolver.java`, `xplat/src/main/java/dev/vox/lss/networking/client/FarPlayerMountLadder.java`, `xplat/src/main/java/dev/vox/lss/networking/server/XrayMaskFilter.java`, the 9 Sodium config stubs under `fabric/src/test/java/net/caffeinemc/…` and `neoforge/src/main/java/net/caffeinemc/…`, and ~14 client tests.

### The 15 largest genuine divergences on the OLDEST line (1.21.1), ranked by branch-side additions, with the API-drift kind

| # | file (abs) | +/− | kind of API drift |
|---|---|---|---|
| 1 | `/…/paper/src/main/java/dev/vox/lss/paper/PaperNbtSectionSerializer.java` | +98/−94 | **Removed MC class** `PalettedContainerFactory` (26.x-only) → construction retargeted to `Registry<Biome>`; NBT accessor family change (see #2). |
| 2 | `/…/xplat/src/main/java/dev/vox/lss/networking/server/NbtSectionSerializer.java` | +93/−64 | **Changed `CompoundTag` accessor family**: 26.x Optional-returning `getStringOr/getIntOr/getByteArray().orElse/getLongArray().orElse/getCompound()→Optional/getList(String)` → 1.21.1 defaulting getters + `contains(name, Tag.TAG_*)` type probes + `getList(String,int)`. Plus removed `PalettedContainerFactory`/`Strategy`, and the **wire-shape change**: 1.21.1's `writeLongArray` VarInt-prefixes the container long array (26.x/1.21.11 write bare words) — affects `containerSize()` arithmetic and the emit. |
| 3 | `/…/fabric/src/test/java/dev/vox/lss/networking/server/XverLiveCorpusDecodeTest.java` | +84/−17 | Cross-version corpus decode expectations keyed to the one-short native header + line fold. |
| 4 | `/…/paper/src/test/java/dev/vox/lss/paper/NbtSectionSerializerTest.java` | +83/−87 | Mirror of #1/#2 in test fixtures. |
| 5 | `/…/fabric/src/test/java/dev/vox/lss/networking/server/NbtSectionSerializerTest.java` | +83/−85 | Mirror of #2. |
| 6 | `/…/fabric/src/main/java/dev/vox/lss/networking/client/FarPlayerRenderer.java` | +70/−53 | **Whole render architecture change.** Fabric API class rename `LevelRenderContext`→`WorldRenderContext`; `poseStack()`→`matrixStack()`, `submitNodeCollector()`→`consumers()`; 26.x **extract/submit** phase (`dispatcher.extractEntity` + `dispatcher.submit(state, cameraRenderState, …)`) does not exist → immediate `dispatcher.render(entity, x,y,z, yaw, partialTick, poseStack, consumers, packedLight)`; `mainCamera().position()`→`getMainCamera().getPosition()`; `getDeltaTracker()`→`getTimer()`; `dimension().identifier()`→`.location()`; `startRiding(e,true,true)` 3-arg → 2-arg. Event also flips `COLLECT_SUBMITS`→`AFTER_ENTITIES`. |
| 7 | `/…/paper/src/main/java/dev/vox/lss/paper/PaperRequestProcessingService.java` | +51/−354 | Mostly lag; real part is the **Bukkit world-layout split** (26.x unified `dimensions/minecraft/<dim>` vs 1.21.x `world_nether`/`world_the_end`, re-rooted per level via `getWorldFolder()`). |
| 8 | `/…/paper/src/test/java/dev/vox/lss/paper/PaperRegionFreshnessWiringTest.java` | +48/−26 | Per-line resolver expectations for #7. |
| 9 | `/…/fabric/src/test/java/dev/vox/lss/SaveHookContractTest.java` | +42/−36 | **Missing MC class**: `SerializableChunkData` is 1.21.2+; existence pin inverted. |
| 10 | `/…/xplat/src/main/java/dev/vox/lss/compat/VoxyCompat.java` | +42/−274 | Mostly lag; real part is `LevelRenderer.allChanged()` vs 26.2's render-extract rework. |
| 11 | `/…/neoforge/src/gametest/java/dev/vox/lss/neoforge/gametest/LSSNeoGameTests.java` | +41/−76 | **Missing launcher option**: MC 1.21.1's server `Main` has no `--tests`; filter moves to `neoforge.enabledGameTestNamespaces` system property. |
| 12 | `/…/fabric/src/gametest/java/dev/vox/lss/test/GenerationLifecycleGameTests.java` | +38/−19 | GameTest annotation moves `net.fabricmc.fabric.api.gametest.v1.GameTest` → `net.minecraft.gametest.framework.GameTest`; `template=`/`timeoutTicks=` attribute renames. |
| 13 | `/…/fabric/src/main/java/dev/vox/lss/mixin/ChunkSaveDataHook.java` (+ neoforge twin, +24/−17 each) | +24/−17 | **Mixin target class swap**: `@Mixin(SerializableChunkData.class)` / `@Inject(method="copyOf")` returning `SerializableChunkData` → `@Mixin(ChunkSerializer.class)` / `@Inject(method="write")` returning `CompoundTag`. |
| 14 | `/…/paper/src/main/java/dev/vox/lss/paper/PaperSectionConstruction.java` (+ xplat twin `SectionConstruction.java`) | +21/−6 | `PalettedContainerFactory` absent → ctor param becomes `Registry<Biome>`; `factory.createForBiomes()` expands to explicit `new PalettedContainer<>(registry.asHolderIdMap(), registry.getHolderOrThrow(Biomes.PLAINS), Strategy.SECTION_BIOMES)`. |
| 15 | `/…/common/src/main/java/dev/vox/lss/common/wire/NativeSectionShape.java` | +20/−20 | **Pure data, not code**: `NATIVE_COUNT_SHORTS` 2→1, `NATIVE_LONG_ARRAY_PREFIXED` false→true, and the three fold functions go from `throw` to real returns (`nonEmpty+fluid` for line/Fabric, `nonEmpty` for Paper/Moonrise). |

Also in the top-40 and worth naming as distinct drift kinds:
- `/…/xplat/src/main/java/dev/vox/lss/networking/server/BackgroundIoSubmit.java` (+18/−14) — **executor type change**: `PriorityConsecutiveExecutor.scheduleWithResult(prio, body)` → `ProcessorMailbox<StrictQueue.IntRunnable>.tell(new IntRunnable(prio, task))`, with the `CompletableFuture` now hand-completed.
- `/…/xplat/src/main/java/dev/vox/lss/mixin/AccessorSimpleRegionStorage.java` (+16/−5) — **superclass hierarchy move**: the `worker` field lives on `ChunkStorage` (1.21.1) vs `SimpleRegionStorage` (26.x); `@Mixin` target swapped, class name deliberately kept.
- `/…/xplat/src/main/java/dev/vox/lss/networking/server/ChunkGenerationService.java` (+19/−16) — **ticket API**: `new TicketType(NO_TIMEOUT, FLAG_LOADING)` + `addTicketWithRadius`/`removeTicketWithRadius` → generic `TicketType.create(name, Comparator)` + `addRegionTicket`/`removeRegionTicket`; also `ChunkPos.x()`→`.x` (record→field).
- `/…/fabric/src/main/java/dev/vox/lss/compat/ScopedCarrier.java` + `/…/neoforge/src/main/java/dev/vox/lss/compat/ScopedCarrier.java` (+10/−118 each) — **missing JDK feature**: `java.lang.ScopedValue` is Java 25; both 130-line shims collapse to a 22-line pass-through on every Java-21 line (1.21.11, 1.21.10, 1.21.1 — all three).
- `/…/fabric/src/main/java/dev/vox/lss/compat/XaeroMapCompat.java` / `XaeroTileExtractor` — world-height expression (`getMinY()`/`getMaxY()+1` vs `getMinBuildHeight()`/`getMaxBuildHeight()`) and light opacity (`getLightDampening()` 26.x / `getLightBlock()` 1-arg 1.21.11–1.21.10 / **2-arg** 1.21.1).
- Sodium surface: on **1.21.10 only**, `net.caffeinemc.mods.sodium.api.config.*` (the 0.8+ structured config API) does not exist — 25 stub/consumer files removed (see §2d).

**Summary of drift kinds by frequency:** (1) mechanical class rename `Identifier`/`ResourceLocation` — 44 files; (2) NBT accessor family Optional↔defaulting — ~12 files; (3) removed/renamed MC types (`PalettedContainerFactory`, `Strategy`, `SerializableChunkData`, `SimpleRegionStorage`, `PriorityConsecutiveExecutor`) — ~15 files; (4) changed method signatures/arity (`startRiding`, `getLightBlock`, `addRegionTicket`, `TicketType.create`) — ~10 files; (5) architectural reworks (render extract/submit, Bukkit world layout) — ~6 files; (6) missing JDK/loader features (`ScopedValue`, `--tests`, fabric-client-gametest-api, Sodium 0.8 config API) — ~30 files.

## (d) Feature cuts (files genuinely absent on a line)

Only **two files** were removed relative to the merge-base across the whole fleet:

1. `/home/vox/projects/voxel-server-support/fabric/src/gametest/java/dev/vox/lss/test/LSSClientGameTests.java` — removed on `support/mc1.21.1`. **Tier 3 cut**: `fabric-api` 0.116.x has no `fabric-client-gametest-api-v1`. Also flips `enableClientGameTests = false` in `fabric/build.gradle` and deletes the entire `client-gametest` job from `.github/workflows/build.yml` (−53 lines).
2. `/home/vox/projects/voxel-server-support/fabric/src/main/java/dev/vox/lss/config/LSSConfigMenu.java` — removed on `support/mc1.21.10`. **Sodium 0.8+ walker cut**: MC 1.21.10's Sodium tops out at 0.7.3, which predates `net.caffeinemc.mods.sodium.api.config`.

Cut #2 cascades to **25 further files that exist on main but not on 1.21.10** (they show as "D" but are main-side-new *and* would be cut anyway): 16 under `fabric/src/test/java/net/caffeinemc/mods/sodium/api/config/structure/`, 9 under `neoforge/src/main/java/net/caffeinemc/mods/sodium/api/config/…`, plus `neoforge/src/main/java/dev/vox/lss/config/LSSConfigMenu.java` and 4 `neoforge/src/test/java/dev/vox/lss/neoforge/` tests (incl. `SodiumNeoGoldenParityTest.java`, `SodiumConfigApiContractTest.java`). This is why 1.21.10's `neoforge/` diff (33 files, −1,086) is 3× its siblings'.

Build-side cuts, not file cuts:
- `LINE_PAPER_LOADERS` drops `folia` on **1.21.10 and 1.21.1** (no Folia build for those MC families); `plugin.yml`'s `folia-supported` row is absent with the contract pins **inverted to absence**.
- `LINE_SHIP_NEOFORGE=false` on **1.21.11 and 1.21.10** → `.github/workflows/release-neoforge.yml` deleted (−118 lines) on 1.21.11, 1.21.10, and 1.21.1.
- On 1.21.10, `sodium_version` is *absent from gradle.properties entirely* and `fabric/build.gradle` guards the golden arm with `if (project.hasProperty('sodium_version'))`, setting `lss.sodiumModernGoldenExpected=false`.

**Net: 2 real file cuts. Feature-cut surface is essentially nil.**

## (e) Test / golden divergence

**Binary golden corpora** (per-MC-version, byte-regenerated):

| corpus | main count | 26.1 diverging | 1.21.11 | 1.21.10 | 1.21.1 |
|---|---|---|---|---|---|
| `nbt-corpus/` (fabric 14 + paper 14) | 28 | **2** | **28** | **28** | **28** |
| `v20-corpus/` (fabric 14 + paper 14) | 28 | 0 | **6** | **6** | **6** |
| `xver-live-corpus/` (12 cols + MANIFEST) | 13 | **0** | **0** | **0** | **0** |

The `nbt-corpus` split is exactly the `NATIVE_COUNT_SHORTS`/`NATIVE_LONG_ARRAY_PREFIXED` axis: 26.1 shares 26.2's 2-short/bare-word shape (only `xray-masked.bin` differs on both fabric and paper — a lag/lever row), while every 1.21.x line regenerates all 28. The `v20-corpus` is prefix-free by wire spec, so only 6 files move (`global-palette.bin`, `hashmap-wide.bin`, `waterlogged.bin` × 2 platforms). **`xver-live-corpus` is byte-identical on all five refs** — that is the cross-version compatibility claim, and it is deliberately never regenerated (surfaces row 8, pinned by `XverLiveCorpusDecodeTest`).

**Gametest divergence:** 7 / 8 / 10 / 10 gametest `.java` files diverge (26.1 / 1.21.11 / 1.21.10 / 1.21.1). Drivers:
- Annotation package: `net.fabricmc.fabric.api.gametest.v1.GameTest` (main: 8 files) → `net.minecraft.gametest.framework.GameTest` on 1.21.1 (0 fabric-api, 9 vanilla).
- `/home/vox/projects/voxel-server-support/fabric/src/gametest/java/dev/vox/lss/test/Gt.java` and `/home/vox/projects/voxel-server-support/neoforge/src/gametest/java/dev/vox/lss/neoforge/gametest/Gt.java` — **added on 1.21.10 only**: a 30-line adapter because 1.21.9/1.21.10's `GameTestHelper` dropped the `String` overloads of `assertTrue`/`assertFalse`. The port sed-reroutes ~550 call sites through it, keeping every condition and message expression byte-identical to the parent line. This is the single cleanest example in the repo of "absorb drift in one shim rather than 550 edits."
- `/home/vox/projects/voxel-server-support/fabric/src/gametest/java/dev/vox/lss/test/TestPositions.java` — the designated per-line factory (`ChunkPos` ctor/field/`asLong` vs `pack`, `addRegionTicket` vs `addTicketWithRadius`), ~48 hold/release sites funnelled through it (V-3/T2).
- `/home/vox/projects/voxel-server-support/neoforge/src/gametest/resources/data/lsstest/structure/empty.nbt` — **added on 1.21.1 only** (structure-template requirement).
- `/home/vox/projects/voxel-server-support/fabric/src/test/java/dev/vox/lss/testutil/TestPalettedContainers.java` and `/home/vox/projects/voxel-server-support/paper/src/test/java/dev/vox/lss/paper/testutil/TestPalettedContainers.java` — **added on 1.21.1 only** (the `PalettedContainerFactory` replacement helpers).

**Contract-test divergence** — a distinct, well-defined class of ~10 files whose *job* is to pin per-line values, so they diverge by construction: `/…/paper/src/test/java/dev/vox/lss/paper/PluginYmlContractTest.java` (+99 across lines), `/…/fabric/src/test/java/dev/vox/lss/FabricModJsonContractTest.java` (+84), `/…/fabric/src/test/java/dev/vox/lss/LanHookContractTest.java` (+76), `/…/paper/src/test/java/dev/vox/lss/paper/ReleaseWorkflowContractTest.java` (+70), `/…/fabric/src/test/java/dev/vox/lss/ToolchainContractTest.java`, `/…/fabric/src/test/java/dev/vox/lss/compat/XaeroWiringContractTest.java`, `/…/fabric/src/test/java/dev/vox/lss/trace/MoveTraceHookContractTest.java`, `/…/paper/src/test/java/dev/vox/lss/paper/FoliaWiringContractTest.java`, `/…/fabric/src/test/java/dev/vox/lss/SaveHookContractTest.java`, `/…/neoforge/src/test/java/dev/vox/lss/neoforge/NeoForgeModuleContractTest.java`.

---

# 3. Cross-line union — the version-hot set

**Denominator: 590 `.java` files on `origin/main`** (264 `src/main`, 314 `src/test`, rest gametest).

| metric | count | % of main's 590 |
|---|---|---|
| diverge on **at least one** line | **195** | **33.1 %** |
| diverge on **all four** lines | **74** | **12.5 %** |
| diverge on exactly 3 lines | 16 | 2.7 % |
| diverge on exactly 2 lines | 52 | 8.8 % |
| diverge on exactly 1 line | 53 | 9.0 % |
| **never diverge on any line** | **395** | **66.9 %** |

Lag-corrected (excluding files whose diff is pure deletion of main's new lines):

| metric | count | % of 590 |
|---|---|---|
| real branch-side additions on ≥1 line | **175** | 29.7 % |
| real branch-side additions on **all 4** | **49** | **8.3 %** |
| of the 74 all-4 files, pure temporal lag on all 4 | **19** | 3.2 % |

### The divergence sets are almost perfectly **nested** (overlap matrix, `.java` modified)

| | ∩26.1 | ∩1.21.11 | ∩1.21.10 | ∩1.21.1 | own |
|---|---|---|---|---|---|
| **26.1** | 80 | 79 | 79 | 74 | 80 |
| **1.21.11** | 79 | 90 | 90 | 85 | 90 |
| **1.21.10** | 79 | 90 | 143 | 137 | 143 |
| **1.21.1** | 74 | 85 | 137 | 188 | 188 |

1.21.11's set **fully contains** 1.21.10's ∩ … no — read it as: 1.21.10 ⊇ 1.21.11 (all 90 of 1.21.11's files are in 1.21.10's 143), and 1.21.1 ⊇ 96 % of 1.21.10 (137/143, only 6 files 1.21.10-exclusive). **This is a strict ladder, not four independent forks.**

### Using 26.1 as the "temporal-lag control"

26.1 is MC 26.1 vs main's 26.2: same Java 25, same loom, same `official` mapping namespace, no `Identifier` rename, near-identical NBT (only 2 goldens move). Its 80 diverging files are therefore **almost entirely backport lag + line identity, not MC-API drift**. Subtracting that baseline gives the version-attributable count:

| line | own | ∩26.1 (lag baseline) | **version-attributable `.java`** | % of 590 |
|---|---|---|---|---|
| 1.21.11 | 90 | 79 | **11** | 1.9 % |
| 1.21.10 | 143 | 79 | **64** | 10.8 % |
| 1.21.1 | 188 | 74 | **114** | 19.3 % |

**MC 1.21.11 costs only ~11 genuinely different Java files vs MC 26.2.** The cliff is at 1.21.11→1.21.10 (the `Identifier` rename + Sodium 0.8 loss) and again at 1.21.10→1.21.1.

### The ~20 hottest files (diverge on most lines, largest real divergence)

Ranked by (# lines with real branch-side additions, then total additions). All under `/home/vox/projects/voxel-server-support/`:

| # | file | lines | Σ+ | Σ− |
|---|---|---|---|---|
| 1 | `fabric/src/gametest/java/dev/vox/lss/test/ServiceLifecycleGameTests.java` | 4 | 320 | 1,416 |
| 2 | `fabric/src/gametest/java/dev/vox/lss/test/GenerationLifecycleGameTests.java` | 4 | 276 | 263 |
| 3 | `fabric/src/gametest/java/dev/vox/lss/test/SerializerParityGameTests.java` | 4 | 273 | 281 |
| 4 | `xplat/src/main/java/dev/vox/lss/compat/VoxyCompat.java` | 4 | 170 | 1,096 |
| 5 | `paper/src/main/java/dev/vox/lss/paper/PaperRequestProcessingService.java` | 4 | 152 | 1,390 |
| 6 | `xplat/src/main/java/dev/vox/lss/networking/server/ChunkDiskReader.java` | 4 | 144 | 67 |
| 7 | `paper/src/main/java/dev/vox/lss/paper/PaperFarPlayerSnapshots.java` | 4 | 130 | 166 |
| 8 | `fabric/src/main/java/dev/vox/lss/networking/client/FarPlayerRenderer.java` | 4 | 125 | 104 |
| 9 | `fabric/src/gametest/java/dev/vox/lss/test/TwoPlayerGameTests.java` | 4 | 103 | 115 |
| 10 | `paper/src/test/java/dev/vox/lss/paper/PluginYmlContractTest.java` | 4 | 99 | 249 |
| 11 | `fabric/src/test/java/dev/vox/lss/FabricModJsonContractTest.java` | 4 | 84 | 175 |
| 12 | `fabric/src/gametest/java/dev/vox/lss/test/CommandGameTests.java` | 4 | 84 | 98 |
| 13 | `fabric/src/test/java/dev/vox/lss/LanHookContractTest.java` | 4 | 76 | 24 |
| 14 | `paper/src/test/java/dev/vox/lss/paper/ReleaseWorkflowContractTest.java` | 4 | 70 | 171 |
| 15 | `xplat/src/main/java/dev/vox/lss/networking/client/ResetCoordinator.java` | 4 | 68 | 588 |
| 16 | `fabric/src/test/java/dev/vox/lss/compat/VoxyCompatTest.java` | 4 | 64 | 1,404 |
| 17 | `fabric/src/main/java/dev/vox/lss/networking/client/LSSClientCommands.java` | 4 | 59 | 31 |
| 18 | `xplat/src/main/java/dev/vox/lss/networking/client/ColumnCacheStore.java` | 4 | 54 | 774 |
| 19 | `common/src/main/java/dev/vox/lss/common/wire/NativeSectionShape.java` | 4 | 53 | 91 |
| 20 | `fabric/src/main/java/dev/vox/lss/mixin/IntegratedServerLanHook.java` | 4 | 51 | 64 |
| 21 | `fabric/src/test/java/dev/vox/lss/common/wire/NativeToV20TranslatorTest.java` | 4 | 50 | 23 |
| 22 | `neoforge/src/main/java/dev/vox/lss/neoforge/LSSNeoClientBootstrap.java` | 4 | 44 | 16 |

Caveat: the very large Σ− values (#1, #4, #5, #15, #16, #18) are dominated by backport lag, not drift — these are files main has grown heavily since 2026-08-15. The **Σ+ column is the honest version-heat signal**, and by that measure the true hot core is: the 4 gametest suites, `FarPlayerRenderer`, `ChunkDiskReader`/`BackgroundIoSubmit`, `NbtSectionSerializer` family, `NativeSectionShape`, `PaperFarPlayerSnapshots`, `VoxyCompat`, and the ~10 contract tests.

---

# 4. The gradle / toolchain axis

## Identical on all five refs
`/home/vox/projects/voxel-server-support/build.gradle`, `/…/settings.gradle`, `/…/gradlew`, `/…/gradlew.bat`, `/…/gradle/wrapper/gradle-wrapper.properties` + `.jar`. **Zero gradle-wrapper or root-build divergence.** Also `loader_version=0.19.3` and `loom_version=1.17.13` are identical everywhere.

## Per-line differences

| axis | main (26.2) | 26.1 | 1.21.11 | 1.21.10 | 1.21.1 |
|---|---|---|---|---|---|
| **Loom plugin id** | `net.fabricmc.fabric-loom` | `fabric-loom` | **`fabric-loom-remap`** | **`fabric-loom-remap`** | **`fabric-loom-remap`** |
| **Loom version** | 1.17.13 | 1.17.13 | 1.17.13 | 1.17.13 | 1.17.13 |
| **mappings** | *(none — mojmap jars direct)* | none | `loom.officialMojangMappings()` | `loom.officialMojangMappings()` | `loom.officialMojangMappings()` |
| **dep scopes** | `implementation` | `implementation` | `modImplementation`/`modCompileOnly` | `modImplementation`/`modCompileOnly` | `modImplementation`/`modCompileOnly` |
| **release jar task** | `jar` | `jar` | **`remapJar`** | **`remapJar`** | **`remapJar`** |
| **mapping namespace** | `official` | official | intermediary | intermediary | intermediary |
| **accessWidener header** | `v2 official` | `v2 official` | **`v2 named`** | **`v2 named`** | **`v2 named`** |
| **fabric `options.release`** | 25 | 25 | **21** | **21** | **21** |
| **paper `options.release`** | 25 | 25 | **21** | **21** | **21** |
| **neoforge `java.toolchain.languageVersion`** | `of(25)` | 25 | **`of(21)`** | **`of(21)`** | **`of(21)`** |
| **CI `setup-java` version** | `${{ env.LINE_JAVA_VERSION }}` | same (25) | same (21) | same (21) | **hardcoded `21`** (R2-2 regression on this branch) |
| **paperweight dev bundle** | `26.2.build.84-stable` | 26.1.2 family | `1.21.11-R0.1-SNAPSHOT` | `1.21.10-R0.1-SNAPSHOT` | `1.21.1-R0.1-SNAPSHOT` |
| **neoforge version** | 26.2.0.59 | 26.1.2.95 | 21.11.45 | 21.10.64 | 21.1.248 |
| **neoforge loader floor (toml)** | **derived** `[${neoforge_floor},)` | literal `[26.1,)` | literal `[21.11,)` | literal `[21.10,)` | literal `[21.1,)` |
| **fabric-api** | 0.154.0+26.2 | 0.151.0+26.1.2 | 0.141.4+1.21.11 | 0.138.4+1.21.10 | 0.116.15+1.21.1 |
| **Tier-3 client gametests** | `def tier3 = true` + poison-pill block | true | true | true | **`false`** (block deleted) |
| **Sodium compile pin** | `${sodium_version}` mc26.2-0.9.1-beta.3 | mc26.1.2-0.8.12 | mc1.21.11-0.8.12 | **none (0.7.3 max)** | mc1.21.1-0.8.13-beta.2 |
| **`sodiumModernGolden` arm** | unconditional | unconditional | unconditional | **`if (hasProperty('sodium_version'))`**, `Expected=false` | unconditional |
| **`sodiumNeoGolden` config** | present | present | present | **entire block deleted** | present |
| **NeoForge dev-run wiring** | plain classpath | plain | plain | plain | **`evaluationDependsOn(':common')` + `sourceSet project(':common').sourceSets.main` + `additionalRuntimeClasspath`** (FML 4.x transformer-layer fix) |
| **NeoForge gametest filter** | `--tests lsstest:*` | same | same | same | **`systemProperty 'neoforge.enabledGameTestNamespaces'`** |
| **paper `folia_supported` derivation** | reads `.github/line.env` | **block deleted** | deleted | deleted | deleted |
| **fabric `processResources` expands** | `version, minecraft_dependency, fabric_api_dependency, suggests_sodium, suggests_voxy` | −2 (`suggests_*`) | −2 | **−4** (only version + mc_dep) | −2 |

**Two important asymmetries for a collapse plan:** (i) `paperweight` is a *plugin applied in `paper/build.gradle`* with a per-line **dev-bundle coordinate only** — the plugin version itself never differs. (ii) Several main-side parameterizations (`neoforge_floor` derivation R2-6, `folia_supported` from line.env R2-5, `LINE_JAVA_VERSION` sourcing in `build.yml` R2-2, `suggests_*` templating) exist **only on main** and are unbackported — collapsing forward gains them for free.

---

# 5. Version-conditional machinery ALREADY in main

Main has a mature, documented version-isolation framework. This is the pattern the collapse should extend, not invent.

**Canonical docs** (`/home/vox/projects/voxel-server-support/docs/planning/`):
- `version-port-isolation-plan.md` (415 lines) — the V-1/V-2/V-3 program.
- `per-version-surfaces.md` — **the canonical 19-row table** of every pinned-vanilla surface a port must re-verify, each with its enforcing contract test or a "hand" marker. Rows 1, 5, 6, 9, 10, 11, 12, 14, 15, 17, 18, 19 are exactly the drift I measured in §2c.
- `pre-authorized-cuts.md` — the 7-row list of cuts a best-effort line may take without a new decision.
- `port-isolation-round-2-plan.md`, `port-runbook.md`, `v0-13-0-port-plan.md`, `neoforge-1.21.1-port-spike.md`, `cross-version-identity-encoding-plan.md`.

**Data-as-line-parameter (the primary pattern):**
- `/home/vox/projects/voxel-server-support/common/src/main/java/dev/vox/lss/common/wire/NativeSectionShape.java` — the exemplar. Four LINE-level constants (`NATIVE_COUNT_SHORTS`, `NATIVE_LONG_ARRAY_PREFIXED`, `foldedCountForNativeHeader`, plus `foldedCountFabricFamily`/`foldedCountPaperFamily`) that the wire cursor, both serializers, and three relationship tests all *derive* from. On main the folds `throw IllegalStateException("no single-short fold on a 2-short line")` — deliberate poison pills. A port flips constants instead of re-flavoring the cursor.
- `/home/vox/projects/voxel-server-support/.github/line.env` — release-line identity as a sourced env file; `build.yml` sources it for `LINE_JAVA_VERSION`, `paper/build.gradle` parses it for `foliaSupported`, `scripts/release_check.py` derives `SHIP_NEOFORGE` from it, and `ReleaseWorkflowContractTest` cross-pins it against `gradle.properties`.
- `/home/vox/projects/voxel-server-support/gradle.properties` — `minecraft_dependency`, `fabric_api_dependency`, `sodium_legacy_golden` etc. explicitly commented as "LINE DATA (R2-6)".

**Single-file seams (concentrate drift into one swappable file):**
- `/…/xplat/src/main/java/dev/vox/lss/networking/server/BackgroundIoSubmit.java` (V-3/S4) — absorbs the executor-type churn so `ChunkDiskReader`'s five signatures stay line-invariant. Its javadoc literally records the 1.21.1 churn it was created to contain.
- `/…/fabric/src/gametest/java/dev/vox/lss/test/TestPositions.java` (V-3/T2) — one file holds `ChunkPos` ctor/`asLong`/ticket-API flavor for ~48 sites.
- `/…/xplat/src/main/java/dev/vox/lss/platform/SectionConstruction.java` + `/…/paper/src/main/java/dev/vox/lss/paper/PaperSectionConstruction.java` — the `PalettedContainerFactory` vs `Registry<Biome>` seam.
- `/…/fabric/src/gametest/java/dev/vox/lss/test/Gt.java` (1.21.10 branch only) — the ~550-site `GameTestHelper` adapter; the best template in the fleet for a shim-based collapse.

**Explicit version-volatility registry:**
- `/…/fabric/src/test/java/dev/vox/lss/testutil/VersionVolatileFileListTest.java` — a **hard-coded list of 3 files** (`dev/vox/lss/networking/client/FarPlayerRenderer.java`, `dev/vox/lss/mixin/ChunkSaveDataHook.java`, `dev/vox/lss/compat/ScopedCarrier.java`) that MUST live as same-FQN per-loader twins, never in `xplat`, so a support line replaces whole files merge-conflict-free. It reds the *legal-looking* refactor (move to xplat, delete twins) that the compiler would happily accept.
- `/…/fabric/src/test/java/dev/vox/lss/testutil/XplatJava21SurfaceTest.java` — scans compiled xplat class constant pools for post-Java-21 JDK API (`java/lang/ScopedValue`, `StructuredTaskScope`, `java/lang/classfile`, `java/lang/foreign/`, `Gatherer`). `KNOWN_25_ONLY` is **deliberately empty** — a new entry means per-line patch burden on every support line.

**Reflection ladders / runtime probes (51 files use reflection; the deliberate ones):**
- `/…/fabric/src/main/java/dev/vox/lss/config/menu/SodiumGeneration.java`, `SodiumConfigScreens.java`, `LegacySodiumPage.java` (+ neoforge twins) — a **reflective generation switch** between Sodium 0.6/0.7's internal options API and 0.8+'s public config API, with zero compile dependency on the legacy side. This is the repo's most sophisticated drift absorber and is already line-invariant.
- `/…/xplat/src/main/java/dev/vox/lss/compat/VoxyCompat.java`, `ModCompat.java`, `AntiXrayCompat.java`, `MoonriseReadCompat.java`, `XaeroMapCompat.java` — reflective mod-surface ladders with graceful degradation rungs.
- `/…/common/src/main/java/dev/vox/lss/common/compat/ViaProbe.java`, `/…/paper/src/main/java/dev/vox/lss/paper/FoliaSupport.java`, `PaperChannelPressure.java`, `PaperWorldHandler.java`.
- Runtime version reads: `net.minecraft.SharedConstants.getProtocolVersion()` in `/…/paper/src/main/java/dev/vox/lss/paper/LSSPaperPlugin.java:431` and `/…/fabric/src/gametest/…/ServiceLifecycleGameTests.java:1585`; `getCurrentVersion()` in `/…/paper/src/main/java/dev/vox/lss/paper/PaperPayloadHandler.java:53` and `/…/fabric/src/test/java/dev/vox/lss/ToolchainContractTest.java:113`.
- Mixin containment: `require = 0` on `ChunkSaveDataHook` (a bypassing chunk-system degrades to no dirty detection, never a crash); `defaultRequire: 1` on the accessors (loud fail).

**Contract-test enforcement layer:** `ToolchainContractTest`, `ReleaseWorkflowContractTest`, `PluginYmlContractTest`, `FabricModJsonContractTest`, `LanHookContractTest`, `SaveHookContractTest`, `MoveTraceHookContractTest`, `XaeroWiringContractTest`, `SodiumLegacyHookContractTest`, `SodiumLegacySurfaceResolvesTest`, `SodiumConfigApiContractTest`, `SodiumNeoGoldenParityTest`, `NeoForgeModuleContractTest`, `ClientMenuEntrypointContractTest`, `GameTestEntrypointContractTest` — a three-link chain (line.env ↔ gradle.properties ↔ resolved MC artifact) that makes a wrong-line forward-merge red rather than silent.

---

# Assessment

**The codebase is far less version-hot than the raw diffs suggest.** Of main's 590 Java files, only **195 (33 %) diverge on any support line and just 74 (12.5 %) on all four** — and those headline numbers are badly inflated by an eleven-day backport lag (main is 212 commits ahead of every branch; ~19 of the all-four-line files and ~25 per line have *zero* branch-side content, they are simply missing main's newer code, and every one of the 54–98 apparent "deletions" per branch is unbackported main work rather than a feature cut, with exactly **two** genuine file removals fleet-wide). Correcting for lag by using the near-API-identical 26.1 line as a control, the truly version-attributable Java surface is **11 files for MC 1.21.11 (1.9 %), 64 for 1.21.10 (10.8 %), and 114 for 1.21.1 (19.3 %)** — and of the oldest line's 188 diverging files, **44 (23 %) differ by nothing but the mechanical `Identifier`→`ResourceLocation` rename**, leaving a hard core of only **125 files, ~21 % of the tree, on the worst line, shrinking to 55 on the newest**. The divergence sets are strictly nested (1.21.1 ⊃ 96 % of 1.21.10 ⊃ 100 % of 1.21.11 ⊃ the 26.1 lag baseline), the root `build.gradle`/`settings.gradle`/gradle wrapper are byte-identical across all five refs, the `xver-live-corpus` cross-version fixture never moves, and the real drift decomposes into a small, enumerable set of axes already catalogued in main's own 19-row `per-version-surfaces.md`: one class rename, one NBT-accessor family flip, ~15 removed/renamed MC types, ~10 signature changes, two architectural reworks (far-player render extract/submit; Bukkit unified-vs-split world layout), a Java 25→21 floor whose entire footprint is one 130-line `ScopedCarrier` file, and a Sodium 0.8-API availability boolean. Main already demonstrates every technique needed to absorb all of it — LINE-level constants in `NativeSectionShape`, single-file seams (`BackgroundIoSubmit`, `TestPositions`, `SectionConstruction`), the 1.21.10 branch's `Gt.java` shim that neutralizes 550 call sites in 30 lines, reflective generation switches for Sodium, the `VersionVolatileFileListTest` whole-file-twin registry, and a `line.env`/`gradle.properties` data plane cross-pinned by fifteen contract tests. **Roughly 80 % of the tree is already genuinely shared and another ~5 % is mechanically derivable; the irreducible version-hot residue is on the order of 60–90 files concentrated in the NBT/wire serializers, the far-player renderer, the IO-submit and ticket seams, the gametest suites, and the per-line contract tests** — well within reach of a single multi-version branch built on the seams that already exist.
