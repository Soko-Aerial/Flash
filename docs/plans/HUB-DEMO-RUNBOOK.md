# Flash Hub server demo runbook (2026-10-14)

**Insecure development hub, for a demo only.** It runs plain http and keeps the organisation root key on the server (`--dev-embedded-root`). Never point real devices at it.

**Deploying to a real server instead?** See `docs/deploy/HUB-SERVER-DEPLOYMENT.md` — profiles (test vs production), building and shipping `:server:installDist`, the root key and delegation, TLS modes, systemd, backups and the security checklist. This file is the local demo and the `myserver` record.

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

---

# Remote test hub on `myserver` (deployed 2026-10-19)

A second hub so phones and the desktop can enrol against a **real remote host** with the
developer PC switched off. Same insecure profile as the local demo above
(`FLASH_TLS_MODE=proxy`, `FLASH_ALLOW_INSECURE=true`, `FLASH_DEV_EMBEDDED_ROOT=true`): the
organisation **root key lives on that server**. Test only — never production, never real data,
and never a real organisation's delegation.

Everything lives in `~/flash-hub/` on `myserver`; its own `README.md` there is the operator
sheet. The same files are kept in the repo at `tools/hub-test-server/` (that directory's README
says how to refresh the host copy) — they are deployment state, not product code.

```text
ssh myserver
cd ~/flash-hub
./up.sh            start / refresh, then print the temporary public URL and the checks
./status.sh        state, live URL, health, console instructions, setup token
./down.sh          stop (the data directory is kept)
./up.sh --fresh    wipe the data directory and start a fresh organisation
```

Host facts worth remembering (checked 2026-10-19): Ubuntu 22.04 **aarch64**, no JVM installed,
Docker present (the `ubuntu` user is in the `docker` group), **80/443 belong to another app**,
and the root filesystem has well under 2 GB free. Therefore:

- **Never build on that host.** Build the distribution on the developer PC
  (`tools\flash-gradle.cmd :server:installDist`) and copy `server/build/install/server` up.
  The container is `eclipse-temurin:21.0.5_11-jre-alpine` — the same runtime as
  `server/Dockerfile`'s final stage — with the distribution mounted read-only at `/app`.
- The host runs **other applications** (cinepro, litellm-advanced, movieaura). This deployment
  uses its own compose project (`flash-hub`), its own network (`172.31.250.0/24`), its own data
  directory, and publishes only two **loopback** ports: `18443` (hub) and `18444` (admin
  console). Nothing on 80/443, no other container or unit touched. Keep it that way.

## The public URL is temporary

The address devices enrol against is a **Cloudflare quick tunnel**
(`https://<random>.trycloudflare.com`). The tunnel terminates TLS, so the hub itself runs in
`tlsMode=proxy` and trusts forwarded headers only from its own compose network
(`FLASH_TRUSTED_PROXIES`). Consequences:

- The URL **changes whenever the tunnel container is recreated**. The hub puts it in every
  invite link and QR code (`FLASH_EXTERNAL_URL`), so after a tunnel change run `./up.sh` again:
  it reads the live URL and recreates the hub with it. `./status.sh` warns when the two disagree.
- It is public to anyone who has the hostname. The setup token and the admin console are the
  only gate; the console is **not** published through the tunnel.

## Enrolling a device against any hub

1. **First administrator.** Reach the console over SSH —
   `ssh -N -L 8444:127.0.0.1:18444 myserver`, then `http://127.0.0.1:8444/admin/`. On a fresh
   hub use `http://127.0.0.1:8444/admin/setup` with the setup token
   (`<data dir>/setup-token.txt`, also printed by `./status.sh`) and save the authenticator
   secret it shows once. Setup closes after the first admin.
2. **Create the invite.** Console → **Invites** → **Create invite** (role, department, expiry).
   Copy the resulting link: `flash://enroll?server=<url>&token=<token>&root=<fingerprint>`.
   The link carries the hub URL, so it is only correct while `FLASH_EXTERNAL_URL` is current.
3. **Enrol the device.** In the app: **Settings → Enterprise & Organization** → enrol, paste the
   link in "Enrol in Organization", **Confirm & Enrol**. The device then appears in the console
   under **Devices**; move it to a department if the invite did not set one. An org admin can also
   move devices from **Organization → Departments → edit → Members** (added 2026-10-19).

## Picking up new code

The host runs whatever distribution was last copied up, so:

- **Server/console changes** (anything under `server/src/main/resources`, e.g. the console):
  rebuild `:server:installDist` on this PC, replace `~/flash-hub/app` with
  `server/build/install/server`, and **recreate the hub container**:
  `cd ~/flash-hub && docker compose up -d --force-recreate hub`. `./up.sh` alone is not enough —
  the distribution is a bind mount, but the running JVM has already loaded the old jar and
  resources, and `docker compose up -d hub` does nothing when only mounted files changed. (The
  mount is read-only, so changes must be made here and copied up; `.env` and `./data` are kept.)
- **App changes** (e.g. the hub presence fix, ERROR-741): nothing on the hub changes; build and
  install the app on the devices.

## What the test hub is for

It is the vehicle for the hub device checks that are still owed in
`docs/testing/TEST-BACKLOG.md` — the `HUB-*`, `HUBR-*`, `ANN-*`, `ORGT-*`, `DEPH-*`, `PRIV-*`
and `HUBT-*` series — because those need a reachable hub from a phone that is not on the
developer's LAN. Record results in the backlog as usual (§35), not here.
