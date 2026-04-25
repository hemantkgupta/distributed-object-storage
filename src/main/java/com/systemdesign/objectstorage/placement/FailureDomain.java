package com.systemdesign.objectstorage.placement;

/**
 * A failure domain label for a storage node. Two nodes in the same failure domain
 * can fail together (same rack, same power circuit, same availability zone).
 *
 * <p>The placement policy uses failure domains to ensure that no two shards of the
 * same object are placed in the same domain — preventing correlated failures from
 * exceeding the erasure coding tolerance.
 *
 * @param type  the domain type (e.g., "rack", "power", "az")
 * @param value the domain identifier (e.g., "rack-1", "us-east-1a")
 */
public record FailureDomain(String type, String value) {
    public FailureDomain {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("failure domain type must not be blank");
        }
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("failure domain value must not be blank");
        }
    }
}
