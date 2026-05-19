package com.systemdesign.objectstorage.repair;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.systemdesign.objectstorage.erasure.ErasureCodingService;
import com.systemdesign.objectstorage.model.*;
import com.systemdesign.objectstorage.repository.MetadataRepository;
import com.systemdesign.objectstorage.storage.StorageNode;
import com.systemdesign.objectstorage.storage.StorageNodeHealth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the repair orchestrator's end-to-end behaviour: surviving shards are
 * read, the missing shard is reconstructed, and a replacement is placed on a
 * free node. Metadata is updated with the new location.
 */
class ErasureRepairOrchestratorTest {

    private ErasureRepairOrchestrator orchestrator;
    private Map<Integer, StorageNode> nodes;
    private FakeMetadataRepo metadataRepo;
    private InMemoryRepairLeaseStore leaseStore;
    private ErasureCodingService ec;

    @BeforeEach
    void setUp() {
        nodes = new LinkedHashMap<>();
        for (int i = 1; i <= 8; i++) {
            nodes.put(i, new TestNode(i));
        }
        metadataRepo = new FakeMetadataRepo();
        leaseStore = new InMemoryRepairLeaseStore();
        ec = new ErasureCodingService();
        orchestrator = new ErasureRepairOrchestrator(nodes, metadataRepo, ec, leaseStore, "worker-1");
    }

    @Test
    void missingShardIsReconstructedOnReplacementNode() throws Exception {
        byte[] payload = "repair-me".getBytes(StandardCharsets.UTF_8);
        ObjectMetadata meta = encodeAndStore(payload, 1, 2, 3, 4, 5, 6);

        // Drop shard 0 from node 1
        ((TestNode) nodes.get(1)).deleteShard(meta.getBaseShardId() + "-shard-0");

        RepairTask task = new RepairTask(
                "task-1", meta.getId(), 0, RepairPriority.HIGH,
                Instant.now(), Instant.now(), 0);

        RepairResult result = orchestrator.repair(task, RepairBudget.defaults());

        assertThat(result.outcome()).isEqualTo(RepairOutcome.REPAIRED);
        assertThat(result.shardsWritten()).isEqualTo(1);
    }

    @Test
    void repairUpdatesMetadataWithNewLocation() throws Exception {
        byte[] payload = "loc-update".getBytes(StandardCharsets.UTF_8);
        ObjectMetadata meta = encodeAndStore(payload, 1, 2, 3, 4, 5, 6);
        ((TestNode) nodes.get(3)).deleteShard(meta.getBaseShardId() + "-shard-2");

        RepairTask task = new RepairTask(
                "task-2", meta.getId(), 2, RepairPriority.HIGH,
                Instant.now(), Instant.now(), 0);
        orchestrator.repair(task, RepairBudget.defaults());

        var updated = metadataRepo.findById(meta.getId()).orElseThrow();
        Map<String, Integer> locations = new ObjectMapper().readValue(
                updated.getShardLocations(),
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Integer>>() {});
        // Shard 2's recorded node is no longer node 3.
        assertThat(locations.get("2")).isNotEqualTo(3);
    }

    @Test
    void insufficientShardsReturnsInsufficientShardsOutcome() throws Exception {
        byte[] payload = "unrecoverable".getBytes(StandardCharsets.UTF_8);
        ObjectMetadata meta = encodeAndStore(payload, 1, 2, 3, 4, 5, 6);
        // Drop 3 shards → only 3 of 6 remain, below k=4.
        ((TestNode) nodes.get(1)).deleteShard(meta.getBaseShardId() + "-shard-0");
        ((TestNode) nodes.get(2)).deleteShard(meta.getBaseShardId() + "-shard-1");
        ((TestNode) nodes.get(3)).deleteShard(meta.getBaseShardId() + "-shard-2");

        RepairTask task = new RepairTask(
                "task-insuf", meta.getId(), 0, RepairPriority.CRITICAL,
                Instant.now(), Instant.now(), 0);
        RepairResult result = orchestrator.repair(task, RepairBudget.defaults());

        assertThat(result.outcome()).isEqualTo(RepairOutcome.INSUFFICIENT_SHARDS);
    }

