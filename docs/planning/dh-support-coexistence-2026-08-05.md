# LSS alongside the official Distant Horizons server plugin (issue #82)

Question from issue #82: an admin on Paper wants LSS (for Voxy users) **and** the official
DH server-side plugin (for Distant Horizons users) on the same server. Chunksmith's page
says it is incompatible with other LOD generation systems — does the same apply here?

Rig used: `test-server/paper` (Paper 26.2-92, MC 26.2), LSS 0.9.1 (`lodStore: off`) +
`DistantHorizonsSupport-0.14.0.jar` (Modrinth `IjY7seTG`, source
`gitlab.com/distant-horizons-team/distant-horizons-server-plugin`, DHS 0.14.0 targets
DH 3.1.x/3.2.x). Both plugins enabled cleanly, one player joined and left.

## Verdict

**They do not conflict in the Chunksmith sense.** Chunksmith conflicts with DHS because
both implement the *same* protocol on the *same* channel — two servers answering one
client. LSS shares nothing with DHS: different channel, different client mod, different
data model. Nothing overlaps at the protocol layer.

What they do share is one server's chunk system, CPU, disk and uplink — and there DHS
turns out to be a heavy and somewhat destructive neighbour, in ways that are DHS-side
behaviour rather than an LSS bug.

## No-conflict surface (verified)

| Surface | LSS | DHS | Collide? |
|---|---|---|---|
| Plugin channel | `lss:*` | `distant_horizons:msg` | no |
| Commands | `/lsslod` | `/dhs`, `/dh` | no |
| Permission node | `lss.admin` | `distant_horizons.admin` | no |
| Bukkit listeners | `EventPriority.MONITOR`, read-only | `EventPriority.MONITOR`, read-only | no |
| Shaded packages | `org.sqlite`, `com.github.luben` (unrelocated) | everything under `no.jckf.dhsupport` | no |
| Client mod | Voxy + LSS client | Distant Horizons | independent renderers |

On SQLite specifically: LSS shades sqlite-jdbc 3.49.1.0 unrelocated; DHS bundles no driver
and uses the one Paper ships in `libraries/` (also 3.49.1.0). Two copies in one JVM is
already the status quo on every Paper server running LSS, and sqlite-jdbc extracts its
native library to a `UUID.randomUUID()`-named temp file per classloader, so there is no
`UnsatisfiedLinkError` path. Not exercised on this rig — `lodStore` was `off`; worth one
confirmation run with `lodStore: "full"`.

## Finding 1 — DHS loads chunks and then discards them *without saving*

`DhSupport.generateLod` loads every chunk of an LOD section, and for any chunk that was not
already loaded calls `world.discardChunk(...)` on completion
(`BukkitWorldInterface.discardChunk` → `world.unloadChunk(x, z, false)`).

Paper 26.2's `CraftWorld.unloadChunk0(x, z, save=false)` (bytecode-verified against
`versions/26.2/paper-26.2.jar`):

1. `LevelChunk.tryMarkSaved()` — marks the chunk as having **no unsaved changes**
2. `unloadChunkRequest(x, z)` — drops the PLUGIN ticket
3. `ServerChunkCache.purgeUnload()` — forces the unload queue to drain immediately

So any state that chunk was carrying, unwritten, is dropped rather than flushed.

