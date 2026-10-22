#!/usr/bin/env bash
set -euo pipefail
IMAGE="$1"
# Optional second argument: the submission-pipeline worker image. Without it the worker is left alone.
PIPELINE_IMAGE="${2:-}"
cd /opt/pgw
export IMAGE

# The worker is best-effort. It starts only once the app is healthy, and a failure here is
# reported but never fails the deploy: a worker problem must not make a good app deploy look
# broken, or send it into the rollback path below.
deploy_worker() {
  [ -n "$PIPELINE_IMAGE" ] || return 0
  export PIPELINE_IMAGE
  echo "Deploying the worker..."
  if docker compose -f docker-compose.deploy.yml pull pipeline       && docker compose -f docker-compose.deploy.yml up -d pipeline; then
    echo "Worker started."
  else
    echo "WARNING: the worker did not start; the app deploy is unaffected."
  fi
}

docker compose -f docker-compose.deploy.yml pull app
docker compose -f docker-compose.deploy.yml up -d app

echo "Waiting for readiness..."
for i in $(seq 1 30); do
  if curl -fs http://localhost:8080/actuator/health/readiness > /dev/null; then
    echo "Healthy."
    deploy_worker
    exit 0
  fi
  sleep 2
done

echo "Deploy failed health check — rolling back."
docker compose -f docker-compose.deploy.yml logs --tail=100 app
# rollback: re-pull and restart the previously-running tag, which Docker still has cached
# under its own name if you're tagging by version (not overwriting :staging/:latest in place)
exit 1
