# Single-branch build options research (2026-08-27)

> Produced by the v0.14.0 program's research agent as input to the single-branch
> consolidation plan. Companion doc: single-branch-divergence-analysis-2026-08-27.md.

Research complete. Here is the memo.

---

# Single-branch multi-version × multi-loader: option space for LSS

**Discriminating constraints for this codebase** (from `/home/vox/projects/voxel-server-support/CLAUDE.md`): 5 MC lines (26.2, 26.1, 1.21.11, 1.21.10, 1.21.1) currently on branches; 3 loaders including **Paper via paperweight-userdev** (no mod-build framework targets this); Mojang mappings everywhere (so cross-version *remapping* is not needed — only API-rename conditionals); Java 25 on 26.x vs Java 21 on 1.21.x, and old-line paperweight/codebook 1.0.15 cannot parse Java 25 bytecode; ~1250 Tier-1 + 71 Tier-2 + Tier-3 + ~330 Paper tests that must run **per line**; the VSS branded jar twins (`vssJars`); `release_check.py`/line.env contract machinery. The multi-**loader** axis is already solved in-repo (common/ + xplat/ shared source set + `LoaderServices` seams); the unsolved axis is multi-**version**.

---

## 1. Prism (Leclowndu93150/Prism)

**What it is.** A Gradle plugin (settings plugin `dev.prism.settings` + project plugin `dev.prism`) that wraps Fabric Loom, ModDevGradle, and MDG-Legacy under one DSL, generating per-version/per-loader subprojects from a `prism { version("26.2") { neoforge { … } } }` block. Not a preprocessor, not a template — a build-tool facade. Docs at prism.leclowndu93150.dev; plugin served from the author's own maven (`maven.leclowndu93150.dev`, must be first in pluginManagement).

**Mechanism / code sharing.** Layout is `versions/{mc}/{common,fabric,neoforge}/src/...`. Sharing tiers per the docs' own table: root `common/` = all versions but **no Minecraft classes**; `versions/{mc}/common/` = MC code shared across loaders **within one version only**. There is **no cross-version sharing of MC-touching code and no preprocessor** — every MC version duplicates its MC code wholesale. For LSS that is exactly backwards: xplat/ + fabric/ + paper/ (the bulk of the codebase, MC-touching) would be copied 5×, recreating the cherry-pick problem inside one branch as copy-paste-across-directories.

**Ground truth from Foxy** (`/home/vox/projects/foxy` — which, note, is *not* hand-rolled: it runs Prism 0.5.17): `versions/26.1.2/src` and `versions/26.2/src` are full duplicate trees of 38 Java files in which exactly **one Java file + neoforge.mods.toml differ**. Works fine for a 2-version, single-loader, 38-file bridge mod; Prism handled toolchains (26.x→Java 25 auto), per-version deps, run configs, `./gradlew build` builds both, jars named `foxy-26.2-NeoForge-1.1.0.jar`. Escape hatches exist (`subprojects { tasks.withType<Jar>()… }` works — Foxy uses it to strip stub classes).

**Loaders/versions.** Fabric (any), NeoForge 1.20.2+, Forge/LexForge/legacy lines. **No Paper/Bukkit support at all** — the paper/ module would live outside Prism as a plain subproject anyway.

**IDE/tests/CI.** IntelliJ run configs per target, project-generator plugin, foojay JDK auto-download. Tests: nothing documented; each generated subproject is a normal Gradle project, so `fabric-loader-junit`/gametest wiring would be hand-added per version-subproject — undocumented territory. CI: one `./gradlew build` builds everything, or `:26.2:fabric:build` per target.

**Maturity.** 1 star, 0 forks, created 2026-04, last push 2026-07-23, single teenage author, plugin hosted only on his personal maven. That is a bus-factor-1 dependency for the entire build of a 5-line project.

**VSS fit.** Possible via escape hatches; Prism's jar-naming conventions would fight `release_check.py` globs.

**Migration/risks.** Migration = rewrite the whole multi-project build into Prism's DSL *and still* solve cross-version sharing yourself (Prism gives you nothing there), *and* carry paper/ outside it. Risks: abandonment, DSL not covering LSS's exotic needs (vssJars, soakShadowJar, jarJar-sqlite shape, gametest entrypoints), maven outage breaks builds. **Not viable as the backbone; its only lesson is the version-dir layout.**

## 2. Stonecutter (kikugie — now codeberg.org/stonecutter)

