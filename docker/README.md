# Docker — end-to-end local stack

Boots Postgres, the obj-gateway-app Spring Boot service, Prometheus, and
Grafana on Docker Desktop (Mac, Apple Silicon or Intel).

## Quick start

```bash
# From the repo root
docker compose up --build
```

First build takes a few minutes (Gradle pulls Spring Boot 3.2.4 dependencies).
Subsequent runs reuse the image layers.

## Smoke test

```bash
# Health
curl -s http://localhost:8080/actuator/health
# → {"status":"UP", ...}

# PUT — the controller uses multipart/form-data (field name: "file")
echo "hello world" > /tmp/hello.txt
curl -s -X POST -F "file=@/tmp/hello.txt" \
  http://localhost:8080/api/v1/storage/test-bucket/hello.txt
# → Stored test-bucket/hello.txt

# GET — returns the raw bytes (application/octet-stream)
curl -s http://localhost:8080/api/v1/storage/test-bucket/hello.txt
# → hello world

# DELETE
curl -s -X DELETE http://localhost:8080/api/v1/storage/test-bucket/hello.txt
# → Deleted test-bucket/hello.txt
```

## Observability

- Prometheus: <http://localhost:9090> (try query `up` to see the gateway target)
- Grafana:    <http://localhost:3000> (login `admin` / `admin`)
  - Pre-provisioned dashboard: **Distributed Object Storage**
  - Panels: PUT/GET/DELETE rate, PUT latency p50/p95/p99, GET latency
    p50/p95/p99, 5xx error rate, JVM heap, repair queue depth (placeholder).

## What runs where

| Service     | Port (host) | Notes                                            |
|-------------|-------------|--------------------------------------------------|
| postgres    | 6432        | maps container 5432; healthcheck via pg_isready  |
| gateway     | 8080        | Spring Boot bootJar; depends_on postgres healthy |
| prometheus  | 9090        | scrape interval 15s                              |
| grafana     | 3000        | datasource + dashboard auto-provisioned          |

Shards land in the `storage-nodes-data` named volume mounted at
`/app/data/storage-nodes` inside the gateway container.

## Common operations

```bash
docker compose ps                          # status of all services
docker compose logs -f gateway             # tail gateway logs
docker compose exec postgres psql -U objectadmin object_metadata
docker compose down                        # stop everything
docker compose down -v                     # stop AND wipe all named volumes
```

## Build notes worth knowing

- The root `build.gradle` adds `-parameters` to every subproject's compiler
  args. Spring MVC's `@PathVariable String bucket` falls back to reflection
  for the parameter name when no explicit value is given — without
  `-parameters` the gateway returns 500 with *"parameter name information
  not available via reflection."* The Spring Boot Gradle plugin enables
  `-parameters` only on the module that applies it, and the controllers
  live in library modules.
- `STORAGE_NODES=1,2,3,4,5,6` is set in `docker-compose.yml` for the
  gateway. `@Value("${storage.nodes}")` on `ConsistentHashRing` expects a
  comma-separated string; the YAML list in `application.yml` flattens to
  indexed keys (`storage.nodes[0]`, …) which the placeholder resolver
  cannot match. The env var wins via Spring's relaxed binding.

## Mac troubleshooting

- **Apple Silicon image pulls.** All base images here (`postgres:16-alpine`,
  `eclipse-temurin:17-jre`, `gradle:8.7-jdk17`, `prom/prometheus`,
  `grafana/grafana`) publish multi-arch manifests on Docker Hub. No
  `--platform linux/amd64` workaround is needed.
- **Slow first build.** Gradle dependency download runs once per fresh image;
  if the build looks stuck, check `docker compose logs gateway`.
- **Port conflicts.** If host port 6432, 8080, 9090, or 3000 is taken, edit
  the `ports:` mapping in `docker-compose.yml` (left side = host).
- **Volume permissions.** Named volumes are owned by the container user.
  Don't `chown` them from the host — instead reset with `docker compose
  down -v`.
- **Gateway exits with `Connection refused` to Postgres.** The healthcheck
  on `postgres` should gate the gateway, but if Postgres takes longer than
  60s to initialise, raise the `start_period` in `docker-compose.yml`.
- **`./gradlew` permission denied during build.** The build calls `gradle`
  directly inside the `gradle:8.7-jdk17` image, not `./gradlew`, so the
  host's wrapper permissions don't matter.
