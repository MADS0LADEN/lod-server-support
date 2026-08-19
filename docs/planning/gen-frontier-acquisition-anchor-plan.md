# Plan: the generation anchor tracks the ACQUISITION frontier (dirty revalidations stop stalling backfill)

**Status:** IMPLEMENTED 2026-08-18 (fix/gen-acquisition-frontier) · server-side only, no wire change · review decisions: no config key; CLAUDE.md clause only; [lss-adm] stamp-source tag ADOPTED (`fsrc=acq|reval|-`)

## 1. Problem

Every `dirtyBroadcastIntervalSeconds` (default 10 s), a dirty broadcast of near-player
columns collapses the generation admission window and stalls outward backfill
generation for ~5 s. Measured on a live 35 s client trace (26.1 test rig,
stationary player, cold outward backfill at rings 19-26):

| trace second | gen columns arriving | event |
|---|---|---|
| 2-5 | 3, 32, -, 42 | ring 19-20 cohorts (40-cap draining) |
| 5.9 | | **dirty broadcast, 167 cols near ring 4** |
| 6-11 | 0 | **stall** (frontier collapsed 20 → 4; damped re-walk 16 rings × 333 ms ≈ 5.3 s) |
| 12-15 | 40/s sustained | rings 20-23 |
| 15.5 | | **dirty broadcast, 28 cols** |
| 16-19 | 0 | **stall** |
| 20-24 | 40, 7, 2, 2, 52 | ring 23-24 |
| 25.1 | | **dirty broadcast, 40 cols** |
| 25-27 | 0 | **stall** |
| 28-35 | 40/s runs | rings 24-26 (fourth dirty at 34.6 s would repeat the cycle) |

~15 of 35 seconds dead: **~40% of generation throughput lost** on a stationary
client with an ordinary near-field dirty drip. The 40/s cohort bursts themselves are
the generation caps working as designed; only the inter-cohort stalls are the defect.

Mechanism (every link individually by design):
1. Dirty re-asks re-enter the want-set; the next 1 Hz declaration is closest-first, so
   they lead it.
2. The router's drain stamps the live frontier at the first unsatisfied entry — the
   inner dirty position (ring ~4). Inward stamps apply instantly (the movement
   anti-inversion rule).
3. The order-spread gate admits generation only within frontier + 2 rings, so all
   outer generation admission freezes.
4. The dirty entries resolve within a second (probe/disk — they are loaded or saved
   chunks), but the frontier walks back out at the damped 333 ms/ring
   (`FRONTIER_OUTWARD_DAMP_MILLIS_PER_RING`), eating ~5.3 s per collapse.

The composition error: an inner **revalidation** (the client already has the column;
the server already has the data; generation can never be involved in serving it)
should not gate outer **acquisition** work.

## 2. The discriminator

The wire already distinguishes the two populations:
- **ts > 0** — resync/revalidation: the client HAS data ("send if newer"). Dirty
  re-asks are always this shape (`markDirtyIfKnown` re-declares with the stored
  stamp).
- **ts ≤ 0** — acquisition: the client has nothing. Fresh backfill wants, ingest-
  failure re-declarations, and cache-less joins are this shape.

Rule change: **the live frontier prefers the first unsatisfied ts≤0 entry of the
drain pass** (the acquisition head). It falls back to the first unsatisfied ts>0
entry **only when the pass has no unsatisfied acquisition entry at all**, which
preserves today's exact behavior for pure-revalidation sessions (cold-restart
resync, converged players receiving dirty pushes).

Inner regeneration is not starved by this: a ts>0 ask whose region was deleted
server-side still escalates on its disk miss, and the spread gate is one-sided
outward (`candidate > frontier + spread`) — an inner candidate is never gated by an
outer frontier, and the cohort rule only tightens against the *nearest* outstanding
ticket. Both pacing rules keep working for it unchanged.

## 3. Implementation

All in `common/` (processing thread only, no locks involved):

**`AbstractPlayerRequestState`**
- `stampLiveFrontier(int cx, int cz)` keeps its exact semantics (damping, instant
  inward) — no change to the mechanism, only to who calls it when.

**`IncomingRequestRouter.processPlayer` drain** (the three stamp sites at the
IN_FLIGHT-duplicate head, the send-queue-full retained head, and the
first-entry-needing-work):
- Replace the single `frontierStamped` boolean with:
  - `acquisitionStamped` (boolean) — set when a ts≤0 entry stamps (immediately, as
    today).
  - `deferredRevalStampPos` (long packed, sentinel when absent) — the FIRST would-stamp
    ts>0 entry of the pass, recorded instead of stamped.
- Each current stamp site becomes: `if (req.clientTimestamp() <= 0) { stamp;
  acquisitionStamped = true; } else if (deferredRevalStampPos == NONE) {
  deferredRevalStampPos = packed; }` — guarded by `!acquisitionStamped` exactly as
  the current boolean is.
