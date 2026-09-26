/**
 * Asynchronous jobs: service task calls, timers, and retries with backoff, and the executor that claims and runs due
 * jobs. A job that runs out of retries raises an incident.
 *
 * <p>Planned for phase 2.
 */
package com.intwfs.mintwf.core.job;
