# Implementation plan — perf round 2026-08-06 recommendations (post-review v3)

Source findings: `docs/planning/perf-profile-round-2026-08-06.md` (R1–R7, plus its
erratum). This plan covers the implementable items (R3, R4, R1, R2, R5) in landing
order, each with correctness gates and a performance-verification protocol.
R6 (backfill) and R7 (store adoption) require no code by their own findings.

**Review round (2026-08-06, three Opus agents — concurrency, pinned-decisions,
methodology):** v1 of this plan was reviewed and substantially revised. The two
design-level outcomes: R1 now hands over **raw compressed bytes**, not a live stream
(resolves stream-ownership, timeout-abandon leak, unbounded pool parse, and external
`.mcc` detection in one move), and R3's primary key design is the **structural-identity
wrapper**, not a flat canonical string (collision-unsafe: separator injection + type
collapse could serve wrong terrain). The methodology review additionally found the
harness bug recorded in the findings doc's erratum (`BENCHMARK_CONFIG_STAGED` — every
`PROFILE_*` knob inert) and re-derived the acceptance arithmetic. Dispositions are
inlined per phase; verified-as-fact review results are stated as fact, not hedged.

**Second review round (2026-08-06, one Fable agent, full-plan pass over v2 + the
re-baseline data):** verified all v2 design revisions as sound (raw-bytes handover,
structural Key, CRC32C spec, Phase 0 diagnosis, root-key set, phase composition) and
found three gate-definition MAJORs fixed in this v3: the hibw throughput gate now has a
real metric (pooled cols ÷ pooled window — the `sections_per_second` field is
full-run-denominated and auto-passes), the round-acceptance backfill gate is summed
over the conserved three-thread universe at ≥15% (the two-thread ≥25% was unpassable —
Phase 3 moves parse ONTO the backfill thread), and Phase 0 items 3–5 now build the
marker-level counters, walk timing, and allocation persistence the Phase 1/2/4 gates
read. Plus: the three-branch `createChunkInputStream` reconstruction spec, the
shadow-vanilla-helpers + drift-risk + external-coverage notes, the order-independent
hash-combine spec with the capacity-forcing test, the new-production-code status of the
config echo line, and the split-kill-switch decision point in Phase 3's rollback.

**A/B discipline (all phases):** same-session interleaved arms, ABBA-ordered with one
discarded warm-up rep, **counts pooled across reps** (never per-rep ratios averaged),
gates as cut-ratios with a Poisson 95% lower bound reported. The frozen 2026-08-06
artifact numbers are context, never targets — each gate re-measures its own base arm.
Before any measuring session: `pgrep -af 'lss.multitest|test-server'` must be empty.
Before Phase 1's first gated run: one **A/A control** (base ref vs base ref, 3 reps of
the standard arm PLUS a hibw pair) to establish this box's spread on every gated metric
— no such control exists in the archive, and the hibw ceiling gate's ±5% must be sized
against the measured A/A spread, not assumed.

## Landing order

| phase | item | note |
|---|---|---|
| 0 | measurement tooling prerequisites | blocks all gated measurement |
| 1 | R3 memo key | smallest code change |
| 2 | R4 contentHash → CRC32C | carries the round's one store schema bump (v3→v4) |
| 3 | R1 background-read split | Fabric structural; creates the LSS-owned parse site |
| 4 | R2 selective NBT parse | hard-dependent on Phase 3 |
| 5 | R5 ops note + doc corrections | docs only |

One PR per phase, each carrying its A/B evidence in the description.

---

## Phase 0 — measurement tooling prerequisites

None of the phase gates are runnable with the tooling as committed. Build, in one PR:

