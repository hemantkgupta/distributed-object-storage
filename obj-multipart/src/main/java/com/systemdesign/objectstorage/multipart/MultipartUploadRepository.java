package com.systemdesign.objectstorage.multipart;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface MultipartUploadRepository extends JpaRepository<MultipartUpload, String> {
    Optional<MultipartUpload> findByIdAndStatus(String id, MultipartUpload.UploadStatus status);

    /** Used by GC service to find abandoned uploads older than a cutoff. */
    List<MultipartUpload> findByStatusAndCreatedAtBefore(MultipartUpload.UploadStatus status, Instant cutoff);
}
