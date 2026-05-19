package com.systemdesign.objectstorage.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.stream.Stream;

/**
 * Storage node implementation backed by a local filesystem directory.
 *
 * <p>Each node stores shards as flat files in {@code {basePath}/node-{nodeId}/}.
 * This simulates distinct physical nodes using separate directories — the
 * contract is identical to a production implementation backed by raw block devices.
 */
public class LocalFilesystemStorageNode implements StorageNode {

    private final int nodeId;
    private final Path nodeDir;

    public LocalFilesystemStorageNode(int nodeId, Path basePath) {
        this.nodeId = nodeId;
        this.nodeDir = basePath.resolve("node-" + nodeId);
    }

    @Override
    public int nodeId() {
        return nodeId;
    }

    @Override
    public void writeShard(String physicalShardId, byte[] data) throws IOException {
        if (!Files.exists(nodeDir)) {
            Files.createDirectories(nodeDir);
        }
        Files.write(nodeDir.resolve(physicalShardId), data);
    }

    @Override
    public byte[] readShard(String physicalShardId) throws IOException {
        return Files.readAllBytes(nodeDir.resolve(physicalShardId));
    }

    @Override
    public void deleteShard(String physicalShardId) throws IOException {
        Files.deleteIfExists(nodeDir.resolve(physicalShardId));
    }

    @Override
    public boolean shardExists(String physicalShardId) {
        return Files.exists(nodeDir.resolve(physicalShardId));
    }

    @Override
    public List<String> listShards() throws IOException {
        if (!Files.exists(nodeDir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(nodeDir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .toList();
        }
    }

    @Override
    public byte[] shardChecksum(String physicalShardId) throws IOException {
        byte[] data = readShard(physicalShardId);
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            return md.digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 not available", e);
        }
    }

    @Override
    public StorageNodeHealth health() {
        try {
            long capacity = nodeDir.toFile().getTotalSpace();
            long usable = nodeDir.toFile().getUsableSpace();
            int shardCount = listShards().size();
            return new StorageNodeHealth(nodeId, capacity, capacity - usable, shardCount, true);
        } catch (Exception e) {
            return new StorageNodeHealth(nodeId, 0, 0, 0, false);
        }
    }
}
