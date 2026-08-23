# Plan: warm-revalidation acceleration — read-path freshness rungs + region-summary sync

**Status:** IMPLEMENTED 2026-08-20 on `feat/region-summary-sync` (P1 + P2, three
changesets, each 3-Opus-reviewed, plus a final 2-Fable + 3-Opus branch panel —
all findings folded). CLAUDE.md's `RegionStampTable`/`RegionSummaryService`/
`LodRequestManager` entries are the AS-BUILT record; where this document and
CLAUDE.md disagree, CLAUDE.md wins. §5/§6 below carry inline as-built corrections
from the folds. **P0 was not run as a separate rig session**: P1 landed on its
standing independent justification, and the eviction-regime measurement became a
permanent live gate instead (`summary_evicted.sh` / `evicted-tscache-rejoin` —
header_hits ≈ up_to_date ≈ 2k, re-download bounded to the poisoned residue), which
measures the rung's win on every run rather than once.

Plan lineage: v2.0 2026-08-19 — the SYNTHESIS of the five-reviewer round (2 Fable:
protocol honesty, integration; 3 Opus: adversarial, performance, design
alternatives) over the v1.0 draft. **v1.0's client-side watermark/sidecar design is
REPLACED** (see §13 — the alternatives review's Fact A made it unnecessary, and the
honesty review found four independent false-clean paths in its ratchet lifecycle);
the server's per-request stat sweep is replaced by a save-hook-fed permanent stamp
table (three reviewers independently). All review-verified code facts below were
re-verified against `feat/quadtree-client-state` before synthesis. Companion to
docs/planning/quadtree-client-state-plan.md (the section-leaf store is a
dependency); supersedes nothing else — quadtree plan §5.3's rejection of
DECLARATION aggregation stands.

---

## 1. The problem, corrected (review-verified numbers)

A warm rejoin or dimension re-entry at large LOD distance re-declares every cached
stamp as a ts>0 resync, closest-first, 800/batch. Two regimes, and v1.0 priced only
the cheap one:

