# Moonrise-Fabric low-priority disk reads (reflective bridge)

Status: IMPLEMENTED + LIVE-VALIDATED (2026-07-30, branch feat/moonrise-low-priority-reads)
— follow-up to issue #69's Moonrise compat (PR #70, merged 2026-07-30).
Live gate (§5) result: `disk-saturation` with real moonrise-opt 1.1.0
(`SOAK_EXTRA_GRADLE_ARGS="-Pbenchmark.moonrise=true"`, run 20260730T175822Z) — checker
PASS 0 violations/0 warnings, adoption INFO fired once, ZERO
"Background-priority disk reads unavailable" (pre-bridge it would fire),
disk.submitted=completed=1960 with errors=0/saturated=0, superseded=350 (the ≥100
premise held under Moonrise IO). Vanilla control leg (run 20260730T175422Z): PASS,
2144 reads, no Moonrise lines — the null-bridge path is bit-identical to today.
Review round 2 (implementation, three agents — runtime/integration/test lenses):
2 more MAJORs fixed (the gitignore-eaten shaded-package Priority stub broke
clean-checkout compilation — force-tracked + `!**/src/**/libs/**` negation; weak
pins — decoy overloads + bridge-invocation counting). Runtime agent probe-verified
resolution+invoke against the real 1.1.0 class files (unique non-synthetic 7-arg
match, WMTE-free adaptation, defensive-copy callbacks, shutdown ISE lands in the
per-chunk triage domain).
Target: `main`, Fabric module only.
Review round 1 (plan, 2026-07-30, one adversarial agent): 1 MAJOR fixed — the
invoke-throw latch domain is now TYPED (linkage/adaptation only; Moonrise's own
synchronous runtime throws — shutdown races, `PrioritisedTask.queue()` on a retired
executor, verified in bytecode — triage per-read without latching, Paper parity).
Plus: execution-time latch re-check in the read closure (no in-flight `disk.errors`
burst), instance-scoped resolution/warn state (`build(modLoaded, lookup)`, the
AntiXray pattern — test-order independence), Moonrise-aware fallback warning text,
`read_path` diag token, and the disk-saturation premise caveat for the live gate.

## 1. Problem

PR #70 fixed Moonrise's *dirty-detection* crash (hook retargeted to
`SerializableChunkData.copyOf`). The *disk-read* side was never exercised live: the
Moonrise dirty-broadcast soak did zero disk reads (`disk.submitted: 0` — warm world,
all in-memory/up-to-date serves), so what a Moonrise-Fabric server does on a cold LOD
read has never run outside analysis. From the Moonrise-Fabric 1.1.0 bytecode
(`SimpleRegionStorageMixin.initHook`): Moonrise **nulls the vanilla `worker` field**
(and steals its `RegionFileStorage`). So today, on a Moonrise server:

1. `ChunkDiskReader.resolveBackgroundHandles` NPEs on the null worker → caught →
   latches `backgroundIncompatible` → warns once with the *C2ME* message → falls back
   to `chunkMap.read` + the adaptive read throttle (Approach B).
2. Moonrise's `ChunkMapMixin` overrides `read(ChunkPos)` to route into
   `MoonriseRegionFileIO` — correct bytes, but at Moonrise's **default read priority**,
   competing with gameplay chunk loads.

Meanwhile the Paper twin (`PaperChunkDiskReader`) calls
`MoonriseRegionFileIO.loadDataAsync(..., intendingToBlock=false, Priority.LOW)` so LOD
reads defer to gameplay's NORMAL — and Moonrise-Fabric is the *same* Moonrise. The gap:
Fabric+Moonrise should get the same LOW-priority, read-your-writes read path, via
reflection (no compile-time dep), failing gracefully to today's exact behavior.

## 2. Verified Moonrise-Fabric API surface (javap on moonrise-opt 1.1.0+87549dd, MC 26.2)

