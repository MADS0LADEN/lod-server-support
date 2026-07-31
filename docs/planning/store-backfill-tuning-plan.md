# Store backfill tuning — `lodStoreBackfillColumnsPerSecond` (+ the MSPT ceiling)

**Status: PLAN — small, self-contained; can land on any post-`feat/lod-store` branch.**
Written 2026-07-31 after the live Modrinth deploy raised "is the background worker
tunable at all?" (answer today: on/off + start/stop only; the pace is hardcoded).

## 1. What becomes tunable, and what deliberately does not

| Knob | Today | Plan |
|---|---|---|
| `lodStoreBackfillColumnsPerSecond` | `StoreBackfill.MAX_COLUMNS_PER_SECOND = 100` (constant) | **Config**, default 100, clamp 10..1000 |
| `lodStoreBackfillTickCeilingMillis` | `45.0f` hardcoded at the wiring site (`RequestProcessingService`) | **Config**, default 45, clamp 20..50 |
| Pause poll (500 ms), MIN_PRIORITY thread, one-read-at-a-time, reader-headroom gate | constants/structure | **STAY FIXED** — these are the self-restraint architecture, not tuning surface; a configurable headroom gate is exactly the v1 footgun the plan rejected ("pause while any backlog is nonempty" never released) |

Rationale for the clamps: below ~10 col/s the walk cannot finish a large world in
useful time (worse than off — it holds the thread and the `hasRow` probes for
nothing); above 1000 col/s the rate cap stops being the limiter at all (the
single-threaded synchronous read is ~1-2 ms healthy, so ~500-1000/s is the natural
ceiling) and the value would only mislead. The MSPT ceiling clamp keeps the gate
meaningfully below the 50 ms tick (a ceiling ≥ 50 never pauses; one ≤ 20 never runs
on a busy server).

## 2. Implementation (all mechanical, one small PR)

1. **Constants** — `LSSConstants`: `MIN/MAX_LOD_STORE_BACKFILL_CPS` (10/1000),
   `MIN/MAX_LOD_STORE_BACKFILL_TICK_CEILING_MS` (20/50).
2. **Config** — `ServerConfigBase`: the two fields + defaults + `Math.clamp` in
   `validate()` (both platforms inherit; no Paper override — the keys are inert
   there until Paper grows a backfill, same recorded stance as `lodStoreBackfill`).
3. **StoreBackfill** — the constant becomes a ctor parameter `columnsPerSecond`
   (used in both places it appears: the window cap and the window arithmetic).
   Keep a package-visible default so existing tests stay untouched except where they
   pin the new wiring.
4. **Wiring** — `RequestProcessingService` passes `config.lodStoreBackfillColumnsPerSecond`
   and swaps the `45.0f` literal for the config field in the `tickHealthy` lambda.
5. **Checker allowlist** — `check_soak.py` `SERVER_CONFIG_INT_KEYS` gains both keys
   (the R4 lesson: a key missing from the allowlist means no scenario can ever set
   it; add them NOW, not when the first backfill scenario needs them).
6. **test-server.sh** — optional passthrough `LSS_LODSTORE_BACKFILL_CPS` written into
   the staged config when set (omit the key otherwise so the server default rules);
   `run-fabric-store` keeps the default.
7. **Docs** — CLAUDE.md config list + the release-notes draft's backfill bullet gain
   the two knobs with defaults.

## 3. Tests

- **Clamp pins** — both `ConfigValidationTest` and `PaperConfigValidationTest`
  clamp sweeps gain the two fields (the sweeps are table-driven; two entries each).
- **Wiring pin** — `StoreBackfillTest`: a driver built with `columnsPerSecond = 2`
  over a 6-chunk region takes ≥ 2 windows (assert on the pause/window COUNTER the
  driver already tracks, not wall-clock — timing asserts flake on loaded boxes; if a
  counter isn't cheaply exposable, pin only that the ctor value lands in the field
  and leave pacing to the live gate).
- **Default pin** — `new LSSServerConfig().lodStoreBackfillColumnsPerSecond == 100`
  (the same drift-guard shape as the resweep-default pins from the 4-agent round).
- **Live gate** — unchanged: the Phase 4 benchmark evidence (99 col/s at cap,
  ~32 col/s constrained) re-run once at a non-default value (e.g. 25) to show the
  knob actually steers the measured rate.

## 4. Explicitly out of scope

- A Paper backfill (separate, recorded deferral — these keys stay inert there).
- Runtime re-tuning via command (`/lsslod store backfill rate <n>`): rejected for
  now — config-reload semantics on Fabric are restart-anyway, and a live setter
  crosses the driver's thread-confinement for a knob that is set-and-forget. Revisit
  only if live tuning proves genuinely needed.
- Any change to the restraint gates themselves (headroom, pause poll, priority).
