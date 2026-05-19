package com.systemdesign.objectstorage.repository;

import com.systemdesign.objectstorage.model.ObjectMetadata;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface MetadataRepository extends JpaRepository<ObjectMetadata, String> {
    Optional<ObjectMetadata> findByBucketAndObjectKey(String bucket, String objectKey);
    void deleteByBucketAndObjectKey(String bucket, String objectKey);
}