1. **`profile_disk_read.sh` AND `compress_gate.sh`: export `BENCHMARK_CONFIG_STAGED=1`**
   around their benchmark.sh invocations — each stages a config that benchmark.sh's
   neutral-staging block (landed 6856bcb, 2026-08-02) silently replaces, so every
   `PROFILE_*` knob and compress_gate's `useCompressedColumns` arm variable are inert
   (profile_disk_read found by review; compress_gate found by the post-review
   blast-radius audit — its archived runs predate the block, so the recorded
   protocol-19 evidence is clean). **Status: the `profile_disk_read.sh` export is DONE
   (2026-08-06, verified by the re-baseline runs — 0 clobber lines, staged config
   confirmed live in the JFR).** Remaining: the same one-liner in `compress_gate.sh`,
   and the effective-config assertion in both. **The echo line is NEW production code**
   (today nothing echoes config at service start): add one INFO line echoing
   `useNbtTranscode`/`diskReaderThreads`/(later `useSelectiveNbtParse`) — *(B0 as
   shipped: also `useCompressedColumns`, which IS compress_gate's arm variable, so its
   effective-config assertion has something to match; keys append per the format
   contract)* — treat its
   format as a script-consumed contract (small format pin), and the run scripts grep it
   into `meta.json` as part of `arm_valid` — an ignored config key must fail the arm,
   not silently compare two identical arms. Note the `profile_disk_read.sh` fix is
   working-tree-only today; the Phase 0 PR lands it.
