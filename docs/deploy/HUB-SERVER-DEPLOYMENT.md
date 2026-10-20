# Deploying the Flash Enterprise Hub on any server

This is the **operator's guide** for standing the hub up on a server you own: a VPS, a
machine in the office, a home server, or a container host. It is written to be followed
without reading the source.

Two companions:

- `docs/plans/HUB-DEMO-RUNBOOK.md` — the *local demo* hub on the developer PC, and the
  concrete **test deployment on `myserver`** (its shell scripts live in
  `tools/hub-test-server/`).
- `docs/security.md` — the threat model this guide refers to (SEC-04, SEC-06, SEC-16,
  REV-10, REV-12, REV-16).

Everything below was checked against the code on **2026-10-19**
(`server/src/main/kotlin/com/transfer/flash/server/config/ServerConfig.kt`, `Main.kt`,
`server/Dockerfile`, `core/hub-protocol/.../HubContract.kt`). Numbers and default values
are the ones in that code; re-read those files if a build is much newer than this date.

---

## 1. What you are deploying

One JVM process — `com.transfer.flash.server.MainKt serve` — that offers, on **one**
public port:

| What | Path | Notes |
|---|---|---|
| Relay (device-to-device chat, files, presence, calls signalling) | `/flash-relay` | WebSocket |
| REST API (enrol, directory, announcements, groups) | `/api/v1/...`, `/admin/api/v1/...` | bearer token |
| Enrolment landing page | `/enroll` | the page a phone opens from an invite link |
| Liveness | `/health` | public, status only |

and, on a **second** listener (loopback by default), the **admin console** at `/admin/`.

State lives in one data directory: a SQLite database, the hub's delegate key, a
sealing/key-encryption key (`data.key`), the delegation certificate, and the one-time
setup token.

There is no external database, no message queue and no other service to run. A single
small VM is enough for an organisation; the hub holds offline mail in its SQLite mailbox
for devices that are away.

### Scope: what the hub covers, and what it does not

Covered, by the code in this repository:

- Enrolment of devices by invite link, with a device certificate chain signed by the
  organisation root (or a delegated hub key).
- A relay for 1:1 chat, group chat, file/chunk transfer and call signalling between
  devices **of the same organisation**, including devices that are not on the same LAN.
- The directory: departments, departments' admins, groups, members, org-wide people list,
  and **online presence** (`PersonDto.online`, fed from live relay sessions).
- Organisation and department **announcements**, with pinning, search and retention.
- The admin console: devices, invites, departments, groups, join requests, announcements,
  audit, delegation, settings; scoped logins for **department heads**.
- Offline delivery: the mailbox holds messages for an absent device until it returns
  (default TTL 7 days) or the sender's delivery ACK retires them.
- Revocation lists, session expiry, rate limiting, an audit chain, and the management
  endpoints (`/health/detail`, `/metrics`).

Deliberately **out of scope** here:

- It is **not** a general file server, VPN, or chat bridge to other networks.
- It never holds the organisation **root** key in a production deployment (see §5). The
  *test* profile does, and says so loudly.
- Group message **encryption at rest on the hub** is parked (FO-27). Announcements are
  stored in plain text on the hub **by design** (ADR-138) and the console says so.
- Presence over the hub is served by REST (`PersonDto.online`, `online` on directory
  members). There is no separate presence push.

---

## 2. Pick your profile first

| | **Try it / test** | **Production** |
|---|---|---|
| Root key | on the server (`FLASH_DEV_EMBEDDED_ROOT=true`) | offline, on a machine you control (`keytool init-root`) |
| Delegation | minted by the hub on first start | `keytool issue-delegation` → `delegation.cert` |
| Root pin | not set | `FLASH_REQUIRE_ROOT_PIN=true` + `FLASH_ROOT_FINGERPRINT=<hex>` |
| TLS | a tunnel or proxy in front (`tlsMode=proxy`), or `none` for a LAN demo | your own certificate, `tlsMode=proxy` behind nginx/Caddy, or `tlsMode=self` |
| `FLASH_ALLOW_INSECURE` | `true` | **unset** |
| Admin console | loopback + SSH tunnel | loopback + SSH tunnel (or an authenticated proxy) |

**A test hub must never hold anything real.** With `--dev-embedded-root` the server mints
its own root key: whoever reads that file can mint identities for the organisation.

The `myserver` deployment recorded in `tools/hub-test-server/` is the **test** column.

---

## 3. Requirements

