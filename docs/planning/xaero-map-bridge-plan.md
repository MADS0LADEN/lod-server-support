# Xaero's World Map bridge — implementation plan (issue #223)

Status: IMPLEMENTED 2026-08-23 on feat/xaero-map-bridge (2-Fable plan review folded
— §10; implementation notes in §11; the 1-Fable + 4-Opus implementation review
folded — §12). Targets main (the staged v0.12.0 release), designed for cheap
porting to all four support lines (§8.4 lists the three per-line API
substitutions).

## 0. Goal

Issue #223: Xaero's World Map only records chunks within vanilla render distance, so
on an LSS server the map is a small dot inside a huge rendered world. LSS receives
full MC-native section data (block states + biomes + light) for every LOD column —
enough to write Xaero map tiles CLIENT-SIDE, with no server change and no protocol
change. The referenced mod (voxyworldgenxaero-bridge) solves this for Voxy worldgen
in single-player only; its technique does not transfer (see §1). We build the
multiplayer equivalent from our own delivery stream.

Non-goals (v1): cave layers (surface only); Minimap-only installs (the minimap
renders World Map tiles when both Xaero mods are present — we target the World Map
only); reading Voxy's store to backfill terrain LSS never re-serves (§8.3);
server awareness of any kind; **single-player** (LSS's client is inert on
integrated servers — voxyworldgenxaero-bridge remains the SP answer; say so in the
README so nobody files "doesn't work in SP").

## 1. Research facts this plan builds on (2026-08-22, two research agents + the review's re-verification)

Artifacts: `scratchpad/xaero-research/Xaero-WorldGen` (MIT-licensed bridge-mod
source), `scratchpad/xaero-research-map/` (Xaero WM 1.45.0 jars for 26.2 + 1.21.1,
CFR decompile output in `cfr-out262/`, XaeroPlus clone). Key findings:

- **The SP bridge's technique is non-transferable.** It exploits Xaero's
  *world-save* mode (integrated server only): fabricated `RegionDetection`s backed
  by stub `.mca` files + mixins substituting synthesized vanilla chunk NBT inside
  `WorldDataReader.readChunk`. In multiplayer Xaero maps from the client chunk
  cache through a different pipeline. Transferable lessons: serve snapshots to
  Xaero threads, never fight real data, gate on completeness, throttle everything.
- **Xaero WM has no public write API and is closed-source (ARR)** — but the jar is
  fully unobfuscated under stable `xaero.map.*` FQNs. XaeroPlus (MIT, actively
  maintained) binds the same internals with `remap=false` mixins; the community
  norm the author tolerates is interop-from-a-separate-mod with no redistribution.
  We go one step softer than XaeroPlus: **reflection only, no mixins** — and the
  review VERIFIED every member this plan needs is `public` (fields
  `writerThreadPauseSync`/`mainStuffSync`/`mainWorld` included; `MapTileChunk`,
  `MapBlock`, `Overlay` ctors included). No `privateLookupIn`, no opens.
- **FQN stability is verified**, not assumed: the 26.2 and 1.21.1 jars (both
  1.45.0, shipped the same day — Xaero updates every MC line in lockstep) have an
  FQN-identical write pipeline, re-confirmed member-by-member for everything in
  §4. Xaero publishes current builds for every line we support: 26.2, 26.1.x,
  1.21.11, 1.21.10, 1.21.1.
- **The MP write pipeline**: packet mixins mark `LevelChunk.xaero_wm_chunkClean =
  false`; each frame the MAIN CLIENT THREAD runs `MapWriter.onRender → writeMap →
  writeChunk` over a window hard-clamped to `min(config, 32,
  effectiveRenderDistance)` around the player, reading chunks from the client
  chunk cache (skipping `EmptyLevelChunk` and chunks whose 8 neighbors aren't
  loaded). Thread enforcement is explicit in Xaero:
  `MapTileChunk.updateBuffers` and `MapProcessor.getLeafMapRegion(create=true)`
  both hard-throw off `Minecraft.isSameThread()`.
- **The native gate ladder** (`MapWriter.onRender:195-230`) — the checks a
  foreign writer must mirror, verbatim: under `renderThreadPauseSync`:
  `!mapProcessor.isWritingPaused() && !isWaitingForWorldUpdate() &&
  mapSaveLoad.isRegionDetectionComplete() && isCurrentMultiworldWritable()`;
  plus `getWorld() != null`, `!isCurrentMapLocked()`,
  `!getMapWorld().isCacheOnlyMode()`, `getCurrentWorldId() != null`,
  `!ignoreWorld(world)`; under `mainStuffSync`: `mainWorld == getWorld()` and
  `getWorld().dimension() == getMapWorld().getCurrentDimensionId()` (a
  `ResourceKey<Level>` equality — THE dimension binding; see §2.7).
