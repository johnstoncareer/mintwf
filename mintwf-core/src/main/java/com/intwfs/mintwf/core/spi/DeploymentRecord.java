package com.intwfs.mintwf.core.spi;

import java.time.Instant;
import java.util.Objects;

/**
 * One stored version of a process definition.
 *
 * @param name the process display name, or {@code null}
 * @param hash hex SHA-256 of {@code xml}, used to skip redeploying unchanged content
 * @param xml the BPMN document exactly as deployed
 */
public record DeploymentRecord(String processKey, int version, String name, String hash, byte[] xml,
                               Instant deployedAt) {

    public DeploymentRecord {
        Objects.requireNonNull(processKey, "processKey");
        Objects.requireNonNull(hash, "hash");
        Objects.requireNonNull(deployedAt, "deployedAt");
        xml = xml.clone();
    }

    @Override
    public byte[] xml() {
        return xml.clone();
    }
}
