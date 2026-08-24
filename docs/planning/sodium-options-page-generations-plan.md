# Sodium options page across Sodium generations — plan

Status: IMPLEMENTED on main (PR #236, 2026-08-23) — plan v1.1 (2-Fable plan review, §9)
built as §10, then a 1-Fable + 4-Opus implementation review folded (§11); Phase 3 (the
line ports) follows the §12 checklist.

## 0. Goal

1. The in-game LSS options page works on **every shipping line regardless of the
   Sodium generation the player runs** — the public config API (Sodium 0.8/0.9,
   MC 1.21.11+ and the 1.21.1 backport) AND the older internal options API
   (Sodium 0.6/0.7, MC ≤1.21.10 and the 1.21.1 Voxy-fork pairing).
2. Adding or changing an LSS option is a **one-file change that cherry-picks to
   every line unchanged**: the option list is data, the Sodium API bindings are
   thin renderers that feature work never touches.

Non-goals (recorded as §8 follow-ups): a Sodium-free vanilla settings screen; a
NeoForge page on the modern API; enum-typed options (none exist today).

## 1. Research facts this plan builds on (2026-08-23)

### 1.1 Which Sodium each line's players actually run

Modrinth version listing (queried 2026-08-23) + the jars in the gradle cache +
the Prism/README client stacks (memory `neoforge-client-stacks`, README §client
paths):

| Line | MC | Newest Sodium (type) | Options API | LSS page today |
|---|---|---|---|---|
| main | 26.2 | 0.9.1 (release), 0.9.2-alpha.4 | public `api.config` (0.8+) | LIVE (`LSSConfigMenu`, `sodium:config_api_user`) |
| 26.1 | 26.1.2 | 0.9.1 (release) | public | LIVE, source-identical to main at the v0.12.0 tags |
| 1.21.11 | 1.21.11 | 0.8.13 (release), 0.8.14-beta.2 | public | LIVE, source-identical to main at the v0.12.0 tags |
| 1.21.10 | 1.21.10 | **0.7.3 (release, FINAL — Nov 2025)** | internal `client.gui.options` only | **CUT** (mc1.21.10-line-notes.md Decision 2; ModMenu integration cut with it) |
| 1.21.1 | 1.21.1 | **BOTH**: 0.6.13 (release, Apr 2025 — the j-shelfwood NeoForge Voxy fork's pairing, the README's recommended NeoForge client path) AND 0.8.12 (release) / 0.8.13-beta.2 (Aug 2026 — the m3t4f1v3 `mc_1211-sodium0.8.12` Voxy branch, Create+/Sable packs) | internal (0.6) / public (0.8) | compiles against 0.8.13-beta.2 → page appears ONLY on 0.8.12+; on 0.6.13 it is silently absent (0.6 never queries the entrypoint; the ModMenu deep-link `NoClassDefFoundError`s into its catch → null) |
| 1.21.8 (frozen v0.6.1) | 1.21.8 | 0.7.3 (final) | internal | cut (the precedent) |
| 1.20.1 (frozen v0.5.0) | 1.20.1 | 0.5.13 (final; `me.jellysquid` package) | internal (static enabling only — §1.2) | never had one |

Two consequences the current state hides: the 1.21.10 line ships with no page
at all, and the 1.21.1 line's page is invisible on the client stack its own
README recommends. Both frozen lines are out of scope; the design below costs
them one package-prefix constant if either is revived.

Sodium's generation boundaries are FINAL for the legacy lines: 0.7.3 is the last
build for 1.21.6-1.21.10, 0.6.13 the last for 1.21.2-1.21.5 (1.21.1 got the 0.8
backport instead). The legacy target does not move any more — the drift risk is
historical, not forward.

### 1.2 The two API shapes (verified by `javap` on the real jars; review-corrected)

**Legacy — Sodium 0.6.13 ≡ 0.7.3** (0.7 adds a `setTooltip(Function<T,Component>)`
overload and `DynamicMaxSliderControl`, neither needed here; **0.5.13 differs**:
`me.jellysquid` package AND `setEnabled(boolean)` — static enabling, no
`BooleanSupplier` — so dependency greying would be static on a revived 1.20.1).
All in `<prefix>.client.gui`:

- `options.OptionImpl.createBuilder(Class<T>, OptionStorage<S>)` →
  `setName(Component)`, `setTooltip(Component)`,
  `setBinding(BiConsumer<S,T>, Function<S,T>)`,
  `setControl(Function<OptionImpl<S,T>, Control<T>>)`, `setImpact(OptionImpact)`,
  `setEnabled(BooleanSupplier)`, `setFlags(OptionFlag...)`, `build()`. All public.
- Controls: `control.TickBoxControl(Option<Boolean>)`,
  `control.SliderControl(Option<Integer>, min, max, interval, ControlValueFormatter)`
  (`ControlValueFormatter.format(int) → Component`, one abstract method),
  `control.CyclingControl` (enum; unused).
- `options.OptionGroup.createBuilder().add(Option).build()`;
  `new options.OptionPage(Component, ImmutableList<OptionGroup>)` (guava).
- `options.storage.OptionStorage<S> { S getData(); void save(); }`. The
  Apply-button model, as the bytecode actually has it: values are STAGED in
  `OptionImpl.modifiedValue` (`getValue()` returns the staged value,
  `isAvailable()` re-evaluates the `BooleanSupplier` each render);
  `OptionImpl.applyChanges()` ONLY writes the binding — it is
  `SodiumOptionsGUI.applyChanges()` that collects every changed option's storage
  into a `HashSet` and calls `save()` once per distinct storage. So a storage
  implemented via `java.lang.reflect.Proxy` MUST answer `hashCode`/`equals`/
  `toString` (review A-2), and a session touching both LSS pages runs
  `cfg.save()` twice (harmless) and the far-player push once.
- `SodiumOptionsGUI`: private ctor, `public static Screen createScreen(Screen)`
  (returns `ConfigCorruptedScreen` instead when Sodium's own config is
  read-only), `private final List<OptionPage> pages` (a mutable `ArrayList`
  filled in the ctor), `private OptionPage currentPage`, `public setPage(OptionPage)`
  = `currentPage = page; rebuildGUI()` — and `rebuildGUI` touches `Screen.font`,
  which MC assigns only in `init()`, so **`setPage` before the screen is shown
  NPEs** (review A-3); `rebuildGUI` defaults `currentPage` to `pages.get(0)` only
  when it is null.
- The ecosystem's injection point is the constructor: Sodium Extra 0.6.0
  `MixinSodiumOptionsGUI` = `@Shadow pages` + `@Inject(method="<init>",
  at=@At("RETURN"))` → `pages.add(...)`.

**Modern — Sodium 0.8.12-alpha.3 ≡ 0.8.13-beta.2 ≡ 0.9.1-beta.3**: the 26
classes under `net.caffeinemc.mods.sodium.api.config` are the SAME SET in all
three jars, and our `LSSConfigMenu` diff main↔26.1↔1.21.11 is empty **at the
v0.12.0 tags** (main↔1.21.1 is the `Identifier`→`ResourceLocation` rename only).
Registration: the `sodium:config_api_user` Fabric entrypoint
(`ConfigEntryPoint.registerConfigLate(ConfigBuilder)`) or the
`@ConfigEntryPointForge("<modid>")` annotation on NeoForge. The builders carry
`setEnabledProvider(Function<ConfigState,Boolean>, Identifier...)`,
`setStorageHandler(StorageEventHandler)`, `setDefaultValue`, `setRange`,
`setValueFormatter`, `setImpact`, `setBinding(Consumer, Supplier)`.

The ModMenu deep-link (`LSSModMenuIntegration`) binds INTERNAL 0.8 classes
(`client.config.ConfigManager`, `client.gui.VideoSettingsScreen.createScreen(Screen, OptionPage)`)
— present 0.8.12→0.9.1, absent ≤0.7. `SodiumOptionsGUI` does NOT exist in
0.8.12+ (verified) — the two generations are mutually exclusive by class
presence, which is what makes a runtime probe unambiguous.

**Mixin facts (sponge-mixin 0.8.7, both loaders; review A-1/B-2/A-6):** a
string-targeted mixin whose target class is absent is a WARN ("@Mixin target
was not found"), never a crash, in any config; a `@Pseudo` mixin declares the
target may be absent and is skipped silently. `MixinInfo` checks
`isClassLoaded(target)` while reading declared targets — which happens at config
PREPARE (the first transform after the DEFAULT phase, BEFORE any mod entrypoint):
**a `Class.forName` of the target class inside that window** (a config plugin's
`onLoad`/`shouldApplyMixin` — the v1.0 design) defines the class through the
transformer BEFORE our hook is attached — the hook then never applies, no
crash, no page, invisible to stub tests. A later load (entrypoint/click time) is
transformed with the hook; a `.class` RESOURCE lookup is safe everywhere (defines
nothing, runs no static init, exempt from JPMS encapsulation) — implementation
review A-5 corrected the v1.1 "entrypoint-time" wording.

### 1.3 Repo facts that shape the design

- `XplatLoaderPurityTest` bars `net\.caffeinemc` from xplat as a whole-source
  regex — string literals AND comments count. Anything naming a Sodium class
  lives in the per-loader trees.
- `release_check.py` `FABRIC_ONLY_CLASS_PREFIXES` lists `LSSConfigMenu`,
  `LSSModMenuIntegration`, `RateSliderStops`; the cross-loader check is
  PRESENCE (every shared class in the fabric jar must exist in the neoforge jar);
  the VSS lang-value rebrand pin exists for the FABRIC pair only.
- The NeoForge jar ships NO lang file (`processResources` copies only
  `icon.png`) and the NeoForge client has no options surface at all today
  ("the config key is the whole control surface"); its `vssJar` rewrites only
  the TOML + brand properties.
- Sodium is NOT on the Tier 1 test classpath on any line (plain `compileOnly`
  on main; loom's `modCompileOnly` feeds the compile collector only), so
  real-package-name stubs under `fabric/src/test/java/net/caffeinemc/…` load at
  test time — and, conversely, the test output dir precedes every jar on the
  classpath, so a real Sodium jar added to the test runtime would be SHADOWED
  by those stubs (review A-4/B-3: the v1.0 "golden arm" was vacuous).
- `LSSConfigMenu` has 12 feature commits; each new option is ~15 lines of
  builder calls that today would have to be re-expressed per API generation.
  Its defaults (`setDefaultValue(0)`, `(true)`) are hand-duplicated from
  `LSSClientConfig` field initializers with no pin. `new LSSClientConfig()` is
  IO-free (Tier 1 already builds them bare); `LSSClientConfig.CONFIG` resolves
  under fabric-loader-junit via the ServiceLoader fallback.
- `RateSliderStops` is package-private with package-private statics, read
  directly by `ConfigValidationTest` (same package).
- `ToolchainContractTest.mixinCompatibilityLevelMatchesTheCompiledTarget`
  iterates a hard-coded list of the two mixin configs; the configs' `compatibilityLevel`
  is per-line data (JAVA_25 on 26.x, JAVA_21 on 1.21.x).
- `per-version-surfaces.md` has NO row for the Sodium page (table ends at row
  17) — the 1.21.10 cut was decided in line notes, not derived at port time.
- The `support/mc*` branch HEADS predate the Xaero bridge (no
  `enableXaeroMapBridge` field there); the shipped v0.12.0 line state is the
  `v0.12.0+mc<line>` tags = the local `port/xaero-<line>` heads (review B-1).
- House pattern for optional-mod bridges: zero compile dep, `MethodHandle`
  resolution once per JVM, every failure shape → degrade + once-bounded WARN
  (`VoxyCompat`, `MoonriseReadCompat`, `XaeroMapCompat`), unit-tested against
  real-package-name stubs with an injectable resolver.

## 2. Design decisions (v1.1)

**D1 — One catalog, MC-free and Sodium-free, in xplat.**
`dev.vox.lss.config.menu.ClientOptionCatalog` describes pages → groups →
options as records. An option carries: stable id (`lss:receive_server_lods` —
the modern API keys per-option state by it, so ids never change), kind
(`BoolSpec`, `IntSpec(min,max,step)` — the curved rate slider is an `IntSpec`
over the STOP INDEX domain whose binding maps index↔rate through
`RateSliderStops`), name key, tooltip (`Tooltip.fixed(key)` or
`Tooltip.conditional(Condition, whenTrueKey, whenFalseKey)` — `Condition` is
an ENUM over `MenuContext` so every key is enumerable for the lang pin), impact,
**default read from a fresh `new LSSClientConfig()`** (kills the hand-duplicated
defaults), value label (`IntFunction<Label>`, `Label = key | literal`), binding
**over the config INSTANCE** (`BiConsumer<LSSClientConfig,V>` /
`Function<LSSClientConfig,V>` — the legacy API passes the storage's data object
and the tests bind fresh instances; review A-7), `enabledBy` (another option's
id, same page), `saveHook` (`SAVE` | `SAVE_AND_PUSH_FAR_PLAYER_PREFS`, each a
method over a config instance), `visibility` (`ALWAYS` | `SEEU_ONLY`).
`MenuContext` = three booleans (`governorOn`, `xaeroPresent`, `seeuPresent`)
with a `current()` factory over `LSSClientConfig.CONFIG`,
`LoaderServices.isModLoaded("xaeroworldmap")` and
`FarPlayerClientSupport.isSeeuPresent()` — all legal xplat inputs.
`RateSliderStops` moves into xplat beside it and becomes PUBLIC (review A-10/B-7).
That is exactly the feature set the two live pages use today — nothing
speculative (no enum kind until an enum option exists).

**D2 — Renderers are thin walkers, one per generation, selected at RUNTIME by a
RESOURCE probe.** `SodiumGeneration.detect()` (per-loader tree, memoized, never
throws) asks the class loader for `.class` RESOURCES — never `Class.forName`
(§1.2 mixin facts): `…/api/config/ConfigEntryPoint.class` present → `MODERN`;
`…/client/gui/SodiumOptionsGUI.class` present under `net.caffeinemc` (or
`me.jellysquid`, a second prefix constant) → `LEGACY`; else `NONE`. The
contract test scans the probe source for `Class.forName` and reds on a hit. No
per-line flavor point: the probe, the catalog, the legacy renderer and the
ModMenu switch are byte-identical on every line. The only per-line facts are
(a) which Sodium artifact the line's `modCompileOnly` pins (already per-line
build.gradle data) and (b) whether the modern renderer FILE is present (D3).

**D3 — The modern renderer = today's `LSSConfigMenu`, gutted to a ~60-line
catalog walker.** Same FQN and entrypoint (the lines' `fabric.mod.json` already
reference it). It stays `compileOnly` against the line's 0.8/0.9 artifact and
is PRESENT only on lines that have one (main, 26.1, 1.21.11, 1.21.1). On
0.7-only lines (1.21.10) the file and the `sodium:config_api_user` entrypoint
are absent — a whole-file presence decision like today's cut, but now free:
feature work touches the catalog, never the walker. Pinned: entrypoint ⇔ file
(§4). (Recorded option, not recommended: keep the file on 1.21.10 by compiling
against the 1.21.11 0.8 artifact through loom's intermediary remap; take it
only if a 1.21.10 backport is ever announced.)

**D4 — The legacy renderer is REFLECTIVE, zero compile dep.**
`LegacySodiumPage` (per-loader twins, byte-identical — the ScopedCarrier
pattern; the `net.caffeinemc` strings are barred from xplat) resolves the public
internal classes of §1.2 by name under the probed prefix with `MethodHandle`s
at FIRST BUILD (inside the screen constructor — the target is loaded by then, so
`Class.forName(name, false, loader)` is safe there), and walks the catalog into
`OptionPage`s. `OptionStorage` and `ControlValueFormatter` are
`java.lang.reflect.Proxy` instances whose handlers answer `hashCode` (identity),
`equals` (identity) and `toString` besides the interface methods (§1.2 — the
screen puts storages in a `HashSet`). Two storages per build: plain
`cfg.save()`, and `cfg.save()` + the far-player prefs push. `enabledBy` resolves
LAZILY inside the `BooleanSupplier` through a per-build `Map<id, OptionImpl>`
(order-independent; review A-8), reading the dependency's staged `getValue()`.
The resolved member table is a DATA constant (`Surface`: class name, member
kind, name, arity) that both the builder and the §4 resolves-test consume, so
the test cannot drift from the code. Every failure shape → no page + one WARN
naming the missing member; the Sodium screen must always open. Why reflective
rather than `modCompileOnly` against 0.6/0.7: the 1.21.1 line must carry BOTH
generations and Gradle cannot put two versions of one artifact on a compile
classpath — the reflective file compiles everywhere, including main where no
legacy Sodium exists. The touched surface is all `public` and the Sodium
NeoForge jar is an automatic module, so no `setAccessible` is needed on the
build path — JPMS-safe.

**D5 — The legacy injection point is a `@Pseudo` string-targeted mixin, no
plugin.** `dev.vox.lss.mixin.sodium.SodiumLegacyOptionsHook`:
`@Pseudo @Mixin(targets = "net.caffeinemc.mods.sodium.client.gui.SodiumOptionsGUI", remap = false)`,
`@Shadow @Final private List<Object> pages` (the erased descriptor matches),
`@Inject(method = "<init>", at = @At("RETURN"))` with a `(CallbackInfo ci)`
handler (args omitted — the hook stays MC-type-free) →
`pages.addAll(LegacySodiumPage.build())`, and the mixin implements the xplat
handle `LegacyOptionsScreenHandle` (`lss$injectedPages()` over a `@Unique`
field) so the deep-link (D6) can find the pages it added. It lives in a NEW
non-required config `lss-sodium-legacy.mixins.json` (both loaders; fabric
`mixins` list + toml `[[mixins]]`; `compatibilityLevel` is per-line data like
the other two configs and joins `ToolchainContractTest`'s list — review B-5).
`@Pseudo` is Mixin's own answer to a possibly-absent target (silent skip — no
WARN on 0.8+ or Sodium-less clients), which removes the config plugin the v1.0
plan had — and with it the plugin-time `Class.forName` hazard (review A-1/B-2).
Why a mixin and not `ScreenEvents.BEFORE_INIT` + reflection into `pages`:
(a) Reese's Sodium Options swaps the screen but takes its page list from the
constructed `SodiumOptionsGUI` — `<init>` is the one point every consumer sees,
and it is what Sodium Extra/Iris use; (b) loader-neutral (NeoForge has no
ScreenEvents twin); (c) no reflection into a private final field. The hook
injects NO MC method (an `init`/`render` target would need refmap remapping the
string-target mixin cannot get on the loom-remap lines).

**D6 — ModMenu becomes a generation switch, fully reflective.**
`LSSModMenuIntegration` (fabric): `MODERN` → the existing deep-link, made
reflective so it compiles on 0.7-only lines and fails soft on internal-API
drift; `LEGACY` → `SodiumOptionsGUI.createScreen(parent)` and, when the result
is our handle (not `ConfigCorruptedScreen`), a PRE-INIT selection of the first
injected page by setting the private non-final `currentPage` field reflectively
(`setAccessible` — legal on Fabric and on NeoForge's automatic module; wrapped
fail-soft: on any failure the screen opens on Sodium's default tab and the LSS
tab is one click away). `rebuildGUI` keeps a non-null `currentPage`, so
`init()` renders our tab first with no pre-init work — `setPage` is NEVER
called before init (review A-3/B-9). `NONE` → null (unchanged behavior).
Compile dep: `modmenu` only — RE-ADDED on 1.21.10 (ModMenu 16.0.1 exists
there). NeoForge's `IConfigScreenFactory` extension point is a new surface
(today none) — Phase 4, not v1.

**D7 — Branding on the legacy page.** Legacy tabs sit beside Sodium's
General/Quality/Performance/Advanced with no per-mod grouping, so the page
names carry the brand: the first page is `Brand.shortName()` ("LSS"/"VSS") and
later pages `Brand.shortName() + " " + title` ("LSS Far Players"). Modern keeps
the nested "General"/"Far Players" under the mod entry (icon + version, as
today). The catalog stores the bare title key; each renderer decides. Lang keys
are unchanged.

**D8 — Semantics parity, both directions, pinned.**
- Dependency enabling: legacy `setEnabled(BooleanSupplier)` reads the
  dependency option's STAGED `getValue()` (the modern `readBooleanOption` on
  pending state) — an unticked "Receive Server LODs" greys the sliders before
  Apply on both.
- Save hooks: the two storages of D4 map 1:1 to the modern per-option
  `StorageEventHandler`s — the E2 review's M2 ("a mid-session Share My Position
  flip must not wait for a rejoin") holds on legacy too, and the catalog test
  pins that every far-player option uses the push hook.
- The curved rate slider is the same index-valued option + formatter on both
  (legacy `SliderControl(option, 0, STOPS.length-1, 1, fmt)`).
- Conditional tooltips resolve at page build on both (as today).

**D9 — Fail-safe doctrine.** None of this may crash a client: the probe never
throws (and never defines a class); a legacy build failure skips the page with
one WARN; an absent mixin target is a silent `@Pseudo` skip; the ModMenu
factory returns null on any `Throwable` (as today). Config-file-only users see
no behavior change on any line.

## 3. Components

xplat (line-invariant, no MC/Sodium types, no Sodium FQNs even in comments):
- `config/menu/ClientOptionCatalog.java` — `pages()`; the records `PageSpec`,
  `GroupSpec`, `OptionSpec` (sealed: `BoolSpec`, `IntSpec`), `Tooltip` +
  `Condition`, `Label`, `Impact`, `SaveHook`, `Visibility`, `MenuContext`.
- `config/menu/RateSliderStops.java` — moved from fabric, made public.
- `config/menu/LegacyOptionsScreenHandle.java` — the one-method handle the
  hook implements (`List<Object> lss$injectedPages()`).

fabric + neoforge SAME-FQN TWINS (byte-identical; twin-identity pinned):
- `config/menu/SodiumGeneration.java` — the resource probe
  (`MODERN`/`LEGACY`/`NONE`, `legacyPrefix()`; injectable `Predicate<String>`
  resource-presence seam for the test).
- `config/menu/LegacySodiumPage.java` — the reflective builder + its `Surface`
  table (D4/D7/D8).
- `mixin/sodium/SodiumLegacyOptionsHook.java` (D5).
- `lss-sodium-legacy.mixins.json` (`required: false`, `package`
  `dev.vox.lss.mixin.sodium`, `client: ["SodiumLegacyOptionsHook"]`,
  per-line `compatibilityLevel`).

fabric only:
- `config/LSSConfigMenu.java` — the modern walker (D3); present iff the line
  has a 0.8+ artifact.
- `config/LSSModMenuIntegration.java` — the generation switch (D6).
- `fabric.mod.json` — `mixins` gains the legacy config; `sodium:config_api_user`
  present iff `LSSConfigMenu` exists; `modmenu` always present.

neoforge only:
- `neoforge.mods.toml` — second `[[mixins]]` row.
- `neoforge/build.gradle` — `processResources` copies `assets/lss/lang/` from
  the fabric tree beside the icon (the legacy page needs the keys); `vssJar`
  gains the fabric jar's lang-VALUE rebrand loop (review B-6 — otherwise the
  VSS NeoForge page reads "LSS" mid-sentence).