- **A JRE 21** (the image uses `eclipse-temurin:21.0.5_11-jre-alpine`), or Docker.
- **A public HTTPS address.** Devices enrol against `FLASH_EXTERNAL_URL`, and every invite
  link and QR code carries it. It must be `https://` unless `FLASH_ALLOW_INSECURE=true`.
- **A TLS terminator**: a reverse proxy you already run, a Cloudflare tunnel, or the hub
  itself (`tlsMode=self`, self-signed, fingerprint printed at start-up).
- **Two reachable ports**: the public one (default `8443`) and the management one (default
  `8444`, loopback only).
- Disk: the SQLite database plus the offline mailbox. Small. Count on a few hundred MB for
  a few hundred devices; give it a real volume so `/data` can be backed up.
- Outbound access from the server is not needed by the hub itself (no telemetry, no phone
  home).

---

## 4. Build the artifact

Two supported ways.

**A. Distribution on a JRE host** (smallest footprint, no Docker; this is what the
`myserver` test hub does):

```text
tools\flash-gradle.cmd :server:installDist      (Windows, from the repo root; PowerShell: tools\flash-gradle.ps1)
./gradlew :server:installDist                   (Linux/macOS; on a shared PC use the repo's lock script, §36 of AGENTS.md)
```

Result: `server/build/install/server/` — a `bin/server` launcher plus `lib/*.jar` (~51 MB,
57 jars). Copy that directory to the server and run:

```bash
java -Xmx512m -cp '/opt/flash-hub/app/lib/*' com.transfer.flash.server.MainKt serve
```

**B. Container image** (builds in-image; needs the whole repository as build context):

```bash
docker build -f server/Dockerfile -t flash-hub:local .
docker run -d --name flash-hub \
  -p 127.0.0.1:8443:8443 -p 127.0.0.1:8444:8444 \
  -v flash-hub-data:/data \
  -e FLASH_EXTERNAL_URL=https://hub.example.com \
  -e FLASH_REQUIRE_ROOT_PIN=true \
  -e FLASH_ROOT_FINGERPRINT=<64 hex> \
  flash-hub:local
```

The image runs as an unprivileged user with `/app` read-only and `/data` its only writable
path, and it **sets `FLASH_REQUIRE_ROOT_PIN=true` by default** — so the image will refuse to
start a brand-new organisation until you pin the root fingerprint (that is the point).

> Both `server/build/install/server` and the image build require the **repository**, because
> the hub depends on the shared `:core:hub-protocol` module. They are not published to
> Maven Central.

---

## 5. Create the organisation root key and the hub's delegation (production)

The root key **never goes on the server** (SEC-04). Do this on your own machine:

```bash
# 1. The root key pair. Keep the file and its pass-phrase offline.
server keytool init-root --out ./root --no-passphrase   # (omit --no-passphrase to be prompted)
# prints, among other things, the ROOT FINGERPRINT (SHA-256 hex) — write it down.

# 2. The hub's own key pair must exist first. Start the hub once (it creates
#    <dataDir>/server-identity.pk8 and server-identity.pub, owner-only), then
#    give `issue-delegation` that public key file.

# 3. Issue the hub a delegation.
server keytool issue-delegation \
  --root-key ./root/root.key \
  --server-pub /path/to/server-identity.pub \
  --scopes enrol:member,enrol:dept_admin,revoke:member \
  --max-devices 1000 --valid-days 365 \
  --out ./delegation.cert

# 4. Copy delegation.cert to the hub's data directory, or point FLASH_DELEGATION_FILE at it.
```

Then start the hub with:

```bash
FLASH_REQUIRE_ROOT_PIN=true
FLASH_ROOT_FINGERPRINT=<the 64 hex characters from step 1>
```

`FLASH_REQUIRE_ROOT_PIN=true` refuses the first delegation unless the fingerprint matches
(REV-16): no trust-on-first-use of whatever file happens to be in the data directory.
Without it, the first delegation that appears is adopted.

`keytool rotate` exists for rotating the delegate key; `keytool` refuses to run against a
newer schema (downgrade protection).

---

## 6. Configuration reference

Precedence: **command-line argument > environment variable > default**. A malformed value
is a startup error — the hub never silently falls back to a default for something you
tried to set. Every setting can be given as `--kebab-case` on the command line or as its
`FLASH_*` environment variable.

