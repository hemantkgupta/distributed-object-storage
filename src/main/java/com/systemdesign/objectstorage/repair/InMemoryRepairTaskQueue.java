package com.systemdesign.objectstorage.repair;

import com.systemdesign.objectstorage.model.RepairResult;
import com.systemdesign.objectstorage.model.RepairTask;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory repair task queue for local development and testing.
 * Tasks are ordered by priority (CRITICAL > HIGH > NORMAL), then by nextRunAt.
 */
public class InMemoryRepairTaskQueue implements RepairTaskQueue {

    private final Map<String, RepairTask> tasks = new ConcurrentHashMap<>();

    @Override
    public void enqueue(RepairTask task) {
        tasks.put(task.taskId(), task);
    }

    @Override
    public List<RepairTask> dueTasks(int maxTasks, Instant now) {
        return tasks.values().stream()
                .filter(t -> t.isDue(now))
                .sorted(Comparator
                        .comparing(RepairTask::priority)
                        .thenComparing(RepairTask::nextRunAt))
                .limit(maxTasks)
                .toList();
    }

    @Override
    public void markCompleted(String taskId, RepairResult result) {
        tasks.remove(taskId);
    }

    @Override
    public void reschedule(String taskId, Instant nextRunAt) {
        RepairTask existing = tasks.get(taskId);
        if (existing != null) {
            tasks.put(taskId, existing.withNextAttempt(nextRunAt));
        }
    }

    @Override
    public int depth() {
        return tasks.size();
    }
}
