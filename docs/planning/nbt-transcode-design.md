# NBT→wire transcode design (Tier 2) — the round-2 CPU target

Provenance: produced by the 2026-07-29 optimization investigation (four read-only agents
over the disk-read profile, `docs/planning/disk-read-profile-2026-07-29.md`). Every vanilla
claim below was verified against the REAL decompiled MC 26.2 sources (paperweight mache
cache — see the handoff doc for how to find the right cache) and the Moonrise-patched
Paper jar, not from memory. Tier 1 of this design (the memoized element codec) LANDED in
commit 1576f73; this file is the still-open Tier 2.

## Why this is the remaining lever

After round 1, the measured residual on the disk serve path is (a) the container-LEVEL
codec plumbing — RecordCodecBuilder field walking, ListCodec element iteration, NbtOps map
traversal, and the boxed LONG_STREAM decode of the `data` long array — which the element
memo cannot reach (measured ~unchanged, 485→446 samples), and (b) the raw NBT tag load
(~15% of server CPU). A direct NBT→wire transcoder eliminates (a) entirely and most of the
remaining per-section object work. Estimate: a further 25-35% of server CPU during
saturated disk serving. ((b) needs a custom region reader — out of scope even for Tier 2.)

## Verified ground truth (26.2)

**The wire format per section** (`LevelChunkSection.write(buf)` — TWO shorts on this line):

```
short nonEmptyBlockCount        // !state.isAir() cells
short fluidCount                // cells with !state.getFluidState().isEmpty()  ← 26.2 addition
-- block states (PalettedContainer$Data.write) --
byte  bits                      // storage.getBits() = Configuration.bitsInMemory()
      palette                   // single: 1 varint global id; linear/hashmap: varint N +
                                //   N varint global ids IN PALETTE-LIST ORDER; global: NOTHING
long[...]                       // storage raw longs, big-endian, NO length prefix
                                //   (count implied by bits: ceil(4096/(64/bits)); 0 when bits=0)
-- biomes: same shape over the biome strategy/registry --
```

**The centerpiece fact** (verified in `PalettedContainer.unpack` + `Data.write` +
`Configuration`): for every `Configuration.Simple` case (block palettes 1-256 entries,
biome palettes 1-8), `bitsInMemory == bitsInStorage` and `alwaysRepack() == false`, so the
codec-unpacked container that the current pipeline serializes holds **the disk palette
list in disk order and the disk long array by reference**, and `write()` emits them
verbatim. The pinned wire bytes are therefore already a pure function of the disk NBT plus
static registry ids — the object round-trip contributes nothing. That is what makes a
byte-identical transcode possible.

**Bit-width thresholds** (`Strategy.createForBlockStates`/`createForBiomes` →
`getConfigurationForPaletteSize` = `ceillog2(paletteSize)`):
- blocks: 0→single (0 bits); 1-4→linear @ 4 bits ALWAYS; 5/6/7/8→hashmap @ that width;
  ≥9→`Configuration.Global(ceillog2(BLOCK_STATE_REGISTRY.size()), ceillog2(paletteSize))`.
- biomes: 0→single; 1/2/3→linear @ exact width; ≥4→global.
- Disk `pack()` uses the SAME `getConfigurationForPaletteSize` — which is WHY the long
  array is verbatim-copyable.

**Traps checked and closed:**
- The pinned target is the *codec-unpacked* emission (disk palette order), NOT
  fresh-container insertion order — the corpus goldens generate their NBT through vanilla
  `pack()`, so they pin exactly the disk-order mapping. Copying disk order verbatim
  matches current behavior on ALL inputs.
- The ONE repack case is `Global` (block palette >256 / biome palette >8): unpack re-reads
  disk longs at `bitsInStorage`, swaps disk-list indices to global registry ids
  (`reencodeContents`), repacks at `bitsInMemory`; the network palette section is then
  empty. Deterministic and replicable — or route those sections through the per-section
  fallback (see ladder below).
- A 1-entry palette is `ZeroBitStorage`: any `data` on disk is IGNORED (bits-0 branch runs
  first); `getRaw()` is empty so zero longs hit the wire.
- Duplicate palette entries (reachable via lenient air substitution) round-trip verbatim
  through both `LinearPalette` and `HashMapPalette` (identity bimap, `add` appends
  unconditionally, `write` iterates `byId(0..size-1)`) — "emit ids in list order" already
  matches; no fallback needed.
