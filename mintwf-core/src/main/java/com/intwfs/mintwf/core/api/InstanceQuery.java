package com.intwfs.mintwf.core.api;

/**
 * Filters for listing process instances. A {@code null} field matches everything.
 */
public record InstanceQuery(String processKey, InstanceStatus status) {

    public static InstanceQuery all() {
        return new InstanceQuery(null, null);
    }

    public boolean matches(String processKey, InstanceStatus status) {
        return (this.processKey == null || this.processKey.equals(processKey))
                && (this.status == null || this.status == status);
    }
}
