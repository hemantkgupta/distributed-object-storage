package com.systemdesign.objectstorage.multipart;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "upload_part")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UploadPart {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    /** FK to multipart_upload.id */
    @Column(name = "upload_id", nullable = false)
    private String uploadId;

    /** 1-indexed, matching S3 convention */
    @Column(nullable = false)
    private int partNumber;

    @Column(nullable = false)
    private long sizeBytes;

    /** Base64-encoded MD5 of the raw part bytes — returned to client as ETag */
    @Column(nullable = false)
    private String etag;

    /** UUID prefix for physical shard file names belonging to this part */
    @Column(nullable = false)
    private String baseShardId;

    /**
     * JSON map: shardIndex → nodeId for this part's shards.
     * Example: {"0":3,"1":1,"2":5,"3":2,"4":6,"5":4}
     */
    @Column(columnDefinition = "TEXT", nullable = false)
    private String shardLocations;

    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }
}
