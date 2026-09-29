#!/usr/bin/env bash
set -euo pipefail
IMAGE="$1"
# The pipeline ships from the same commit as core, so a deploy that moved one
# and not the other would put a worker and a schema from different releases
# against the same database. Both are required.
PIPELINE_IMAGE="${2:?usage: deploy.sh <core-image> <pipeline-image>}"
cd /opt/pgw
export IMAGE PIPELINE_IMAGE
docker compose -f docker-compose.deploy.yml pull app pipeline
docker compose -f docker-compose.deploy.yml up -d app pipeline

echo "Waiting for readiness..."
app_ready=false
for i in $(seq 1 30); do
  if curl -fs http://localhost:8080/actuator/health/readiness > /dev/null; then
    app_ready=true
    break
  fi
  sleep 2
done

if [ "$app_ready" != true ]; then
  echo "Deploy failed health check — app never became ready."
  docker compose -f docker-compose.deploy.yml logs --tail=100 app
  exit 1
fi
echo "App healthy."

# The worker publishes no port — core already owns 8080 on the host — so its
# health is read from the container healthcheck rather than over HTTP.
echo "Waiting for pipeline readiness..."
pipeline_cid=$(docker compose -f docker-compose.deploy.yml ps -q pipeline)
pipeline_ready=false
for i in $(seq 1 30); do
  status=$(docker inspect -f '{{.State.Health.Status}}' "$pipeline_cid" 2>/dev/null || echo starting)
  if [ "$status" = healthy ]; then
    pipeline_ready=true
    break
  fi
  if [ "$status" = unhealthy ]; then
    break
  fi
  sleep 2
done

if [ "$pipeline_ready" != true ]; then
  echo "Deploy failed health check — pipeline never became ready."
  docker compose -f docker-compose.deploy.yml logs --tail=100 pipeline
  exit 1
fi
echo "Pipeline healthy."

exit 0
# rollback: re-pull and restart the previously-running tag, which Docker still has cached
# under its own name if you're tagging by version (not overwriting :staging/:latest in place)
