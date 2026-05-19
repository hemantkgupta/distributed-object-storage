package com.systemdesign.objectstorage.storage;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the {@link ShardId} record: equality, hash, accessors, and validation.
 */
class ShardIdTest {

    @Test
    void equalShardIdsAreEqualByValue() {
        ShardId a = new ShardId(7, "abc-shard-3");
        ShardId b = new ShardId(7, "abc-shard-3");
        assertThat(a).isEqualTo(b);
    }

    @Test
    void equalShardIdsShareHashCode() {
        ShardId a = new ShardId(7, "abc-shard-3");
        ShardId b = new ShardId(7, "abc-shard-3");
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void differentNodeIdProducesUnequalShardId() {
        ShardId a = new ShardId(1, "abc-shard-3");
        ShardId b = new ShardId(2, "abc-shard-3");
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void differentPhysicalIdProducesUnequalShardId() {
        ShardId a = new ShardId(1, "abc-shard-3");
        ShardId b = new ShardId(1, "xyz-shard-3");
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void accessorsReturnConstructorValues() {
        ShardId id = new ShardId(42, "obj-shard-0");
        assertThat(id.nodeId()).isEqualTo(42);
        assertThat(id.physicalId()).isEqualTo("obj-shard-0");
    }

    @Test
    void toStringContainsBothFields() {
        ShardId id = new ShardId(7, "abc-shard-3");
        assertThat(id.toString()).contains("7").contains("abc-shard-3");
    }

    @Test
    void blankPhysicalIdIsRejected() {
        assertThatThrownBy(() -> new ShardId(1, ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullPhysicalIdIsRejected() {
        assertThatThrownBy(() -> new ShardId(1, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