- **The commit sequence** (`MapWriter.writeChunk:423-620`), the normative model:
  `synchronized (region.writerThreadPauseSync)` → **`!region.isWritingPaused()`**
  (the writer's half of the save-path exclusion — `MapSaveLoad` saves inside
  `pushWriterPause`) → `synchronized (region)` → `loadState == 2` +
  `registerVisit()` + `isResting()` → `setBeingWritten(true)` → get/create
  `MapTileChunk` (public ctor; `setLoadState((byte) 2)`;
  `region.setAllCachePrepared(false)`) → per-pixel `MapBlock` writes → per tile:
  `setWorldInterpretationVersion(1)` (BEFORE setTile — its
  tileWasLoadedWithTopHeightValues branch reads it) → `setWrittenCave(...)` →
  `tileChunk.setTile(insideX, insideZ, tile, blockStateShortShapeCache,
  mapProcessor)` → `setWrittenOnce(true)`/`setLoaded(true)`; tile-chunk write
  additionally gated on `tileChunk.getLoadState() == 2` and
  `!getLeafTexture().shouldDownloadFromPBO()`.
- **`setBeingWritten(true)` is set and NEVER cleared by the writer.**
  `MapSaveLoad.updateSave` only enqueues saves for regions with
  `isBeingWritten() == true` (and clears the flag itself after saving); the flag
  is also what stops the load drain demoting an empty fresh region. Clearing it
  after a commit = tiles never persist.
- **Region loading is mandatory**: fresh regions start loadState 0 and never
  self-promote; promotion to 2 happens only in `MapSaveLoad.run`'s load drain.
  The native writer's dance (`MapWriter:311-355`): per pass, if
  `canRequestReload_unsynced() && loadState != 2` → `setBeingWritten(true)` then
  `requestLoad(region, reason)`, paced to ~1 request per pass via
  `getNextToLoadByViewing().shouldAllowAnotherRegionToLoad()`. Also
  `getLeafMapRegion` returns **null until `isRegionDetectionComplete()`**.
- **The per-pixel primitive**: `MapBlock.write(BlockState state, int height,
  int topHeight, ResourceKey<Biome> biome, byte light, boolean glowing,
  boolean cave)` after `prepareForWriting(worldBottomY)`. Colors are computed
  later from the stored state/biome at texture time. **Fluids/transparents never
  become the main state** — they go into overlays (`MapBlock.addOverlay`,
  `Overlay(BlockState, byte light, boolean)` public ctor, opacity via
  `increaseOpacity`, interned through `OverlayManager.getOriginal`); the main
  state is the opaque floor. Light byte = BLOCK light at h+1 (sky light is
  cave-mode-only); biome sampled at topHeight; void column =
  `write(AIR, worldBottomY, worldBottomY, biome, 0, false, false)`; slopes may
  stay `slopeUnknown` (self-heal at render).
- **No direct `updateBuffers` needed**: `setChanged(true)` +
  `setToUpdateBuffers(true)` is sufficient — Xaero's `LeafRegionTexture.preUpload`
  sweep performs the GPU work under its own locks/budget. This also shrinks the
  reflective surface (no `BlockTintProvider`/`MapUpdateFastConfig`/
  `BiomeColorCalculator` handles).
- **Persistence**: the MapRunner sweep saves being-written, terrain-bearing
  regions on its own cadence (`hasHadTerrain` propagates from
  `tileChunk.setHasHadTerrain()`), force-flushing on dimension finish. No
  explicit refresh call needed (the save path handles cache/refresh when
  `!isAllCachePrepared`).
- **Boundary self-heal verified both directions**: a newly loaded `LevelChunk`
  defaults `xaero_wm_chunkClean = false` and the native writer rewrites on that
  flag regardless of the existing tile — the native writer reclaims LOD tiles
  when the player arrives. Data model: `MapRegion` (32×32 chunks, `regX =
  chunkX >> 5`) → 8×8 `MapTileChunk` (4×4 chunks) → 4×4 `MapTile` (one chunk)
  → 16×16 `MapBlock`. Surface layer = caveLayer `Integer.MAX_VALUE`.
  `MapProcessor.getCurrentDimension()` returns the literal `"placeholder"`
  string in 1.45.0 — mirror whatever the decompile shows, never invent.
- **XaeroPlus warning**: heavy bytecode transforms inside `writeChunk` caused
  delayed JVM C2 crashes on ARM — reflection-from-outside avoids that class
  entirely.

## 2. Design decisions

1. **Data source = an internal `VoxelColumnConsumer`** registered by the bridge
   (the `VoxyCompat` shape). `LSSApi.dispatchColumn` already hands every decoded
   column — live serves, disk serves, generation serves, dirty re-serves — to
   consumers with the live `ClientLevel`, MC-native `LevelChunkSection`s and
   block/sky `DataLayer`s, on the LSS decode thread. No new tap into the
   pipeline, and the bridge automatically sees exactly what the LOD renderer
   sees (post-XVER-translation, post-masking — x-ray-masked bytes stay masked on
   the map, the correct privacy outcome). The dispatch level is dimension-matched
   to the payload by the drain (ClientColumnProcessor skips stale-dimension
   payloads), so the extractor can take world bounds / hasSkyLight from it.
2. **Reflection-only interop, zero compile-time dep, no mixins.** One xplat class
   `XaeroMapCompat` resolves the full handle set once (lazy, on first init with
   the mod present), in the `MoonriseReadCompat` style: any resolve failure →
   bridge unavailable, one warn naming the drift, LSS unaffected. Feasibility is
   review-verified: every needed member is public (§1). Gate:
   `LoaderServices.get().isModLoaded("xaeroworldmap")` (verify the NeoForge mod
   id at implementation; expected identical). `fabric.mod.json` gains
   `"suggests": {"xaeroworldmap": "*"}` — literal `"*"`, no gradle.properties
   backing (we bind reflectively and fail soft; a version range would be a lie),
   with `FabricModJsonContractTest.suggestsRangesAreTemplatedAndBackedByProperties`
   extended to pin the literal. ARR posture: we ship no Xaero code, no compile
   dep, no bytecode injection — softer than the tolerated community norm.