- `LSSNeoClientBootstrap` — no change in v1 (the hook is a mixin; the probe is
  lazy). Phase 4 adds the `IConfigScreenFactory` registration.

Docs:
- `per-version-surfaces.md` — NEW row 18 "Sodium options-page generation":
  what to check at a port = which Sodium generations the line's players run
  (Modrinth listing + the README client stacks) → modern-walker presence +
  entrypoint + `modCompileOnly` pin + the legacy config's `compatibilityLevel`;
  pins = `ClientMenuEntrypointContractTest`, `SodiumLegacyHookContractTest`,
  the third config in `ToolchainContractTest`, `release_check`'s NeoForge lang
  row. Row 10's "both mixin configs" → "all three".
- `pre-authorized-cuts.md` — rewrite the "Sodium config menu deleted" row to
  "modern (0.8+) walker absent on 0.7-only lines; the legacy page still ships".
- `mc1.21.10-line-notes.md` (lives on the support branch only) — Decision 2
  amended there: the cut is reversed by design change, not by a Sodium
  backport (that amendment IS the dated §6.2 record; main introduces no cut, so
  no main-side decisions entry). README line 21 + release notes for the
  1.21.10 line name the restored page.
- CLAUDE.md / README wording: Fabric "both Sodium generations"; NeoForge
  "legacy (0.6.13 fork path) only — the 0.8.12/Connector path stays
  config-key-only until Phase 4" (review B-10). The 1.21.1 line's
  `suggests.sodium` is a hand literal (no template/test there): edit it to `*`.

