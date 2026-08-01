# End-to-end zstd column compression (CAPABILITY_ZSTD_COLUMNS)

Status: PLANNED (not implemented). Origin: the 2026-08-01 elytra chunk-wall
investigation (`elytra-chunk-wall-investigation-2026-08-01.md`) surfaced that the
serve path compresses twice and ships worse bytes than it already holds.

## 1. Problem

Column section bytes cross the wire as **raw** `section.write(buf)` output; the only
compression is vanilla's connection-level per-packet zlib (threshold 256). Meanwhile
the LOD store already holds every stored column as a **zstd-1 frame**. A store-hit
serve today therefore does:

```
SQLite row (zstd frame) → zstd DECOMPRESS → raw payload → netty zlib DEFLATE → wire
```

Two compression passes per served column, both discarded value: the zstd frame we
started with is smaller than the deflate output we ship. Measured on the live server
(2026-08-01, real overworld terrain, 26.2): raw:wire ratio through connection zlib is
**~6–7:1** (5 MiB/s counted ≈ 6 Mbps observed; 2.96 GB counted over 2m23s during the
incident ≈ ~4 MB/s on the wire). zstd-1 on the same corpus achieves equal or better
ratios at a fraction of the CPU (store Phase 0 measurements), and for store hits the
compression cost is **already sunk**.

Secondary irritation, same root: the bandwidth limiter charges raw bytes
(`sectionBytes.length + ESTIMATED_COLUMN_OVERHEAD_BYTES`), so the configured cap is
~6–7× the observed network utilization — confusing enough that it derailed the
incident analysis for a round (see investigation doc §5).

## 2. Design summary

A negotiated capability lets the server ship the zstd frame **end-to-end**:

```
SQLite row (zstd frame) ──────────── verbatim ────────────→ wire → client off-thread decompress
live/disk/gen serve → zstd-1 compress ON PROCESSING THREAD ↗        (ClientColumnProcessor)
```

- **Capability bit**: `CAPABILITY_ZSTD_COLUMNS = 0x2` in the existing
  `HandshakeC2SPayload` capabilities bitmask (bit 0 is `CAPABILITY_VOXEL_COLUMNS`).
  The client declares it only if its zstd native actually loaded (probe at init —
  same containment philosophy as the store natives: load failure ⇒ don't declare,
  session runs raw, one log line).
