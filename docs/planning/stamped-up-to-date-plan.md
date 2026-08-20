# Plan: stamped up_to_date — healing the permanent summary-stale set

**Status:** v1.3 2026-08-20 — v1.2 + the final whole-branch panel fold (§11: the
2-Fable + 3-Opus panel over the entire branch diff; one co-found ordering MAJOR +
three pin-fidelity MAJORs, all landed). The review
falsified v1.0's central claim ("the stamp adds no new claim") via three verified
mechanisms and prescribed a NARROWING, not a redesign; §9 is normative where it
tightens §2-§7. Follow-up to region-summary-sync-plan.md, targets
`feat/region-summary-sync` (PR #208) so the summary feature ships with its steady
state fixed.

**The stamping doctrine (the review's corrected honesty rule):** a rung may be
stamped ONLY if its staleness bound at disposition is provably inside
`FRESH_CLAIM_MARGIN_SECONDS` — which only the compare-backed rungs satisfy, and
only with the pending-mark/latch guard of §9.2.

## 1. The problem (measured live, 2026-08-20)

A warm rejoin on the rig (1.02M cached columns, distance 512) validated 571,706
columns off one summary frame — and left **499 of the 1,183 real window tiles
stale**, producing ~221k re-asks answered as ~220k `up_to_date` + **2** actual
column re-sends (~75 KB wire). The per-column layer absorbs the tile layer's
over-caution at ~9 B/ask, so nothing is *wrong* — but the user-verified repro is
the finding: **converge → disconnect (server empty, auto-paused, nothing writes)
→ rejoin → the SAME ~500 tiles are stale again.** The stale set is a ratchet that
never heals and only grows over a world's life.

Two mechanisms compose:

- **Serve-then-save inversion.** A column's client stamp is its byte-ACQUISITION
  second; the tile stamp is the region header's max save second (+ margin). Any
  save landing after a column's serve makes it summary-stale. This is guaranteed
  for LSS-generated terrain (served at generation completion, chunk saved at
  unload/autosave minutes later — the whole generation band), near-guaranteed
  for the vanilla load corridor (metadata-only `inhabitedTime` re-saves — the
  header is content-blind; `DirtyContentFilter` protects the dirty pipeline but
  cannot protect a raw header second), and permanent for pearl/spawn-loaded
  regions. Region-dir census on the rig: 1,524 overworld regions, **~522 written
  on the day of play** ≈ the stale count. The soak harness met this inversion
  during scenario design: warm-rejoin-summary only achieves validatable stamps
  via `save-all` + a full clearcache re-serve — "a serve-then-save stamp never
  clears the margin" — a move no production player performs.
- **`up_to_date` answers do not move the stamp.** `BatchResponseS2CPayload`
  entries are (type, position) pairs; the client's cached stamp advances ONLY on
  an actual column re-serve. So a column that fails a tile compare once fails it
  on every future frame, even though every rejoin's re-ask re-proves it current.

Left unfixed, the ask-suppression win decays monotonically for roaming players
(54% clean on this world's day one), and every rejoin re-pays a ~200k-ask sweep
for information the server already confirmed last time.

## 2. The fix — the server stamps its verifications

When the server answers `up_to_date`, it also tells the client *when* that
verification happened, and the client ratchets its cached stamp forward to that
second. The next summary frame then validates the column, because the
verification postdates the header write that caused the staleness. Steady state
becomes: **only regions saved since the client's last session read stale**, they
cost one `up_to_date` round, and they heal.

**Honesty argument (the core of the design).** The stamp claims exactly what the
`up_to_date` answer already claims — "your bytes are current as of this check" —
timestamped at the disposition instant with the server's own clock, which is the
same clock and the same claim shape as a re-serve's acquisition stamp (the
acquisition-time doctrine: an `up_to_date` IS a re-acquisition of currency,
minus the bytes). Every rung that answers `up_to_date` (tscache, P1 header rung,
store rung, probe) already owns the correctness of that claim, with its
documented staleness bounds (Paper's unfired-event bound, the 5 s stat horizon,
the mark latch + grace); the stamp adds **no new claim** and therefore no new
wrongness. A wrong `up_to_date` today already freezes the column until a dirty
broadcast or reconnect; a stamped wrong `up_to_date` extends exactly the same
freeze through the summary layer — and the dirty rung still outranks validation,
so broadcasts heal both identically.

**Rejected alternative — client-side inference (no wire change).** The client
could ratchet a stale-tile column to `tileStamp + 1` upon receiving its
`up_to_date` (it knows the failing bound from the retained frame). Rejected:
(a) the client would be MINTING freshness claims it cannot verify against the
server clock — the exact class the delivery-honesty refactor removed
(`sessionSatisfied` exists because fabricated client-clock stamps were banned);
(b) it has a genuine (if narrow) same-second false-clean edge when a content
save lands in the same header second the prior metadata save claimed, where the
margin protection degenerates. The server-stamped design has neither problem.

## 3. Wire design — one optional S2C channel, the summary discipline

`lss:col_stamps` (S2C, raw `byte[]` body, version-neutral — the far-player /
region-summary channel family, census 12 → 13):

```
version(u8)=1, dimension(utf8 ≤256B), baseSecond(varlong),
count(varint ≤ MAX_BATCH_CHUNK_REQUESTS=1024),
count × { packedPos(i64), stampDelta(zigzag varint vs baseSecond) }
```

- **No capability bit, no handshake change, no BatchResponse change.** The frame
  is sent ONLY to sessions that have sent a `lss:region_summary_req` this
  session — the summary request is already the "I do warm revalidation"
  declaration (the fire-and-forget-request-is-the-capability pattern, §4 of the
  summary plan), and stamps are useless to any client that doesn't validate
  tiles. Released clients (≤0.11.x) never request summaries, so they never
  receive a frame; if one somehow did, modern MC discards unknown custom
  payloads silently. The v20 batch-response shape is untouched — no
  same-dialect wire risk.
- **Server side:** each `up_to_date` disposition for a stamps-eligible session
  appends (packedPos, epochSecond-at-disposition) to a per-player per-tick
  accumulator beside the existing `SendActionBatcher` flush; one frame per
  player per tick, dimension = the player's registered dimension at flush.
  Sent on the summary lane's accounting (NOT in `bytes_sent` — the law A2
  identity stays exact; same discipline as `summary.bytes`).
- **Loss tolerance: none needed.** A dropped/unsent frame = today's behavior
  for those positions; they re-ask on the next rejoin and get stamped again —
  eventual convergence across sessions. Deliberately NO retention machinery
  (unlike the once-per-session summary frame, whose loss kills the whole
  exchange): stamps frames recur naturally. The writability guard may drop
  them freely.
- **Hostile decode caps** (FarPlayerWire discipline): version byte, dimension
  length, count cap, remaining-bytes floor before any allocation, contained
  decode, drop on dimension mismatch (the anti-stale binding, echoed like the
  summary frame).

## 4. Client apply — a pure monotonic ratchet

`ColumnStateMap.ratchetStamp(packedPos, second)`: if the position's leaf exists
AND its ts > 0 AND `second > ts` → set ts = second. Nothing else moves:

- **Never creates leaves** (hostile frames can't allocate — the
  applyTileValidation rule).
- **Marks untouched and still outrank.** A dirty/retry-marked position keeps its
  mark; classify's dirty rung fires regardless of the new stamp. A dirty
  broadcast crossing a stamps frame in either order still re-asks.
- **`validated`/`summaryValidated` untouched** — the ordinary `onUpToDate` path
  (which arrives with the same answer) owns those bits; the ratchet is pure ts.
- **ts ≤ 0 positions ignored** — an unstamped position has no claim to extend
  (and `sessionSatisfied`/NOT_GENERATED state is not touched).
- The ratcheted ts persists through the ordinary cache save (it IS the ts the
  cache stores) — the heal survives to the next session, which is the point.
- Ordering vs the batch response is idempotent both ways (monotonic ratchet;
  `onUpToDate` doesn't write ts). No cache-load buffering: answers only flow
  after declarations, which only flow after the cache gate.

Gating mirrors the send side: applied only when `enableRegionSummarySync` (the
consumer is tile validation), dropped contained otherwise. The reference twin
(`ReferenceColumnStateMap`) mirrors the ratchet and the fuzz differential gains
the op.

## 5. Config

**No new keys.** Structurally tied to the summary switches: the client only
requests summaries when `enableRegionSummarySync` is on (→ no request, no
stamps), and the server only serves summary-requesting sessions under
`enableRegionSummaries`. Turning either off turns stamps off with it.

## 6. Observability

- Server: `summary.stamps_frames`, `summary.stamps_entries`, `summary.stamps_bytes`
  (monotonic counters, summary group; excluded from `bytes_sent` like
  `summary.bytes` with the same SERVER_MONOTONIC audit note). Diag `Summary:`
  line extends with `stamps=<entries>/<frames>`.
- Client: `summary.stamps_applied` (ratchets that actually advanced a stamp) and
  `summary.stamps_ignored` (entries that no-opped: unknown/unstamped position,
  non-advancing second). Client diag Summary line extends. Both exporter
  contract files, `PaperSoakMetricsExporter` parity, `check_soak.py` KNOWN keys,
  `_srv`/`_cli` selftest fixtures.
- The summary-inert check (all counters zero on non-opt-in soaks) covers the new
  fields automatically — harness clients never request summaries, so the send
  gate keeps stamps at zero everywhere but the opt-in scenarios.

## 7. Tests & harness

- **Tier 1:** wire codec hostile twins (truncation, count overflow, dimension
  oversize, trailing bytes, unknown version); ratchet semantics pins (monotonic,
  leaf-gated, ts>0-gated, mark-preserving, validated-untouched); the fuzz op +
  reference-twin mirror; server-side accumulation pins (stamps only for
  summary-requesting sessions, per-tick flush, dimension binding, disposition
  second); census updates — both `WireParityTests` (12→13),
  `ClientReceiverCensusTest`, the NeoForge twins, and Paper's OUTGOING
  registration (`registerOutgoingPluginChannel` — Bukkit refuses to send on an
  unregistered outgoing channel; the incoming census doesn't cover it, so the
  send-registration needs its own pin).
- **Harness:** `warm-rejoin-summary` run 2 re-asks its residue and receives
  `up_to_date` in volume (recorded green: 1,567), so add floors: server
  `summary.stamps_entries >= 50`, client run-2 `summary.stamps_applied >= 50`
  (both scale-safe under the self-scaling suppression pin), plus selftest catch
  cases. The full three-session heal (stale → stamped → clean) is pinned at
  Tier 1 (frame → ratchet → applyTileValidation validates) rather than by a new
  three-run scenario — the live rig IS the three-run gate, and the counter
  floors make the mechanism's live firing checkable on every soak.
- **Live acceptance (the rig):** rejoin twice; second rejoin's `tiles_stale`
  must collapse toward "regions saved since last session" (tens, not ~500) and
  `columns_validated` rise accordingly.

## 8. Effort

~1–1.5 days: wire + service accumulation + client ratchet + counters/contracts
+ censuses + Tier 1 + harness floors. No store, no persistence, no protocol
version bump, no config surface.

## 9. v1.1 — the 1-Fable review fold (normative where it tightens the above)

1. **Stamp ONLY the three compare-backed rungs** — the router's tscache rung,
   the header-fresh delivery, and the store-stamp delivery. Every one performs a
   real per-recipient freshness compare at disposition. The done-bit rung
   (`resolvedAsDuplicate`/`hasDiskReadDone`) **never stamps** (review MAJOR-1:
   its honesty rests on a range-filtered invalidation channel, and stamping it
   composes with the client's lost-CLEAR ingest-failure retention into a
   PERMANENT ghost-terrain seal — the exact F2 hole, made unhealable); the
   cannot-improve flavors (oversized-enqueue rejection, clear-send-failure)
   never stamp (MINOR-4: the server holds fresher bytes it declined to ship).
   Excluding the done-bit rung loses no heal coverage: it only answers
   intra-session repeats of positions the compare-backed rungs already stamped.
   Mechanically: `SendAction.ColumnUpToDate` gains `stampSecond` (-1 = never
   stamp), set only at the three sites; accumulation happens at the existing
   drain choke point, which supplies the per-action `producerState` identity —
   the dimension binding comes from the SURVIVING state per action, never
   "player's dimension at flush" (MINOR-5: overworld/End coords overlap; a
   mislabeled frame fabricates freshness in the wrong dimension).
2. **The pending-change guard** (MAJOR-2: invalidation-drain latency must not
   be laundered into a cross-session seal): at stamp time, a position whose
   change is marked-but-undrained (`DirtyColumnTracker` pending) or whose
   region latch (`liveSaveMark`) is armed gets `stampSecond = -1` — answered
   `up_to_date` as today, just not stamped. This closes the save→drain window
   structurally for every event/hash-covered change; without it, any
   `dirtyBroadcastIntervalSeconds` in (15, 300] makes the seal reachable at
   legal config (the drain window is NOT pinned inside the 15 s margin).
3. **Paper ships stamping WITH the residual named and canaried** (MAJOR-3): an
   event-blind content change (the unfired-event class) leaves the tscache
   stale indefinitely, and a stamp issued in that state converts the
   session-transient wrong `up_to_date` into a cross-session seal that defeats
   the header layer. This is accepted-with-eyes-open: the residual equals the
   store rung's existing Paper exposure class, the store resweep
   (`lodStoreResweepSeconds`, Paper default 300 s) bounds the store-rung arm,
   and the canary rides `paper-store-unfired-event` (the edited column must not
   be stamps-sealed — its probe hash must still change). Fabric is fully
   covered (the save hook + `DirtyContentFilter` feed the guard in §9.2).
4. **Eligibility mark**: a per-UUID set in `RegionSummaryService`, written in
   `offerRequest` after decode + kill-switch, swept in `removePlayer` (network
   disconnect — survives dimension change, matching the per-dimension request
   re-fire); `handleRegionSummaryRequest` gains the CURRENT-dialect guard both
   platforms (the far-player subscription discipline — a legacy session must
   not become eligible). Pins: swept-at-disconnect, legacy-never-eligible.
5. **Frame splitting, never truncation** (MINOR-7): a tick's accumulator past
   1024 entries emits multiple frames — silent tail truncation would
   systematically drop the bulk regime the feature targets.
6. **Semantic stamp bounds at decode** (MINOR-8): `baseSecond` bounded against
   local receipt time + a skew allowance (mirror the stamp table's 3600 s), and
   per-entry deltas bounded; a violating frame drops whole. Without this a
   hostile/corrupt huge second ratchets ts toward MAX and seals the position
   against offline edits permanently. Hostile twins pinned.
7. **Client defense-in-depth**: `ratchetStamp` also skips retry-marked and
   dirty-marked positions — NOT sufficient alone (the same-tick `onUpToDate`
   consumes the retry mark before the frame applies — the ordering is recorded
   in the javadoc so a future "simplify client-side" doesn't undo the
   server-side narrowing), but cheap armor.
8. **Cross-session pin** (NOTE-10): a ratcheted ts must survive
   `detachForSave` → cache v4 round-trip → next-session re-declaration — the
   heal's one cross-session link, previously untested.
9. **The live heal gate** (NOTE-10): `scripts/stamp_heal.sh` — phase 1 runs
   `warm-rejoin-summary` (whose run 2 gets stamped), phase 2 carries world AND
   client cache forward (the summary_evicted.sh pattern) into a new
   `stamp-heal-rejoin` scenario: one run, one frame, and the named check pins
   the HEADLINE claim — `tiles_stale` collapsed to the designed residue (the
   phase-1 kick-save's player tile) while `columns_validated` stays bulk-scale,
   plus low re-ask volume. This is the stale→stamped→clean proof on every
   burn-in instead of rig anecdote.
10. **Accounting notes** (NOTE-11): first unhealed rejoin pays ~10-11 B/entry
    of stamps traffic (~2.3 MB at the rig's 221k) — once, amortized from the
    next session; `summary.stamps_bytes` gets its own wrs ceiling so the
    existing `summary.bytes` ceiling stays sharp. Support-line record
    (NOTE-12): if summaries ever backport, stamps travel with them — wire is
    never tiered.

## 10. v1.2 — the 3-Opus implementation review fold (normative over §9)

1. **The narrowing is now TWO rungs, not three** (honesty MAJOR: the store-stamp
   conversion's own doctrine is "delivery honesty — never a fabricated fresh
   stamp"; a re-serve hands the STORED acquisition second, so a "verified now"
   stamp claims strictly more than the bytes the rung declined to send, and the
   store's staleness bound — boot sweep + the invalidation channel, NO periodic
   resweep on Fabric at the default 0 — is not provably inside the margin). Only
   the router's tscache rung and the header-fresh delivery stamp; the store
   conversion is pinned NEVER-STAMP, and the site CENSUS
   (`stampedSiteCensusHoldsTheNarrowing`) enforces exactly two 5-arg construction
   sites. Store-served columns heal via the tscache rung on a later ask.
2. **The §9.2 guard is TWO-PHASE** (honesty MAJOR: the tracker set empties at
   DRAIN on the tick thread, but the tscache/store invalidation is a mailbox event
   applied later on the processing thread — stamps issued in the drain-to-apply
   gap seal permanently at any `dirtyBroadcastIntervalSeconds > 15`, a legal
   `/lsslod set` value up to 300). `drainDirty` moves positions into an
   `invalidating` set `isPending` also consults; the invalidation APPLY releases
   them via the broadcasters' `onApplied` callback (`confirmInvalidated`) — the
   guard now spans mark → applied-evidence-gone with zero residual for every
   mark-covered change.
3. **The stamps sinks carry the writability drop** (all three lenses): the frames
   land at exactly the join/portal moment the yield/pacing machinery protects, and
   this was the only raw-body S2C lane without a pressure consult. NOT_WRITABLE →
   a plain UNCOUNTED drop (never RETRY — §3's loss tolerance is the design).
4. **The heal gate is rebuilt on a primed phase 1** (client MAJOR: on
   warm-rejoin-summary's deliberately-clean geometry — its clearcache re-stamp is
   exactly the move that erases the inversion — healed and unhealed phase-2 runs
   produced identical counters; the gate passed with the feature disabled).
   `stamp_heal.sh` = `stamp-heal-prime` (wrs WITHOUT the clearcache and poison;
   its named check PINS the before-state: run-2 `tiles_stale+unknown >= 8` and
   `stamps_applied >= 400`) → `stamp-heal-rejoin` (the after: `stale+unknown <=
   5`, clean bulk, re-asks bounded ≤ 2000). The rejoin ceiling is the measured
   STRUCTURAL after-set + 1: phase 1's own shutdown kick-save re-stales the
   spawn/player corner tiles after the session's last stamps (the heal is
   measured one session behind by design — one frame per session), plus one
   persistent no-evidence doubt tile; live 2026-08-20: before 8+1, after 3+1,
   1,876 stamps primed / 256 at rejoin, 1,121 columns validated off the frame. NOTE: the chain stays OUT of `soak.sh all` (chain-only, like the
   evicted chain) — §9.9's "on every burn-in" is corrected to "on every chain
   run".
5. **§9.3's canary claim corrected: accepted-open, UNCANARIED.** The
   paper-store-unfired-event canary is structurally impossible — that scenario's
   soak client is summary-gated off, and the inert check would red on any stamps
   counter movement there. The residual is instead pinned DELIBERATE at Tier 1
   (`theEventBlindStateStampsByAcceptedDesign`): no mark + no latch ⇒ the
   predicate stamps; the seal holds until the chunk's next save, the store resweep
   bounds the store-side arm (moot for stamps since §10.1), and Fabric is fully
   covered by the hash-confirmed save hook.
6. **Eligibility is dialect-conjunctive at the SINK too** (§9.4 completed): a
   session that re-handshakes DOWN to a legacy dialect keeps its request mark
   until disconnect, so `eligible()` re-checks `dialectOf == CURRENT` (the
   far-player re-handshake discipline); the legacy-never-eligible pin exists now
   (Paper ingress test with a v18-marked session — `dialectOf` defaults CURRENT
   for unknown UUIDs, so only an explicit mark exercises the guard).
7. **Hardening set**: predicate short-circuits on eligibility FIRST (the dirty
   tracker's monitor — shared with the save hook — stays off the router's hot
   path on servers with no summary-requesting session; the SAM now takes the
   player UUID); `isClaimSuppressed` is a READ-ONLY latch probe (the shared
   helper WRITES the grace deadline — a hot third writer raced it and consumed
   the header rung's clear-side grace early; not-yet-started grace reads as
   still-suppressed, strictly conservative); the stamps flush is
   Throwable-contained so an Error cannot eat the tick's batch responses, keyed
   by the identity-checked STATE, and pinned to flush BEFORE the batch (FIFO then
   applies the ratchet while same-tick marks are still set — the client armor
   order §9.7 mis-described, now corrected in the ratchet javadoc and pinned);
   `ratchetStamp` also skips sessionSatisfied (the applyTileValidation exclusion
   mirrored — the lost-CLEAR park retains a positive stamp under it) and guards
   `second <= 0`; `ColumnUpToDate`'s compact ctor normalizes a dimension-less
   stamp claim to never-stamp; `ColumnStampsWire.encode` bounds seconds
   producer-side (one clock-damaged entry would discard whole frames at every
   client); the fuzz ratchet op was added by WIDENING the kind space to 101 (not
   by halving prune). Paper's `registerOutgoingPluginChannel` pin (§7) is MOOT —
   Paper sends via NMS DiscardedPayload, bypassing Bukkit's Messenger (recorded so
   nobody "fixes" it).
8. **Known-unpinned residuals, named**: a latched region releases the stamping
   predicate only when some OTHER caller refreshes its header (the probe is pure
   memory — safe direction, costs heal coverage; symptom: `stamps_entries` far
   below `up_to_date` with no error); server-clock-ahead-of-client beyond the
   3600 s skew silently drops every frame client-side (diagnosable cross-exporter:
   server entries > 0, client applied == 0 — the wrs floors' exact shape).

## 11. v1.3 — the final whole-branch panel fold (2-Fable + 3-Opus)

Landed fixes:

1. **Per-player FIFO ready queues in `RegionSummaryService`** (co-found MAJOR —
   concurrency + server-honesty lenses): the RETRY retention's tail re-add on the
   shared queue could send a FRESHER frame before the retained stale one; the
   client apply has no recency guard (deliberate — §2's honesty argument assumes
   stale-then-fresh), so the stale frame's lower margined tile stamps re-set
   `summaryValidated` on positions the fresh frame's revocation just cleared — a
   false-clean seal, permanent for tiles beyond dirty-push range. Now: one FIFO
   per player (assembly order unconditional; RETRY leaves the frame at ITS queue's
   head and stops only that player's drain — the head-of-line blocking across
   players is gone too), `removePlayer` sweeps the queue, the sender is
   per-frame Throwable-contained (throw = the vanished DROP belt), and the sink
   re-checks CURRENT dialect (the stamps lane's rule mirrored — a pre-handshake
   request reads as CURRENT on an untracked UUID, and a session can re-handshake
   DOWN before the frame drains). Pinned by
   `retainedFramesSendInAssemblyOrderPerPlayer` + `removePlayerSweepsRetainedFrames`.
2. **The two-phase guard is BATCH-COUNTED** (concurrency lens): `invalidating` is
   now position → outstanding-batch COUNT (a stale confirm from a slow first
   invalidation released a later batch's guard early under overlapping drains —
   reachable at `dirtyBroadcastIntervalSeconds` ≥ the store-invalidate latency),
   and the broadcaster callback is once-guarded in `invalidateTimestamps` (the
   requeue-after-throw path replays applied batches; a double confirm must not
   decrement a later batch's count). `drainAll`'s comment now states its real
   contract: shutdown-only, deliberately NO handoff — a future non-shutdown
   caller must use `drainDirty`. Pinned by
   `overlappingInvalidationBatchesHoldTheGuardUntilTheLastConfirms`.
3. **The site census enforces arity, not idiom** (pins MAJOR): recursive scan of
   ALL FIVE modules' production sources, balanced-paren top-level-comma count
   (== 4 ⇒ 5-arg construction) plus an arg-taking `.stampSecond(` call census —
   the old one-directory regex was evadable by hoisting the second into a local,
   relocating the site, or a third site in a matching file.
4. **The rejoin gate's four adjacency-proven legs got isolating doctored cases**
   (pins MAJOR — mutation-verified: each leg's deletion now reds the selftest);
   `stamp-heal-prime` also gained run-2 disc completeness (the only 2-run
   scenario without it) and dropped its unread `client.requested_total`
   declaration; `evicted-tscache-rejoin` declares
   `server.tscache.size_per_dimension` (its eviction-premise belt's field).
5. **The Fabric C2S receiver census exists now** (pins MAJOR — the census
   family's one unpinned link): `everyRegisteredC2SPayloadHasAServerReceiver`,
   the identical silent-drop mode the S2C half was created for.
6. **Hardening batch**: `ColumnStampsWire.encode` refuses null/empty/over-cap
   dimensions (the sibling codec's producer-guard rule — a bad dimension shipped
   frames every client rejected WHOLE while counters said sent);
   `RegionStampTable.refreshedHeader` publishes the new snapshot BEFORE the new
   stat deadline (the lock-free fast path could serve the previous horizon's
   header as fresh for the whole refresh-IO duration); the summary window clamps
   its EDGES (center bound = maxTiles − radius — an off-center max-radius request
   reached ~2× the honest window; `radiusAndCenterClampToTheServerWindowNotTheProtocolMax`
   re-pinned); the pump sweeps anchor-less eligibility marks (the Folia
   quit-race could resurrect `requestedThisSession` after `removePlayer`, with no
   other sweep for the server's life); the client `/lss diag` Summary line also
   renders when only stamps counters moved; the fuzz ratchet op carries a
   fired-at-least-once premise AND the needs⇔classify invariant is asserted
   BIT-FOR-BIT per pool position (`needsBitForTest` — the aggregated ring probes
   could not see a stuck-OFF needs bit behind a needing sibling);
   `enableQuadtreeScan` gained the sibling config round-trip pin; the
   entries/frames diag order is pinned with asymmetric counts; soak.sh refuses
   the Fabric-only chain phases on other platforms and the dead
   `STAMP_HEAL_SCENARIOS` variable is gone.

Accepted-open (recorded, deliberately not machined):

- **The client summary apply is an unbounded trusted-server surface**: a hostile
  server can send repeated max-radius frames costing ~2-5 ms of render thread
  each (17k tiles × 16 leaf lookups) plus a revocation-driven ring-reopen walk.
  Not rate-limited: the client already extends the server far deeper trust (raw
  column ingest), and every legit flow is one frame per dimension entry.
- **`everObserved` is narrower than the deleted-after-observed doctrine**
  (listed-but-never-examined and all-absent-header regions read NO_REGION after
  deletion): sound because BOTH sentinels are pinned client-side skips; the
  residual is counter attribution. Commented at the predicate.
- **The sweeper holds a region-entry monitor across its header read** (~one 8 KiB
  read + two stats): a reader-pool thread wanting the SAME region can block
  behind it ahead of the disk-read gate. Bounded (per-region, ms-scale, and the
  chunk rung usually rides the tscache instead); the fix (IO outside the
  monitor + CAS) is real machinery for a stall no live gate has ever surfaced.
- **Fabric's `handleRegionSummaryRequest` hostile-decode containment is
  unexercised by a dedicated test** (Paper's twin pin covers the shared logic;
  Fabric's extra branch is the throttled decode WARN).
