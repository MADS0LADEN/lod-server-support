# Compressed columns (CAPABILITY_ZSTD_COLUMNS) — implementation plan

Status: **IMPLEMENTED** on `feat/compressed-columns` (2026-08-01, all four phases —
see `compressed-columns-progress.md` for the executed record incl. the gate
verdicts: warm G1 −43% CPU/col, cold −27%, wire ×1.114, all soaks green).
2-agent review round folded (§9). Develops `compressed-columns-design.md`
(2026-08-01) into phased, file-level work. Origin: the elytra chunk-wall investigation
measured the serve path compressing twice (store zstd decompress → netty zlib deflate)
and burning deflate CPU over raw bytes the store already holds compressed.

Protocol: **18 → 19**. Capability: `CAPABILITY_ZSTD_COLUMNS = 0x2`. Config:
`useCompressedColumns` (server, shared base, default true). Store: schema bump
(`fhash` column) → drop-and-rebuild per the derived-data convention.

**What this buys, stated honestly (review finding B1):** the win is **CPU, not bytes**.
The repo's own Phase 0 codec table (`lod-store-progress.md`) has deflate-6 at 4,776 B/col
vs zstd-1 at 5,342 B/col on the same corpus — vanilla's netty compressor is deflate-6, so
shipping zstd frames costs ~**+5–12% wire bytes** while removing ~**500 µs/col** of
deflate+decompress from the server serve path (~12–17% of whole-process CPU on a warm
serve workload) and a similar inflate cut client-side. The design doc's §1 premise
sentence ("the zstd frame … is smaller than the deflate output we ship") was wrong and
is corrected there. Near-parity wire would need dictionary training (design §7, future).

## 0. Corrections and decisions beyond the design doc

Grounding the design in the code surfaced these; each is a decision this plan carries:

1. **The store's integrity model conflicts with "ship the frame verbatim, zero
   validation".** `SqliteLodStore.get()` verifies `chash` (FNV-1a of the RAW bytes)
   *after* decompressing — the check is what feeds the row-poison purge ladder
   (`DeleteRows` on integrity throw). Serving the frame without any check would let a
   bit-rotted row loop forever through client decode-failure → re-declare → same frame
   (bounded only by `ColumnStateMap.MAX_INGEST_FAILURES` parking the position stale for
   the session, with the corrupt row never purged). Decision: add an **`fhash` column**
   (FNV-1a of the COMPRESSED blob, computed at deposit, ~2-3 µs over ~5 KB) and a
   `getFrame()` path that validates `usize` bounds + zstd declared-content-size ==
   `usize` + `fhash` — bit-rot detection parity with today, no decompress. Schema bump
   ⇒ drop-and-rebuild (convention: derived data, never migrated; release-noted — the
   warm store re-warms from serves/backfill once).
2. **The compat matrix line "v18/v17 pairs ⇔ no session" is wrong today and stays
   wrong under v19 — in a good way.** A version-mismatched handshake gets NO reply;
   the client's v16 discovery timer then re-handshakes as 16, and any v0.7+ server's
   `enableV16Compat` shim serves a v16-dialect session. So v19 client ↔ v18 (v0.8.x)
   server = degraded v16 session (raw, drip-feed), NOT no-session — same as today's
   v18↔v17 pairing. No new work; the plan and release notes state the matrix honestly.
3. **Compression decisions are per-recipient, so they belong at payload build, not at
   send-flush.** `buildAndEnqueueColumnPayload` runs per recipient on the processing
   thread (dedup fan-out calls it once per attached player); the send flush is
   main-thread. All codec choice + compression happens at build; the flush and the
   v16 egress seams (`asV16()` on Fabric, the Paper splice) never touch a frame.
4. **Compress once per RESULT, not once per recipient.** A drained result fans out to
   N dedup recipients and one store deposit. Introduce a small thread-confined holder
   (`ColumnBytes`: raw and/or frame + rawSize, lazily converting in either direction,
   memoized) created once per drained result on the processing thread and passed to
   every recipient build and to the deposit. Mixed-capability fan-out decompresses a
   store frame lazily at ~24 µs/col only for raw-needing recipients (v16 /
   no-capability — rare). Accepted exception (review A7): the PROBE path has no dedup
   group — two players wanting the same loaded chunk build independent holders over
   the same `LoadedColumnData` and compress twice. Accept at first; if Phase 4 JFR
   shows it hot, memoize the holder on `LoadedColumnData` for the cycle.
