package com.systemdesign.objectstorage.repair;

import com.systemdesign.objectstorage.model.RepairPriority;
import com.systemdesign.objectstorage.model.RepairTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the {@link InMemoryRepairTaskQueue}. Two properties matter:
 * higher-priority tasks drain first, and within a priority class older tasks
 * (lower nextRunAt) come first.
 */
class InMemoryRepairTaskQueueTest {

    private InMemoryRepairTaskQueue queue;
    private Instant now;

    @BeforeEach
    void setUp() {
        queue = new InMemoryRepairTaskQueue();
        now = Instant.parse("2026-05-20T12:00:00Z");
    }

    @Test
    void criticalTasksDrainBeforeHighTasks() {
        queue.enqueue(task("t-normal", RepairPriority.NORMAL, now));
        queue.enqueue(task("t-high",   RepairPriority.HIGH,   now));
        queue.enqueue(task("t-crit",   RepairPriority.CRITICAL, now));

        List<RepairTask> due = queue.dueTasks(10, now);

        assertThat(due).extracting(RepairTask::taskId)
                .containsExactly("t-crit", "t-high", "t-normal");
    }

    @Test
    void withinPriorityOlderTasksComeFirst() {
        queue.enqueue(task("late",   RepairPriority.HIGH, now.plusSeconds(60)));
        queue.enqueue(task("early",  RepairPriority.HIGH, now));
        queue.enqueue(task("middle", RepairPriority.HIGH, now.plusSeconds(30)));

        List<RepairTask> due = queue.dueTasks(10, now.plusSeconds(120));

        assertThat(due).extracting(RepairTask::taskId)
                .containsExactly("early", "middle", "late");
    }

    @Test
    void notYetDueTasksAreOmitted() {
        queue.enqueue(task("future", RepairPriority.CRITICAL, now.plusSeconds(60)));
        queue.enqueue(task("ready",  RepairPriority.HIGH, now));

        List<RepairTask> due = queue.dueTasks(10, now);

        assertThat(due).extracting(RepairTask::taskId).containsExactly("ready");
    }

    @Test
    void depthReflectsTotalEnqueuedTasks() {
        queue.enqueue(task("a", RepairPriority.HIGH, now));
        queue.enqueue(task("b", RepairPriority.HIGH, now));
        assertThat(queue.depth()).isEqualTo(2);
    }

    @Test
    void markCompletedRemovesTaskFromQueue() {
        queue.enqueue(task("a", RepairPriority.HIGH, now));
        queue.enqueue(task("b", RepairPriority.HIGH, now));
        queue.markCompleted("a", null);
        assertThat(queue.depth()).isEqualTo(1);
    }

    @Test
    void rescheduleAdvancesNextRunAt() {
        queue.enqueue(task("a", RepairPriority.HIGH, now));
        queue.reschedule("a", now.plusSeconds(60));
        List<RepairTask> due = queue.dueTasks(10, now);
        assertThat(due).isEmpty();
    }

    @Test
    void maxTasksLimitIsHonoured() {
        for (int i = 0; i < 10; i++) {
            queue.enqueue(task("t-" + i, RepairPriority.HIGH, now));
        }
        List<RepairTask> due = queue.dueTasks(3, now);
        assertThat(due).hasSize(3);
    }

    private RepairTask task(String id, RepairPriority p, Instant nextRunAt) {
        return new RepairTask(id, "obj-" + id, -1, p, now, nextRunAt, 0);
    }
}
