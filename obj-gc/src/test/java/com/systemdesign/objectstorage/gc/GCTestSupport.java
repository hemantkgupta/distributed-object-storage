package com.systemdesign.objectstorage.gc;

import com.systemdesign.objectstorage.model.ObjectMetadata;
import com.systemdesign.objectstorage.multipart.MultipartUpload;
import com.systemdesign.objectstorage.multipart.MultipartUploadRepository;
import com.systemdesign.objectstorage.multipart.UploadPart;
import com.systemdesign.objectstorage.multipart.UploadPartRepository;
import com.systemdesign.objectstorage.repository.MetadataRepository;
import com.systemdesign.objectstorage.storage.StorageNode;
import com.systemdesign.objectstorage.storage.StorageNodeHealth;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;

/** Test doubles used by GC tests — kept as static nested classes for readability. */
final class GCTestSupport {

    private GCTestSupport() {}

    static final class InMemoryNode implements StorageNode {
        private final int nodeId;
        final Map<String, byte[]> shards = new LinkedHashMap<>();
        InMemoryNode(int nodeId) { this.nodeId = nodeId; }
        void put(String id, byte[] data) { shards.put(id, data); }
        @Override public int nodeId() { return nodeId; }
        @Override public void writeShard(String id, byte[] data) { shards.put(id, data); }
        @Override public byte[] readShard(String id) throws IOException {
            byte[] d = shards.get(id);
            if (d == null) throw new IOException("missing");
            return d;
        }
        @Override public void deleteShard(String id) { shards.remove(id); }
        @Override public boolean shardExists(String id) { return shards.containsKey(id); }
        @Override public List<String> listShards() { return new ArrayList<>(shards.keySet()); }
        @Override public byte[] shardChecksum(String id) { return new byte[0]; }
        @Override public StorageNodeHealth health() {
            return new StorageNodeHealth(nodeId, 1L, 0L, shards.size(), true);
        }
    }

    static final class FakeMetadataRepo implements MetadataRepository {
        final Map<String, ObjectMetadata> rows = new LinkedHashMap<>();
        @Override public Optional<ObjectMetadata> findByBucketAndObjectKey(String b, String k) { throw new UnsupportedOperationException(); }
        @Override public void deleteByBucketAndObjectKey(String b, String k) { /* unused */ }
        @Override public <S extends ObjectMetadata> S save(S e) {
            if (e.getId() == null) e.setId(UUID.randomUUID().toString());
            rows.put(e.getId(), e); return e;
        }
        @Override public List<ObjectMetadata> findAll() { return new ArrayList<>(rows.values()); }
        @Override public Optional<ObjectMetadata> findById(String id) { return Optional.ofNullable(rows.get(id)); }
        @Override public boolean existsById(String id) { return rows.containsKey(id); }
        @Override public long count() { return rows.size(); }
        @Override public void deleteById(String id) { rows.remove(id); }
        @Override public void delete(ObjectMetadata entity) { rows.remove(entity.getId()); }
        @Override public <S extends ObjectMetadata> List<S> saveAll(Iterable<S> e) { throw new UnsupportedOperationException(); }
        @Override public List<ObjectMetadata> findAllById(Iterable<String> ids) { throw new UnsupportedOperationException(); }
        @Override public void deleteAllById(Iterable<? extends String> ids) { throw new UnsupportedOperationException(); }
        @Override public void deleteAll(Iterable<? extends ObjectMetadata> entities) { throw new UnsupportedOperationException(); }
        @Override public void deleteAll() { rows.clear(); }
        @Override public List<ObjectMetadata> findAll(Sort sort) { throw new UnsupportedOperationException(); }
        @Override public Page<ObjectMetadata> findAll(Pageable pageable) { throw new UnsupportedOperationException(); }
        @Override public void flush() { /* no-op */ }
        @Override public <S extends ObjectMetadata> S saveAndFlush(S e) { return save(e); }
        @Override public <S extends ObjectMetadata> List<S> saveAllAndFlush(Iterable<S> e) { throw new UnsupportedOperationException(); }
        @Override public void deleteAllInBatch(Iterable<ObjectMetadata> e) { throw new UnsupportedOperationException(); }
        @Override public void deleteAllByIdInBatch(Iterable<String> ids) { throw new UnsupportedOperationException(); }
        @Override public void deleteAllInBatch() { throw new UnsupportedOperationException(); }
        @Override public ObjectMetadata getOne(String id) { throw new UnsupportedOperationException(); }
        @Override public ObjectMetadata getById(String id) { throw new UnsupportedOperationException(); }
        @Override public ObjectMetadata getReferenceById(String id) { throw new UnsupportedOperationException(); }
        @Override public <S extends ObjectMetadata> Optional<S> findOne(Example<S> e) { throw new UnsupportedOperationException(); }
        @Override public <S extends ObjectMetadata> List<S> findAll(Example<S> e) { throw new UnsupportedOperationException(); }
        @Override public <S extends ObjectMetadata> List<S> findAll(Example<S> e, Sort s) { throw new UnsupportedOperationException(); }
        @Override public <S extends ObjectMetadata> Page<S> findAll(Example<S> e, Pageable p) { throw new UnsupportedOperationException(); }
        @Override public <S extends ObjectMetadata> long count(Example<S> e) { throw new UnsupportedOperationException(); }
        @Override public <S extends ObjectMetadata> boolean exists(Example<S> e) { throw new UnsupportedOperationException(); }
        @Override public <S extends ObjectMetadata, R> R findBy(Example<S> e, Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> fn) { throw new UnsupportedOperationException(); }
    }