## 4. Tests (Tier 1 unless noted; review-revised)

- `ClientOptionCatalogTest` (fabric T1, xplat code): ids unique + `lss:`
  namespace; EVERY name/tooltip/label key present in `en_us.json` (tooltip keys
  enumerated through `Tooltip.keys()`, labels sampled over the slider domain —
  the catalog is the only key source, so the lang pin becomes complete);
  defaults == a fresh `LSSClientConfig`'s field values (through each getter);
  binding round-trip per option on a fresh instance; `enabledBy` resolves to a
  `BoolSpec` on the same page; stops monotonic and inside the `validate()`
  clamps; all far-player options carry the push hook; `visibility` hides
  exactly the SeeU option.
- `SodiumGenerationTest`: the probe with an injected resource-presence
  predicate → `MODERN`/`LEGACY`(both prefixes)/`NONE`, MODERN wins when both
  answer (cannot happen live — pinned anyway), a throwing predicate → `NONE`;
  source scan: no `Class.forName` in `SodiumGeneration`.
- `LegacySodiumPageTest`: stubs of the internal classes in
  `fabric/src/test/java/net/caffeinemc/mods/sodium/client/gui/...` (the
  Voxy/Moonrise/Xaero precedent) → build against a fresh config with the
  storages' save calls captured; assert page/group/option count and order equal
  the catalog, names/tooltips resolved (conditional tooltips flip with
  `MenuContext`), tickbox vs slider by kind, the enabled-supplier follows the
  dependency's staged value, the SCREEN's apply contract (apply each changed
  option, collect storages into a `HashSet`, `save()` once each — the proxy
  survives `HashSet.add`, the push hook fires exactly for the far-player page),
  a throwing stub → empty list + one warn, brand prefix on page names.
