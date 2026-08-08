# Pre-D3 whole-program review — 3 Fable lenses, 2026-08-08

Scope: `git diff 1f6e8a33e853..main` (the entire v0.10.0 program). User-directed
gate: findings must be resolved/dispositioned before D3 re-ports prep. This file
is the durable record; the fix round's outcomes are appended per finding.

## Lens 2 — server pipeline/perf (COMPLETE: no CRITICAL/MAJOR)

Verdict: release-sound as merged. Items queued (none gating):
- L2-1 MINOR MemoryLodStore quiesce: `offeredForTest` bumps AFTER the offer —
  multi-producer quiesce can read complete with a deposit mid-flight (test-seam
  only; all current tests single-producer). Fix: bump before the offer loop.
- L2-2 MINOR ChunkDiskReader:229 `rawServes` increments on EMPTY fetch results
  too — the `raw_serves=` diag overstates on miss-heavy workloads.
- L2-3 MINOR SqliteLodStore ~1302: the migration batch rides the shared writer
  txn — a walk-batch SQL failure rolls back up to WRITE_TXN_ROWS-1 already-
  applied deposits (bounded: derived data re-warms). No pin for deposit
  survival across a faulted walk batch.
- L2-4 NIT tile-cache warn condition includes the exact-epoch collision
  (message slightly wrong for that 1-second case).
- L2-5 NIT ceiling+yield armed together: ceiling-deferred ticks don't advance
  the yield starvation floor's clock (consistent with pinned F2-7; doc gap).
- L2-6 NIT serializer statics are JVM-global across gametest service instances
  (rationale comment exists only on SELECTIVE_FALLBACKS).
Verified clean: D0 thread confinement, §2.2 router dispositions, yield loss-class
heals, direct-emit/reader-pool thread safety, schema-4 walk hygiene, save
scheduler one-drain bound.

## Lens 3 — harness/gates/docs honesty (COMPLETE: 5 MAJOR / 13 MINOR / 6 NIT)

MAJORS (gate the D2 tag text / D3):
- L3-1 MAJOR store_migration_gate.sh overlap premise is timing-vacuous: the
  ~2.4k-row walk finishes ~8 s after boot, the client joins tens of seconds
  later — every "warm from 19-rows" serve actually hits migrated v20 rows; a
  broken 19-row serve rung still PASSES. Needs an overlap lever/ordering
  assertion (no walk gauge exists in snapshots).
