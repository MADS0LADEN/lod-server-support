# Runtime settings commands + backfill remaining-estimate + `/lsslod help` + package descriptions — plan

**Status: IMPLEMENTED — shipped in v0.11.0** (stage C, 2026-08-13; `/lsslod set` on both platforms, `common/config/RuntimeSettings`; kept as the design record). **Reviewed 2026-08-12** (1 Fable
subagent, all load-bearing citations re-verified): verdict IMPLEMENT WITH FIXES,
2 MAJOR / 9 MINOR — all folded into this revision. Headlines: the SessionConfig
re-push must enumerate + send on the pump/main thread AFTER the mailbox drain (a
command-thread iteration can read an unflipped dialect and push a protocol-20 config
at a legacy client, which kills its session until rejoin), and `genSlotCap` must be
volatile (the router reads it on the processing thread — a plain-field tick-poll write
has no happens-before). Four items, one plan:

1. Server admin commands to change LSS settings at runtime — minimum keys: the
   generation concurrency caps, the bandwidth rate limits, and `lodDistanceChunks`.
2. Side fix: `/lsslod store backfill status` additionally shows an estimate of
   remaining regions/columns, not just processed counts.
3. `/lsslod help` — a brief description of every subcommand.
4. Update the embedded package descriptions (fabric.mod.json + plugin.yml) to:
   *"Voxy multiplayer support (unofficial). Enables players with Voxy to see fully
   rendered terrain out to hundreds of chunks on multiplayer servers without needing
   to explore the world first."*

---

## Ground truth from exploration (what the design must respect)

**No runtime config mutation exists anywhere today.** `LSSServerConfig.CONFIG` is a
`static final` singleton with public mutable fields (`LSSServerConfig.java:7-8`);
Paper's `PaperConfig` is a plugin instance field the service shares by reference.
`JsonConfig.save()` (`JsonConfig.java:91-109`, tmp + atomic move, drops
`@HiddenFromFile` keys at default) is production-ready but only ever called inside
`load()`. `validate()` also only runs at load — and is pinned idempotent
(`ConfigValidationTest.java:558-571`), which makes "mutate field → re-run validate()"
a safe application step (it also handles the cross-field clamp: per-player gen cap
clamps against the configured global, `ServerConfigBase.java:599-600`).

**Per-field runtime verdicts** (from the consumption trace):

| Field | Verdict | What runtime application needs |
|---|---|---|
| `mbPerSecondLimitPerPlayer` | LIVE | Nothing — read per tick at the flush (`RequestProcessingService.java:588`, Paper `:1005`). Must be clamped before storing (an unclamped huge value overflows the `(int)` cast to negative and throttles to zero; a negative resets to the compiled default via `resolveMb`). `validate()` covers both. |
| `mbPerSecondLimitGlobal` | CAPTURED | `SharedBandwidthLimiter.maxBytesPerSecond` is final (`SharedBandwidthLimiter.java:27,44`), no setter; the live re-reads at `/lsslod stats` are display-only — mutation alone makes stats lie. Limiter is tick-thread-only by contract (`:22-24`). |
| `generationConcurrencyLimitGlobal` | CAPTURED | Final `maxConcurrent` in `ChunkGenerationService` (`:58,77`) / `PaperChunkGenerationService` (`:69,108`); admission gate at `:105`/`:132`. A service rebuild would strand outstanding force-load tickets — must be a field update, not a rebuild. |
| `generationConcurrencyLimitPerPlayer` | MIXED | Captured in the gen service (`maxPerPlayerActive`) AND per player at registration (`AbstractPlayerRequestState.genSlotCap`, `:159,177`, admission at `:984-990`) — `computeIfAbsent` means new joins would pick up a change while existing sessions keep the boot value; that split needs fixing, not accepting. Live only in the v16 SessionConfig advertisement. |
| `lodDistanceChunks` | MIXED | LIVE: handshake replies (all dialects), ingress range filter (`RequestProcessingService.java:381`, Paper `:774`), v16 declare pass, dirty broadcaster, yield prune, stats echo. CAPTURED: `OffThreadProcessor.diskReadDoneSweepRadiusChunks` (`:171-173,193` — **the in-source warning says a future reload path must re-derive it; an under-sized radius destroys still-declarable done-bits, the corrupting direction**) and the AUTO `ColumnTimestampCache` byte budget (`effectiveTimestampCacheMB()`, captured at `ColumnTimestampCache.java:82,111`). Connected clients keep their handshake-time distance: Fabric has NO mid-session SessionConfig push; Paper's only one is the v16 re-attach prompt (heals by re-handshake). Client-side, a same-rung mid-session SessionConfig already works: `ClientSessionGate.onSessionConfig:344-357` retires and rebuilds the manager. |

