# Config surface review — defaults, clamps, retirements, auto-configuration

**Status: IMPLEMENTED 2026-08-02** (commit `4c24216`), with one recommendation REVERSED
during implementation — see §6 and §11. Originally written as review/recommendations.
Written 2026-08-02 on `feat/compressed-columns` at the user's
request: audit every config option and its default and clamp, turn the new
performance features on by default including the store and backfill, find options
that can be deleted / made internal / auto-configured, and free up clamps that are
too conservative.

Scope: `ServerConfigBase` (27 fields, shared verbatim by Fabric and Paper),
`PaperConfig` (1 extra field + 1 default override), `LSSClientConfig` (6 fields),
and the `LSSConstants` MIN/MAX band each one clamps into.

---

## 0. The recommended diff at a glance

**Defaults changed (11):**

| Option | Now | Recommend | Why, in one line |
|---|---|---|---|
| `lodStore` | `"off"` | **`"full"`** | 98.8% of live serves are store hits; it is the single biggest win LSS has |
| `lodStoreBackfill` | `false` | **`true`** | The store's value is warm-on-arrival; organic warming never reaches unvisited terrain |
| `lodStoreBackfillColumnsPerSecond` | `100` | **`500`** | Live-proven; turns a multi-hour background drag into a bounded startup event |
| `bytesPerSecondLimitGlobal` | 100 MiB | **256 MiB** | At the per-player default the global binds at 5 concurrent players |
| `perDimensionTimestampCacheSizeMB` | `32` | **`64`** | 32 MB ≈ 2.5 discs at the default LOD distance; spread-out players evict each other |
| `generationConcurrencyLimitPerPlayer` | `16` | `16` (unchanged) | — but its clamp ceiling is incoherent, see §9 |
| `diskReaderThreads` | `5` | **`0` = auto** | Correct value depends on the resolved read path, which LSS already knows |
| `lodStoreBackfillTickCeilingMillis` | `45` | **retire to constant** | 20..50 is not a tuning range |
| `sendQueueLimitPerPlayer` | `1024` | **retire to constant** | Structurally unreachable for any legal client |
| `useBackgroundReadPriority` | `true` | **retire the knob** | Rollback lever for a feature two releases old |
| `useNbtTranscode` | `true` | keep one more release | Rollback lever, still young |

**Clamps freed (4):** `MAX_LOD_STORE_MAX_MB` 32 GB → 1 TB; `MAX_BYTES_PER_SECOND_PER_PLAYER`
100 MB → 1 GiB; `MAX_TIMESTAMP_CACHE_SIZE_MB` 256 → 512; `MAX_CONCURRENT_GENERATIONS`
256 → 512.

**Explicitly NOT changed:** `bytesPerSecondLimitPerPlayer` (20 MiB) and
`outboundBufferCeilingKB` (0 = off). Both are covered in §8 — these are the two places
where "enable all the new performance features" is the wrong instinct, and I want the
reasoning on the record rather than buried.

**Coherence bugs found (3):** see §9. One of them (`lodStore`'s javadoc still documenting
the retired `"memory"` value) I fixed while writing this, because it was a miss from the
commit that retired it, not a recommendation.

---

## 1. Method and evidence base

Every recommendation below is tied to one of:

- **Live server** (Modrinth Fabric 26.2, `feat/lod-store` builds, real players):
  full-session figures 8.63 GB raw → 1.38 GB wire (6.25:1), 284,021 columns 100%
  codec-1, **280,273 store hits / 2,808 real disk reads = 98.8%**, backfill run at
  500 col/s across 676 regions resuming over four restarts.
- **Phase 0 store corpus** (1.22 GB real terrain): the codec table, ~5.3 KB/col at
  zstd-1, warm hit ~100 µs vs ~2.4 ms NBT path.
- **Phase 4 backfill benchmark**: 99 col/s at the 100 cap with a client actively
  pulling; 32 col/s on a constrained box.
- **The elytra chunk-wall investigation** (2026-08-01): ping flat 20–26 ms across a
  full flight, H2 (path congestion) falsified, H3 (receiver-limited) confirmed, root
  cause the cadence gate.
- **`read-scheduler-design.md` §0**: the semantics of `diskReaderThreads`.

Where I have no measurement, I say so and recommend no change. That applies to the
generation concurrency defaults in particular.

---

## 2. The headline: store + backfill on by default

### 2.1 `lodStore`: `"off"` → `"full"`

The case is overwhelming on latency and CPU. On the live server **98.8% of column
serves never touch a region file**. A store hit serves in ~100 µs against ~2.4 ms for
the NBT path — and since the compressed-columns work, a hit ships its stored zstd frame
*verbatim*, so it also skips compression on the way out. Every phase gate the store
went through passed, including the ≤10% cold-path non-regression gate (median +7.2%).

