# Research spike: a Foxy-style Sodium-0.8 bridge for user-supplied Voxy (NeoForge 1.21.1)

**Date:** 2026-08-15 · **Status:** research complete, unimplemented · **Requested by:** VoX

## 1. Goal and constraint

Make the community Voxy NeoForge 1.21.1 port (j-shelfwood `voxy-neoforge`, currently
`voxy-0.2.9-alpha`, hard-paired with Sodium 0.6.13) run beside **Sodium 0.8.12-beta.1** —
the Sodium that Sable 2.x requires and that Create+-class packs are therefore locked to —
**without redistributing Voxy**. The model is Foxy's: we ship only a compatibility layer;
the user supplies the Voxy jar themselves. Voxy is All Rights Reserved, so this is the
only clean distribution posture (the existing full-port forks are ARR-gray).

Motivating stack (the bitvox/Create+ pairing from the 2026-08-15 "LSS not active"
investigation): NeoForge 1.21.1 (21.1.248), sodium-neoforge-0.8.12-beta.1, sable 2.0.1,
LSS/VSS 0.11.0. Without a working Voxy there is no `LSSApi` consumer, the client never
handshakes, and LSS idles by design. This bridge is what would light LSS up on such packs.

## 2. Prior art (all examined in source)

| Project | What it is | What we take from it |
|---|---|---|
| **Leclowndu93150/Foxy** (MIT) | Loads *unmodified Fabric* Voxy 0.2.16-beta on NeoForge 26.1.2: an `IModFileCandidateLocator` reads the user's Voxy jar, synthesizes `neoforge.mods.toml` from `fabric.mod.json`, extracts its nested libs, applies its access widener via a mod-shipped transformation service, and ships `net.fabricmc.*` stubs that delegate to NeoForge. | The distribution pattern (user-supplied jar, nothing redistributed, MIT), and proof that mod-shipped `ITransformationService` works on NeoForge if we need bytecode surgery. Mechanically we need almost none of it — see §4. |
| **j-shelfwood/voxy-neoforge** | The community NeoForge 1.21.1 port users already have (`voxy-0.2.9-alpha`). Native NeoForge (no Connector), Sodium dep declared **`[0.6.13,)` open-ended** — so it already *loads* beside Sodium 0.8 and fails only at runtime. | The target artifact. The open range means **no locator/metadata rewrite is needed at all**. |
| **falling-colud/voxy-forged** | Full source port of Voxy to NeoForge 1.21.1 **already adapted to Sodium 0.8.12-beta.2**, plus render extras (Iris viewport fix, LOD color/curve fixes, border-ring handling). ARR-gray redistribution ("provided for personal use"). | The **answer key**: diffing it against j-shelfwood yields the exact, complete Sodium-0.8 delta (§3). Its unchanged mixins also prove which 0.6-era injection targets survived into 0.8. |
| **falling-colud/make-it-compatible-voxy** | Drop-in compat patches for Voxy-next-to-other-mods on NeoForge 1.21.1 (Sable vehicles over LOD, Simple Clouds, fog mods, Big Water, LittleTiles), compiled against sable 2.0.3 + sodium 0.8.12-beta.2, **compileOnly/libs pattern — redistributes nothing**. | The engineering pattern our bridge should copy (self-gating patches, compileOnly against user-obtainable jars, MixinSquared available if we ever need mixin-into-mixin). Also proof that Voxy + Sable + Sodium 0.8 coexist on 1.21.1. |

## 3. The verified breakage inventory

Method: constant-pool extraction of every `net.caffeinemc` member reference in the
shipped `voxy-0.2.9-alpha` jar, checked descriptor-exact against the **actual
sodium-neoforge-0.8.12-beta.1 inner mod jar** (`META-INF/jarjar/net.caffeinemc.sodium-neoforge-*-mod.jar`
— the outer jar is just a locator shell), then cross-checked against the voxy-forged diff.

**Exactly three things break. Everything else is descriptor-identical.**

1. **`ShaderParser.parseShader(String, ShaderConstants)`** — returned `String` in 0.6,
   returns the record `ShaderParser$ParsedShader` (fields `src`, `includeIds`) in 0.8.
   Call site: `me.cortex.voxy.client.core.gl.shader.ShaderLoader`. This is the
   `NoSuchMethodError` observed live. Fix is literally `.src()` (voxy-forged's own fix
   is that one line).
