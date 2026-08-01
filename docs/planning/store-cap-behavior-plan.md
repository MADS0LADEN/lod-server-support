# Store size-cap behavior — uncapped default, one-shot eviction log, cap-aware backfill

**Status: PLAN, user-directed (2026-08-01)** — born from live evidence on the Modrinth
test server: `lodStoreMaxMB` default (2048) ≈ one 256-distance disc, so a
Chunky-pregenerated world + the backfill hit the cap and entered a
**backfill↔eviction treadmill** — deposits (~800 KB/s) and 512-row evictions
(every 5 s gauge refresh) oscillating at the cap forever, spamming
`LOD store size cap: evicted 512 oldest rows…` every ~5 s, evicting the
nearest-spawn (most valuable, earliest-deposited) rows to make room for far terrain
that itself gets evicted, and — via the review-round fix that un-marks evicted
regions — re-walking the same ground every restart. Sibling of
`store-backfill-tuning-plan.md` (rate knobs); this doc owns cap semantics.

## 1. Default: NO size cap (user decision)

- `lodStoreMaxMB` default changes **2048 → 0 = uncapped** (the `resweepSeconds`
  0-means-off pattern): clamp becomes "0, or 64..32768"; `Environment.maxDbBytes()`
  gets `Long.MAX_VALUE` when 0; the whole eviction/vacuum arm of the gauge refresh
  is skipped when uncapped.
- Rationale (user): admins should simply know the store roughly DOUBLES the world
  folder (measured ~7.6 KB/col vs ~10.6 KB/chunk of region data); a silent
  partial-warmth cap surprises more than disk growth does. The cap stays available
  as an opt-in bound for quota-limited hosts.
- Docs to touch: `ServerConfigBase` javadoc, CLAUDE.md config list, the release-notes
  draft ("**default: no cap** — expect ≈2× world size when fully warmed; set
  `lodStoreMaxMB` to bound it"), and the test-server staging config commentary.
- Ripple: config clamp-sweep tests (both platforms) get the 0-legal branch + a
  default pin (`== 0`); eviction unit tests keep constructing explicit small caps
  (unchanged); the deployed Modrinth config carries no `lodStoreMaxMB` key, so the
  next jar+restart there goes uncapped and the treadmill ends by default.

## 2. Eviction log: exactly ONCE per server session (user decision)

- The `LOD store size cap: evicted…` INFO latches after its FIRST emission
  (`AtomicBoolean`, the codebase's warn-once pattern): once a store is at its cap,
  eviction is a steady-state fact, not news — "you know it's basically constantly
  happening after it's first reported."
- The one emission carries the durable context: cap, current db size, and a pointer
  to where the ONGOING state remains observable. That observability must exist:
  `/lsslod store status` gains an `evicted=<total>` token (diagnostics counter
  already tallies per-batch counts internally — expose it; both platforms' status
  lines), so a capped store is diagnosable without any log line.
- The latch is per-session (resets on restart) — one line per boot on a
  still-capped store is the desired reminder cadence.

## 3. Backfill becomes cap-aware: estimate at start, STOP at the cap

- **Walk-size estimate at start** (one log line, before the first region): estimate
  from region FILE SIZES already visible to `enumerate()` (`Files.size` per planned
  `.mca` × ~0.72 — the measured LOD-bytes/region-bytes ratio; no extra IO, ±30% is
  plenty for a warning). Line shape:
  `Store backfill: N regions to process, estimated ~X GB when complete (cap: Y GB |
  uncapped)`. When capped AND estimate > cap, append the consequence up front:
  `the walk will STOP at the cap — nearest-spawn terrain is warmed first; raise
  lodStoreMaxMB for full coverage`. The admin learns in second one, not from
  eviction spam an hour later.
- **Hard stop at the cap** (never a pause — a full store does not un-fill itself):
  before each region, if the store reports db bytes ≥ ~95% of an ACTIVE cap, exit
  with status `capped: store at size cap, R regions done, M unwalked` and leave the
  unwalked regions unmarked (an admin who raises the cap and re-runs resumes
  exactly there). Depositing into a capped store is provably wasted work — each
  deposit evicts an OLDER (nearer-spawn) row, inverting the walk's own value order.
  Requires a small store accessor for current db bytes + cap (the gauge already
  tracks both).
- Uncapped default (§1) makes the gate a no-op for most servers; it exists for the
  opt-in-cap case and closes the treadmill there.

## 4. Tests

- Config: default pin (`lodStoreMaxMB == 0`), clamp sweep 0-branch (0 stays 0;
  1..63 clamps to 64; both platforms).
- Store: eviction-log one-shot latch pin (two eviction batches, one log call —
  injected logger seam or the diag counter as proxy); uncapped skips the eviction
  arm entirely.
- Backfill: cap-stop pin (tiny explicit cap, fake reader → status starts `capped:`,
  unwalked region NOT done-marked, resume after "raising" the cap walks it);
  estimate-line presence with a fake region dir (assert the arithmetic, not the
  prose).
- Live: one Modrinth-server observation after deploy — no eviction line post-boot
  (uncapped), backfill estimate line present, `/lsslod store status` shows the new
  token.

## 5. Out of scope (recorded, deliberate)

- Recency-aware eviction (least-recently-SERVED vs oldest-deposited) — fixes the
  value inversion generally but needs hit-time write-back (real write
  amplification); future design note, not a bolt-on.
- Cap-crossing mid-walk re-planning (shrink the plan when an admin lowers the cap
  live) — /reload-adjacent complexity for no live user.
