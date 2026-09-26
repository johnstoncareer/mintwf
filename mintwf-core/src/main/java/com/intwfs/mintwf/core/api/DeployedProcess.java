package com.intwfs.mintwf.core.api;

import java.time.Instant;

/**
 * The result of a deployment.
 *
 * @param name the process display name, or {@code null}
 * @param created {@code false} when the content matched the latest version and nothing was deployed
 */
public record DeployedProcess(String processKey, int version, String name, String hash, Instant deployedAt,
                              boolean created) {
}
