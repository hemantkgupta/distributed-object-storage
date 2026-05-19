package com.systemdesign.objectstorage.gc;

import com.systemdesign.objectstorage.model.GCResult;

/**
 * Cleans orphaned shards and abandoned multipart uploads.
 *
 * <p>Design from MinIO research: quarantine before purge.
 * Immediate deletion can destroy the only recoverable copy if metadata
 * is lagging or the repair orchestrator hasn't finished.
 */
public interface GarbageCollector {

    /**
     * Runs one garbage collection pass.
     *
     * @param policy grace periods and age thresholds
     * @return counts of what was cleaned
     */
    GCResult collect(GCPolicy policy);
}