- **Protocol bump 18 → 19.** The column payload gains a leading **codec byte**
  (`0 = raw`, `1 = zstd frame`) for capability sessions. Precedent and rationale are
  identical to the 17→18 source-tag bump: a mismatched pair must fail safe (silent
  no-session) rather than misalign the column decode by one byte. The capability bit
  alone cannot carry this — an old server ignores unknown bits and would ship the
  old layout to a client expecting the new one. Version agreement guarantees layout;
  the bit carries *ability* (a v19 client without natives simply doesn't declare it).
- **Per-column codec byte**, not per-session: lets the server skip compression for
  tiny columns (0-section authoritative clears, sub-~512 B columns where the frame
  header wins nothing) and preserves a per-column fallback if a stored frame ever
  fails validation — mirroring the per-section fallback ladder philosophy of the NBT
  transcode.

## 3. Server path

1. **Store hits** (the common warm path): ship the stored frame **verbatim**. Zero
   decompress, zero compress. This requires the invariant **store frame == wire
   frame** — the store already writes single zstd frames of exactly the raw
   sectionBytes, so the invariant is free today; it gets a golden pin so it stays
   free (§8). Decode never depends on the compression level, so a future store-level
   change does not force recompression.
2. **Live/disk/generation serves**: compress zstd-1 on the **processing thread**
   (never main) before enqueue. Below the size threshold ⇒ codec 0.
3. **Store deposits**: the delivery choke deposits the *same frame* it shipped —
   compress once, use twice. (Deposit-side recompression today is store-internal;
   this aligns the two.)
4. **DirtyContentFilter** keeps hashing the **raw** bytes. Hash identity must be
   codec-independent or every serve-path pairing (probe vs disk vs store) breaks the
   re-save suppression. Pinned by test (§8).
5. **v16 egress shim**: the V16 dialect already strips the source byte; it must also
   serve raw (decompressing store hits for legacy clients — rare, correct, cheap).

## 4. Client path

`ClientColumnProcessor` decompresses on its existing off-render-thread drain, then
decodes as today. zstd-jni is **already nested in the Fabric jar** (store dependency,
native-stripped to the supported platform matrix) — the client side needs no new
dependency, only the init-time native probe.

**Decompression-bomb guard** (required, not optional): read the frame's declared
content size *before* allocating; reject any frame whose declared or actual
decompressed size exceeds `MAX_SECTIONS_SIZE` (2 MiB), through the same
hostile-frame handling as an oversized raw payload. The existing
`MAX_SEND_SECTIONS_SIZE` netty-frame guard keeps operating on the shipped
(compressed) size and stays conservative.

Codec byte outside {0,1} ⇒ treat as decode failure (ingest-failure path re-serves) —
NOT the unknown-value pass-through used for the source tag, because the codec byte
changes how the remaining bytes must be read.

## 5. Bandwidth-limiter semantics — decided: keep charging RAW bytes

The tempting change (charge shipped bytes so cap ≈ wire) is **rejected** for now.
The chunk-wall investigation concluded the binding constraint on a weak/fast-moving
client is **decode/ingest work, which scales with raw bytes**, not wire bytes.
Charging compressed bytes would silently multiply the client-work admission by ~6–7×
at the same configured cap — re-running the incident with a config that "didn't
change". So:

- `recordSend` keeps charging `raw + ESTIMATED_COLUMN_OVERHEAD_BYTES`.
- Diagnostics grow a second gauge: `wire_bytes` (shipped size) next to the existing
  counted rate, so `/lsslod diag` shows both and the §1 confusion dies.
- Revisit only as part of the per-player flow-control design (investigation doc §7),
  where a client-health feedback loop can own the client-work budget explicitly.

## 6. Config & rollout

- Server: `useCompressedColumns` (default **true** at ship, à la `useNbtTranscode` —
  the rollback lever; `false` ⇒ codec 0 for everyone, capability ignored).
- Client: no new config — the capability declaration is automatic (native probe).
  `receiveServerLods=false` continues to gate everything upstream of this.
- Compat matrix: v19+bit ⇔ compressed; v19 without bit ⇔ raw; v18/v17 pairs ⇔
  no session (existing strict-version behavior, both sides' v16-style shims
  unaffected); v16 dialect ⇔ raw via egress shim.
- VSS branding: wire surface shared verbatim as always — the capability lives in
  `common/`, `release_check.py`'s wire-identity pin covers it for free.

## 7. Costs & non-goals

- Vanilla's connection zlib still deflate-wraps packets above threshold; over a zstd
  payload that pass is wasted CPU for ~+0.1% size. Accepted — it is vanilla's knob
  (`network-compression-threshold`), not ours.
- NOT compressing `BatchResponseS2CPayload` / `DirtyColumnsS2CPayload` — tiny,
  packed-long payloads; zlib handles them fine.
- Dictionary training (shared zstd dict for small columns) — real future win, out of
  scope; would need store-side dict versioning. Note only.

## 8. Testing

- **T1**: codec round-trip goldens — compressed twins generated from the existing
  raw corpus; bomb-guard (declared-size lie, actual-size lie, truncated frame);
  store-frame==wire-frame pin; DirtyContentFilter raw-hash pin; HandshakeGate ladder
  with the new bit (declared/undeclared × useCompressedColumns on/off); Paper twin
  parity (encoder + payload edge frames); ClientColumnProcessor decompress-failure
  containment (reports through ingest-failure, never kills the drain).
- **T3**: end-to-end with capability negotiated — decoded content identical to the
  raw-path run.
- **Soak**: `store-second-join` (+Paper) with compression active — byte-parity laws
  already compare decoded content; add the wire_bytes field to the exporter schema
  (checker schema bump).
- **Benchmark**: before/after CPU/column and bytes/column on the fresh + no-cache
  scenarios; expect store-hit serve CPU to drop (no decompress+deflate) and wire
  bytes to drop vs zlib.

## 9. Open questions

1. Threshold value for "too small to compress" (measure on the corpus; likely
   256–1024 B).
2. Should generation serves compress before or after the x-ray mask? (After —
  masking operates on raw bytes; pin it.)
3. Interaction with the serve-path efficiency ideas (v19 dedup etc., branch
   `feat/lod-store` brainstorm doc) — this capability is independent but should
   share the protocol bump if both land in one release.