3. **Write path = direct tile synthesis (the WorldDataReader precedent), commit
   on the MAIN CLIENT THREAD.** We do NOT puppet `MapWriter.writeChunk` (it is
   hard-coupled to the client chunk cache, the player window, private cursor
   state, and the 8-neighbor edge rule) and we do NOT touch Xaero's discovery
   (no fake chunks, no fake packets). The bridge performs §1's commit sequence
   on the thread Xaero enforces (`isSameThread` throws are explicit), under the
   same locks, the same gate ladder, and the same lifecycle flags. Where the
   decompiled `writeChunk` and this plan disagree, **the decompile is
   normative** — mirror it (CFR output is in the research scratchpad;
   re-decompile on Xaero updates). No direct `updateBuffers`:
   `setChanged(true)` + `setToUpdateBuffers(true)` and let Xaero's preUpload
   sweep do GPU work (§1).
4. **Two-stage pipeline, budgeted.** Stage 1 (LSS decode thread, inside the
   consumer callback): extract a compact immutable `PreparedTile` — per pixel:
   floor BlockState, height, topHeight, biome, block-light byte, plus the fluid
   overlay runs (§5) — so no `LevelChunkSection` reference outlives the
   callback. Stage 2 (main client thread, from the existing shared
   `ClientNetGlue.onEndClientTick()` pump — both loaders already call it): drain
   a bounded queue under a per-tick budget — the 2 ms wall is the BINDING
   constraint, the commit-count cap (64) is a safety ceiling (impl review MAJOR:
   the original cap of 8 = 160 tiles/s against 300-1000 delivered columns/s
   made every fast-link backfill drop most of the map and made the clearcache
   heal circular) — plus at most ~1 `requestLoad` per pump pass (the native
   pacing, §2.7). The drain start ROTATES per pump (the IncomingRequestRouter
   M4 precedent): below queue saturation nothing evicts a permanently-deferring
   prefix, and an unrotated head-first walk would starve committable entries.
5. **Bounded latest-wins queue, keyed by packed chunk pos** — bounded by COUNT
   (2048) and BYTES (~24 MB estimated; the ClientColumnProcessor discipline —
   plain tiles are ~4.7 KB but overlay-heavy ocean tiles reach ~90 KB, so a
   count cap alone admits ~165 MB; impl review MAJOR corrected this plan's
   original ≤8 MB arithmetic). A newer tile for the same position replaces in
   place; overflow evicts oldest (counted). Cleared at session end
   (`ClientNetGlue.onDisconnect`) and on config-off; offers are additionally
   gated on a LIVE LSS session, closing the disconnect-drain race that could
   carry one stale tile into the NEXT server's (or a singleplayer world's)
   persistent map. Every pump-side removal is COMPARE-and-remove (entry + tile
   identity) so a commit racing a fresh re-offer can never delete the newer
   tile. Stale-DIMENSION entries are NOT a lifecycle event: the pump drops any
   entry whose dimension differs from the current level's (counted) — no
   dimension-change hook needed. This mirrors the LSS re-declaration
   philosophy: a dropped tile is not a correctness hole — the map is
   best-effort, and the position heals on the next dirty serve or a
   `/lss clearcache` backfill.
6. **Overlap policy: never fight Xaero's native writer.** At COMMIT time (main
   thread — safe and current), skip any position whose chunk is currently
   loaded in the `ClientLevel` (`EmptyLevelChunk` counts as not-loaded, like the
   native writer; counted `skipped_loaded`): the native writer owns loaded
   chunks and rewrites them on its clean-flag anyway (§1 boundary self-heal —
   our tiles get reclaimed when the player arrives, so LOD-over-native
   degradation is self-limiting in both directions). Note: the scanner already
   excludes vanilla-view positions from the want-set, so loaded-chunk columns
   mostly never arrive — `skipped_loaded` is a race belt (movement crescents,
   RD shrink, chunk-loaded-between-serve-and-commit), not a volume path.
   Accepted edge: a chunk loaded but outside Xaero's own write window
   (`min(config, 32, effectiveRD)`) is skipped by both writers — a thin stale
   ring possible when server view distance exceeds Xaero's window; heals when
   the chunk enters the window or is re-served.
