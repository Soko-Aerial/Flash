# Flash Hub - TEST deployment on `myserver`

An **insecure test hub** for the Flash apps, running on its own.

This directory is the **record of what runs on `myserver`** (`~/flash-hub/`); that host's copy is
the live one. Refresh it after editing here:

```text
scp tools/hub-test-server/{docker-compose.yml,up.sh,status.sh,down.sh,README.md} myserver:~/flash-hub/
ssh myserver 'cd ~/flash-hub && sed -i "s/\r$//" *.sh *.yml && chmod +x *.sh'
```

(`sed` because a copy that came through a shell pipe can arrive with CRLF line endings, which
`bash` refuses. `scp` never does this; a pipe can.)

- `./up.sh` â€” start (or refresh) and print the temporary public URL.
- `./up.sh --fresh` â€” wipe this hub's data first (fresh organisation).
- `./status.sh` â€” state, URL, health, console instructions.
- `./down.sh` â€” stop it (data is kept).

## Deploy a new build (one script)

From the repo root, in PowerShell 5.1 (the owner's prompt) or any shell:

```text
powershell -File tools\hub-test-server\deploy.ps1              # build + pack + upload + swap + wait for healthy
powershell -File tools\hub-test-server\deploy.ps1 -SkipBuild   # reuse server\build\install\server
powershell -File tools\hub-test-server\deploy.ps1 -Rollback    # app.prev back (add -RestoreData for data.bak)
```

It packs the build as one `.tgz` (a recursive `scp` of 57 jars is slow), keeps the previous code in `app.prev` and a copy of
`data/` in `data.bak` (`data/` is root-owned, so the copy uses `sudo`; the step runs with `ssh -t` so a password prompt works),
and fails unless the container reports `healthy`. The hub is only down for the swap. The agents' own ssh deploy is refused by
the permission classifier, so the owner runs this.

## What lives where

| Path | What |
|---|---|
| `docker-compose.yml` | the hub + the Cloudflare quick tunnel, own project `flash-hub` |
| `app/` | the hub distribution (`:server:installDist`), built on the developer PC |
| `data/` | hub state: SQLite database, delegate key, `data.key`, delegation |
| `.env` | setup token, hub name, the live tunnel URL |

## Isolation from the other apps on this host

Own compose project name, own docker network (`172.31.250.0/24`), own data
directory, and only two **loopback** host ports: `18443` (hub) and `18444`
(admin console). Nothing is published on 80/443, and no other container, unit or
file on this host is touched.

## This is a test hub â€” read before using it with anything real

It runs with `FLASH_ALLOW_INSECURE=true` and `FLASH_DEV_EMBEDDED_ROOT=true`
(see `docs/plans/HUB-DEMO-RUNBOOK.md`, `docs/security.md` SEC-04): the
organisation **root key is kept on the server** and the hub mints its own
delegation. A production hub keeps the root offline (`keytool init-root` /
`issue-delegation`) and sets `FLASH_REQUIRE_ROOT_PIN=true`.

Also: the public URL is a **Cloudflare quick tunnel**, so it is only as private
as its unguessable hostname, and it **changes whenever the tunnel container is
recreated**. Run `./status.sh` to read the current one.