2. **Voxy's `MixinRenderSectionManager` ctor `@Inject`** — the handler arg-captures
   `(ClientLevel, int, CommandList)`; 0.8.12-beta.1's `RenderSectionManager.<init>` is
   `(ClientLevel, int, SortBehavior, CommandList)`. Capture mismatch → apply-time
   failure (config is `required: true`, `defaultRequire: 1`). Latent behind crash 1
   (ShaderLoader dies first).
3. **Voxy's `MixinDefaultChunkRenderer` two `render` `@Inject`s** — handlers capture 5
   args; beta.1's `DefaultChunkRenderer.render` has 6 (trailing
   `boolean indexedRenderingEnabled`). Same apply-failure class, also latent.

Verified **fine** on beta.1: `SodiumWorldRenderer.instanceNullable` +
`initRenderer(CommandList)` (handler capture matches), `RenderSectionManager.getBuilder`,
`ChunkBuilder.getTotalThreadCount`, `ChunkRenderMatrices`, `RenderSection`
accessors/`setInfo`, `ChunkTrackerHolder.get`, `ChunkJobQueue` (`<init>` Semaphore
redirect + `shutdown` RETURN), `DefaultTerrainRenderPasses.CUTOUT`, `ShaderChunkRenderer`
ctor/begin/end (`RenderDevice` still exists in 0.8), `CameraTransform` fields,
`ColorSRGB`. The old-options-API user `VoxySodiumOptions` is **referenced by nothing**
in the shipped jar (its `MixinVideoSettingsScreen` isn't in the shipped mixin config) —
dead code, never classloads, harmless. `MixinRenderRegionManager` is compiled but not in
the shipped config either.

## 4. Bridge design (recommended)

A standalone client-only NeoForge mod (working name **`voxy-sodium8-bridge`**), MIT,
compileOnly against user-obtainable jars (fork jar + sodium inner jar), shipping no
third-party bytes. Because the fork is native NeoForge with an open Sodium range, there
is **no Foxy-style locator, no Fabric stubs, no metadata synthesis** — the whole bridge
is three runtime patches plus self-gating:

- **Patch 1 — parseShader:** our mixin into Voxy's `ShaderLoader` (`remap = false`;
  mixins may target another mod's classes) `@Redirect`s the `INVOKESTATIC parseShader`
  to the 0.8 form and returns `.src()`. The old descriptor exists in Voxy's bytecode,
  so the redirect matches even though it would never link.
- **Patches 2+3 — the two broken Voxy mixins:** two candidate mechanisms, to be settled
  by a one-day prototype:
  - **(a) Error-handler suppression + reimplementation (primary).** Register an
    `IMixinErrorHandler` (from our mixin config plugin's `onLoad` via
    `Mixins.registerErrorHandlerClass`) that downgrades the apply failure of exactly
    those two mixin classes (matched by name — everything else still fails hard as
    designed). Our own mixins into beta.1's `RenderSectionManager` (ctor TAIL) and
    `DefaultChunkRenderer` (render HEAD-cancellable + before-`end` inject) with the
    *correct* 0.8 signatures then reimplement the three handler bodies — ~25 lines
    total, both sources in hand (j-shelfwood for the logic, voxy-forged for the 0.8
    signatures). Handler bodies call Voxy internals (`VoxyCommon.getInstance`,
    `IGetVoxyRenderSystem`, `VoxyClient.disableSodiumChunkRender`, `doRender` via
    invoker/accessor) — compileOnly, self-gated on Voxy's presence.
  - **(b) Transformation-service handler rewrite (fallback).** A mod-shipped
    `ITransformationService` (the Foxy-proven mechanism) rewrites the two Voxy handler
    methods before Mixin sees them: append the missing parameter to the descriptor
    (ignored in the body) so the capture matches 0.8. Keeps Voxy's logic verbatim;
    deeper black magic; use only if (a)'s error-handler semantics disappoint.
- **Self-gating:** activate only when a `me.cortex.voxy`-providing mod AND Sodium ≥0.8
  are both present. On Sodium 0.6.x, do nothing (the fork works natively). When dormant,
  contribute zero mixin targets (config-plugin `shouldApplyMixin` false), the
  make-it-compatible pattern.

**What users get:** drop `voxy-0.2.9-alpha.jar` (obtained from its own author) plus our
bridge into a Sodium-0.8 pack. Voxy runs; Sable keeps its Sodium; on LSS/VSS servers the
client gains a consumer and LOD sessions activate. Optionally add
make-it-compatible-voxy for the Sable-render/fog/clouds polish — it already assumes
exactly this stack.

**What users do NOT get:** voxy-forged's enhancements (Iris shadow-viewport fix, color/
curve fixes, border-ring LOD handoff). The bridged fork is 0.6-era Voxy behavior, just
running on 0.8.