- Mod id: `moonrise` (fabric.mod.json; depends `minecraft >26.1.2 <26.3`).
- Entry class (NOT shaded, same name as Paper):
  `ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO`
- The overload Paper uses exists verbatim:
  ```
  public static Cancellable loadDataAsync(ServerLevel, int, int,
      MoonriseRegionFileIO$RegionFileType,
      java.util.function.BiConsumer<CompoundTag, Throwable>,
      boolean /* intendingToBlock */,
      Priority)
  ```
- **The support types ARE shaded on Fabric** (unlike Paper):
  `Priority` = `ca.spottedleaf.moonrise.libs.ca.spottedleaf.concurrentutil.util.Priority`
  (enum, has `LOW`), return type `Cancellable` similarly shaded (we ignore it — Paper
  does too). `RegionFileType` is a nested enum of the entry class with `CHUNK_DATA`.
- The callback is a plain `java.util.function.BiConsumer` — a lambda can be passed
  through reflection with no proxying.
- The Moonrise-Fabric jar's bytecode references Mojang-official MC names
  (`net.minecraft.server.level.ServerLevel` — 26.2's runtime namespace, which is why
  `localRuntime` staging works). Direct class literals on our side (`ServerLevel.class`,
  `CompoundTag.class`) therefore match at dev AND production runtime; per project rule
  we never `Class.forName` an MC type.

## 3. Design

### 3.1 `MoonriseReadCompat` (new, `fabric/src/main/java/dev/vox/lss/compat/`)

Zero compile-time dependency, following the `VoxyCompat`/`AntiXrayCompat` pattern.
Resolved **once per JVM** (lazy static holder — the mod set cannot change at runtime):

1. Gate: `FabricLoader.getInstance().isModLoaded("moonrise")` → absent = unavailable
   (no classloading attempted; honest "the mod is present" signal, same as `ModCompat`).
2. `Class.forName("ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO")`.
3. Scan `getMethods()` for the exact overload: name `loadDataAsync`, 7 params,
   `(ServerLevel.class, int.class, int.class, <enum>, BiConsumer.class, boolean.class,
   <enum>)` — the `RegionFileType` and `Priority` classes are taken **from the matched
   method's own parameter types**, never hardcoded, so the bridge is immune to the
   shaded-vs-unshaded package difference and to future re-shading. Both param 3 and
   param 6 must be enums.
4. Enum constants via `Enum.valueOf`: `CHUNK_DATA` on param-3's type, `LOW` on
   param-6's type.
5. `MethodHandles.lookup().unreflect(method)` → cached `MethodHandle`.

Hardcoded strings are only: the entry class name, the method name, and the two enum
constant names — all Moonrise-owned API identifiers that no remapper touches.

**Failure containment (resolution):** every step under `catch (Throwable)` → the bridge
resolves as unavailable (null). If `isModLoaded("moonrise")` was true but resolution
failed, warn once: "Moonrise detected but its IO API could not be resolved — LOD reads
fall back to the standard ladder" (diagnoses future Moonrise signature drift instead of
leaving only the misleading C2ME-flavored warning). Resolution failure is *latched* —
never retried, never throws out.

**Instance-scoped state (the AntiXray `buildCarrier` pattern):** resolution is a pure
function `MoonriseReadCompat.build(boolean modLoaded, ClassLookup lookup, DriftWarn warn)`
returning an
instance that owns its own resolution result and warn-once state; the production
static holder wraps exactly one instance built with the real loader check +
`Class.forName`. Tier-1 tests construct fresh instances with injected lookups, so the
"latched, never retried" and "warns exactly once" pins are order-independent (no
JVM-wide static poisoning across tests — Gradle runs all Tier 1 in one JVM).

Public surface (`ChunkDiskReader` lives in another package, so these are public, like
`AntiXrayCompat.callSerializing`):