| Env var | CLI | Default | Meaning |
|---|---|---|---|
| `FLASH_PORT` | `--port` | `8443` | Public listener: relay + REST + enrolment |
| `FLASH_HOST` | `--host` | `0.0.0.0` | Bind address of the public listener |
| `FLASH_ADMIN_PORT` | `--admin-port` | `8444` | Management listener (the admin console) |
| `FLASH_ADMIN_HOST` | `--admin-host` | `127.0.0.1` | Bind address of the management listener. **Keep it loopback** and reach it through an SSH tunnel |
| `FLASH_ADMIN_ON_PUBLIC_PORT` | `--admin-on-public-port` | `false` | Also serve `/admin` on the public port. Leave off |
| `FLASH_DATA_DIR` | `--data-dir` | `./data` | State directory (database, keys, delegation, setup token) |
| `FLASH_TLS_MODE` | `--tls-mode` | `proxy` | `proxy` (TLS ends in front of the hub), `self` (the hub terminates TLS), `none` (plain HTTP, development only, needs `FLASH_ALLOW_INSECURE`) |
| `FLASH_TRUSTED_PROXIES` | `--trusted-proxies` | empty | Comma-separated addresses/CIDRs of the proxies whose `X-Forwarded-Proto`/`For` headers are believed. **Required in practice for `tlsMode=proxy`** (see REV-10 below) |
| `FLASH_EXTERNAL_URL` | `--external-url` | guessed from host/port, else the detected LAN IPv4 | The public `https://` address devices enrol against. It is baked into invite links and QR codes |
| `FLASH_ALLOW_INSECURE` | `--allow-insecure` | `false` | Permits `tlsMode=none`, an `http://` external URL and `--dev-embedded-root`. Development only |
| `FLASH_DEV_EMBEDDED_ROOT` | `--dev-embedded-root` | `false` | **Development only**: the hub mints its own root key and delegation. Needs `FLASH_ALLOW_INSECURE` |
| `FLASH_DELEGATION_FILE` | `--delegation-file` | `<dataDir>/delegation.cert` | The root-signed delegation |
| `FLASH_ROOT_FINGERPRINT` | `--root-fingerprint` | unset | 64 hex chars: pin the organisation root key |
| `FLASH_REQUIRE_ROOT_PIN` | `--require-root-pin` | `false` (image: `true`) | Refuse the first delegation unless the fingerprint is set and matches |
| `FLASH_TLS_KEYSTORE` | `--tls-keystore` | unset | Keystore for `tlsMode=self` (else a self-signed certificate is generated in the data directory) |
| `FLASH_TLS_KEYSTORE_PASSWORD` | `--tls-keystore-password` | unset | Its password |
| `FLASH_SETUP_TOKEN` | `--setup-token` | unset | The one-time first-admin token (≥16 chars). Else the hub creates `<dataDir>/setup-token.txt`, owner-only, and never logs it |
| `FLASH_MANAGEMENT_TOKEN` | `--management-token` | unset | Bearer token (≥16 chars) for `/health/detail` and `/metrics`. Without it only a direct loopback caller (no proxy headers) may read them |
| `FLASH_HUB_NAME` | `--hub-name` | `Flash Hub` | The name devices show in Settings |
| `FLASH_DEVICE_QUOTA` | `--device-quota` | `1000` | Maximum enrolled devices |
| `FLASH_MAILBOX_TTL_DAYS` | `--mailbox-ttl-days` | `7` | How long the hub holds mail for an absent device |
| `FLASH_CHALLENGE_TIMEOUT_MS` | `--challenge-timeout-ms` | `60000` | Auth challenge validity |

Subcommands (run with no `serve` argument):

```text
server serve                       run the hub (default)
server healthcheck                 probe /health on loopback; exit 0 = healthy (the Docker HEALTHCHECK)
server keytool init-root|issue-delegation|rotate
server admin-recovery --username NAME   break-glass: new password + authenticator secret, shown once
server help
```

---

## 7. Put TLS in front (recommended: `tlsMode=proxy`)

The hub then speaks plain HTTP inside your network and believes forwarded headers **only**
from `FLASH_TRUSTED_PROXIES`.

```nginx
server {
    listen 443 ssl http2;
    server_name hub.example.com;

    ssl_certificate     /etc/letsencrypt/live/hub.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/hub.example.com/privkey.pem;

    client_max_body_size 0;          # transfer chunks; do not cap

    location / {
        proxy_pass http://127.0.0.1:8443;
        proxy_http_version 1.1;
        proxy_set_header Upgrade    $http_upgrade;   # /flash-relay is a WebSocket
        proxy_set_header Connection "upgrade";
        proxy_set_header Host       $host;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header X-Forwarded-For   $remote_addr;
        proxy_read_timeout 3600s;                    # long-lived sockets
        proxy_send_timeout 3600s;
    }
    # /admin is NOT proxied: keep the console on the hub's loopback listener.
}
```

