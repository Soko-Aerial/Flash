#!/usr/bin/env bash
# Show the state of the Flash Hub TEST deployment and its temporary public URL.
set -uo pipefail
cd "$(dirname "$0")"

set -a; [ -f .env ] && . ./.env; set +a

echo "== containers =="
docker compose ps

URL="$(docker compose logs --no-color tunnel 2>/dev/null \
      | grep -oE 'https://[a-z0-9-]+\.trycloudflare\.com' | tail -1 || true)"

echo
echo "== temporary public URL (devices enrol here) =="
echo "  ${URL:-<not up yet - run ./up.sh>}"
if [ -n "${FLASH_EXTERNAL_URL:-}" ] && [ -n "$URL" ] && [ "$FLASH_EXTERNAL_URL" != "$URL" ]; then
  if [ "$FLASH_EXTERNAL_URL" = "https://not-configured-yet.invalid" ]; then
    echo "  WARNING: the hub is running with the PLACEHOLDER address, so invites and QR"
    echo "           codes do not work. Run ./up.sh."
  else
    echo "  WARNING: the hub was last started with $FLASH_EXTERNAL_URL but the tunnel now"
    echo "           serves $URL. Run ./up.sh so invites and QR codes carry the live URL."
  fi
fi

echo
echo "== health =="
printf '  hub  http://127.0.0.1:18443/health  -> '
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:18443/health || echo "unreachable"
if [ -n "$URL" ]; then
  # -4 on purpose: some hosts have a broken IPv6 route to Cloudflare's edge and would
  # report a false failure on the tunnel while devices still reach it over IPv4.
  printf '  via the tunnel  %s/health  -> ' "$URL"
  curl -4 -s -o /dev/null -w '%{http_code}\n' --max-time 25 "$URL/health" || echo "unreachable"
fi

echo
echo "== admin console (NOT public; reach it over SSH) =="
echo "  ssh -N -L 8444:127.0.0.1:18444 myserver      then open http://127.0.0.1:8444/admin/"
echo "  first-run setup:  http://127.0.0.1:8444/admin/setup"
echo "  setup token:      ${FLASH_SETUP_TOKEN:-<see .env>}"
echo
echo "== data (BACK THESE UP; data.key seals the authenticator seeds) =="
ls -la ./data 2>/dev/null | head -12