- `SodiumLegacySurfaceResolvesTest` (the review-scoped "golden arm", RUNS ON
  MAIN): a plain Gradle `Configuration` (`sodiumLegacyGolden`, NOT loom-remapped,
  never on the test classpath) resolves the Mojang-mapped NeoForge artifact
  named by gradle.properties `sodium_legacy_golden` (main:
  `mc1.21.1-0.6.13-neoforge` — the legacy surface is generation-invariant;
  1.21.10 may point at `mc1.21.10-0.7.3-neoforge`); the test opens the jar as
  a zip, reads each `Surface` class with ASM and asserts every bound member
  exists by NAME + ARITY (descriptor-agnostic: MC names differ per line). Skips
  with an assumption when the property/jar is absent (offline builds). This is
  the automated proof that the reflective table matches real bytecode; the live
  gate stays the only end-to-end proof.
- `SodiumLegacyHookContractTest`: source regex (the `MoveTraceHookContractTest`
  idiom): `@Pseudo`, the string target equals `SodiumGeneration`'s prefix
  constant + `.client.gui.SodiumOptionsGUI`, `remap = false`, exactly one
  `@Inject` at `<init>` RETURN, the body delegates to `LegacySodiumPage`, no MC
  method targets; the config is `required:false` and lists the hook;
  `fabric.mod.json` `mixins` and the NeoForge toml both declare it.
