# v0.11.0 — Modrinth changelog (short variant, hand-paste if CI misfires)

**Far players**: see other players far beyond render distance as player models in
your LOD terrain — poses, equipment, name tags, smooth motion, and mounts (horses,
boats, minecarts; unknown modded mounts degrade safely). On by default with full
privacy controls: server modes (`on`/`opt-in`/`off`), exclude list, a Paper
permission + vanish-plugin awareness, and a client "Share My Position" opt-out.
Credit: SeeU (MIT) as prior art. Folia remains experimental.

**Also new**: `/lss reset` (client — wipe and re-stream this server's LODs),
`/lsslod set` runtime settings + `/lsslod help`, backfill remaining estimate,
`maxConcurrentDiskReads` (bounds disk CPU independently of bandwidth, auto),
`dirtyBroadcastIntervalSeconds: 0` = broadcasts off, `lodDistanceChunks` default
now 300, LOD yields to vanilla transport by default
(`lodYieldsToVanillaTransport: true`), adaptive slow-connection pacing (a client
transfer governor + a server ping backstop keep gameplay responsive while LODs
stream; both default on), client cache in `.lss/` for fresh installs.

**Fixed**: a "Packet was larger than I expected" disconnect right after joining
on slow connections (a protocol-discovery race with the server's legacy-client
support; the client also waits longer before trying legacy protocols now).
