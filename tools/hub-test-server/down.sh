#!/usr/bin/env bash
# Stop the Flash Hub TEST deployment. Data in ./data is kept.
# The other stacks on this host are not touched.
set -euo pipefail
cd "$(dirname "$0")"
docker compose down
echo "stopped. ./data was kept; 'docker compose down -v' is not used on purpose."
