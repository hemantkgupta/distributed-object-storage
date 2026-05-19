package com.systemdesign.objectstorage.multipart;

import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;

/**
 * Test-only base implementing every JpaRepository method as unsupported, so fake
 * repositories can override just the methods the unit under test invokes.
 */
abstract class NoopMultipartUploadRepository implements MultipartUploadRepository {
    @Override public Optional<MultipartUpload> findByIdAndStatus(String id, MultipartUpload.UploadStatus status) { throw new UnsupportedOperationException(); }
    @Override public List<MultipartUpload> findByStatusAndCreatedAtBefore(MultipartUpload.UploadStatus status, Instant cutoff) { throw new UnsupportedOperationException(); }
    @Override public <S extends MultipartUpload> S save(S entity) { throw new UnsupportedOperationException(); }
    @Override public <S extends MultipartUpload> List<S> saveAll(Iterable<S> entities) { throw new UnsupportedOperationException(); }
    @Override public Optional<MultipartUpload> findById(String id) { throw new UnsupportedOperationException(); }
    @Override public boolean existsById(String id) { throw new UnsupportedOperationException(); }
    @Override public List<MultipartUpload> findAll() { throw new UnsupportedOperationException(); }
    @Override public List<MultipartUpload> findAllById(Iterable<String> ids) { throw new UnsupportedOperationException(); }
    @Override public long count() { throw new UnsupportedOperationException(); }
    @Override public void deleteById(String id) { throw new UnsupportedOperationException(); }
    @Override public void delete(MultipartUpload entity) { throw new UnsupportedOperationException(); }
    @Override public void deleteAllById(Iterable<? extends String> ids) { throw new UnsupportedOperationException(); }
    @Override public void deleteAll(Iterable<? extends MultipartUpload> entities) { throw new UnsupportedOperationException(); }
    @Override public void deleteAll() { throw new UnsupportedOperationException(); }
    @Override public List<MultipartUpload> findAll(Sort sort) { throw new UnsupportedOperationException(); }
    @Override public Page<MultipartUpload> findAll(Pageable pageable) { throw new UnsupportedOperationException(); }
    @Override public void flush() { /* no-op */ }
    @Override public <S extends MultipartUpload> S saveAndFlush(S entity) { return save(entity); }
    @Override public <S extends MultipartUpload> List<S> saveAllAndFlush(Iterable<S> entities) { throw new UnsupportedOperationException(); }
    @Override public void deleteAllInBatch(Iterable<MultipartUpload> entities) { throw new UnsupportedOperationException(); }
    @Override public void deleteAllByIdInBatch(Iterable<String> ids) { throw new UnsupportedOperationException(); }
    @Override public void deleteAllInBatch() { throw new UnsupportedOperationException(); }
    @Override public MultipartUpload getOne(String id) { throw new UnsupportedOperationException(); }
    @Override public MultipartUpload getById(String id) { throw new UnsupportedOperationException(); }
    @Override public MultipartUpload getReferenceById(String id) { throw new UnsupportedOperationException(); }
    @Override public <S extends MultipartUpload> Optional<S> findOne(Example<S> example) { throw new UnsupportedOperationException(); }
    @Override public <S extends MultipartUpload> List<S> findAll(Example<S> example) { throw new UnsupportedOperationException(); }
    @Override public <S extends MultipartUpload> List<S> findAll(Example<S> example, Sort sort) { throw new UnsupportedOperationException(); }
    @Override public <S extends MultipartUpload> Page<S> findAll(Example<S> example, Pageable pageable) { throw new UnsupportedOperationException(); }
    @Override public <S extends MultipartUpload> long count(Example<S> example) { throw new UnsupportedOperationException(); }
    @Override public <S extends MultipartUpload> boolean exists(Example<S> example) { throw new UnsupportedOperationException(); }
    @Override public <S extends MultipartUpload, R> R findBy(Example<S> example, Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> fn) { throw new UnsupportedOperationException(); }
}
