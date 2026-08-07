# Cross-MC-version LOD serving — protocol 20 identity encoding (implementation plan)

*2026-08-06. Trigger: issue #85 — players on 1.21.11 / 26.1.2 clients joining a 26.2
server through ViaVersion/ViaBackwards get LOD data they cannot decode (Via translates
vanilla packets; `lss:*` custom payloads pass through byte-for-byte). Worse, the
mismatch is invisible today: the old-line clients speak LSS protocol 18/16, the
server's compat rungs accept them as native same-MC sessions, and the client then
decodes 26.2 registry ids against its own registry — silent garbage terrain, or a
decode throw that loops forever through the ingest-failure re-serve path.*

**Goal:** a version-neutral column encoding (protocol 20) so any v20 client works
against any v20 server regardless of Minecraft version, plus (a) server-side
translation to the native v19/v18/v16 formats for older LSS clients on the *same* MC
version, (b) client-side translation so a v20 client works against older LSS servers
on the same MC version, (c) an in-place store migration, and (d) a mismatch guard so
legacy clients on a different MC version fail loud instead of half-working.

**Non-goals:** serving *legacy-protocol* clients across MC versions (impossible — their
formats are native-id by definition; the guard handles them); per-version numeric-id
mapping tables à la ViaVersion (we side-step the whole chained-mapping apparatus by
keying on identity); the 1.21.8 and 1.20.1 lines (OUT OF SCOPE — user decision
2026-08-06: the supported MC set for cross-version serving is exactly 26.2, 26.1,
1.21.11).

---

## 1. Why the current bytes are version-locked (verified facts)

The column payload's `sectionBytes` (live path `SectionSerializer.java:99-136`, Paper
twin identical) are:

```
VarInt sectionCount
repeat:
  byte  sectionY
  short nonEmptyBlockCount; short fluidCount        // 26.2 shape
  <blocks PalettedContainer>  <biomes PalettedContainer>
  bool hasBlockLight [+2048 B]; bool hasSkyLight [+2048 B]
```

Each `PalettedContainer` = `byte bitsPerEntry; palette; packed long[]` (no length
prefix; count implied). The **palette entries are global registry ids** — and in
DIRECT mode (blocks >256 palette entries, biomes >8) the palette list is *absent* and
the packed long array holds global ids directly, at a bit width derived from the
**registry size** (`ceillog2`). Ids and registry sizes both shift every MC version
(and with mods/datapacks) — three distinct version couplings in one layout.

Everything else in the frame — packed index arrays, light nibbles, section Y, the two
shorts — is already version-neutral.

## 2. Wire format (protocol 20)

Design rule (Via lesson): **rewrite palettes, never voxels.** Only the palette layer
changes; packed arrays and light ship verbatim.

### 2.1 Column layout

Identical to v19 (coords, dimension, columnTimestamp, source byte, codec byte,
zstd-1 framing, `MAX_SECTIONS_SIZE` guard) except inside the section bytes:

```
VarInt dictCount
repeat dictCount: VarInt len + UTF-8 canonical identity string   // shared block+biome dictionary
VarInt sectionCount
repeat:
  byte  sectionY
  short nonEmptyBlockCount; short fluidCount
  <blocks: byte bits; palette of VarInt DICT INDICES; packed long[]>
  <biomes: byte bits; palette of VarInt DICT INDICES; packed long[]>
  light as today
```

- **Dictionary**: first-seen order (deterministic given section order — pinned by
  goldens), one entry per distinct identity in the column. Measured on real 26.2
  terrain: ~38 entries/col. Blocks and biomes share one table; context (which palette
  references it) disambiguates.
- **Palette entries are dictionary indices**, VarInt (1 B in practice). `bits==0`
  single-value = one index. The packed long arrays keep their existing
  palette-relative semantics — shipped verbatim from the native form.
- **DIRECT-mode sections are re-palettized** (the one repack case, exactly as
  ViaVersion's `PaletteType1_18.readValues` synthesizes a palette from a global-mode
  section): enumerate distinct ids in the longs, build an indexed palette, repack.
  v20 has **no DIRECT mode** — removing the registry-size-dependent bit width from
  the wire. **v20 palette widths are explicitly specified** (review MAJOR — a bare
  `ceillog2(n)` emits width/shape combinations no vanilla strategy table accepts,
  e.g. 1–3-bit block palettes from sparse sections stuck in global mode, since
  vanilla never shrinks out of DIRECT): blocks `bits = clamp(ceillog2(n), 4, 12)`
  (n ≤ 16 → 4 = vanilla-linear-shaped; 17–256 → 5–8 = vanilla-hashmap-shaped;
  257+ → 9–12 = v20-custom), biomes `ceillog2(n)` in 0–6 (0–3 vanilla-shaped,
  4–6 v20-custom). Natively-indexed sections ship their packed longs verbatim
  (their widths are already vanilla-shaped); only re-palettized sections repack.
  The v20-custom widths are decodable only via the §2.3 translation — never fed to
  `section.read` directly. Worst case (a pathological 4096-state section, ~143 KB
  dictionary raw) is bounded by `MAX_SECTIONS_SIZE` (2 MiB) — which is a
  **column-level** cap enforced at client read (`LSSConstants.java:60`); the v20
  emitter adds its own server-side size check rather than relying on the client's
  read cap to reject an oversized own-encode.
- **The v20 emitter replaces `section.write` wholesale** (it cannot call it — the
  container write is where the ids are embedded) and recomputes both count shorts
  itself, via the transcoder's existing histogram approach
  (`NbtSectionSerializer.java:476-489,670`).