```bash
FLASH_TLS_MODE=proxy
FLASH_TRUSTED_PROXIES=127.0.0.1            # or the proxy's container subnet, e.g. 172.20.0.0/16
FLASH_EXTERNAL_URL=https://hub.example.com
```

**Why `FLASH_TRUSTED_PROXIES` matters (REV-10).** With `tlsMode=proxy` and no trusted
proxies, every request appears to come from the proxy's address: the per-address limits
(sign-in lockout, connection cap, enrolment) are then **shared by the whole organisation**,
so a few failed sign-ins from one device lock everybody out. The hub logs a configuration
warning at start-up when this is misconfigured.

Caddy (same idea, WebSockets are automatic):

```text
hub.example.com {
    reverse_proxy 127.0.0.1:8443
}
```

### The hub terminating TLS itself: `tlsMode=self`

```bash
FLASH_TLS_MODE=self
FLASH_TLS_KEYSTORE=/etc/flash/keystore.p12
FLASH_TLS_KEYSTORE_PASSWORD=...
FLASH_EXTERNAL_URL=https://hub.example.com:8443
```

Without a keystore the hub generates a self-signed certificate in the data directory and
prints its SHA-256 fingerprint at start-up so a client can pin it. Devices enrol by invite
link, which carries the pinned root identity, so a self-signed hub works; but a public
CA certificate is friendlier and is what `proxy` + nginx/Caddy gives you.

### Plain HTTP: `tlsMode=none`

Only with `FLASH_ALLOW_INSECURE=true`, for a LAN demo. The hub logs a warning on every
start.

---

## 8. No Docker: a systemd unit

```ini
# /etc/systemd/system/flash-hub.service
[Unit]
Description=Flash Enterprise Hub
After=network-online.target
Wants=network-online.target

[Service]
User=flash
Group=flash
WorkingDirectory=/opt/flash-hub
EnvironmentFile=/etc/flash-hub.env
ExecStart=/usr/bin/java -Xmx512m -cp /opt/flash-hub/app/lib/* com.transfer.flash.server.MainKt serve
Restart=on-failure
RestartSec=5

# Hardening: the hub needs its data directory and nothing else.
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=/var/lib/flash-hub

[Install]
WantedBy=multi-user.target
```

`/etc/flash-hub.env` (owner-only permissions — it carries the setup/management tokens):

```text
FLASH_DATA_DIR=/var/lib/flash-hub
FLASH_TLS_MODE=proxy
FLASH_TRUSTED_PROXIES=127.0.0.1
FLASH_EXTERNAL_URL=https://hub.example.com
FLASH_REQUIRE_ROOT_PIN=true
FLASH_ROOT_FINGERPRINT=<64 hex>
```

---

## 9. First administrator, then invites

1. **Setup token.** Either set `FLASH_SETUP_TOKEN` yourself or read the generated one:
   `cat <dataDir>/setup-token.txt` (owner-only; the hub never logs it).
2. **Create the first admin.** With the console on a loopback port, open an SSH tunnel:
   ```bash
   ssh -N -L 8444:127.0.0.1:8444 user@your-server
   ```
   then `http://127.0.0.1:8444/admin/setup`, paste the token, create the administrator and
   **save the authenticator secret and recovery codes** (shown once). Setup closes after
   the first admin.
3. **Create an invite.** Console → **Invites** → **Create invite**. Choose the role
   (`member`, `dept_admin`) and, if you like, a **department** — the department is
   *optional* for an org admin, and the device can be moved later (§11).
4. **Enrol a device.** Copy the invite link
   (`flash://enroll?server=<url>&token=<token>&root=<fingerprint>`) and, in the app,
   **Settings → Enterprise & Organization → Enrol in Organization**, paste, **Confirm &
   Enrol**. The device then appears in Console → **Devices**.
5. **Invite links are bound to `FLASH_EXTERNAL_URL`.** If that address changes, existing
   links are wrong; create fresh invites.

**Never publish the admin console.** It is a separate listener on purpose: keep it on
loopback, reach it through an SSH tunnel, and never set `FLASH_ADMIN_ON_PUBLIC_PORT=true` on
a public host.

---

## 10. Backups

Everything of value is under the data directory (`FLASH_DATA_DIR`):