2. **Ref-vs-ref arms in `profile_disk_read.sh`**: `base`/`change` arm vocabulary behind
   `PROFILE_BASE_REF`, using the `benchmark_compare.sh` worktree pattern
   (`git worktree add --detach` + rsync'd base world + prebuild of both roots). ABBA
   ordering across reps (the current `for rep; for arm` runs a fixed order — systematic
   first-position bias).
3. **`scripts/backfill_profile.sh`** (used ad hoc this round, never committed): written
   to the scripts contract — `OUT_ROOT` default, documented `PROFILE_*` knobs, the
   :25565 port-conflict guard — and it records **walk start/end timestamps + deposit
   counts into `meta.json`** (from the backfill log lines), defining the Phase 2 gate's
   deposits/s = deposits ÷ walk seconds. No committed export carries walk timing today
   (`server.json`'s store block is a cumulative end-of-run dump).
4. **`analyze_profile_jfr.py`**: (a) a windowing mode for client-less runs
   (`--window full|t0:t1` — *B0 as shipped adds `walk`, which reads the walk window
   backfill_profile.sh parses into meta.json, so the backfill gate needs no manual
   timestamp plumbing*) so backfill runs emit `bands.json` + `flame.collapsed`
   (wire-slope detection has nothing to detect without a client); (b) per-thread ×
   band × has-LSS-frame counters in `bands.json` — the Phase 3 gate needs
   "IO-Worker samples carrying an LSS frame", which no committed artifact can express;
   (c) **per-marker × thread counters** over a configurable stack-marker class list —
   `MemoizedNbtCodec` (its `Key` must be a NESTED class, or be added to the list, so
   "samples under MemoizedNbtCodec" stays a stable identifier across phases),
   `LodStoreService.contentHash`, `SelectiveChunkNbtLoader` — these are what the
   Phase 1/2/4 gates actually read; this round's 76/80/~60 numbers were ad-hoc scripts,
   the exact practice this item ends; (d) **persist per-class allocation weight**
   (today printed, never written) for the Phase 4 allocation gate; (e) extend
   `DISK_PATH_MARKERS` with `AbstractChunkDiskReader` and the Phase 3/4 classes
   (`ChunkRawRead` site, `SelectiveChunkNbtLoader`) — otherwise the moved parse
   reclassifies to `nbt-other` and the Phase 3/4 gates break silently.
5. **`scripts/compare_profile.py`**: pairs base/change stamp dirs, prints pooled band
   counts, **pooled marker-counter cuts, per-column allocation weight
   (÷ `columns_received`)**, µs/col, and Poisson CIs — the arithmetic every gate below
   states. Also computes the hibw ceiling metric: pooled `sources.disk_read` ÷ pooled
   wire-slope window seconds.
6. **Re-baseline**: PARTIALLY DONE (2026-08-06, findings doc "Re-baseline" section):
   two verified arms exist — staged-config 20 MiB (79 s data window, IO-Worker
   390 LSS-frame vs pool 311, ~222 µs/col) and 100 MiB CPU-bound (28 s window,
   ~1,615 col/s at 1.54 cores, IO-Worker 409 vs pool 318, ~368 µs/col). These are the
   standing CONTEXT numbers; phase gates still run their own same-session base arms.
   Arm-duration guidance from these runs: standard arm 150 s (~80 s data phase at
   R=256 before the world-edge not-found tail), **hibw arm 90–120 s** (the world
   exhausts in ~30 s — longer runs only grow the not-found tail). The backfill arm
   still needs its committed script + windowing (items 3–4).

Classifier tripwire, checked in every A/B: `nbt-other` and `serialize-live` counts must
be stable across arms within counting noise — they are vanilla/probe work no phase
touches, so movement there means the classifier moved, not the code.

---

## Phase 1 — R3: structural-identity cached-hash memo key (`MemoizedNbtCodec`)

**Problem (measured, corrected by review):** the palette memo keys raw `CompoundTag`s;
every lookup pays `AbstractMap.hashCode` (EntryIterator allocation per nested compound)
and every hit pays full structural `AbstractMap.equals`. 76 samples under
`MemoizedNbtCodec` on the backfill thread (25% of it), ~54 on the serve path (~40% of
the transcode subtree), plus the 328 MB/75 s EntryIterator churn.

**Design (review-revised):** a `Key` wrapper holding the (copied) tag and a
**precomputed one-pass structural hash** built with `CompoundTag.forEach`
(allocation-free, unlike entrySet iteration), `equals` delegating to `Tag.equals` —
structural identity is retained exactly as the class javadoc pins ("the entry tag
itself is the cache key") and as both reviewed prior designs (brainstorm Ideas A and G)
chose. **The flat canonical-string key from plan v1 is rejected**: raw disk tags are
mod/corruption-controlled, and both separator injection (`{a:"x;b=y"}` vs
`{a:"x", b:"y"}`) and type collapse (`{p:"1"}` vs `{p:1b}` under stringification)
collide — a collision returns another entry's `globalId` straight onto the wire and
into the store, a silent wrong-terrain serve no re-declaration heals.

Constraints carried from review:
- `tag.copy()` in `decodeAndCache` is the **remainder** normalization, not key hygiene —
  it must survive (deleting it re-pins the first caller's whole chunk NBT from a
  JVM-lifetime static map). Say so in a comment.
- No shared/static builder or hasher state — the memo is hit from the reader pool AND
  the backfill thread concurrently. Per-call locals only.
- The precomputed hash must be **order-independent per compound level** (sum/xor
  combine, matching `AbstractMap.hashCode` semantics) — a sequential combine hashes
  equal-content tags differently when their backing maps iterated in different orders
  (capacity-history dependent), silently degrading to duplicate memo entries. And the
  walk must recurse **allocation-free through nested `CompoundTag`/`ListTag` values**
  (palette entries carry a `Properties` sub-compound) or the nested iterator churn
  survives. `CompoundTag.forEach(BiConsumer)` exists in 26.2 (verified).
- The `Key` class is **nested inside `MemoizedNbtCodec`** so the Phase 0 marker
  counters ("samples under MemoizedNbtCodec") remain a stable identifier through
  Phase 4's band subtraction.
- Eliminable machinery is ~50% (backfill) / ~44% (serve) of the memo samples **before**
  charging the new hash walk; the hit path keeps `ConcurrentHashMap.get` +
  `String.hashCode` work. Gate accordingly (below), and report the new key-construction
  cost as its own named term (samples under the `Key` constructor/hash walk).

**Correctness gates:** **new** `MemoizedNbtCodecTest` + `PaperMemoizedNbtCodecTest`
(none exist today — the `memoSizeForTest` seam has zero callers): cap stop, warn-once,
hit/miss, defensive copy, and an explicit collision-adjacent case (equal-content
different-insertion-order tags hit the same entry — the test must FORCE different
backing-map capacities, since same-capacity maps usually iterate identically and the
test passes vacuously otherwise; different tags never collide). Existing
byte-identity pins stay the real proof: six nbt-corpus goldens, transcode-vs-object
fuzz, `SerializerParityGameTests`, `:paper:test` twins.

**Perf verification** (ref-vs-ref, Phase 0 tooling, 3 reps × 150 s ABBA + backfill arm):
1. Backfill arm: samples under `MemoizedNbtCodec` on the backfill thread, pooled.
   **Gate: ≥30% cut** (Poisson 95% lower bound above 0 required; the measured headroom
   is ~50% minus new-key cost, so ≥30% is achievable, ≥50% was not).
2. Serve arm: same metric on the reader pool + `serialize` band share.
   **Gate: ≥20% cut pooled; `columns_received` parity within ±2%** (the bandwidth-capped
   rig makes sections/s a null instrument — parity is the honest check).
3. Secondary: per-column sampled allocation weight not regressed.

**Rollback:** revert (no persisted state, no wire).

---

## Phase 2 — R4: `contentHash` FNV-1a → CRC32C (store schema v4)

**Problem (measured):** byte-at-a-time FNV-1a is 80 samples ≈ 28% of the SQLite batcher
thread under deposit load; frame deposits also compute chash+fhash on the processing
thread for compressed sessions.

**Audit — resolved by review, stated as fact:** `contentHash` has exactly four call
sites: the batcher raw-deposit insert, the two reader-side validations, and the
processing-thread frame-deposit pre-compute. Corruption detection only; never an
identity/dedup key; never crosses a jar boundary; `DirtyContentFilter.fnv1a64` is a
separate function with its own 0-sentinel and stays FNV. The XXH64 contingency from
plan v1 is dead — CRC32C proceeds.

**Change** (`common/.../LodStoreService.java`, `SqliteLodStore.java`):
- `java.util.zip.CRC32C`, **`new CRC32C()` per call — never a static field, never a
  ThreadLocal** (mutable, non-thread-safe, and called from three thread families; a
  shared instance produces wrong hashes that drive the row-poison purge into deleting
  good rows). The cost is the update intrinsic, not the allocation.
- Zero-extended into the existing 64-bit columns — sound (both validations compare
  same-function longs; `usize == 0` short-circuits before any hash compare, so the
  CRC-of-empty degenerate value is never consulted). Record in the PR: detection
  strength drops 2⁻⁶⁴ → 2⁻³² per corrupted row, on derived data whose worst case is one
  wrong LOD column that the purge ladder then deletes.
- Rename the three surfaces that would become lies: the `LodStoreService.contentHash`
  javadoc ("FNV-1a 64"), the private `fnv1a` wrapper name in `SqliteLodStore`, and the
  schema-history comment — which also gains its `4:` line.
- Bump `SCHEMA_VERSION` 3 → 4. Drop-and-rebuild is the established derived-data policy;
  done-mark reset is **automatic by construction** (`deleteDbFiles` unlinks the db that
  contains the `backfill` table — nothing to build). Rollback symmetry verified:
  `metaMatches` is an equality compare, so an old jar against a v4 store also rebuilds.
- Release-note obligation goes somewhere durable: append the item to the next
  release-notes draft doc in `docs/planning/` (established pattern), `### Performance`,
  no platform qualifier (both platforms), noting the one-time store rebuild
  (~25 min re-backfill at 500 col/s for a 10 GB store).

**Correctness gates:** `SqliteLodStoreTest` hash/validation pins updated; a v3→v4
drop-and-rebuild-fires-once test (registry-fingerprint-drift pattern);
`store_gate.sh warm` AND `cold` GATE PASS on the change ref — noting `warm` is a
hit-path gate that structurally cannot see a deposit-side change; **`cold` (deposits on,
`COLD_REGRESSION_CEIL` 10%) is the store gate that can**; store soaks
(`store-second-join`, `store-save-storm`, `store_offline_edit.sh` — whose cross-phase
probe hashes are client-side and exercise chash only indirectly, via
mismatch-purges-row; still valuable, not "end-to-end chash").

**Perf verification** (ref-vs-ref, backfill arm):
1. `LodStoreService.contentHash` samples on the batcher thread, pooled.
   **Gate: ≥80% cut vs the same-session base arm** (large effect size — CRC32C intrinsic
   vs a serial byte loop — this is the one gate where ≥80% is defensible), deposit
   throughput not regressed — deposits/s = deposits ÷ walk seconds, from
   `backfill_profile.sh`'s walk-timing `meta.json` (Phase 0 item 3; no committed export
   carries walk timing today).
2. `store_save_storm.sh` non-regression at the harness's **own** bound —
   `max(1.0 CPU-s, 25% of the off arm)` on an n=1 pair with ~0.5 s observed noise. The
   plan-v1 ">5%" bound was finer than the instrument and is withdrawn.

**Rollback:** revert; the schema mismatch rebuilds the store in either direction.

---

## Phase 3 — R1: split the Fabric background read (raw-bytes fetch on IOWorker, inflate+parse on pool)

**Problem (measured; re-baselined 2026-08-06 under the fixed harness):**
`backgroundRead` runs `RegionFileStorage.read(pos)` — pread + zlib inflate + full NBT
parse — on the vanilla IOWorker's single-threaded executor. Re-baseline run A (staged
config, 5 reader threads, 20 MiB cap): IO-Worker **390 LSS-frame samples vs 311 across
the entire 5-thread pool**. Run B (100 MiB, CPU-bound, ~1,615 col/s at the throughput
ceiling): **409 vs 318** — the busiest serving thread by ~7× per-thread, with the nbt
band at 16.2% of all samples. Backfill reads share the same choke (412 of its 415
IO-Worker samples are LSS-attributed). This is the mechanism of the documented A7
timeout storms, and at the ceiling it is the serve path's top structural constraint.

**Design (review-revised — raw bytes, not a live stream):** the executor closure hands
over a value object, not an open resource:

- New seam `ChunkRawRead` (functional interface, injection-testable) used **only** by
  the background rung. **`ChunkNbtRead` is unchanged** — the Moonrise rung returns its
  bridge future directly and the rung-order pin asserts tag identity (`assertSame`), so
  widening the shared interface would touch all eight ladder pins; a separate seam
  touches none of them.
- On the executor: resolve the region file (`@Invoker` for the private
  `RegionFileStorage.getRegionFile` — which **never returns null and creates on
  demand**; absence is signalled downstream), then read the chunk's **raw compressed
  record**: `record RawChunkBytes(byte[] compressed, byte version)`, empty/absent →
  `Optional.empty()`. This needs a small raw-read surface on `RegionFile` (the wrapped
  `getChunkDataInputStream` only exposes the *decompressing* view): a mixin method that
  resolves the sector record — and, for external chunks, reads the `.mcc` bytes into
  the buffer **on the executor** (file IO stays on the executor; reader/writer of a
  given `.mcc` stay serialized exactly as today, sidestepping the non-`ATOMIC_MOVE`
  replace hazard). The v1 "lazy pool IO for externals" fallback is **withdrawn**
  (review: it breaks a serialization that exists today and its atomicity premise was
  wrong). Conservative alternative if the raw surface gets ugly: on the external flag,
  fall back to `storage.read(pos)` full-parse-on-executor for that column (rare,
  already-oversized).
- On the pool (`readAndSerializeSections` overload consuming the raw record —
  `record RawChunkRecord(byte[] payload, byte version)`, "payload" not "compressed":
  `VERSION_NONE` (id 3) payloads are uncompressed): the reconstruction must mirror
  `createChunkInputStream`'s **three branches** (verified in 26.2 bytecode), not just
  the happy path — (a) `RegionFileVersion.fromId` returns **null** for unknown ids, so
  a naive `.wrap(...)` NPEs; unknown id resolves **authoritative not-found**, matching
  vanilla; (b) `VERSION_CUSTOM` (id 127) logs and resolves not-found; (c) a valid id
  wraps — and `wrap` includes the `FastBufferedInputStream` layer, so valid-branch
  equivalence holds by construction. Then `NbtIo.read` → the unchanged serialize path.
  All pool-side work is **pure CPU over private in-memory bytes**: no fd, no stream
  ownership, no close protocol, nothing for a timed-out `future.get` to leak (a
  `byte[]` is just garbage). The timeout statement is now honest:
  `DISK_READ_TIMEOUT_SECONDS` bounds the executor fetch; pool-side inflate+parse is
  bounded work over a bounded buffer.
- Parse failures land in the same per-chunk triage (`readAndDeliver`'s
  `catch (Throwable)` with the `TimeoutException` discriminator — verified). One PR
  check: failures now arrive unwrapped rather than in `ExecutionException`; nothing
  branches on the wrapper today, keep it that way. Null-record shapes (truncated
  header, missing stream, bad version) resolve as authoritative not-found, matching
  `storage.read`'s null today.
- Scope hygiene: the pool-side `NbtIo.read` stays **outside** the AntiXray
  `callSerializing` shim (the existing comment documents that exclusion; update it).
- Mixin surface: the `getRegionFile` invoker + the `RegionFile` raw-read method, both
  registered in `lss.mixins.json` and pinned by a listing test
  (`ChannelAccessorContractTest` pattern). The raw-read method must **prefer
  `@Shadow`/`@Invoker` on vanilla's own private helpers** (`getOffset`,
  `isExternalStreamChunk`, `createStream`, the external-path resolution) over
  re-deriving the header/sector constants — the region record format is a
  per-MC-version reimplementation risk, and shadowed members fail LOUDLY on rename
  (`defaultRequire: 1`) where hand-rolled parsing would silently misread. Coverage
  honesty: the external (`.mcc`) and truncated-header branches have no real-file
  coverage (`SerializerParityGameTests` exercises internal chunks; the `ChunkRawRead`
  seam pins are injected-value tests, and mixin classes refuse loading under
  fabric-loader-junit) — add an oversized-chunk parity gametest, or document the
  residual gap explicitly in the PR.