- `ClientMenuEntrypointContractTest` (NEW, line-neutral — never edit
  `FabricModJsonContractTest`, whose per-line flavors conflict on cherry-pick;
  review B-8): `sodium:config_api_user` ⇔ `LSSConfigMenu.java` exists;
  `modmenu` entrypoint present.
- `ToolchainContractTest`: the third config joins the compatibilityLevel list.
- `NeoForgeModuleContractTest`: twin identity for `SodiumGeneration`,
  `LegacySodiumPage`, `SodiumLegacyOptionsHook` and the legacy mixins config
  (the ScopedCarrier pin generalized to a list); the new config's listed class
  exists under `mixin/sodium`; the toml declares the config.
- `release_check.py`: `RateSliderStops` prefix row retired; NeoForge required
  entries gain `lss-sodium-legacy.mixins.json` + `assets/lss/lang/en_us.json`;
  the VSS lang-value pin generalized to the NeoForge pair; selftest fixtures
  updated.
- Live gates (manual checklist rows, per line): `lss-test-neo-1.21.1`
  (fork + 0.6.13 NeoForge): LSS tabs present, Apply writes the file, a
  Share-My-Position flip pushes prefs mid-session; a 1.21.10 Fabric instance
  with Sodium 0.7.3 (+ Reese's if installed — §6); the 1.21.1 Create+ instance
  (0.8.12): modern page unchanged; ModMenu Configure on each; main 26.2:
  unchanged, no mixin log noise.

## 5. Per-line rollout (what each port commit touches)

Port BASE per line = the `v0.12.0+mc<line>` tag (= the local `port/xaero-<line>`
head), NOT the `support/mc*` branch head — the support heads predate the Xaero
bridge and lack `enableXaeroMapBridge`, which the catalog binds (a pick onto
them fails to COMPILE, not merge). Phase-3 pre-flight per line: fast-forward
`support/<line>` to its tag; `git grep enableXaeroMapBridge -- xplat` must hit.

| Line | Port work |
|---|---|
| main | Phase 1 + 2 (everything; legacy stack dormant — no 26.x legacy Sodium exists) |
| 26.1, 1.21.11 | cherry-pick onto the tag base; expected zero conflicts (the only flavored file becomes a walker); `compatibilityLevel` JAVA_25 (26.1) / JAVA_21 (1.21.11) in the new config |
| 1.21.1 | cherry-pick + the existing `Identifier`→`ResourceLocation` rename in the walker; JAVA_21 config; the legacy stack lights up for 0.6.13 on BOTH loaders; hand-edit `suggests.sodium` to `*`; `sodium_legacy_golden` stays 0.6.13 |
| 1.21.10 | cherry-pick; `LSSConfigMenu` stays absent; restore the `modmenu` entrypoint + `modCompileOnly modmenu 16.0.1`; JAVA_21 config; amend Decision 2 + README + release notes; `sodium_legacy_golden=mc1.21.10-0.7.3-neoforge` |
| 1.21.8, 1.20.1 | frozen — untouched |

## 6. Risks / accepted

- **Internal-API dependence on the legacy lines.** Accepted: the shape held
  0.6→0.7 and those Sodium lines are FINAL (§1.1), so the target is frozen; the
  reflective build fails soft with a named WARN, and the resolves-test reds at
  build time if the pinned artifact ever changes.
- **Reese's Sodium Options on legacy** — verified against RSO 1.8.0 bytecode
  (implementation review): its `postInit` swaps the screen for its own, built
  from the constructed `SodiumOptionsGUI`'s `pages` — our tabs carry over; only
  the ModMenu deep-link's pre-init page selection is dropped (RSO opens on its
  own tab). Page yes, deep-link no; nothing to gate.