**Command surfaces**: Fabric `LSSServerCommands` — Brigadier tree behind one
`Permissions.COMMANDS_GAMEMASTER` requires (`:18-19`), no help literal, no unit tests
(only Tier 2 `CommandGameTests` + the `DiagnosticsFormatter` goldens). Paper
`PaperCommands` — arg-switch CommandExecutor with exact-string golden tests
(`PaperCommandsTest`, incl. tab-completion pins at `:242`) and usage strings as
de-facto help; permission is plugin.yml's `lss.admin`. `PluginYmlContractTest:82` pins
**exactly one command key** — everything new must live under the `/lsslod` root.
Runtime-mutation precedent: `store invalidate all` / `backfill start|stop` already
mutate service state behind the same gates.

**Backfill status**: everything needed for a remaining-estimate already exists inside
`StoreBackfill.run()` but as thread-locals — `plan.size()` (regions remaining at walk
start, done-marks pre-skipped at `enumerate():425`), loop index `ri`, and
`describePlan`'s planned-bytes sum (`:442-460`, logged once at `:209` then discarded).
The `running:` statusLine form (`:348-350`) is pinned by NO test; the terminal forms
ARE pinned (`terminalStatusLineIsAScriptConsumedContract`, regex over
`complete: N regions, ...`) and script-consumed by `scripts/backfill_profile.sh:216-234`
— extend `running:`, leave the terminal lines byte-stable.

