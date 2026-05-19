package com.systemdesign.objectstorage.service;

import org.springframework.stereotype.Service;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Service
public class NodeStorageService {
    
    // Simulating distinct physical nodes by writing to distinct local directories.
    private static final String BASE_PATH = System.getProperty("user.dir") + "/data/storage-nodes/node-";

    public void writeShard(int nodeId, String shardId, byte[] data) throws IOException {
        Path nodeDir = Paths.get(BASE_PATH + nodeId);
        if (!Files.exists(nodeDir)) {
            Files.createDirectories(nodeDir);
        }
        Path targetFile = nodeDir.resolve(shardId);
        Files.write(targetFile, data);
    }

    public byte[] readShard(int nodeId, String shardId) throws IOException {
        Path targetFile = Paths.get(BASE_PATH + nodeId).resolve(shardId);
        return Files.readAllBytes(targetFile);
    }

    public void deleteShard(int nodeId, String shardId) throws IOException {
        Path targetFile = Paths.get(BASE_PATH + nodeId).resolve(shardId);
        Files.deleteIfExists(targetFile);
    }
    
    public boolean shardExists(int nodeId, String shardId) {
        return Files.exists(Paths.get(BASE_PATH + nodeId).resolve(shardId));
    }
}