**The honest cost, which must be release-noted prominently and not buried in a config
table: the store roughly doubles the world folder.** At ~5.3–7.6 KB/col against
~10.6 KB/chunk of region data, a fully warmed store is on the order of 70–100% of the
region files' size. On the live server that is a ~4 GB `world/lss-lod/store.db`. Turning
this on by default means every LSS server that upgrades starts consuming disk it wasn't
consuming before, without being asked.

I think that trade is right — it is derived data, deleting `lss-lod/` is always safe,
and the alternative is that the feature stays off for everyone who doesn't read release
notes — but it is a **disk-for-latency trade made on the admin's behalf**, and it
deserves a first-boot INFO line stating the expected growth, not just a changelog entry.

Three caveats that ride with the flip:

- **Folia stays off.** The recorded stance is "untested, leave it off" (wording-only,
  no code gate). A default-on store would silently arm it there. Recommend `PaperConfig`
  gain the same shape as its `lodStoreResweepSeconds` override: `lodStore = "off"` when
  `FoliaSupport.IS_FOLIA`, until a Folia store gate exists.
- **The natives must load.** sqlite-jdbc and zstd-jni are nested native-stripped to the
  supported platform matrix (no musl). The degrade ladder already handles failure
  (SQLite fails → in-memory tier; codec fails → store-off, one warning each), so a
  default-on store on an unsupported platform is a warning, not a crash. Verified by
  the existing containment tests.
- **`lodStoreMaxMB` stays `0` (uncapped).** Do not pair a default-on store with a
  default cap. That combination is exactly the backfill↔eviction treadmill that
  `store-cap-behavior-plan.md` documented on a pregenerated world, and it was an
  explicit user decision to go uncapped.

### 2.2 `lodStoreBackfill`: `false` → `true`

Organic warming only ever covers terrain a client has already asked for — which is
precisely the terrain that was already served once. The store's *point* is that the
second player to visit an area, and the same player after a restart, arrive warm. That
requires walking terrain nobody has requested yet, which is what the backfill does.

The restraint architecture is what makes this safe as a default, and it is deliberately
not tunable: MIN_PRIORITY thread, one read at a time, the reader-headroom gate, the MSPT
ceiling, 500 ms pause polling. Those are structure, not knobs.

**The cost is the same disk as §2.1, but arriving on a schedule rather than on demand** —
and on a large pregenerated world it is a lot of it at once. The mitigating facts: the
walk logs a size estimate up front, it is resumable across restarts via per-region
done-marks (live-verified across four restarts), and `/lsslod store backfill stop` works
at runtime.

### 2.3 `lodStoreBackfillColumnsPerSecond`: `100` → `500`

This is the change that makes §2.2 tolerable. At 100 col/s a 700k-column world takes
~2 hours of continuous background walking; at 500 it takes ~23 minutes. **For a task
with a definite end, finishing sooner is strictly better** — the restraint gates are
unchanged, so the walk is no more intrusive per unit of work, it simply stops being
present sooner.

500 is live-proven: it is what the Modrinth server has run since 2026-07-31 without
complaint. It also sits inside the physically honest band — the single-threaded
synchronous read is ~1–2 ms healthy, so ~500–1000/s is the natural ceiling, and a
constrained box simply gets gated down (measured: 32 col/s under load at the 100 cap).
The pace is a ceiling, never a floor.

Keep the clamp at 10..1000. The 1000 ceiling is a genuine physical bound, not
conservatism — above it the number would describe something the reader cannot deliver.

---

## 3. Server options — full review

### 3.1 Already correct, no change

