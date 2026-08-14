# Client full-reset command (`/lss reset`) + client cache relocation to `.lss/` — plan

**Status: IMPLEMENTED — shipped in v0.11.0** (stage D, 2026-08-13; `/lss reset` + the `.lss`/`.vss` cache dot-dirs; kept as the design record). Two client-side features, one plan:

1. A new client command that clears BOTH the Voxy data cache (disk + **in-memory** —
   the player visibly watches all LODs disappear) AND the LSS client cache, then resets
   LSS client session state to fresh-join equivalent so the server re-streams
   everything and the LODs rebuild live.
2. Relocate the LSS client cache from `config/lss/cache/` to a `.lss/` folder in the
   game root (the `.voxy` convention) — adopting the OLD location when a cache already
   exists there; only fresh installs use the new location. The JSON client config stays
   at `config/lss-client-config.json`, untouched.

Exploration ground truth: the full Voxy source is checked out at `research/voxy/`
(HEAD `136381a7`) with a built 0.2.11 jar, and the newest shipped build is at
`~/projects/lss-multi-test/downloads/voxy-0.2.18-beta-26.1.2.jar`. Every Voxy claim
below was read from those, not inferred.

**Reviewed 2026-08-12** (1 Fable subagent; every load-bearing Voxy claim re-verified
by javap against BOTH shipped jars): verdict IMPLEMENT WITH FIXES, 3 MAJOR / 7 MINOR —
all folded into this revision. Headlines: the wipe root now comes from the live
instance's public `getStorageBasePath()` (stable across all three versions, and the
only correct answer under a Flashback replay); the sequence gains an await on the
decode-drain's in-flight column; and both mid-sequence failure paths
(shutdownInstance/createInstance throwing) are now specified.

---

## Part 1 — the reset command

### What already exists (reuse, don't rebuild)

