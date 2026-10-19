#!/usr/bin/env bash
# Bring up the Flash Hub TEST deployment and print its temporary public URL.
#
#   ./up.sh            start (or refresh) the tunnel and the hub
#   ./up.sh --fresh    wipe this hub's data directory first (fresh organisation)
#
# The tunnel URL is written to .env as FLASH_EXTERNAL_URL, because the hub puts it in
# invites and QR codes. A Cloudflare quick tunnel gets a NEW url every time it is
# recreated; if that happens, run this script again so the hub picks up the new one.
set -euo pipefail
cd "$(dirname "$0")"

FRESH=0
[ "${1:-}" = "--fresh" ] && FRESH=1

if [ ! -f .env ]; then
  TOKEN="$(head -c 18 /dev/urandom | od -An -tx1 | tr -d ' \n')"
  cat > .env <<EOF
FLASH_SETUP_TOKEN=$TOKEN
FLASH_HUB_NAME="Flash Hub (test)"
HUB_NETWORK_CIDR=172.31.250.0/24
FLASH_EXTERNAL_URL=https://not-configured-yet.invalid
EOF
  chmod 600 .env
fi

if [ "$FRESH" = "1" ]; then
  echo "== wiping ./data (fresh organisation) =="
  docker compose down >/dev/null 2>&1 || true
  rm -rf ./data
fi
mkdir -p ./data

echo "== tunnel =="
docker compose up -d tunnel

echo "waiting for the temporary public URL ..."
URL=""
for _ in $(seq 1 45); do
  URL="$(docker compose logs --no-color tunnel 2>/dev/null \
        | grep -oE 'https://[a-z0-9-]+\.trycloudflare\.com' | tail -1 || true)"
  [ -n "$URL" ] && break
  sleep 2
done
if [ -z "$URL" ]; then
  echo "ERROR: the tunnel printed no trycloudflare URL. Last log lines:" >&2
  docker compose logs --no-color --tail 30 tunnel >&2 || true
  exit 1
fi

# Remember the URL, then start/recreate the hub so it advertises that address.
if grep -q '^FLASH_EXTERNAL_URL=' .env; then
  sed -i "s#^FLASH_EXTERNAL_URL=.*#FLASH_EXTERNAL_URL=$URL#" .env
else
  printf 'FLASH_EXTERNAL_URL=%s\n' "$URL" >> .env
fi

echo "== hub =="
docker compose up -d hub

echo "waiting for the hub to answer /health ..."
for _ in $(seq 1 30); do
  if curl -fsS -o /dev/null "http://127.0.0.1:18443/health" 2>/dev/null; then break; fi
  sleep 2
done

exec ./status.sh
