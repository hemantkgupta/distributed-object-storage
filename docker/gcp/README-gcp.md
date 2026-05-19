# GCP deploy — fallback if Docker Desktop is not viable

This is the parity recipe for running the same stack on GCP without
Docker Desktop. Try the local stack at `docker/README.md` first; only
reach for this if Mac is blocked.

## Topology

| Concern             | GCP service                         |
|---------------------|-------------------------------------|
| Spring Boot gateway | Cloud Run (container)               |
| Metadata Postgres   | Cloud SQL for PostgreSQL 16         |
| Shard storage       | GCS bucket (or persistent disk)     |
| Metrics             | Cloud Monitoring (Managed Service for Prometheus) |
| Dashboards          | Cloud Monitoring dashboards         |

The gateway today writes shards to a local filesystem path. To deploy
to Cloud Run you have two options:

1. Mount a GCS bucket via `gcsfuse` (sidecar or built into the image).
2. Replace `NodeStorageService` with a GCS-backed implementation. Cleaner
   and avoids the cold-start cost of fuse.

The instructions below assume option (1) for the minimum diff path.

## One-time setup

```bash
export PROJECT_ID=<your-project>
export REGION=us-central1

gcloud auth login
gcloud config set project $PROJECT_ID
gcloud services enable run.googleapis.com sqladmin.googleapis.com \
  artifactregistry.googleapis.com storage.googleapis.com \
  monitoring.googleapis.com
```

## Cloud SQL (Postgres 16)

```bash
gcloud sql instances create dos-metadata \
  --database-version=POSTGRES_16 \
  --tier=db-f1-micro \
  --region=$REGION

gcloud sql databases create object_metadata --instance=dos-metadata
gcloud sql users create objectadmin --instance=dos-metadata --password=objectpassword
```

Note the instance connection name: `$PROJECT_ID:$REGION:dos-metadata`.

## GCS bucket for shards

```bash
gsutil mb -l $REGION gs://$PROJECT_ID-dos-shards
```

## Build and push the image to Artifact Registry

```bash
gcloud artifacts repositories create dos --repository-format=docker --location=$REGION

# From the repo root
gcloud builds submit --tag $REGION-docker.pkg.dev/$PROJECT_ID/dos/gateway:latest .
```

## Deploy gateway to Cloud Run

```bash
gcloud run deploy dos-gateway \
  --image=$REGION-docker.pkg.dev/$PROJECT_ID/dos/gateway:latest \
  --region=$REGION \
  --platform=managed \
  --port=8080 \
  --add-cloudsql-instances=$PROJECT_ID:$REGION:dos-metadata \
  --set-env-vars=SPRING_DATASOURCE_URL="jdbc:postgresql:///object_metadata?cloudSqlInstance=$PROJECT_ID:$REGION:dos-metadata&socketFactory=com.google.cloud.sql.postgres.SocketFactory" \
  --set-env-vars=SPRING_DATASOURCE_USERNAME=objectadmin \
  --set-env-vars=SPRING_DATASOURCE_PASSWORD=objectpassword \
  --allow-unauthenticated
```

The Cloud SQL JDBC socket factory is bundled by adding the dependency
`com.google.cloud.sql:postgres-socket-factory` to `obj-gateway-app/build.gradle`.

## Monitoring

Cloud Run automatically exposes a Prometheus-compatible scrape endpoint
via Managed Service for Prometheus when the Spring Boot Actuator
`/actuator/prometheus` endpoint is reachable. Enable it via:

```bash
gcloud beta run services update dos-gateway \
  --region=$REGION \
  --add-custom-audiences=prometheus
```

Then import `docker/grafana/provisioning/dashboards/object-storage.json`
into a Cloud Monitoring dashboard (the PromQL queries are portable).

## Tear-down

```bash
gcloud run services delete dos-gateway --region=$REGION
gcloud sql instances delete dos-metadata
gsutil rm -r gs://$PROJECT_ID-dos-shards
```