    static final class FakeUploadRepo implements MultipartUploadRepository {
        final Map<String, MultipartUpload> rows = new LinkedHashMap<>();
        @Override public Optional<MultipartUpload> findByIdAndStatus(String id, MultipartUpload.UploadStatus s) { throw new UnsupportedOperationException(); }
        @Override public List<MultipartUpload> findByStatusAndCreatedAtBefore(MultipartUpload.UploadStatus s, Instant cutoff) {
            return rows.values().stream()
                    .filter(u -> u.getStatus() == s)
                    .filter(u -> u.getCreatedAt() != null && u.getCreatedAt().isBefore(cutoff))
                    .toList();
        }
        @Override public <S extends MultipartUpload> S save(S e) {
            if (e.getId() == null) e.setId(UUID.randomUUID().toString());
            rows.put(e.getId(), e); return e;
        }
        @Override public Optional<MultipartUpload> findById(String id) { return Optional.ofNullable(rows.get(id)); }
        @Override public boolean existsById(String id) { return rows.containsKey(id); }
        @Override public List<MultipartUpload> findAll() { return new ArrayList<>(rows.values()); }
        @Override public long count() { return rows.size(); }
        @Override public void deleteById(String id) { rows.remove(id); }
        @Override public void delete(MultipartUpload e) { rows.remove(e.getId()); }
        @Override public <S extends MultipartUpload> List<S> saveAll(Iterable<S> e) { throw new UnsupportedOperationException(); }
        @Override public List<MultipartUpload> findAllById(Iterable<String> ids) { throw new UnsupportedOperationException(); }
        @Override public void deleteAllById(Iterable<? extends String> ids) { throw new UnsupportedOperationException(); }
        @Override public void deleteAll(Iterable<? extends MultipartUpload> entities) { throw new UnsupportedOperationException(); }
        @Override public void deleteAll() { rows.clear(); }
        @Override public List<MultipartUpload> findAll(Sort sort) { throw new UnsupportedOperationException(); }
        @Override public Page<MultipartUpload> findAll(Pageable pageable) { throw new UnsupportedOperationException(); }
        @Override public void flush() { /* no-op */ }
        @Override public <S extends MultipartUpload> S saveAndFlush(S e) { return save(e); }
        @Override public <S extends MultipartUpload> List<S> saveAllAndFlush(Iterable<S> e) { throw new UnsupportedOperationException(); }
        @Override public void deleteAllInBatch(Iterable<MultipartUpload> e) { throw new UnsupportedOperationException(); }
        @Override public void deleteAllByIdInBatch(Iterable<String> ids) { throw new UnsupportedOperationException(); }
        @Override public void deleteAllInBatch() { throw new UnsupportedOperationException(); }
        @Override public MultipartUpload getOne(String id) { throw new UnsupportedOperationException(); }
        @Override public MultipartUpload getById(String id) { throw new UnsupportedOperationException(); }
        @Override public MultipartUpload getReferenceById(String id) { throw new UnsupportedOperationException(); }
        @Override public <S extends MultipartUpload> Optional<S> findOne(Example<S> e) { throw new UnsupportedOperationException(); }
        @Override public <S extends MultipartUpload> List<S> findAll(Example<S> e) { throw new UnsupportedOperationException(); }
        @Override public <S extends MultipartUpload> List<S> findAll(Example<S> e, Sort s) { throw new UnsupportedOperationException(); }
        @Override public <S extends MultipartUpload> Page<S> findAll(Example<S> e, Pageable p) { throw new UnsupportedOperationException(); }
        @Override public <S extends MultipartUpload> long count(Example<S> e) { throw new UnsupportedOperationException(); }
        @Override public <S extends MultipartUpload> boolean exists(Example<S> e) { throw new UnsupportedOperationException(); }
        @Override public <S extends MultipartUpload, R> R findBy(Example<S> e, Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> fn) { throw new UnsupportedOperationException(); }
    }

