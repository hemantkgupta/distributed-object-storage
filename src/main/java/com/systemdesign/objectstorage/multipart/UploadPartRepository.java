package com.systemdesign.objectstorage.multipart;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UploadPartRepository extends JpaRepository<UploadPart, String> {
    List<UploadPart> findByUploadIdOrderByPartNumberAsc(String uploadId);
    Optional<UploadPart> findByUploadIdAndPartNumber(String uploadId, int partNumber);
}
