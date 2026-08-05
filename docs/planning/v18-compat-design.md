# v18 server-side compat rung — design

**Status: implemented for v0.9.1 (2 adversarial plan-review rounds + execution review).**

## 0. Problem

Protocol 19 (compressed columns, PR #77, v0.9.0) dropped protocol-18 clients — the entire
v0.7.x–v0.8.x install base on the 26.2 line — to the v16 fallback session. Live evidence
(2026-08-04, Modrinth test server): a v0.8.x client handshakes v18, the gate answers
VERSION_MISMATCH (silent no-reply), and ~5 s later the client's v16 discovery timer
re-handshakes as protocol 16 and gets the degraded legacy session (server-side synthetic
1 Hz want-set, source-less columns, `RESPONSE_RATE_LIMITED_V16` bounces).

That fallback works, but it costs a v18 client everything the v17 design line gave it:
client-owned want-set declarations at adaptive cadence, source attribution, the
delivery-honesty ladder driven by the client's own re-declarations, and the 5 s discovery
delay on every join. The 18→19 wire delta is one S2C byte — this rung serves v18 clients
natively for that price.

## 1. The wire delta (verified against `git show v0.8.2`; re-verified by review round 1)

| Surface | 18 → 19 change |
|---|---|
| `VoxelColumnS2CPayload` | +1 codec byte after the source byte; codec 1 = zstd-1 frame |
| `HandshakeC2SPayload` | unchanged (capabilities bitmask; bit 2 defined in 19 but the v0.8.2 client hardcodes caps=1) |
| `SessionConfigS2CPayload` | layout unchanged (4 fields); leading VarInt version value differs |
| `BatchChunkRequestC2SPayload`, `BatchResponseS2CPayload`, `DirtyColumnsS2CPayload` | unchanged |

Every decode-limit constant the v0.8.2 client clamps on is unchanged (`MAX_SECTIONS_SIZE`,
`MAX_DIMENSION_STRING_LENGTH`, batch caps — review V1). One more 18→19-era delta a v18
session WILL observe: `COLUMN_SOURCE_STORE = 3`, a source value no v0.8.2 client ever saw
— verified safe (v0.8.2 uses the source byte only for its `/lss trace` JSONL `src:` field;
no switch, no gate — review V3).

The v0.8.2 client's column decode is: `readInt, readInt, readUtf(dim), readLong,
readByte(source), readByteArray(sections)` — i.e. the CURRENT layout minus the codec
byte. Its `ClientSessionGate.onSessionConfig` hard-gates on
`version == 18 || (version == 16 && compat)`; anything else logs "incompatible … LOD
distribution disabled". So the reply MUST echo 18, and columns MUST NOT carry the codec
byte — a codec byte would be consumed as the section-array length VarInt (codec 0 reads
as a zero-length array with trailing garbage → DecoderException → hard kick).

**A v18 session is therefore: a CURRENT session whose SessionConfig echoes 18, whose
columns are always codec-RAW, and whose column frames omit the codec byte at egress.**
No ingress work at all — C2S is byte-identical, and the want-set pipeline never learns
the session is legacy (unlike v16, which needs the synthetic-declarer manager).

## 2. Design

Mirror the v16 shim's shape at every seam, minus everything a membership-only dialect
does not need.

### 2.1 Constants + gate

- `LSSConstants.V18_COMPAT_PROTOCOL_VERSION = 18`.
- `HandshakeGate.WireDialect.V18`; new 6-arg
  `evaluate(version, caps, configEnabled, servicePresent, v16CompatEnabled, v18CompatEnabled)`.
  Rung order: `== PROTOCOL_VERSION` → CURRENT; `== 18 && v18CompatEnabled` → V18;
  `== 16 && v16CompatEnabled` → V16; else VERSION_MISMATCH. The 5-arg and 4-arg
  overloads delegate with the new flag false, so every existing gate pin still pins the
  strict ladder unchanged. **Both production call sites (Fabric `LSSServerNetworking`,
  Paper `LSSPaperPlugin`) must migrate to the 6-arg form — a missed site compiles clean
  and silently leaves v18 clients on the v16 fallback (review F5), so each platform gets
  a pin that drives its PRODUCTION handshake path with a v18 frame, not just the gate.**

### 2.2 Config

- `ServerConfigBase.enableV18Compat = true` (both platforms inherit; GSON picks the
  field up with no loader changes). Mirrors `enableV16Compat`.
- New default-true pins for BOTH shim flags (none exists today for `enableV16Compat` —
  review finding 3).
- `docs/planning/config-defaults-and-clamps-review-2026-08-02.md` gains the row. Note
  the sunset conditions differ: v16 compat retires when no v0.6.x-or-older peers remain;
  v18 compat retires when no v0.7.x–v0.8.x peers remain (later).

### 2.3 Dialect identity — `common/compat/V18CompatTracker`

Membership only (`ConcurrentHashMap.newKeySet<UUID>`) plus a cumulative started counter
for diagnostics; no per-session state:

- `onHandshake(uuid)` — called on a REGISTER outcome with the V18 dialect. **Threading
  differs by platform and the difference is load-bearing (review F1): Fabric marks
  inline immediately before `registerPlayer` (main thread, same as the v16 mark); Paper
  marks ONLY via the `dialectFlip` runnable that `enqueueRegister` defers to the PUMP —
  a region-thread mark would flip `isV18` mid-flush and ship codec-less frames to a
  decoder still armed CURRENT (the exact race the pinned round-3 v16 ordering fix
  closed).**
- `onDisconnect(uuid)` — network disconnect hooks (Fabric DISCONNECT event, Paper
  PlayerQuit). **Paper additionally drops membership when the lifecycle mailbox drains a
  quit-originated Remove** — the quit's direct `onDisconnect` can run before a deferred
  Register's mark has applied, which would leak membership forever (an unbounded set
  under a forged-UUID join/quit flood — review F2; the v16 manager has the same
  inherited hole with heavier state, out of scope here). The mailbox Remove is
  quit-originated only (enqueueRemove's single caller is the PlayerQuit handler —
  verified) — the dimension-change cycle calls `removePlayer` directly on the pump — so
  this cannot break identity-survives-dim-change. **Execution review added the third
  drop site: the departed-player sweep** (`processPlayerLifecycle`'s toRemove — an
  entity removed with no PlayerList entry, i.e. a player whose quit event never fired)
  also drops membership; it is semantically a disconnect.
- Identity SURVIVES `service.removePlayer` (the dimension-change remove+register
  cycle), mirroring capabilities and the v16 identity. Pinned by driving the production
  remove+register cycle through the Paper service twin and asserting the re-derived
  `wantsCompressedColumns` stays false (review F6 — a tracker-only test cannot pin
  this).
- `onNonV18Handshake(uuid)` — shed on a cross-dialect re-handshake (the v16 shed's twin).
- `isV18(uuid)`, `sessionCount()`, `diagLineOrNull()`.

Cross-dialect shedding at the handshake seam: a CURRENT reply sheds both legacy
identities; a V18 reply sheds v16; a V16 registration sheds v18. VERSION_MISMATCH sheds
nothing (matches the existing keeps-registration stance). On Paper the reply-seam sheds
run inline on the region thread for reply-only outcomes — an inherited, accepted v16
residual (hostile cross-dialect re-handshake on a live registered stream, review F3);
do not "fix" it asymmetrically for one dialect. The register-path flips mark-own +
shed-other together on the pump.

### 2.4 SessionConfig reply

- Fabric `LSSServerNetworking.handleHandshake`: V18 dialect →
  `new SessionConfigS2CPayload(V18_COMPAT_PROTOCOL_VERSION, effectiveEnabled,
  lodDistanceChunks, enableChunkGeneration)` (version is already a ctor parameter and
  the encode never branches on its value — review V2). Fabric ordering: mark v18 before
  `registerPlayer`, both inline on the main thread (mirrors the v16
  `onHandshake`-before-`registerPlayer` ordering).
- Paper `LSSPaperPlugin`: V18 → `PaperPayloadHandler.sendSessionConfig(player, 18, …)`
  (version already a parameter). The v16 branch is untouched; the CURRENT branch's
  stale-session shed extends to both trackers.
- Paper's deferred-reply registration ordering is preserved: the v18 mark joins the
  existing `dialectFlip` runnable that runs on the PUMP immediately before
  `registerPlayer` — required because `registerPlayer` derives
  `wantsCompressedColumns` from the dialect (2.5), and because the reply must not
  precede the state (the v0.8.0 pre-registration-gap fix applies to v18 joins too).

### 2.5 zstd eligibility

The registration derivation gains a fifth term on both platforms:
`wireCompressionLive && (caps & CAPABILITY_ZSTD_COLUMNS) != 0 && !isV16(uuid) && !isV18(uuid)`.
A real v18 client never sets bit 2 (v0.8.2 hardcodes caps=1), but a hostile v18
handshake with caps=3 must never produce a codec-1 payload for a session whose layout
cannot carry a codec byte — identical reasoning to the pinned v16 term. Derivation
timing verified on both platforms (Fabric: same-thread sequential; Paper/Folia: flip →
register → reply strictly ordered on the pump — review V4). Dimension-change
re-registration re-derives through the surviving membership on both platforms (review
V5).

### 2.6 Column egress

Every producer already funnels through one per-player egress seam per platform, and
column payloads are built PER RECIPIENT (the dedup fan-out shares only the
`ColumnBytes` holder, whose `raw()` decompresses stored zstd frames for raw-needing
recipients) — so no fan-out share or store hit can carry another player's codec-1
frame into a v18 session (review V6).

- Fabric `VoxelColumnS2CPayload`: the private `v16Wire` boolean becomes a 3-value wire
  shape (CURRENT / V18 / V16). `asV18()` mirrors `asV16()`; `write()` for V18 emits the
  source byte and skips the codec byte. Same precondition as v16 (codec == RAW),
  enforced at the seam, not in the payload.
- Fabric `RequestProcessingService.sendColumnPayload`: an `else if isV18(uuid)` branch —
  instanceof + codec-RAW guard (drop + warn-once on violation; reachable only in the
  same established-zstd-session-downgrade window the v16 guard documents) → send
  `col.asV18()`. No prune bookkeeping — there is no synthetic want-set; the client's own
  re-declaration heals any drop.
- The `isV16Convertible` predicate is renamed `isLegacyConvertible` (identical body —
  codec == RAW is the condition for BOTH legacy shapes; only
  `CompressedColumnBuildTest` references it — verified contained, review finding 7).
- Paper `PaperPayloadHandler.rewriteColumnToV18(frame)`: splice out exactly the codec
  byte (source byte kept verbatim — including unknown-source values like 3/store, which
  pass through under the forward-safety rule); THROWS on codec != RAW, like the v16
  splice.
- Paper `columnPayloadSender`: a v18 branch mirroring the v16 branch's try/catch drop
  shape, minus the prune.

### 2.7 Observability

- Registration log gains `, v18-compat` (beside the existing `, v16-compat`).
- `/lsslod diag` gains a `V18Compat: clients=N, started=M` line, both platforms. The
  concrete surface (review finding 2): a new `DiagnosticsFormatter.DiagData` component +
  `withV18Line(...)` + a rendering slot immediately after the v16 slot (before xray);
  call sites `LSSServerCommands` and `PaperCommands` chain it beside `withV16Line`;
  `PaperCommandsTest`'s mocked service gains a `getV18CompatTracker()` stub (its diag
  tests NPE otherwise); `DiagnosticsFormatterTest`'s optional-line ORDER pins gain the
  v18 slot.
- Exporter schemas and the soak checker are deliberately untouched — verified
  dialect-free (review finding 8).

## 3. What is deliberately NOT built

- **No client-side v18-server support.** A v0.9.1 client joining a v0.8.x server still
  degrades via the v16 discovery fallback. Building native discovery for it would need a
  second timer rung (try 18 before 16) for a shrinking population of un-upgraded
  servers; the fallback already covers it. Residual, documented here.
- **No zstd for v18 sessions** — the layout has nowhere to carry the codec byte, and the
  v18 client has no decompressor. Raw bytes still ride Minecraft's connection zlib.
- **No v17 rung.** Protocol 17 never shipped in a tagged release (18 landed 2026-07-17,
  before v0.7.0); there are no v17 clients in the field.
- **No exporter/soak schema changes.**
- **No fix for the v16 manager's quit-race membership leak** (review F2) — inherited,
  heavier-state, out of scope; the v18 tracker avoids duplicating it via the mailbox
  Remove drop (2.3).

## 4. Failure analysis

- **Codec byte leaking to a v18 client** = hard kick (decoded as the section-length
  VarInt). Defenses: the per-session RAW derivation (2.5), the egress guard (2.6), and
  the identity surviving dimension change (2.3). Pinned at all three. Review V6 found no
  bypass path (all column sends funnel through the one seam per platform; payloads are
  per-recipient).
- **Version echo of 19** = client self-disables (logged, no kick). Pinned via the reply
  builders.
- **Stale v18 identity after a client upgrade mid-connection** (re-handshake as 19):
  shed at the reply seam (`onNonV18Handshake`), mirroring the pinned v16 shed.
- **Queued codec-1 payloads draining across a 19→18 dialect flip** (hostile/buggy
  re-handshake only): dropped at the guard with warn-once; heals by re-declaration.
  Same accepted residual as the v16 guard (send-success accounting books a payload that
  never shipped, bounded to the flip instant).
- **The v18 client's v16 discovery timer**: disarmed by the immediate v18 reply — the
  5 s window never elapses. If a reply is slower than 5 s (join-time server freeze), the
  v0.8.x client's own downgrade guard re-asserts v18 on the late v16-fallback config;
  the server's cross-shed then restores the v18 session. Bounded, no ping-pong (the v18
  reply never re-enters the guard). Decode-safe throughout: the v0.8.2 sourceless flag
  latches per SessionConfig FRAME in stream order, so column shape always matches the
  last config on the stream (review V7).
- **Paper `/reload` (review finding 1 + F4).** The rebuilt service loses all dialect
  identity (v16 and v18 alike) while connections survive; the re-attach prompt
  deliberately speaks the v16 6-field shape to ANY unregistered declarer — it is the one
  shape every dialect of client parses safely, and its job is to trigger a re-announce.
  A v0.7.x–v0.8.x client heals exactly like a v19 client: its downgrade guard
  (`v16 && sessionConfigReceived && !isV16Server`) re-announces protocol 18, which
  re-registers through the new rung; the in-order channel delivers the echo-18 config
  (disarming the vintage client's sourceless-decode flag, which pre-v0.8.2 arms on the
  raw version with no announce gate) before any post-re-registration column. ACCEPTED
  with two documented corners: (a) a vintage client with `enableV16ServerCompat=false`
  self-disables until rejoin (non-default flag, prompt-visible in its log); (b) with
  `enableV18Compat=false` on the server, the prompt→silent-mismatch loop never heals and
  never degrades to v16 either (the vintage discovery timer is permanently disarmed once
  a config arrived) — bounded to one prompt per 60 s; flag-off is an operator opt-out.
  Folia: `/reload` unsupported there, Paper-only surface.
- **Handshake-then-instant-quit on Paper** (review F2): without the mailbox-Remove drop,
  the deferred mark applies after the quit's unmark → permanent membership + lying diag
  count. Closed by 2.3's Remove-drain drop; pinned.

## 5. Test plan

Tier 1 (both platforms where twins exist):

1. `HandshakeGateTest` (+ Paper twin) — v18 rung: flag-on → V18/REGISTER; flag-off →
   VERSION_MISMATCH (no reply); NO_CONSUMER / DISABLED ladder parity under V18; v17
   still mismatches; the v16 rung is unaffected by the v18 flag and vice versa. Rename /
   re-comment the two tests whose names use the pre-rename "V18 = current dialect"
   vocabulary (`onlyExactly16GetsTheCompatRungAndV18KeepsItsDialect`,
   `olderClientVersionSendsNothing` — review finding 6).
2. Fabric wire shape (`WireParityTest` legacy-shapes section) — `asV18()` goldens:
   normal column, ghost-clear (0-section), long-dimension, unknown-source (incl. 3 =
   store) — hand-built expected bytes replicating the literal v0.8.2 read sequence with
   zero trailing bytes; `asV18` vs CURRENT differ by exactly the one codec byte.
3. Paper `WireParityTest` — `rewriteColumnToV18` splice twins of the same goldens
   (cross-module byte parity with Fabric `asV18`); throws on codec-1; source byte
   preserved verbatim. Constant anchor `V18_COMPAT_PROTOCOL_VERSION == 18` joins the
   existing v16 fixture anchors.
4. `V18CompatTrackerTest` — membership lifecycle: handshake/disconnect/cross-shed/
   counts/diag line.
5. Registration derivation — a v18 session with caps=3 derives
   `wantsCompressedColumns == false`, AND the production removePlayer+registerPlayer
   dimension-change cycle re-derives false through the surviving membership (Paper
   service twin via the injection seams; the Fabric derivation is textually identical
   per the established twin convention).
6. Paper glue (`LSSPaperPluginGlueTest`) — V18 dialect: deferred reply, dialect
   recorded, kill switch; the production `handleHandshake` path drives the 6-arg
   evaluate (review F5). The execution review found the two production LAMBDA bodies
   sat one seam above these tests (a dropped V18 mark or a version-19 echo compiled
   clean), so both were extracted static and pinned directly:
   `sessionConfigVersionFor` (echoes 18 for V18 only) and `dialectFlipFor` (the
   mark-own/shed-other switch, driven against real manager+tracker instances).
7. Egress guards — Paper: v18 branch splices codec-RAW and drops codec-1 with warn-once
   (mirror the v16 pins); the quit-race mailbox-Remove membership drop (review F2).
   Fabric: `isLegacyConvertible` predicate pin moves with the rename.
8. Config — `enableV18Compat` AND `enableV16Compat` default-true pins (the latter does
   not exist yet — review finding 3).
9. `DiagnosticsFormatterTest` — v18 slot ordering added to the optional-line pins;
   `PaperCommandsTest` — tracker stub + diag line rendering.

Tier 2: extend the existing crafted-frame handshake gametest
(`ServiceLifecycleGameTests` — already in the entrypoint listing, no
`GameTestEntrypointContractTest` impact) with a v18 frame → reply echoes 18 →
registered — this drives the PRODUCTION Fabric `handleHandshake` (review F5's Fabric
pin).

Live gate: deploy to the Modrinth test server; a v0.8.x client (vx7m) should register
natively (`v18-compat` in the join log, no v16 fallback at +5 s), receive columns, and
`/lsslod diag` should show `V18Compat: clients=1` with zero `V16Compat` sessions.

## 6. Ship checklist (docs + release)

- CLAUDE.md: the "v17 is a breaking protocol bump — with legacy shims on BOTH sides"
  paragraph gains the v18 rung (membership-only, codec-stripped, echo-18); the server
  config list gains `enableV18Compat`; the S2C payload bullet notes the codec byte is
  protocol-19 and stripped for v18 sessions; Tier-1 catalog mentions the new pins.
- `docs/planning/config-defaults-and-clamps-review-2026-08-02.md`: the new row (2.2).
- Release notes (v0.9.1): headline the restored v0.7.x–v0.8.x client support
  (player-facing: "older LSS/VSS clients get a full session again instead of the legacy
  fallback"), mention both platforms, Folia experimental-status sentence if any
  Folia-affecting wording is used.
- This doc's status header flips to "implemented (v0.9.1)" when merged.