- **A clear column (0 sections) always emits an EMPTY dictionary** — pinned:
  `isClearColumn` (`ClientColumnProcessor.java:424-434`) reads the leading VarInt,
  which v20 turns into `dictCount`, so ghost-clear semantics survive only via
  dictCount==0 ⇔ sectionCount==0. No dictionary content may ever leak into a clear
  frame.
- **The two count shorts are pinned as version-neutral wire fields** (the 26.2
  shape). `nonEmptyBlockCount`/`fluidCount` are what 26.2's `LevelChunkSection.write`
  emits, but older MC versions' section shapes differ (1.21.11's count set diverges —
  see `nbt-transcode-design.md:22-34` and the backport-gotchas notes). v20 always
  carries both shorts; a client whose section object wants a different count set
  recomputes locally from the decoded container (cheap — the recalc exists for the
  mask path already). Without this pin the framing itself would be version-coupled.
- **Canonical identity form**: `namespace:path[k1=v1,k2=v2]` — ALL properties, sorted
  by property name, no spaces; bare `namespace:path` when the state has none. Biomes
  are bare identifiers. MC's identifier charset (`[a-z0-9_./-]`) and property
  names/values (alphanumeric) cannot contain `[ ] , =`, so no escaping is needed —
  pinned by a validation test, not assumed. Note `BlockState.toString()` (used by
  `RegistryFingerprint`, `RequestProcessingService.java:970-983`) is NOT this form —
  the fingerprint keeps its stringifier untouched (it is persisted in store meta);
  the wire gets a new strict `IdentityCodec` in `common/`.

### 2.2 Handshake / session config

- **The C2S handshake byte shape is FROZEN at `(VarInt version, VarInt caps)` — for
  v20 and forever** (review CRITICAL, found independently by two lenses): every
  legacy Fabric server's registered codec reads exactly two VarInts on
  `lss:handshake_c2s`, and trailing bytes are a decoder kick — the repo documents
  this exact hazard at `SessionConfigS2CPayload.java:97-101`. Legacy Paper ignores
  trailing bytes (`PaperPayloadHandler.decodeHandshake:236-246`), which would have
  made the failure Fabric-only and nastier to diagnose. An appended field would
  hard-kick the v20 client from every shipped Fabric server, collapsing the §6
  ladder at stage 1. C2S-shape stability joins the design rules; a Tier-1 pin
  decodes the v20 announce under the v19 codec.
- The client's MC data version therefore ships on a **new channel**
  (`lss:client_info`, C2S, sent alongside the announce): legacy servers silently
  discard unregistered channels; the v20 server treats absence as "legacy client"
  and presence as diagnostics + guard input. The v20 server's handshake decode
  branches version-first on the existing channel and still parses 2-field legacy
  announces.
- `SessionConfigS2CPayload` v20 appends the server's data version — the S2C append
  IS safe (verified): the client codec branches per-frame on the leading version
  VarInt and drains foreign layouts (`SessionConfigS2CPayload.java:67-103`).
  Neither field is needed to decode v20 data (identity decode is version-blind).
- Capability bits (`CAPABILITY_VOXEL_COLUMNS`, `CAPABILITY_ZSTD_COLUMNS`) unchanged.
- `HandshakeGate.WireDialect` grows `V19`; `CURRENT` becomes protocol 20. Gate ladder
  order unchanged (version rung first; mismatch = no reply).
- **New config keys** (all following `ServerConfigBase` clamp/default conventions,
  extending the `ConfigValidationTest` compat-defaults pin): server
  `enableV19Compat` (default true — the v19 rung's kill switch, sibling of
  `enableV16Compat`/`enableV18Compat`; the repo convention is that every rung
  ships one) and `enableViaMismatchGuard` (default true — §7's operator override:
  the guard denies registration off a third-party reflective API, and a Via API
  drift that misreported protocols would otherwise lock out legitimate same-MC
  legacy clients with no recourse); client `unknownBlockFallback` (default
  `minecraft:stone`) and `crossVersionBlockFallbacks` (the curated-table extension,
  §3).

### 2.3 Client decode changes

The client does NOT parse v20 containers through vanilla's `section.read` strategy
tables — their width→shape mapping is version-fixed and rejects the v20-custom
widths (§2.1). Instead the decode **reuses the §4.2 translator with the CLIENT's
registry**: `ClientColumnProcessor` translates v20 → the client's OWN native layout
in memory (identity → local id through the fallback ladder §3; vanilla-shaped
sections pass their longs verbatim, only v20-custom-width sections repack), then
feeds the existing `section.read` path (`ClientColumnProcessor.java:454-455`)
unchanged. One translator component, exercised on both ends of the wire; the
post-decode fills (`withImplicitSkyAbove`, `withAirFilledAbsentSections`) are
format-agnostic and untouched.

Decode-robustness changes riding along:
- Sections whose `sectionY` is outside the client's world range are **parsed and
  discarded** (today's count-clamp at `:448` assumes same-version heights; clamping
  the count would desync the read cursor on a cross-version height mismatch).
- **The hostile-allocation pin survives** (review MAJOR): today's clamp is
  dual-purpose — `ClientColumnProcessor.java:437-441` pins that the claimed section
  count never sizes an allocation. The v20 decode accumulates sections dynamically,
  bounded by buffer exhaustion plus a sanity cap, never allocating from any
  wire-claimed count (dictCount included). Pinned by a hostile-frame test.
- Unknown-identity resolution uses the fallback ladder (§3), memoized per session.

## 3. Unknown-identity fallback ladder (client)