```java
/** Nullable — non-null only when Moonrise is loaded and the API resolved. */
public static LowPriorityRead resolveOrNull();   // delegates to the static holder

@FunctionalInterface
public interface LowPriorityRead {
    CompletableFuture<Optional<CompoundTag>> read(ServerLevel level, int cx, int cz);
}
```

The returned function mirrors `PaperChunkDiskReader.moonriseReader` exactly: build a
`CompletableFuture`, invoke `loadDataAsync(level, cx, cz, CHUNK_DATA, (tag, err) ->
{ if (err != null) f.completeExceptionally(err); else f.complete(Optional.ofNullable(tag)); },
false /* intendingToBlock — we block on our own pool thread; no escalation past LOW */,
LOW)`, return the future. The `Cancellable` return value is ignored (Paper ignores it
too). `tag == null` → `Optional.empty()` → the authoritative not-found ladder
(memoization + generation escalation), identical to Paper's semantics.

### 3.2 `ChunkDiskReader` ladder change

`submitReadDirect` decision order becomes:

```
if (!useBackgroundReadPriority || backgroundIncompatible)
    → chunkMap.read                                   (unchanged: rollback / latched)
else if (moonrise bridge available && !moonriseIncompatible)
    → moonrise LOW read                               (NEW — before the IOWorker accessor)
else
    → backgroundReaderOrFallback(chunkMap)            (unchanged: vanilla IOWorker path,
                                                       C2ME-style latch+throttle fallback)
```

- The Moonrise rung is consulted **before** the IOWorker accessor: on a Moonrise server
  the accessor path can only ever latch-and-fall-back, and on a vanilla/C2ME server the
  bridge is null and the rung is skipped — behavior is bit-identical to today whenever
  Moonrise is absent.
- **No adaptive throttle on the Moonrise rung.** Moonrise `Priority.LOW` is the read
  protection, exactly as on Paper (the throttle exists for the case where we have *no*
  priority lever). The throttle still engages if the rung is unavailable and the
  IOWorker path latches incompatible — today's behavior, unchanged.
- **Read-your-writes:** `loadDataAsync` serves in-progress writes, so on Moonrise
  servers this *closes* the documented Fabric background-path read-your-writes gap
  (vanilla-IOWorker path keeps its gap; that note stays scoped to the vanilla path).
- One-time INFO at adoption (first Moonrise-path submit, or reader construction):
  "Moonrise detected — LOD disk reads routed through Moonrise's prioritised IO at LOW
  priority." (Mirrors the engine-adoption logging style; also the greppable live-gate
  marker, see §5.)
- **Moonrise-aware fallback warning:** when the vanilla-IOWorker fallback DOES fire on
  a server where `isModLoaded("moonrise")` is true (bridge unresolved or latched
  incompatible), `warnBackgroundUnavailable` names Moonrise instead of C2ME — the
  fixed "e.g. C2ME / IOWorker executor is absent" text is misleading there, and the
  bridge already knows the mod is loaded.
- **Diagnostics token:** the Moonrise rung is live-only (CI can never reach it), same
  as the C2ME fallback — which got `read_throttle=ENGAGED(...)` in `/lsslod diag` for
  exactly that reason. Add a `read_path` token to the disk reader diagnostics
  (`moonrise-low` / `moonrise-incompatible` / existing behavior otherwise) so the live
  gate has a machine-checkable signal and a mid-session latch is visible.
- `useBackgroundReadPriority=false` stays a true full rollback to `chunkMap.read` on
  every platform/mod combination — no new config key. The existing flag is the kill
  switch, mirroring Paper.

**Failure containment (per read, on the reader pool):** two distinct failure domains,
deliberately not conflated — and the latch domain is **typed, not positional**.
Moonrise's `loadDataAsync` provably throws unchecked exceptions *synchronously from
its own body* for per-call runtime-state reasons (verified in the 1.1.0 bytecode:
`getControllerFor` has an `IllegalStateException` rung; `ChunkIOTask.scheduleReadIO`
→ `PrioritisedTask.queue()` throws `IllegalStateException` on a retired executor —
i.e. a read racing server shutdown throws *through the invoke*). On Paper the same
throw propagates into the base's per-read `catch` — per-chunk triage, no latch. So:

