package com.systemdesign.objectstorage.scrubber;

import com.systemdesign.objectstorage.model.RepairResult;
import com.systemdesign.objectstorage.model.RepairTask;
import com.systemdesign.objectstorage.repair.RepairTaskQueue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Recording RepairTaskQueue used by scrubber tests. Captures every enqueued
 * task so the test can assert on what the scrubber decided to repair.
 */
final class InMemoryRepairTaskQueue implements RepairTaskQueue {

    final List<RepairTask> enqueued = new ArrayList<>();

    @Override public void enqueue(RepairTask task) { enqueued.add(task); }
    @Override public List<RepairTask> dueTasks(int maxTasks, Instant now) { return List.copyOf(enqueued); }
    @Override public void markCompleted(String taskId, RepairResult result) { /* no-op */ }
    @Override public void reschedule(String taskId, Instant nextRunAt) { /* no-op */ }
    @Override public int depth() { return enqueued.size(); }
}