- Error semantics to replicate: exact-length long-array validation
  (`SimpleBitStorage.InitializationException`) and missing-`data` for a multi-entry
  palette → whole-column authoritative miss (same throttled warn); per-entry decode
  failure → air substitution IN PLACE (indices never shift) + warn — the leniency lives in
  the container codec's `elementCodec.mapResult(ExtraCodecs.orElsePartial(air))`, and the
  Tier 1 memo already caches per-entry results the transcoder can reuse.
- `BlockState.CODEC` depends only on the bootstrap-frozen block registry — datapacks and
  `/reload` cannot alter it. Biomes are per-`RegistryAccess` (ride the existing weak
  factory memo pattern).
- Light data is ALREADY transcode-shaped on the disk path (raw `byte[]` from NBT, written
  verbatim behind presence booleans; no `DataLayer` is ever constructed).
- The two-short header comes from the round-1 histogram (`countNonEmptyAndFluid`) — the
  memo's value record should carry `globalStateId`/`isAir`/`hasFluid` per entry so the
  transcoder resolves ids without touching `BlockState` objects.
- 26.2's `fluidCount` IS on the wire — a one-short assumption corrupts every section
  (`golden_waterloggedBlockStateProperty` pins it). Older MC lines write only one short —
  do not backport blindly.

**X-ray masking:** `XrayMaskFilter.mask` operates on `LevelChunkSection` INSIDE the
serialize choke point. Phase-1 answer: evaluate `needsMasking` equivalently on transcoded
data — height gate (`sectionY<<4 >= maxBlockHeight`), `nonEmpty > 0` from the histogram,
and a palette pre-gate against `MaskSet` global-state ids (add a `containsId(int)`
accessor) — and route ONLY sections that actually need masking through the object
fallback (memoized codec parse → mask → `section.write` into the same buffer). Preserves
masked bytes by construction; `xray-masked.bin` + `xrayMaskedDiskReadBytesMatchMaskedLiveBytes`
keep gating. Transcoding the mask itself is a later phase (must emulate
`PalettedContainer.set` resize semantics) — only if masked-server profiles justify it.
`AntiXrayCompat.callSerializing` stays wrapped around the whole column; transcoded
sections bypass the AntiXray mixins, which is byte-neutral because LSS binds their context
to null (pass-through) — but do one live smoke on the antixray rig, same as the v0.7.2 gate.

## Staged plan

1. **Stage A (DONE, commit 1576f73):** memoized element codec, both twins.
   Extend next: the memo value record with `globalStateId`/`isAir`/`hasFluid`.
2. **Stage B:** transcoder core — per-section descriptor pass (palette ids via memo,
   histogram, counts, light presence) + emitter. Twins textually identical per house
   style (an optional pure-primitive emitter in `common/` — hand-rolled varint matching
   `VarInt`, big-endian longs — would be unit-testable without MC; acceptable either way).
   Fallback ladder: unrecognized shapes / Global configs / mask-needing sections → the
   Tier 1 object path per section (definitionally today's bytes). ADD new golden cases
   FIRST, generated by the CURRENT path so they pin the transcoder against today's bytes:
   hashmap-width 17+ palette, >256 global fallback, size-2-at-4-bits boundary,
   duplicate-air lenient palette (regen flow: `-Dlss.regenGoldens=true`, commit BOTH
   modules — the cross-module diff enforces).
3. **Stage C:** flip the disk path to the transcoder behind a config flag
   (`useNbtTranscode`, default true — the `useBackgroundReadPriority` rollback-flag
   precedent), keep the object path as the permanent fallback rung. Gate: full Tier 1+2,
   `:paper:test`, `fresh-backfill` + `warm-rejoin` soaks, the profile harness A/B, one
   live antixray smoke.
4. **Stage D (optional):** transcoded masking; Global-config transcode. Only if profiles
   demand.

## Effort and expected win

Stage B+C: ~3-5 days including new goldens and soak runs. Kills the container codec
plumbing and the remaining per-section object work — on the order of a further 25-35% of
total server CPU during saturated disk serving. Reminder: at shipped defaults the serve
rate is bandwidth-capped, so wins land as CPU headroom (multi-player, shared hosts,
higher caps), not single-player col/s.
