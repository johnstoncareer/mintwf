# Architecture

This document describes how mintwf is structured and why. It is the reference for contributors. When a design decision here changes, update this file in the same change.

## Constraints

- **Small, embeddable core.** `mintwf-core` is plain Java 25 with no runtime dependencies.
- **BPMN 2.0 definitions.** The engine runs a documented subset of BPMN 2.0. It rejects unsupported elements at deploy time instead of ignoring them.
- **No REST API.** Process commands are Claude skills, so the engine is reached through a command-line interface.
- **Long-running, failure-tolerant processes.** Instance state survives restarts, and calls to external systems are retried, compensated, or raised as fallout.

## How the pieces fit

```
 Claude skill ──► mintwf CLI ──┐                 ┌── mintwf worker (long-running)
 (SKILL.md)      (one-shot,    │                 │   polls due jobs: async service
                  JSON output) └──► H2 database ◄┘   tasks, timers, retries
```

- Each **CLI command** (`deploy`, `start`, `complete-task`, …) opens the store, runs one engine command in one transaction, prints JSON, and exits. Skills call the CLI and read its output.
- Asynchronous and time-based work (service task calls, timers, retries) is written to a `job` table. The **worker** is a long-running process (`mintwf worker`) that picks up due jobs and runs them.
- The CLI and worker coordinate only through the database. There is no server and no API between them.

## Modules

| Module | Contents | Dependencies |
|---|---|---|
| `mintwf-core` | Model, parser, runtime, SPIs, in-memory store | JDK only |
| `mintwf-store-jdbc` | JDBC `ProcessStore`, schema migrations | H2, Jackson (variable serialization) |
| `mintwf-handler-http` | The `http` task handler, discovered through `TaskHandlerProvider` | core, Jackson |
| `mintwf-cli` | CLI commands and the `worker` subcommand, packaged as a single executable jar | core, store-jdbc, handler-http, picocli, Jackson |
| `.claude/skills/*` | One `SKILL.md` per process command, each calling the CLI | none |

The HTTP handler is its own module because it needs a JSON library, which core must not depend on. It is also the model for third-party handler jars.

All modules exist. Packages that later phases fill contain only a `package-info.java` that says so.

### Packages outside core

| Package | Module | Responsibility | Phase |
|---|---|---|---|
| `com.intwfs.mintwf.store.jdbc` | `mintwf-store-jdbc` | `JdbcProcessStore` and schema migrations | 2 (done) |
| `com.intwfs.mintwf.handler.http` | `mintwf-handler-http` | `http` task handler | 2 (done) |
| `com.intwfs.mintwf.cli` | `mintwf-cli` | `mintwf` entry point and engine configuration (done); JSON output | 3 |
| `com.intwfs.mintwf.cli.command` | `mintwf-cli` | One command per process command skill | 3 |
| `com.intwfs.mintwf.cli.worker` | `mintwf-cli` | `mintwf worker`: runs due jobs until stopped | 2 (done) |

## Core packages

All code lives under `com.intwfs.mintwf.core`.

| Package | Responsibility |
|---|---|
| `model` | Immutable graph of a deployed process: `ProcessDefinition`, `FlowNode` subtypes, `SequenceFlow`. Keyed by `(processKey, version)`. |
| `parser` | Parses XML into a DOM while validating it against the bundled OMG XSDs, then builds the model. Rejects unsupported elements with the offending element id. |
| `expression` | `SimpleExpressionEvaluator`, the built-in condition language. |
| `runtime` | `Interpreter` (the token interpreter), `InMemoryProcessStore`, and variable validation. Internal. |
| `api` | `ProcessEngine` facade: `deploy`, `start`, `completeTask`, `cancel`, `retryIncident`, queries, and `executeDueJobs`, with `correlateMessage` and `sendSignal` to come. Maps one-to-one to the skills. |
| `spi` | Extension points: `ProcessStore`, `TaskHandler`, `TaskHandlerProvider`, `ExpressionEvaluator`. Also the persisted records `InstanceState`, `Execution`, `Job`, and `DeploymentRecord`, and `InstanceChange`, the unit a store saves atomically. Time comes from an injectable `java.time.Clock`. |
| `job` | `RetryPolicy` and `JobWorker`, the background loop that calls `executeDueJobs`. |

## Runtime semantics

### Execution model

A process instance holds a set of **executions** (tokens), each positioned on a flow node, plus a variable scope. Execution ids are sequential numbers within the instance. A token on a `userTask` or `receiveTask` is an open task, and its execution id is the task id.

How each node type treats a token:

- **Start event, service task, user task, receive task:** leave by every outgoing flow whose condition is true or absent. If none applies, leave by the `default` flow.
- **Exclusive gateway:** leave by the first outgoing flow, in document order, whose condition is true. If none applies, leave by the `default` flow.
- **Parallel gateway:** wait until a token has arrived on every incoming flow, merge them, then leave by every outgoing flow.
- **End event:** consume the token. The instance completes when no tokens remain.

One command visits at most 10,000 nodes, which stops loops that never reach a wait state.

A command runs the instance synchronously until every token is at a wait state:

- `userTask` and `receiveTask`
- message, signal, and timer catch events
- an async job (see below)

The resulting state is then saved in one transaction. A failure before the save rolls the instance back to its previous wait state.

### Service tasks