**Descriptions**: current strings live in exactly two files —
`fabric/src/main/resources/fabric.mod.json:6` and
`paper/src/main/resources/plugin.yml:17`. No test or release gate pins the old
wording (`release_check.py` only requires LSS/VSS descriptions to *differ*, which the
independent VSS constants in the vssJar tasks preserve). Constraints: plugin.yml's
description must stay ONE line starting at column 0 (the VSS rewrite regex
`^description:.*$` is single-line), and the string must avoid `$`/`\`/`": "` (Gradle
`expand()` + YAML plain scalar) — the proposed wording satisfies all three as-is. Do
NOT touch plugin.yml lines 22/27 (command/permission descriptions — release_check
token-pinned).

---

## Part 1 — `/lsslod set` runtime settings

### Command shape

- `/lsslod set <key> <value>` — apply + persist; replies with the effective (clamped)
  value and an "applies …" note per key.
- `/lsslod set` (no args) — list the supported keys with current effective values.
- Same admin gates as today (root `.requires(gamemaster)` on Fabric; `lss.admin` on
  Paper). Tab completion for keys on both platforms.

### Shared key registry (`common/`)

A small registry class (e.g. `common/config/RuntimeSettings`) mapping key name →
type, getter, setter, and a short "when it applies" note, over `ServerConfigBase` —
shared by both command surfaces so Fabric/Paper cannot drift (the `HandshakeGate`
parity precedent). Initial keys, exactly the user's minimum:

| Key | Type | Application |
|---|---|---|
| `lodDistanceChunks` | int | immediate server-side; connected clients via the SessionConfig re-push (below) |
| `generationConcurrencyLimitGlobal` | int | immediate (tick-poll, below) |
| `generationConcurrencyLimitPerPlayer` | int | immediate incl. existing sessions (tick-poll) |
| `mbPerSecondLimitPerPlayer` | double | immediate (already live) |
| `mbPerSecondLimitGlobal` | double | immediate (limiter reconfigure, below) |

Apply sequence per `set`: parse → **clamp on a scratch value and assign the final
post-clamp value ONCE** (review fix: Paper's ingress and handshake paths read config
off non-pump threads, so raw-assign-then-validate leaves a transient-unclamped window
— one read of an out-of-band distance, or an mb value whose `(int)` cast goes negative;
Fabric is safe by construction, command thread = tick thread) →
**`config.validate()`** (idempotent, pinned; performs the cross-field work, e.g. the
per-player-vs-global gen-cap cross-clamp) → **`config.save()`** (first post-startup
caller; hidden-key dropping and the bandwidth-legacy `-1` sentinels already make the
written file correct — and every boot already does load→validate→save, so runtime
saves lose nothing new) → reply with the post-validate value. Threading: Fabric
commands run on the server thread (= tick thread) — direct. Paper marshals the
mutation through the GlobalRegionScheduler pump (`FoliaWiringContractTest` disciplines
apply; command may arrive on a region thread on Folia).

### Making the captured consumers follow (the tick-poll pattern)

Rather than plumbing command→service callbacks, each service applies config at the
top of its own tick — the thread that owns the state (precedent: the broadcaster
re-reads config per tick, pinned by `midRunConfigIntervalChangeTakesEffectImmediately`):

1. **`SharedBandwidthLimiter`**: add `reconfigure(long newMax)` (tick-thread only,
   per the class contract) — update the ceiling, clamp `availableTokens` down to it,
   keep `lastRefillNanos`. Service tick calls
   `limiter.reconfigure(config.bytesPerSecondGlobal())` (cheap no-op compare inside).
2. **Generation caps**: add `ChunkGenerationService.updateCaps(int global, int
   perPlayer)` (and Paper twin) — plain field updates called from the owning
   service's tick before admission (same thread as the gate reads; the final
   modifiers come off `maxConcurrent`/`maxPerPlayerActive`). Lowering a cap never
   cancels in-flight generations — it only gates new admissions (document in the
   command reply).
3. **Per-player `genSlotCap`**: make it a **`volatile`** field with
   `updateGenSlotCap(int)`, applied to every registered state in the same tick pass —
   removes the "new joins only" split the trace flagged. Volatile is REQUIRED, not
   plain (review MAJOR): `tryAdmit` reads it on the PROCESSING thread
   (`AbstractPlayerRequestState.java:984-990`; the field comment says "caps are
   immutable" precisely because of this) while the tick pass writes from the
   main/pump thread — a plain field has no happens-before and the change may never
   become visible. Match the `heldSyncSlots`/`heldGenSlots` volatiles beside it. (The
   gen-SERVICE caps in item 2 are genuinely same-thread — submit/tick are
   main-thread-only — so plain fields are fine there.)
4. **M3 sweep radius** (`OffThreadProcessor.diskReadDoneSweepRadiusChunks`): becomes
   volatile, re-derived each lifecycle tick as
   **`max(currentValue, derive(config.lodDistanceChunks))`** — a monotonic max.
   Rationale (corrected by review): an under-radius sweep of a still-declarable
   done-bit is documented as semantically free — worst case one redundant read/serve
   (`AbstractPlayerRequestState.java:1043-1056`; in-pipeline/grace entries are kept
   regardless of radius) — so monotonic max avoids REDUNDANT SERVES, not corruption;
   do not let a future reader treat it as a data-integrity constraint. The
   lowered-distance leak (done-bits beyond the new ingress filter that the sweep can
   no longer cull) is bounded by the previously-served area and dies as players move
   or disconnect; the cost of a too-large radius is only memory already bounded by
   M3's trim.
5. **AUTO timestamp-cache budget**: deliberately NOT rebuilt (captured final byte
   budget). Raising the distance leaves the cache sized for the boot distance —
   correctness-safe (eviction only causes redundant serves). The `set
   lodDistanceChunks` reply notes "timestamp cache stays at boot sizing until
   restart" when the config is in AUTO mode.

### Pushing the new distance to connected clients

Changing the distance is only user-visible if connected Voxy clients learn it. The
client already handles a same-rung mid-session SessionConfig (retire + rebuild the
manager, full rescan — `ClientSessionGate.onSessionConfig:344-357`). Add a
server-side re-push after a `lodDistanceChunks` set:

- For each registered player whose dialect is **current (v20)** — via
  `WireDialectTracker` — send a fresh SessionConfig built exactly like the handshake
  reply (Fabric: the `LSSServerNetworking.java:301` shape; Paper: the
  `LSSPaperPlugin.java:474` encoder, sent from the pump).
- **Ordering invariant (review MAJOR — state it and pin it):** the re-push must
  enumerate registered players and send **on the pump/main thread, AFTER the mailbox
  drain**. `WireDialectTracker.dialectOf` defaults untracked → CURRENT, and on Paper
  the dialect mark applies only in the pump drain's `beforeRegister` — an enumeration
  from the command thread (a region thread on Folia) can catch a registered player
  whose dialect flip is still pending, read CURRENT, and push a protocol-20 config at
  a legacy client, whose gate hard-requires its own version and silently sets
  `serverEnabled=false` — LOD dead until rejoin. Pin on Paper: a
  registered-but-flip-pending player is NOT pushed.
- **Legacy sessions (v19/v18/v16) are skipped** — their clients' mid-session-config
  behavior is release-frozen and unverified; they keep the handshake distance until
  rejoin. Say so in the command reply ("N legacy clients update on rejoin").
- Shrinking note: until a client rebuilds, it may declare beyond the new distance;
  those asks are range-filtered (counted `range_filtered`) — self-limiting for
  current-dialect clients (one round-trip via the re-push). For LEGACY sessions a
  shrink is worse than "update on rejoin" (review): their want-set positions beyond
  the new filter are never answered and never satisfied, so the client re-declares
  the out-of-range tail at 1 Hz for the rest of the session — `range_filtered` /
  `superseded` climb permanently and the client never converges (v16 is softer: its
  synthetic want-set entries expire on the 75 s TTL). Bounded work, but the command
  reply and release notes must say "legacy clients keep re-asking beyond the new
  distance until rejoin".

## Part 2 — backfill remaining estimate

Expose the worker's thread-locals as volatile progress fields on `StoreBackfill`,
written by the worker, read by the command:

- At walk start (`run()` after `enumerate()` + `describePlan`): `planRegionsTotal =
  plan.size()`, `planEstimatedBytes` — NOTE (review): `describePlan` returns a
  formatted string; the byte sum is internal, so exposing it is a small refactor
  (return/record the sum), not just a field write. Reset `planRegionsWalked` here so a
  second `start()` never shows the prior run's progress.
- Per region: bump `planRegionsWalked` (place the bump AFTER the `presentChunks()`
  null-check so an unreadable region doesn't count as walked-with-zero and bias the
  average low — review); accumulate `presentChunksSeen` from the table the walk
  already reads (`:477-493` — currently discarded), giving a measured avg
  columns/region. Definitional note (review): `walked` is the VISITED count — it
  deliberately differs from the terminal lines' `regionsDone`, which counts only
  done-MARKED regions (error/shed/undrained regions are excluded); the status line
  should show the visited count for progress and leave `regionsDone` semantics to the
  terminal lines. Estimate bias caveat: the walk is nearest-spawn-first, so the
  observed average over the walked (denser, near-spawn) prefix overestimates
  columns-left for the sparse frontier — acceptable for a `~` estimate, worth one
  comment.
- Extend the **`running:`** statusLine (unpinned) to:
  `running: <walked>/<total> regions, <deposited> deposited, <skipped> skipped,
  <errors> errors, <pauses> pauses, ~<remaining> regions / ~<columns> columns left`
  — columns-left = remaining × observed avg present-chunks (before the first region
  completes, fall back to `≤ remaining × 1024`). Terminal lines (`complete:` /
  `stopped:` / `capped:` / shutdown) stay **byte-identical** —
  `backfill_profile.sh` and `terminalStatusLineIsAScriptConsumedContract` pin them.
- `/lsslod store backfill status` needs no rendering change (it prints
  `statusLine()`); after a completed/stopped run the terminal line already carries
  the outcome, and `idle` (never started) stays `idle` — no idle-time enumeration
  (region `isDone` probes are store-thread-confined; not worth marshaling for a
  pre-start estimate the start log already prints).

## Part 3 — `/lsslod help`

- Shared help text builder in `common/` (e.g. `CommandHelp.lines(String rootLabel,
  boolean backfillAvailable)`) — one line per verb: stats, diag, store status,
  store invalidate all, store backfill start|stop|status (Fabric only), set, help.
  Both platforms render the same lines; the root label flows from
  `Brand.serverCommand()` / the Bukkit label, so VSS jars print `/vsslod` for free.
- Fabric: `help` literal + `.executes` on the bare root (today a bare `/lsslod` is a
  Brigadier parse error). Review-verified safe: the Tier 2 permission gametest
  (`lsslodRequiresGamemasterPermission`) still passes — the `.requires` gate sits on
  the ROOT literal, so a permissionless parse consumes zero nodes with or without a
  root `.executes`.
- Paper: `help` case; extend the no-arg/unknown usage strings to
  `<stats|diag|store|set|help>` and add `set`/`help` (+ key names at level 2) to
  `onTabComplete`. Also update `plugin.yml:23` (`usage: /lsslod <stats|diag|store>`)
  — unpinned by tests, and the VSS overlay's blanket `lsslod→vsslod` replace handles
  an extended line fine (review).

## Part 4 — package descriptions

Replace the `description` values in `fabric/src/main/resources/fabric.mod.json:6` and
`paper/src/main/resources/plugin.yml:17` with the new string (verbatim, one line).
Nothing else moves: the VSS overlay replaces descriptions wholesale with its own
constants, and no test pins the old wording. Precision note (review): the Fabric pair
check requires only the NAME to differ (`release_check.py:375-376`; description is
merely in the allowed-diff set), while Paper's check requires the description LINE to
have been rebranded (`:419-425`) — both stay green because the VSS constants differ
from the new LSS wording.

---

## Tests

- **Registry + apply (common/Tier 1)**: key parse/clamp round-trips (incl. the
  overflow case: a huge mb value must clamp, never go negative through the `(int)`
  cast; negative resets to compiled default — pin both), cross-clamp (lowering the
  global gen cap drags per-player down via validate()), save() round-trip (file
  reflects the set; hidden keys still dropped — extend `JsonConfigLoadTest`).
- **`SharedBandwidthLimiter.reconfigure`**: ceiling raise/lower, token clamp-down,
  refill honors the new ceiling.
- **Gen cap tick-poll**: extend the existing generation-service tests (Fabric +
  `PaperChunkGenerationServiceTest`) — `updateCaps` mid-run gates the next admission;
  in-flight tickets unaffected. Per-player: `updateGenSlotCap` visible to
  `tryAdmit` on an existing state.
- **Sweep radius monotonic max**: `OffThreadProcessor` test — raising distance grows
  the radius next tick; lowering never shrinks it within the run.
- **SessionConfig re-push**: unit-test the dialect gating (v20 pushed, v19/v18/v16
  skipped) through the existing service seams, PLUS the ordering-invariant pin
  (review MAJOR): on Paper, a registered player whose dialect flip is still pending
  in the mailbox must NOT be pushed — the enumeration runs pump-side after the drain.
  One Tier 2 gametest (a registered handshaken player receives a fresh SessionConfig
  after `set lodDistanceChunks`, mutate-and-restore in `finally` per the
  `ServiceLifecycleGameTests:902-910` precedent). NOTE (review): driving the real
  `set` handler calls `config.save()`, which mutates the gametest run dir's staged
  config file — contained (fabric/build.gradle's doFirst re-stages each run), but the
  test should restore-and-resave or the file-side effect gets chased as a "dirty run
  dir" mystery later.
- **Commands**: Paper — extend `PaperCommandsTest` goldens (new USAGE constants, help
  output, `set` happy/parse-error/unknown-key, tab completion lists). Fabric — add
  dispatch coverage to `CommandGameTests` (`lsslod help`, `lsslod set ...` through the
  real tree with permission gate intact); optionally the first Tier 1
  `LSSServerCommandsTest` if the set-handler is extracted to a testable body (match
  the PaperCommands supplier-seam shape). Refactor constraint (review):
  `ChannelAccessorContractTest:212-214` source-regex-pins that `showDiagnostics`
  feeds the LIVE `lodYieldsToVanillaTransport` flag to `yieldDiagLineOrNull` — any
  handler extraction must preserve that call shape.
- **Backfill**: extend `StoreBackfillTest` — `running:` line carries `X/Y regions` and
  the remaining terms (drive via the existing fake-reader rig); terminal-line pins
  unchanged (the existing contract test doubles as the regression guard);
  progress-field reset on a second `start()`.
- **Descriptions**: none needed (no pins exist); `release_check.py` pair checks run
  in CI as today.

## Docs / release notes

- README: command table gains `set` + `help`; note runtime changes persist to
  `lss-server-config.json`.
- CLAUDE.md: command surface bullets (both platforms) + the tick-poll pattern note on
  the four formerly-captured consumers.
- Release notes: New Features (runtime settings commands, help, backfill remaining
  estimate) — mention legacy-dialect clients pick up distance changes on rejoin; the
  description change needs no notes entry (storefront metadata).

## Risks / accepted constraints

- `validate()`-on-set re-emits the Folia store warning when applicable — cosmetic,
  accepted (it IS a warning-worthy state).
- Config file writes now happen at runtime: save() is atomic-move, brand-invariant
  filename; a concurrent hand-edit loses (last writer wins) — same as any
  command-managed config.
- Legacy-dialect sessions keep the old LOD distance until rejoin (deliberate — their
  mid-session config handling is unverified in released clients).
- AUTO tscache budget stays boot-sized until restart (correctness-safe; noted in the
  command reply).
- The Folia path: the set-apply is marshaled to the pump; the tick-poll consumers all
  run on the pump already. Folia stays experimental — mention in release notes if any
  item ships Folia-visible behavior.

## Verification

1. `./gradlew :fabric:test -x runGameTest -x runClientGameTest` + `./gradlew :paper:test`
   (new registry/limiter/gen-cap/sweep/commands/backfill pins).
2. `./gradlew :fabric:runGameTest` (new CommandGameTests dispatches + existing tree).
3. `python3 scripts/release_check.py` after `:paper:shadowJar` + `:fabric:build` — the
   description change must keep the VSS pair checks green.
4. Live smoke (`./test-server.sh run-fabric-store` + a real Voxy client):
   `/lsslod help`; `/lsslod set mbPerSecondLimitPerPlayer 5` → visible throughput drop
   in `/lsslod stats`; `/lsslod set lodDistanceChunks 128` → client rescans to the new
   distance without rejoin (watch the SessionConfig re-push + `range_filtered` settle);
   `/lsslod store backfill start` → `store backfill status` shows `X/Y regions ... left`.
   Persistence check (review correction): `test-server.sh` REWRITES the staged config
   on every invocation, so do NOT verify persistence by re-running the script —
   inspect `lss-server-config.json` before re-staging, or restart the server process
   directly.
5. One soak (`./scripts/soak.sh fresh-backfill`) to confirm the tick-poll additions
   didn't disturb the law baselines (defaults unchanged → should be byte-identical
   behavior).