- **Latch domain (permanent, linkage/adaptation only):** `WrongMethodTypeException`,
  a `ClassCastException` from handle adaptation, or any `LinkageError` from the invoke
  — these are deterministic "the resolved handle doesn't actually fit" shapes where
  every future invoke would fail identically. Latch the one-way `moonriseIncompatible`
  (volatile, same shape as `backgroundIncompatible`), warn once, and serve that read via
  the inline foreground fallback (no error surfaced — strictly better than completing
  exceptionally). Subsequent reads fall down the vanilla ladder (which on a real
  Moonrise server then latches `backgroundIncompatible` → throttle + `chunkMap.read` —
  today's behavior, reached automatically). **In-flight closures:** the read lambda
  re-checks the latch at *execution* time and falls back inline to the captured
  `chunkMap.read` — the already-queued cohort (up to threads + 32×threads closures)
  must not burst `disk.errors` (an A7 always-fail in the soak checker).
- **Everything else** — any other Throwable from the invoke (Moonrise runtime state,
  shutdown races) exactly like a **callback `err != null`** (per-chunk IO error,
  corrupt region): completes the future exceptionally → the base's standard error
  triage. **Never** latches — Paper semantics, per-chunk containment, the Moonrise
  rung stays active for the next read.

Timeout semantics unchanged: the future is awaited under `DISK_READ_TIMEOUT_SECONDS`
in `readAndSerializeSections`, same as every other read flavor.

### 3.3 Threading

- Resolution: static, immutable after first use (holder idiom) — safe from any thread.
- `loadDataAsync` is invoked from the LSS reader-pool thread; that is exactly how the
  Paper twin calls it (and the Folia note applies: region files are not regionised).
  The callback may fire synchronously or on a Moonrise IO thread; it only completes
  our future — no chunk-system reentrancy. Blocking wait + serialization stay on the
  LSS reader pool.
- Per-reader latches (`moonriseIncompatible`) volatile + one-way, warn guarded by an
  `AtomicBoolean` CAS — same discipline as the existing latch.

## 4. Test plan

### Tier 1 (fabric JUnit, fabric-loader-junit)

Stub classes with the **real Moonrise package names** under
`fabric/src/test/java/ca/spottedleaf/moonrise/...` (the `me.drex` AntiXray-stub
pattern): the entry class with the exact 7-arg overload recording
`(level, cx, cz, type, intendingToBlock, priority)` into a static test sink and
completing the callback from test-controlled state, the nested `RegionFileType` enum,
and the shaded-name `Priority` enum. The production `Class.forName` path then resolves
the stubs directly; only the `isModLoaded` gate is bypassed via a package-private seam
(a resolution entry point that skips the loader check).

New `MoonriseReadCompatTest` pins (fresh `build(modLoaded, lookup)` instance per test
— no shared statics):
1. Happy path: resolves; a read invokes the stub with `CHUNK_DATA`,
   `intendingToBlock == false`, `priority == LOW` (the Paper-parity pins).
2. Callback semantics: `err != null` → exceptional future (and NO incompatible latch);
   `tag == null` → `Optional.empty()`; `tag != null` → `Optional.of(tag)`.
3. Resolution ladder (wrong-shape classes injected via the lookup seam): missing class
   → null; missing 7-arg overload → null; non-enum priority param → null; missing
   `LOW`/`CHUNK_DATA` constant → null; a lookup that throws (incl. LinkageError) →
   null, contained, latched on that instance, never retried; mod-not-loaded → null
   without classloading.
4. Drift warning: resolution failure with the mod loaded warns exactly once (on that
   instance).

`ChunkDiskReaderTest` additions (injected bridge seam, mirroring the
`resolveBackgroundHandles` seam):
5. Bridge available → the Moonrise read is chosen; the IOWorker accessor is never
   consulted; the adaptive throttle stays disabled.
6. Bridge available + `useBackgroundReadPriority=false` → `chunkMap.read` (full
   rollback pin — the Moonrise rung must sit under the flag).
7. Latch-domain split, both flavors: (a) an invoke throwing `WrongMethodTypeException`
   (or `LinkageError`) latches `moonriseIncompatible`, warns once across repeated
   reads, subsequent reads fall to the vanilla ladder; (b) an invoke throwing
   `IllegalStateException` (Moonrise runtime state / shutdown race) triages as a
   per-read error, does NOT latch, and the next read still uses the Moonrise rung.
8. Execution-time latch re-check: a read closure built before the latch fired, run
   after it, uses the inline `chunkMap.read` fallback instead of erroring (no
   `disk.errors` burst from the in-flight cohort).
9. Bridge null → behavior identical to today (the existing tests, unmodified, are the
   pin; they must pass without edits).

### Tier 2 / Tier 3

No new gametests: dev/CI runtimes have no Moonrise, the bridge resolves null, and the
existing `SerializerParityGameTests` byte-parity pins already cover the unchanged
ladder. (Same rationale as the C2ME fallback: only a live server can reach the branch.)

## 5. Live gate (the only real proof the reflection resolves)

`SOAK_EXTRA_GRADLE_ARGS="-Pbenchmark.moonrise=true"` with a scenario that performs
**cold disk reads** — dirty-broadcast does zero (that's how the gap survived #69).
Use `disk-saturation` (hammers the read path under threads:1; its laws pin
`disk.saturated == 0` + `superseded >= 100`, both unaffected by the read *function*)
— plus `fresh-backfill` if the base world needs regenerating. Pass criteria on the
Moonrise leg:

- Checker verdict green (conservation laws hold through the Moonrise read path).
- `server.log` contains the Moonrise-adoption INFO line and does NOT contain
  "Background-priority disk reads unavailable" (today it would).
- `disk.submitted > 0` in the final snapshot (the run actually read — the assertion
  dirty-broadcast was missing), and the `read_path` diag token reads `moonrise-low`.

Premise caveat: disk-saturation's `superseded >= 100` floor was MEASURED on the
vanilla-IOWorker path; under Moonrise IO the LSS `threads:1` pool + serialization
should still be the choke, but per CLAUDE.md's own rule a red there is a premise
question first, not a threshold question. Fallback cold-read gate if the premise
shifted: `fresh-backfill` with Moonrise (cold generation, then its saved chunks
disk-read on later re-declarations) — also with the WSL2 A7 environmental entries in
mind (timeout-triaged `disk.errors` on a loaded box are the documented flake, checked
counter-first).

A standard vanilla `./scripts/soak.sh disk-saturation` (no Moonrise) must stay green
too — the null-bridge path is the same code every CI run exercises.

## 6. Non-goals

- **C2ME keeps the adaptive-throttle fallback.** C2ME's chunkio rewrite is not
  Moonrise; no Moonrise classes exist there. Nothing changes.
- **No Paper/common changes.** `PaperChunkDiskReader` already does this natively; the
  bridge is Fabric-only. `AbstractChunkDiskReader` is untouched.
- **No new config.** `useBackgroundReadPriority` governs the rung, as it does Paper's.
- **No wire/protocol impact.** The read function changes; bytes, triage, and
  serialization are untouched (transcode path identical — same NBT in).

## 7. Docs / rollout

- CLAUDE.md: update the `ChunkDiskReader` bullet (Moonrise rung before the IOWorker
  path; C2ME unchanged) and the Compatibility section (MoonriseReadCompat).
- Release notes item for the next release (Fabric + Moonrise: LOD reads now defer to
  gameplay at LOW priority; fixes the misleading C2ME warning on Moonrise servers).
- Merge via PR to `main` (protected branch), no release tag in this task.