Via lessons applied: fail open with a bounded warn, never abort a section; curated
fallbacks beat algorithmic; terminal default must not be air (air punches holes in
distant terrain — ViaVersion's `checkValidity` returns air; wrong default for LOD).

1. **Exact**: block registry has the name → `defaultBlockState()` → apply each
   property via the state definition; unknown property names/values are skipped,
   missing ones keep defaults (this IS the drop-unknown-props rung — it falls out of
   the resolve algorithm for free).
2. **Curated table** (name-level): a small JSON shipped in the client jar mapping
   removed/renamed block names to visually-similar local ones, ViaBackwards-diff
   style (`sulfur → sandstone`), user-extendable via a client config key. Ships
   EMPTY at first release; populated as real gaps are reported.
3. **Terminal default**: config `unknownBlockFallback`, default `minecraft:stone`.
   Biomes: unknown → `minecraft:plains`.

Per-identity resolution is memoized per session; each fallback logs once per identity
per session (bounded), and a `fallbacks=` counter joins `/lss trace` + client diag.

Two identities may resolve to the same local state (or the fallback) — the decoded
palette then has duplicate entries. `PalettedContainer` tolerates duplicates, so no
index remap is required client-side (Via needs the collapse pass because it re-EMITS
wire bytes; we only materialize sections).

## 4. Server side: encode direction and legacy egress

### 4.1 v20 is the canonical internal form

All three produce paths emit v20 directly; store rows hold v20; legacy sessions get
a per-serve translation at egress. Rationale: single canonical form, self-contained
registry-independent store rows, and the NBT transcode gets *simpler* (see below).
The translation cost lands on legacy sessions during the transition window — palette
layer only (~93 entries + ~38 strings/col, memoized lookups; packed arrays copy
verbatim for natively-indexed sections, the re-palettized minority repacks — §2.1),
negligible next to zstd + serialization.

- **Live path** (`SectionSerializer.java:99-136` + Paper twin): serialize the
  container as today but map palette ids → dictionary indices via a memoized
  per-registry `id → canonical identity` table (built once from
  `Block.BLOCK_STATE_REGISTRY` iteration order — the same enumeration
  `RegistryFingerprint` already uses). DIRECT sections re-palettize here.
- **NBT transcode path** (`NbtSectionSerializer.transcodeSection:408` + Paper twin):
  **AMENDED at C0 (2026-08-07 review MAJOR 6 — the original "canonicalize straight
  from the disk NBT, no registry resolution needed" is RETRACTED).** The pinned
  rule: **identities on the wire are always the identity of a state that RESOLVED
  in the emitting server's registry** — the transcode path canonicalizes from the
  `MemoizedNbtCodec`-resolved state (via its global id through the identity
  table, one array index per palette entry — the memo already resolves every
  entry for the two shorts and the mask pre-gate, so this costs nothing new;
  R-5's "memo stays hot" holds). Canonicalizing raw disk spellings instead would
  (a) break disk/live byte parity the moment the codec's leniency fires (a
  hard-error entry substitutes AIR in place, a partial property set fills
  defaults — the raw spelling and the resolved state's canonical form are then
  different strings, different dictionaries, different bytes: red
  `SerializerParityGameTests`, per-path `DirtyContentFilter` hashes), and
  (b) void §4.2's "exact and lossless on the same MC version" egress premise
  (a raw spelling absent from the registry has no id for the v19 translator).
  The `MemoizedNbtCodec` id resolve therefore survives for the wire identity as
  well as the two shorts and the x-ray mask pre-gate (`mask.containsId`,
  `:497-508`). The >256-palette object-path fallback can be RETIRED for encoding
  (v20 has no DIRECT limit; a >256 disk palette transcodes fine) but stays for
  malformed shapes.
- **X-ray masking** stays upstream of encoding, in the object/descriptor domain
  (`XrayMaskFilter.mask` on `LevelChunkSection` before `write`; transcode pre-gate
  unchanged) — every serve path and the `DirtyContentFilter` hash keep seeing
  identical masked bytes. No mask changes required in phase 1; an identity-level
  `MaskSet.containsIdentity` is a later cleanup, not a dependency.
- **DirtyContentFilter**: hashes raw served bytes; v20 changes the bytes, the filter
  is in-memory-only (re-seeds from serves after restart) — no migration concern.

### 4.2 Legacy egress translators (same-MC v19/v18/v16 clients)

New in `common/`: a `WireSectionCursor` (parse/skip/re-emit both the v20 and native
section layouts — the transcoder's layout knowledge, factored out and shared with
store migration) and a `V20ToNativeTranslator`:

- identity → global id via a memoized `identity → id` map from the server's own
  registry (exact and lossless on the same MC version — every identity a same-version
  server serves exists in its registry; see §5.3 for the one post-migration edge).
- **Palette collapse** (Via lesson): if two dictionary identities resolve to one id
  (cannot happen same-version, CAN happen through the §5.3 edge), dedupe palette
  entries and remap the packed indices — implemented and pinned from day one, not
  deferred.
- Emits native v19 layout (global-id palettes at vanilla widths, DIRECT at
  registry-size bits when >256/>8 — exactly what a v19 client's `section.read`
  expects; v20-custom-width sections repack here). Kill switch: `enableV19Compat`
  (§2.2).

Seam wiring (all four dialects flow through one choke point per platform):

- Fabric `RequestProcessingService.sendColumnPayload` (`:602-664`): dialect switch
  becomes V20-native / V19 (translate) / V18 (translate + `asV18()` splice) / V16
  (translate + `asV16()` splice). The existing `WireShape` byte-splice machinery
  (`VoxelColumnS2CPayload.write:164-178`) is unchanged — v18/v16 are "v19 minus
  bytes", so translation composes with the existing splices.
- Paper `PaperRequestProcessingService.columnPayloadSender` (`:130-174`) +
  `PaperPayloadHandler.rewriteColumnToV16/V18` (`:152-203`): same composition.
- **Store hits for legacy sessions**: today a store hit ships the zstd frame
  verbatim. Post-v20 a legacy serve must decompress → translate → recompress (v19
  with zstd capability) or serve raw (v18/v16, which require RAW anyway — their
  splices throw on non-RAW, pinned today). This CPU lands only on
  legacy-session store hits and shrinks to zero as clients update.
- `V16CompatManager` (synthetic want-set, ingress shim) is untouched — it never
  sees section bytes.

### 4.3 Dialect tracking

Replace the `V18CompatTracker` bare set + `V16CompatManager.isV16` egress checks with
one `WireDialectTracker` (UUID → V16/V18/V19/CURRENT), preserving the pinned
lifecycle semantics exactly: marked at network level BEFORE `registerPlayer` (Fabric
inline main-thread; Paper only via the pump's dialectFlip), survives the
dimension-change remove+register cycle, dropped at disconnect + Paper's
quit-originated mailbox Remove. `V16CompatManager` keeps its session objects for the
ingress shim and consults the tracker for membership — with ONE lifetime (review
finding): today v16 membership has two (the manager's 75 s synthetic-want-set
session TTL vs the egress `isV16` checks), and a tracker dropping only at
disconnect would outlive an expired session. The tracker is the single source of
truth; the manager's session prune is also its tracker-removal hook. `/lsslod diag`
gains a `Dialects: v20=N v19=N v18=N v16=N` line replacing the `V18Compat:` slot —
which touches FIVE pinned surfaces, all enumerated in §9: the `DiagData` record
slot ordering (`DiagnosticsFormatter.java:38,82,102`), the formatter goldens,
Tier-2 `CommandGameTests` exporter/formatter agreement, the Paper command-output
tests, and the exporter schema-parity tests on both platforms. (The soak JSONL
schema is unaffected — verified: no v18 field in `check_soak.py`.)

## 5. Store migration (schema 3 → 4)

Stored blob == wire frame by construction (`depositFrame` verbatim,
`getFrame` serves without decompressing). A wire change is therefore a store change.
The Modrinth server's live store is ~4 GB — a blocking full rewrite at open is not
acceptable, so migration is **lazy + background**, with drop-and-rebuild as the
fallback:

1. **Schema bump with row-level format tag**: `SCHEMA_VERSION = 4`;
   `ALTER TABLE lods_<id> ADD COLUMN wirefmt INTEGER NOT NULL DEFAULT 19` on
   upgrade; new deposits write `wirefmt = 20`. **Meta is stamped
   `wire_format_version = 20` and `schema_version = 4` INSIDE the upgrade
   transaction, immediately** — review MAJOR: `metaMatches`
   (`SqliteLodStore.java:353-364`) compares by EQUALITY, so any
   "stamp 20 when migration completes" scheme fails the match on the first
   post-upgrade restart and `openOrRecreateWriter` (`:288-308`) drops the whole
   store — the 4 GB lazy design self-destructing. Migration COMPLETION is tracked
   separately (a cheap `EXISTS wirefmt=19` probe / `min_wirefmt` meta key), never
   via the version keys. The upgrade block sits between `openWriter()` and the
   `metaMatches` check, probes `pragma table_info` before the ALTER (SQLite has no
   `ADD COLUMN IF NOT EXISTS`; the catch path must never re-run a half-applied
   upgrade — any upgrade failure falls through to drop-and-rebuild), and fires
   ONLY from the exact state `schema_version=3 ∧ wire_format_version=19` — any
   other from-state (dev-era metas, ≤18) drops as today, because the ALTER's
   `DEFAULT 19` would mislabel those rows for the inverse translator.
2. **Migratability gate**: upgrade-in-place ONLY when the stored
   `registry_fingerprint` matches the running server (the stored bytes' ids are only
   decodable against the registry that wrote them — same rule as today) AND the
   §5.1 exact from-state holds AND `codec` matches. Any mismatch → the existing
   drop-and-rebuild, which stays the universal fallback; the backfill re-warms a
   dropped store (~23 min at the default 500 cps).
3. **Read path**: a `getFrame` hit on a `wirefmt=19` row decompresses, translates
   id → identity (the inverse translator — the same memoized registry table §4.1
   builds), re-encodes; serving is correct from minute zero. A legacy-session serve
   of a 19-row skips the double translation (it is already native). Contract
   changes made explicit (review finding): `getFrame`'s hit record carries
   `wirefmt` (today it selects only the blob — `SqliteLodStore.java:510-545`), the
   translation runs on the reader-pool threads (`StoreCodec` is stateless —
   verified safe), and a translated serve's recomputed `usize` is what feeds the
   raw-denominated bandwidth charge (`AbstractChunkDiskReader.java:294`).
4. **Background migration walk**: rides the backfill's restraint pattern (paced,
   MSPT-gated at the same `LOD_STORE_BACKFILL_TICK_CEILING_MS`, resumable): batches
   of `wirefmt=19` rows per dim (rowid watermark in meta `migrate_progress_<dim>` —
   valid: `lods_*` are true ROWID tables, `pos INTEGER PRIMARY KEY` IS the rowid),
   decompress → translate → re-encode → recompute `usize`/`chash`/`fhash` → UPDATE
   with `wirefmt=20`, through the existing single-writer batcher **with the
   watermark write riding the SAME batcher transaction as its batch** (an UPDATE
   lost to rollback then retries because the watermark rolled back with it; a
   watermark committed apart from its rows would silently skip them). Any per-row
   parse anomaly deletes the row (derived data). Migration SQL failures count
   toward the existing `WRITE_FAILURE_LATCH` (20) — accepted: a store that fails 20
   consecutive writes should latch dead regardless of the writer. A `DropAll`
   mid-walk also resets `migrate_progress_*`. One INFO line at start/end with row
   counts; progress in `/lsslod store status` (`migrating=<n>/<total>`).
5. **Interplay**: cap eviction and freshness sweeps operate on rows regardless of
   `wirefmt` (verified: `evictOldestBatch` selects only `pos, length(blob), ts` and
   the sweep never parses blobs; migration UPDATEs don't touch `ts`, so age order
   survives); the backfill walk and the migration walk share the pacing budget (run
   migration first — it converts existing value; backfill adds new value). One
   acknowledged day-one pessimization: at release ~100 % of sessions are v19, and
   every MIGRATED row served to them pays decompress→translate→recompress while
   unmigrated 19-rows serve verbatim — bounded (tens of µs/col), shrinking as
   clients update; the walk still starts promptly because a long mixed-format era
   is worse to operate than a short recompress era.
6. **Post-migration policy — the fingerprint stays.** Identity-keyed rows are
   registry-drift-proof in principle, but relaxing the fingerprint drop creates a
   real edge: a store row written under mod-set A can contain identities absent from
   registry B, which a legacy-session egress translation cannot resolve (§4.2's
   collapse/fallback would have to fabricate server-side content). Keep
   drop-on-fingerprint-drift in this release; "store survives mod changes" is a
   documented follow-up that needs the legacy-egress unknown-identity policy decided
   (likely: delete row + fresh disk read).

## 6. Client-side ladder for older same-MC LSS servers

Generalize `ClientSessionGate` (`ClientSessionGate.java`) from the one-shot v16
discovery boolean to an **attempt ladder**: announce 20 → 5 s silence → announce 19 →
5 s silence → announce 16 (each stage reusing the existing
`V16_DISCOVERY_DELAY_TICKS` machinery, arm/disarm/reset/downgrade-guard semantics
preserved and re-pinned per stage). `V16ClientWire`'s netty-thread boolean becomes a
per-attempt **decode dialect** enum consumed by `VoxelColumnS2CPayload.read`.

- **The v19 rung is cheap**: a v19 server replies natively to a 19 announce with the
  current 4-field SessionConfig; the v20 client's "v19 mode" is precisely today's
  decode path (source + codec bytes, native-id palettes against its own registry —
  same MC version by construction). It is retained code, not new code.
- **Deliberately NO v18 rung**, consistent with the existing decision (a v0.9.1
  client on a v0.8.x server degrades via v16): v0.7.x–v0.8.x servers all carry the
  server-side v16 rung, so the ladder still lands every legacy server; adding 18
  would buy source-byte fidelity on two old lines at the cost of a fourth 5 s stage
  and a third decode dialect. Revisit only if users complain about v16-degraded
  sessions on v0.8.x servers.
- Worst case to a v16 session: ~15 s after join (three stages). Acceptable — v16
  discovery is already 5 s and self-heals late.
- The downgrade guard generalizes: a LOWER-version SessionConfig arriving while a
  higher-version session is established re-announces the current version instead of
  downgrading (today's `:208-223` logic, per rung).

## 7. Mismatch guard for legacy clients on a different MC version

A v19/v18/v16 handshake carries no MC version, and cross-MC legacy sessions cannot be
served (native-id formats). Best-effort detection, applied at the gate BEFORE the
compat rungs register anybody:

- **Reflective Via probe** (`common/` seam + platform impls, the
  `MoonriseReadCompat` zero-compile-dep pattern): `Via.getAPI().getPlayerVersion(uuid)`
  — present when ViaVersion (Paper/Folia) or ViaFabric (Fabric) is installed, which
  is the ONLY way a cross-MC client can be connected directly. Protocol ≠ the
  server's native protocol → the gate answers a legacy handshake with **no
  registration** and one INFO line naming the versions ("LOD unavailable for <name>:
  client MC <x> vs server <y> — client must update LSS"). All failure shapes → probe
  absent → no behavior change (the resolve ladder / warn-once / null-latch shape of
  the other compat bridges). Kill switch: `enableViaMismatchGuard` (§2.2) — the
  guard denies registration off a third-party API, so operators need an override if
  a Via API drift ever misreports protocols.
- **v20 clients are always detectable** via the handshake `mcDataVersion` — and
  always servable, so mismatch there only feeds diagnostics.
- **Documented hole**: Via on a Velocity/Bungee proxy is invisible to the backend;
  legacy clients behind it stay undetected (they get today's garbage behavior until
  they update). Release notes say so.

## 8. Measured size impact

Method: parsed 400 real overworld columns (26.2, `test-server/paper` world region
files — genuine played terrain), extracted every served section's block/biome
palettes, computed the palette-layer cost under each encoding
(`scripts/palette_size_analysis.py`; packed arrays + light identical across
encodings). Baselines from `compressed-columns-design.md`: raw column ≈ 30–35 KB,
zstd-1 wire frame ≈ 5,342 B/col.

| | per column (mean) |
|---|---|
| served sections / block palette entries / distinct identities | 9.6 / 93.5 / 38.0 |
| current palette bytes (VarInt global ids) | ~255 B |
| identity-DICTIONARY encoding (chosen) | ~1,429 B → **+1,174 B/col** |
| identity strings inline per entry (rejected) | ~2,906 B → +2,651 B/col |
| dictionary strings after compression (zlib proxy) | ~308 B |

**Denominator care (review MAJOR — the original draft mixed measures):** the
+1,174 B is **+5.7 % of the palette+packed measure the script computes** (~20.6 KB;
light nibbles and headers excluded) and **+3.4–3.9 % of the full 30–35 KB raw
column**. The wire figure divides a dictionary measured on THIS corpus by a frame
size measured on the compressed-columns corpus (cross-corpus), and the proxy has
known biases in both directions: zlib-6-over-strings-alone omits ~131 index bytes
and doesn't subtract the current palette's own compressed cost (understates), while
the `BIOME_ID_B = 1.5` assumption overstates the current biome cost (~65 biomes =
always 1 VarInt byte). Net planning envelope: **raw +3.5–6 %, wire ≈ +4–6 %**;
store rows grow with the wire figure (row == frame). **Phase 0 includes the
settling measurement** — encode this corpus through BOTH real encoders and diff
actual zstd-1 frames — before any release-notes or store-growth number is
published. The bandwidth limiter charges raw bytes (unchanged policy — verified:
`QueuedPayload.estimatedBytes` raw-denominated, `recordSend` charges it), so
configured caps see the raw figure. Modded servers (longer namespaces, more
distinct states) sit somewhat above; the dictionary amortizes per column and zstd
eats the string redundancy, so the shape holds. Pathological worst case (a
4096-distinct-state section, ~143 KB dictionary raw) is rare, compresses heavily,
and is bounded by the column-level 2 MiB guard + the §2.1 server-side emit check.

## 9. Test plan

The translation matrix is the risk concentration: three produce paths × four egress
dialects × two platforms × (client ladder rungs) × store row formats. Coverage:

**New common unit suites (Tier 1, both modules' twins where applicable):**
- `IdentityCodecTest` — canonical form (all props, sorted, charset-safety pin
  proving `[ ] , =` cannot occur in identifiers/props), round-trip, dictionary
  first-seen determinism, malformed-string rejection.
- `WireSectionCursorTest` — parse/re-emit byte identity on both layouts, cursor skip
  correctness, truncation/negative-length containment.
- `V20ToNativeTranslatorTest` / `NativeToV20TranslatorTest` — bijection fuzz
  (`toNative(toV20(x)) == x` byte-exact on one registry, both container types),
  DIRECT synthesis + re-palettization (blocks >256, biomes >8), single-value
  sections, the **palette-collapse + packed-index remap** case, unknown-identity
  policy.
- **Cross-registry simulation** — the cross-MC-version proof without two MC versions
  in one JVM: synthetic registry pairs (permuted ids — the A3-style permutation
  blindness pin; missing entries; added entries). Encode under registry A, decode
  under registry B: states must match BY IDENTITY; fallback fires exactly on the
  missing set, ladder rung by rung (exact → props-dropped → curated → terminal),
  duplicate-resolve tolerance.
- Fallback ladder unit tests incl. the curated-table JSON loader (empty table,
  malformed file tolerance à la config loading, user-extension merge).

**Goldens (the existing cross-module byte corpus, extended):**
- v20 goldens for the corpus sections, generated by the object path (the
  transcode-vs-object twin discipline extends: transcoded v20 must byte-match
  object-path v20 — new fuzz twin).
- **Translation-chain goldens**: for each corpus column, `v20 → v19` must byte-match
  the native v19 golden; `→ v18`/`→ v16` must match the existing splice goldens.
  Fabric and Paper twins both pinned (`WireParityTest` extension: v20 bytes
  Fabric == Paper).
- `xray-masked` golden regenerated for v20; masked disk/live parity re-pinned.
- **Cross-line fixture corpus** (review MAJOR — closes the only-manual gap): a v20
  frame corpus CAPTURED from real 26.2 terrain is checked in as a golden fixture,
  and the 26.1 / 1.21.11 lines' Tier-1 suites (phase 7) decode it against their
  REAL registries — asserting identity-level content, exact fallback counts, and
  the §2.1 count-shorts recompute. Without this, the actual issue-#85 scenario has
  zero automated coverage anywhere, forever.

**Handshake / dialect (Tier 1):**
- Gate ladder: v20 native (with the `lss:client_info` sidecar), V19/V18/V16 rungs,
  no-reply mismatch, flag-independence pins extended to the third rung
  (`enableV19Compat`), the version-first legacy-announce parse on the shared
  channel.
- **The frozen-shape pin**: the v20 announce decodes byte-cleanly under the v19
  `HandshakeC2SPayload` codec (no trailing bytes — the §2.2 CRITICAL), and
  `lss:client_info` absence/malformation is ignored-safe.
- `ConfigValidationTest` compat-defaults pin extended to `enableV19Compat` +
  `enableViaMismatchGuard`; client-config validation for `unknownBlockFallback`
  (parseable block id) + curated-table file malformation tolerance.
- The `Dialects:` diag replacement's five pinned surfaces (§4.3): `DiagData` slot
  ordering, formatter goldens, Tier-2 `CommandGameTests` exporter/formatter
  agreement, Paper command-output tests, exporter schema parity on both platforms.
- `WireDialectTrackerTest` — the V18CompatTracker lifecycle pins generalized:
  pre-register marking, dimension-change survival, disconnect + Paper quit-race
  mailbox-Remove drop, cross-dialect sheds, diag line.
- Via-probe seam tests against real-package-name stubs
  (`com.viaversion...` under test roots, the `AntiXrayCompat` pattern): resolve
  ladder, mismatch → no-register, absent/throwing probe → unchanged behavior,
  warn-once.

**Client (Tier 1):**
- Ladder tests generalizing `ClientSessionGate` coverage: three-stage timers, per-rung
  downgrade guard, disarm on accept, reset on disconnect/join, v19-rung decode
  dialect selection, late-config races.
- Decode: identity resolve through a real registry (fabric-loader-junit bootstrap, as
  the existing serializer tests do), out-of-range-sectionY discard (cursor stays
  aligned), fallback content assertions, per-session memo + bounded warn.

**Store (Tier 1):**
- Migration suite: migrated row byte-equals a fresh v20 encode of the same source
  (golden); `usize/chash/fhash` recomputed; watermark resume mid-walk (kill between
  batches); fingerprint-drift → drop-and-rebuild; corrupt row → deleted, walk
  survives; mixed-format serving (a 19-row store hit through the inverse translator
  byte-equals its post-migration serve); schema-3 meta → ALTER upgrade; cap
  eviction + freshness sweep indifference to `wirefmt`; `store status`
  `migrating=` token.

**Gametests (Tier 2):** crafted-frame handshakes through the production receiver for
all four rungs (extending the specimen set — v20 with dataVersion registering
natively; v19 registering via the rung and receiving translated columns);
`SerializerParityGameTests` disk-vs-live byte parity under v20 incl. masked parity;
lifecycle pins re-run (the entrypoint-listing contract catches new classes).

**Tier 3:** existing end-to-end revalidates v20 natively (decoded content assertions
already check real block layers — they now exercise identity resolve); add an
ingest-fallback assertion (a column carrying a synthetic unknown identity decodes to
the fallback state at the consumer).

**Soak:** all 19 scenarios inherit v20 natively (the laws are format-blind for
NATIVE sessions; re-baseline nothing unless churn ceilings move). Additions:
- `-Psoak.dialect=19` client lever forcing the announce version → fresh-backfill +
  warm-rejoin as a live v19 session: the v19 egress translator gets LIVE soak
  coverage, not just goldens. **Dialect 16 is EXCLUDED from the lever** (review
  CRITICAL — "the laws are format-blind" is FALSE for the v16 shim): law A1's
  ledger has no terms for the shim's sheds (`overflowBounced`/`graceDiscarded`,
  `V16CompatManager.java:135-136,214` — not snapshot fields), and the synthetic
  want-set's 1 Hz sole-declarer / 75 s TTL semantics break the quiescence client
  mirror — a dialect-16 soak reds A1 by construction. v16 egress correctness is
  covered by the translation-chain goldens + crafted-frame gametests; if live v16
  soak coverage is ever wanted it is its own sub-task (export the shim counters
  into snapshots, extend A1 + `--selftest`, whitelist the scenario-config keys —
  `check_soak.py:160-164`).
- A store-migration variant of `store-second-join`: stage a schema-3/v19-format
  store fixture, verify warm hits from minute zero (inverse translator), migration
  completion, and law cleanliness during the walk.
- `bandwidth-throttle` thresholds re-checked against the raw growth (the limiter
  charges raw bytes, so +~5 % raw shifts its margins).

**Live matrix (manual, release gate for the feature):** the lss-multi-test harness +
a Via'd 26.2 server + a real 1.21.11 client running the backported v20 client build —
eyeball terrain + `/lss trace` fallback counters; repeat with 26.1.2. This is the
only test that proves the actual issue-#85 scenario end-to-end.

## 10. Phasing

| Phase | Content | Est. |
|---|---|---|
| 0 | `IdentityCodec`, registry identity tables (both directions, both platforms), `WireSectionCursor` + unit suites, the §8 settling measurement (real-encoder zstd-1 frame diff) | 2–3 d |
| 1 | v20 encode on all three produce paths + client decode + fallback ladder + handshake/gate v20 + `WireDialectTracker` | ~1 wk |
| 2 | Legacy egress translators (v19/v18/v16 composition) + store-hit recompress + soak dialect lever | 3–4 d |
| 3 | Client ladder (v19 rung + generalized discovery) | 2–3 d |
| 4 | Store schema 4 + lazy read-path translation + background migration walk | ~1 wk |
| 5 | Via probe guard + mismatch messaging | 2 d |
| 6 | Golden/fuzz/cross-registry hardening, Tier 2/3 additions, soak runs, legacy-dialect benchmark run, live matrix | ~1 wk (overlaps) |
| 7 | Fresh 26.1 + 1.21.11 re-ports from main, cross-line fixture suites, tri-release (+ release notes ×3 lines, CLAUDE.md update) | ~2–3 d + ~1 wk (§11) |

Phases 1–5 land as one release branch (a wire bump cannot ship piecemeal), but each
phase is independently reviewable. Total: roughly 4–6 weeks at this repo's review
cadence.

## 11. Release sequencing and backports

1. **main (26.2) first** — v0.10.0, protocol 20. Safe in both directions on day one:
   old clients hit the v19/v18/v16 rungs (translated, same-MC), new clients on old
   servers ride the ladder. Release notes carry the Via-proxy hole and the store
   migration note (one-time background walk; fingerprint drift still drops).
2. **Fresh re-ports of CURRENT main to MC 26.1 and 1.21.11** (user decision
   2026-08-06) — NOT patches to the existing `support/mc26.1` / `support/mc1.21.11`
   branches, which are frozen at the v0.8.1 feature level (protocol 18 — no zstd
   columns, no store-era fixes, no adaptive cadence) and would bolt v20 onto a tree
   missing a release's worth of features. Instead: cut new support branches from
   main at v0.10.0 and retarget the MC version, so all three lines ship the SAME
   mod at the same feature level, differing only in MC bindings. The old branches
   are then retired/archived. Required for issue #85 either way: the cross-version
   story only works once those players' clients speak v20, and a client build must
   match the client's own MC version.
   - The known retargeting gotchas are catalogued (memory
     `mc-version-backport-gotchas` + the 1.21.8/1.21.11 port records): loom-remap vs
     fabric-api loom version, paperweight codebook needing Java 21 on 1.21.x, the
     `ScopedValue`-is-preview AntiXray shim degrade on 1.21.x, per-MC-version
     `xray-masked` goldens, section-write count-shape divergence (§2.1's pin), Bukkit
     split world dirs, `build.yml` `support/**` coverage, dual-line `+mc<ver>`
     release tags, per-line `release.yml` flavors + their
     `ReleaseWorkflowContractTest` twins, `release_check.py --version` expansion
     for the new tags, and the per-line `PluginYmlContractTest` folia-supported
     flavor.
   - 26.1 is a near-neighbor of 26.2 (small API delta); 1.21.11 is the larger port.
     Estimate: ~2–3 d and ~1 wk respectively, on top of the main-line work — the
     v0.8.0 tri-release is the precedent for shipping three lines from one feature
     tree.
3. The 1.21.8 / 1.20.1 lines are out of scope (see non-goals) — they stay at their
   current releases; their servers/clients simply never enter the v20 matrix.
4. After the re-ports ship: answer issue #85 with the concrete version pair
   ("server ≥ v0.10.0 AND every cross-version client on its line's ≥ v0.10.0 build"),
   all three lines released together tri-release-style.

## 12. Risks / open questions

- **Terminal fallback block** — `minecraft:stone` proposed; alternatives (biome-aware,
  y-dependent air-above-surface) add complexity for marginal LOD-scale gain. DECIDE.
- **Curated fallback table** — ship empty + config-extendable proposed; maintaining a
  real cross-version table (à la ViaVersion/Mappings diffs) is ongoing curation cost
  we can defer until reports arrive. DECIDE.
- **Store migration lazy vs drop** — lazy migration specified above; if review finds
  the mixed-format read path too fiddly, the fallback position (drop + 23 min
  backfill re-warm) is already the fingerprint-drift path and loses only warm-start
  value, not correctness. DECIDE (default: lazy as specced).
- **Transition-window CPU** — every legacy-session serve pays a palette translation;
  legacy store hits pay decompress+recompress. Bounded, shrinks as clients update,
  but the benchmark harness should measure it (add a legacy-dialect benchmark run).
- **Modded-registry blowup** — heavily modded servers have larger dictionaries;
  measured shape says the dictionary + zstd contain it, but the live Modrinth server
  (Moonrise et al., vanilla-ish registry) won't prove the heavy-mod case; note for a
  volunteer test.
- **Paper twin drift** — every new common seam (`IdentityCodec`, cursor, translators,
  tracker) lives in `common/`, so the Fabric/Paper twin surface GROWS only at the
  serializer call sites; the wire-parity + golden twins are the guard.
- **The v18-rung-on-client question** — deliberately skipped (§6); revisit on
  complaint volume from v0.8.x-server users.

## 13. Review round (2026-08-06, three Fable lenses)

Three-agent review — wire/translation correctness, store+operations,
test-adequacy+pinned-contracts. All accepted findings are folded into the sections
above; this records the round.

**CRITICALs (both fixed in place):**
- C2S handshake field append would hard-kick v20 clients from every legacy Fabric
  server (trailing-bytes decoder rejection; found independently by two lenses) →
  handshake shape frozen forever, `mcDataVersion` moved to the new
  `lss:client_info` channel, frozen-shape Tier-1 pin added (§2.2, §9).
- `-Psoak.dialect=16` reds law A1 by construction (shim sheds have no ledger terms;
  synthetic want-set breaks the quiescence mirror) → lever restricted to dialect
  19; live-v16 soak spelled out as an optional future sub-task (§9).

**MAJORs folded:** v20 palette widths explicitly specified + client decodes via
local-native translation instead of raw `section.read` (§2.1/§2.3); store meta
stamped 20 at upgrade time — the completes-later scheme would have dropped the
store on first restart (§5.1); §8 denominators corrected + phase-0 real-encoder
settling measurement; kill switches `enableV19Compat` / `enableViaMismatchGuard`
(§2.2/§4.2/§7); hostile-allocation pin preserved in the v20 decode (§2.3);
cross-line fixture corpus so the issue-#85 scenario has CI coverage (§9); the
diag-line replacement's five pinned surfaces enumerated (§4.3/§9).

**MINORs folded:** clear-frame empty-dictionary pin; dialect-tracker/V16-session
single lifetime; column-level 2 MiB clarification + server-side emit check;
migration watermark rides its batch's transaction + `WRITE_FAILURE_LATCH`
statement + `DropAll` progress reset; day-one recompress pessimization noted;
v0.8.1 correction; re-port catalogue additions (release.yml flavors, contract-test
twins, release_check expansion); phasing additions (benchmark run, release notes,
CLAUDE.md).

**Verified non-findings (kept on record so they aren't re-litigated):** the
splice-composition claim is correct; S2C SessionConfig append is safe (client
drains foreign layouts); specimen-17 survives protocol 20; `FoliaWiringContractTest`
is unaffected by Via string constants; VSS `check_wire_identity_fabric` is
unthreatened; rowid watermark valid on `lods_*`; eviction/sweep are
wirefmt-indifferent; the bandwidth raw-charge claim is accurate; §5.6's
fingerprint-stays edge is real and correctly closed; §6's no-v18-client-rung and
§5.6 match the pinned decision record.