    @Test
    void leaseConflictReturnsLeaseConflictOutcome() {
        ObjectMetadata meta = new ObjectMetadata();
        meta.setId("o-1");
        metadataRepo.save(meta);

        // Pre-acquire the lease as a different worker.
        leaseStore.tryAcquire("task-3", "other-worker", java.time.Duration.ofMinutes(5));

        RepairTask task = new RepairTask(
                "task-3", "o-1", 0, RepairPriority.HIGH,
                Instant.now(), Instant.now(), 0);
        RepairResult result = orchestrator.repair(task, RepairBudget.defaults());

        assertThat(result.outcome()).isEqualTo(RepairOutcome.LEASE_CONFLICT);
    }

    @Test
    void missingObjectMetadataReturnsFailed() {
        RepairTask task = new RepairTask(
                "task-x", "nonexistent-object", 0, RepairPriority.HIGH,
                Instant.now(), Instant.now(), 0);

        RepairResult result = orchestrator.repair(task, RepairBudget.defaults());

        assertThat(result.outcome()).isEqualTo(RepairOutcome.FAILED);
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private ObjectMetadata encodeAndStore(byte[] payload, int... nodeIds) throws Exception {
        byte[][] shards = ec.encode(payload);
        String baseShardId = "obj-" + UUID.randomUUID();

        Map<String, Integer> locations = new LinkedHashMap<>();
        for (int i = 0; i < shards.length; i++) {
            int nodeId = nodeIds[i];
            String physicalId = baseShardId + "-shard-" + i;
            nodes.get(nodeId).writeShard(physicalId, shards[i]);
            locations.put(String.valueOf(i), nodeId);
        }

        ObjectMetadata meta = ObjectMetadata.builder()
                .bucket("b")
                .objectKey("k-" + baseShardId)
                .totalSizeBytes(payload.length)
                .checksum(md5(payload))
                .dataShards(ErasureCodingService.DATA_SHARDS)
                .parityShards(ErasureCodingService.PARITY_SHARDS)
                .baseShardId(baseShardId)
                .shardLocations(new ObjectMapper().writeValueAsString(locations))
                .build();
        meta.setId("o-" + baseShardId);
        metadataRepo.save(meta);
        return meta;
    }

    private static String md5(byte[] data) throws Exception {
        return Base64.getEncoder().encodeToString(MessageDigest.getInstance("MD5").digest(data));
    }

    // ── Test doubles ───────────────────────────────────────────────────────

    static final class TestNode implements StorageNode {
        private final int nodeId;
        private final Map<String, byte[]> shards = new HashMap<>();
        TestNode(int nodeId) { this.nodeId = nodeId; }
        @Override public int nodeId() { return nodeId; }
        @Override public void writeShard(String id, byte[] data) { shards.put(id, data.clone()); }
        @Override public byte[] readShard(String id) throws IOException {
            byte[] d = shards.get(id);
            if (d == null) throw new IOException("missing: " + id);
            return d;
        }
        @Override public void deleteShard(String id) { shards.remove(id); }
        @Override public boolean shardExists(String id) { return shards.containsKey(id); }
        @Override public List<String> listShards() { return new ArrayList<>(shards.keySet()); }
        @Override public byte[] shardChecksum(String id) throws IOException {
            try {
                return MessageDigest.getInstance("MD5").digest(readShard(id));
            } catch (Exception e) { throw new IOException(e); }
        }
        @Override public StorageNodeHealth health() {
            return new StorageNodeHealth(nodeId, 1_000_000L, 0L, shards.size(), true);
        }
    }

    static final class FakeMetadataRepo implements MetadataRepository {
        private final Map<String, ObjectMetadata> rows = new LinkedHashMap<>();
        @Override public Optional<ObjectMetadata> findByBucketAndObjectKey(String b, String k) {
            return rows.values().stream()
                    .filter(m -> m.getBucket().equals(b) && m.getObjectKey().equals(k))
                    .findFirst();
        }
        @Override public void deleteByBucketAndObjectKey(String b, String k) { /* unused */ }
        @Override public <S extends ObjectMetadata> S save(S e) {
            if (e.getId() == null) e.setId(UUID.randomUUID().toString());
            rows.put(e.getId(), e);
            return e;
        }
        @Override public Optional<ObjectMetadata> findById(String id) { return Optional.ofNullable(rows.get(id)); }
        @Override public void delete(ObjectMetadata e) { rows.remove(e.getId()); }
        @Override public List<ObjectMetadata> findAll() { return new ArrayList<>(rows.values()); }
        @Override public long count() { return rows.size(); }
        @Override public boolean existsById(String id) { return rows.containsKey(id); }
        @Override public void deleteById(String id) { rows.remove(id); }
        // ── Unused JpaRepository surface ───────────────────────────────────
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
}
