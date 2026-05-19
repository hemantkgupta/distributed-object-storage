package com.systemdesign.objectstorage.multipart;

import com.systemdesign.objectstorage.model.ObjectMetadata;
import com.systemdesign.objectstorage.repository.MetadataRepository;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

abstract class NoopMetadataRepository implements MetadataRepository {
    @Override public Optional<ObjectMetadata> findByBucketAndObjectKey(String b, String k) { throw new UnsupportedOperationException(); }
    @Override public void deleteByBucketAndObjectKey(String b, String k) { throw new UnsupportedOperationException(); }
    @Override public <S extends ObjectMetadata> S save(S e) { throw new UnsupportedOperationException(); }
    @Override public <S extends ObjectMetadata> List<S> saveAll(Iterable<S> e) { throw new UnsupportedOperationException(); }
    @Override public Optional<ObjectMetadata> findById(String id) { throw new UnsupportedOperationException(); }
    @Override public boolean existsById(String id) { throw new UnsupportedOperationException(); }
    @Override public List<ObjectMetadata> findAll() { throw new UnsupportedOperationException(); }
    @Override public List<ObjectMetadata> findAllById(Iterable<String> ids) { throw new UnsupportedOperationException(); }
    @Override public long count() { throw new UnsupportedOperationException(); }
    @Override public void deleteById(String id) { throw new UnsupportedOperationException(); }
    @Override public void delete(ObjectMetadata e) { throw new UnsupportedOperationException(); }
    @Override public void deleteAllById(Iterable<? extends String> ids) { throw new UnsupportedOperationException(); }
    @Override public void deleteAll(Iterable<? extends ObjectMetadata> entities) { throw new UnsupportedOperationException(); }
    @Override public void deleteAll() { throw new UnsupportedOperationException(); }
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
    @Override public <S extends ObjectMetadata> Optional<S> findOne(Example<S> example) { throw new UnsupportedOperationException(); }
    @Override public <S extends ObjectMetadata> List<S> findAll(Example<S> example) { throw new UnsupportedOperationException(); }
    @Override public <S extends ObjectMetadata> List<S> findAll(Example<S> example, Sort sort) { throw new UnsupportedOperationException(); }
    @Override public <S extends ObjectMetadata> Page<S> findAll(Example<S> example, Pageable pageable) { throw new UnsupportedOperationException(); }
    @Override public <S extends ObjectMetadata> long count(Example<S> example) { throw new UnsupportedOperationException(); }
    @Override public <S extends ObjectMetadata> boolean exists(Example<S> example) { throw new UnsupportedOperationException(); }
    @Override public <S extends ObjectMetadata, R> R findBy(Example<S> example, Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> fn) { throw new UnsupportedOperationException(); }
}