| Option | Default | Clamp | Verdict |
|---|---|---|---|
| `enabled` | `true` | — | Correct. The master switch. |
| `enableChunkGeneration` | `true` | — | Correct. Off is a legitimate ops choice (`NOT_GENERATED` becomes reachable and is session-permanent — that asymmetry is by design). |
| `useNbtTranscode` | `true` | — | On is right; see §6 for its retirement schedule. |
| `useCompressedColumns` | `true` | — | On is right. Costs ~+12% wire for a large CPU saving; the store-hit verbatim path is why. |
| `enableV16Compat` | `true` | — | Correct until v0.6.x clients are gone. |
| `enableV18Compat` | `true` | — | (Added v0.9.1, docs/planning/v18-compat-design.md.) Correct until v0.7.x–v0.8.x clients are gone — a LATER sunset than the v16 set: the protocol-18 install base is the newer and larger one. Membership-only rung; off restores the strict silent gate for 18 and those clients degrade to the v16 fallback. |
| `generationTimeoutSeconds` | `60` | 1..600 | Fine. Timeouts are transient outcomes that heal by re-declaration. |
| `dirtyBroadcastIntervalSeconds` | `10` | 1..300 | Fine. |
| `lodStoreResweepSeconds` | 0 Fabric / 300 Paper | 0..3600 | The platform asymmetry is well-reasoned and should stay: Fabric's content-hash pipeline invalidates at runtime, so a periodic resweep there would only churn-drop rows on metadata-only re-saves. |
| `lodStoreMaxMB` | `0` (uncapped) | 0 or 64..32768 | Default correct (§2.1). Ceiling too low — see §5. |
| `xrayObfuscation` | `"auto"` | tri-state | Correct — normalize-to-auto here vs normalize-to-off for `lodStore` is a deliberate safe-bias asymmetry. |
| `xrayHiddenBlocks` | Paper's list | — | Correct (fallback tier only). |
| `xrayMaxBlockHeight` | `64` | -2048..2048 | Correct; clamp is generous by design. |
| `lodDistanceChunks` | `256` | 1..2048 | Generous already. The 2048 ceiling costs nothing now that the fast-rescan gate is predicted-walk-cost based rather than distance-based. |
| Paper `updateEvents` | 7 events | — | Correct. Excluding `BlockFromToEvent` (fluid flow) by default is right. |

> **Erratum (2026-08-13, v0.11.0 stage B):** a NEW key joins the audited set —
> `maxConcurrentDiskReads` (default `0` = AUTO, store-conditional: store armed →
> ceil(pool/2), store-less → pool = no-op; nonzero clamps `1..64` in validate() plus
> to the resolved pool at derivation; disable idiom = set ≥ pool — 0 is AUTO, not
> off). See `docs/planning/disk-read-concurrency-gate-plan.md`.

