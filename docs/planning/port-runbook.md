# The port runbook (V-1/D2) — how a support-line round runs

Consolidates the process reconstructed from the v0.7.3/v0.8.0/v0.10.0 rounds, as
amended by version-port-isolation-plan.md v1.1. The per-surface verification table is
docs/planning/per-version-surfaces.md; this doc is the ORDER and the rules.

## Standing rules (P4 — process hygiene)

1. **Sibling re-ports cut from the SAME main commit.** The v0.10 round's 70-minute
   skew silently cost the 26.1 line 275 lines of test coverage (PR #112 landed
   between the two cuts). One commit hash, recorded in each cut's first commit
   message.
2. **Port-surface measurement is tag-relative**: `git diff <mainline-tag>
   origin/support/<line>` — never `main...branch` (2-3× inflated by cherry-picks and
   stale merge-bases), and never a forward merge run for the metric's sake (a content
   merge into feature-cut lines re-creates keep-ours on feature files, and the major
   lines' regenerated binary goldens conflict on every main-side regen).
3. **Tag-text convention**: per-line notes files live on MAIN under `docs/planning/`
   (`release-tag-v<X>*.txt`); branches do not fork the convention.
4. **The keep-ours merge ritual is RETIRED for the pipeline pair** — release.yml and
   `ReleaseWorkflowContractTest` are branch-invariant since V-1/P1-P2; a support line
   owns `.github/line.env` (+ per-line build.gradle dep pins) instead.
5. **Gate-host coupling**: `ReleaseWorkflowContractTest` lives in `:paper:test`
   because both workflows run `:paper:test` before any publish step. If a line ever
   ships without the Paper module, re-home that gate FIRST.

## The round, in order

1. **Recipe research.** The archived same-line branch is the rename-set recipe; a new
   line gets a research dossier + reviewed plan (the 1.20.1/1.21.1 precedent).
2. **Cut.** Delta-port onto the current support branch where one exists (cheaper —
   the v0.11.0 G row's decision); fresh cut from main only for new lines, with the
   cost stated. Same-commit rule (above) across siblings.
3. **Line identity commit**: `.github/line.env` (~10 values: tag suffix, three MC
   tokens, three game-version lists, paper loaders, NeoForge name prose ≤64 chars
   resolved, make_latest=false, Java version) + `gradle.properties`
   (`minecraft_version`, `minecraft_dependency`, dep pins) + per-line build.gradle
   pins. `ReleaseWorkflowContractTest` + `ToolchainContractTest` follow the data
   automatically; `FabricModJsonContractTest`/`PluginYmlContractTest` keep small
   per-line FORM constants.
4. **Toolchain retarget**: loom(-remap) plugin, mappings namespace
   (`release_check.py` `FABRIC_MAPPING_NAMESPACE`), Java release, mixins
   `compatibilityLevel`, accesswidener namespace, `vssJar dependsOn remapJar` where
   remapping. `ToolchainContractTest` reds until this agrees with line.env.
5. **Source renames/API flavors** per the recipe + the per-version-surfaces table
   rows 1-6, 9, 11.
6. **Fixtures**: regenerate the v20 corpus FIRST (the serializer test's
   `LSS_REGEN_GOLDENS` gate), then natives via `NativeCorpusRegenTool` (same env
   var) — as TWO separate `--tests`-scoped invocations: the shared lever fires both
   stages inside one whole-module JVM in arbitrary test order (natives possibly
   derived from stale v20), and only the mandatory flag-off re-run's bijection pin
   would catch it. Two NON-mechanical rows: `duplicate-air.bin` (hand byte-fold preserving the
   duplicate palette — fromV20 would collapse it) and `xray-masked.bin` (the
   mask-filter golden test's own regen). `xver-live-corpus` is NEVER regenerated.
7. **The per-version-surfaces walk** (the R-7 step): every table row verified against
   the line's own MC artifact; contract-test flavors re-derived; hand rows recorded
   in the port PR description.
8. **Harness retarget**: test-server.sh's LINE DATA block (MC/CDN URLs, legacy LSS
   pin); soak.sh needs NOTHING for the base-world guard since V-1/T3a (the
   mc-version marker is data-driven from gradle.properties).
9. **Validation**: Tier 1+2 local, `CI=true` release build + `release_check
   --version`, CI green incl. Tier 3 where the line has it, per-line soak smokes.
   **Rehearse the release pipeline** with the workflow_dispatch dry-run on the
   branch — it exercises every resolved line.env value end-to-end and cannot
   publish.
10. **Review round** per the mega plan's §6.2 discipline (these rounds have caught
    publish-killing bugs every large round: the `*+` tag-filter trap, the
    mapping-namespace gate, three constructed workflow false-greens).
11. **Docs**: the branch CLAUDE.md banner is a POINTER to per-version-surfaces.md
    plus a line-status column — never a second live copy.
12. **Release** (simultaneous rounds): per-line annotated tags `--cleanup=verbatim`,
    annotation verified via `git for-each-ref` BEFORE push, support lines
    `make_latest=false` (from line.env), watch every run, never re-run a partial
    publish, verify rendered notes on GitHub + Modrinth after.
13. **Back-flow**: hardening invented on a branch during the round is PR'd back to
    main in the same round (the T3 rule — the soak marker guard was re-invented four
    times because this step didn't exist).