Why it matters for LSS: LSS's generated terrain is *supposed* to persist — that is what
makes the second request for a column a cheap disk read instead of another generation.
LSS holds a Moonrise ticket only for the duration of its own `scheduleChunkLoad` callback
(`PaperChunkGenerationService.completeAsyncLoad` — "the load ticket dies with this
callback"), so there is a window where a chunk LSS just generated is loaded-but-unsaved and
untracked by LSS. If DHS's `alreadyLoaded` probe missed it, DHS's discard marks it saved and
purges it. LSS's next disk read for that position misses and regenerates. Both plugins walk
outward from the same player, so the overlap is not incidental.

LSS cannot cause this in reverse: `grep` over `paper/` and `common/` finds **no**
`unloadChunk` / `purgeUnload` / `tryMarkSaved` / `loadChunk(` / `getChunkAt(` call. LSS only
reads (`getChunkNow`) and schedules Moonrise loads with Moonrise-owned tickets.

## Finding 2 — the discard cycle re-instantiates entities every pass (observed)

With **no player online**, the server logged Moonrise's

```
[EntityLookup] Entity uuid already exists: <uuid>, mapped to Villager[.../1043 ...],
can't add Villager[.../1049 ...]
```

for the *same* UUIDs at the *same* coordinates, repeating on a ~13-15 s cycle — matching
DHS's `lod_refresh_interval: 15`. 130 warnings in the ~9 minutes after the only player
disconnected, entity ids climbing 1043 → 6416, i.e. thousands of entity instantiations from
repeated chunk loads.

The underlying duplicate is probably already on disk in this well-used test world; what DHS
adds is a load → discard-without-save → reload loop that re-surfaces it forever instead of
once. LSS cannot produce this shape (Finding 1: it never unloads).

*Confidence note:* attribution rests on the timing match, the thread-dump evidence below,
and LSS's total absence of chunk-residency calls. A DHS-only control run was not performed.

## Finding 3 — DHS keeps generating long after everyone logs off, at real CPU cost

Thread dumps sampled once per second for 24 s, ~7 minutes after the last player left,
caught `DHSupport-Worker-2..5` live in `DhSupport.generateLod`,
`Coordinates.foreachChunkInSection`, `BukkitWorldInterface.getMaterialRecordAt`,
`LodRepository.saveLod/deleteLod`.

Accumulated CPU at ~9 minutes post-disconnect:

```
Paper Common Worker #0-3   ~103 s each   (Moonrise chunk load/generation)
DHSupport-Worker-2..5      ~8.5 s each
Server thread              ~28 s
LSS Processing Thread       ~1.0 s        <- idle
```

Four Moonrise chunk workers burning ~100 s of CPU each over a ~7.5-minute idle window is
roughly three-quarters of a core, sustained, with nobody connected. LSS contributes
essentially nothing to that. Default `render_distance: 1024` chunks is an enormous build
queue; `4` is the default `scheduler_threads`.

Also note `BukkitScheduler.canReadWorldAsync()` returns `false`, so every chunk snapshot DHS
takes hops onto the main thread and blocks its worker until the tick runs it. DHS's cost
lands on the tick loop; LSS's design deliberately stays off it (Moonrise `Priority.LOW`
reads, generation at `Priority.LOW`, serialization on the completion thread). Under combined
load LSS is the one that yields.

## Finding 4 — the costs that simply add up

- **Generation is paid twice.** Both plugins generate missing chunks on demand for their own
  LOD ring (`distant_generation_enabled` + `generate_new_chunks` on the DHS side,
  `enableChunkGeneration` on ours). DHS then discards its result rather than saving it, so
  it never warms LSS's disk path — the work is not shared in either direction.
- **Storage triples.** DHS keeps its own `plugins/DHSupport/data.sqlite` (4.6 MB after a
  single short session here); LSS's optional `lodStore: full` roughly doubles the world
  folder on top of the world itself.
- **Uplink is uncoordinated.** LSS throttles (`bytesPerSecondLimitPerPlayer` 15 MiB,
  `bytesPerSecondLimitGlobal` 60 MiB); DHS's `max_data_transfer_speed` is documented
  "Currently unsupported" and does nothing. A player running both mods pulls two unrelated
  LOD streams and LSS's limiter cannot see DHS's share.
- **`ChunkPopulateEvent`.** DHS listens for it by default; LSS's shipped `updateEvents` does
  not include it. If an admin adds it to LSS's list, DHS's generation churn will spam LSS
  dirty broadcasts. Leave it off.

## Suggested answer for the admin

Running both is safe in the sense that matters — no protocol, channel, command, permission
or classloader collision, and neither plugin corrupts the other's data. It is not free: DHS
alone drives the chunk system hard, keeps working with an empty server, and discards chunks
without saving, which wastes LSS's generation work.

If both are wanted on one server, the levers are all on the DHS side:

- lower DHS `render_distance` (default 1024 chunks is very aggressive)
- lower DHS `scheduler_threads` from 4
- consider `generate_new_chunks: false` so DHS serves existing terrain only and stops
  competing with LSS for generation
- raise `lod_refresh_interval` above 15 s to slow the reload cycle in Finding 2

And on the LSS side, enable `lodStore: "full"` so LSS's serves survive DHS's discards.

## Follow-ups not done

- DHS-only control run to isolate Finding 2 conclusively
- an `lodStore: "full"` run to confirm the two-SQLite path empirically
- a live two-client test (one DH, one Voxy) to measure combined uplink
