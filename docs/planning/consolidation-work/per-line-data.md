# Per-line data extraction (Phase 0 reference, 2026-08-28)

Line-INVARIANT (stay in root gradle.properties): org.gradle.jvmargs, org.gradle.parallel,
loader_version=0.19.3, loom_version=1.17.13, mod_version=0.14.0, maven_group=dev.vox,
archives_base_name=lod-server-support-fabric.

Per-line → lines/<line>/line.properties (TOTAL, explicit values, no key-absence — plan §3.1):

| key | 26.2 | 26.1 | 1.21.11 | 1.21.10 | 1.21.1 |
|---|---|---|---|---|---|
| minecraft_version | 26.2 | 26.1.2 | 1.21.11 | 1.21.10 | 1.21.1 |
| minecraft_dependency | >=26.2 <26.3- | >=26.1 <26.2- | 1.21.11 | 1.21.10 | 1.21.1 |
| fabric_api_dependency | >=0.152.1 | (absent) | (absent) | (absent) | >=0.100.0 |
| fabric_version | 0.154.0+26.2 | 0.151.0+26.1.2 | 0.141.4+1.21.11 | 0.138.4+1.21.10 | 0.116.15+1.21.1 |
| neoforge_version | 26.2.0.59 | 26.1.2.95 | 21.11.45 | 21.10.64 | 21.1.248 |
| has_modern_sodium | true | true | true | FALSE | true |
| sodium_version | mc26.2-0.9.1-beta.3-fabric | mc26.1.2-0.8.12-fabric | mc1.21.11-0.8.12-fabric | (none) | mc1.21.1-0.8.13-beta.2-fabric |
| sodium_legacy_golden | mc1.21.1-0.6.13-neoforge | mc1.21.1-0.6.13-neoforge | mc1.21.1-0.6.13-neoforge | mc1.21.10-0.7.3-neoforge | mc1.21.1-0.6.13-neoforge |
| modmenu_version | 20.0.0-beta.4 | (absent) | (absent) | (absent) | (absent) |
| moonrise_modrinth_version | W0HImEBl | (absent) | (absent) | (absent) | (absent) |
| c2me_modrinth_version | nvOkOiyi | (absent) | (absent) | (absent) | (absent) |
| suggests_sodium | >=0.9.0 | (absent) | (absent) | (absent) | (absent) |
| suggests_voxy | >=0.2.17-alpha | (absent) | (absent) | (absent) | (absent) |

line.env values (per-line, already in each branch's .github/line.env → lines/<line>/line.env):
LINE_TAG_SUFFIX, LINE_MC_FABRIC/PAPER/NEOFORGE, LINE_JAVA_VERSION (25/25/21/21/21),
LINE_MAKE_LATEST (true only 26.2), LINE_GAME_VERSIONS_*, LINE_PAPER_LOADERS
(paper purpur folia | same | same | paper purpur | paper purpur),
LINE_NEOFORGE_NAME, LINE_SHIP_NEOFORGE (true|true|false|false|true),
LINE_FABRIC_MAPPING_NAMESPACE (official on 26.2/26.1; intermediary on 1.21.x — REQUIRED, no default).

## Build-script arms (the crux — per-line build.gradle differences to reconcile in gradle/line.gradle)
- fabric loom plugin id: `net.fabricmc.fabric-loom` (26.2/26.1) vs `net.fabricmc.fabric-loom-remap` (1.21.x)
  → conditional apply keyed on mapping_namespace; belt `-Pfabric.loom.dontRemap`.
- mappings: none (26.x direct mojmap) vs `loom.officialMojangMappings()` (1.21.x).
- dep scopes: implementation (26.x) vs modImplementation/modCompileOnly (1.21.x).
- release jar task: `jar` (26.x) vs `remapJar` (1.21.x) → `releaseJarTask` var (vssJar dependsOn).
- accesswidener header: `v2 official` (26.x) vs `v2 named` (1.21.x) → committed per-line overlay.
- options.release / toolchain / mixin compatibilityLevel: from line_java_version (25/21).
- sodium-golden arm: guarded on has_modern_sodium (1.21.10 cut + 25 caffeinemc files excluded).
- 1.21.1 NeoForge FML-4 run wiring (evaluationDependsOn(':common') + additionalRuntimeClasspath).
- NeoForge gametest filter idiom (--tests vs neoforge.enabledGameTestNamespaces).
- paperweight dev-bundle coordinate (data); paperweight PLUGIN version fleet-identical.

Source-of-truth worktrees for folding: /home/vox/projects/lss-port-{26.1,1.21.11,1.21.10,1.21.1}.
