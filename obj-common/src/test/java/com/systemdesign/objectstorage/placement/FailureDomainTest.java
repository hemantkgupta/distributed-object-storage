package com.systemdesign.objectstorage.placement;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link FailureDomain}: equality semantics, accessors, validation.
 */
class FailureDomainTest {

    @Test
    void equalDomainsAreEqualByTypeAndValue() {
        FailureDomain a = new FailureDomain("rack", "rack-1");
        FailureDomain b = new FailureDomain("rack", "rack-1");
        assertThat(a).isEqualTo(b);
    }

    @Test
    void equalDomainsShareHashCode() {
        FailureDomain a = new FailureDomain("rack", "rack-1");
        FailureDomain b = new FailureDomain("rack", "rack-1");
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void differentValueProducesUnequalDomain() {
        FailureDomain a = new FailureDomain("rack", "rack-1");
        FailureDomain b = new FailureDomain("rack", "rack-2");
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void differentTypeProducesUnequalDomain() {
        FailureDomain a = new FailureDomain("rack", "rack-1");
        FailureDomain b = new FailureDomain("az", "rack-1");
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void accessorsReturnConstructorValues() {
        FailureDomain fd = new FailureDomain("az", "us-east-1a");
        assertThat(fd.type()).isEqualTo("az");
        assertThat(fd.value()).isEqualTo("us-east-1a");
    }

    @Test
    void blankTypeIsRejected() {
        assertThatThrownBy(() -> new FailureDomain("", "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankValueIsRejected() {
        assertThatThrownBy(() -> new FailureDomain("rack", ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullFieldsAreRejected() {
        assertThatThrownBy(() -> new FailureDomain(null, "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FailureDomain("rack", null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