- At end of pass (after the drain loop, before `restoreBacklog`): if
  `!acquisitionStamped && deferredRevalStampPos != NONE`, stamp it. Within one
  ~50 ms drain pass the deferral is immaterial against 333 ms/ring damping, and the
  stamped VALUE for a pure-revalidation pass is identical to today's (the first
  unsatisfied entry).
- Early-stop passes (send-queue-full / no-disk-headroom) degrade to today's
  conservative under-estimate: the retained head gets recorded, and stamps at pass
  end if no acquisition entry preceded it.

Estimated diff: ~30 lines in the router, ~0 in the state class, plus tests.

**Deliberately NOT changed:**
- The damping constant and mechanism (movement anti-inversion armor stays intact).
- The in-flight-stamps-too rule for ts≤0 entries (the anti-starvation pin: the band
  still cannot walk away from a starving acquisition head).
- The fallback to `appliedWantSet[0]` when the live frontier was never stamped.
- No new config key. This is a strict refinement of a server-internal admission
  heuristic; the rollback is a revert. (Review question 1 below if you disagree.)

## 4. Tests

Pinned properties that must stay green (locate by `stampLiveFrontier` /
`generationOrderSpreadExceeded` usages in Tier 1; the damping-interval probe pins
the constant):
- batch[0] wedge, straggler leak, undamped movement inversion, in-flight stamping,
  damping default.

New Tier 1 cases (router test rig, deterministic clock seam already exists):
1. **The trace scenario:** outer ts≤0 backlog + ts>0 dirty head in the same pass →
   frontier stamps at the OUTER acquisition ring, spread gate does not fire
   (`gen_order_gated` delta 0), and the dirty head is still served first (delivery
   order untouched).
2. **Pure-revalidation pass:** all-ts>0 pass stamps the head at pass end — value
   identical to current behavior (cold-resync pin).
3. **Deferred stamp under early stop:** send-queue-full with a ts>0 retained head
   and no prior acquisition entry → the head stamps at pass end (today's
   conservative under-estimate preserved).
4. **Inner regeneration not starved:** ts>0 ask, authoritative disk miss, outer
   frontier — escalation admits (both pacing rules pass for the inner candidate).
5. **Interleaving:** ts>0 head then ts≤0 second entry → acquisition entry stamps
   even though the revalidation entry came first; recorded reval candidate is
   discarded.

Tier 2: existing generation-lifecycle and two-player gametests unchanged (no
behavior shift on their FIFO-clean, dirty-free timelines).

Soak (the live gate): `dirty-during-backfill` is purpose-built for this exact
interaction — run it plus `fresh-backfill`, `dirty-broadcast`, and
`generation-capacity-stress`; all laws must stay green. Expected observable shifts:
`gen_order_gated` drops sharply in dirty-during-backfill; `superseded` may shift
slightly (more gen slots busy → more transient drops — legitimate).

Live validation: repeat the 35 s client trace on the 26.1 rig (`/vss trace`, same
stationary cold-backfill setup). Success = no multi-second generation gaps aligned
with the 10 s dirty cadence; duty cycle from ~60% to ~95%+.

## 5. Risks

- **R1 — a revalidation flood masking a starving acquisition head.** If every pass
  contains an earlier unsatisfied ts>0 entry AND the acquisition head is behind an
  early stop, the deferred reval stamp (inner) still applies — conservative
  direction (over-gating), same as today. No new starvation shape.
- **R2 — sessions mixing deleted-region resync with backfill.** ts>0 asks that
  genuinely need generation anchor the window only when no ts≤0 work exists; when
  both exist the window keys on ts≤0 work. Worst case matches the pre-live-frontier
  1 Hz fallback semantics for the ts>0 population — acceptable, noted in the
  gate's javadoc.
- **R3 — counter drift in soak baselines.** `gen_order_gated` is unpinned
  (diagnostic); the churn ceilings (`superseded` 1500 storm baseline) were measured
  under 1 Hz-dominated profiles that this change does not alter. Verify against the
  full soak set anyway.

## 6. Rollout

1. Implement + Tier 1 on main; full local T1/T2.
2. Soak set above on an idle box.
3. Live trace validation on the 26.1 rig (the discovery instrument is the
   acceptance instrument).
4. PR to main with the trace before/after in the description.
5. Support lines: fold at the next patch round per the TAKE-MAIN rule (the 26.1
   line is where the discovery trace ran, so it benefits first when a 0.11.x patch
   ships). Not release-urgent on its own.

## 7. Review questions

1. **Kill switch?** Plan says no new config key (revert = rollback). If you want a
   belt anyway, a hidden `@HiddenFromFile` boolean (`useAcquisitionFrontier`,
   default true) is ~5 extra lines and matches the expert-switch convention.
2. **Docs surface:** CLAUDE.md's spread-gate parenthetical gets one clause ("anchored
   on the client-declared ACQUISITION frontier (ts≤0)"). Worth also amending
   miss-memo-design.md's pacing section, or is the plan doc enough?
3. **Trace observability:** should the `[lss-adm]` admission trace tag the stamp
   source (`acq`/`reval-fallback`)? ~3 lines, helps the next investigation of this
   area; omitted from the base plan.
