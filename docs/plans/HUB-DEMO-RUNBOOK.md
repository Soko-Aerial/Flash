# Flash Hub server demo runbook (2026-10-14)

**Insecure development hub, for a demo only.** It runs plain http and keeps the organisation root key on the server (`--dev-embedded-root`). Never point real devices at it.

## Start
```text
powershell -File tools\demo-server.ps1 -Fresh      (first time, or to wipe the demo data)
powershell -File tools\demo-server.ps1 -NoBuild    (later runs, reuses the build)
```
It builds through the Gradle lock (`:server:installDist`), prints the LAN address (for example `http://192.168.0.101:8443`) and runs the hub in the foreground; Ctrl+C stops it. Data: `server\data\demo` (git-ignored).

## Verified on 2026-10-14 (smoke, this PC, commit after efca6d8f)
- Starts clean; `GET :8443/health` = 200; `GET :8444/admin/api/v1/setup/status` = `{"setupOpen":true,...}`; the console page `:8444/admin/` = 200.
- `GET /api/v1/directory/sync` without a token = 401, with a malformed cursor and a bad token = 401 (the 400 path is covered by `ORGT-S20`).
- NOT verified here: creating the admin in the browser, enrolling a real device, a device syncing the directory, the Hub tab against this server. Do these once before presenting.

## Demo script
1. Open `http://127.0.0.1:8444/admin/setup`, token `demo-setup-token`, create the first administrator (save the authenticator secret shown).
2. Console: create a department, make an invite, enrol the desktop app (Settings > enterprise hub) with the printed LAN URL. The phone must be on the same Wi-Fi; the phone is not touched by agents, do it yourself.
3. Show: member appears in the directory; disable/remove it and watch the removal reach the device on its next sync (pull-to-refresh once the S1 wiring lands).
4. Server defences worth showing: sign-in lockout, 429 + Retry-After on the directory sync, setup page closes after the first admin.

## Known rough edges (say them out loud rather than hit them live)
- The relay storm test `E2eRelayContractTest.aReconnectStormLeavesExactlyOneLiveSession` is flaky (fails ~1 in 2 runs: after 20 stop/start the device ends SUSPENDED/KICKED). A real client reconnect race is possible; avoid toggling the hub connection quickly during the demo. Logged in `logs/streams/` handoff as OPEN.
- `:desktop:jvmTest` has a hanging TLS binding test (ERROR-106).
- Nothing in the Hub work is device-verified except what `ORGT-B01` covered.