- Doc updates in the same PR: `backgroundRead` javadoc, CLAUDE.md's `ChunkDiskReader`
  paragraph, `read-scheduler-design.md` §10.4 — all three say "reads straight from
  RegionFileStorage", which becomes fetch-only.
- Note for gate reading: backfill reads also split, so backfill's moved parse lands on
  the **backfill thread** (not `LSS Disk Reader`) — the conservation check must expect
  that.

**Correctness gates:** Tier 1 — `ChunkDiskReaderTest` keeps all eight existing ladder
pins untouched (fallback predicate, throwable latch, warn-once, Moonrise rung order +
`assertSame`, config rollback, null bridge, typed latch ×3, async-failure non-latch)
and gains new pins via the `ChunkRawRead` seam (fetch on injected executor, parse on
calling thread, absent/corrupt/external variants). Tier 2 — `SerializerParityGameTests`
(`backgroundPriorityReadMatchesForegroundReadForDiskLoadedColumn` is the end-to-end
byte proof) + `RegionFaultGameTests` containment. Soaks: `fresh-backfill`,
`disk-saturation` (`disk.saturated == 0` with `superseded ≥ 100`), `warm-rejoin`.

**Perf verification** (ref-vs-ref, 3 reps × 150 s ABBA + backfill arm, Phase 0 tooling):
1. **LSS-frame-filtered IO-Worker samples** (per-thread × has-LSS-frame from the new
   `bands.json`), pooled. **Gate: ≥75% cut** (re-baseline context: 390 of 488 on the
   standard arm, 409 of 454 on the hibw arm; the gate is the same-session ratio).
   Vanilla residual on that pool reported separately — it is *expected to rise*
   slightly (the save queue drains faster), which is why the unfiltered count is not
   the gate.