5. **The decode-queue byte gauge keeps a raw-work denomination WITHOUT trusting the
   header (review A2).** The client's `queuedBytes` gauge feeds the backpressure-halt
   threshold and the scanner's pressure factor, calibrated in raw-work units — but the
   byte cap's other job is hostile-retention bounding (`MAX_QUEUED_BYTES`: "the count
   cap alone admits 16 GiB"), and admission runs at OFFER while the bomb guard runs at
   DRAIN. Charging a lying tiny declared size would re-open exactly that hole (2 MiB
   resident, ~0 charged). Decision: a codec-1 offer charges
   `max(shippedLength, clamp(declaredContentSize, 0, MAX_SECTIONS_SIZE))`; codec 0,
   unknown codecs, and unparseable headers charge `shippedLength`. Honest frames are
   unaffected (declared ≥ shipped whenever compression was worth shipping), so the
   pressure calibration keeps its raw denomination; a hostile header can no longer
   under-charge below resident bytes nor drive the gauge negative. The charge is
   stored in `QueuedColumn` at offer and every drain/clear path releases exactly the
   stored charge — never re-derived.
6. **Soak probe hashes record RAW bytes, pinned.** `SoakProbeBridge.recordServed` at
   the build choke hashes "the EXACT wire bytes" today; under compression it must keep
   hashing the raw serializer output or `store_offline_edit.sh`'s cross-phase
   probe-hash verdict and the store byte-parity gates break on encoding, not content.
7. **Unknown codec byte is contained at the DRAIN, not the payload read.** The read
   accepts any codec value (it does not change how *this* payload's remaining bytes
   are read — the section array is length-prefixed either way); the
   `ClientColumnProcessor` drain rejects codec ∉ {0,1} through the ingest-failure
   path. This matches design §4's intent while keeping hostile handling out of the
   receive path; the re-serve loop is bounded by the ingest-failure park.
8. **0-section clears are always codec 0, pinned once in common (review A4).**
   `sendEmptiedColumn`'s 1-byte body is far below the compress threshold and lives in
   `OffThreadProcessor` — ONE pin covers both platforms. Thread attribution corrected:
   `isClearColumn` runs on the MAIN client thread (`handleVoxelColumn` inside
   `client().execute`), not netty — but a decompress there would be nearly as bad, so
   the codec-0 gate stands (codec 1 ⇒ not-a-clear, fail-safe; only a non-compliant
   server can ship a compressed clear, and it still decodes correctly at the drain,
   minus the clear/resync flag). What DOES run on the netty thread is
   `sessionGate.recordColumnFrame(payload.estimatedBytes())` — so the payload
   **memoizes its declared raw size at `read()` time** (it is consulted at least
   twice: netty metric + offer charge), and `estimatedBytes()` stays raw-denominated.
