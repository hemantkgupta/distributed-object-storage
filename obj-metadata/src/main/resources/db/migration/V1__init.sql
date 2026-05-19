-- Object metadata — one row per stored object.
-- shardLocations stores a JSON map: shardIndex -> nodeId
-- e.g. {"0":1,"1":2,"2":3,"3":4,"4":5,"5":6}
-- This is the authoritative record of where every shard lives.
-- GET and DELETE both parse this map rather than re-deriving placement.

CREATE TABLE IF NOT EXISTS object_metadata (
    id              VARCHAR(36)  PRIMARY KEY,
    bucket          VARCHAR(255) NOT NULL,
    object_key      VARCHAR(1024) NOT NULL,
    total_size_bytes BIGINT       NOT NULL,
    checksum        VARCHAR(255) NOT NULL,   -- Base64 MD5, verified on every GET
    data_shards     INT          NOT NULL,
    parity_shards   INT          NOT NULL,
    base_shard_id   VARCHAR(36)  NOT NULL,   -- UUID prefix for physical shard file names
    shard_locations TEXT         NOT NULL,   -- JSON: {"0":1,"1":2,...}
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_bucket_key UNIQUE (bucket, object_key)
);

CREATE INDEX idx_object_metadata_bucket ON object_metadata (bucket);