- L3-2 MAJOR the C4 legacy-FNV chain is self-anchored (fixtures computed by
  `legacyContentHashFnv`, validated by the same function; only the empty-input
  basis is pinned, identical for FNV-1/FNV-1a). Body drift keeps tests green
  while a real v0.9.x upgrade purges the user's store. Fix: one non-empty
  known-answer vector (like contentHash's RFC vectors).
- L3-3 MAJOR PaperLegacyEgressTest v18/v16-splice-vs-native pin is f(x)==f(x)
  (both inputs byte-identical by an earlier pin in the same class); cannot fail
  for its named property. Fabric twin does the real decomposition.
- L3-4 MAJOR release notes "allocates ~30% less memory per column" matches no
  ledger measurement and is directionally WRONG end-to-end (serve alloc/col
  324→515→453 KB vs v0.9.x = net higher). Rewrite the bullet honestly.
- L3-5 MAJOR CLAUDE.md protocol-19 bullet cites deleted `V18CompatTracker`,
  the removed `V18Compat:` diag line (now `Dialects: v20=…` via
  WireDialectTracker.diagLine), and the renamed test. D1 sweep missed the C1
  rename.
MINORS: L3-6 profile_disk_read dialect_ok missing from the return-1 ladder;
L3-7 backfill_profile standalone `run base` never repoints the worktree (N7
unmirrored) and compare never gates refs; L3-8 CLAUDE.md stale selftest counts
(move-trace 34→36, soak 191→199); L3-9 check_store_migration_join has zero
selftest arms; L3-10 TS_EPOCH_SECONDS has no literal pin (downward drift =
forbidden false-up_to_date direction, all suites green); L3-11 yield suite has
no numeric-constant pins (floor 100/prune 1200); L3-12 exactPreSizing pin lacks
a positive executed signal (re-vacuation risk); L3-13 notes' "~21% less CPU"
warm-up drops the +16.9% translate half (net ~−8%); L3-14 Via guard described
as denying "vanilla" clients — actually denies older-LSS-MODDED clients via
legacy dialects (notes + CLAUDE.md); L3-15 useSelectiveNbtParse lost its
Fabric qualifier in the notes; L3-16 "~12–15% (measured)" is a composition,
not a measurement — say so; L3-17 CLAUDE.md store bullet "never migrated"
contradicts C4's shipped 3→4 migration; L3-18 the tracer is absent from the
notes with no recorded drop decision (the TRACER user decision says no
documented surface — RECORD that as the reason, don't add it).
NITS: L3-19 only the native arm unsets BENCHMARK_CLIENT_GRADLE_ARGS; L3-20
backfill midnight-crossing nulls pool as 0; L3-21 migration-gate empty-results
pipeline dies before its own error message; L3-22 live-corpus count pinned
only non-empty (pin against MANIFEST.txt); L3-23 ViaGuard order pin indexOf
comment hazard; L3-24 notes template deviations (bold-dash format, Store/Notes
headers, CRC32C thread attribution, "existing rungs" understates v19 is new).
Verified clean: release/build workflow safety (no unintended publish paths; no
v0.10 tag anywhere), session-version gate anti-vacuity, windowed premise,
tracer harness, D0/C6/C3 suite quality.

## Lens 1 — wire/protocol/client (COMPLETE: no release-blocking; 3 MINOR / 2 NIT)

Verdict: the v20 cross-stage seams (direct emit × legacy egress × store schema 4
× client ladder) compose correctly; frozen-shape/clear-frame/versioning holds.
- L1-1 MINOR IdentityTables (+Paper twin) production inverse build tolerates
  duplicate canonical identities (modded custom Property name collisions) —
  put() overwrites; egress would serve state B for A silently. The uniqueness
  test only covers the vanilla registry. Fix: the same size-equality throw in
  the PRODUCTION build (biomes safe by key uniqueness).
- L1-2 MINOR WireSectionCursor v20 dictionary parse permits ~25-30x heap
  amplification via unreferenced empty-string entries (1 wire byte → ~30 B
  heap; ~50-60 MB per hostile 2 MiB column). Contained/GC-able; a dict-count
  budget closes it.
- L1-3 MINOR V20ToNativeTranslator palette collapse is O(n²) boxed — hostile
  4096-distinct palettes cost ~seconds/column on the client decode thread.
  Linear IntFirstSeen exists one file over.
- L1-4 NIT ClientSessionGate.clampToProtocolBounds zeroes serverDataVersion
  (inert — no consumer yet; first consumer reads fabricated 0).
- L1-5 NIT v19 echo announce-gated but v16 config is not (harmless in known
  orderings; visibly inconsistent rungs).

## Consolidated dispositions (the D3 gate)

CRITICAL 0 / MAJOR 5 (all lens 3) / MINOR 21 / NIT 11.

FIX NOW (gating): L3-1 (migration-gate overlap premise — dev-only walk-hold
property + ordering assertion), L3-2 (FNV non-empty known-answer vectors),
L3-3 (Paper egress pin decomposed like the Fabric twin), L3-4 (alloc bullet
rewritten honestly), L3-5 (CLAUDE.md WireDialectTracker rename sweep).
FIX NOW (cheap, high-value): L3-8 selftest counts, L3-13/14/15/16 notes
honesty rewrites, L3-17 CLAUDE.md "never migrated", L3-18 record the tracer
absence reason (TRACER user decision: no documented surface), L3-10
TS_EPOCH_SECONDS literal pin, L3-22 corpus count vs MANIFEST, L3-6 dialect_ok
in the return ladder, L2-1 offered-before-offer, L2-2 rawServes counts
non-empty only, L1-1 production inverse-size throw.
FIX-ROUND OUTCOMES (2026-08-08, same day, branch fix/pre-d3-review): ALL
gating items closed — L3-1 the walk-hold property (-Dlss.soak.migrationHoldSeconds
→ soak.sh SOAK_MIGRATION_HOLD_SECONDS → gate default 90 s) + HELD/RESUMING log
ordering assertions, LIVE-PROVEN (gate PASS: HELD 10:59:05 → join 10:59:14 →
RESUMING 11:00:30 → complete 1960/1961 rows — the join's serves provably rode
the 19-row rung); L3-2 FNV-1a-64 known-answer vectors ("a"/"abc"/"foobar") pass
against the real impl; L3-3 the Paper egress pin now decomposes field-by-field
like the Fabric twin; L3-4/13/14/15/16/18/24 notes rewritten honestly + tag
text regenerated; L3-5/17/8 + the Via wording fixed in CLAUDE.md; L3-6
dialect_ok joined the return-1 ladder; L3-10 TS_EPOCH literal pin; L3-22
corpus count pinned against MANIFEST.txt; L2-1 offered-before-offer; L2-2
rawServes counts present records only; L1-1 duplicate-identity fail-loud in
both production inverse builds. Full gauntlet green after all fixes.

DEFERRED to the D3-prep queue with this file as the record: L1-2, L1-3
(hostile hardening — new pins + budget/linearization), L1-4, L1-5, L2-3..6,
L3-7, L3-9, L3-11, L3-12, L3-19, L3-20, L3-21, L3-23, L3-24.