**What it is.** A comment-based preprocessor Gradle plugin, deliberately **build-tool agnostic** ("doesn't require any compiler or IDE extensions"; the plugin is platform-independent — Loom, MDG, and by extension paperweight are all just plugins on the versioned subprojects). Latest stable **0.9.7 (2026-07-19)** on the Gradle Plugin Portal, 0.10-alpha in active dev, 1300 commits, config-cache compatible. Docs: stonecutter.kikugie.dev (note: the site serves garbage to non-browser fetchers; use the codeberg `docs` repo).

**Mechanism.** In settings, each registered version becomes a real Gradle subproject (`versions/26.2.x-fabric` etc.) sharing **one physical source tree**; the preprocessor comments switch which code is live. Verified syntax: `//? if >=1.21 <26.1` line/block conditionals, **loader conditions** `//? if fabric { … //?}`, **swap/rename directives** `//~ if >=26.1 'OldName' -> 'NewName'`, compile-time constants `/*$ mc_version*/`. One version is "active" at a time in the working tree (inactive branches are literally commented out); a Gradle task (or the IntelliJ plugin's button) rewrites the tree to switch. `vcsVersion` pins which variant gets committed.

**Multi-loader.** The official `template-multiloader` flattens the matrix into version-ids: `match("1.21.1", "fabric", "neoforge")`, `match("26.2.x", "fabric", "neoforge", version = "26.2")` producing `{version}-{loader}` subprojects with per-loader build scripts (`build.fabric.gradle.kts`) over one `src/main/`. **For LSS the better composition is the inverse:** keep common/xplat/fabric/neoforge/paper modules and their `LoaderServices` seams, and register stonecutter versions per MC-touching module (the template's `match()` is buildSrc sugar over the underlying create-project-with-versions API) — the loader axis stays solved by xplat, stonecutter carries only the version axis. This composition needs a spike but is the documented API shape.

**Code sharing for the 1.21.1→26.2 spread.** Highest of any option: all version-stable code (the majority — common/, most of xplat/fabric/paper logic) exists once with zero annotation; renames across MC internals become `//~` swaps; structural divergence (ScopedValue-preview shim on 1.21.x, per-line mixin descriptors, the NATIVE_LONG_ARRAY_PREFIXED descriptor axis) becomes `//? if` blocks. Binary per-version assets (per-MC xray goldens) stay as gradle-side srcDir selection, as today.

**IDE.** Full resolution only for the *active* version; other versions' code is comments until you switch. IntelliJ plugin adds syntax highlighting, completion, and the switch button. This is the core DX tax: a 1.21.1-breaking edit made while 26.2 is active is caught at (chiseled/CI) build time, not edit time.

**Tests.** Each version-subproject is a full Gradle project → the entire existing test pyramid runs per version natively (`:versions:1.21.1-fabric:test`, gametests included); **chiseled tasks** run any task across all registered versions in one invocation (`chiseledBuild`, and you can register chiseled test tasks). This is the only framework option where "run the 1250-test T1 suite on all 5 lines from one checkout" is a first-class operation.

**CI/release.** Two models, both proven in templates (rotgruengelb's template ships build.yml building all versions + tag-triggered release.yml publishing everything): (a) single runner chiseled build — one Gradle daemon for everything, which collides with the paperweight/Java constraint below; (b) **matrix job per line invoking that line's subprojects directly, each with the right daemon JDK** — this maps 1:1 onto the existing line.env-derivation machinery and sidesteps the codebook problem entirely. One annotated tag → N-line matrix → all jars, replacing per-line tags.

**Paper fit.** Stonecutter doesn't know Paper exists — and that's its advantage: the versioned paper subprojects just apply paperweight-userdev themselves. See §"Paper cross-cutting" below for why this works.

**VSS fit.** `vssJars` remains ordinary Gradle code on each versioned subproject; registered chiseled = all brands × lines from one command.

**Adopters.** Elytra Trims (author's own, multi-loader multi-version), YACL/isXander (Stonecutter + **Modstitch**, isXander's Loom/MDG-unifying companion plugin), a healthy template ecosystem (rotgruengelb, meza/Stonecraft wiring Stonecutter+Architectury). Real but mostly small/mid-size mods; nothing LSS-scale with a Paper module — you'd be the pathfinder there.

**Migration/risks.** Migration = the real cost: diff each support branch against main and convert the deltas into conditionals/swaps (the per-line diffs are concentrated — mixin targets, serializer descriptor axes, sodium-page generations already runtime-probed, gradle/dep versions — but there are four of them). Phaseable: fold 26.1 in first (nearest line), 1.21.x later, or leave the best-effort 1.21.1 line as a branch forever. Risks: single-maintainer project (though healthiest in this niche and 3 years of releases); comment-noise accumulation over a 5-version × 3-loader spread; active-version workflow friction for a heavily agent-driven repo (every build/test command must name or switch the version); Gradle config time with ~15 version-subprojects.

## 3. Hand-rolled multi-version Gradle

**The prompt's exemplar (Foxy) is actually Prism** — see option 1; its "mechanism" is duplicate trees. The real hand-rolled archetypes are:

**(a) DH-style property-switched single-active-version** (see option 6): one branch, `mcVer` in gradle.properties or `-PmcVer=1.21.1`, build scripts resolve deps/toolchain/srcDirs from it, a preprocessor (DH uses **Manifold**) or plain conditional srcDir overlays handle divergence, one Gradle invocation builds ONE version. CI = a plain matrix over `-PmcVer` values, each job free to pick its own daemon JDK (Java 21 for 1.21.x paper jobs — dissolves the codebook constraint by construction). This is proven at absurd scale: Distant Horizons ships **21 MC versions (1.12.2→26.2)** per release from this model.

**(b) Per-version-subprojects in one build** — the PaperMC-blessed shape for the Paper half (below): `paper_1_21_1/`, `paper_26_2/` … each a real subproject; version-stable code lives in shared source sets `srcDir`'d into each (the xplat/ trick applied on the version axis), per-version divergence as small per-version source dirs holding same-FQN overrides (this repo already uses same-FQN twins for the loader axis — the identical pattern works for versions, no preprocessor at all).

**Code sharing.** (a) with Manifold: near-Stonecutter levels. (b) without any preprocessor: everything version-stable shared via srcDirs; divergence quarantined into per-version override dirs — coarser than comment-granularity (a one-line difference forces duplicating the file), but LSS has already practiced squeezing divergence behind seams (LoaderServices, runtime generation probes, `Brand`), so the override dirs stay small.

**IDE.** (a): only the selected version resolves (same tax as Stonecutter, minus the plugin niceties; Manifold has its own IntelliJ plugin). (b): all versions resolve simultaneously — the *best* IDE story of any option, at the cost of Gradle sync weight.

**Tests.** (a): full suite per `-PmcVer` invocation. (b): per-subproject test tasks, all runnable in one invocation (modulo daemon-JVM issues).

**CI/release.** Both map cleanly onto matrix-per-line, one tag → N jars. All the existing contract tests / release_check machinery carries over with path changes instead of branch changes.

**VSS fit.** Trivial — you own every line of the build.

**Migration/risks.** Highest control, zero third-party dependency (or one: Manifold, which is a mature general-purpose compiler plugin but a *javac plugin* — heavier coupling than comment preprocessing, and unproven against Java 25 targets in this niche without checking). You own all the build logic forever; this repo demonstrably has the build-engineering muscle (vssJars, soakShadowJar, jarJar surgery, line.env derivations), so that's a real but bounded cost.

## 4. Architectury / MultiLoader-Template style

**Mechanism.** Loader abstraction: a common source set compiled per loader (jaredlll08's MultiLoader-Template = vanilla Loom + MDG + common srcDir; Architectury adds its Loom fork + API + `@ExpectPlatform` transformers). Maintained and current (Architectury Loom active; MultiLoader-Template the de-facto standard).

**Verdict for LSS: not an option, a description of the status quo.** xplat/ + `LoaderServices` + same-FQN twins *is* the MultiLoader-Template pattern, already purity-pinned (`XplatLoaderPurityTest`). Architectury adds nothing (its API/transformers target Forge-vs-Fabric gaps LSS bridges itself; Mojmap already in use) and covers neither Paper nor multi-version. **It does not solve multi-VERSION at all** — every Architectury project still picks branches, Stonecutter (see meza/Stonecraft), or hand-rolling for that axis. Its only relevance: whatever you adopt must *compose with* (not replace) the existing xplat layout — Stonecutter and hand-rolled (b) both do.

## 5. ReplayMod preprocessor (JCP-style + remap)

**Mechanism.** JCP-inspired `//#if MC>=11202` directives with `//$$` auto-commented bodies; source lives at a `mainVersion`, is preprocessed *through a version graph* to the others (`./gradlew :1.9.4:setCoreVersion` to switch), per-version overlay dirs (`versions/$MC/src/main/java`) for full-file overrides, and — its signature feature — **automatic remapping between mappings/versions** plus type-aware `@Pattern` search-and-replace rewrites (`mc.getWindow()` → `mc.window`).

**Fit.** 95 stars, GPL-3, actively maintained but essentially a ReplayMod/EssentialGG-ecosystem internal tool (Essential's gradle toolkit productionizes it), JitPack-distributed, built around Loom-era Fabric assumptions and around the mapping-translation problem. **LSS is Mojmap-everywhere, so the remap machinery — the main thing this buys over Stonecutter — is dead weight**; what remains is a directive syntax comparable to Stonecutter's but with worse docs, GPL licensing questions for build-plugin adoption, no Paper story, and a far smaller adopter base. The one idea worth stealing regardless of option: per-version **overlay dirs** compose with any approach for files too divergent for comments.

## 6. What major mods actually do (2025-2026)

| Mod | Strategy (verified) |
|---|---|
| **Distant Horizons** | **The single-branch existence proof.** One branch, `mcVer=` in gradle.properties / `-PmcVer=`, **Manifold preprocessor**, `core` subproject + `fabric`/`forge`(/neoforge) loader dirs; latest release **3.2.0-b (2026-07) ships 21 MC versions, 1.12.2→26.2**, Fabric+NeoForge/Forge. IntelliJ + Manifold plugin; "run any gradle command to refresh" after a version switch. |
| **Sodium** | Per-MC-version **branches** (wiki: "branches specific to Minecraft versions or version ranges"), multiloader (Fabric+NeoForge) within each branch. I.e., LSS's current model. |
| **Iris** | Same shape: `multiloader-new`-style branch layout (fabric+neoforge dirs in one branch), versions as branches. |
| **Moonrise** | Pure per-version branches: `mc/1.21.1` … `mc/1.21.11`, `mc/26.1`, `mc/26.2`. |
| **Xaero's** | Closed source — publishes one jar per MC version per loader; build system unverifiable. No claim. |
| **YACL / Elytra Trims** | **Stonecutter** (YACL with Modstitch unifying Loom/MDG), multi-version + multi-loader single-branch. |

So the field splits cleanly: engine-invasive perf mods (Sodium/Iris/Moonrise) accept branches; mods whose core is version-stable logic over a thin MC surface (DH, YACL — **and LSS**, whose common/+wire/store/scanner core is exactly that) go single-branch with a preprocessor. DH is the closest analogue to LSS in shape (LOD system, big version-neutral core, thin per-version serializer/mixin skin) and it is the most successful single-branch operation in the ecosystem.

## Paper/paperweight in one branch — the cross-cutting hard part

No framework option handles Paper; but it doesn't need one:

- **Multi-version paperweight in one Gradle build is an officially demonstrated pattern**: PaperMC's own `paperweight-test-plugin` `multi-project` branch has `paper_1_17_1`, `paper_1_19_4`, `paper_26_1_2` subprojects in one build (userdev `2.0.0-beta.21` applied `apply false` at root — the documented workaround for a Gradle bug — then versionless per-subproject; conventions plugin + `java.disableAutoTargetJvm()`).
- **The Java constraint is acknowledged and has a knob**: the userdev docs state "a given dev bundle may not always support the Java toolchain Gradle is configured to use" and prescribe configuring the **`javaLauncher`** property (their example: compiling a 1.17.1 bundle from a Java-25 Gradle by pointing the launcher at an older JDK). That is exactly the LSS problem (codebook 1.0.15 vs Java 25) inverted, with the same fix.
- **The zero-risk alternative**: CI matrix-per-line with per-job daemon JDKs (Java 21 jobs for 1.21.x lines) never puts two paperweight generations in one daemon at all. Available under Stonecutter (invoke versioned subprojects directly) and hand-rolled (a) natively; only "everything in one chiseled invocation locally" needs the javaLauncher route.

## Comparison

| | Prism | Stonecutter | Hand-rolled (DH-style / per-ver subprojects) | Architectury/MLT | ReplayMod preproc |
|---|---|---|---|---|---|
| Mechanism | build-DSL facade, version dirs | comment preprocessor + versioned subprojects | property-switch or version subprojects, optional Manifold | loader abstraction only | JCP directives + remap + overlays |
| Cross-version sharing of MC code | **none** (duplicate trees) | maximal (line-granular) | high (Manifold) / medium (file-granular overrides) | n/a | high |
| Paper/paperweight | no; outside the tool | agnostic — paperweight per versioned subproject ✔ | native ✔ (PaperMC's own demoed pattern) | no | no |
| Tests per version | undocumented, DIY | first-class (chiseled over real subprojects) | first-class | n/a | per-version projects |
| IDE | per-target run configs | active-version only + IJ plugin | (a) active-only / (b) all resolve | n/a | active-version + overlays |
| CI: one tag → N jars | one invocation | chiseled or matrix-per-line ✔ | matrix-per-line ✔ | n/a | version-graph tasks |
| Maturity | 1★, single author, own maven | 0.9.7, active, real adopters | you own it; DH proves 21 versions | mainstream (wrong axis) | niche, GPL, remap-centric |
| VSS fit | fights conventions | plain Gradle, fine | trivial | n/a | awkward |
| Migration from 5 branches | rewrite build + still no sharing | convert 4 branch-diffs to conditionals (phaseable) | convert diffs to overrides/directives + own build | none (already have it) | convert + adopt their toolchain |
| Key risk | abandonment | single maintainer; active-version DX; you'd pathfind Paper | build-logic ownership forever | doesn't solve the problem | dead-weight remap, GPL, ecosystem lock |

## Recommendation (ranked)

1. **Stonecutter as the version axis, composed with the existing xplat/LoaderServices loader axis, paperweight-userdev per versioned paper subproject, CI as matrix-per-line with per-job JDKs.** It is the only *maintained, adopted* machinery that fits every LSS constraint: Mojmap makes its comment-conditionals sufficient (no remap needed), its build-tool agnosticism is precisely what lets the Paper module and the exotic Gradle surface (vssJars, soakShadowJar, jarJar) survive untouched, versioned-subprojects mean the 1600+-test pyramid runs per line natively, and the DH precedent shows the LSS code shape (big version-neutral core, thin MC skin) is the shape that wins single-branch. Do it as a **spike first** (register 26.2+26.1 only; prove the multi-module registration, one `//~` rename, one paper line) and phase lines in; consider leaving best-effort 1.21.1 as a branch permanently. Accept the active-version DX tax and the single-maintainer risk (mitigant: it's a preprocessor — ejecting means committing the generated per-version trees).
2. **Hand-rolled per-version subprojects (no preprocessor), same-FQN override dirs — the repo's existing twin pattern extended to the version axis.** Choose this if the spike shows Stonecutter's multi-module composition or agent-driven active-version workflow fights the repo. Costs file-granular (not line-granular) divergence and permanent build-logic ownership; buys the best IDE story (all versions resolve at once), zero third-party risk, and the PaperMC-demoed paperweight pattern verbatim. DH-style `-PmcVer` switching is the lighter sub-variant if per-line CI isolation matters more than IDE breadth.
3. **ReplayMod preprocessor** — only if Stonecutter dies; its remap superpower is worthless under Mojmap-everywhere and everything else it does, Stonecutter does with better docs and licensing. Steal its overlay-dir idea regardless.
4. **Prism** — do not adopt. It solves the axis LSS already solved (loader), skips the axis LSS needs (cross-version MC-code sharing — Foxy's own trees are 95% duplicated files, `/home/vox/projects/foxy/versions/*/src`), has no Paper story, and is a bus-factor-1 dependency served from a personal maven. Foxy's success is evidence it works for 38-file 2-version bridge mods, not for this.
5. **Architectury/MultiLoader-Template** — n/a: it's the pattern the repo already implements as xplat/; adopting it would be a rewrite with negative payoff. Its only role in the decision: whatever wins must compose with xplat/, which options 1 and 2 do and Prism does not.

Sources: [Prism repo](https://github.com/Leclowndu93150/Prism) · [Prism docs](https://prism.leclowndu93150.dev/getting-started) · [Stonecutter (Codeberg)](https://codeberg.org/stonecutter/stonecutter) · [Stonecutter plugin portal](https://plugins.gradle.org/plugin/dev.kikugie.stonecutter) · [template-multiloader](https://codeberg.org/stonecutter/template-multiloader) · [rotgruengelb template](https://github.com/rotgruengelb/stonecutter-mod-template) · [Stonecraft](https://github.com/meza/Stonecraft) · [DH repo](https://gitlab.com/distant-horizons-team/distant-horizons) · [DH versions (Modrinth)](https://api.modrinth.com/v2/project/distanthorizons/version) · [paperweight-userdev docs](https://docs.papermc.io/paper/dev/userdev/) · [paperweight-test-plugin multi-project](https://github.com/PaperMC/paperweight-test-plugin/tree/multi-project) · [Sodium support policy](https://github.com/CaffeineMC/sodium/wiki/Support-Policy) · [Moonrise branches](https://api.github.com/repos/Tuinity/Moonrise/branches) · [Iris multiloader-new](https://github.com/IrisShaders/Iris/tree/multiloader-new) · [ReplayMod preprocessor](https://github.com/ReplayMod/preprocessor) · [MultiLoader-Template](https://github.com/jaredlll08/MultiLoader-Template) · local: `/home/vox/projects/foxy/{settings.gradle.kts,build.gradle.kts,versions/}`