- **The modern deep-link still binds internal 0.8 classes** — unchanged risk,
  now reflective and fail-soft instead of a compile-time bind.
- **Dual generation on 1.21.1 cannot double-register**: with 0.8.12 present the
  probe answers `MODERN` and the `@Pseudo` hook finds no target; with 0.6.13
  present the entrypoint is never queried. Pinned by `SodiumGenerationTest`'s
  mutual exclusion.
- **The deep-link's `currentPage` reflection** is the one `setAccessible` in
  the design, confined to the ModMenu path and fail-soft to Sodium's default
  tab.
- **The NeoForge jar now carries menu code it may never render** (`NONE`
  generation on servers) — bytes only, no runtime cost.

## 7. Execution order / effort (review-revised)

1. **Phase 1 (main, PR 1, no behavior change):** catalog + `RateSliderStops`
   move (public) + `LSSConfigMenu` → walker + `ClientOptionCatalogTest` +
   `ClientMenuEntrypointContractTest`. Gate: T1 and a 26.2 eyeball that the
   rendered page is unchanged. ~0.5 d.
2. **Phase 2a (main, same PR or PR 2, no boot surface):** probe, reflective
   builder + `Surface`, handle interface, stub tests, the resolves-test +
   `sodiumLegacyGolden` configuration. Gate: T1.
   **Phase 2b:** the hook + config on both loaders' boot paths, the ModMenu
   switch, NeoForge lang copy + vssJar rebrand loop, `release_check` rows,
   contract tests, docs rows. Gate: T1 + `release_check --selftest` + a 26.2
   client boot with no mixin log noise. ~2 d for 2a+2b.
3. **Phase 3 (lines):** 1.21.10 FIRST (the pure-legacy line is the real proof),
   then 1.21.1 (dual, both loaders, the NeoForge rig, the support-branch
   fast-forward), then 26.1/1.21.11 (mechanical). ~0.5 d per mechanical line,
   ~1 d for 1.21.1.
4. **Phase 4 (optional, separate plan):** NeoForge modern renderer
   (`@ConfigEntryPointForge` + `compileOnly sodium-neoforge`) and the
   `IConfigScreenFactory` mod-list button.

Total ≈ 4-5 dev-days for the five shipping lines.

## 8. Follow-ups (recorded, out of scope)

- A Sodium-free vanilla settings screen (would be a version-volatile file —
  MC's `OptionsSubScreen` changed 1.21.2+; the JSON files remain the
  Sodium-less surface for now).
- `ENUM` option kind when the first enum option lands (legacy `CyclingControl`
  / modern `createEnumOption` both exist).
- If 1.20.1 is ever revived: the `me.jellysquid` prefix constant, a second
  string-targeted hook class, and `setEnabled(boolean)` (static greying) in
  the builder's 0.5 arm.

## 9. Review record (2026-08-23, 2-Fable, plan v1.0 → v1.1)