## 5. Alternatives considered

- **Upstream the fix to j-shelfwood** (Option C, complementary, recommended regardless):
  the fork could be dual-Sodium natively — reflective `parseShader` dual-path plus two
  `require = 0` variants of each broken handler (0.6-shaped and 0.8-shaped; exactly one
  applies per Sodium). ~40 lines, no new mod, benefits everyone. Worth a PR/issue
  whether or not we build the bridge; the bridge serves the meanwhile and any future
  drift.
- **Point users at voxy-forged:** already exists and targets Sodium 0.8.12-beta.2, but
  it *redistributes* ARR code — precisely what this spike is chartered to avoid — and
  swaps the whole Voxy artifact rather than layering on what the user already has.
- **Downgrade the pack to the Sable-1.x era** — investigated and rejected 2026-08-15
  (sable 1.2.2 + aeronautics 1.2.1 accept Sodium 0.6.13, but it rolls the physics stack
  back two months on both sides of a live server, with world-downgrade risk).

## 6. Risks

1. **The error-handler mechanism is the prototype's first question.** If Mixin's
   required-injector failure can't be cleanly downgraded per-mixin, fall back to (b).
2. **Fork-version fragility.** We pin against the 0.2.9-alpha surface (class/method
   names of five Voxy classes). A future fork release that fixes Sodium 0.8 itself makes
   the bridge redundant (fine — self-gate on a version check); one that *renames*
   internals breaks our compileOnly surface (fail-safe: patches skip, fork crashes the
   way it does today, with a clear log line saying why).
3. **beta.1 ↔ beta.2 drift:** verified zero for every touchpoint in §3 (checked beta.1
   directly; voxy-forged targets beta.2).
4. **Runtime unknowns.** Descriptor compatibility ≠ behavioral compatibility; the
   0.6-era render glue on 0.8's renderer needs a real-pack shakedown (Create+ is the
   test bed; voxy-forged working on 0.8 is strong prior evidence the glue semantics
   hold).
5. **A future fork release could close the Sodium range** (declare `[0.6.13,0.7)`),
   reintroducing the load-gate problem; the Foxy locator pattern is the known escape
   hatch if that ever happens.

## 7. Effort

- Prototype (mechanism a vs b, parseShader patch, boot on Create+): **1–2 days**
- Hardening (self-gating, degrade paths, logging, version pins): **1 day**
- Live shakedown on Create+ vs bitvox + LSS session end-to-end: **0.5–1 day**

Total: **~3–4 days** for a shippable v1. Deliverable is small (~5 classes, 2 mixin
configs, a config plugin).

## 8. Evidence trail (this session's scratchpad)

- Fork Sodium surface: constant-pool scan of `voxy-0.2.9-alpha.jar` (scratchpad
  `voxyshim/`), descriptor-checked against `sodium-08-inner.jar` extracted from the
  pack's actual `sodium-neoforge-0.8.12-beta.1+mc1.21.1.jar`.
- Delta confirmation: `diff -r` of `j-shelfwood/voxy-neoforge` vs
  `falling-colud/voxy-forged` (three files differ in the sodium mixin dir +
  `ShaderLoader.java`; clones in scratchpad).
- Foxy mechanism: `Leclowndu93150/Foxy` clone, README + `loader/` sources.
- Sable floor provenance: `sable-neoforge-1.21.1-{1.0.6,1.1.3,1.2.2,2.0.0,2.0.3}` TOMLs
  (the Sodium `incompatible` range first appears in 2.0.0; 1.x declares no Sodium
  dependency and its mixins reference the 0.6-era API).