| File | What it is | If lost |
|---|---|---|
| `flash-hub.db` | devices, directory, groups, announcements, audit, mailbox, sealed-file index | organisation must be re-enrolled |
| `data.key` | seals sensitive columns (e.g. authenticator seeds) | **those columns become unreadable**: admins must re-enrol their authenticator |
| `files/` | the sealed file payloads, one directory per `fileId` (ADR-215) | the index survives and every download answers `503` until the sweeper removes those rows after retention |
| `server-identity.pk8` / `server-identity.pub` | the hub's own delegate key pair (owner-only) | issue a new delegation; devices re-enrol |
| `delegation.cert` | the root-signed delegation | re-issue from the root |
| `setup-token.txt` | first-admin bootstrap | regenerate (only matters before the first admin) |

- **Stop the hub (or use a filesystem snapshot) for a consistent database copy.** SQLite in
  WAL mode tolerates a hot copy only if you copy the `-wal` file too; the simplest safe
  backup is `systemctl stop` → copy → `systemctl start` (the downtime is seconds).
- **Copy `files/` with the database, in the same operation.** The payloads carry no key (the hub
  cannot read them) but they are the half of a transfer that the index points at; a database-only
  restore is honest about it (downloads answer `503`, the sweeper eventually clears the rows) but
  the files the recipients had not fetched yet are then gone. Files are replaceable by re-sending;
  the organisation's trust state is not, so when space is tight back up `flash-hub.db` and
  `data.key` first.
- **Keep `data.key` in a different place from database backups.** It is the only thing that
  makes a stolen database copy useless on its own; storing them together throws that away.
- Test a restore once, on a spare machine, before you need one.

---

## 11. Day-to-day operations

- **Health**: `GET /health` (public, status only). Because the hub probes itself, the Docker
  healthcheck is `server healthcheck`.
- **Detail and metrics**: `GET /health/detail`, `GET /metrics` — only a direct loopback
  caller, or anyone holding `FLASH_MANAGEMENT_TOKEN`.
- **Audit**: Console → **Audit**. Entries are chained and signed with the delegate key.
- **Rate limits** (per device or per address, whichever the route uses): sign-in/verify,
  enrolment, admin login, directory sync, people listing, announcements, and sealed-file
  uploads/reads. A refused request answers `429` with `Retry-After`. Device checks that exceed
  them are a misconfiguration, not an outage.
- **Quota**: `FLASH_DEVICE_QUOTA` caps enrolled devices.
- **Sealed files** (ADR-215): Console → **Settings** carries `maxFileBytes` (default 50 MB),
  `fileRetentionDays` (7), `deviceStoredBytes` (500 MB) and `globalStoredBytes` (5 GB). A change
  applies to the next upload; uploads already started keep the size they declared. Disk use is
  `GET /admin/api/v1/files/stats`. The hub stores ciphertext it cannot read, so a file on disk
  is not a privacy problem but it is a disk-space problem: reaching `globalStoredBytes` refuses new
  uploads with `429 ERR_QUOTA_EXCEEDED`, while a physically full disk fails that one upload (the
  client can retry) until the sweeper frees expired files on its next pass.
- **Locked-out administrator**: `server admin-recovery --username <name>` on the server's
  own console prints a one-time password, a new authenticator secret and recovery codes, and
  ends that admin's sessions.
- **Logs**: to stdout/stderr, so `docker logs`, `journalctl -u flash-hub`, or the compose
  log. Log hygiene means no names, no tokens and no secrets in them.

### Departments and department heads

- A **department** is created in Console → **Organization → Departments**. Its editor manages
  the department's **admins**; it does not list its members.
- **Membership is changed from Devices**: Console → **Devices** → open the device → the
  **Department** field → Save. Several devices at once: tick them → **Move to department**.
- Only an **organisation administrator** may move a device between departments. A department
  head sees only their own department's devices, so a device with **no** department is
  invisible to them.
- A device's **certificate and bearer token keep the department they were issued with** until
  the device renews (`IdentityStore.setDepartment`, "registry department only"). The registry
  and the directory change immediately; department-scoped behaviour on that device catches up
  at its next renewal. Plan a move rather than expecting it to be instant end to end.

### Upgrades

Replace the distribution (or pull the new image) and restart. Schema migrations are applied
by each component at start-up, are idempotent, **fail closed** (the hub will not start on a
failed step) and refuse to run older code against a newer schema — so take a backup first and
do not roll back a binary onto a database a newer binary has already migrated.

---

## 12. Security checklist (production)