9. **`WireDialect.V18` renames to `WireDialect.CURRENT`** (one enum constant, both
   platforms' call sites) so the next bump stops renaming the dialect.
10. **`StoreCodec` becomes the shared wire+store codec.** The client probe reuses
    `StoreCodec.zstdOrNull()` (zstd-jni is already nested in the Fabric jar with
    linux/win/mac × x64/arm64 natives — the desktop client matrix is covered; probe
    failure ⇒ don't declare the capability, one log line). It grows
    `declaredContentSize(byte[])` for the bomb guard and gauge charge. The store-frame
    == wire-frame invariant is then structural: both sides call the same
    `compress(raw)` at level 1.
11. **The SERVER needs the same native probe as the client (review A1 — MAJOR).**
    zstd-jni publishes no musl natives and "musl servers are common"
    (fabric/build.gradle's own words); a natives-less server with the default-on
    config and a capable client would throw `UnsatisfiedLinkError` at every
    `ColumnBytes.frame()` build → per-delivery containment converts each to
    superseded + an error line → the client re-declares ≤1 s later → forever, for
    every column. Nothing would degrade to raw. Decision: the service latches a
    server-side codec at start (one `StoreCodec.zstdOrNull()`; null ⇒ capability
    ignored, codec 0 for everyone, one WARN) — a term of the session flag alongside
    config and dialect. Independent of the store's own probe (a `lodStore=off` server
    never probes otherwise).

## 1. Phase 0 — premise measurement (no product code)

Before wiring anything, measure on the existing corpora (the golden cross-module byte
corpus + a real-region sample via the `profile_disk_read.sh` world), in the style of the
store's Phase 0 (`LodStoreExperimentTool`):

- **Per-column CPU**: `deflate-6(raw)` (vanilla's netty level) vs `zstd-1(raw) +
  deflate-6(frame)` vs store-hit `verbatim + deflate-6(frame)`; `zstd
  decompress(frame)`; client-side `inflate` at both sizes.
- **Per-column WIRE bytes** (review B1): `deflate-6(raw)` output size vs zstd-1 frame
  size + its deflate wrap — this derives G3's ceiling instead of guessing it. Expected
  from the store table: ON ships ~+5–12%.
- **Threshold**: pick `COLUMN_COMPRESS_MIN_BYTES` from the corpus size distribution
  (design guess 256–1024; measure where the frame stops winning). Constant, not
  config. Also decide the codec-0 fallback rule for incompressible input (§2).
- **Frame header sanity**: assert `Zstd.getFrameContentSize` returns the exact raw size
  on frames our `Zstd.compress(raw, 1)` produces (the bomb guard and gauge depend on
  single-shot frames always carrying content size; zstd-jni 1.5.7-3 exposes it —
  javap-verified in review).
- **Derive G1's margin `m`** from the measured deflate-6 removal against the warm-arm
  whole-process baseline (provisional expectation ~12–17%; the gate floor stays 0.10
  unless the measurement says otherwise — never gate on hope).

Deliverable: a numbers table appended to `compressed-columns-design.md` §9, the
threshold constant, and the derived G1/G3 values for §5.2.

## 2. Phase 1 — protocol v19 + capability + live-path compression

No store coupling yet: store hits keep decompressing in `get()` and re-compress at
build like any other serve. This phase alone already removes the big cost (netty
deflate over raw bytes) from EVERY serve path and lands the whole wire/compat surface.

**common/**
- `LSSConstants`: `PROTOCOL_VERSION = 19`; `CAPABILITY_ZSTD_COLUMNS = 0x2`;
  `COLUMN_CODEC_RAW = 0` / `COLUMN_CODEC_ZSTD = 1`; `COLUMN_COMPRESS_MIN_BYTES`
  (Phase 0 value).
- `ServerConfigBase`: `useCompressedColumns` default true (rollback lever à la
  `useNbtTranscode`; false ⇒ codec 0 for everyone, capability ignored).
- `StoreCodec`: add `declaredContentSize(byte[])`; javadoc the dual store/wire role.
- `HandshakeGate`: rename `WireDialect.V18` → `CURRENT`. The gate itself is
  capability-agnostic (bit 0x2 never changes the ladder) — pinned by test.
- New `processing/ColumnBytes` (or nested in `OffThreadProcessor`): thread-confined
  raw↔frame holder; `frame()` compresses raw at zstd-1 memoized; `raw()` decompresses
  memoized; `rawSize()`; factory `ofRaw(byte[])` (+ `ofFrame` in Phase 2). `frame()`
  returns null (⇒ codec 0) below `COLUMN_COMPRESS_MIN_BYTES` AND when the frame did
  not shrink the input (`frame.length >= raw.length` — review A5: zstd worst case is
  raw + raw/255 + header, so "the frame is strictly smaller" is not a given for
  incompressible input; the fallback also ships strictly better bytes).
- `OffThreadProcessor`: per-player session flag `wantsCompressedColumns`, ANDed from
  FOUR terms — `(capabilities & CAPABILITY_ZSTD_COLUMNS) != 0`, config, **the
  server-side codec latch (§0.11)**, and NOT-v16. Mechanism for the dialect term
  (review A3 — neither platform's registration carries the dialect today): consult
  the platform's v16 tracker at registration time (`v16Compat.isV16(uuid)` on both —
  ordering verified safe: both platforms mark the manager BEFORE `registerPlayer`,
  and dimension-change re-registration re-derives; Paper's deferred-reply path sets
  the flag in `registerPlayer` before the reply `Runnable` runs, so no serve precedes
  it). Create ONE `ColumnBytes` per drained result at the three delivery sites (disk
  drain, generation delivery, loaded/probe path) and thread it through
  `buildAndEnqueueColumnPayload` (signature change: holder + source replace the bare
  `byte[]`). `sendEmptiedColumn` forces raw (the common pin, §0.8). `estimatedBytes`
  stays `rawSize + ESTIMATED_COLUMN_OVERHEAD_BYTES` (limiter semantics — design §5).
  `MAX_SEND_SECTIONS_SIZE` guard checks `rawSize` (unchanged meaning). New
  diagnostics counters `wire_bytes` (shipped payload size, counted at send success in
  the flush) + `columns_compressed`/`columns_raw`.
- `SharedBandwidthLimiter`: untouched (decided: raw charging).

**fabric/**
- `VoxelColumnS2CPayload`: new `codec` byte + memoized declared-raw-size (computed
  once at `read()` — §0.8); wire layout `cx, cz, dim, ts, source, codec,
  byteArray(shipped)` — codec appended after source, so the v18→v19 diff is one byte.
  `write` skips source AND codec for `v16Wire`; `read` skips both when
  `V16ClientWire.isColumnSourceless()`. `estimatedBytes()` returns raw-denominated
  size (audit its callers — R7). `decompressedSections()` renames to
  `shippedSections()` + `codec()`; the drain owns decompression. `asV16()` gains a
  codec-0 assert (review A6: Fabric's v16 egress converts unconditionally at the
  flush; a codec-1 payload leaking to a v16 session is hard-kick class — assert +
  warn-drop, symmetric to the Paper splice guard).
- `FabricOffThreadProcessor.buildAndEnqueueColumnPayload`: choose codec per recipient
  from the holder + session flag; `SoakProbeBridge.recordServed` keeps receiving RAW
  bytes (pin).
- `LSSClientNetworking`: handshake sites (session gate lambda + `triggerHostHandshake`)
  declare `CAPABILITY_VOXEL_COLUMNS | (zstdAvailable ? CAPABILITY_ZSTD_COLUMNS : 0)`
  via a lazy `StoreCodec.zstdOrNull()` holder (one log line on probe failure). The v16
  re-handshake path keeps declaring too — harmless, the dialect term ignores it.
  `isClearColumn` call gated on codec 0.
- `ClientColumnProcessor`: `offer` charges per §0.5 (max/clamp rule, stored in
  `QueuedColumn`); `drainColumnQueue` decompresses inside the existing
  Throwable-contained try — bomb guard first (declared ∈ (0, MAX_SECTIONS_SIZE]),
  exact-size `StoreCodec.decompress`, then the unchanged `decodeSections`; codec ∉
  {0,1} or any zstd throw ⇒ ingest-failure report, drain continues.
  `reportAndClearBacklog` / `shutdown` / `reportUndispatched` release the stored
  charge.
- `ClientSessionGate` / v16 client shim: no change (v16 columns are sourceless AND
  codec-less; the read-path gate covers both bytes).

**paper/**
- `PaperPayloadHandler.encodeVoxelColumnPreEncoded`: codec-byte overload, layout
  identical to Fabric (wire-parity fixtures). The v16 SPLICE now removes exactly TWO
  bytes (source + codec) from a CURRENT-shaped frame — and the Paper egress for v16
  sessions only ever splices codec-0 frames (the session flag guarantees raw;
  assert + pin).
- `PaperOffThreadProcessor`: same holder/flag plumbing as Fabric.
- `LSSPaperPlugin`: registration passes capabilities through as today; the dialect
  term resolves via the v16 manager at registration (§ above) — `LifecycleEvent
  .Register` does NOT need to carry the dialect.

**Phase 1 tests (Tier 1, both platforms)**
- Codec round-trip goldens: compressed twins generated from the existing raw corpus;
  Fabric↔Paper wire parity for codec-1 frames; v19 layout goldens.
- Bomb-guard trio: declared-size lie (over-cap), declared-vs-actual mismatch,
  truncated frame — all report ingest-failure, drain survives.
- Offer-charge rules (§0.5): lying-tiny header charges shipped length; negative /
  unknown declared size never decrements the gauge; charge/release symmetry across
  drain, clear, shutdown, reportUndispatched for codec 0 and 1.
- Handshake ladder with bit 0x2 × `useCompressedColumns` on/off × dialect (incl. the
  v16-with-0x2 exclusion); gate-unchanged pin.
- **Server codec latch seam** (§0.11): injected unavailable codec ⇒ raw session for a
  capable client, one WARN, no per-column errors.
- Clears always raw (ONE common pin); threshold edge + non-shrinking-frame fallback
  (at/below `COLUMN_COMPRESS_MIN_BYTES` or frame ≥ raw ⇒ codec 0).
- Unknown codec byte containment; `isClearColumn` codec gating.
- Dedup fan-out: mixed capable + non-capable recipients get codec 1 / codec 0 from ONE
  holder; compression ran once (holder memo pin).
- v16 egress: Fabric `asV16()` strips both bytes AND asserts codec 0; Paper splice
  removes exactly two + codec-0 assert; v16 recipients always receive raw.
- Probe-hash-raw pin; `DirtyContentFilter` unaffected (gen-serve seeding happens on
  serializer output, upstream of the holder — one structural pin).
- Client capability probe seam (probe fails ⇒ bit undeclared).
- `WantSetBudgetInvariantTest` etc. — no constants moved; run the suites.

## 3. Phase 2 — store frame serving (the verbatim path)

- `SqliteLodStore`: schema bump (`fhash INTEGER NOT NULL` in `lods_<dim>`; meta
  `schema` value bump ⇒ existing drop-and-rebuild). `applyDeposit` computes/stores
  `fhash`. New `getFrame(dim, packed)` → `FrameHit(byte[] frame, int usize, long
  ts)`: tombstone check, `usize` bounds, `declaredContentSize(frame) == usize`,
  `fnv1a(frame) == fhash`; integrity throw takes the existing row-poison purge ladder;
  `usize == 0` returns the all-air shape exactly like `get()`. `get()` (raw) remains
  for every other caller.
- `LodStoreService`: `getFrame` default null (⇒ callers fall back to `get()`);
  `MemoryLodStore` returns its resident frame — deliberately unvalidated (review A8:
  session-lifetime RAM, accepted; a step below its current round-trip length check,
  stated here so it isn't rediscovered).
- **Deposit-frame-reuse, decided shape (review A9)**: the compressed-session deposit
  carries the FRAME plus `chash` and `usize` computed on the processing thread (raw
  is in hand there) — never both raw and frame, which would roughly double the
  bounded deposit queue's memory. Raw-only sessions deposit raw as today (writer
  compresses + hashes). R3's corruption-window note moves accordingly: the raw hash
  is now computed at delivery time on the processing thread for compressed sessions —
  same exposure class, different thread.
- `AbstractChunkDiskReader.storeServedHit`: fetch via `getFrame`; `ChunkReadResult`
  carries the frame (new nullable `frameBytes` + `rawSize` alongside `sectionBytes` —
  exactly one of the two set for data results). The rung contract (hits excluded from
  `disk.*` and the throttle EWMA) is untouched.
- `OffThreadProcessor` delivery: `ColumnBytes.ofFrame(frame, rawSize)` for store
  hits; raw materializes lazily only for raw-needing recipients. Deposit skip for
  `fromStore` results unchanged. **The `rawSize` guard is load-bearing here** (review
  A5): `getFrame`'s bound is `MAX_ROW_USIZE` (16 MiB) and deposits are deliberately
  not gated on send success, so a frame hit with `usize > MAX_SEND_SECTIONS_SIZE`
  must refuse at build exactly as an oversized raw hit does today.
- Tests: `DiskReaderStoreRungTest` + Paper twins drive frame hits end-to-end;
  fhash bit-rot ⇒ purge + NBT fallback; store-frame==wire-frame identity pin (the
  deposited frame IS the shipped frame object/bytes) + decode-equivalence golden;
  deposit-frame-reuse pin (writer compress skipped; chash/usize arrive precomputed);
  oversized-usize frame hit refuses at build; mixed fan-out decompress-once pin;
  schema-drift rebuild test extends the existing family; all-air frame hit.

## 4. Phase 3 — observability

- `/lsslod diag` + `DiagnosticsFormatter`: `wire_bytes`, `cols_zstd/cols_raw`
  (golden-line updates both platforms).
- Benchmark exporters (Fabric + client): `wire_bytes`, compressed/raw counts.
- Soak exporters (Fabric + Paper twins): `service.wire_bytes` — added to
  `check_soak.py`'s `SERVER_MONOTONIC` whitelist, which automatically makes it
  required (`GLOBAL_SERVER_FIELDS`) and A6-monotonic — the same class as
  `service.bytes_sent` (review B6: NOT the report's mechanism dict; byte-volume
  counters deliberately live in neither lens dict). Selftest fixtures gain the field.
  Client snapshot: `wire_received_bytes` added to `KNOWN_CLIENT_KEYS` (else it lands
  in the unknown-key warning).
- The counted `wire_bytes` vs the sampler's socket `bytes_acked` cross-check lives in
  the Phase 4 gate script, not the checker (the socket also carries vanilla traffic).

## 5. Phase 4 — validation

### 5.1 Correctness

- Full Tier 1 + Tier 2 (`:fabric:build -x runClientGameTest`, `:paper:test`).
- Tier 3: the existing decoded-content assertions now run over a compressed session by
  default (dev client has natives). Add two explicit pins so the test cannot silently
  pass raw: the session negotiated 0x2, and ≥1 codec-1 column was received (client
  counter).
- Soak: `fresh-backfill`, `warm-rejoin`, `store-second-join(+-full)`,
  `dirty-broadcast` on Fabric; the four Paper scenarios via `SOAK_PLATFORM=paper` —
  all with the default-on config, plus ONE kill-switch A/B of `store-second-join`
  (`"useCompressedColumns": false` scenario config) pinning byte-parity of decoded
  content across the flag **and an `rtt.p50/p95` parity rider** (review B7a: Phase 1
  moves ~50 µs/col of compress onto the single processing thread; no MSPT gate exists
  in these harnesses by design, and serve-latency stretch is exactly what the
  client-snapshot RTT fields see). `store_offline_edit.sh` must stay green (probe
  hashes raw).

### 5.2 CPU-reduction proof (the headline gate)

New `scripts/compress_gate.sh` + `compress_gate_check.py`, cloned from the
`store_gate.sh` discipline: **interleaved same-box A/B arms** flipping ONLY
`useCompressedColumns` (one rep = off-arm + on-arm back-to-back; never compare across
days/box-states — the A7 lesson), N ≥ 3 reps, `proc_sampler.sh` attached (1 Hz
`srv_cpu`/`cli_cpu` jiffies + socket `bytes_acked`), JFR pairs kept.

**Premise pins (review B4)** — the OFF arm must actually pay deflate:
`compress_gate.sh` stages `network-compression-threshold=256` explicitly (today it
holds only by vanilla default), and `compress_gate_check.py` asserts the premise per
OFF-arm run: sampler `bytes_acked` delta ≪ counted raw bytes (ratio ≳ 4:1) — a
compression-disabled server fails loudly instead of silently gutting G1.

**Tooling prerequisite (review B2)**: `analyze_profile_jfr.py` requests only
`jdk.ExecutionSample` — time in native methods (`Deflater.deflateBytes`, zstd-jni) is
`jdk.NativeMethodSample` and currently invisible (the store gate measured the `zip`
band at 0.5% while deflate-6 provably costs ~15-20% of the serve path). Add
`jdk.NativeMethodSample` to its event list and band attribution (the `zip` band
prefixes already cover `java.util.zip.` + `com.github.luben.zstd.`). The
already-collected `jdk.ThreadCPULoad` per thread group is the zero-change fallback.

Arms:
- **warm** (`warm-join` scenario, distance 96 — the converging disc store_gate
  already calibrated): store-hit serving, the biggest win.
- **cold** (`no-cache`): disk/live serves — zstd+small-deflate replaces big-deflate.
- **fresh**: report-only rider (generation CPU drowns the serve delta; no gate).

Gates — all evaluated over the sampler-defined active-delivery window, normalized per
**`client.json` `columns_received`** (review B8: the exact denominator the cloned
store-gate math uses; its cross-arm parity is G4), decided by **median across reps AND
per-rep majority** (review B3: the store gate's rule, stated here explicitly — the
measured single-rep spread on this box is −1.2%…+10.3%, the size of the whole margin):
- **G1 server CPU/col (warm)**: ON ≤ OFF × (1 − m), m from Phase 0 (provisional
  0.10; expectation ~12–17% from the deflate-6 table). A miss is stop-and-investigate.
- **G1t targeted leg (gated)**: netty server-IO thread-group CPU/col (ThreadCPULoad)
  — or the NativeMethodSample-corrected `zip` band — drops on the ON arm. This is the
  noise-immune leg that makes a G1 red diagnosable (the analog of the store gate's
  addressable-CPU leg); whole-process G1 alone cannot say *where* a regression lives.
- **G2 client CPU/col**: ON ≤ OFF × 1.05 — a non-regression guard only (review B5:
  the ~30-40 µs/col client delta is invisible against a whole client process;
  the client *evidence* is the client-JFR ThreadCPULoad for the netty-client-IO +
  `-ColumnProcessor` thread groups, report-only).
- **G3 wire bytes (recalibrated — review B1)**: sampler `bytes_acked` delta ON ≤ OFF ×
  (ceiling from Phase 0's wire measurement; expected ~1.05–1.12 — the ON arm ships
  MORE socket bytes, that is the accepted trade). Plus the counted `wire_bytes` ≈
  shipped sizes cross-check (within slack for zlib framing + vanilla traffic).
- **G4 riders**: `columns_received` parity across arms (else per-col normalization
  lies), `not_generated == 0`, `compress_gate_check.py` green on both arms and the
  §5.1 soak verdicts green (review B9's clarification: "checker-green" means those
  two — benchmark runs produce no `check_soak.py` verdict).
- **Report-only**: `srv_rss`/`cli_rss` across arms (holder dual-residency — review
  B7c); fresh-arm numbers; JFR band diffs.

**Paper (review B7b)**: `compress_gate.sh` rides `benchmark.sh` = Fabric-only, so the
Paper CPU claim is *inferred* from the shared common path plus Paper soak health —
stated, not measured. Optional report-only: attach `proc_sampler.sh` via its
documented `PROC_SAMPLER_SRV_PATTERN` override to one Paper `store-second-join`
kill-switch A/B.

### 5.3 Live acceptance

Deploy to the tracked Modrinth test server: `/lsslod diag` `wire_bytes` should now
match the panel's observed bandwidth (killing the §1 confusion — the concrete
user-visible deliverable); store status + a warm rejoin eyeball. An elytra re-fly is
informative but NOT a gate — the chunk-wall fix is per-player flow control, out of
scope (design §5).

## 6. Rollout & release notes

- Default ON at ship; `useCompressedColumns: false` is the rollback lever (codec 0
  for everyone, capability ignored).
- Release notes: performance item — server CPU drop (numbers from 5.2) **at the cost
  of ~+5–12% network bytes** (review B1: never promise a "wire drop"; the honest
  pitch is less CPU on both ends for slightly more bandwidth); config item; the store
  one-time rebuild on upgrade (schema bump — backfill marks reset, Fabric backfill
  re-walks); the honest compat matrix (item 0.2), incl. that older clients/servers
  pair through the v16 shims at reduced fidelity; VSS wire surface covered for free
  (`release_check.py` wire-identity pin).
- Protocol coordination: this takes 19. If the serve-path brainstorm's v19-dedup ever
  lands it takes 20 — do NOT hold this bump hostage to that design (design §9.3
  resolved: independent).

## 7. Risks

- **R1 double compression** (netty zlib over zstd frames): the frame still exceeds
  the 256 threshold so netty deflates it — small CPU on ~5 KB, ~+0.1% size; G1
  measures whole-process so it's priced in. Vanilla's knob, not ours.
- **R2 missing natives — BOTH sides**: client probe ⇒ no declaration; server latch
  (§0.11) ⇒ capability ignored, raw sessions, one WARN. Musl servers are the known
  live case. Both probe seams tested.
- **R3 deposit-time corruption passing fhash** (frame corrupted before hashing):
  client decode fails, ingest-failure re-serves, `MAX_INGEST_FAILURES` parks — bounded
  but sticky for the session; server cannot distinguish. Accepted (same exposure class
  as a pre-existing raw-bytes corruption today; for compressed sessions the raw hash
  is computed on the processing thread — §3).
- **R4 hostile header distortion**: closed by the §0.5 charge rule — resident bytes
  are always charged at ≥ shipped length; declared lies are clamped to
  `MAX_SECTIONS_SIZE` and caught at decode.
- **R5 store rebuild on upgrade**: one-time warm-store loss; release-noted; backfill
  re-walks (its restraint gates make that cheap).
- **R6 processing-thread costs**: compress ~50 µs/col on live serves; decompress
  ~24 µs/col only for raw-needing recipients of store frames (v16/no-capability —
  rare; ~2% of a core at 700 col/s worst case). Watched by the §5.1 RTT rider.
- **R7 `estimatedBytes()` audit**: every caller of the payload's size accessors must
  keep raw-denominated semantics (limiter, netty-side `recordColumnFrame`, client
  metrics) — an explicit Phase 1 audit item, not an assumption.
- **R8 wire-bytes regression** (~+5–12% vs deflate-6): the accepted trade, bounded by
  G3's measured ceiling; the future recovery lever is dictionary training (design §7),
  out of scope.

## 8. Design-doc open questions — resolved

1. Threshold: Phase 0 measures; constant `COLUMN_COMPRESS_MIN_BYTES` (+ the
   non-shrinking-frame codec-0 fallback).
2. X-ray mask ordering: structural — masking lives inside the serializers, upstream of
   the holder; compression always operates on masked raw bytes. One pin test.
3. Shared protocol bump: independent; this takes 19 (see §6).
4. (New) Design §1's wire-size premise corrected in the design doc — see the header
   note and review finding B1.

## 9. Review round (2026-08-01, two agents)

Agent A (correctness/protocol/compat, 42 tool-verified checks) and Agent B
(performance-claim validity / test adequacy) reviewed the v1 plan. All findings folded:

- **A1 MAJOR** server-side zstd probe missing → §0.11, flag term, seam test, R2.
- **A2 MAJOR** declared-size offer charge re-opened the hostile-retention hole →
  §0.5 max/clamp rule, Phase 1 charge tests, R4.
- **A3** dialect unavailable at registration on both platforms → mechanism pinned
  (v16-manager lookup at registration; ordering verified safe).
- **A4** isClearColumn thread attribution wrong; netty-side `estimatedBytes()` needs
  the memoized declared size → §0.8.
- **A5** "frame strictly smaller" false for incompressible input; store-frame hits
  need the `MAX_SEND_SECTIONS_SIZE` refuse (MAX_ROW_USIZE is 16 MiB) → §2 holder
  fallback, §3 guard + test.
- **A6** Fabric `asV16()` needed the codec-0 assert Paper had → §2 + test.
- **A7** probe-path double compression accepted explicitly → §0.4.
- **A8** MemoryLodStore.getFrame unvalidated, accepted explicitly → §3.
- **A9** deposit-reuse shape decided: frame + precomputed chash/usize, never both
  arrays → §3.
- **B1 MAJOR** wire-size premise backwards (deflate-6 4,776 vs zstd-1 5,342 B/col) →
  header note, G3 recalibrated, §6 release-notes fix, design-doc correction.
- **B2 MAJOR** JFR analyzer blind to native deflate (`NativeMethodSample` not
  requested) → §5.2 tooling prerequisite + ThreadCPULoad fallback.
- **B3 MAJOR** G1 margin at the documented noise floor → explicit median+majority,
  gated targeted leg G1t, Phase-0-derived m.
- **B4** netty-compression premise unpinned → staged threshold + per-run premise
  assert.
- **B5** G2 per-column client normalization noise-dominated → non-regression guard +
  JFR thread-group evidence.
- **B6** soak-report wording wrong (mechanism dict is hand-maintained and the wrong
  class; client key needs KNOWN_CLIENT_KEYS) → §4 reworded.
- **B7** missing dimensions: processing-thread RTT rider (§5.1), Paper-inferred
  statement + optional sampler attach (§5.2), RSS report-only (§5.2).
- **B8/B9** G1 denominator = `columns_received`; "checker-green" clarified.

Verdicts: A — "with findings 1 and 2 folded in, ready to implement"; B — "with
findings 1–3 folded in, §5.2 becomes a gate that would genuinely prove the CPU
reduction and honestly report the wire trade."
