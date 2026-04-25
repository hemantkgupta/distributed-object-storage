package com.systemdesign.objectstorage.repair;

import com.systemdesign.objectstorage.model.RepairResult;
import com.systemdesign.objectstorage.model.RepairTask;

import java.time.Instant;
import java.util.List;

/**
 * Queue of pending repair tasks, ordered by priority and scheduled run time.
 */
public interface RepairTaskQueue {

    /** Adds a repair task to the queue. */
    void enqueue(RepairTask task);

    /** Returns tasks that are due to run (nextRunAt <= now), up to maxTasks, ordered by priority. */
    List<RepairTask> dueTasks(int maxTasks, Instant now);

    /** Marks a task as completed with the given result. Removes it from the queue. */
    void markCompleted(String taskId, RepairResult result);

    /** Reschedules a task for a later run (e.g., after budget exhaustion or transient failure). */
    void reschedule(String taskId, Instant nextRunAt);

    /** Returns the current queue depth. */
    int depth();
}
