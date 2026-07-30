# Serve-path efficiency — think-big brainstorm (2026-07-30)

Scope: sustained disk-read serving (the `no-cache` profile workload), **server-side** —
client decode CPU (`ClientColumnProcessor`, Voxy ingest — the issue #71 pressure domain)
is out of scope except where a wire idea reaches it (Idea D does). The generation serve
path is also out of scope: fresh-world backfill is worldgen-bound (~50 gen/s), serve CPU
is noise against it — though Idea C's serve-time deposit does capture gen-sourced columns
(a generated column is warm for every later session). Mandate: maximize efficiency;
implementation cost, thrown-away work, and **protocol compatibility are all
sacrificable**. Each idea is validated against the code as merged (PR #74, main @ af33bf1)
and the 2026-07-30 profile matrices (`profile-results/20260730-transcode` +
`20260730-objectpath`). Reviewed 2026-07-30 by a two-agent round (correctness vs
code/jars; completeness/risk/pinned-decisions) — corrections incorporated, see §9.
§7 reviews the Distant Horizons server plugin for cross-pollination; §8 excavates LSS's
own pre-v0.2.0 voxel-cache architecture (tag `before-loop`) — the same shape, built and
abandoned in Feb–Mar 2026 — and why its failure conditions no longer hold.

## 0. Baseline — where the CPU goes today

From the transcode-build JFR windows (~565–595 col/s, ~1 core total):

| Band | Vanilla arm | C2ME arm | Composition |
|---|---|---|---|
| Vanilla tick | ~45% | ~35% | not ours |
| LSS serialization (reader pool) | ~25% | ~36% | `transcodeSection` self ~12–23% of JVM, `MemoizedNbtCodec.resolve` ~6–7% (~⅕ of it the `CompoundTag.hashCode` key chain — 15–23% across reps), writeContainer, biome ids |
| NBT tag load (IO-Worker / c2me-worker) | ~19% | ~24% | `CompoundTag$1.load`: readUTF/readFully, String alloc, map inserts — parsing region bytes into tags LSS mostly discards |
| Worker-Main (vanilla arm only) | ~6% | — | vanilla background pool (`PalettedContainer.count` etc.) — not ours |
| Netty / LSS processing / rest | ~5% | ~4% | — |

LSS-attributable ≈ **45–60% of JVM CPU**, split roughly half serialization band, half NBT
tag load. Two structural facts frame everything below:

1. **The NBT-load band parses the whole chunk tag; LSS reads a fraction of it.** The
   transcoder consumes `Status`, `sections[].{Y, block_states.palette, block_states.data,
   biomes, BlockLight, SkyLight}`. Everything else — `block_entities`, `Heightmaps`,
   `structures`, `fluid_ticks`/`block_ticks`, `PostProcessing` — is parsed into maps and
   strings, then dropped.
2. **Delivered rate rides the per-player bandwidth cap, not CPU.** CPU wins land as lower
   CPU per delivered column (headroom for gameplay / more players), not more columns/s —
   unless a wire idea (§2) also shrinks the bytes the cap meters (`estimatedBytes` =
   uncompressed section bytes + 45).

Allocation baseline: ~15–17 GB sampled per ~65 s window (byte[] 5.5–6.3 GB, NBT
strings/map nodes most of the rest); GC absorbs it (~0.2–0.3 s total pauses) but it is
the memo/transcode bands' churn made visible.

---

## 1. Flagship arc — own the bytes end-to-end

### Idea A — streaming NBT→wire transcode (kill `CompoundTag`)

Today: region bytes → inflate → `CompoundTag` tree (the whole chunk) → `transcodeSection`
walks the tree. The tree is pure overhead: the transcoder needs ~6 values per section, and
the palette memo (`MemoizedNbtCodec`) already proves entry decode results are massively
reusable.

**A-lite (no custom IO): selective scan via vanilla's `StreamTagVisitor`.** Verified
present in the 26.2 jar: `RegionFileStorage.scanChunk(ChunkPos, StreamTagVisitor)`, and
`IOWorker.scanChunk` (which consults `pendingWrites` first). Fabric's background path
today schedules `storage.read(pos)` on the IOWorker's executor at BACKGROUND ordinal
(`ChunkDiskReader.backgroundRead`); swapping in `storage.scanChunk(pos, visitor)` is the
same shape. **Read-your-writes vs BACKGROUND is a pick-one** (review finding): the
pending-write-aware `IOWorker.scanChunk` submits via `submitThrowingTask` at FOREGROUND
(bytecode-verified), while the BACKGROUND-shape `storage.scanChunk` skips `pendingWrites`
exactly like today's `storage.read` — same RYW gap as today, or replicate the
`pendingWrites` lookup through the existing accessor (feasible, not free). A
`CollectFields`-style selector — verified expressive enough:
`FieldSelector(ListTag.TYPE, "sections")` selects the list and `CollectFields` collects
the full subtree beneath it — materializes ONLY `Status` + the `sections` list and skips
parsing block entities, heightmaps, structures, and tick lists entirely.

- Pros: small diff on the vanilla arm; no custom file handling; no torn-read risk; keeps
  the BACKGROUND priority discipline; visitor work stays cheap enough for the IOWorker
  thread (skip-heavy, materialize-light). Downstream (`serializeChunkNbt`) unchanged —
  byte-identical output by construction, existing goldens/fuzz carry over.
- Cons: only reaches the **vanilla-IOWorker arm**. Moonrise (`loadDataAsync`) and the
  C2ME-latched fallback (`chunkMap.read`) return full `CompoundTag`s — Paper and modded
  Fabric servers keep the full parse. Saves only the non-section share of the NBT band
  (estimate 20–40% of it; the profile cannot split section vs non-section parse — measure
  first with a one-off visitor prototype). Scan cost still serialized on the single
  IOWorker thread.
- Verdict: cheap, real, partial. Worth doing only as a stepping stone if B is deferred.

**A-full: byte-slice transcoder (needs Idea B's raw bytes).** One linear pass over the
decompressed chunk bytes on the LSS reader pool: match keys as raw modified-UTF-8 (no
String decode), skip unneeded subtrees token-wise, and transcode sections in place:

- Palette entries memoized by **byte-slice key** (hash of the entry's raw NBT bytes,
  full-slice compare on hit) → cached `{globalId, meta}` — replaces the
  `CompoundTag.hashCode` + tag-copy memo key (~⅕ of `resolve`'s time today; the
  defensive `Tag.copy()` is insertion-only, bounded by the cap). Miss path: materialize
  just that entry subtree into
  a `CompoundTag` and decode through the existing `MemoizedNbtCodec` — vanilla leniency
  semantics (partials, air substitution, hard-error warns) preserved by reusing the code
  that already implements them.
- `data` long arrays and light nibble arrays read directly into `long[]`/`byte[]` (one
  bounds-checked bulk copy each).
- **Key-order independence is mandatory**: NBT compounds are unordered; `data` can precede
  `palette`. A single pass must buffer subtree offsets (shallow index of the section
  compound: 6 offsets) and then extract — not assume order.
- Any anomaly (unknown Status string, mis-sized data, >256 palette, mask pre-gate hit,
  malformed structure) → **re-parse the column's raw bytes through `NbtIo` into the
  existing object path**. The fallback ladder stays definitionally today's semantics;
  exotic columns pay a double parse, mainstream columns pay none.
- Estimated effect: the NBT-load band (~17–26%) collapses to inflate + memcpy (est. 5–8%,
  saving ~12–18 points); the serialization band loses its tag-API overhead
  (`CompoundTag.getList`, map lookups, memo hashing — est. 20–30% of that band, ~5–10
  points). Against the 45–60-point LSS-attributable total: **≈ −30–50% LSS CPU per cold
  column**. (B's inflate parallelization changes throughput/latency shape, not CPU per
  column — deliberately excluded from this number.) Measure-first caveat applies: the
  profile cannot split section vs non-section parse, so prototype the visitor before
  believing the band split.

### Idea B — LSS-owned region reader (the compat-killer)

Read `r.X.Z.mca` with our own `FileChannel`s on the LSS reader pool: parse the 8 KiB
header, `pread` the chunk's sectors, inflate, hand bytes to A-full. This is the enabler
for A-full everywhere. (Paper nuance from review: Moonrise's public load API is
`CompoundTag`-only, but `MoonriseRegionFileIO$RegionDataController.readData(x,z)` —
reachable via `getControllerFor` — returns a post-decompression `DataInputStream`, an
internal-API middle road that could feed a streaming transcoder without custom file
handling. It bypasses the in-flight-write consultation and Moonrise's IO scheduling, so
it shares most of B's cons while adding internal-API fragility; noted as an alternative,
not the plan.)

Validated side benefits — this is why it is the flagship despite the risk list:

- **The entire read-compat surface disappears on the happy path.** Gone or demoted to a
  fallback rung: `MoonriseReadCompat` (202 lines of reflective shape-matching),
  `AccessorIOWorker`/`AccessorSimpleRegionStorage` mixins + the pinned BACKGROUND ordinal,
  the C2ME null-worker latch, the Moonrise typed-latch split, `chooseReadPath`'s 3-rung
  ladder, and Paper's `MoonriseRegionFileIO` dependency for reads. New chunk engines
  (the next C2ME rewrite, a future Moonrise API break) stop being LSS incidents: region
  files on disk are the stable interface, pinned per MC line anyway.
- **The IOWorker-monopolization failure class dies structurally.** The documented A7 soak
  reds (gen-save floods starving LSS reads on the single IOWorker thread → 10 s timeout
  storms) exist because LSS reads queue below vanilla saves on one thread. Own handles =
  own queue; inflate parallelizes across `diskReaderThreads` instead of serializing on
  IOWorker. (The flip side is real — see cons.)
- **Fabric/Paper twins collapse.** `ChunkDiskReader` + `PaperChunkDiskReader` +
  the two NBT serializers' read plumbing become one `common/` implementation with real
  unit tests against committed `.mca` fixtures — today the live LOW paths are explicitly
  untestable ("the soak harness is its gate").
- Folia-safe by precedent: region files are not regionised (the existing Paper reader
  already reads them off region threads).
- Optional: mmap region files read-only (zero-syscall reads, page-cache friendly);
  measure before committing — `pread` into a pooled buffer is likely within noise.

Cons / risks, honestly:

- **Torn reads.** The `.mca` format has no checksum; a concurrent platform write (new
  sectors + header update, file growth) can hand us a stale header or half-written
  sectors. Mitigations, layered: length/sector sanity checks → NBT structural validation
  (A-full's scanner is a full validator by construction) → single retry → **fall back to
  the platform read path for that chunk** (the current ladder survives as the fallback
  rung, so the worst case is today's behavior). Zlib itself is a strong tamper detector
  (bad adler/stream = exception). Frequency is low (a specific chunk being rewritten in
  the exact read window) but nonzero under gen-save floods — exactly when we read most.
- **Read-your-writes regresses to "eventually".** Today: Fabric-vanilla background path
  already gave it up (documented, dirty-broadcast heals); Paper's Moonrise path KEEPS it
  (`loadDataAsync` serves pending writes) and would lose it. Worse, Moonrise/C2ME buffer
  writes in memory — a saved-but-unflushed chunk is invisible to our reader for longer
  than vanilla's window. The heal is the same dirty-broadcast → re-serve loop, but the
  stale window widens on exactly the platforms that buffer most. Needs a soak scenario
  (dirty-broadcast under sustained save pressure) before shipping.
- **We give up cooperative IO priority.** BACKGROUND/`Priority.LOW` exists so LOD reads
  defer to gameplay IO. Own handles compete at the OS level. The existing mitigation
  (`AdaptiveReadThrottle`, AIMD wired into `hasHeadroom`, mechanically engageable
  unconditionally — verified) is necessary but not sufficient as-is: its latency EWMA was
  designed as a proxy for the shared single IOWorker, and on our own multi-threaded
  handles the signal degenerates to device latency plus LSS's own inflate/transcode CPU —
  it can read healthy on page-cache hits while readers steal IOPS from gameplay saves.
  B's v1 must **re-derive the control law with a tick-health (MSPT) term** — the same
  direction `ChunkGenerationService`'s comment already prescribes for backoff — and the
  soak revisit needs a gameplay-side harm signal (save/MSPT degradation), since no
  existing law measures LSS harming vanilla IO. The C2ME fallback arm is the live
  precedent that OS-level competition with the throttle armed can run at parity.
- **B must reproduce the corrupt-region triage classification**, not just read bytes:
  garbage-zlib → contained authoritative not-found is pinned by `RegionFaultGameTests`
  and load-bearing beyond containment — the miss memo must never seed from error-triage
  misses, and soak law A5's `d_nf` fold assumes the triage shapes. This is part of B's
  correctness surface, alongside retiring the `read_path=moonrise-low` diag token and the
  `ChunkDiskReaderTest`/`MoonriseReadCompatTest` ladder pins that become dead code.
- Format corners: compression types 1/2/3 (gzip/zlib/none), 4 (lz4, 1.20.5+), 127
  (custom named) — and note 26.2's `RegionFileVersion.configure` makes the WRITE
  compression server-selectable, so the reader must handle all ids in the wild, not just
  deflate — oversized chunks (bit 0x80 → external `.mcc`), Bukkit's split world dirs
  (known gotcha), and future format drift — all per-MC-line pins, same as today's
  ordinal pin.
- DFU: unchanged — the current path already reads raw region NBT with no DataFixer
  (pre-upgrade chunks fall to the object path / generation ticket exactly as today).

### Idea C — persistent LOD sidecar store (write-through serve cache)

The single strongest validation in this document: **on Fabric, the wire bytes for every
saved chunk are already being produced and thrown away.** `LSSServerNetworking.
onChunkSaveData` → `DirtyContentFilter.contentChanged` runs `SectionSerializer.
serializeColumn` (the full wire serialization) on EVERY chunk save just to FNV-hash the
result — verified: no debounce, no memoized skip; only service-null/disabled early-outs.
Writing those bytes to an LSS-owned store instead of discarding them makes the warm serve
path a pure `pread` + send — zero NBT, zero transcode, zero platform IO. Two review
caveats on "already paid": (1) those bytes are **post-mask** (masking runs inside
`serializeColumn`), so the free ride is the store-post-mask-bytes variant keyed by the
mask-config hash — storing unmasked bytes + palette ids would need a second
serialization; (2) `contentChanged` runs the serialization inside one `synchronized`
monitor (a documented autosave-storm contention point, and it can run OFF-main under
C2ME/Moonrise) — the store append must happen outside that monitor.

Design sketch:
- Store: LSS region-style files (1024 columns/file) holding per-column
  `{formatVersion, columnTimestamp, contentHash, maskConfigHash, wire bytes (zstd'd)}`,
  in the world folder (`lss-lod/` per dimension).
- Population: (1) save-time write-through on Fabric (marginal cost ≈ one file append);
  (2) serve-time write-through on both platforms (any cold serve deposits its bytes —
  this also captures generation-sourced columns, so generated terrain is warm for every
  later session); (3) optional background migrator for old worlds.
- Serving: store hit (fresh per the verifier below + `ColumnTimestampCache` + dirty
  state) → bytes straight to the send queue. Store miss/stale → today's path, then
  deposit. A store rung consulted BEFORE the loaded-chunk probe would also move
  loaded-but-unchanged serves off the main thread (the probe is the one serve path that
  costs tick time today).
- Invalidation rides the machinery that already exists: `DirtyContentFilter` knows the
  hash changed at save; dirty broadcast + `invalidate()` already fan out to the caches.
- **Store-hit freshness verification is a hard design requirement, not hygiene**
  (review MAJOR): the dirty economy the store would sit behind is deliberately
  incomplete. On Paper, dirty detection is the `updateEvents` allowlist —
  `ChunkPopulateEvent` opt-in, `setblock`/plugin NMS edits/WorldEdit fire nothing — and
  today those mutations still heal because a ts<=0 ask always reads disk. A store hit
  intercepts exactly that read: a column mutated via any unfired-event route would serve
  pre-edit bytes to every client, including brand-new ones, FOREVER — no dirty
  broadcast, no reconnect, no rejoin heal fires. Same class on both platforms for
  offline world edits (MCA editors, upgraders, region trims — `copyOf` never fires).
  Required: a per-hit freshness check (region-file mtime or chunk `LastUpdate` vs
  deposit stamp) and/or a startup sweep invalidating store regions whose region-file
  mtime postdates the deposit; a Paper-only ts<=0 store bypass is the blunt fallback.
- Version stamp: discard-not-migrate on wire-format bump (the `ColumnCacheStore` pre-v4
  precedent); discard on mask-config change.
- **C-lite as stage 1** (review addition): a bounded in-memory LRU of wire bytes keyed
  by `(dimension, packedPos, contentHash)` banks most of the warm-repeat value
  (rejoin-within-uptime, time-separated multi-player) with no disk format, no Paper
  population asymmetry, and no stale-forever class — staleness is process-lifetime-
  bounded and rides the existing invalidation choke points. Days not weeks; trivial
  kill switch; de-risks C's serving/invalidation plumbing before any persistent format.

- Pros: warm serving approaches the theoretical floor — file IO only. The 45–60% LSS CPU
  share applies to COLD backfill; every subsequent session serves from the store. Also
  collapses serve latency (no 10 s timeout class on warm columns) and sidesteps platform
  IO entirely for store hits (compounding B's compat win). Scope honestly: CONCURRENT
  multi-player overlap is already free — `DedupTracker` attaches N players to one read
  and the serialized bytes fan out unchanged (verified, `OffThreadProcessor` delivery) —
  so C's multi-player win is the time-separated case only.
- Cons: **disk cost** — measured wire bytes ≈ 33 KB/column uncompressed (~1.2 GB / 37k
  cols); zstd ≈ 5–10 KB/column → ~5–10 GB per million explored columns, a 15–30% overhead
  on top of region data. Needs a size cap + LRU eviction story, config to disable, and
  honest release notes. **Paper asymmetry**: no `copyOf` hook, so Paper only gets
  serve-time population — save-freshness lags until the next serve or dirty event.
  Consistency bugs here are silent-stale-LOD bugs, the worst failure class in this doc.
  Cold backfill (the benchmark scenario) is unimproved.
- Verdict: highest steady-state value per unit of work in this doc — but its failure
  class (silent wrong terrain) is worse than B's engineering risk, so it ships only with
  the freshness verifier solved. Orthogonal to A/B; does not replace them (first-ever
  serves of every column still take the cold path).

---

## 2. Protocol ideas (v19 — break allowed per user)

### Idea D — session-scoped content-addressed section dedup

Real worlds repeat section bodies at enormous rates (ocean water columns, desert fill,
stone bands, all-air+skylight caps) — **unmeasured hypothesis: run the offline
repeat-rate measurement against real region files before committing any wire surface.**
The server keeps a per-session set of `hash(section body) → sent`; a repeated body ships
as an 8–16-byte reference instead of ~2–8 KB. The client keeps a bounded
`hash → decoded section` cache and rebuilds `VoxelColumnData` from refs. "The server
knows what it sent" needs the delivery-honesty treatment, not just eviction epochs
(review finding): post-send losses are documented loss classes (decode failure, consumer
rejection via `reportIngestFailure`, send-queue drops), so refs must follow the same
send-success-only discipline as departure stamps, and a client-side ref-miss needs an
explicit cheap re-ask shape (a ref the client can't resolve = re-declare the position
with a "no ref cache" marker) rather than being treated as impossible.

- Pros: multiplies with A-full/B — hash the RAW section byte-slice before transcoding,
  and a hit skips the transcode too, not just the bytes. Cuts wire bytes (more columns
  per bandwidth-cap dollar — the one lever that raises DELIVERED rate) and client decode
  CPU. Light arrays dedup too (all-zero / full-bright templates dominate).
- Cons: protocol statefulness where v17 is deliberately stateless-per-position (the
  idempotency model survives — a ref is still a statement about a position — but eviction
  must be server-driven or epoch-based so the client never holds a dangling ref);
  client memory (bounded LRU, tens of MB worst case); a collision serves wrong terrain —
  use 128-bit (XXH128) and treat as impossible. Interacts with the v16 shims: v19 is the
  natural point to retire `enableV16Compat`'s wire paths or leave them keyed to dialect.
- Verdict: the best pure-protocol idea; world-dependent (oceans huge, varied terrain
  moderate). Prototype = measure repeat-rate offline from an existing world's store/
  region files before committing wire surface.

### Idea E — batched column frames + off-thread pre-compression

Columns ship one payload each today ("column data payloads are sent individually"), each
individually zlib'd by MC's connection compressor on the netty thread. v19 alternative: a
`ColumnBatchS2CPayload` carrying N columns, compressed ONCE by LSS on the processing/
reader pool (zstd via a shaded lib, or `Deflater` with a preset dictionary), flagged
pre-compressed so MC's compressor passes it through (below-threshold or marked).

- Pros: better ratio (shared context across adjacent columns compresses far better than
  per-column), compression CPU moves off the netty thread onto pooled threads, fewer
  packets. If the bandwidth cap meters post-compression bytes (an honest accounting
  change), delivered col/s rises at the same configured cap.
- Cons: zstd-jni is a native dep on every platform (weight, packaging risk — Deflater+
  dictionary is the no-dep fallback); batching complicates the per-column send-success
  bookkeeping (`stampDeparted`, the duplicate-serve grace, per-column `queued_bytes`) —
  a shared departure instant is fine, but a batch send-FAILURE loses N columns' proactive
  done-bit clears at once, which is exactly law A1's documented lost-frame latent shape:
  the "send-failure drops never stamp" rule must extend to whole frames. And "flagged
  pre-compressed so MC's compressor passes it through" is not a vanilla feature —
  verified against 26.2: `CompressionEncoder` is a `MessageToByteEncoder<ByteBuf>`
  installed as the named `"compress"` handler by `Connection.setupCompression`, decides
  purely on `size >= threshold` over anonymous post-PacketEncoder bytes (shared
  per-connection `Deflater`, default level), with NO per-packet exemption hook. Bypass
  options: Fabric mixin swapping in an LSS-aware encoder subclass that recognizes our
  frames from the packet-id/channel prefix (doable, standard-invasive); Paper has no
  non-fragile equivalent (reflective pipeline injection — decline); proxies compress the
  client leg regardless, so a backend bypass never fully controls the wire. The v1
  answer is NO bypass: pre-compression shrinks the deflater's input 3–6×, so most of the
  netty CPU win arrives anyway, and deflate over high-entropy data emits cheap stored
  blocks (~0.03% overhead). Side synergy: on `network-compression-threshold=-1` servers
  (LAN/high-bw), pre-compressed LSS frames make that ops choice viable — document it.
- Verdict: moderate CPU win, real bandwidth win; do after D (D shrinks the input first).

### Idea F — wire micro-tightening (fold into any v19 bump)

Light-nibble RLE (mostly 0x00/0xFF runs), varint section counts, drop the per-column
dimension string for a session-interned dimension id (the string is re-sent per column
today — `writeUtf` per payload). Each is small; zlib already flattens most of it. Do
opportunistically when v19 opens the format, not as its own project.

---

## 3. Targeted band work (no architecture change)

### Idea G — memo key fast path
`resolve`'s cost is ~⅓ key hashing (`CompoundTag.hashCode` over the entry subtree).
Most palette entries are property-less (`{Name: "minecraft:stone"}`): key those by the
name String directly (String.hashCode is cached); full-tag key only for propertied
entries. Subsumed by A-full's byte-slice keys — do only if A is deferred.

### Idea H — vectorized histogram / bit-unpack
The transcode leaf's inner loop (LSB-walk histogram) is scalar shift/mask. Java 25 has
the Vector API (still incubator — `--add-modules jdk.incubator.vector` on every server
JVM is a real deployment con; scalar fallback mandatory). Gather-based histograms
vectorize poorly; the realistic win is unpacking cells to a byte[] lane-wise then a
scalar count pass. Prototype before believing in it; likely 1.2–1.5x on the loop, not 4x.

### Idea I — validated round-2 leftovers (already queued, value order)
Paper frame-assembly copy kill (`encodeVoxelColumnPreEncoded` exact-size + array steal),
router micro-wins (hoisted per-dimension tscache resolution, packed-long duplicate-ladder
overloads, direct `IncomingRequest[]` ingress, `Long2ObjectMaps.emptyMap`), 8-byte-stride
`fnv1a64` (save path). Fillers between the big rocks.

---

## 4. Composition & recommended arc

- A-full requires B (raw bytes). A-lite requires neither but only covers one arm.
- C is orthogonal to A/B and dominates warm serving; A/B dominate cold backfill.
- D multiplies with A/B (raw-slice hash before transcode) and shrinks E's input.
- B's throttle work (AdaptiveReadThrottle always-on) is a precondition, not a follow-up.

Sequencing is genuinely contested (review MAJOR), so both readings are stated:

- **The case for C-first:** delivered rate is bandwidth-capped, so B+A-full's cold-CPU
  win is invisible in col/s — it buys headroom. C is smaller (1–2 wk vs 2–3 wk), has no
  dependency on B (store bytes are byte-identical under the invariant regardless of
  which serializer produced them), and by the report's own verdict is the highest
  value-per-work item. Once C ships, B+A's fleet-wide value shrinks to first-ever
  serves — doing B+A first maximizes its apparent ROI.
- **The case for B-first (kept as the recommendation, narrowly):** C's failure class —
  silent wrong terrain, permanent on Paper without the freshness verifier — is worse
  than B's engineering risk, and the verifier design is exactly the hard part; B+A-full
  meanwhile is pure-additive (byte-identical, fallback-laddered) and removes a compat
  surface that is an ongoing incident source (Moonrise/C2ME breakage class). Ship the
  de-risked thing while the risky thing's correctness story matures — with **C-lite
  (the in-memory memo) pulled forward into step 1's window**, since it banks warm-repeat
  value with none of C's failure class.

Recommended sequence:

1. **B + A-full together** (one project: own reader feeding the streaming transcoder),
   platform-read ladder demoted to the per-chunk fallback rung. Est. LSS CPU per cold
   column ≈ −30–50%; kills the read-compat surface; unlocks common/ unit tests with
   `.mca` fixtures. The existing golden corpus + transcode-vs-object fuzz + Tier-2
   disk/live parity gametests are the byte-identity gate throughout. **Alongside, as
   week-1 side tasks: C-lite, and D's offline repeat-rate measurement** (days each, no
   dependencies — the measurement decides whether D is a tier-1 or tier-3 idea).
2. **C** (sidecar store), gated on the freshness-verifier design; Fabric save-time +
   both-platform serve-time write-through. Warm serving → file IO.
3. **v19: D (if the measurement supports it), then E, with F folded in.** The only tier
   that raises delivered col/s under an unchanged bandwidth cap.
4. **I / G / H** as gap-fillers (G and most of I are obsoleted by 1–2; sequence honestly).

## 5. Invariants this must not break

- Byte identity on the default path until a v19 deliberately changes it (goldens, fuzz
  twins, Tier-2 parity, DirtyContentFilter hash stability — a byte change invalidates
  every stored hash and every client cache; C's version stamp handles its store).
- The fallback ladder's semantics: vanilla leniency (partials → air substitution + warn),
  authoritative-miss-on-corruption (never serve a column with a silently missing
  section), `Status` gating, the light band rule, the masking pre-gate mirror.
- The AntiXray shim on every path that still touches MC serialize classes (object-path
  fallback, live path, masked path) — A-full's happy path genuinely escapes it, the
  rungs do not.
- Soak-law accounting: B changes the timeout failure class (A7's documented signatures
  assume IOWorker queueing); the catalog entries and `check_soak` expectations need a
  deliberate revisit in the same PR, not post-hoc — including a NEW gameplay-side harm
  signal (save-latency/MSPT degradation), since no existing law measures LSS harming
  vanilla IO, which is exactly B's new risk direction. B must also reproduce the
  corrupt-region triage classification (authoritative not-found containment, never
  memo-seeding error-triage misses — the A5 `d_nf` fold depends on it).
- The want-set model itself (drops heal by re-declaration) is untouched by everything
  here; D/E touch delivery bookkeeping and must preserve the grace/duplicate economy
  (D: send-success-only ref registration; E: frame-level send-failure clears).

## 6. Rough sizing

Rows are each-versus-today and **non-additive** (D's cold-CPU save is mostly the
transcode skip, which shrinks once A-full lands; G and most of I are subsumed by A-full).
D and E's wire numbers are unmeasured hypotheses pending the repeat-rate measurement.

| Project | LSS CPU (cold) | LSS CPU (warm) | Bytes/wire | Risk | Size |
|---|---|---|---|---|---|
| B + A-full | ≈ −30–50% | — | — | torn reads, RYW window, IO competition | ~2–3 wk |
| C store | — | −80–95% | — | silent-stale correctness (verifier required), disk cost | ~1–2 wk |
| C-lite (memory memo) | — | most of C within uptime | — | low | days |
| D dedup | −10–30%? (world-dep) | small | −30–70%? (world-dep) | protocol state, ref honesty | ~1–2 wk |
| E batch+zstd | small | small | −20–40%? | native dep, frame-loss bookkeeping, pass-through mixin | ~1 wk |
| A-lite alone | −5–15% (vanilla arm only) | — | — | low | ~2–3 d |
| G/H/I | −3–8% | — | — | low | days |

## 7. Inspiration review: Distant Horizons server plugin (2026-07-30)

Reviewed https://gitlab.com/distant-horizons-team/distant-horizons-server-plugin (README,
config.yml, `DhSupport`, `LodHandler`, `LodRepository`, `Lod`/`DataPoint`,
`FullBuilder`/`FastOverworldBuilder`, `WorldHandler`). DH is the opposite architectural
pole from LSS and the contrast is instructive.

**Their model in one paragraph.** The server BUILDS the client's native LOD format
(detail level 6 only: 64×64-column sections) by scanning the live world through the
Bukkit API: per column, a list of 64-bit RLE "datapoints" (mappingId 32b / height 12b /
startY 12b / skyLight 4b / blockLight 4b — one point per vertical run of identical
material, adaptive y-stepping, optional heightmap fast path), plus a per-LOD string id
map (biome+blockstate). The result is LZMA2-compressed (XZ preset 3, dict sized to
payload) and stored in **SQLite** (`lods(worldId, x, z, data, beacons, timestamp,
version)`, REPLACE INTO, async repo, trim + version migrations). Serving = load blob,
ship it verbatim in 16 KiB plugin-message frames (bufferId + isFirst). Requests carry a
client timestamp; the server responds only if newer. Edits go through a configurable
`update_events` reflection listener → `touchLod` dedup map → a 15 s refresh loop that
rebuilds and PUSHES the new blob to players within a radius. Chunk sourcing for builds
uses `loadOrGenerateChunkAsync` (server-owned generation, like v17), with a configurable
fake "dummy chunk" column when generation is off.

**What it validates in this doc:**
- **Idea C, emphatically.** DH's store isn't a cache — it is the product; warm serves
  never touch chunk data. They also live with exactly the staleness gap our review
  flagged (event-driven touch, no offline-edit detection, no freshness verifier) —
  evidence the risk is survivable in practice, though LSS's byte-parity economy sets a
  higher bar than their eventually-consistent visual data.
- **Store compressed, ship verbatim** (their strongest trick for us): the blob is
  compressed once at build/store time and the SAME bytes go to every player, every
  session — compression CPU is amortized to ~zero per serve. This merges our Ideas C+E:
  C's store should hold pre-compressed wire bytes and E's frame should carry them
  as-is. They even cache pre-compressed constant segments (generation-step arrays) —
  compress-once-serve-many all the way down.
- `material_map` (iron_ore→stone replacement at build time) is precisely our x-ray
  masking design; their per-world config overlay matches our per-world adoption.
- LSS's `PaperWorldHandler` is visibly the same design as their `WorldHandler`
  (reflection over configured event classes, same method-name probe) — the shared
  staleness holes are therefore also shared, which strengthens the C freshness-verifier
  requirement rather than weakening it.

**What to steal (new or sharpened ideas):**
- **Detail-level negotiation / far-ring downsampling (new idea, v19).** DH's wire
  protocol has detail levels as a first-class concept (even though the server only
  implements one). LSS ships full-fidelity sections to every ring; a v19 per-ring
  detail tier (e.g. 2×2×2-downsampled sections beyond ring N, ~8× fewer cells) would
  cut wire bytes where the bandwidth cap actually binds — the far rings are most of the
  area. Cons: LSSApi consumers expect `LevelChunkSection` (needs an API profile), the
  server pays the downsample, and Voxy already downsamples client-side — only worth it
  co-designed with the consumer so the work isn't done twice. Honest framing: this is
  the one idea in this doc that raises effective LOD RANGE per bandwidth dollar rather
  than CPU efficiency.
- **SQLite as C's storage engine** is a legitimate alternative to hand-rolled
  region-style files: REPLACE INTO upserts, range-DELETE trims, schema versioning, and
  mature concurrent access for free, at the cost of a bundled driver and less
  page-cache-transparent IO. Worth a real trade study in C's design phase rather than
  assuming custom files.
- **`server_key` / proxy identity**: DH lets proxied networks declare a stable identity
  so client caches survive address changes. LSS's `ColumnCacheStore` is keyed
  per-server-address and has the same multi-backend identity problem — a session-config
  field carrying an optional server identity would fix cross-proxy client cache reuse.
  Ops/UX, not CPU, but cheap and real.
- **Dummy-column fallback** (config-specified fake terrain when generation is off) —
  a UX idea for LSS's `NOT_GENERATED` parks; noted, not recommended (it fabricates
  terrain, and our session-permanent park + dirty revival is cleaner).

**What NOT to copy, deliberately:**
- **Building the client's LOD format server-side.** Their builders scan blocks through
  the Bukkit API with multi-second budgets, 4 worker threads, a 15 s refresh cadence,
  and a pregeneration queue — that cost profile is WHY they need the store and LZMA2.
  LSS's raw-section shipping keeps the server transform at microseconds/column; the
  winning hybrid is LSS's cheap transform + DH's store/compress-once economics (= C
  with compressed blobs), not their build pipeline.
- **LZMA2 at serve-path priorities**: correct for their build-once model; wrong for any
  path that compresses per serve. If C stores compressed blobs, a mid-tier codec (zstd)
  at store-write time gets most of the ratio at a fraction of the build CPU.
- **Push-data-on-update**: DH pushes rebuilt blobs to players in a radius; LSS's dirty
  NOTICE + client re-ask is pinned (want-set backpressure, client-owned wants) and
  strictly better under load — keep it.

## 8. Prior art in our own history: the pre-v0.2.0 voxel-cache architecture

Prompted by the DH comparison: LSS itself ran a DH-shaped architecture in Feb–Mar 2026,
on the `chunk-ingest` line (tag `before-loop`, branches `chunk-ingest`/`research` — never
released; main's v0.2.0 of 2026-03-11 was the fresh reconstruction that replaced it).
What it had, verified from the tree at `before-loop`:

- **Server-side voxelization** into Voxy's own 64-bit packed format — `VoxelPacking`/
  `VoxelizedSection`/`Voxelizer`/opacity table, ported from Voxy source; the client
  reverse-translated into Voxy's internal mapper IDs.
- **A three-tier serve cache**: L1 `HotColumnCache` (SoftReference in-memory map — our
  C-lite, GC-evicted); L2 `VoxelCacheService` — per-world SQLite at
  `<world>/lss-cache/voxel-cache.db`, WAL + synchronous=NORMAL + 64 MB page cache +
  256 MB mmap, a read-connection pool, a deduping write batcher, schema v9, a size cap,
  and a `content_hash` column — storing **Zstd-1-compressed wire blobs served verbatim**
  ("the server never decompresses"); L3 the region-file read. The DH trick §7 recommends
  (store compressed, ship verbatim) was fully implemented here.
- **A dirty pipeline the cache made necessary**: `ChunkMap.save` mixin →
  `DirtyHashChecker`, an off-thread worker that RE-READ the chunk from disk and
  RE-VOXELIZED it just to compare `ContentHash` against the cached hash before marking
  dirty — plus cache invalidation fan-out (L1 queue drain + L2 delete batch).
- The tracked-request protocol (requestId, token-bucket rate limiting, concurrency
  controllers, session revalidation-on-reconnect) — DH's model, and the one piece that
  DID survive into released v0.2.0–v0.7.x before v17 deleted it too.

Why it died (the 2026-02-28 raw-primitives pivot doc + the v0.2.0 commit + the
2026-03-10 decision record, plus the commit record itself — a week of "reduce hot-path
allocations"/"fix leaky abstractions" refactors, a "WORKING!" commit, a terminal tag
named `before-loop` with message "loooop"):

1. **The cache existed to amortize a transform that was then made cheap.** Voxelize +
   Zstd per column was expensive, so a three-tier cache grew around it. v0.2.0's insight
   was that MC-native `section.write` is cheap enough to serve uncached — the whole
   SQLite/WAL/pool/batcher/schema surface was compensating complexity, and deleting the
   expensive transform deleted the cache's reason to exist. A timestamp map
   (`ColumnTimestampCache`) was all that survived.
2. **The cache created its own hardest problem.** Served bytes came from the store, not
   the world, so "did this chunk really change?" needed a full re-read + re-transform +
   hash compare (`DirtyHashChecker`). Today's disk/live byte-parity invariant dissolves
   that problem — `DirtyContentFilter` hashes at the save choke point for free. That is
   the exact machinery Idea C now proposes to ride, which is why C is viable today when
   it wasn't then; C's freshness verifier (§1) is the modern, O(1)-mtime descendant of
   `DirtyHashChecker`, guarding only the paths the save hook can't see.
3. **Consumer-format coupling.** Wire AND cache blobs encoded Voxy's internal ID space —
   provenance problems, double translation, and stored data hostage to any Voxy update.
   MC-native primitives fixed wire and API; a store must likewise hold the MC-native
   bytes, never a consumer format.
4. **Complexity outran correctness.** The architecture accreted faster than it could be
   kept right; the reconstruction kept the good parts (common processing layer,
   `DedupTracker`, off-thread pipeline) and dropped the rest.

Implications for this doc, stated plainly: the history does not argue against Idea C —
it argues against C's 2026-02 CONDITIONS, all of which have since inverted (transform
now cheap, dirty hashing now free at the save hook, format now consumer-neutral). What
it does supply: the SQLite implementation (pragmas, pool, batcher, schema) exists at
`before-loop` as a working reference if C's trade study picks SQLite; `HotColumnCache`'s
SoftReference eviction is a known-bad choice for C-lite (GC-pressure eviction is
erratic — use a bounded LRU); and the burden of proof sits on C to stay SMALL — the
moment the store needs its own verification pipeline, that is the 2026-02 failure mode
recurring.

## 9. Review round (2026-07-30)

Two-agent review, findings incorporated above. Load-bearing corrections: A-lite's
read-your-writes is a pick-one with BACKGROUND priority (IOWorker.scanChunk submits
FOREGROUND; storage.scanChunk skips pendingWrites); Idea C's "already paid" save-time
bytes are post-mask and produced inside DirtyContentFilter's synchronized monitor; the
Paper store-hit stale-forever hole (unfired `updateEvents` + offline edits) promoted the
freshness verifier to a hard requirement; B+A-full's estimate trimmed to −30–50% (inflate
parallelization is a throughput property, not CPU-per-column); memo key cost corrected
⅓→⅕; concurrent multi-player serving already dedups via `DedupTracker` (C's win is
time-separated repeats). Additions from review: C-lite stage 1, the
`RegionDataController.readData` middle road on Paper, MSPT-keyed throttle as part of B's
v1 + a gameplay-harm soak signal, D's ref honesty (send-success-only registration,
ref-miss re-ask), E's frame-level send-failure clears, and the sequencing counter-case
for C-first (kept B-first, narrowly, on failure-class grounds).