| # | Sev | Finding | Fold |
|---|---|---|---|
| A-1 / B-2 | MAJOR | A `Class.forName` presence probe (or a config plugin doing one) defines the mixin target before the hook attaches — silent no-page | D2: resource probe, never `Class.forName`; D5: `@Pseudo`, no plugin; contract test scans for `Class.forName` |
| A-2 | MAJOR | `Proxy` storage NPEs in the screen's `HashSet` on Apply; `OptionImpl.applyChanges` never calls `save()` (the screen does) | §1.2 corrected; D4 handlers answer Object methods; the stub test models the screen's apply contract |
| A-3 / B-9 | MAJOR | `setPage` before `init()` NPEs (`font`), `createScreen` may return `ConfigCorruptedScreen`, and the deep-link had no page handle | D6: handle interface from the hook + fail-soft pre-init `currentPage` set; `setPage` never pre-init |
| A-4 / B-3 | MAJOR | The real-jar golden arm was vacuous (test output shadows the jar; `modTestRuntimeOnly` needs `createRemapConfigurations` and would register Sodium as a mod) | §4: ASM name+arity resolves-test over a plain non-loom configuration, driven by the builder's `Surface` table; runs on main |
| B-1 | MAJOR | Support heads lack `enableXaeroMapBridge`; "zero conflicts" holds at the v0.12.0 tags only | §5: port base = the tags; pre-flight fast-forward |
| A-5 | MINOR | 0.5.13 `setEnabled(boolean)` — not builder-identical | §1.1/§1.2/§8 |
| A-6 | MINOR | absent-target mechanics mis-stated | §1.2 mixin facts |
| A-7 | MINOR | bindings should take the config instance | D1 |
| A-8 | MINOR | legacy `enabledBy` resolution order | D4 lazy map |
| A-9 | NIT | plugin placement / handler signature | moot (no plugin); `(CallbackInfo ci)` kept |
| A-10 / B-7 | MINOR | `RateSliderStops` visibility; tooltip-key enumeration | D1 public + `Tooltip.keys()` |
| B-4 | MINOR | 1.21.1 `suggests.sodium` is a hand literal, untested | §3 docs / §5 |
| B-5 | MINOR | third mixin config: `compatibilityLevel` per line; toolchain + NeoForge pins | D5 / §4 |
| B-6 | MINOR | NeoForge VSS lang rebrand + release_check lang row | §3 / §4 |
| B-8 | MINOR | do not edit `FabricModJsonContractTest` | new `ClientMenuEntrypointContractTest` |
| B-10 | MINOR | NeoForge is legacy-only in v1 | §3 wording |
| B-11 / B-12 / B-13 | NIT | surfaces row 18; Decision 2 lives on the support branch; no Sodium FQNs in xplat comments; Phase 2 split; effort | §3 / §7 |

## 10. As-built notes (2026-08-23, Phases 1 + 2a + 2b on main, branch `feat/sodium-options-generations`)

Built as planned in v1.1 with these recorded specifics:

- **Catalog shape**: `OptionSpec` is a sealed interface with `BoolSpec`/`IntSpec`
  records + small fluent builders (a 10-arg record ctor was unreadable); tooltips use
  the enumerable `Tooltip.Condition` (`ALWAYS`, `GOVERNOR_ON`, `XAERO_PRESENT`,
  `SEEU_ABSENT`); `Impact` is NULLABLE (the LOD-distance slider ships with no impact
  line — preserved); `Visibility` = `ALWAYS` | `SEEU_ONLY`. `MenuContext.current()`
  contains every lookup (loader-less unit contexts read as "absent").
