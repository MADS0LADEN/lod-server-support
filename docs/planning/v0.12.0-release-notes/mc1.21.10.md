### New Features

- **Rejoins stop re-downloading and re-checking terrain you already have** — the server no longer re-sends unchanged terrain to returning players (works with any client version), and with client and server both on v0.12.0 cached terrain is verified in seconds instead of minutes.
- **Xaero's World Map fills in beyond render distance (opt-in)** — with Xaero's World Map installed on the client, set `enableXaeroMapBridge` to true in `lss-client-config.json` and downloaded LOD terrain is drawn into the map (works even without Voxy).
- **Lower client memory use** — terrain tracking takes roughly 6-10× less memory at the default LOD distance, with fewer hitches when teleporting or switching dimensions.

### Bug Fixes

- **LOD downloads no longer stay slow for a whole session on fast connections** — the join speed ramp now completes within about 40 seconds of joining (client-side, works against any server version).
- **Fabric: replacing the mod jar under a running server no longer risks an unclean shutdown** and a lost final world save.

### Compatibility

- Mixed client/server versions remain fully compatible in both directions; this line is supported at the "correct, not perfect" tier.
- No Folia build exists for MC 1.21.10 (Folia players should use the 1.21.11 release), and this line has no in-game options page — every setting lives in the JSON config files.