> **Erratum (2026-08-13, v0.11.0 stage A):** two rows above are superseded.
> `dirtyBroadcastIntervalSeconds`' clamp is now **`0` or `1..300`** — `0` disables
> dirty pushes entirely (previously it clamped to 1 s, the *fastest* cadence — the
> opposite of the operator's intent); the drain + invalidation fan-out keep running
> every `DIRTY_DRAIN_ONLY_INTERVAL_SECONDS` (10 s), negatives normalize to 0
> (`docs/planning/dirty-broadcast-interval-zero-plan.md`). And `lodDistanceChunks`'
> default is **300** (user decision 2026-08-12): 256 → 512 in the 2026-08-08 rework
> (see §11.4's earlier same-day history), then 512 → 300 for v0.11.0 as a middle
> landing — the AUTO timestamp-cache derivation (§7.3 erratum) follows it
> automatically.

### 3.2 `bytesPerSecondLimitGlobal`: 100 MiB → **256 MiB**

At the 20 MiB per-player default, the global cap **starts binding at five concurrent
LOD players**. That is a surprising place for a fleet-wide ceiling to engage, and when
it does it manifests as everyone's LOD slowing down together with no local explanation.
The user already runs 300 MB/s live.

256 MiB moves the crossover to ~13 concurrent players, which is a more honest "this is a
safety valve, not a throughput setting" position. The 1 GiB clamp ceiling stays.

### 3.3 `perDimensionTimestampCacheSizeMB`: 32 → **64**

At `HEAP_BYTES_PER_ENTRY = 64`, 32 MB holds 524,288 entries per dimension. A single
player's disc at `lodDistanceChunks=256` is ~206,000 columns. So the default holds about
**2.5 discs per dimension** — fine for one or two clustered players, tight the moment
three players are spread out, and eviction here costs a re-read on the up-to-date rung.

64 MB (≈1M entries, ~5 discs) is the right default for a knob whose failure mode is
silent extra IO. Note the multiplier when reading this number: it is **per dimension**,
so the real budget is ×3 for a vanilla server.

### 3.4 `diskReaderThreads`: 5 → **0 = auto** (§7.1)

This is the most misunderstood option in the file, and `read-scheduler-design.md` §0
says why: **it is not disk parallelism.** Because vanilla's IOWorker is single-threaded,
it is the number of LSS reads that can sit in the shared IO queue *ahead of a vanilla
read*. More threads therefore do not speed up cold reads — they linearly increase how
long a vanilla chunk load can wait behind LSS.

That analysis was written before the Moonrise rung existed, and the picture has since
split three ways:

| Resolved read path | What protects gameplay | Right concurrency |
|---|---|---|
| Moonrise (Fabric w/ Moonrise, and all Paper/Folia) | `Priority.LOW` — real priority | Higher is safe; CPU parallelism is the only limit |
| Vanilla IOWorker (plain Fabric) | BACKGROUND ordinal on a single-threaded worker | Low — concurrency *is* the tradeoff |
| C2ME / chunk-IO-overhaul (latched fallback) | `AdaptiveReadThrottle` (AIMD) | The throttle owns it; the thread count is a ceiling |

One fixed default cannot be right for all three. LSS already resolves which path it is
on at startup (`read_path=moonrise-low` is in `/lsslod diag` today). See §7.1.

Two further notes: the default of 5 is also the shape behind the documented A7 soak
signature ("redded at exactly +5 = `diskReaderThreads`" — one IOWorker stall expiring
all five blocked readers at once). And with the store on by default, cold reads become
the ~1.2% path, which lowers the value of extra threads further.

**Interim recommendation if auto-config is deferred: leave the default at 5 but fix the
javadoc**, which today says nothing about what the number actually means. An admin
reading the current comment would reasonably raise it to "make LOD faster" and get
strictly worse vanilla chunk loading in exchange for nothing.

### 3.5 `sendQueueLimitPerPlayer`: 1024 → **retire to a constant** (§6)

Its own javadoc concedes the point: at the default "the router's queue gate is
unreachable for any legal client." Under v17 replace semantics a player's backlog is at
most one wire batch, and a payload only enqueues for an admitted backlog position. The
knob exists to be lowered by ops, but the gated regime was measured harmless (same
throughput, same CPU, exactly-once reads), which means lowering it buys nothing either.

### 3.6 `generationConcurrencyLimitGlobal` (32) / `PerPlayer` (16): **no default change**

I considered raising these — 32 global with 16 per-player means two players saturate the
fleet-wide generation budget — and I am recommending against it, because **I have no
measurement to justify it and worldgen is the most CPU-expensive thing LSS can trigger.**

The specific asymmetry that makes me cautious: on Paper, LOD generation runs at
`Priority.LOW` and defers to player-driven generation. On Fabric it does not — vanilla
pins worldgen priority to ticket level 33, so there is no priority hand-off and there is
no MSPT gate on the generation path (unlike the backfill, which has one). The
concurrency caps *are* the honest limiter on Fabric. Raising them raises worldgen CPU
against gameplay with nothing underneath to catch it.

If this is worth pursuing, the experiment is cheap and specific: raise
`generationConcurrencyLimitGlobal` to 64 on a Fabric box, run `fresh-backfill`, and watch
`mspt_avg_window` against the control. Do that before changing the default, not after.

The *clamps* on these two are incoherent regardless — see §9.1.

### 3.7 `missMemoTtlSeconds`: 30, clamp 0..60 — **no change**

The 60 s ceiling is a **correctness bound, not conservatism**: the memo's falsifying
hooks are known incomplete, so a stale entry may delay service by up to the TTL. Raising
the ceiling raises the worst-case stall. Leave it.

Worth recording alongside the store flip: the adaptive scan cadence (up to 4 Hz) means
the memo now absorbs up to 4× the churn reads it was tuned against, so its value has gone
up, not down. Any future A/B against a `missMemoTtlSeconds: 0` baseline must be recorded
fresh — pre-cadence baselines understate the churn.

---

## 4. Client options — full review

| Option | Default | Verdict |
|---|---|---|
| `receiveServerLods` | `true` | Correct. The master switch, and the A/B lever that identified the elytra wall as LSS-side. |
| `lodDistanceChunks` | `0` (= use server) | Correct. 0-means-defer is the right shape. |
| `enableAdaptiveScanCadence` | `true` | Correct, and now materially more valuable than when it shipped — the predicted-walk-cost gate (this branch) is what finally makes it fire during movement. |
| `enableIngestBackpressure` | `true` | Correct. Protects weak clients; bit-identical to pre-#71 when no consumer reports a backlog. |
| `enableV16ServerCompat` | `true` | Correct until v0.4.x–v0.6.2 servers are gone. |
| `enableV16Generation` | `true` | Correct — it is the faithful reproduction of native protocol-16 client behavior. |

**No client default should change.** The client surface is already minimal and every
option is either a master switch or a kill switch for a feature that should be on.

The one observation worth making: four of the six are kill switches, and three of those
(`enableAdaptiveScanCadence`, `enableIngestBackpressure`, and the pair of v16 flags) will
eventually be dead weight. They should retire on the same schedule as their server-side
counterparts (§6), not individually.

---

## 5. Clamps that are too conservative

| Constant | Now | Recommend | Reasoning |
|---|---|---|---|
| `MAX_LOD_STORE_MAX_MB` | 32,768 (32 GB) | **1,048,576 (1 TB)** | This caps *the admin's own disk*, opted into deliberately. A Chunky-pregenerated world can exceed 32 GB of store, and hitting an artificial ceiling turns an intentional cap into a silent treadmill. There is no LSS-side resource that 32 GB protects. |
| `MAX_BYTES_PER_SECOND_PER_PLAYER` | 104,857,600 (100 MB) | **1,073,741,824 (1 GiB)** | The live server hit this exact ceiling. Match the global cap's ceiling; the *default* stays 20 MiB (§8.1), so this only affects admins who deliberately type a big number. |
| `MAX_TIMESTAMP_CACHE_SIZE_MB` | 256 | **512** | 256 MB/dimension is reachable on a big-distance server with many players. Note the ×dimensions multiplier when documenting it. |
| `MAX_CONCURRENT_GENERATIONS` | 256 | **512** | Headroom exists: `WantSetBudgetInvariantTest` requires `SYNC_ON_LOAD_SLOT_CAP(200) + MAX_CONCURRENT_GENERATIONS + WANT_SET_FRONTIER_RESERVE(64) ≤ WANT_SET_BUDGET(800)`, so the true ceiling is **536**. 512 takes the headroom while keeping the invariant with room to spare. (This raises the *ceiling*, not the default — see §3.6.) |

**Two clamps that look conservative and are not — do not touch:**

- `MAX_MISS_MEMO_TTL_SECONDS = 60` — a staleness bound (§3.7).
- `MAX_LOD_STORE_BACKFILL_CPS = 1000` — a physical bound (§2.3).

---

## 6. Retirement candidates

Four options are rollback levers for features that have shipped and been pinned by
byte-level tests. Each costs a config field, a clamp/validate line, a checker-allowlist
entry, documentation in three places, and a branch in every reader. They should sunset
on a schedule rather than accumulate.

| Option | Recommendation | Reasoning |
|---|---|---|
| `useBackgroundReadPriority` | **Retire now** (constant `true`) | Two releases old, live-proven on both platforms, and its failure modes are already handled by automatic latching (`backgroundIncompatible`, `moonriseIncompatible`) rather than by the config. The knob would only be reached by an admin who has already been told to try it. |
| `sendQueueLimitPerPlayer` | **Retire now** (constant `MAX_BATCH_CHUNK_REQUESTS`) | Structurally unreachable (§3.5). |
| `lodStoreBackfillTickCeilingMillis` | **Retire now** (constant `45`) | The clamp is 20..50, and its own comment explains that ≥50 never pauses and ≤20 never runs. A 30-unit window where both ends are degenerate is not a tuning range. |
| `useNbtTranscode` | **Keep one more release** | Youngest of the four; the per-section fallback ladder is intricate enough that a whole-feature rollback is still worth having. |
| `useCompressedColumns` | **Keep** | Newest, and it is the one that changes wire bytes. Keep until it has a release cycle of live exposure. |
| `enableV16Compat` / `enableV16ServerCompat` / `enableV16Generation` | **Keep, retire together** | Legacy shims with a natural sunset condition (no v0.6.x peers). Retire as a set when that condition is met, not piecemeal. |
| `enableV18Compat` | **Keep, separate sunset** | (v0.9.1.) Same shape, DIFFERENT condition: no v0.7.x–v0.8.x peers — later than the v16 set's. Do not bundle it into the v16 retirement. |

Retiring a key is cheap and safe in this codebase: GSON ignores unknown keys on load and
`validate()`'s next save drops them, exactly as `syncOnLoadConcurrencyLimitPerPlayer` and
`lodStoreMemoryMB` did. Release-note each removal.

---

## 7. Auto-configuration proposals

The convention already exists in this codebase — `0` means "derive it" for
`lodStoreMaxMB`, `outboundBufferCeilingKB`, `lodStoreResweepSeconds`, and the client's
`lodDistanceChunks`. These three extend it to options where LSS demonstrably knows better
than the admin.

### 7.1 `diskReaderThreads = 0` → derive from the resolved read path

The strongest candidate, because the correct value depends on information the admin does
not have and LSS resolves at startup anyway (§3.4):

```
0 (default) → Moonrise rung resolved      : min(8, availableProcessors / 2)
              vanilla IOWorker            : 3
              C2ME/throttle-latched        : 5  (the AIMD throttle owns the real limit)
```

Non-zero keeps today's explicit meaning. This turns a knob that is actively misleading
(more is not faster; more is worse for gameplay) into one that is right by default and
still overridable. It also means the Paper/Folia line — where `Priority.LOW` is genuine
protection — stops inheriting a number tuned for vanilla Fabric's single-threaded worker.

The catch to be honest about: the read path can latch *after* startup
(`moonriseIncompatible` / `backgroundIncompatible` are one-way latches hit on first
failure), and the pool is sized once. The degrade would leave a pool sized for a path LSS
is no longer on. That is acceptable — the latched fallbacks engage the adaptive throttle,
which narrows `hasHeadroom()` and makes the pool size non-binding — but it must be stated,
not discovered.

### 7.2 `bytesPerSecondLimitGlobal = 0` → `16 × bytesPerSecondLimitPerPlayer`

Removes the "why did everyone slow down at five players" surprise (§3.2) without picking
an absolute number that is wrong for both a 4-slot box and a 200-slot network. If §3.2's
simple bump to 256 MiB is preferred, that is fine too — this is the more elegant of two
acceptable answers, not a correctness issue.

### 7.3 `perDimensionTimestampCacheSizeMB = 0` → derive from `lodDistanceChunks`

`entries ≈ π · (lodDistanceChunks + LOD_DISTANCE_BUFFER)² · 1.5`, converted to MB at
`HEAP_BYTES_PER_ENTRY`, clamped into the existing band. At the 256 default that lands
around 40 MB — close to today's value, but it *tracks* the setting it depends on instead
of silently under-provisioning when an admin raises the distance.

> **Erratum (2026-08-08, D0 tile redesign):** the derivation above (and §3.3's per-entry
> cost model) describes the retired two-map cache. Since
> `timestamp-cache-tile-redesign.md` the AUTO formula is the scanned SQUARE disc ×
> `TIMESTAMP_CACHE_AUTO_COVERAGE_FACTOR` (8.0) at
> `TIMESTAMP_CACHE_HEAP_BYTES_PER_COLUMN` (5 B) — ~12 MB at distance 256, covering
> ~5.3× the columns. That doc is the current authority for this key; the reasoning here
> (track the distance, don't fix a number) still stands.

**Not recommended for auto-config:** `generationConcurrencyLimitGlobal` from core count.
Worldgen cost per core varies enormously with datapacks and platform, and §3.6's point
stands — there is no MSPT gate underneath it on Fabric. A derived value would be
confidently wrong.

---

## 8. What I recommend NOT changing

The instruction was to enable the new performance features by default. Two options look
like they belong in that set and do not, and I would rather argue the point here than
quietly skip them.

### 8.1 `bytesPerSecondLimitPerPlayer` stays at 20 MiB

The tempting argument: compressed columns cut wire bytes ~6.25:1, so the same setting
now costs a sixth of the network it used to — surely it can rise.

That argument fails on the limiter's denomination. **The cap charges RAW bytes
deliberately** (`estimatedBytes` = raw sections + envelope; `wireBytes` is diagnostics
only), because it bounds *client decode and ingest work*, which scales with raw bytes and
is unaffected by wire compression. And the elytra investigation confirmed **H3,
receiver-limited** — the client was the bottleneck. So the cap is correctly denominated
for the actual binding constraint, and compression did not loosen that constraint at all.

Worse, this is the one knob with a user-visible failure attached to it: the elytra
chunk-wall reproduced at ~25 MB/s raw, which is just above this default. Raising the
default would ship the incident configuration to everyone.

The right way to move this number is the experiment already specified in the
investigation's §11.7 and still outstanding: **sweep `bytesPerSecondLimitPerPlayer`
upward on the current build until `runway` collapses, and record the throughput at
onset.** It is zero-code and it bounds the risk arithmetically. Until it runs, 20 MiB
stays.

### 8.2 `outboundBufferCeilingKB` stays at 0 (off)

> **ERRATUM (2026-08-13, twice — final state per adaptive-transfer-rate-plan.md):**
> the AUTO mode that briefly occupied 0 (auto-outbound-ceiling-design.md) was
> live-falsified three times the same day and DELETED — 0 means off again,
> exactly this section's original verdict. What survives of that detour: the
> fixed minimum re-clamped 4096 → 64 KB, and the key's `/lsslod set` row (0 =
> off). Slow-link pacing now lives in the client transfer governor + the server
> ping backstop (`enablePingBackstop`).

This is not a performance feature — it is a protective gate for a condition that was
**measured absent**. Flat 20–26 ms ping across a full elytra flight is a direct and
sensitive probe of shared-queue depth, and the server's send queue read empty throughout.

Arming it by default would risk re-introducing the exact failure mode of the retired
movement-cadence debounce: *LOD silently stops during fast travel*. Its own javadoc says
nonzero `deferred=` on a healthy link is a red flag, not the gate working. It ships
correct and tested so it can be armed from evidence — `obuf_hw` in `/lsslod diag` — the
day a real buffer appears. Default-on inverts that.

---

## 9. Coherence bugs found during the review

### 9.1 The generation clamps are inverted

`generationConcurrencyLimitPerPlayer` clamps to `MIN/MAX_CONCURRENCY_LIMIT` = **1..1000**,
while `generationConcurrencyLimitGlobal` clamps to `MIN/MAX_CONCURRENT_GENERATIONS` =
**1..256**. A per-player ceiling four times the fleet-wide ceiling is unreachable by
construction: one player can never hold more generation slots than exist globally.

An admin who sets per-player to 500 gets silent nonsense — the value validates, and then
the global cap of ≤256 governs. Recommend clamping per-player to `min(configured global,
MAX_CONCURRENT_GENERATIONS)`, or at minimum sharing the same ceiling constant.

(`MAX_CONCURRENCY_LIMIT` is not dead — `WantSetBudgetInvariantTest` asserts
`SYNC_ON_LOAD_SLOT_CAP ≤ MAX_CONCURRENCY_LIMIT` — so it should be kept and re-pointed,
not deleted.)

### 9.2 `lodStore`'s javadoc documented the retired `"memory"` value — **fixed**

`ServerConfigBase.lodStore` still described three values and called `"full"`
"memory + SQLite disk store", which has been wrong since the Phase 2 delete-the-tier
verdict and doubly wrong since `"memory"` was retired. This was a miss from that commit,
not a recommendation, so I corrected it in place rather than filing it.

### 9.3 `LSSConstants` has an orphaned comment block

The comment describing the LOD-store resweep cadence now sits directly above the
`MIN/MAX_LOD_STORE_MAX_MB` pair (the `MEMORY_MB` constants that used to separate them are
gone), so it reads as if it documents the size cap. Cosmetic, but it is the kind of drift
that misleads the next reader of a constants file. Fix when touching the file.

---

## 10. Sequencing and gates

The recommendations are not equally risky and should not land together.

**Batch 1 — mechanical, no behavior change for existing installs.** §9.1 clamp fix,
§9.3 comment fix, §3.4's javadoc correction, and the §5 clamp liberalizations. Nothing
here changes a default; every one is either a doc fix or a raised ceiling nobody is
currently against. Gate: existing clamp-sweep tests on both platforms.

**Batch 2 — the retirements (§6).** Three keys removed, one behavior frozen to its
current default. Gate: config tests, the `check_soak.py` allowlist, and a release note
per key.

**Batch 3 — the store flip (§2).** The big one. `lodStore=full` + `lodStoreBackfill=true`
+ 500 col/s, plus the Folia guard. Gates, in order:
1. The full Fabric soak suite with the new defaults — the store scenarios now exercise
   the *default* path rather than an override, which is a genuine change in what is
   covered.
2. `SOAK_PLATFORM=paper` on the same.
3. A fresh-world first-boot check that the disk-growth INFO line fires and the backfill
   estimate is sane.
4. Confirmation that Folia defaults to off.

**Batch 4 — auto-configuration (§7).** Independent of the rest and the most design work.
`diskReaderThreads` first; it has the clearest payoff and the clearest failure mode to
document.

**Not in any batch:** §8.1 waits on the cap sweep, which needs the user to fly on the
live server. §3.6 waits on a generation-concurrency MSPT experiment. Both are blocked on
measurement, not on decisions.

---

## 11. Implementation outcome (2026-08-02)

Everything above landed in one commit except as noted here.

### 11.1 The retirement batch was wrong about two of its three knobs

§6 recommended retiring `useBackgroundReadPriority` and `sendQueueLimitPerPlayer` on the
grounds that they are unreachable or buy nothing. **Implementing it surfaced that both are
the only levers that let a test harness exercise a real production path**, which is a fact
the review did not have and which reverses the call:

- **`sendQueueLimitPerPlayer`** — the `bandwidth-throttle` soak scenario sets it to `64` and
  its checker *gates on* `service.queue_full >= 1`. That counter is the send-queue breaker: a
  genuine loss signal, unlike the transient-drop counters beside it. Retiring the key would
  have left that production path with no end-to-end coverage at all.
- **`useBackgroundReadPriority`** — it is the arm selector for `benchmark_compare.sh`'s
  `v17-fg` foreground-vs-background CPU comparison. Retiring it would not have *broken* that
  harness; it would have made its two arms **silently identical**, which is worse. That is the
  same failure shape §9.1 flags for the generation clamp, and it would have been self-inflicted.

Only `lodStoreBackfillTickCeilingMillis` was retired. It has no scenario, no harness, and no
production caller beyond one wiring site.

**The generalisable lesson: "no production use" is not the same as "no use."** A knob whose
only consumer is the test harness is test infrastructure, and the audit criterion should have
been "who reads this, including harnesses" rather than "would an admin ever set it."

### 11.2 Everything else landed as recommended

Defaults (§0), the AUTO derivations (§7.1, §7.3), the four freed clamps (§5), and the §9
coherence fixes are all in. §7.2 was implemented as the simple 256 MiB bump rather than the
`16 × per-player` derivation, which §7.2 itself named as an equally acceptable answer.

`lodDistanceChunks` also went 256 → **512** at the user's request in the same pass. That
change is what makes §7.3 load-bearing rather than tidy: at 512 the old fixed 32 MB timestamp
cache would have held roughly *half* of one player's disc, so it would have thrashed.

### 11.3 Soak baselines were frozen, deliberately

The 19 pre-store scenarios now pin `lodStore: "off"` explicitly. Their configs are copied
verbatim as the whole server config, so a default-on store would silently have changed what
every one of them measures — including the timing-sensitive quiescence laws, against a
background backfill. The store scenarios already opt in explicitly, so coverage of the
store path is unchanged.

This means **the full suite has not yet been run against the shipped defaults**, which is
still the outstanding Batch 3 gate from §10. `SOAK_LODSTORE_OVERRIDE=full` is the lever for
that run.

### 11.4 Two defaults re-tuned after the fact (same day, user decision)

- **`lodDistanceChunks` 512 -> 256.** Reverted to the historic value. This does not undo
  §7.3: the AUTO timestamp cache now derives ~30 MB at 256 (i.e. the old hand-tuned figure)
  and, unlike the fixed value it replaced, it *follows* the distance if anyone raises it.
  That is the durable half of the change.
- **`bytesPerSecondLimitPerPlayer` 20 -> 50 -> 25 MiB** (settled at 25). §8.1 argued for
  holding at 20 pending the cap sweep; the landing point is one notch above that and lands
  somewhere defensible on its own terms. The §8.1 reasoning is what matters when retuning:
  the cap charges RAW bytes because it bounds client decode work, so the 6.25:1 from
  compressed columns did NOT loosen it — at 25 MiB counted the wire cost is ~4 MB/s while
  the client still decodes 25 MiB/s. **25 MiB puts the ceiling AT the ~25 MB/s rate the
  elytra wall reproduced at, rather than above it**: traffic can reach the rate that once
  hurt but cannot exceed it, and the mechanism that actually caused the wall (the scan-cadence
  gate) is fixed. The §11.7 cap sweep remains the falsifiable check.
- **2026-08-05 amendment (v0.9.1, user decisions): per-player 25 -> 15 MiB, global
  256 -> 60 MiB.** The per-player ceiling now sits comfortably BELOW the elytra-wall
  incident rate instead of AT it (below even §8.1's conservative 20); the global ceiling
  reverses most of §3.2's raise and binds at FOUR concurrent full-rate LOD players — a
  deliberate total-egress bound for typical hosts, with "raise the global first" as the
  operator guidance on bigger fleets. Every soak scenario except bandwidth-throttle and
  dirty-during-backfill rides these defaults: if a scenario reds on convergence timing
  after this change, the cut is the first suspect (re-baseline, don't chase phantoms).
  The §11.7 cap sweep remains the falsifiable check for both.

- **2026-08-13 erratum (v0.11.0 stage E1 — far players)**: six new server keys join the
  audited surface, all clamped by the shared static helpers (the stage-C R-2 rule from
  the outset): `farPlayers` "off"/"opt-in"/"on" (compiled default **off** — E1 ships
  inert; the E2 defaults decision owns any flip), `farPlayersUpdateIntervalTicks` 10
  (2..100), `farPlayersMaxDistanceBlocks` 2048 (128..16384),
  `farPlayersMinDistanceBlocks` 0 (0..; cross-field: dragged under max at validate,
  excluded from the SHARED_BOUNDS sweep as a derived bound), `farPlayersSendSpectators`
  false, `farPlayersExclude` empty list. `farPlayers` and `farPlayersMaxDistanceBlocks`
  are `/lsslod set` rows (R-9) sharing those exact helpers. This file predates the keys;
  the audit tables above deliberately do NOT enumerate them — this erratum is the record.

- **2026-08-13 erratum (v0.11.0, user decision at the F pause): `lodYieldsToVanillaTransport`
  default false → TRUE.** Supersedes the v0.10.0 A2 ships-unarmed stance and its planned
  live-E3-A/B precondition — the v0.11.0 Modrinth manual-testing pause is the live
  observation window instead. Soaks/gametests provably unaffected (loopback channels
  never report unwritable — the CI-inertness pin in TransportYieldFlushTest); both
  config-suite default pins flipped with the change.
