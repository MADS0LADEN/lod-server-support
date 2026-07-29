# Tech-review round 2 fix plan — 2026-07-29 (R2-1..R2-11)

Round 2 of the review campaign (three rotated lenses: session/lifecycle + V16 shims,
serialization/platform edges, flow-control/counter integrity) against
`fix/tech-review-fixes` @ 3c9366b. Three MAJORs and a set of MINOR/NITs, all
inline-verified before this plan. Branch: `fix/tech-review-round2` (stacked on the
round-1 branch; PR based on it until #67 merges).

| # | Sev | Finding | Files |
|---|-----|---------|-------|
| R2-1 | MAJOR | NBT disk parse stricter than vanilla: any palette parse error drops the WHOLE section silently (`result().orElse(null)`); all-sections-fail returns `byte[0]` → served as an authoritative 0-section CLEAR that wipes the client's correct cached LOD. Mainstream trigger: vanilla version-upgrade block renames in unvisited chunks (raw pre-DFU NBT, no DataVersion check). | `NbtSectionSerializer` + Paper twin |
| R2-2 | MAJOR | Bandwidth limiters gate on token PRESENCE (`tokens > 0`) and forgive overdraft (`max(0, tokens-bytes)`): sustained rate converges to ~20 payloads/s/player regardless of cap when payload > per-tick refill (3–40× over on tight caps). Plus refill truncation: `lastRefillNanos = now` even when refill rounds to 0 → sub-20 B/s allocations starve forever. | `PlayerBandwidthTracker`, `SharedBandwidthLimiter` |
| R2-3 | MAJOR | Paper `/reload` orphans every connected session: fresh service, empty player map, client never re-handshakes mid-connection (verified: no producer can fire), want-sets silently dropped until manual rejoin. Plus the concurrent-handshake-into-dead-service sliver. | `LSSPaperPlugin`, `PaperRequestProcessingService`, `ClientSessionGate`, `LSSClientNetworking` |
| R2-4 | MINOR | Cross-capability re-handshake (caps=0 or version mismatch) leaves the prior registration live and streaming — hostile/buggy clients only, but the ladder's own "never register a consumer-less client" contract has no inverse. | `LSSServerNetworking`, `LSSPaperPlugin` |
| R2-5 | MINOR | Disk path serves light-only sections OUTSIDE the world section range (vanilla saves light entries at blockRange±1): guaranteed live/disk divergence at height-cap builds; client clamp can truncate real trailing sections or dispatch out-of-world sectionY to consumers (ingest-fail park loop). | `NbtSectionSerializer` + Paper twin, reader plumbing |
| R2-6 | MINOR | Stale live palette entries (mined ore never pruned from `PalettedContainer`) defeat masked-byte determinism: live rebuilds (bytes B1) where disk doesn't (B2), violating the filter's own same-content-same-bytes contract; `masked_sections` counts sections with zero hidden blocks. | `XrayMaskFilter` + Paper twin |
| R2-7 | MINOR | Engine-adoption UNREADABLE is cached per-dimension for the service lifetime, defeating the probe's deliberate transient-null non-latch: one early null locks the dimension to LSS-keys fallback until restart. | `XrayMaskManager` + Paper twin, `AntiXrayCompat` |
| R2-8 | MINOR | `PaperChunkGenerationService.completeAsyncLoad` discards the chunk Moonrise DELIVERS and re-fetches via `getChunkNow` — re-opening the completion-window unload race the completion-thread design exists to close; null-chunk and vanished flavors share one counter + warn latch. | `PaperChunkGenerationService` |
| R2-9 | MINOR | `/lsslod diag` Throughput sums per-state counters (die on every dimension change/rejoin) divided by service-lifetime uptime — wrong after any Nether trip; inconsistent with the same command's Bandwidth line. Service-scoped `TickDiagnostics` totals already exist. | `DiagnosticsFormatter` |
| R2-10 | MINOR | Latent A1 false-positive family (documentation-first): a want-set batch counted `requested_total` at a failed transport send, and server dispositions lost in a failed batch-frame send, have no RHS term. Same class as the documented A5 latents; documented nowhere. | `check_soak.py` comment, CLAUDE.md flake catalog |
| R2-11 | NITs | onJoin defensive teardown; PaperWorldHandler silent no-op extractor warn; `isClearColumn` buffer release; window-rate N/(N−1) bias; activeCount counts just-removed players for one tick; stale comments (PaperSectionSerializer Folia claim, DirtyContentFilter version pin, AntiXrayCompat isBound note); v16 prune-before-send accepted-residual comment; dirty-notice send-failure observability note in the flake catalog. | various |

---

## R2-1 — vanilla-lenient NBT section parse + error-triage for all-fail

Both `NbtSectionSerializer.parseSection` twins:
1. `blockStateCodec.parse(...)`: replace `.result().orElse(null)` with
   `.resultOrPartial(err -> <throttled WARN>)` — vanilla's own load leniency
   (`SerializableChunkData` promotes partials): an unknown palette entry becomes the
   container default (air), the section SURVIVES. A truly unrecoverable parse (no
   partial) still drops the section — now logged (shared static `LogThrottle`, 60 s,
   per class) — and increments a local `unparseableSections` count.
2. In `serializeChunkNbt`: if `parsed.isEmpty() && unparseableSections > 0`, THROW
   `IOException("N unparseable sections")` instead of returning `byte[0]`. The reader's
   existing error triage turns that into a TRANSIENT not-found (never memoized, never an
   authoritative clear): gen-enabled servers escalate to a generation ticket, which loads
   the existing chunk through the REAL DataFixer pipeline and serves it correctly;
   gen-disabled parks NOT_GENERATED (healed by dirty/reconnect) — both strictly better
   than wiping the client's correct cache. A genuine all-air column (zero failures)
   keeps the pinned `byte[0]` End-void sentinel unchanged.
3. Re-rate the delivery-honesty residual (#8) in the design doc: the trigger is
   mainstream (vanilla upgrades), and the partial-substitution rung is serializer-local.

Pins: fabric + paper Tier-1 — a section whose palette contains one bogus entry among
valid ones parses to a kept section with air substituted (decode and assert content);
an all-bogus column throws (assert `IOException` from `serializeChunkNbt`); the End-void
all-air sentinel test stays green; wire-parity twins for the partial-substitution case.
Verify `DataResult.resultOrPartial`'s exact 26.2 signature at implementation.

## R2-2 — debt-carrying token buckets + refill-remainder fix

`PlayerBandwidthTracker` and `SharedBandwidthLimiter` (verify the shared one's exact
shape at implementation — same floor documented at :41):
1. `recordSend`: remove the zero floor — `availableTokens -= bytes` may go NEGATIVE
   (debt). `canSend`'s `> 0` gate is unchanged, so an oversized payload is still
   admitted once (no sufficiency-gate deadlock for payloads above the burst cap) but
   the NEXT send waits until the debt refills past zero — sustained rate converges to
   the cap. Debt is bounded by one payload beyond zero (sends are canSend-gated), so no
   debt cap is needed.
2. Refill: advance `lastRefillNanos` only when the computed refill is > 0 — fractional
   allocations accumulate instead of being truncated away every 50 ms (closes the
   permanent-starvation corner; with the 1 s elapsed cap, any allocation ≥ 1 B/s
   eventually refills).
3. Testability: inject a `LongSupplier` nano-clock (constructor/seam, default
   `System::nanoTime`) — the frontier-damping precedent — so the convergence pin is
   deterministic.

Pins: new deterministic tests — sustained send of payloads 3× the per-tick refill
converges to ≤ cap over a simulated second (both tracker and shared limiter); a
payload larger than the burst cap is admitted once then blocked until debt clears;
zero-refill polls accumulate (no starvation at 1 B/s allocation). Audit
`FlushSendQueueTest`'s token mechanics (it deliberately exploits one-token-sends-one-
payload; the admission side is unchanged, but any test that relies on floor-to-zero
refill timing gets re-derived). Soak: `bandwidth-throttle` must stay green (its small
columns sit inside the refill — behavior unchanged there by design; law B2 headroom
should if anything improve).

## R2-3 — /reload session re-attach (Paper server prompt + client re-handshake)

Two halves, both sides of the wire, no new payload types:

**Server (Paper only — Fabric has no /reload):** in `handleBatchRequest`, a
successfully-DECODED v18 batch from a player with NO registered state is proof of an
orphaned LSS client (vanilla clients never speak the channel; the deferred-reply
registration means a live client cannot declare before it is registered; v16 frames
fail the v18 decode and land in hostile-frame containment, so ancient clients can never
receive the prompt). Reply with the current v18 SessionConfig as a RE-HANDSHAKE PROMPT,
rate-limited per-UUID (60 s, map swept on PlayerQuit). The prompt-on-decode gate also
heals the handshake-into-dead-service sliver.

**Client:** `ClientSessionGate` tracks `lastHandshakeSentMs` (stamped by every handshake
send). A valid SessionConfig arriving when the client did NOT handshake within the last
`HANDSHAKE_RECENCY_MS` (10 s) is treated as a server-side session loss (Paper /reload):
after the PINNED second-config teardown+replace runs unchanged, send ONE fresh
handshake (rate-limited: min 30 s between recovery handshakes). Loop-freedom: the
recovery handshake's deferred config reply arrives well inside the recency window →
no further re-handshake. The v16 discovery/downgrade machinery is untouched (this rule
fires only on post-config sessions).

Old (pre-round-2) clients keep today's behavior (broken until rejoin — the prompt never
reaches them because their frames don't decode as v18 batches); document that in the
release notes when this ships. Live /reload validation is manual (test-server.sh) — the
unit pins carry the contract.

Pins: ClientSessionGateTest — config-without-recent-handshake triggers exactly one
recovery handshake (injected clock + sender seam); config right after our own handshake
does NOT; the 30 s recovery rate limit; the pinned teardown-before-replace stays green.
Paper glue test — unregistered decoded batch sends the prompt; rate-limited; a
non-decoding frame gets containment, never a prompt; a registered player's batch never
prompts.

## R2-4 — unregister on NO_CONSUMER / VERSION_MISMATCH re-handshake

Both platforms: when the gate's NO_CONSUMER or VERSION_MISMATCH rung fires for a player
that HAS a registered state, remove that registration (the same removal path a quit
takes, incl. the v16 session shed already performed on replying outcomes). Pins: one
test per platform (re-handshake with caps=0 stops the stream / removes state; the
existing reply-semantics pins stay).

## R2-5 — world-range gate for disk-parsed sections

Plumb the level's `minSection`/`maxSection` (known at submit time in both readers) into
`serializeChunkNbt`; drop parsed sections outside the range before the band filter.
Restores live/disk parity at height-cap builds (the live path iterates
`chunk.getSections()` which cannot hold out-of-range sections) and stops out-of-world
`sectionY` reaching consumers. Pins: Y=maxSection+1 sky-cap entry dropped, in-range
boundary kept, existing band-rule tests green.

## R2-6 — mask identity when zero cells replaced

`XrayMaskFilter.mask` twins: track whether any cell was actually replaced during the
rebuild; if zero, return the ORIGINAL section (identity) — the call sites' existing
`masked != section` checks then skip the swap and the counter. Restores the
same-content-same-bytes contract for stale-palette sections and stops `masked_sections`
counting no-op rebuilds. Pins: stale-palette section (palette lists ore, content has
none) returns the same instance on both twins; golden fixture unchanged.

## R2-7 — engine-adoption transient-null retry

`AntiXrayCompat`'s probe already distinguishes the transient null-controller flavor;
surface that to `XrayMaskManager.entryFor` and DON'T cache it: re-probe on subsequent
serves until a terminal outcome (adopted/absent/unreadable-config), latching to the
fallback with the existing once-warn after K=100 consecutive transient probes. Paper
twin mirrored. Pins: manager tests with a counting stub probe — transient null
re-probes then adopts on later success; K-latch fires the warn once; terminal outcomes
still cache permanently.

## R2-8 — serialize the delivered chunk in the Moonrise completion

`completeAsyncLoad` passes the callback's `ChunkAccess` into `extractColumnData`; when
it is a `LevelChunk` (always, at ChunkStatus.FULL) serialize IT instead of re-fetching
via `getChunkNow` — closing the completion-window unload race by construction; the
re-fetch remains only as the non-LevelChunk fallback ladder rung (still transient).
Split the shared counter/warn latch into null-chunk vs vanished flavors (internal
counters + diag; exporter schema untouched). Pins: the existing extraction-ladder unit
tests re-anchored on the delivered-chunk path; a vanished-window test that the OLD code
triaged transient now serves successfully.

## R2-9 — service-scoped Throughput in /lsslod diag

`DiagnosticsFormatter.collectDiagData`: source sent-columns/bytes from the
service-scoped `TickDiagnostics` totals (they exist precisely to survive state
teardown) instead of summing live states. Update the wiring pin in
DiagnosticsFormatterTest; golden line format unchanged. Benchmark exporter untouched
(single-session).

## R2-10 — document the A1 latent family

`check_soak.py law_A1` comment + CLAUDE.md flake-catalog entry mirroring the A5
latents: a failed client transport send counts `requested_total` with no RHS term
(RequestMetricsTest pins count-at-send as intended); a failed server batch-frame send
loses up to 4096 dispositions similarly; both are dying-connection/boundary shapes the
quiescent windowing excludes. No code change.

## R2-11 — NITs

onJoin defensive `teardownManager` before reset; PaperWorldHandler warn-once when a
configured event's extraction yields nothing recognizable; `isClearColumn` release in
finally; `TickDiagnostics.getWindowBytesPerSecond` divides by (N−1) intervals;
`activeCount` increments after the `isRemoved()` check; comment refreshes
(PaperSectionSerializer Folia probe claim, DirtyContentFilter 26.1.2 verification note
→ reword to 26.2-line reality, AntiXrayCompat isBound()-true-with-null note, v16
prune-on-dispatch accepted-residual note); dirty-notice send-failure invisibility noted
in the flake catalog beside R2-10's entry.

## Plan-review amendments (applied — these OVERRIDE the sections above)

The adversarial plan review verified every claim and required these changes:

1. **R2-1: authoritative-miss null, not IOException.** The throw variant reds law A7 on
   any corrupt-column world (disk.errors is non-optable), spams unthrottled stacks at
   1 Hz per unparseable column, skips the miss memo (durable disk fact re-read forever),
   and doesn't compile through `AntiXrayCompat.callSerializing`. `serializeChunkNbt`
   returning null is ALREADY the authoritative-miss contract (non-FULL chunks) — same
   dispositions (gen ticket → real DFU load → correct serve; gen-disabled park), zero
   plumbing. Additionally: ANY truly-unparseable section (no partial) makes the whole
   column unservable (null) — serving with a silently-missing section stamps a
   persistent hole no re-declaration heals. Renamed-block partials (air-substituted,
   section kept) are the accepted residual — wrong-but-load-shaped data until vanilla
   load+save fires dirty; the DataVersion-gate alternative was REJECTED (it would turn
   every upgraded-world LOD disc into a generation storm). Biomes stay deliberately
   lenient (whole-container plains fallback). "Healed by reconnect" was overstated:
   gen-disabled parks re-park on reconnect; the heal is vanilla load+save.
2. **R2-2:** advance the refill clock by CONSUMED time
   (`lastRefillNanos += refill * NANOS_PER_SECOND / allocation`), not conditionally —
   the conditional variant still truncates up to ~49% at threshold allocations. Name and
   unit-pin the shared-limiter fairness change (one player's oversized payload now
   stalls the shared bucket for debt/cap seconds — correct enforcement, changed
   semantics). The injected clock must seed `lastRefillNanos` at construction. Test
   audit verified: existing FlushSendQueue/SharedBandwidthLimiter tests survive debt
   unchanged; bandwidth-throttle soak measured inert (4157 B avg payload vs 13107 B
   refill).
3. **R2-3 REDESIGNED (the planned version was FLAWED).** v16 and v18 batches share ONE
   decoder — the planned v18-config prompt would hard-kick supported v16 clients after
   /reload (6-field decoder, 4-field payload, buffer underflow). Ship instead: the
   prompt is the **v16-dialect 6-field SessionConfig** (sender exists). v16 clients
   parse it cleanly and remain broken-until-rejoin (today's behavior); v17/v18 clients'
   EXISTING downgrade guard (v16 config + established v18 session → re-announce v18
   handshake) performs the heal with ZERO client changes — all fielded guard-carrying
   clients recover. The entire client half of the original design (recency rule, rate
   limit, its pins) is DELETED. Server side kept: prompt only on a successfully-decoded
   batch with `state == null` STRICTLY (the mid-registration window is a healthy
   client), per-UUID 60 s rate limit in a concurrent map swept on PlayerQuit; the
   Folia dimension-change remove→register window can fire one spurious prompt
   (bounded by the rate limit, coincides with the client's own reset — documented).
   Client-side change limited to rewording the guard's warn text (it currently blames
   a discovery race) — next client release. Corrections of record: the prompt does NOT
   heal the handshake-into-dead-service sliver (no config → no batches → no prompt;
   that client degrades to a spurious v16 session via discovery — pre-existing,
   documented); clients with `enableV16ServerCompat=false` see the prompt as an
   incompatible config and disable (they were orphaned anyway). A live /reload smoke
   on test-server.sh is REQUIRED-once for this commit (legacy-client safety is the
   design's whole pivot).
4. **R2-4 DOWNGRADED to documented residual.** Two Tier-2 pins assert the opposite
   deliberately (`ServiceLifecycleGameTests` NO_CONSUMER keeps-registration :611-618,
   VERSION_MISMATCH zero-mutation :699-725) — this is a pinned decision (a stray
   duplicate handshake must not kill a live stream), not an oversight. Reachability is
   hostile-only, harm bounded. Action: rationale comments at both rungs + this note;
   no behavior change.
5. **R2-5:** the 2-arg `serializeChunkNbt` overload stays range-free (the committed
   golden corpus serializes Y=-128; ~60 test call sites) — only the production
   3-arg/range path gates. Range-check BEFORE parse (an out-of-range garbage entry must
   not count toward `unparseableSections`). Envelope tests pass explicit ranges (a
   Mockito-defaulted level reads [0,0]). Premise restated: the client clamp is a
   section-COUNT clamp; raw sectionY reaches consumers — both true, fix unchanged.
6. **R2-6 REDESIGNED (the planned version was FLAWED).** Identity-return reintroduces
   the pinned-away ore-presence oracle (a stale palette listing mined ore IS the leak
   the rebuild prunes — XrayMaskFilterTest pins the pruning). Ship: keep the rebuild,
   fix ONLY the counter (count masked_sections when ≥1 cell was actually replaced);
   document the stale-palette live/disk byte divergence as an accepted residual (cost:
   one spurious re-serve per such save; DirtyContentFilter is fail-open).
7. **R2-7:** the transient flavor is NOT expressed in the probe's result type today —
   add it to the sealed `Unreadable` shape (public API change; AntiXrayCompatTest's
   no-latch assertions move with it). The Paper twin has no transient rung — "mirrored"
   becomes "no-op, documented". Suppress the per-evaluation info log during the retry
   window (the K=100 latch bounds probing, not logging).
8. **R2-8:** the LevelChunk instanceof guard is LOAD-BEARING (no in-repo evidence for
   the always-FULL claim), the mock-based tests re-anchor on `mock(LevelChunk.class)`
   (else they silently test the fallback), and the pinned diag literal
   (`null_failures=%d`) updates with the counter split. Exporter untouched (verified).
9. **R2-11(a) INVERTED:** the window-rate bias is one extra SAMPLE in the numerator
   (N samples / N−1 intervals) — drop the oldest bucket from the sum; the time base is
   already correct. TickDiagnosticsTest re-derived.
10. **R2-10:** count-at-send pin is `LodRequestManagerTest:748` (not RequestMetricsTest
    alone — verify at implementation); add R2-3's post-/reload unregistered-declaration
    window to the documented family.

## Commit grouping

1. R2-1 + R2-5 (NBT parse leniency + range gate — same files, shared tests)
2. R2-2 (bandwidth debt)
3. R2-3 + R2-4 (session re-attach + unregister rungs)
4. R2-6 + R2-7 (mask identity + adoption retry)
5. R2-8 (Moonrise completion)
6. R2-9 + R2-10 + R2-11 (diag source, docs, NITs)

## Validation

Tier 1 fabric + paper; Tier 2; Tier 3; both release builds. Soaks (sequential, no
concurrent builds): fresh-backfill, bandwidth-throttle (R2-2's law-B2 gate),
dirty-broadcast, warm-rejoin. Manual: none required to land (the /reload flow is
unit-pinned; a live test-server /reload smoke is a nice-to-have noted in the PR).

## Out of scope

Runtime AntiXray config-reload re-adoption (R2-7 covers only the transient-null flavor);
Fabric-side /reload analogues (none exist); benchmark exporter throughput source;
`recordSendCycle` count-at-send semantics (pinned intent — documented instead);
backports to support lines.