- **Legacy builder**: resolution is name + arity over `getMethods()` with a
  parameter-type PREFERENCE for overloads (0.7's two `setTooltip`s); a 0.5-style
  `setEnabled(boolean)` is detected (`Handles.enabledIsStatic`) and rendered as
  build-time static greying — so a revived 1.20.1 needs only the `me.jellysquid`
  prefix, which the probe already carries. Storage proxies are one per `SaveHook`
  (two per build), formatter proxies one per slider; both answer the Object methods.
  A build failure is logged once and does NOT latch (a later screen retries); a
  RESOLVE failure latches for the session.
- **Deep-link** (D6): the hook implements `LegacyOptionsScreenHandle` from xplat
  (`lss$injectedPages()` over a `@Unique` field, null-safe because Mixin does not
  merge instance initializers); the ModMenu switch sets `currentPage` reflectively
  pre-init and falls back to Sodium's default tab on any failure.
- **Golden arm** (§4): `SodiumLegacySurfaceResolvesTest` RUNS ON MAIN against the
  real `mc1.21.1-0.6.13-neoforge` jar (the plain `sodiumLegacyGolden` configuration,
  jar path passed as `lss.sodiumLegacyGoldenJar` at test EXECUTION so offline boxes
  skip instead of failing) — every `SURFACE` row resolved on the first run.
- **Tests**: the stub package carries a resource-only `SodiumOptionsGUI` so the probe
  answers LEGACY under fabric-loader-junit and `LegacySodiumPage.build()`'s
  PRODUCTION path is exercised (incl. the throwing-Sodium degrade). Source-regex
  pins strip comments first (the javadoc legitimately names `Class.forName` to
  explain the rule).
- **release_check**: the lang-value rebrand pin is now `_check_vss_lang_rebrand`,
  shared by the fabric AND neoforge VSS pairs; the NeoForge jar requires
  `lss-sodium-legacy.mixins.json` + `assets/lss/lang/en_us.json`; selftest 90 cases.
- **Gate at build**: `:fabric:build -x runClientGameTest` (T1 1963 + T2),
  `:neoforge:build` (14 contract tests, shadowJar + vssJar), `:paper:test` (430),
  `release_check.py` OK on all six jars. The 26.2 page eyeball + the per-line live
  gates (§4) are OWED — main's own runtime is MODERN, so the legacy stack is dormant
  there by construction.

## 11. Implementation review record (2026-08-23, 1-Fable + 4-Opus over PR #236)

| # | Sev | Finding | Fold |
|---|---|---|---|
| O1-1 / O3-1 | MAJOR | `.gitignore`'s bare `net/` silently excluded the 12 `net/caffeinemc` test stubs — a clean checkout could not compile the tests | `git add -f` + the `!**/src/**/net/` carve-out (the `libs/` precedent) |
| O3-2 | MAJOR | `neoforge-support-plan.md` §5.4 (normative) still listed the Sodium screen as a NeoForge non-feature | §5.4 amended |
| O2-1 | MAJOR | the golden arm self-skipped green on a mistyped/unresolvable coordinate | `CI=true` → FAIL; lenient artifact views (no Project capture); `inputs.files` |
| O2-2 / F-4 | MAJOR | the 0.7 `setTooltip` overload preference had no coverage | stub declares the `Function` overload FIRST (throws if bound); `method()` unit pin; the resolves-test asserts a non-JDK `setTooltip/1` exists |
| O2-3 | MAJOR | stub `OptionGroup.Builder.build()` lacked Sodium's `Validate.notEmpty` — the skip-empty-group guard was unpinned | stub throws like the real one |
| O1-2 / O4-1 | MINOR | the modern walker built one `StorageEventHandler` per option → one `cfg.save()` per changed option | two shared handlers (`EnumMap` over `SaveHook`), pinned by `LSSConfigMenuTest` |
| O1-3 / F-2 | MINOR | the shipping 0.8+ walker and the modern deep-link had no tests | `LSSConfigMenuTest` over recording `api/config` stubs; `SodiumConfigScreens.MODERN_SURFACE` + the modern arm of the resolves-test against the line's own artifact |
| O1-4 | MINOR | the NeoForge legacy page advertised three far-player options that are no-ops there | `FarPlayerRenderer.RENDER_AVAILABLE` twins → `MenuContext.farPlayerRenderAvailable` → `Visibility.RENDER_AVAILABLE` on show/tags/render-limit (Share My Position never hidden) |
| O2-4 / F-1 / O3-5 | MINOR | the hook's `pages`/ctor and the deep-link's `createScreen`/`currentPage` were outside the golden arm; the ENUM row checked only `ACC_ENUM` | `FIELD` kind + the screen's rows in `SURFACE`; the ENUM row checks `Impact`'s constant names |
| O2-6 | MINOR | the class-load scan was a two-substring check | `CLASS_LOAD` regex (forName / loadClass / findClass / `::loadClass`), applied to the probe AND the hook |
| O2-7 / O2-8 | MINOR | the lang pin compared values only; the fabric jar did not require the lang file | key-set compare (+ selftest negative); fabric required row (+ negative); selftest 92 |
| O4-2 | MINOR | the two proxies were uncontained boundaries into Sodium's Apply/render code | handlers contain + warn once; unknown method → null |
| O4-3 | MINOR | `handles()` latched on exceptions only; double WARN on resolve failure | `catch (Throwable)`, single WARN |
| O4-4 | MINOR | `handles()` gated on `detect()==LEGACY`, so a foreign 0.8-API jar beside 0.6/0.7 would drop the page | `SodiumGeneration.legacyPrefixIgnoringModern()` for the hook path |
| O4-5 / O3-9 | NIT | `defaultRequire: 1` — a require miss is an `InjectionError` that escapes `required:false` | `0` (the tracer's precedent), pinned on both loaders |
| F-3 | NIT | `pages.addAll` was the one uncontained statement inside Sodium's constructor | try/catch + once-WARN |
| F-5 / O3-3 / O3-4 / O3-7 | NIT | prose: "entrypoint-time" → plugin-time; README's ModMenu-on-NeoForge claim; forward-dated README; stale pointers in two plans | fixed |
| O2-10 / O2-13 / O2-11 / O3-8 / O3-11 / O3-12 | NIT | static-enabled arm untested; walker purity unpinned; raw-substring JSON pin; dead `Handles.loader`; import order; vssJar comments | fixed |
| O1-8 | NIT (pre-existing) | zh_cn/zh_tw lack the three Xaero keys | not in scope — recorded |

Verdicts: Fable lead mergeable; Opus runtime mergeable; Opus fidelity / tests /
conventions needs-fixes → all folded above.

## 12. Phase 3 port checklist (from review O3, verified against the v0.12.0 tags)

Port BASE per line = the `v0.12.0+mc<line>` tag (= `port/xaero-<line>`); fast-forward
`support/<line>` first. All six catalog-bound config fields exist at every tag; the
lang key sets equal main's; `FarPlayerClientSupport`/`LoaderServices.isModLoaded` are
xplat everywhere.

**Conflicts common to all four lines** (mechanical): `gradle.properties` (main's R2-6
block is main-only — append `sodium_legacy_golden` at EOF; `sodium_version` for the
modern golden arm exists only where the line pins one: add it or leave the modern arm
unresolvable), `fabric/build.gradle` dep hunk (hard-coded Sodium/ModMenu literals per
line — insert the two golden deps after them; the `configurations` and `test` hunks
apply clean), `README.md` (the NeoForge client-paths block is main-only — place the
paragraph before "Compatible with [AntiXray]…"), `per-version-surfaces.md` (rows 11/13
line-flavored; append the new row — it is row 15 on 1.21.1), the two
`lss-sodium-legacy.mixins.json` (`compatibilityLevel` JAVA_25 on 26.1, JAVA_21 on
1.21.11/1.21.1/1.21.10 — edit BOTH trees identically, the twin pin includes the JSON).

**Applies clean**: CLAUDE.md bullet, pre-authorized-cuts row, fabric.mod.json mixins
block, neoforge.mods.toml, both neoforge/build.gradle hunks, ToolchainContractTest,
ConfigValidationTest, NeoForgeModuleContractTest, all new source/test files, the
`RateSliderStops` rename, release_check.py (except 1.21.10).

| Line | Extra |
|---|---|
| 26.1 | nothing beyond the common set; legacy stack dormant |
| 1.21.11 | JAVA_21 configs |
| 1.21.1 | JAVA_21 configs; `LSSConfigMenu` whole-file conflict → take the new file, then `Identifier`→`ResourceLocation` at 7 sites (import, `id`, `dep`, `iconFromMetadata` return, `tryParse`, `parse("lss:icon.png")`); `SodiumConfigScreens`/`LSSModMenuIntegration` need no edit; hand-edit `suggests.sodium` `>=0.8.13-beta.2` → `*`; surfaces row = 15; the line's CLAUDE.md banner ("no Sodium page there" / "LIVE … config_api_user") reworded; BOTH generations + BOTH loaders light up — the live gates matter most here |
| 1.21.10 | JAVA_21 configs; `LSSConfigMenu` modify/delete → KEEP DELETED; `LSSModMenuIntegration` modify/delete → TAKE (`ClientMenuEntrypointContractTest` requires it) → restore the `modmenu` entrypoint + `modCompileOnly "maven.modrinth:modmenu:16.0.1"` + `suggests` `sodium`/`modmenu` `*`; `release_check.py` `FABRIC_ONLY_CLASS_PREFIXES` conflict → keep main's post-image (both `LSSConfigMenu` and `LSSModMenuIntegration` listed); `sodium_legacy_golden=mc1.21.10-0.7.3-neoforge`; amend Decision 2 in `mc1.21.10-line-notes.md` + README line 21 + release notes. FIRST — the pure-legacy proof |
