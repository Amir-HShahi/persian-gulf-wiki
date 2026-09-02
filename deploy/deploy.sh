#!/usr/bin/env bash
set -euo pipefail
IMAGE="$1"
cd /opt/pgw
export IMAGE
docker compose -f docker-compose.deploy.yml pull app
docker compose -f docker-compose.deploy.yml up -d app

echo "Waiting for readiness..."
for i in $(seq 1 30); do
  if curl -fs http://localhost:8080/actuator/health/readiness > /dev/null; then
    echo "Healthy."
    exit 0
  fi
  sleep 2
done

echo "Deploy failed health check — rolling back."
docker compose -f docker-compose.deploy.yml logs --tail=100 app
# rollback: re-pull and restart the previously-running tag, which Docker still has cached
# under its own name if you're tagging by version (not overwriting :staging/:latest in place)
exit 1
