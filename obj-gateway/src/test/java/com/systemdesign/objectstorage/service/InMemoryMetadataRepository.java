package com.systemdesign.objectstorage.service;

import com.systemdesign.objectstorage.model.ObjectMetadata;
import com.systemdesign.objectstorage.repository.MetadataRepository;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.*;
import java.util.function.Function;

/**
 * Test-only in-memory MetadataRepository. Throws on unimplemented JpaRepository
 * methods so unintended dependencies surface immediately.
 */
public final class InMemoryMetadataRepository implements MetadataRepository {

    private final Map<String, ObjectMetadata> rows = new LinkedHashMap<>();
    public boolean failNextSave = false;

    @Override
    public Optional<ObjectMetadata> findByBucketAndObjectKey(String bucket, String objectKey) {
        return rows.values().stream()
                .filter(m -> bucket.equals(m.getBucket()) && objectKey.equals(m.getObjectKey()))
                .findFirst();
    }

    @Override
    public void deleteByBucketAndObjectKey(String bucket, String objectKey) {
        rows.values().removeIf(m -> bucket.equals(m.getBucket()) && objectKey.equals(m.getObjectKey()));
    }

    @Override
    public <S extends ObjectMetadata> S save(S entity) {
        if (failNextSave) {
            failNextSave = false;
            throw new RuntimeException("simulated metadata save failure");
        }
        if (entity.getId() == null) entity.setId(UUID.randomUUID().toString());
        rows.put(entity.getId(), entity);
        return entity;
    }

    @Override public void delete(ObjectMetadata entity) { rows.remove(entity.getId()); }
    @Override public Optional<ObjectMetadata> findById(String id) { return Optional.ofNullable(rows.get(id)); }
    @Override public boolean existsById(String id) { return rows.containsKey(id); }
    @Override public List<ObjectMetadata> findAll() { return new ArrayList<>(rows.values()); }
    @Override public long count() { return rows.size(); }
    @Override public void deleteById(String id) { rows.remove(id); }

    // ── Unused JpaRepository surface ───────────────────────────────────────
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