7. **Gate ladder = the native writer's, verbatim; deferral not deletion.** Every
   pump pass re-checks, in the native order and under the native monitors
   (§1's ladder): session present + usable, `!isWritingPaused()`,
   `!isWaitingForWorldUpdate()`, `isRegionDetectionComplete()`,
   `isCurrentMultiworldWritable()`, world non-null + not ignored + map not
   locked + not cache-only, and under `mainStuffSync` the `mainWorld` identity
   + the `ResourceKey<Level>` dimension equality. Mirroring that equality is
   the whole anti-wrong-dimension binding: writes are structurally impossible
   into a dimension the processor isn't currently writing — at the accepted
   cost that (exactly like Xaero's own writer) commits PAUSE while the user
   browses another dimension's map in the GUI; the queue holds meanwhile.
   Ladder-not-ready states DEFER (entries stay queued; the bounded queue is the
   TTL). Per region, mirror the write gates (`!region.isWritingPaused()` under
   `writerThreadPauseSync` — the save-race exclusion — then `loadState == 2` +
   `registerVisit()` + `isResting()` under the region monitor) and the
   MANDATORY load dance for regions not at loadState 2:
   `canRequestReload_unsynced()` → `setBeingWritten(true)` →
   `requestLoad(region, "lss-xaero-bridge")`, paced ~1/pass via
   `getNextToLoadByViewing().shouldAllowAnotherRegionToLoad()` — fresh regions
   NEVER self-promote, so without this the feature silently no-ops everywhere
   Xaero hasn't already been. Entries for a region awaiting load defer without
   burning deferral budget; a per-entry deferral cap (~200 ladder-ready passes)
   then drops (counted). `setBeingWritten(true)` is set and never cleared by us
   (§1 — the save path owns the reset; clearing it would silently lose every
   tile not later touched by the native writer).
8. **Failure containment — the map must never cost LOD correctness.** The
   consumer callback catches ALL its own throwables (nothing escapes to
   `dispatchColumn` — an escape would trigger `reportIngestFailure` and put the
   column into the re-serve loop for a map problem). The bridge NEVER calls
   `reportIngestFailure` and does not override `pendingIngestBacklog` (the
   backpressure gauge is for LOD renderers; the map drops instead of pacing the
   stream). Commit-time throws: LogThrottle'd warn + drop the tile; a
   consecutive-failure latch (5 in a row) kills the bridge for the session
   (`state=dead` in diag), FarPlayerRenderer-crash-latch style.
9. **Config + Sodium toggle.** New client config key `enableXaeroMapBridge`
   (default true) checked LIVE at both enqueue and pump, so the Sodium toggle
   applies mid-session (flip off → queue cleared). Sodium option
   `lss:xaero_map_bridge` on the LSS page (boolean, `join_slow_start` pattern,
   enabled-dep on `lss:receive_server_lods`), lang keys
   `lss.config.xaero_map_bridge{,.tooltip,.tooltip.not_installed}` — the
   not-installed tooltip selected at menu build when `xaeroworldmap` is absent
   (the join_slow_start governor-off / SeeU conditional-tooltip precedent).
   Strings stay brand-neutral (Brand discipline; the VSS jar shares them).
10. **The cached-server gap is documented, not engineered around (v1).** On a
    server where the client already holds stamps, converged columns answer
    `up_to_date` — no data flows, the map stays empty until terrain changes.
    The existing `/lss clearcache` (run WHILE CONNECTED — the no-session form
    clears all servers' caches; forget stamps → full re-serve → bridge
    repopulates; Voxy dedupes its own re-ingest) is the documented one-shot
    backfill. Release notes + README say so. A future option is reading Voxy's
    store directly (the SP bridge proves the read surface: `WorldEngine.
    acquireIfExists`/`WorldSection.copyData`/`Mapper` — the exact inverse of our
    ingest bridge) — recorded as v-next, out of scope.
11. **Porting is cheap by construction.** Everything lives in xplat (`compat/` +
    the pure extractor) + one `ModCompat.init` branch + one line in the shared
    tick glue — both loaders get it with zero loader-specific code (NeoForge's
    bootstrap already calls `ModCompat.init()` and
    `ClientNetGlue.onEndClientTick()`). Reflection makes Xaero's MC-typed
    signatures mapping-proof: our class literals remap per line at build, Xaero
    targets the same runtime names, and Xaero's own FQNs are identical on all
    five lines (§1). The Sodium menu addition is the only per-line divergence
    (the 1.21.10 line has no Sodium page — config-file key still works there;
    the port drops the menu hunk, exactly like the rest of its Sodium cut).

## 3. Components

New files (X = new, M = modified):

- X `xplat/…/compat/XaeroMapCompat.java` — resolve-once handle set (§4),
  consumer registration, the bounded latest-wins queue, the main-thread pump
  (`pumpFromClientTick()`) implementing §2.7's ladder + §2.6's skip + the
  budget, counters, throw latch, `clearQueue()`, diag snapshot accessor. Test
  seams: `ClassResolver` + registrar + a sink/clock seam so Tier 1 drives the
  pump against stubs (the `VoxyCompat`/`MoonriseReadCompat` seam discipline;
  instance-scoped resolution for order-independent tests, since
  `getCurrentSession()` is static state on the Xaero side).
- X `xplat/…/compat/XaeroTileExtractor.java` — PURE section→pixel computation,
  zero Xaero types (§5). Input: `VoxelColumnData` + world bounds/hasSkyLight
  from the dispatch level; output `PreparedTile` (record). Fully unit-testable.
- M `xplat/…/compat/ModCompat.java` — `xaeroworldmap` branch in `init()`,
  `clientTick()` forwarder, `xaeroDiagLine()` accessor.
- M `xplat/…/networking/client/ClientNetGlue.java` — `ModCompat.clientTick()`
  call in `onEndClientTick()`; queue clear in `onDisconnect` (beside
  `FarPlayerClientSupport.onSessionEnd()`).
- M `xplat/…/networking/client/ClientCommandActions.java` — the conditional
  `XaeroMap:` diag line (the `Summary:` conditional-line precedent, ~line 171).
- M `xplat/…/config/LSSClientConfig.java` — `enableXaeroMapBridge` (default
  true) + javadoc.
- M `fabric/…/config/LSSConfigMenu.java` — the toggle (§2.9).
- M `fabric/src/main/resources/assets/lss/lang/en_us.json` — 3 keys.
- M `fabric/src/main/resources/fabric.mod.json` — suggests `xaeroworldmap: "*"`.
- M `fabric/src/test/java/…/FabricModJsonContractTest.java` — extend the
  suggests pin for the new literal-`"*"` entry.
- M docs: README compatibility note (incl. the clearcache backfill + SP
  non-goal), this plan, release-notes bullet (main's v0.12.0 notes file; line
  notes at port time).

Tier 1 tests (fabric module, `fabric-loader-junit`; `LevelChunkSection`
construction under it is proven — SectionConstructionPinTest et al.):
- X `XaeroTileExtractorTest` — hand-built sections → expected pixel arrays:
  plains surface; water column (floor state + h/topH split + one overlay run
  with summed opacity); deep-ocean multi-run; void/all-air; a RESYNC all-air
  column (must produce the void pixel that erases stale map terrain); End (no
  sky light); bottom-of-world; missing mid-column sections (scan across as
  air); single non-air section (the superflat shape); light = block light at
  h+1, biome at topH.
- X `XaeroMapCompatTest` — against real-package-name stubs under
  `fabric/src/test/java/xaero/map/…` (the Moonrise stub pattern; Xaero classes
  are not remapped, so stub FQNs are literal): resolve happy path registers the
  consumer; each missing/wrong-shape member → unavailable + no consumer + one
  warn; the pump gate ladder (each not-ready gate defers; dimension-mismatch
  entry drops; loaded-chunk skip; budget stops; unloaded region issues EXACTLY
  ONE paced requestLoad with setBeingWritten(true) first and defers without
  burning deferral budget; ladder-ready deferral cap drops); commit-sequence
  order against a recording stub (worldInterpretationVersion before setTile;
  setBeingWritten never cleared; setChanged + setToUpdateBuffers, no
  updateBuffers call); latest-wins + bounds; config-off clears; throw latch
  kills after 5; a throwing commit NEVER escapes the consumer callback and
  NEVER reports ingest failure (pin: the report seam records zero calls).

## 4. The Xaero reflective surface (all verified public, 26.2 ≡ 1.21.1)

`WorldMapSession.getCurrentSession()`, `isUsable()`, `getMapProcessor()`.
`MapProcessor`: `isWritingPaused()`, `isWaitingForWorldUpdate()`,
`isCurrentMapLocked()`, `isCacheOnlyMode()`, `ignoreWorld(...)`, `getWorld()`,
`getCurrentWorldId()`, `isCurrentMultiworldWritable()`, `getMapWorld()`,
`getMapSaveLoad()`, `getLeafMapRegion(int, int, int, boolean)`,
`getTilePool()` (+ `MapTilePool.get(String, int, int)`),
`getOverlayManager()`, `getBlockStateShortShapeCache()`, fields
`mainStuffSync`, `mainWorld`, `renderThreadPauseSync` (whichever of these the
decompiled ladder actually reads — final list from the decompile).
`MapWorld.getCurrentDimensionId()` (+ `isCacheOnlyMode` if it lives here).
`MapSaveLoad`: `requestLoad(MapRegion, String)`, `isRegionDetectionComplete()`,
`getNextToLoadByViewing()` (+ its `shouldAllowAnotherRegionToLoad()`).
`MapRegion`: field `writerThreadPauseSync`, `isWritingPaused()`,
`getLoadState()`, `isResting()`, `registerVisit()`, `setBeingWritten(boolean)`,
`canRequestReload_unsynced()`, `setAllCachePrepared(boolean)`, `getChunk(...)`.
`MapTileChunk`: ctor, `getTile`/`setTile(int, int, MapTile,
BlockStateShortShapeCache, MapProcessor)`, `setLoadState(byte)`,
`setChanged(boolean)`, `setToUpdateBuffers(boolean)`, `setHasHadTerrain()`,
`getLoadState()`, `getLeafTexture()` (+ `shouldDownloadFromPBO()`).
`MapTile`: ctor/pool source, `setBlock`, `getBlock`,
`setWorldInterpretationVersion(int)`, `setWrittenCave(int, int)`,
`setWrittenOnce`, `setLoaded`. `MapBlock`: ctor, `prepareForWriting(int)`,
`write(BlockState, int, int, ResourceKey, byte, boolean, boolean)`,
`addOverlay(Overlay)`. `Overlay`: ctor `(BlockState, byte, boolean)`,
`increaseOpacity(int)`. `OverlayManager.getOriginal(Overlay)`.

Exact arities/owners come from the decompiled sequence at implementation time
(§2.3's decompile-is-normative rule); resolve all-or-nothing. Members ADDED at
implementation because the decompiled sequence uses them (impl review: each is
a single point of bridge-death on an Xaero update, so the list must be
complete for the next re-verification — all five verified present with
identical signatures in the 1.21.1 jar): `MapProcessor.
getMapRegionHighlightsPreparer()` + `MapRegionHighlightsPreparer.prepare(
MapRegion,int,int,boolean)` (the createdTileChunk block), `MapProcessor.
getCaveModeDepthConfig()` (setWrittenCave's live depth — mirroring the config
avoids a spurious native rewrite delta), `MapSaveLoad.setNextToLoadByViewing(
LeveledRegion)` (the pacing registration half), `MapTileChunk.includeInSave()`,
`MapRegion.setChunk(int,int,MapTileChunk)`. Deliberately ABSENT:
`updateBuffers`, `addToRefresh`, `BlockTintProvider`, `MapUpdateFastConfig`,
`BiomeColorCalculator` (§1 — Xaero's own sweeps handle textures/refresh/save
once the flags are set).

## 5. Pixel recipe (v1 — mirrors the decompiled `loadPixel`)

Per column (16×16), scanning each (x,z) from the top present section downward
(missing sections scan as air):
- **topHeight** = Y of the first non-air block (fluids/transparents count).
- **Overlays + floor**: fluid/transparent runs above the floor become
  `Overlay(runState, light, false)` entries per contiguous run with
  `increaseOpacity(lightDampening × runLength)`, interned via
  `OverlayManager.getOriginal` — REQUIRED in v1 (fluids never become the main
  state; without overlays every ocean renders as dry floor). `state` = the
  first opaque block (the floor), `height` = its Y.
- **biome** = sampled at (x, topHeight, z), as the `MapBlock.write` signature
  wants it; overlay biome = the surface biome.
- **light** = the BLOCK-light nibble at (x, height+1, z) (sky light is
  cave-mode-only in Xaero's writer).
- **void column** = `write(AIR, worldBottomY, worldBottomY, biome-or-null, 0,
  false, false)` — this is also what ERASES stale map terrain when a resync
  serves an all-air column (ghost-terrain clears arrive air-filled).
- **glowing/cave** = false/false; `setWrittenCave(Integer.MAX_VALUE, 0)`
  (surface layer values per decompile); leave slopes `slopeUnknown` (Xaero
  self-heals at render).
Known accepted v1 gaps after the folds: sub-fluid detail fidelity vs Xaero's
exact transparency accumulation (match the decompile where cheap; eyeball in
the manual test), no slope polish.

## 6. Observability

Counters (`written`, `skipped_loaded`, `defer_events` — defer EVENTS, not
entries — `dropped` = overflow+stale+expired aggregate, `commit_failures` —
split out because it is the only pre-death failure signal, unlike the benign
drop flavors — `load_requests`, `queued` gauge) + `state` (active / unavailable
/ dead / disabled), surfaced ONLY in the client `/lss diag` conditional line
(comma-separated, the house style) — no exporter/soak schema churn. The
`unavailable` state is the RESOLVE-FAILED case (Xaero present, internals
unrecognized) and renders even though no bridge instance exists — without it a
drifted Xaero is indistinguishable from "not installed", hiding §7.1's top
risk. Harness inertness is by ABSENCE (no Xaero jar in soak/benchmark/gametest
runtimes, and no schema fields added), no property gate needed; the
lss-multi-test Prism profiles are real clients where bridge activity is correct
behavior.

## 7. Risks / accepted

1. **Internal-surface drift** (Xaero refactors internals): resolve-time shape
   validation catches renames; semantic drift lands in the throw latch →
   bridge-dead session, LODs unaffected. We deliberately do NOT hard-pin a WM
   version range (XaeroPlus does; we fail soft instead). Worst realistic case =
   map bridge silently off until we re-align — same class as Moonrise drift.
2. **Lock discipline**: we take Xaero's own monitors on the same thread in the
   native order — no new deadlock topology; the save-path race is excluded by
   the same `isWritingPaused`/`writerThreadPauseSync` discipline the native
   writer uses. Staying inside the native lifecycle also means the SP bridge's
   "cache not prepared" crash workaround is NOT needed (we `setAllCachePrepared
   (false)` at tile-chunk creation and let preUpload manage cache prep).
3. **Persistent-map writes are semi-irreversible** (Xaero saves our tiles to its
   region zips). Mitigations: the toggle, the loaded-chunk skip, the native
   writer reclaiming loaded chunks, and Xaero's own map UI can reset regions.
   Named in release notes.
4. **Login-order race**: `getCurrentSession()` null until Xaero's login hook →
   deferral (§2.7) absorbs it; multiworld confirmation likewise.
5. **1.21.x mapping namespaces**: none for reflection (§2.11); the extractor
   uses our own per-line MC types.
6. **`END_CLIENT_TICK` phase vs Xaero's frame phase**: both are the main client
   thread and the GPU work stays in Xaero's own sweep (§2.3), so no frame-phase
   assumption remains on our side. If implementation still finds a
   preUpload-order artifact, the fallback is already the design (flags only).

## 8. Follow-ups (recorded, out of scope)

1. Overlay fidelity beyond §5 (exact transparency accumulation) if the manual
   test finds visible seams at the native/LOD boundary.
2. Cave layers.
3. Voxy-store backfill for converged servers (§2.10).
4. Support-line ports (after the user's manual test, with the rest of the
   staged v0.12.0+ work). NOT verbatim (impl review corrected §2.11's claim) —
   three mechanical per-line API substitutions in the new xplat code:
   `level.getMinY()/getMaxY()` → `getMinBuildHeight()/getMaxBuildHeight()` on
   1.21.1 only, and `state.getLightDampening()` → `getLightBlock(BlockGetter,
   BlockPos)` on ALL THREE 1.21.x lines (verified against the per-line
   mappings); plus the menu hunk's `Identifier.parse` → `ResourceLocation.parse`
   on 1.21.1 and the whole menu hunk dropped on 1.21.10 (its Sodium cut).
   Verify the per-line Xaero 1.45.0 jar in the test instance.

## 9. Execution order

Branch `feat/xaero-map-bridge` off main. (1) extractor + tests; (2) compat
class + stub tests against the decompiled sequence; (3) config/menu/lang/diag/
suggests + contract-test extension; (4) docs + release-notes bullet; (5) full
local gates (`:fabric:build -x runClientGameTest`, `:fabric:runGameTest`,
`:paper:test`, `:neoforge:build` — server modules untouched but the contract
suites must stay green); (6) PR → 1-Fable + 4-Opus implementation review →
fold → merge; (7) Prism test instance: clone of the lss-test-26.2 profile +
Xaero's World Map 1.45.0 (+ Minimap for the combined look) + the branch jar,
pointed at the Modrinth rig; hand to the user with a short test script (join →
map fills far beyond RD; `/lss clearcache` backfill; browse another dimension's
map → commits pause, resume on return; toggle off stops writes; no log spam).

## 10. Review record (2026-08-22, 2-Fable)

Reviewer A (evidence lens, decompile re-verification) — 4 MAJORs, all folded:
setBeingWritten set-never-clear (→ §1/§2.7); the mandatory paced requestLoad
dance incl. setBeingWritten-before-request (→ §2.7); water overlays required in
v1 (→ §5); the complete native gate ladder incl. `region.isWritingPaused` under
`writerThreadPauseSync`, `isRegionDetectionComplete`, `mainStuffSync` world +
dimension equality (→ §1/§2.7). MINORs folded: no direct updateBuffers
(setToUpdateBuffers instead), "main client thread" naming, pixel-recipe
corrections (biome at topH, block-light-only, void shape, setWrittenCave,
version-before-setTile, slopeUnknown), handle-set corrections, the
both-writers-skip window edge, tile-chunk creation details (setLoadState 2,
setAllCachePrepared false, registerVisit, PBO gate). Verified: all needed
members public; FQNs identical 26.2 ↔ 1.21.1; boundary self-heal both
directions; no cache-prepared crash workaround needed.

Reviewer B (project lens) — 2 MAJORs, both folded: dimension write-context
pinning (resolved by mirroring the native mainStuffSync equality — wrong-
dimension writes structurally impossible, browsing pauses commits; → §2.7);
region loading load-bearing (same fold as A-MAJOR-2). MINORs folded: pump-side
stale-dimension drop replaces the dim-change clear hook; suggests literal-`"*"`
decision + contract-test extension; not-installed conditional tooltip; named
diag file (ClientCommandActions); END_CLIENT_TICK-phase fallback named.
Confirmed: scanner already excludes vanilla-view positions (skipped_loaded is a
race belt); resync all-air erases correctly; /lss reset is synergy; harness
inertness airtight; Tier 1 stub + section-construction feasibility proven; VSS
brand-neutrality; SP non-goal stated (§0).

## 11. As-built notes (2026-08-23)

- Files landed exactly per §3, plus: the consumer is (de)registered LIVE by a
  per-pump `reconcileRegistration()` — `LSSApi.hasVoxelConsumers()` drives the
  handshake's CAPABILITY_VOXEL_COLUMNS bit, so an Xaero-only install (no Voxy)
  legitimately subscribes to LOD data (that IS the feature), while a disabled or
  dead bridge releases the bit for the next join instead of downloading the disc
  for nothing.
- Handle resolution: exact-typed `findVirtual`/`findGetter` against the resolved
  Xaero classes for everything except the three ClientLevel-typed members
  (`getWorld`, `mainWorld`, `ignoreWorld`), which resolve by name+arity scan and
  are used as Objects behind the `LevelOps` seam — tests cannot construct a
  ClientLevel, and the stubs declare them as Object.
- §5 recipe refinements from the decompile: the deep-run extension charges the
  RUN state's light dampening (not the current block's); water is detected by
  fluid TYPE (`Fluids.WATER/FLOWING_WATER`), and the flower-tag invisibility
  term is contained against unbound tags — tag lookups throw both under
  fabric-loader-junit and (defensively) mid-reload.
- The pump takes no failure-count reset on a clean ladder pass — only a
  successful COMMIT resets the death latch (commit failures are contained per
  entry, so "the ladder returned" proves nothing; caught by the latch test).
- The per-pump nanos budget is an instance field so tests can neutralize
  MethodHandle warmup; the commit cap is asserted exactly.
- Tier 1: `XaeroTileExtractorTest` (11 tests) + `XaeroMapCompatTest` (16 tests)
  against real-package-name stubs under `fabric/src/test/java/xaero/map/` with a
  shared ordered event sink (`XaeroStubEvents`) pinning the cross-object commit
  sequence; `FabricModJsonContractTest` pins the literal-`"*"` suggests entry.

## 12. Implementation review record (2026-08-23, 1-Fable + 4-Opus over PR #229)

Reviewer 1 (Fable, decompile fidelity): NO MAJORs — all ~65 handle descriptors,
both hot paths, the requestLoad lock-order vs the MapRunner drain, the
flag-then-consume texture path, and the extractor recipe verified faithful
member-by-member against the shipped 1.45.0 jar. Folded MINORs: the skipped
`isNormalMapData` cross-layer branch recorded as an accepted gap (below);
requestLoad's secret main-thread-only-ness commented at the call site. NOTEs
recorded: post-cap overlay charging diverges in >10-layer stacks; run-merge is
per-state (native also merges same-particle-material states); nether portal
floors instead of overlaying (the translucency approximation); every commit
re-textures (no per-pixel equality short-circuit — bounded by the budget).

Reviewer 2 (Opus, LSS integration): MAJOR-1 — mid-session deregistration of the
sole consumer put every arriving column through the no-consumer ingest-failure
path (up to 4 re-serves per position, whole-disc churn); FIXED with the
registration lifecycle in §2 (add-only while live, no-op consumer when
disabled/dead, deregistration + latch re-arm only at session end). MAJOR-2 —
the 8-commit cap arithmetic (160 tiles/s vs 300-1000 columns/s); FIXED (§2.4:
64-cap safety ceiling, 2 ms binding; plus the full-queue extraction pre-skip).
MINORs folded: Errors swallowed in the consumer (dispatchColumn converts ANY
escape into a re-serve); the session-active offer gate (the cross-server /
singleplayer one-tile leak); compare-and-remove (the lost-fresher-tile race);
the byte gauge; the ConfigValidationTest default pin; the honest disabled init
log.

Reviewer 3 (Opus, concurrency/failure): MAJOR-1 — a REAL main-thread deadlock:
`shouldAllowAnotherRegionToLoad` (which synchronizes on its own, possibly
BRANCH, region) was called inside the leaf-region monitor while Xaero's loader
thread nests parent-then-leaf and the map GUI parks branch regions in
`nextToLoadByViewing`; FIXED by hoisting the gauge consult to once-per-pump
before any region monitor (the native shape) — pinned by an event-order test.
MAJOR-2 = the byte-gauge finding (fixed above; §2.5 arithmetic corrected).
MAJOR-3 — the death latch was process-permanent with no re-arm; FIXED:
session-scoped (re-armed at session end; genuine drift re-latches next session
within 5 commits). MINORs folded: extraction failures now feed their own
latch; the bugged-state memo (one exception per bugged state, not 256/column);
the one-shot extension guard restored (native parity — pinned by the
charge-arithmetic test); drain rotation (head-of-line starvation);
synthetic/bridge methods skipped in the name-scan. NOTEs verified clean:
memory-visibility audit, queueLock leaf-ness, extractor thread-confinement,
hostile-input handling; `registerVisit`'s wider footprint and the
reconfiguration-gap queue survival recorded as accepted.

Reviewer 4 (Opus, test rigor): stub-vs-jar descriptor audit CLEAN (zero
mismatches). MAJORs all folded: the stub `prepareForWriting` made faithful
(clears overlays — the per-pixel order pin is now real); the region save-race
gate test; holdsLock monitor-discipline checks inside the stubs; the two
latch-semantics tests (across-pumps + success-resets); the colorless rung now
actually reached (TRIPWIRE — visible but MapColor.NONE — with BARRIER kept as
the render-shape case); biome/glowing pinned end-to-end (extractor
biome-at-topH with a differing upper-section biome + commit pass-through).
MINORs folded: the five dead-knob branch tests, the surface-layer event
assert, the zero-nanos budget test, setChanged-before-setTile + ctor-args
order pins, distinct-tile latest-wins + oldest-eviction, cross-dimension
replacement, the facade/diag tests, the wiring contract test
(XaeroWiringContractTest — the SaveHookContractTest family), @AfterEach stub
hygiene, and the ranked extractor cases (waterlogged, ice, multi-run,
water-to-void, run light, boundary light, extension arithmetic).

Reviewer 5 (Opus, product surface): MAJORs folded: the release-notes
"never overwrites" claim corrected (only currently-loaded chunks are
protected; Xaero reclaims on revisit); the `unavailable` diag state made REAL
(a resolve-failed latch renders it — it was unreachable dead code); the five
added reflective members recorded in §4. MINORs folded: §8.4's port-cost
correction (three API substitutions, not verbatim); the Configuration bullet
for `enableXaeroMapBridge`; counter semantics split (`commit_failures` out of
`dropped`, `defer_events` naming); comma-separated diag (house style) + a
describe() test; README permanence/masking/re-download-cost/works-without-Voxy
wording; the NeoForge mod id VERIFIED (`xaeroworldmap` in the NeoForge jar's
neoforge.mods.toml — §2.2's open item closed). Notes accepted: no map-only
backfill verb (v-next, §8.3); single-player silence (README states MP-only);
the load reason string "lss-xaero-bridge" may surface in Xaero's own logs.

Accepted gaps (recorded, deliberate): the native `!isNormalMapData()`
cross-layer outdating branch is skipped (converted-legacy-map regions can show
stale CAVE layers where LOD tiles landed until a native rewrite); post-cap
overlay charging and >10-run stacks diverge from native; `registerVisit` fires
per deferred pass (wider visit footprint than the native player-window
writer); a server-initiated play→config reconfiguration skips the disconnect
teardown (the session-active offer gate + stale-dimension drops contain it);
`AWAITING_LOAD` entries have no expiry short of queue eviction (transient in
practice; the rotation keeps them from starving the drain).