- [ ] `FLASH_REQUIRE_ROOT_PIN=true` and a real `FLASH_ROOT_FINGERPRINT`.
- [ ] `FLASH_ALLOW_INSECURE` unset; `FLASH_DEV_EMBEDDED_ROOT` unset. The organisation root
      key lives offline, never on the hub.
- [ ] Public address is `https://` and matches `FLASH_EXTERNAL_URL`.
- [ ] `FLASH_TLS_MODE=proxy` with `FLASH_TRUSTED_PROXIES` listing the proxy, or
      `tlsMode=self` with a real certificate.
- [ ] The admin console is bound to loopback (`FLASH_ADMIN_HOST=127.0.0.1`, default) and is
      **not** reachable from the internet. No `FLASH_ADMIN_ON_PUBLIC_PORT`.
- [ ] `FLASH_SETUP_TOKEN` and `FLASH_MANAGEMENT_TOKEN` are long, random and secret; the env
      file is owner-only.
- [ ] The data directory is owner-only and is backed up with `data.key` stored separately.
- [ ] `/admin/api/v1/setup/status` shows `setupOpen:false` once the first admin exists.
- [ ] At least two administrators, so one lost authenticator is not an outage. No real data
      before these are true.

---

## 13. Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| Startup: `externalUrl must be https://` | `FLASH_EXTERNAL_URL` is http | set the https address, or `FLASH_ALLOW_INSECURE=true` for development |
| Startup: `tlsMode 'none' ...` | `tlsMode=none` without the opt-in | `FLASH_ALLOW_INSECURE=true`, or use `proxy`/`self` |
| Startup: `--dev-embedded-root ...` refusal | embedded root without the opt-in | do not use it in production; for a test set `FLASH_ALLOW_INSECURE=true` |
| Startup warning about `FLASH_TRUSTED_PROXIES` | `tlsMode=proxy` with no trusted proxies | set it, or the whole organisation shares one rate-limit bucket |
| The hub refuses to adopt the delegation | `FLASH_REQUIRE_ROOT_PIN=true` with a wrong/absent fingerprint | set `FLASH_ROOT_FINGERPRINT` to the value `keytool init-root` printed |
| Devices enrol but cannot relay | `/flash-relay` Upgrade headers not forwarded | enable WebSocket proxying and a long `proxy_read_timeout` |
| Everything is `429` after a burst | shared per-address bucket (see above) | fix `FLASH_TRUSTED_PROXIES` |
| Console unreachable | it is on the management port, loopback | SSH tunnel `-L 8444:127.0.0.1:8444`, then `http://127.0.0.1:8444/admin/` |
| Devices show "hub out of date" | hub build predates a client route | update the hub; the client degrades gracefully |

---

## 14. The `myserver` test deployment (worked example)

`tools/hub-test-server/` is the checked-in copy of a real, live deployment:
`docker-compose.yml` (the hub + a Cloudflare quick tunnel, own compose project `flash-hub`,
own network, own data directory, **loopback-only** host ports `18443`/`18444`, nothing on
80/443), plus `up.sh` / `status.sh` / `down.sh`.

Why it is built the way it is, and what to copy from it into a production layout:

- The distribution is built on the developer PC and **mounted read-only** at `/app`, so the
  server needs no JDK and no Gradle — useful on a small or ARM host. The production
  equivalent is `scp`-ing `server/build/install/server/` to `/opt/flash-hub/app/` and
  starting it with a JRE.
- `FLASH_TRUSTED_PROXIES` is the compose subnet, because the tunnel (the "proxy") lives on
  that network.
- `FLASH_EXTERNAL_URL` is the tunnel's current URL; `up.sh` recreates the hub whenever it
  changes, and `status.sh` warns when the two disagree.
- It carries `FLASH_ALLOW_INSECURE=true` and `FLASH_DEV_EMBEDDED_ROOT=true` because it is a
  **test** hub. Production must use §5 and §12.

Its README documents the `scp` refresh, including `sed -i 's/\r$//'` for scripts that
arrived through a Windows shell pipe.

---

## 15. Device checks this leaves owed

Deploying a hub is where the still-unverified hub features get their first real test. They
are listed, one by one, in `docs/testing/TEST-BACKLOG.md` — the `HUB-*`, `HUBR-*`,
`ANN-*`, `ORGT-*`, `DEPH-*`, `PRIV-*` and `HUBT-*` series — with the exact steps and pass
conditions. Record results there (§35 of `AGENTS.md`), not in this guide.