- **`/lss clearcache`** (`LSSClientCommands.java:19-33`, branded root literal via
  `Brand.clientCommand()`): calls `LodRequestManager.flushCache()`
  (`LodRequestManager.java:715-723`) — deletes the LSS cache files for the current
  server (`ColumnCacheStore.clearForServer`), clears `ColumnStateMap` (including
  `sessionSatisfied` NOT_GENERATED parks), clears `InFlightTracker`, resets
  `RequestMetrics` rates, and `SpiralScanner.reset()` (re-primed to fire immediately).
  After it, the scanner re-declares the whole want-set from ring 0 with `ts<=0` ("I
  have nothing") and the server re-resolves honestly — **no server cooperation or
  re-handshake is needed; the wire model self-heals by design.** This is the LSS half
  of the reset, minus one gap (decode queue, below).
- **Voxy's own `/voxy reload`** (`research/voxy/.../client/VoxyCommands.java:80-98`) is
  the canonical in-memory teardown sequence:
  `shutdownRenderer() → VoxyCommon.shutdownInstance() → System.gc() → VoxyCommon.createInstance() → levelRenderer.allChanged()`.
  It does NOT touch disk. Our command replicates it reflectively and inserts the disk
  wipe into the down-window.
- **Bridge patterns**: `VoxyCompat`'s backlog probe (`VoxyCompat.java:191-215`) is the
  all-or-nothing "resolve a chain, partial = absent, separate failure domain" template;
  `AntiXrayCompat.buildEngineProbe` is the "live object, may fail per call, warn once"
  template. The reset ladder combines both.

### Voxy facts the design rests on (verified against 0.2.11 jar, 0.2.18 jar, dev source)

- `me.cortex.voxy.commonImpl.VoxyCommon` — `getInstance()`, `shutdownInstance()`,
  `createInstance()`, `isAvailable()` are `public static` and **byte-stable across all
  three versions**. The most stable reflection surface available.
- `shutdownInstance()` nulls the instance FIRST, then `VoxyInstance.shutdown()`
  (`commonImpl/VoxyInstance.java:216-270`) stops services and `world.free()`s every
  engine — which **flushes and closes storage**. So the disk wipe MUST happen after
  `shutdownInstance()` returns and before `createInstance()`; wiping a live store races
  `SectionSavingService` and gets partially resurrected by the flush-on-close.
- `createInstance()` **throws** if an instance already exists (`VoxyCommon.java:78-80`)
  and silently **no-ops** when the factory is null — GPU-unsupported / never enabled
  (`:74-77`). `isAvailable()` (`:85-87`) is the discriminator.
- **Renderer must go down BEFORE the instance** — and the failure mode is worse than a
  throw (review correction): `VoxyInstance.shutdown` busy-waits `while
  (world.isWorldUsed()) Thread.sleep(10)` (`VoxyInstance.java:248-255`) before
  `free()`. With the renderer still holding acquired sections and its release path
  unable to run (we ARE the main thread), that is an **unbounded main-thread freeze**,
  not an exception. This is why `/voxy reload` shuts the renderer down first, and why
  the abort-if-holder-unresolvable rule below is non-negotiable.
- **`VoxyClientInstance.getStorageBasePath()` is public in all three versions**
  (javap-verified in both shipped jars; dev `VoxyClientInstance.java:73-75`) and
  returns the path the live instance is ACTUALLY using — including the Flashback
  replay override (`:33-38`). **CORRECTED at stage D review (2026-08-13, §6.1
  pair):** the override path is the ORIGIN's real store path recorded at replay
  capture time, so reading it is necessary but NOT sufficient — wiping it during
  playback would destroy the origin server's store. The ladder therefore
  cross-checks the live root against the current connection's own derivation and
  skips the wipe on any mismatch (`RESET_WIPE_SKIPPED`).
- The renderer holder is **the one unstable name**: mixin interface,
  `me.cortex.voxy.client.core.IGetVoxyRenderSystem.shutdownRenderer()` in 0.2.11/dev
  (on vanilla `LevelRenderer`) vs `IVoxyRenderSystemHolder.voxy$shutdownRenderer()` in
  0.2.18-beta. Needs a two-rung resolver (repo precedent: `MoonriseSendStateCompat`'s
  two-rung ladder, `AntiXrayCompat`'s carrier ladder). **AMENDED at stage D
  implementation (2026-08-13, §6.1 pair — see the v0.11.0 progress doc decisions
  log):** the primary rung obtains the holder via the interface's **static
  `getNullableHolder()`** rather than instanceof on `Minecraft.levelRenderer` — that
  is what the 26.2 Voxy build's own reload does (bytecode-verified against the
  `voxy-0.2.18-beta.jar` for MC 26.2), and on 26.2 the render-extract rework means
  the mixin's carrier class is not knowable from LSS; the static abstracts it away.
  The instanceof-on-levelRenderer shape survives as rung 2 (0.2.11/dev).
- ~~`levelRenderer.allChanged()`~~ **AMENDED same pair:** on MC 26.2 vanilla moved
  `allChanged()` off `LevelRenderer` onto **`Minecraft.levelExtractor`**
  (`net.minecraft.client.renderer.extract.LevelExtractor.allChanged()`, both public)
  as part of the render-extract rework — the 26.2 Voxy reload calls exactly that
  (bytecode-verified), and it re-triggers Voxy's renderer rebuild. Pure vanilla MC
  either way (direct class literal per the CLAUDE.md MC-type rule); support lines on
  older MC keep the `levelRenderer.allChanged()` shape at stage G.
- Voxy's per-server disk cache: multiplayer `<gameDir>/.voxy/saves/<serverIp with
  ':'→'_'>/<32-hex worldId>/…`, singleplayer `<world dir>/voxy`, realms
  `.voxy/saves/realms`, null server info → `.../UNKNOWN`
  (`VoxyClientInstance.getBasePath`, `research/voxy/.../client/VoxyClientInstance.java:94-119`).
  NOTE: Voxy's ip-keying differs from LSS's `serverAddress` resolution
  (`LSSClientNetworking.java:156-170`) — the wipe must mirror **Voxy's** keying.
- Re-ingest after a wipe: `WorldUpdater.insertUpdate` re-meshes only on
  `didStateChange`, and **nothing in Voxy drops loaded geometry on ingest** — which is
  exactly why the instance teardown (not just a disk wipe + re-serve) is required for
  the "LODs visibly disappear" requirement.
- Gating: `rawIngest` returns false when Voxy ingest is disabled
  (`VoxyClientInstance.isIngestEnabled` — config toggle / Flashback replay). A reset
  with ingest off produces LSS ingest-failure reports, not repopulation — report it in
  the command feedback if detectable, otherwise let the normal containment handle it.

### Command design

**Name:** `/lss reset` (branded root, same tree as `clearcache`/`diag`/`trace` in
`LSSClientCommands.registerCommands`). `clearcache` stays unchanged (LSS-only,
documented behavior, soak-scripted).

**Sequence** (main client thread — same thread Voxy's own command and login/disconnect
mixins run on, so no concurrent-lifecycle race):

1. **Drain the LSS decode queue AND await the in-flight column**:
   `columnProcessor.reportUndispatched(manager)` (`ClientColumnProcessor.java:562-572`,
   epoch bump + drain) — then **wait, bounded, for the `processing` flag**
   (`ClientColumnProcessor.java:91`) to clear. The javadoc at `:558-560` is explicit
   that a column an in-flight drain already polled "still dispatches normally" on the
   `LSS-ColumnProcessor` thread — concurrently with step 2 — and `VoxyCommon.INSTANCE`
   is a plain non-volatile static, so that dispatch can open a fresh store INSIDE the
   directory being wiped (on Windows, an open handle partially fails the recursive
   delete). A polled column decodes in milliseconds, so the await is cheap and closes
   the race deterministically.
2. **Voxy teardown + wipe + rebuild** (new bridge entry point, see below):
   a. read the wipe root from the live instance's `getStorageBasePath()` (reflective,
      BEFORE shutdown — the only source that is correct under a Flashback replay);
      fall back to the hand-derived `getBasePath` logic only when the instance is
      null (config-disabled case, no live path to ask);
   b. two-rung holder resolve (AMENDED 2026-08-13, see the facts block: primary =
      the static `IVoxyRenderSystemHolder.getNullableHolder()`; the levelRenderer
      instanceof shape is the 0.2.11/dev fallback rung) → `shutdownRenderer()` —
      **if the holder is unresolvable, ABORT the Voxy half with a once-warn**
      (skipping renderer-first teardown risks the `isWorldUsed` busy-wait freezing the
      main thread — see the facts above; fail-safe direction), still run step 3;
   c. if `getInstance() != null` → `shutdownInstance()` inside its own containment:
      **if it throws** (e.g. the world-cleaner join interrupt,
      `VoxyInstance.java:220-223`), the instance is already nulled and storage may
      hold open handles — SKIP the wipe (deleting over open handles is the Windows
      partial-wipe trap), still attempt `createInstance()` (recovery — Voxy itself is
      instanceless at that point), and report "Voxy reset incomplete — rejoin to fully
      clear". If the instance was null, skip 2e's create (never create an instance
      Voxy itself didn't have — config-disabled/GPU cases) but still wipe (no live
      storage);
   d. recursively delete the resolved per-server directory, with containment:
      normalize + assert the target is under `.voxy`/the world dir before deleting
      (a replay path failing containment → skip wipe = fail-safe); any IO failure is
      contained + logged, never thrown;
   e. `System.gc()` (parity with `/voxy reload` — native storage handles), then
      `createInstance()`, then the renderer rebuild trigger (AMENDED 2026-08-13, see
      the facts block: on MC 26.2 that is `Minecraft.levelExtractor.allChanged()`;
      older lines keep `levelRenderer.allChanged()` — vanilla literal either way,
      exactly as Voxy's own reload does). **If `createInstance()` throws** (contained): the
      renderer stays down and every re-served column will fail ingest (bounded by the
      ingest-failure parking caps) — feedback must say "Voxy failed to restart —
      rejoin to recover", not the happy-path line.
3. **LSS fresh-join reset**: `manager.flushCache()` — after the new Voxy instance is
   live, so every re-served column lands in the fresh engine. Voxy-AFTER-drain,
   LSS-LAST ordering means columns that arrive over the wire during step 2 enqueue and
   dispatch into the NEW instance, and their stamps are cleared at step 3 → re-served →
   duplicate ingest, which is idempotent by protocol design (`LSSApi.java:88-93`).
   (Mid-command wire arrivals cannot slip into the queue during the command itself:
   both the stamp and the queue offer hop through `context.client().execute`,
   `LSSClientNetworking.java:224-227` — same thread as the command.)
4. **Feedback**: chat lines per BRANCH — happy path "Voxy LODs cleared (disk +
   memory)" + "LSS cache cleared — re-requesting from server" (the scanner is primed
   to fire immediately); holder-unresolvable "Voxy reset unavailable on this Voxy
   version — LSS cache cleared and re-requested" (LODs do NOT visibly disappear on
   this branch — the message must not claim they did); the two step-2 failure branches
   per 2c/2e above.

**No-manager fallback** (LSS inactive on this server, mirroring `clearcache`'s `:27`
fallback): `ColumnCacheStore.clearAll()` + the Voxy half if in-game — but this branch
is destructive with NO re-stream (no LSS server to refill Voxy; it repopulates only
from vanilla chunk loading), so it requires a confirm step (`/lss reset confirm`) and
its feedback says exactly that. With an active LSS session the single-step command
stands — the wipe is recoverable by construction.

### New `VoxyCompat` surface

- New handles, resolved lazily in their **own all-or-nothing failure domain**
  (pattern: `initBacklogProbe`, `VoxyCompat.java:191-215`): `getInstance`
  (`isAvailable` dropped at implementation — the null-instance branch already
  discriminates, review n1), `shutdownInstance`, `createInstance`,
  `VoxyClientInstance.getStorageBasePath` (the wipe-root source — public in all three
  versions), plus the two-rung renderer-holder resolve
  (`IVoxyRenderSystemHolder.voxy$shutdownRenderer` →
  `IGetVoxyRenderSystem.shutdownRenderer`; the interface Class is `Class.forName`-safe
  — it's a Voxy class, not an MC class). A failed resolve = the reset command reports
  "Voxy reset unavailable" once and does only the LSS half — it must never cost the
  existing ingest bridge (same isolation contract as the backlog probe).
- `VoxyCompat` is **package-private** (`dev.vox.lss.compat`); the command lives in
  `dev.vox.lss.networking.client`, so the entry point is a public facade on
  `ModCompat` (mirroring `getVoxyViewDistanceChunks`, `ModCompat.java:23-26`), gated
  on `voxyLoaded`.
- The orchestration body lives in a package-visible, seam-injected method (holder
  supplier, instance handles, wipe-root resolver, `Runnable allChanged`) so JUnit can
  drive the full ladder without MC — the `AntiXrayCompat.buildEngineProbe` injectable
  shape.
- Wipe-path derivation duplicated from `VoxyClientInstance.getBasePath` survives ONLY
  as the instance-null fallback (comment pinning the source file+line in
  `research/voxy`); the live path always comes from `getStorageBasePath()`. Drift in
  the fallback degrades to "wipes nothing / wrong-but-contained subdir of `.voxy`",
  never data loss outside `.voxy` — and a Flashback replay path failing the
  containment assert skips the wipe, the fail-safe direction.

---

## Part 2 — LSS client cache relocation (`config/lss/cache` → `.lss/cache`)

### Current state (all from `ColumnCacheStore.java`)

- Root is a static final: `FabricLoader.getInstance().getConfigDir().resolve("lss").resolve("cache")`
  (`:46`) → `config/lss/cache/<sanitized-server>/<sanitized-dim>.bin` (`:361-371`),
  `.tmp` sibling during save (`:115`). Deliberately **unbranded** (jar-swap continuity,
  `docs/planning/ci-dual-publish.md:88`) — keep `.lss` unbranded for the same reason.
- The client JSON config is fully independent (`LSSClientConfig.java:13-16` →
  `config/lss-client-config.json` via `brandedConfigCandidates`) — **no change there**.

### Design

- New root: `<gameDir>/.lss/cache/` via `FabricLoader.getInstance().getGameDir()`.
  Everything below the root (per-server dirs, sanitization, format v4, tmp+atomic
  moves) is unchanged.
- **Adoption rule, resolved once**: extract a pure function
  `static Path resolveCacheRoot(Path configDir, Path gameDir)` — if
  `configDir/lss/cache` **exists as a directory**, return it (existing installs keep
  their cache and their path; no migration, per the user decision); otherwise return
  `gameDir/.lss/cache`. Production caches the result lazily (replacing the eager
  static-final at `:46`) so tests can exercise both branches of the pure function;
  a directory-exists check is deliberately the whole test (an empty old dir still
  adopts — harmless, deterministic).
- Expose the resolved root package-visibly (e.g. `ColumnCacheStore.cacheRoot()`) for
  the two test sites that currently rebuild the path by hand.

### Blast radius (from exploration — complete list)

- **Tests**: `ColumnCacheStoreTest.java` hardcodes the old path at `:35-40`
  (`getCacheFile` helper) and `:381` (`hostileServerAddressCannotEscapeCacheDir`) —
  switch both to `cacheRoot()`; add unit tests for `resolveCacheRoot` (old-dir-exists →
  old; absent → `.lss`). The other cache-touching tests
  (`ClientColumnProcessorTest.java:795-820`, `LodRequestManagerTest.java:1010`,
  `SpiralScannerTest`, `ColumnStateMapTest`) go through the store's own API and follow
  automatically.
- **Harness scripts** that hardcode `config/lss/cache`: `scripts/soak.sh` (`:349,
  :352, :360-362, :366, :369, :545-548` — including the `cache-platform` marker file),
  `scripts/benchmark.sh:94` (also clears `config/vss/cache` — dead path, may drop),
  `scripts/benchmark_compare.sh:60` AND `:133` (two sites — review), and
  `scripts/profile_disk_read.sh:165`. Clearing must cover BOTH roots, and — review
  point — soak.sh's base-world cache **collection** (`:545-548`) must CHECK both roots
  too, or the first post-relocation fresh-backfill saves to `.lss/cache`, collection
  finds nothing at the old path, and every warm scenario silently goes cold (a premise
  red). Note also that the warm-restore staging (`:360-362`) *creates*
  `config/lss/cache`, which under the adoption rule forces the old root for that run —
  load-bearing and correct, but it deserves a comment in the script.
- **Docs**: `docs/planning/ci-dual-publish.md:88` ("config paths are hardcoded…
  `config/lss/…`") needs a caveat; `soak-test-design.md:183` /
  `timestamp-store-unification-design.md:331` are historical — one-line update or leave.
- The only existing game-root write is `ClientTraceLog` (`logs/`), so `.lss/` is the
  first game-root LSS directory — README should mention it (users ask what new dot-dirs
  are).

---

## Tests

- **`VoxyCompat` reset ladder** (extend `VoxyCompatTest` + stubs under
  `fabric/src/test/java/me/cortex/voxy/`): add `shutdownInstance`/`createInstance`/
  `isAvailable`/`getStorageBasePath` to the stubs (call-recording + throw injection),
  a stub for each holder-interface name. Pins: happy-path ORDER (drain + await the
  `processing` flag → wipe-root read from `getStorageBasePath` BEFORE shutdown →
  shutdownRenderer (with the levelRenderer null-check) → shutdownInstance → wipe →
  createInstance → allChanged); unresolvable holder aborts BEFORE shutdownInstance and
  warns once; null instance skips shutdown+create, still wipes via the FALLBACK
  derivation; `shutdownInstance` throw → wipe SKIPPED, create still attempted, the
  "incomplete" feedback branch; `createInstance` throw contained → the "failed to
  restart" feedback branch; all-or-nothing handle resolution (partial chain = absent);
  the existing ingest bridge and backlog probe unaffected by a dead reset domain; the
  no-manager fallback requires the `confirm` token.
- **Wipe containment**: temp-dir tests — deletes only under the resolved `.voxy`
  server dir; refuses a path that normalizes outside it (mirror
  `hostileServerAddressCannotEscapeCacheDir`).
- **Command orchestration**: keep the command body thin; put the sequence in a
  seam-injected coordinator so JUnit pins the ordering and both fallbacks (no manager;
  Voxy half unavailable). `LSSClientCommands` itself stays uncovered (status quo —
  only `BrandTest` pins literals).
- **`ColumnCacheStoreTest`** updates + `resolveCacheRoot` branch tests, per Part 2.
- **Soak**: untouched — `clearcache-mid-session`, `store-second-join`,
  `store-save-storm`, `paper-store-unfired-event` all script `clearcache`, whose
  semantics don't change; soak clients have no Voxy. The soak.sh cache-clearing edits
  keep staging correct under either root.

## Docs / release notes

- README command list (currently documents `clearcache` at `:45`): add `/lss reset`
  with the one-line "wipes Voxy + LSS LOD state for this server and re-streams
  everything" description + the `.lss/` folder note.
- CLAUDE.md: client-side section — `/lss reset` + the cache-root adoption rule.
- Release notes (New Features + Configuration): the reset command; the new `.lss/`
  location for fresh installs (existing installs keep `config/lss/cache`).

## Risks / accepted constraints

- **Voxy version drift**: the instance-lifecycle statics are stable across 0.2.11 →
  0.2.18 → dev; the renderer holder is not (two-rung + abort-fail-safe covers known
  versions; an unknown future rename degrades to LSS-only reset with a warn, never a
  broken Voxy).
- **`fabric.mod.json` suggests `voxy >=0.2.17-alpha`** — the 0.2.18 rung
  (`voxy$shutdownRenderer`) is the primary; 0.2.11-era rung is best-effort.
- **Wipe-path drift**: the live wipe root comes from `getStorageBasePath()` (no drift
  possible); the duplicated `getBasePath` logic survives only as the instance-null
  fallback, contained to `.voxy` subtrees by construction. **CORRECTED at stage D
  review (2026-08-13, MAJOR, §6.1 pair — see the progress doc):** a Flashback
  replay's override path does NOT fail containment — Voxy records the ORIGIN's real
  store path into the replay meta (`MixinFlashbackRecorder`), so during same-box
  playback `getStorageBasePath()` returns a path that PASSES the directory checks.
  The shipped protection is the derived-root CROSS-CHECK in the ladder: the live
  root must equal this connection's own `getBasePath` derivation (true in every
  non-override session), else the wipe is skipped with the honest
  `RESET_WIPE_SKIPPED` feedback.
- **Ingest-disabled Voxy** (config off / replay): reset leaves LODs empty until the
  user re-enables — LSS's ingest-failure containment reports and re-declares; document
  in the README line.
- Deleting the whole `.voxy/saves/<ip>` dir wipes ALL dimensions for that server
  (matches "fresh client"); singleplayer wipes `<world>/voxy`.

## Verification

1. Tier 1: `./gradlew :fabric:test -x runGameTest -x runClientGameTest` (new
   VoxyCompat/coordinator/cache-root pins; updated ColumnCacheStoreTest).
2. Tier 2 + 3 unchanged behavior: `./gradlew :fabric:build -x runClientGameTest`, then
   `:fabric:runClientGameTest`. NOTE (review): Tier 3 lands on the `.lss` root only in
   a CLEAN run dir (CI) — a dev box's persisted Loom run dir with a pre-relocation
   `config/lss/cache` adopts the old root and never exercises the new branch. The
   `resolveCacheRoot` unit tests are the real gate for the `.lss` branch; a local
   Tier-3 green is not proof of it.
3. One full soak (`./scripts/soak.sh clearcache-mid-session`) to prove the soak.sh
   cache-staging edits under the new root.
4. Live smoke with real Voxy (the lss-multi-test Prism profile has 0.2.18-beta):
   join the local test server (`./test-server.sh run-fabric`), let LODs build, run
   `/lss reset` — LODs visibly vanish, then rebuild from ring 0; check `.voxy/saves/<ip>`
   was recreated fresh and `latest.log` for exactly one warn if any rung failed.
   Repeat once on a server WITHOUT Voxy installed client-side (LSS-only half + clean
   feedback), and once with an existing `config/lss/cache` present (adoption keeps the
   old root).