    static final class FakePartRepo implements UploadPartRepository {
        final Map<String, UploadPart> rows = new LinkedHashMap<>();
        @Override public List<UploadPart> findByUploadIdOrderByPartNumberAsc(String uploadId) {
            return rows.values().stream()
                    .filter(p -> p.getUploadId().equals(uploadId))
                    .sorted(Comparator.comparingInt(UploadPart::getPartNumber))
                    .toList();
        }
        @Override public Optional<UploadPart> findByUploadIdAndPartNumber(String uploadId, int n) {
            return rows.values().stream()
                    .filter(p -> p.getUploadId().equals(uploadId) && p.getPartNumber() == n)
                    .findFirst();
        }
        @Override public <S extends UploadPart> S save(S e) {
            if (e.getId() == null) e.setId(UUID.randomUUID().toString());
            rows.put(e.getId(), e); return e;
        }
        @Override public Optional<UploadPart> findById(String id) { return Optional.ofNullable(rows.get(id)); }
        @Override public boolean existsById(String id) { return rows.containsKey(id); }
        @Override public List<UploadPart> findAll() { return new ArrayList<>(rows.values()); }
        @Override public long count() { return rows.size(); }
        @Override public void deleteById(String id) { rows.remove(id); }
        @Override public void delete(UploadPart e) { rows.remove(e.getId()); }
        @Override public void deleteAll(Iterable<? extends UploadPart> entities) {
            entities.forEach(this::delete);
        }
        @Override public <S extends UploadPart> List<S> saveAll(Iterable<S> e) { throw new UnsupportedOperationException(); }
        @Override public List<UploadPart> findAllById(Iterable<String> ids) { throw new UnsupportedOperationException(); }
        @Override public void deleteAllById(Iterable<? extends String> ids) { throw new UnsupportedOperationException(); }
        @Override public void deleteAll() { rows.clear(); }
        @Override public List<UploadPart> findAll(Sort sort) { throw new UnsupportedOperationException(); }
        @Override public Page<UploadPart> findAll(Pageable pageable) { throw new UnsupportedOperationException(); }
        @Override public void flush() { /* no-op */ }
        @Override public <S extends UploadPart> S saveAndFlush(S e) { return save(e); }
        @Override public <S extends UploadPart> List<S> saveAllAndFlush(Iterable<S> e) { throw new UnsupportedOperationException(); }
        @Override public void deleteAllInBatch(Iterable<UploadPart> e) { throw new UnsupportedOperationException(); }
        @Override public void deleteAllByIdInBatch(Iterable<String> ids) { throw new UnsupportedOperationException(); }
        @Override public void deleteAllInBatch() { throw new UnsupportedOperationException(); }
        @Override public UploadPart getOne(String id) { throw new UnsupportedOperationException(); }
        @Override public UploadPart getById(String id) { throw new UnsupportedOperationException(); }
        @Override public UploadPart getReferenceById(String id) { throw new UnsupportedOperationException(); }
        @Override public <S extends UploadPart> Optional<S> findOne(Example<S> e) { throw new UnsupportedOperationException(); }
        @Override public <S extends UploadPart> List<S> findAll(Example<S> e) { throw new UnsupportedOperationException(); }
        @Override public <S extends UploadPart> List<S> findAll(Example<S> e, Sort s) { throw new UnsupportedOperationException(); }
        @Override public <S extends UploadPart> Page<S> findAll(Example<S> e, Pageable p) { throw new UnsupportedOperationException(); }
        @Override public <S extends UploadPart> long count(Example<S> e) { throw new UnsupportedOperationException(); }
        @Override public <S extends UploadPart> boolean exists(Example<S> e) { throw new UnsupportedOperationException(); }
        @Override public <S extends UploadPart, R> R findBy(Example<S> e, Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> fn) { throw new UnsupportedOperationException(); }
    }
}