A `serviceTask` is bound to a `TaskHandler` by a `mintwf:type` extension attribute, with handler-specific configuration in extension elements. Handlers are discovered with `ServiceLoader`, so telecom-specific adapters can ship as separate jars. The first built-in handler is `http`, using `java.net.http`.

Service tasks are **asynchronous by default**. Reaching one creates a job in the same transaction as the instance change, and the token waits on the task. A worker then:

1. Claims the job by setting a lock owner and expiry (5 minutes by default).
2. Runs the handler outside any transaction, against a copy of the variables.
3. Loads the latest instance state, applies the variables the handler set, moves the token on, and deletes the job, all in one save. If that save loses a race with another command, it reloads and applies again without calling the handler a second time.

If the handler fails:

- The job is rescheduled with exponential backoff (`RetryPolicy`; by default 3 attempts, waiting 10 s and then 20 s).
- When no attempts are left, the job becomes an **incident**, which is the fallout case. An incident is a job with zero retries left, so it needs no table of its own. Incidents appear in `get-instance` and are cleared with `retry-incident`, which gives the job a fresh set of attempts.

Delivery is at least once. If a worker dies after its handler succeeded but before the save, the lock expires and the job runs again, so handlers should be idempotent.

A service task with `mintwf:async="false"` runs inside the command that reaches it instead. A failure then fails the command and leaves the instance unchanged, and there are no retries.

### Expressions

`conditionExpression` uses a small built-in language: variable paths, comparisons, `&&`, `||`, `!`, and literals. It is plugged in through `ExpressionEvaluator`, so a richer language (such as FEEL) can be added without changing the core.

### Variables

Variable values are limited to JSON types: string, number, boolean, null, list, and map. They pass unchanged through the CLI, the database, and the skills. The JDBC store reads decimal numbers back as `BigDecimal`, so no precision is lost.

### Versioning

Deploying stores the BPMN XML and its content hash. Deploying changed content under an existing process key creates a new version. Redeploying identical content is a no-op. An instance stays on the version it started with.

### Compensation

The engine records every completed activity that has a compensation handler. On `cancel`, or on a compensation throw event, the handlers run in reverse completion order.

## Persistence

`mintwf-store-jdbc` targets **H2** in embedded file mode with `AUTO_SERVER=TRUE`, so the CLI and the worker can open the same database file concurrently.

Each instance's runtime state (executions and variables) is stored as **one JSON document** with a `revision` column for optimistic locking. Fields that are used in queries are also stored as columns. A command loads the instance, applies the change, and saves only if `revision` is unchanged. On a conflict it reloads and retries.

Tables are prefixed `mintwf_`. When a store opens, numbered scripts in `mintwf-store-jdbc` create or upgrade the schema and record the version in `mintwf_schema`.

| Table | Purpose | Status |
|---|---|---|
| `mintwf_deployment` | Process key, version, BPMN XML, content hash, deploy time | Done |
| `mintwf_instance` | Id, process key and version, business key, status, timestamps, `doc` (JSON), `revision` | Done |
| `mintwf_job` | Type, instance, execution, node, due time, retries left, last error, lock owner, lock expiry. Rows with zero retries are incidents. | Done |
| `subscription` | Waiting message name, signal name, or timer due time, with the instance and execution. Used by `correlate-message`, `send-signal`, and the timer scan. | Phase 4 |
| `history` | Append-only log of node entry and exit, for diagnosing fallout | Not scheduled yet |

A worker claims a job with `UPDATE mintwf_job SET lock_owner = ?, lock_expiry = ? WHERE id = ? AND retries > 0 AND due_at <= ? AND (lock_owner IS NULL OR lock_expiry < ?)`. When two workers race for a job, only one update succeeds. An expired lock can be reclaimed, so a crashed worker's jobs are picked up again.

## Delivery phases

| Phase | Scope |
|---|---|
| 1. Core, in memory | Start and end events, `sequenceFlow`, `serviceTask` (synchronous), `userTask`, `receiveTask`, `exclusiveGateway`, `parallelGateway`; the parser with subset validation; the in-memory store |
| 2. Durability | `mintwf-store-jdbc`, the job model, async service tasks, retries, incidents, the worker |
| 3. CLI and skills | `mintwf-cli` and all process command skills, including `retry-incident` |
| 4. Events | Message and signal catch events, intermediate and boundary timers, boundary error events |
| 5. Structure | Compensation, `subProcess`, `callActivity` |

Each phase adds its supported elements to the BPMN subset documented in the README.

## Decisions

| Decision | Choice | Reason |
|---|---|---|
| How skills reach the engine | One-shot CLI and a worker sharing a database | Nothing to host or secure. Considered instead: a daemon reached over a local socket, which adds an IPC layer. |
| Default database | H2 embedded, `AUTO_SERVER=TRUE` | Pure Java, so no native driver. Auto-server mode allows the CLI and worker to share the file. |
| Dependency management | JUnit and Jackson BOMs plus explicitly pinned versions | The project no longer uses Spring, so it no longer imports the Spring Boot BOM. |
| Incidents | A job with zero retries left, not a separate table | Retrying is one update, and a cancelled instance's incidents go with its jobs. |
| BPMN parsing | DOM with schema validation during the parse | Process files are small. A DOM makes the subset checks simple, and validating while parsing needs only one pass. |