2. Conservation sanity: total LSS-attributed samples roughly flat, moved work
   reappearing on `LSS Disk Reader` (serve arm) / backfill thread (backfill arm);
   classifier tripwire (`nbt-other`, `serialize-live` stable).
3. Throughput, TWO arms with different roles (established by the re-baseline): the
   **hibw arm** (`PROFILE_BW_PER_PLAYER` = global = 100 MiB, 90–120 s) is CPU-bound at
   ~1,615 col/s and carries the ceiling gate. **Metric definition matters:** NEVER
   `server.json`'s `sections_per_second` — it is denominated over the full run duration
   on a corpus-fixed world, so two arms auto-agree regardless of ceiling (and the field
   counts columns, not sections). The ceiling metric is **pooled `sources.disk_read` ÷
   pooled wire-slope window seconds over ≥3 hibw reps** (`compare_profile.py` computes
   it; the ~1–2 s `cpu.jsonl` cadence quantizes a single 28 s window by ±4–7%, which
   pooling absorbs). **Gate: ceiling within ±5% or better, with ±5% validated against
   the A/A hibw spread first.** The **standard 20 MiB arm** is bandwidth-capped, where
   any throughput number is a null instrument and the check is `columns_received`
   parity ±2%.
4. `disk_reader.avg_read_time_ms` directional only (18% pool utilisation on a warm page
   cache — little dynamic range).
