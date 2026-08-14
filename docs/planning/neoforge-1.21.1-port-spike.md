# NeoForge / MC 1.21.1 port — research spike (2026-08-14)

**Status: SPIKE ONLY — no port work authorized.** Three research agents (MC-version
gap, NeoForge loader surface, third-party-fork analysis) + a direct Voxy-fork API
probe, run during the v0.11.0 Modrinth pause, triggered by GitHub issue #160.
Full agent reports live in the session transcript; this doc is the durable
synthesis. Companion outcome already merged: the far-players #160-proofing
(PR #162 — the ENTITY_LOAD containment + per-type rider attribution).

## Bottom line

| Scope | Estimate (1 experienced dev) |
|---|---|
| MC 26.2 → 1.21.1 retarget (Fabric + common) | **10–16 days** (floor ~8 with deferrals) |
| NeoForge loader half (v1 scope) | **13–18 days** (20–26 for full feature/test parity) |
| Combined v1-scoped 1.21.1 Fabric+NeoForge line (overlaps netted) | **≈ 20–30 dev-days (4–6 weeks)** |
| Wire compat with the `dev.xantha.vss` NeoForge fork | **+12–18 days + a permanent treadmill — NOT RECOMMENDED** (see below) |

## The strategic picture

- **Voxy availability is the gate, and it half-opened**: official Voxy has no
  1.21.1 build on ANY loader and upstream closed a NeoForge port request as
  not-planned. BUT the community runs unofficial native ports —
  `j-shelfwood/voxy-neoforge` (NeoForge 21.1.217, MC 1.21.1) was probed
  directly: it is **modern Voxy** (`commonImpl.VoxyCommon`,
  `WorldIdentifier.of(Level)`, the exact
  `rawIngest(WorldIdentifier, LevelChunkSection, int,int,int, DataLayer, DataLayer)`
  static, `VoxyInstance.ingestService.getTaskCount()`,
  `VoxyClientInstance.getStorageBasePath()`) — **our `VoxyCompat`
  MethodHandle bridge would resolve against it essentially unchanged**. Caveat:
  Voxy is ARR, so these forks ship no legal jars (users self-compile /
  community builds); an official LSS port would depend on a renderer we cannot
  pin or distribute. The reset ladder's renderer rung (`IVoxyRenderSystemHolder`)
  postdates that fork's base — ships in its documented degraded state.
- **The v20 wire is the payoff**: `common/wire/` has zero MC imports and the
  identity-dictionary design absorbs 1.21.1 clients/servers by construction
  (fallback ladder → `minecraft:stone` for unknown identities). The
  cross-version story we built for the XVER program is exactly what a
  multi-version line needs.

## MC-version half (agent 1) — key facts

Translation templates already in-repo: `support/mc1.20.1` (the old-API family
1.21.1 uses — NBT getters, `registryOrThrow`, `codecRW/RO`, `TicketType.create`,
vanilla gametest annotations) and `support/mc1.21.11-v0.10` (modern features on
Java 21 + the AntiXray ScopedValue pass-through + `NativeCorpusRegenTool`).
NOTE: that branch is v0.9.1-era — far players / disk gate / governor / backstop
/ pacing / reset / RuntimeSettings exist only on main and retarget from main.

Work concentrates in:
1. **Dirty hook retarget** (MODERATE, 1–2 d): `SerializableChunkData` is 1.21.2+;
   1.21.1 target is `ChunkSerializer.write` — MUST bytecode-verify 1.21.1
   Moonrise/C2ME call it (async-save overloads may bypass; `require=0` hides a
   dead hook — the dirty-broadcast soak is the catch).
2. **IOWorker rewrite** (0.5–1 d code, more in soak re-baselining):
   `PriorityConsecutiveExecutor` → 1.21.1's `ProcessorMailbox` submit shape; the
   A7 timeout-storm history lives on this path — fresh soak baselines required.
3. **NBT serializer/transcode** (2–3 d): old-API template is 1.20.1's file;
   chunk NBT structure unchanged 1.18→26.2 so the transcode ports structurally;
   regen goldens via `-Dlss.regenGoldens=true` (keep `xver-live-corpus`
   un-regenerated — decoding 26.2 columns on 1.21.1 IS the XVER claim).
4. **FarPlayerRenderer rework** (1–2 d): 26.2 extract/submit → 1.21.1 immediate
   `dispatcher.render(...)` (the standard fake-entity idiom, simpler); the only
   structural client rework; needs manual visual verification.
5. **Bulk renames + Java 25→21** (1.5–2.5 d): only `ScopedValue` (AntiXray) uses
   post-21 APIs — copy the 1.21.11 line's pass-through.

Cheap/free: ping instruments (packet + ping logger exist since 1.20.2, no
governor recalibration), move tracer (anchors ASM-verified on 1.21.x), LAN hook
(3-arg publishServer), tickets (~6 sites), Moonrise/C2ME rungs (resolve-or-degrade).

Feature-drops for the line: Tier 3 client gametests (API postdates 1.21.1 —
1.20.1 precedent), `useBackgroundReadSplit` + `useSelectiveNbtParse` shipped
flag-off initially, Voxy reset ladder degraded. Keep: v20 wire, tracer,
governor/backstop/pacing/far-player service (MC-free).

## NeoForge loader half (agent 2) — key facts

- True loader glue is small: only 24 of ~90 fabric/ files import `net.fabricmc`;
  ~1.5–2k of ~18k lines. `common/` is pure (zero MC imports).
- **Recommended architecture**: `support/mc1.21.1` branch = `common/` + shared
  `xplat` source set + `fabric/` + `neoforge/` (MultiLoader shared-srcDir —
  unusually cheap here because we already use Mojang mappings on Fabric).
  Tests stay single-home on the fabric module; neoforge gets contract tests +
  a gametest smoke subset + `SOAK_PLATFORM=neoforge` (the Paper/Folia
  precedent — doubles as the cross-loader interop proof).
- Build: **ModDevGradle 2** (validated by the third-party fork using it).
  Riskiest build item: sqlite/zstd natives under NeoForge jarJar/module
  metadata; fallback = Paper-style shading (proven in-repo).
- Networking: payload classes are vanilla `StreamCodec`+`CustomPacketPayload` —
  shared verbatim, wire byte-identical by construction. MUST register
  `.optional()` (mandatory payloads refuse vanilla/Fabric clients at login);
  NeoForge THROWS on sends to unannounced channels (our handshake-gated sends
  already avoid it; contain the flush paths); NeoForge-client→Fabric-server
  channel announcement has an open upstream bug (#1913) — mitigated by our own
  handshake being the session armer.
- No NeoForge client-gametest equivalent — Tier 3 confidence transfers to the
  soak + manual smoke.

## The `dev.xantha.vss` NeoForge fork (agent 3) — learnings + wire verdict

Fork facts: XANTHA's standalone predecessor ported to NeoForge 1.21.1 by
`wish131400` (CurseForge, CN-primary, ~159k downloads incl. the Forge 1.20.1
sibling); 50 commits in 8 weeks, protocol bumped 30→43 in that window with
EXACT-equality gates on both ends ("don't mix jars" is their documented norm).

**Wire verdict: FORK, not a rung.** Foreign channel namespace (`vss:*`),
requestId-keyed responses (vs our position-keyed idempotence), cancel/bandwidth/
region-presence side-channels, server-push preload, fragmentation — a different
protocol FAMILY forked at/before our v16 era. Our `WireDialect` rung
architecture cannot absorb it (rungs translate frames on our own channels).
Compat = a second protocol front-end: ~12–18 d (our server serving their v43
clients) or ~10–15 d (our client on their servers), PLUS re-validation on their
~weekly breaking cadence. **Recommendation: don't pursue** — their own users
already live under update-both-sides; if a 1.21.1 NeoForge line ships, giving
that community OUR protocol (support-line playbook) is cheaper and better than
emulating theirs. If interop ever matters: upstream outreach, not emulation.

**Steal-worthy ideas (design tickets, main line):**
1. **Server storage identity** (their standout): server ships a random identity
   (+ sharedWorld/node flags) in the CONFIGURATION phase; the client keys
   Voxy's storage path by it instead of by connection address — fixes
   same-server-different-hostname store fragmentation and proxy networks.
   We'd do it via VoxyCompat/upstream API, never a @Pseudo mixin; their
   identity normalization (hostile-server containment) is load-bearing.
2. Dimension-ordinal wire encoding (VarInt 0/1/2 + Utf fallback) — ~20 B/column
   saving for a future protocol bump.
3. **Payload-size audit**: they split columns under the 1 MiB CustomPayload cap;
   our single-payload columns rely on zstd staying under it — audit our worst
   case (the MAX_SEND_SECTIONS_SIZE guard is raw-denominated).
4. `completeColumn` from heightmap-vs-highest-section (cheap partial-column
   detection from raw NBT).

**Their pain validates our choices**: file-per-column store (their last three
commits fight scaling problems SQLite solved), global-synchronized dirty
marking on the block-mutation hot path (ours rides the save pipeline + content
hash), full codec parse per column (our transcode deleted it), uncontained
entity mutation in packet handlers (issue #160 itself — our latches + PR #162).
Their NeoForge glue is a useful working API map (registrar/events/
RenderLevelStageEvent/config-phase tasks); their port debris (SRG-named AT
lines silently dead under `require=0`) is the argument for our contract-test
culture on any port.

## Recommended sequencing (if/when authorized)

1. Ship v0.11.0 (stage G) first — the port forks from a tagged, reviewed tree.
2. `support/mc1.21.1` branch: MC retarget of the Fabric module (the 10–16 d
   half) — valuable alone (a 1.21.1 Fabric line) and de-risks the loader half.
3. xplat extraction + neoforge module (the 13–18 d half), soak-gated.
4. Decide the Voxy-fork relationship (outreach to j-shelfwood / the #160
   community about a pinnable renderer build) before announcing.


## Addendum (2026-08-14): NeoForge across ALL FOUR lines (main-first strategy)

Follow-up research (primary-source fact sheet in the session transcript) for the
question "add NeoForge to main and let backports carry it to 1.21.1 / 1.21.11 /
26.1.x". Verdict: **the strategy is sound and is the industry-standard shape**
(Sodium, Iris, and Lithium all run `common/fabric/neoforge` subprojects with one
branch per MC version — exactly our layout + one module).

**Availability — no blockers**: NeoForge has PROMOTED (non-beta) builds for all
four lines: 21.1.248 (1.21.1, the long-lived legacy target), 21.11.45 (1.21.11),
26.1.2.95 (26.1, their current primary stable modding target), 26.2.0.59 (26.2).
No skipped versions in our range.

**API drift 21.1→26.2 on OUR surfaces — small**: payload networking
(RegisterPayloadHandlersEvent/PayloadRegistrar/optional()/PacketDistributor) and
the event bus/@Mod shape are STABLE across the whole span (one rename:
client-side sends moved to ClientPacketDistributor); ModDevGradle 2 everywhere.
The two rework zones are VANILLA-driven (the 1.21.9 render extract/submit split
+ the 26.2 FeatureRenderDispatcher wave; the 1.21.5 data-driven gametest
change) — i.e. they diverge per MC version exactly where our Fabric side
already diverges, so the backport process handles them identically.

**Renderer matrix (the hard constraint)**: official Voxy is Fabric-only but
covers 26.2/26.1/1.21.6-1.21.11 (NOT 1.21.1). Voxy-on-NeoForge exists only via
the 1.21.1 community fork and the Foxy loader-shim (26.1.2 only). Distant
Horizons ships a single fabric+neoforge jar on ALL four lines but has no LSS
ingest bridge. ⇒ **scope NeoForge as SERVER-FIRST**: a NeoForge server serving
Fabric+Voxy clients is full product value (the wire is loader-agnostic); the
NeoForge CLIENT half compiles but stays dormant (no consumer → capability bit
never set) until a renderer lands there. Defer the far-player client renderer +
Sodium config screens on NeoForge accordingly.

**Effort (server-first v1 scoping)**:

| Step | Estimate |
|---|---|
| Main (26.2): xplat extraction + neoforge module (no MC retarget) | ~10-15 d |
| 26.1 backport increment | ~2-4 d |
| 1.21.11 backport increment (NeoForge 21.11's Identifier rename rides the branch's existing vanilla names) | ~3-5 d |
| 1.21.1 (new branch: full MC retarget + old-idiom NeoForge glue) | ~12-18 d |
| **Total matrix** | **~27-42 dev-days (6-8 weeks)** |

Plus the RECURRING tax — the real cost of the strategy: a full release becomes
up to 4 lines × 3 loaders ≈ 12 artifacts. Mitigation per the support-line
effort budget: NeoForge jars gate on contract tests + one soak platform
(`SOAK_PLATFORM=neoforge`) + release_check arms, not full per-line gauntlets.

**Especially-hard list (confirmed)**:
1. The renderer matrix above (solved by server-first scoping; a DH ingest
   bridge is the strategic alternative and a separate program).
2. Release/validation multiplication (recurring, not one-time).
3. The xplat refactor's blast radius on main — every open branch crosses it;
   land it in a quiet window (right after v0.11.0 ships).
4. NeoForge's <32 KiB C2S payload bound: our want-set batch maxes ~16.5 KiB
   (1024 × 16 B + envelope) — fits, but needs a build-time pin so a future
   budget raise cannot silently cross it.
5. Rendering/gametest glue divergence per branch (vanilla-driven; same
   divergence axis the Fabric side already carries).
6. Java targets: xplat code must stay Java-21-clean for the 1.21.x branches
   (existing backport discipline; ScopedValue is the one known offender).
