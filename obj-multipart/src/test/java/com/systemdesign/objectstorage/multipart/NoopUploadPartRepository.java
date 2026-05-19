package com.systemdesign.objectstorage.multipart;

import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.*;
import java.util.function.Function;

abstract class NoopUploadPartRepository implements UploadPartRepository {
    @Override public List<UploadPart> findByUploadIdOrderByPartNumberAsc(String uploadId) { throw new UnsupportedOperationException(); }
    @Override public Optional<UploadPart> findByUploadIdAndPartNumber(String uploadId, int partNumber) { throw new UnsupportedOperationException(); }
    @Override public <S extends UploadPart> S save(S entity) { throw new UnsupportedOperationException(); }
    @Override public <S extends UploadPart> List<S> saveAll(Iterable<S> entities) { throw new UnsupportedOperationException(); }
    @Override public Optional<UploadPart> findById(String id) { throw new UnsupportedOperationException(); }
    @Override public boolean existsById(String id) { throw new UnsupportedOperationException(); }
    @Override public List<UploadPart> findAll() { throw new UnsupportedOperationException(); }
    @Override public List<UploadPart> findAllById(Iterable<String> ids) { throw new UnsupportedOperationException(); }
    @Override public long count() { throw new UnsupportedOperationException(); }
    @Override public void deleteById(String id) { throw new UnsupportedOperationException(); }
    @Override public void delete(UploadPart entity) { throw new UnsupportedOperationException(); }
    @Override public void deleteAllById(Iterable<? extends String> ids) { throw new UnsupportedOperationException(); }
    @Override public void deleteAll(Iterable<? extends UploadPart> entities) { throw new UnsupportedOperationException(); }
    @Override public void deleteAll() { throw new UnsupportedOperationException(); }
    @Override public List<UploadPart> findAll(Sort sort) { throw new UnsupportedOperationException(); }
    @Override public Page<UploadPart> findAll(Pageable pageable) { throw new UnsupportedOperationException(); }
    @Override public void flush() { /* no-op */ }
    @Override public <S extends UploadPart> S saveAndFlush(S entity) { return save(entity); }
    @Override public <S extends UploadPart> List<S> saveAllAndFlush(Iterable<S> entities) { throw new UnsupportedOperationException(); }
    @Override public void deleteAllInBatch(Iterable<UploadPart> entities) { throw new UnsupportedOperationException(); }
    @Override public void deleteAllByIdInBatch(Iterable<String> ids) { throw new UnsupportedOperationException(); }
    @Override public void deleteAllInBatch() { throw new UnsupportedOperationException(); }
    @Override public UploadPart getOne(String id) { throw new UnsupportedOperationException(); }
    @Override public UploadPart getById(String id) { throw new UnsupportedOperationException(); }
    @Override public UploadPart getReferenceById(String id) { throw new UnsupportedOperationException(); }
    @Override public <S extends UploadPart> Optional<S> findOne(Example<S> example) { throw new UnsupportedOperationException(); }
    @Override public <S extends UploadPart> List<S> findAll(Example<S> example) { throw new UnsupportedOperationException(); }
    @Override public <S extends UploadPart> List<S> findAll(Example<S> example, Sort sort) { throw new UnsupportedOperationException(); }
    @Override public <S extends UploadPart> Page<S> findAll(Example<S> example, Pageable pageable) { throw new UnsupportedOperationException(); }
    @Override public <S extends UploadPart> long count(Example<S> example) { throw new UnsupportedOperationException(); }
    @Override public <S extends UploadPart> boolean exists(Example<S> example) { throw new UnsupportedOperationException(); }
    @Override public <S extends UploadPart, R> R findBy(Example<S> example, Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> fn) { throw new UnsupportedOperationException(); }
}