- **tscache-HIT regime** (the server's `ColumnTimestampCache` covers the disc):
  ~1.05M asks at effective distance 512 = ~1,313 batches ≈ **17–18 MB up** (16 B/entry
  — `BatchChunkRequestC2SPayload` writes long+long) + **~10 MB down** (9 B/entry batch
  responses) + ~1M router passes, over **~5.5 min at the 4 Hz fast cadence**
  (verified: the fast path stays armed through a stationary warm rejoin — the
  800-budget truncation keeps `predictedWalkCost`'s span small at every depth). The
  user-visible symptom: **the want-set spends its whole proximity-ordered budget on
  revalidation, so NEW terrain does not start arriving for minutes after every
  rejoin or portal transit.**
- **tscache-MISS regime** (evicted tiles — AUTO budget is ~8 discs/dimension, so any
  server whose players' history exceeds that; `check_soak.py` itself documents the
  shape): `resolvedFromTimestamp` misses, the ask falls through to a **real disk
  read**, and the result path consults only `PendingRequest.claimsData` (a boolean —
  the client's timestamp value never travels past the router), so the server
  **re-sends the full column**: up to ~10–15 GB of redundant terrain per rejoining
  player, bounded only by the bandwidth caps, plus a 1M-read storm (A7's exact
  precondition). This regime is why the feature is worth building — and why half of
  it needs no protocol change at all (§3).

Root-cause note (answering "is this a bug?"): the client always sends its stamp and
the router always uses it; the gap has existed since v0.2.0 (2026-03-10, the commit
that introduced the batched protocol + tscache) and is not a regression. The read
result cannot compare honestly because LSS column timestamps are **ACQUISITION
seconds** (`epochSeconds()` stamped at serve — disk at `AbstractChunkDiskReader:718`,
probe via cycleNow, generation likewise; store hits deliberately return STORED
acquisition stamps), so "now ≤ old client stamp" is never true. The comparable
quantity — the region file's per-chunk save-second header table — is already read
elsewhere (`SqliteLodStore.readHeaderTimestamps`, the freshness sweeps) but was
never consulted on the serve path. The tscache's documented "redundant serve, never
false up_to_date" doctrine made the miss→re-serve fallback the deliberate safe
choice; the honest cheap answer was never built.

Numbers are a function of EFFECTIVE distance (`min(server, client override, Voxy
view distance)` — the live rig runs 300); state them per-distance when measuring.

Out of scope for the whole program: cold backfill (data-bound), steady state
(silent), acquisition (ts≤0 — the want-set keeps owning it).

## 2. The program: measure → read-path rungs → summary

| Phase | What | Wire change | Est. |
|---|---|---|---|
| **P0** | Instrumented warm rejoin on the repro/live rig at effective 300–512: `requests_received`, `up_to_date`, `columns`, `disk.*`, wall time-to-first-frontier-ask, with a deliberately-evicted-tscache variant. Decides how the win splits between asks and re-serves, and gates P2's spend. | none | 0.5–1 d |
| **P1** | **Read-path freshness rungs** (§3): answer `up_to_date` at the disk/store result using the region-header save second vs the client's stamp. Kills the tscache-miss re-download class entirely. Independently justified; land regardless of P2. | none | 4–6 d |
| **P2** | **Region-summary sync, reshaped** (§4–§8): one ~6 KB exchange validates the clean bulk of the disc client-side, killing the ask flood and the minutes-long frontier delay. | 2 optional channels | ~2–2.5 wk |

P1 before P2 always: it is cheaper, protocol-free, and changes what P2's residual
value is measured against.

## 3. P1 — read-path freshness rungs (no wire change)

**Mechanism.** Thread the client's `clientTimestamp` through the submit path into
`PendingRequest` (keep `claimsData` as the derived `clientTimestamp > 0`). Two new
rungs, both answering the existing `up_to_date` response:

- **Store-hit rung**: a store hit whose STORED acquisition stamp ≤ clientTimestamp
  answers `up_to_date` instead of re-sending the stored bytes. Sound relative to
  the store's own freshness model — the content claim is identical to serving the
  bytes (both are "current as of src_stamp, swept on region change").
- **Header rung**: on a ts>0 request that missed the tscache, consult the region
  header's per-chunk save second (a per-region **memoized** 8 KiB header read — the
  `readHeaderTimestamps` machinery; memo invalidated by mtime change and by the
  save hook, shared with §5's table). As-built the compare is MARGINED, not bare:
  `clientTimestamp >= headerSecond + HEADER_FRESH_MARGIN_SECONDS` ⇒ `up_to_date`
  (see the honesty argument below — a bare strict compare validates exactly the
  raced reads), skipping the read AND the send. Refresh the tscache stamp from the
  answer (monotonic max, at the margined bound + 1) so the next re-ask hits the
  cheap rung.

**Honesty argument (corrected at implementation — the P1 3-Opus fold).** The
original draft claimed read-START stamping; in fact the wire/tscache stamp
(`columnTimestamp`) is issued at read COMPLETION, so a read that raced a pending
region write can hand the client PRE-change bytes stamped up to one read duration
AFTER the change's header second — a bare strict compare would validate exactly
those. Two mechanisms close it: (a) claims must clear the header second by a
**serve-latency margin** (`HEADER_FRESH_MARGIN_SECONDS` = read timeout + 5 s — nil
cost in the target regime, where stamps beat static terrain's last save by hours);
(b) the liveSaveMark is a **LATCH, not a stamp**: while a marked change postdates
every examined header second the whole region declines (comparing stamps against
mark TIME is unsound for the same raced-read reason), self-clearing when the write
lands. The tscache refresh from a header answer is **monotonic-max** — a
header-derived bound never lowers a higher observed acquisition stamp (the
downgrade would persist a memo claim globally and bypass the rung's own 5 s
re-validation). Clock rewind/backup restore behaves exactly like today's tscache
comparison (shared, pre-existing limitation) — but the panel (2026-08-22)
corrected the heal claim: a backwards step ≤ FUTURE_SKEW_ALLOWANCE (1 h) leaves
recent header seconds "future"-but-accepted, so `maxHeaderSecond` exceeds the
rewound clock, no new mark can arm the latch, and the header rung can intercept
the dirty re-ask's read with a false compare — the dirty-broadcast heal is
DEFERRED, not immediate. Bounded: a rewind > 1 h reads degenerate → NEVER_CLEAN
(safe), and the ≤ 1 h window self-heals when wall clock passes the stale stamps
+ margin. v0.13 carry: also latch when `maxHeaderSecond > now + margin`.

**Harness.** Soak-neutral in the hit regime (same `up_to_date` counters; soaks run
intact-tscache single-player). New Tier-1 pins: the strict-inequality margin, the
memo invalidation on save, header-read failure → fall through to the read (never
answer from doubt). Add `disk.header_hits` counter (exporter + contract).

## 4. P2 wire design — two optional channels, minimal surface

The far-player pattern (version-neutral raw bodies), minus everything the review
round showed was unnecessary:

- `lss:region_summary_req` (C2S): `version(u8)=1, dimension(utf8), centerTileX(vint),
  centerTileZ(vint), tileRadius(vint)`.
- `lss:region_summary` (S2C): `version(u8)=1, dimension(utf8), centerTileX, centerTileZ,
  tileRadius, dense row-major stampSeconds(vint, zigzag delta)` — **no flag array**:
  `0` = no region (trivially clean-capable: nothing to validate), the reserved
  sentinel `NEVER_CLEAN` (vint -1 pre-delta) = unknown/unresolvable. One frame; the
  radius clamp bounds it (~6 KB at 512, ~85 KB at the 2048 max — far inside the
  8 MiB clientbound envelope; **no chunking machinery** — v1.0's 2,048-tiles/frame
  split is deleted).
- **No capability bit, no `canSend` discovery** (v1.0's §3 reversed): the REQUEST is
  the capability declaration, sent fire-and-forget in a try/catch — the
  `lss:client_info` sidecar's exact pattern ("legacy servers discard the
  unregistered channel; a send failure must never take the announce down"). A
  legacy server never registers the channel; a legacy client never sends. The
  client arms only on a CURRENT-dialect session (the far-player/fast-cadence v16
  gate). Handshake untouched.
- **Explicit dimension, echoed and checked both ways** (three reviewers
  independently; the `BatchResponse`-carries-no-dimension lesson): the client drops
  any response whose dimension ≠ its current one. This is the entire anti-stale
  binding — no epoch/nonce machinery (v1.0's dangling `epoch` field is deleted),
  because a same-dimension stale response is harmless (stamps only get compared,
  never ratcheted — §6).
- **Hard decode caps as constants** (the FarPlayerWire discipline):
  `MAX_SUMMARY_TILE_RADIUS = ceil(MAX_LOD_DISTANCE/32)+1 = 65` checked at DECODE on
  both sides (long arithmetic — `(2r+1)²` overflows int at hostile radii), plus the
  as-built `MAX_SUMMARY_TILE_ABS = 2_000_000` CENTER-domain bound in both compact
  constructors (the final panel's hostile-center finding: an extreme center passes
  the radius cap but overflows the tile walk's `tileX<<2` leaf arithmetic — AIOOBE
  server-side, leaf aliasing client-side), dimension string ≤ 256 UTF-8 bytes, a
  remaining-bytes floor before the stamp-array allocation, `requireDrained`,
  unknown version byte → drop. Tier-1
  hostile-decode twins of the FarPlayerWire suite (negative/overflow radius,
  truncation, trailing bytes, oversized dimension).
- Census/registration touch-list (verified): both `WireParityTests` channel
  censuses 10→12, Fabric/NeoForge `LSSNetworking` twins, Paper
  `LSSPaperPlugin.registerChannels` + the `dispatchPluginMessage` switch (4→5
  handlers, its hostile-frame containment tests) + `PaperPayloadHandler`
  encode/decode. NOT plugin.yml (carries no channels — v1.0 corrected). VSS:
  nothing (wire surface never rebranded; release_check pins verified).

## 5. P2 server side — the RegionStampTable (no per-request filesystem work)

v1.0's per-request stat sweep is replaced (adversarial A1–A4, integration M1,
performance M2/M3 — three independent derivations of the same fix): the summary
answer is per-dimension data; only the window is per-player.

**The table.** Per dimension: `regionPos → {seenMtimeSecond, maxHeaderSecond,
liveSaveMark}` in a concurrent map. As-built naming/threading (no
`RegionStampSweeper` class exists): the table is `common/region/RegionStampTable`,
and the SUMMARY sweeper is a dedicated MIN_PRIORITY daemon inside
`RegionSummaryService` (the `StoreBackfill` restraint precedent), lazily started
on the first offered request. Summary sweeps never touch the reader pool (bounded
queue + abort policy, the single-submitter `hasHeadroom` contract — the disk gate
does not even see stats); the ONE exemption is the P1 chunk rung itself, whose
memoized 8 KiB header read runs inline on the asking reader thread — bounded,
horizon-cached, and cheaper than the region read it replaces.

- **Seeding**: on a dimension's first demand, ONE `readdir` of its region directory
  (never N constructed-path probes — sparse worlds pay for what exists). As-built
  this is lazier than planned: the readdir existence listing is taken once per STAT
  HORIZON (5 s) per dimension, and header reads happen lazily per-ask, memoized
  with mtime-`!=` invalidation — there is no boot-time stat+header sweep of every
  present file. The region-dir resolver
  is HOISTED out of the store branch (`RequestProcessingService:229` builds it only
  when `storeMode != OFF` today, and the compiled store default is off — v1.0 would
  have silently no-opped on most servers; integration M2) and shared with the
  store/backfill; unresolvable dimension → every tile `NEVER_CLEAN` + a once-logged
  warn (the silent-no-op guard).
- **Refresh, stat-as-detector + header-as-stamp** (alternatives R4): on demand, a
  tile older than a short freshness horizon re-stats; `mtime != seenMtime`
  (INEQUALITY, the store sweep's backup-restore discipline, not `>`) triggers an
  8 KiB header re-read updating `maxHeaderSecond`. Raw mtime is never the reported
  stamp — metadata-only touches (rsync/backup tooling) cost one header read, not a
  false-stale flood; header seconds are per-chunk save times, the same quantity P1
  compares.
- **The live rung**: `onChunkSaveData`'s hash-confirmed `obs.changed()` branch —
  the C2ME/Moonrise-proof choke point, may run off-main so the bump is an atomic
  monotonic max — sets `liveSaveMark = epochSeconds()` for the region at save
  SUBMISSION time. This closes the save-submitted-but-IOWorker-write-pending mtime
  lag (honesty M2) and the drained-dirty-tracker window (v1.0's rung 1 — the
  `DirtyColumnTracker` probe — is DELETED; `drainDirty` clears unconditionally
  every interval, so it never was a superset of unsaved change). The hook's P3 skip
  gate is harmless: anything it skips is recovered by the stat/header refresh.
- **Reported stamp** — as-built the mark is a LATCH, never max'd into the stamp
  (the final panel's raced-read proof, and its clear side carries a one-horizon
  grace): while `liveSaveMark > maxHeaderSecond` the whole region answers
  `NEVER_CLEAN` (comparing client stamps against mark TIME is unsound — a read
  racing the pending write hands out stamps NEWER than the mark carrying
  PRE-change bytes), and the FIRST observation of the clear condition still
  answers `NEVER_CLEAN` for one STAT_HORIZON (a sibling chunk's same-second
  landing can clear the latch while the marked write is still pending). Reported
  `M = maxHeaderSecond` once honest, `+ FRESH_CLAIM_MARGIN_SECONDS` on the wire;
  missing region = 0 ONLY for never-observed absence on a listable dir (§7's
  no-evidence doctrine); any doubt = `NEVER_CLEAN`. Every rung fails toward stale.

**Request handling.** The request lands on the platform's normal ingress (Paper:
`dispatchPluginMessage` on whatever thread — on Folia a region thread — so it is
marshalled exactly like the handshake, via the lifecycle-mailbox pattern) into a
**latest-wins per-player mailbox slot** (the want-set's own shape — natural
coalescing, no rate limiter, no limiter-lifecycle questions; portal spam collapses
to one pending request). The pump hands it to the sweeper; the sweeper windows the
table (clamping the RADIUS AND CENTER to the server's own window —
`ceil(lodDistanceChunks/32)+1` tiles around the player's tile, never the protocol
max: the changeset-2 review's I-M1, a ~30-byte request must not buy a 17k-tile
sweep — the `range_filtered` rule applied to tiles; hostile centers/radii are
clamped, counted, and harmless because they can only select the same window an
honest client gets), refreshes stale tiles, assembles the frame, and sends it on a
**dedicated send lane with its own byte counter** (`summary.bytes` — the far-player lane precedent; NOT the column
queue, NOT under the join slow-start budget, NOT in `bytes_sent`'s law term without
the audit note). Restart thundering herd: N joins share one table — the first
requests pay the seeding once, the rest are memory reads.

Server kill switch `enableRegionSummaries` (default true) is checked in the
HANDLER (not just channel advertisement); boot-set in practice — the key is not in
the `/lsslod set` registry, so a flip needs a restart.

The flood bound is admission-side, in three layers (the changeset-2 review's I-M1 —
mailbox coalescing bounds CONCURRENCY, not rate): latest-wins pending, the window
clamp above (one sweep costs at most the server's own configured disc), and a
per-player re-sweep cooldown equal to the stamp table's stat horizon (5 s — inside
it a re-sweep reads identical memos; a cooldown-held request is RETAINED and admits
when it lapses). Frame bytes stay outside `SharedBandwidthLimiter` by design: the
clamped, cooled bound is a few KB per player per cooldown, counted in the
`summary.bytes` lane. Tile sweeps store array-FREE header memos (the long bound
only), so a summary window can never evict the P1 chunk rung's retained arrays.

## 6. P2 client side — per-column comparison, ZERO new persistence

v1.0's sidecar watermark files and all four lifecycle rules are DELETED
(alternatives R1, dissolving honesty M1/M3/M6's ratchet paths and adversarial
A6/A7/A9/A11/A12 wholesale). The insight (Fact A, verified): the client's persisted
per-column stamps ARE server-clock "current as of" bounds — the sidecar stored a
weaker aggregate of information the cache file already holds.

**Validation rule.** For each tile with reported stamp `M`: for every STAMPED
position in the tile, set `validated` iff `stamp > M` (STRICT — same-second fails
toward revalidation, mirroring P1's margin). Leaf math: per leaf, candidates =
`positiveTs & ~validated`, one array scan; a 32×32 tile is exactly 4×4 leaves;
~20k leaf ops for a full disc — sub-ms. Properties, each load-bearing:

- **Mark-preserving, by pin** (honesty M4): the op touches ONLY `validated` bits.
  Dirty and retry marks survive — dirty outranks validated in classify, so a dirty
  notice racing the summary in either order still re-asks (this is what contains
  the bg-split read-your-writes window in-session too). The Tier-1 differential pin
  ("summary-validated ≡ per-column `up_to_date`") is SCOPED TO MARK-FREE stamped
  positions, plus a dedicated pin that a dirty/retry-marked position inside a
  validated tile still re-asks. Never creates leaves (adversarial m2 — a hostile
  frame must not allocate; pinned).
- **Provenance-scoped and two-directional, as-built** (the final panel's two client
  MAJORs, whose individual fixes would each make the other worse): summary
  validation sets a `summaryValidated` mask (a subset of `validated`); a fresher
  frame whose margined stamp a position's stamp no longer clears REVOKES the bit —
  but ONLY summary-provenance bits: a per-column server proof (`onReceived`/
  `onUpToDate`, which upgrade by clearing `summaryValidated`) is never downgraded
  by a tile claim. Revoked positions are reported to the caller
  (`applyTileValidation`'s `LongConsumer`), and `applySummary` reopens each one's
  scanner ring — a revoked position below the confirmed prefix would otherwise be
  orphaned until a full scanner reset. `fullyValidated` treats server-proofed
  conflicts as non-residue. Frame ordering is honest without sequencing: a stale
  frame can only OVER-validate, the reachable order (stale-then-fresh) is corrected
  by the fresher frame's revocation, and classify's dirty rung outranks validation
  meanwhile. Dirty notices arriving DURING a dimension's cache load are queued
  (`dirtyDuringCacheLoad`) and re-marked (+ ring reopened) after `adoptLoaded` and
  the ingest-failure re-applies, strictly before the buffered frame applies.
- **Both sentinels are skipped client-side** (the final honesty review's
  no-evidence doctrine): `STAMP_NEVER_CLEAN` counts `tiles_unknown`,
  `STAMP_NO_REGION` counts `tiles_no_region` — the server's never-observed scope is
  server-lifetime only, so "no region" also describes a region deleted while the
  server was off, and validating cached stamps against it would seal deleted
  terrain forever (never re-asked ⇒ never regenerated).
- **Per-column granularity degrades gracefully**: a stale tile still validates every
  column acquired after its last change — the busy-server regime where v1.0's
  all-or-nothing tile rule collapsed to zero benefit.
- **Unstamped positions are untouchable by construction** (all-air,
  session-satisfied, pruned, fresh installs) — they classify -1 and re-ask
  per-column exactly as today; nothing to poison, nothing to ratchet. Works on the
  first post-upgrade join against every existing v4 cache.
- **Request timing**: sent at dimension entry, in PARALLEL with the async cache
  load (`startAsyncCacheLoad` — the client knows dimension/center/radius already);
  the response, if it arrives before the load, is buffered in one nullable field
  and applied right after `adoptLoaded`. The load is preceded on the same FIFO by
  the old dimension's merge-save, so the RTT hides in an existing 0.5–2 s dead
  window — **zero escaped batches** typically (performance m3; v1.0's
  suppress-on-arrival race, already ≥99% won, goes to ~100%). Still no gating, no
  timeout, no retry: a lost response = today's behavior; the mailbox coalescing
  makes a re-entered dimension's fresh request cheap.
- Kill switch `enableRegionSummarySync` (client, default true): never request,
  never apply. With no persisted client state there is nothing a flip can poison
  (v1.0's A12 class is gone).
- **Soak/benchmark clients are property-gated OFF** (`-Dlss.soak`/`-Dlss.benchmark`
  — the far-player capability precedent), with a per-scenario opt-in property for
  the new scenarios. This replaces v1.0's client-config staging step and its
  ordering trap: existing scenario baselines cannot shift, and check_soak gains the
  `summary.*`-inert mirror of the far-player all-snapshots check.

**Counter attributability** (performance m8): client diag gains a `Summary:` line
(tiles clean/stale/unknown/no_region, columns validated) and the server diag a
summary counter group, so operators see WHY `re_resolved`/`up_to_date` collapse on
upgraded pairs instead of reading it as breakage.

## 7. Honesty analysis (what remains after the reshape)

- **Freshness equivalence holds now**: today's suppressed path answers `up_to_date`
  from save-time-or-older stamps; the summary validates only columns acquired
  strictly after the tile's last content change (header + live save mark). The
  probe-serves-live-edits case: an edited-and-SAVED chunk bumps `liveSaveMark`
  before any summary can claim it clean; an edited-NEVER-saved chunk is equally
  invisible to both paths (today's tscache stamp is the old one → `up_to_date`) —
  verified equivalent, not merely asserted (v1.0's gap, honesty M2).
- **Shared pre-existing limitations, stated**: server clock rewind and
  backup-restored worlds can produce wrong currency claims in BOTH the existing
  per-column path and the summary (same clock, same stamps). Panel correction
  (2026-08-22): post-P1 the heal is deferred, not immediate — inside the ≤ 1 h
  skew allowance the header rung intercepts the dirty re-ask (see the latch
  section above); beyond it, degenerate-second poisoning fails safe. Not
  mitigated, documented; hardening carried to v0.13.
- **The one new residual**: a change whose save submission, header write, AND
  dirty broadcast are all lost (hard crash between copyOf and the region write)
  reverts the chunk on disk; the client's stamp may postdate the reverted content.
  Today's path has the same hole via the tscache (its stamp also predates the
  crash). Equivalent, documented.
- **No-evidence states are DOUBT, never claims** (changeset-2 review, H-M1/H-M2):
  the tile path answers `STAMP_NO_REGION` (0 = "validate your stamps") ONLY for a
  region never observed present in this server life on a listable directory.
  Everything else fails to NEVER_CLEAN: an unlistable/not-a-directory region path
  (the Paper custom-world resolver hazard — once-logged), a region file that
  VANISHED after being observed (the "delete to regenerate" repair — a client
  validating old stamps there would keep deleted terrain forever), and a present
  region whose header carries no valid save second (all-absent chunks — the
  residue of a chunk-delete pass; its `maxHeaderSecond == 0` would otherwise alias
  the sentinel on the wire). Residuals accepted and stated: absence-after-presence
  detection is server-lifetime-scoped — a region deleted while the server is OFF
  reads as never-observed on the next boot (NOTE, corrected at the final panel:
  the tscache does NOT share this scope — it persists across restarts via
  `world/data/lss-timestamps.bin` — which is exactly why the CLIENT must skip
  `STAMP_NO_REGION` rather than validate against it; with that skip in place the
  claim "the client re-asks whatever it cannot validate" is enforced, and the
  deleted region heals via the read path's not-found → generation); and a
  single chunk deleted INSIDE a surviving region is invisible at tile granularity
  (poisoning on any absent slot would kill the feature — sparse regions are mostly
  absent slots). The per-CHUNK rung is unaffected: an absent chunk's header slot
  answers NEVER_CLEAN and the real read's authoritative-miss ladder owns it.

## 8. Observability, laws, harness (P2)

- Server (as-built): `summary.requests`, `summary.range_filtered`,
  `summary.frames`, `summary.tiles_{known,never_clean,no_region}`, `summary.bytes`,
  sweeper `summary.refresh_ms_hw` high-water (`tiles_no_region` is its own
  disposition — a whole-window no_region reading is the resolver-gone-wrong
  signal). Client: `summary.tiles_{clean,stale,unknown,no_region}`,
  `summary.columns_validated`. Exporter + BOTH contract files +
  `PaperSoakMetricsExporter` parity + `check_soak.py` KNOWN keys +
  `DiagnosticsFormatter` (+tests) — the verified touch-list, priced in §10.
- Laws unaffected structurally (suppression removes client asks; server
  dispositions stay conserved); the quiescence/traffic-floor exposure is closed by
  the property gate (§6). Scenarios as-built (three, plus the chain): `warm-rejoin-summary`
  (bulk validation + the t195 poison honesty leg + a SELF-SCALING suppression pin —
  `requested_total < columns.known` — plus an absolute ceiling; server volume
  ceilings on requests/frames/bytes/range_filtered), `dirty-while-offline-summary`
  (the false-clean canary: the offline edit's column must re-serve while the
  control stays equal), and `evicted-tscache-rejoin` (phase 2 of
  `summary_evicted.sh` — the P1 header rung's live gate). Platform coverage
  as-built: `warm-rejoin-summary` joins the Paper AND Folia sets (the sweeper's
  live Bukkit gate — integration m1, the no-cheap-unit-test doctrine);
  `dirty-while-offline-summary` stays Fabric-only like its namesake (console
  setblock fires no Bukkit event); the evicted chain is Fabric-only (Bukkit's
  split world dirs — see summary_evicted.sh's header).
- Tier 2 (as-built): `regionFreshnessServesRealRegionHeaders` — header rung + tile
  stamps + the whole summary pipeline against REAL game-written region files
  (terminal-pass shutdown — the static-mask-manager stomping hazard), in the
  `fabric-gametest` entrypoint. Tier 1: everything in
  §3/§4/§6 plus the sweeper's rungs against a temp region dir (mtime inequality,
  header refresh, degenerate mtimes 0/negative/future → NEVER_CLEAN — adversarial
  A10 — and int-formatted, range-checked region filenames — adversarial m5).
- Log discipline: all drop/clamp paths behind 60 s throttles (adversarial m6).

## 9. Config

- Server: `enableRegionSummaries` (default true; handler-checked).
- Client: `enableRegionSummarySync` (default true).
- P1 has no config (it is a correctness-preserving optimization of an existing
  path; the flag would be `useHeaderFreshnessRung` ONLY if review of the
  implementation wants a field lever — decide at implementation review).
  As-built: DECIDED, no lever — none of the four reviews asked for one, every
  doubt path fails toward serving, and `evicted-tscache-rejoin` gates the rung
  live on every chain run.

## 10. Effort (re-priced against the verified touch-lists)

| Phase | Est. | Notes |
|---|---|---|
| P0 measure | 0.5–1 d | rig session + one eviction variant |
| P1 rungs | 4–6 d | PendingRequest threading, header memo, two rungs, pins |
| P2 wire | 2–3 d | templated (far players), census ×2, Paper switch + hostile tests |
| P2 server | 5–7 d | table + sweeper + resolver hoist ×2 platforms + Folia mailbox ingress |
| P2 client | 3–4 d | ~40% below v1.0 — the sidecar/lifecycle machinery is gone |
| P2 harness | 4–6 d | property gate, two scenarios ×3 platforms, contracts/exporters |

P2 total ~2–2.5 weeks-equivalent (v1.0 claimed 2.5–3.5 for a bigger, weaker
design). Dependency: PR #207 merged + field-validated (the leaf store makes both
the validation op and the fast-skip payoff real).

## 11. Risks

- **R1 false clean**: the reshape's floor is structural — the client can only
  validate stamps it already holds, against a server stamp that every rung rounds
  toward stale (strict inequalities, `!=` mtime detection, NEVER_CLEAN on doubt,
  save-submission live marks). The canary scenario + kill switches remain.
- **R2 sweeper table drift** (a bug under-reporting M): contained by P1 — even a
  false-validated position that later re-asks meets the header rung's independent
  comparison; and by dirty broadcasts in-session. Diag `Summary:` line makes it
  attributable.
- **R3 mtime/filesystem weirdness**: inequality detection + degenerate-value
  NEVER_CLEAN + the store sweep's field history as prior art.
- **R4 walk-cost inversion on partial validation** (performance m5): a scattered
  stale residue can drop the client to 1 Hz for the residue drain (seconds, not
  minutes — the residue is small by construction). Accepted; note in the
  cadence docs. If P0/live data shows it matters, the fix is cadence-side
  (leaf-aware walk pricing), not protocol-side.
- **R5 scenario premise drift**: closed by the property gate + inert check.

## 12. Explicitly out of scope / recorded verdicts

- **Super-tile level 2: REJECTED, not deferred** (performance M4 + alternatives
  R7): at the 2048 maximum the flat response is ~85 KB — already negligible — and
  super-tiles cannot reduce the server's per-region work, which the stamp table
  already amortizes to zero. Recorded so it is not re-invented.
- **Mid-session revisit re-validation** (the in-session elytra re-download): the
  single largest remaining number in this problem space (~10⁴× P2's bytes — a
  pruned-and-revisited area re-downloads in full today). Needs a partial cache
  re-read design (the movement prune drops the stamps a summary would compare);
  own plan, after P2 ships.
- **Server-clock stamp ratchet** (raising validated columns' stamps to a
  server-supplied reference so "touched but caught-up" tiles stay cheap): the one
  power v1.0's watermark had that per-column comparison lacks. Add only if P0/live
  measurement shows the touched-tile residue matters; it changes the meaning of
  every persisted stamp and reintroduces stickiness.
- **Wire-level aggregated declarations**: still rejected (quadtree plan §5.3).
- **v4 trailer / v5 cache format**: not needed by this design at all; the trailer
  technique (verified viable — the v4 loader ignores trailing bytes) is recorded
  as the storage escape hatch if a future feature genuinely needs per-tile client
  state.

## 13. Review-round record (2026-08-19, 5 reviewers over v1.0) — disposition map

**Verdict pattern**: every reviewer endorsed the aggregate-watermark ARCHITECTURE
and rejected major parts of v1.0's MECHANISM. The v2.0 reshape adopts the
alternatives review's smaller design (per-column comparison, Fact A), the
convergent server inversion (permanent save-hook-fed table), and P1 (the corrected
form of the performance review's PendingRequest fix — corrected because the
literal `result.columnTimestamp()` comparison is inert under acquisition-time
semantics; the header table is the sound source).

- **Honesty (Fable) M1/M3/M6** (ratchet-stamp ambiguity; stamped-position
  carve-outs; same-second equality): DISSOLVED — no ratchet exists; strict
  inequalities specified everywhere (§3/§6). **M2** (drained-tracker/mtime-lag
  window; the probe-freshness equivalence gap): FIXED by the liveSaveMark rung
  (§5) + the §7 equivalence argument. **M4** (equivalence pin would force
  mark-clearing validation): ADOPTED — mark-preserving validation pinned, pin
  re-scoped (§6). **M5** (dimension binding): ADOPTED (§4). Minors: unit claim
  corrected (§1 acquisition times); wire spec inconsistencies deleted with the
  flag array/epoch field; arithmetic corrected (§1); canSend dropped; sidecar
  invariant moot.
- **Adversarial (Opus) A1–A4** (center clamp, limiter lifecycle, reader pool,
  tracker contention): DISSOLVED by the table+mailbox inversion it proposed (§5).
  **A5** (decode caps): ADOPTED (§4). **A6/A7/A9/A11/A12** (sidecar caps, vacuous
  coverage, future/rewound watermarks, the cache-collision inheritance, kill-switch
  poisoning): DISSOLVED — no client persistence. **A8** (dimension/epoch):
  ADOPTED as explicit dimension echo (§4); the unsolicited-frame concern is closed
  by drop-on-dimension-mismatch + stateless comparison. **A10** (degenerate
  mtimes): ADOPTED (§8 tests, §5 rungs). Minors m1 (handler-checked kill switch),
  m2 (never-create-leaves pin), m5 (int paths + range check), m6 (log throttles),
  m7 (named hostile tests): ALL ADOPTED.
- **Integration (Fable) M1** (reader-pool contracts): DISSOLVED/ADOPTED via the
  dedicated sweeper (§5). **M2** (resolver only built store-armed): ADOPTED —
  hoist specified (§5). Minors: Folia mailbox ingress (§5), Paper/Folia scenarios
  (§8), plugin.yml correction (§4), harness touch-list + honest pricing (§8/§10),
  fire-and-forget in place of canSend (§4), sidecar-merge question moot,
  both-handshake-sites moot (no capability bit), gametest scope (§8).
- **Performance (Opus) M1** (baseline understated; the re-serve regime; the
  PendingRequest fix): ADOPTED as §1's two-regime framing + P1 (with the
  acquisition-time correction). **M2** (stat siting, herd, memo TTL): DISSOLVED
  by the permanent table. **M3** (lossy rate-limit drop): DISSOLVED by mailbox
  coalescing. **M4** (extension ordering): ADOPTED — super-tile rejected, revisit
  magnitude recorded (§12). Minors: byte arithmetic (§1), effective-distance
  framing (§1), request-at-entry (§6), chunking deleted (§4), walk-cost inversion
  recorded as R4, dedicated send lane (§5), diag attributability (§6/§8), tile
  count 1,089 not 1,225 at 512 (noted), tracker bucketing moot.
- **Alternatives (Opus) R1** (delete the sidecar; per-column comparison): ADOPTED
  — the reshape's core. **R2** (save-lifetime dirty rung): ADOPTED as
  liveSaveMark. **R3** (explicit dimension): ADOPTED. **R4** (header-as-stamp,
  mtime-as-detector, `!=`, margins): ADOPTED. **R5/R6/R7** (drop capability bit +
  canSend; sentinel stamps; delete super-tile): ADOPTED. **R8** (request at
  dimension entry): ADOPTED. Kept-as-recommended: client-requested over push,
  flat region tiles, timestamps over hashes, no gating, revalidation-only scope,
  the measurement gate (P0), and Alternative F as the standalone first step (P1).
