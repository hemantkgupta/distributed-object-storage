-- Multipart upload support.
--
-- Flow:
--   POST /{bucket}/{key}?uploads                 → creates multipart_upload row (INITIATED)
--   PUT  /{bucket}/{key}?partNumber=N&uploadId=X  → creates upload_part row, stores shards
--   POST /{bucket}/{key}?uploadId=X&complete      → verifies ETags, assembles object_metadata, marks COMPLETED
--   DELETE /{bucket}/{key}?uploadId=X             → deletes parts + shards, marks ABORTED
--
-- Why separate tables:
--   Parts arrive out of order and may be retried. Keeping them in a separate
--   table lets the gateway accept concurrent parallel chunk uploads without
--   touching object_metadata until all parts are verified.

CREATE TYPE upload_status AS ENUM ('INITIATED', 'COMPLETED', 'ABORTED');

CREATE TABLE IF NOT EXISTS multipart_upload (
    id          VARCHAR(36)   PRIMARY KEY,
    bucket      VARCHAR(255)  NOT NULL,
    object_key  VARCHAR(1024) NOT NULL,
    status      upload_status NOT NULL DEFAULT 'INITIATED',
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_multipart_upload_bucket_key ON multipart_upload (bucket, object_key);

CREATE TABLE IF NOT EXISTS upload_part (
    id              VARCHAR(36)   PRIMARY KEY,
    upload_id       VARCHAR(36)   NOT NULL REFERENCES multipart_upload(id) ON DELETE CASCADE,
    part_number     INT           NOT NULL,
    size_bytes      BIGINT        NOT NULL,
    etag            VARCHAR(255)  NOT NULL,   -- Base64 MD5 of the raw part bytes
    base_shard_id   VARCHAR(36)   NOT NULL,
    shard_locations TEXT          NOT NULL,   -- JSON: {"0":nodeId, ...}
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_upload_part UNIQUE (upload_id, part_number)
);

CREATE INDEX idx_upload_part_upload_id ON upload_part (upload_id);