5. Backfill arm: LSS-frame IO-Worker samples ≥50% cut pooled; walk time flat (the
   MIN_PRIORITY thread now carries strictly more per-column work — the pacing gate must
   hold).
6. Directional, non-gating: fresh-backfill `disk.errors` on this box (A7-class,
   variance-prone, branch-A/B triage if red).

**Rollback:** **revert** is the primary path. For a live-server incident needing a
config-only mitigation, `useBackgroundReadPriority=false` exists and works but is
coarse — a full read-protection opt-out that also drops the Moonrise rung (pinned as a
true rollback); acceptable for an emergency, wrong as the phase's rollback story.
Because the `ChunkRawRead`/`ChunkNbtRead` seam split retains both code paths anyway, a
dedicated split kill switch is nearly free — decide at PR time whether to expose it as
config (project convention favors it for the round's riskiest change) or keep the seam
internal.

---

## Phase 4 — R2: selective NBT parse at the Phase 3 parse site

**Problem (measured, decomposed by review):** the nbt band is 211 samples on the serve
path, but its composition bounds the win: ~37 are memo machinery (Phase 1's target),
~94 are **inflater byte movement** (skipping a subtree still consumes its bytes through
the inflater), ~50 are structure building (`readUTF`, `String.<init>`, `HashMap.putVal`,
tag boxing). Selective parse eliminates the structure-building slice and its allocation
churn; it does NOT eliminate inflate. The realistic serve-path CPU cut is ~7%; the
headline win is allocation (the String/HashMap/StringTag/CompoundTag classes dominate
the ~195 MB/s churn). If a larger cut is ever wanted, the design that reaches it is
**early-abort once the whitelisted keys are read** (root-key order permitting — skips
the tail inflate); that is explicitly out of scope for this phase and recorded as the
stretch follow-up.

**Scope (verified complete):** the consumed root-key set is exactly **`{Status,
sections}`** — both platforms' serializers were audited; `minSectionY`/`maxSectionY`
come from the level, not `yPos`; `DataVersion` is unread **by decision** (the R2-1
amendment rejected DataVersion gating — it would turn every upgraded-world disc into a
generation storm) and must never become a gate. Applies only where LSS owns the parse:
the post-Phase-3 Fabric background rung. (`RegionFileStorage.scanChunk(ChunkPos,
StreamTagVisitor)` is public in 26.2 and was considered as a no-mixin alternative —
rejected: it runs on the caller thread and re-enters `getRegionFile` off-executor,
breaking the confinement Phase 3 preserves.)

**Change** (new `fabric/.../SelectiveChunkNbtLoader.java` + config):
- Root-level whitelist reader over the Phase 3 raw record's wrapped stream: whitelisted
  keys → standard `TagType.load`; others → `TagType.skip`. Result: a standard sparse
  `CompoundTag`; downstream unchanged. Must reproduce `NbtIo.read`'s root handling
  (type byte + skipped root name) and depth/size accounting exactly.
- The whitelist is a named constant beside the serializer, and a **source-regex pin**
  (the `SaveHookContractTest`/`StoreEnvironmentContractTest` mechanism) fails the build
  when a new root-level getter appears in the serializer without a whitelist update.
- **Leniency semantics, stated honestly (v1's parity claim was unachievable):** the real
  pins are `NbtSectionSerializerTest` `malformedBlockStates_codecParseError_
  sectionSkippedSiblingsServe` and `trulyUnparseableSection_condemnsTheColumn
  AsAuthoritativeMiss` (+ Paper twins) — codec-level leniency inside `sections`, which
  selective parse cannot touch; both stay green. The accepted, documented divergence is
  one-directional: a corrupt **non-section** subtree that would have thrown under full
  parse can now parse successfully — MORE lenient, defensible for LOD (the column's
  sections are intact; full parse would have condemned it to the error→generation
  ladder for data LSS never reads). On any selective-loader throw, the fallback is a
  full `NbtIo.read` over a **fresh wrap of the same compressed buffer** (free under the
  Phase 3 raw-record design — no re-buffering), then the normal triage.
- Config: `useSelectiveNbtParse`, default true. Shared `ServerConfigBase` key with
  Fabric-only effect is the established pattern (`lodStoreBackfillColumnsPerSecond`
  precedent) — javadoc states the platform scope in that idiom. Default pinned in BOTH
  `ConfigValidationTest` and `PaperConfigValidationTest` (the `nbtTranscodeDefaultsOn`
  twin pattern), CLAUDE.md's config list gains the key.

**Correctness gates:** selective-vs-full contract test — byte-identical serialization
over the golden corpus, plus a fuzz twin with TWO axes: root-key orderings AND
malformed/truncated/wrong-typed **root** bytes asserting the documented
fallback/divergence behavior; full Tier 1 + Tier 2 + Tier 3; `fresh-backfill` +
`dirty-broadcast` soaks; config tests above.

**Perf verification** (kill-switch A/B — same jar, `PROFILE_SELECTIVE_PARSE` knob wired
per the `PROFILE_NBT_TRANSCODE` precedent, **valid only after Phase 0's
`BENCHMARK_CONFIG_STAGED` fix and arm-validity assertion**; 3 reps × 150 s ABBA):
1. **Per-column sampled allocation weight** (String, HashMap$Node, StringTag,
   CompoundTag classes ÷ `columns_received`). **Gate: ≥30% cut** — the most robust
   metric in the round (historic rep-to-rep spread 1–3%, threshold 10–30× noise).
2. `nbt` band **minus samples under `MemoizedNbtCodec`** (Phase 1 already removed
   those), pooled. **Gate: ≥25% cut** (the structure-building slice of the band;
   ≥40% of the whole band was arithmetically unavailable).
3. `columns_received` parity ±2%; classifier tripwire as always.
4. Backfill arm: same metrics during the walk.

**Rollback:** the config flag; then revert.

---

## Phase 5 — R5 ops note + documentation corrections

1. README ops note ("network compression and LOD traffic"): vanilla deflates every
   packet above `network-compression-threshold`, including LSS's zstd frames (~30%
   overhead on the warm store serve cost, measured); no per-packet opt-out exists;
   proxy-terminated compression (Velocity) moves the cost off the server. Recommend
   AGAINST raising the global threshold (vanilla chunk-packet bandwidth pays for it).
2. Carry-through of the findings-doc erratum wherever its numbers were quoted.

---

## Round acceptance (after Phase 4)

One closing interleaved ref A/B (pre-round base vs final tree, same session, 3 reps ×
150 s ABBA + backfill arm, pooled):
- **Serve path: expect ≥8% LSS-attributed µs/col cut, gate ≥5%.** (Plan v1's ≥25% was
  arithmetically unreachable: R1 moves work rather than cutting it, R2's ceiling is the
  ~50-sample structure slice, R3 nets ~2–3.5%, R4 is store-side. The review's
  band-composition arithmetic is the reference.)
- **Backfill/deposit arms carry the headline** — but the metric must be summed over
  **all three carrying threads** (backfill worker + SQLite batcher + IO-Worker
  LSS-frame), because Phase 3 deliberately moves the backfill's parse from the
  IO-Worker ONTO the backfill thread: a two-thread sum RISES ~35% when everything
  succeeds (the v2 gate was arithmetically unpassable). Over the conserved three-thread
  universe (~996 base samples), the supported arithmetic is memo (~38) + contentHash
  (~64) + the parse structure slice (~100) ≈ **gate ≥15% cut**, with per-column CPU
  across the walk as the cross-check.
- Per-column allocation on the serve arm: ≥30% cut (Phase 4's gate re-confirmed on the
  final tree).
- Throughput: hibw-arm ceiling (pooled `sources.disk_read` ÷ pooled window — the
  Phase 3 metric definition, never the full-run-denominated `sections_per_second`
  field) within ±5% or better; standard-arm `columns_received` parity ±2%; backfill
  effective col/s flat.
- `store_gate.sh warm 3 120` (three reps — the single-rep variant has no noise
  rejection) still GATE PASS, with one pre-agreed clause: Phases 1+4 shrink the
  **off-arm** (the disk path) while the on-arm store cost is unchanged, so metric 2's
  relative `BAND_CPU_CUT_FLOOR` can mechanically fall below its 0.50 floor *because the
  round succeeded*. A metric-2 miss with the addressable-cut and absolute on-arm
  µs/col legs healthy is a **documented re-baseline of the floor, not a phase revert**.

Failure handling: any gate miss reverts or flag-disables the offending phase before
release; phases are independently shippable.

## Standing risks

- **WSL2 box variance** — interleaved same-session ratios, pooled counts, A/A control
  first; suspicious result → the documented branch-A/B triage.
- **Pinned-decision collisions** — each phase names its pins; the three-lens review
  round has already walked them once, but implementation PRs re-verify before touching.
- **Store rebuild on Phase 2** — deliberate schema bump with a release-note draft item;
  bundle any future store format change into the same bump if it lands this round.
- **Phase 3 mixin surface** — the `RegionFile` raw-read method is the one genuinely new
  reach into vanilla internals; if 26.2's internals make it fragile, the conservative
  external-chunk fallback (full parse on executor for that column) bounds the blast
  radius, and the split still covers the ~99% internal-chunk case.
