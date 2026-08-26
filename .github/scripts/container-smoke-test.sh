#!/usr/bin/env bash
# Boots the built image against a real pgvector database and checks that it comes
# up, migrates, ingests the policy corpus and serves an authenticated request.
#
# This is the step that catches what unit and integration tests structurally
# cannot: a missing runtime dependency in the final layer, a JVM flag that only
# fails outside the test JVM, a migration that works from the test classpath but
# not from inside the jar, a health check wired to the wrong path.
set -euo pipefail

IMAGE="${1:?image reference required}"
NETWORK="osa-smoke-$$"
DB="osa-smoke-db-$$"
APP="osa-smoke-app-$$"

cleanup() {
  docker logs "$APP" 2>&1 | tail -40 || true
  docker rm -f "$APP" "$DB" >/dev/null 2>&1 || true
  docker network rm "$NETWORK" >/dev/null 2>&1 || true
}
trap cleanup EXIT

docker network create "$NETWORK" >/dev/null

docker run -d --name "$DB" --network "$NETWORK" \
  -e POSTGRES_DB=ordersupport -e POSTGRES_USER=ordersupport -e POSTGRES_PASSWORD=ordersupport \
  pgvector/pgvector:pg16 >/dev/null

echo "Waiting for Postgres..."
for _ in $(seq 1 30); do
  docker exec "$DB" pg_isready -U ordersupport -d ordersupport >/dev/null 2>&1 && break
  sleep 2
done

docker run -d --name "$APP" --network "$NETWORK" -p 18080:8080 \
  -e SPRING_PROFILES_ACTIVE=local \
  -e SPRING_DATASOURCE_URL="jdbc:postgresql://$DB:5432/ordersupport" \
  -e SPRING_DATASOURCE_USERNAME=ordersupport \
  -e SPRING_DATASOURCE_PASSWORD=ordersupport \
  -e APP_AI_MODE=offline \
  -e SPRING_AI_MODEL_CHAT=none \
  -e SPRING_AI_MODEL_EMBEDDING=none \
  "$IMAGE" >/dev/null

echo "Waiting for the application to report healthy..."
healthy=false
for _ in $(seq 1 60); do
  if curl -fsS http://localhost:18080/actuator/health 2>/dev/null | grep -q '"status":"UP"'; then
    healthy=true
    break
  fi
  sleep 2
done
[[ "$healthy" == true ]] || { echo "FAIL: the application never became healthy"; exit 1; }
echo "  health: UP"

# An unauthenticated call must be rejected by the packaged image, not just by the
# filter chain as assembled in a test context.
status=$(curl -s -o /dev/null -w '%{http_code}' http://localhost:18080/api/v1/orders)
[[ "$status" == "401" ]] || { echo "FAIL: unauthenticated request returned $status, expected 401"; exit 1; }
echo "  unauthenticated request: 401"

# An authenticated call must return the seeded orders, which proves migrations and
# the demo seed both ran inside the container.
orders=$(curl -fsS -H 'X-API-Key: osa_demo_ada_key' http://localhost:18080/api/v1/orders)
echo "$orders" | grep -q 'ORD-1001' || { echo "FAIL: seeded orders missing"; echo "$orders"; exit 1; }
echo "  authenticated order list: ok"

# Retrieval must work in the image, which proves pgvector, the migration that
# creates the vector table, and startup ingestion all line up.
policies=$(curl -fsS -H 'X-API-Key: osa_demo_ada_key' \
  'http://localhost:18080/api/v1/policies/search?q=how+long+to+return+an+item&topK=3')
echo "$policies" | grep -q 'returns-policy' || {
  echo "FAIL: policy retrieval returned nothing useful"; echo "$policies"; exit 1; }
echo "  policy retrieval: ok"

echo "Container smoke test passed."
