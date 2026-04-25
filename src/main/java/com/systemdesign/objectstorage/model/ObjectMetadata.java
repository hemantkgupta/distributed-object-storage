package com.systemdesign.objectstorage.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(
    name = "object_metadata",
    uniqueConstraints = @UniqueConstraint(columnNames = {"bucket", "object_key"})
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ObjectMetadata {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String bucket;

    @Column(name = "object_key", nullable = false)
    private String objectKey;

    @Column(nullable = false)
    private long totalSizeBytes;

    /** Base64-encoded MD5 of the original bytes. Verified on every GET to detect bit-rot. */
    @Column(nullable = false)
    private String checksum;

    /** Erasure coding parameters */
    private int dataShards;
    private int parityShards;

    /**
     * UUID prefix shared by all physical shard files for this object.
     * Physical shard file on node = baseShardId + "-shard-" + shardIndex
     */
    @Column(nullable = false)
    private String baseShardId;

    /**
     * JSON map: shardIndex (String) → nodeId (Integer).
     * Example: {"0":1,"1":2,"2":3,"3":4,"4":5,"5":6}
     * Drives both PUT placement and GET retrieval. Survives node rebalancing
     * because the map is stored at write time — GET never re-derives placement.
     */
    @Column(columnDefinition = "TEXT", nullable = false)
    private String shardLocations;

    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }
}
