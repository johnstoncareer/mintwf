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
| `mintwf-cli` | CLI commands and the `worker` subcommand, packaged as a single executable jar | core, store-jdbc, picocli |
| `.claude/skills/*` | One `SKILL.md` per process command, each calling the CLI | none |

Only `mintwf-core` exists today. The other modules are added in the phases below.

## Core packages

All code lives under `com.intwfs.mintwf.core`.

| Package | Responsibility |
|---|---|
| `model` | Immutable graph of a deployed process: `ProcessDefinition`, `FlowNode` subtypes, `SequenceFlow`. Keyed by `(processKey, version)`. |
| `parser` | Validates XML against the bundled OMG XSDs (`javax.xml.validation`), then reads it with StAX into the model. Rejects unsupported elements with the offending element id. |
| `runtime` | Token-based interpreter. One `NodeBehavior` per node type. |
| `api` | `ProcessEngine` facade: `deploy`, `start`, `completeTask`, `correlateMessage`, `sendSignal`, `cancel`, `retryIncident`, and queries. Maps one-to-one to the skills. |
| `spi` | Extension points: `ProcessStore`, `TaskHandler`, `ExpressionEvaluator`, `Clock`. |

## Runtime semantics

### Execution model

A process instance holds a set of **executions** (tokens), each positioned on a flow node, plus a variable scope. Each node type has a `NodeBehavior` that handles a token entering and leaving the node.

A command runs the instance synchronously until every token is at a wait state:

- `userTask` and `receiveTask`
- message, signal, and timer catch events
- an async job (see below)

The resulting state is then saved in one transaction. A failure before the save rolls the instance back to its previous wait state.

### Service tasks

A `serviceTask` is bound to a `TaskHandler` by a `mintwf:type` extension attribute, with handler-specific configuration in extension elements. Handlers are discovered with `ServiceLoader`, so telecom-specific adapters can ship as separate jars. The first built-in handler is `http`, using `java.net.http`.

Service tasks are **asynchronous by default**. Reaching one creates a job, and the worker runs it:

- On success, the token continues.
- On failure, the job is retried with exponential backoff.
- When retries are exhausted, the engine creates an **incident**. This is the fallout case. Incidents appear in `get-instance` and are cleared with `retry-incident`.

### Expressions

`conditionExpression` uses a small built-in language: variable paths, comparisons, `&&`, `||`, `!`, and literals. It is plugged in through `ExpressionEvaluator`, so a richer language (such as FEEL) can be added without changing the core.

### Variables

Variable values are limited to JSON types: string, number, boolean, null, list, and map. They pass unchanged through the CLI, the database, and the skills.

### Versioning

Deploying stores the BPMN XML and its content hash. Deploying changed content under an existing process key creates a new version. Redeploying identical content is a no-op. An instance stays on the version it started with.

### Compensation

The engine records every completed activity that has a compensation handler. On `cancel`, or on a compensation throw event, the handlers run in reverse completion order.

## Persistence

`mintwf-store-jdbc` targets **H2** in embedded file mode with `AUTO_SERVER=TRUE`, so the CLI and the worker can open the same database file concurrently.

Each instance's runtime state (executions and variables) is stored as **one JSON document** with a `version` column for optimistic locking. Fields that are used in queries are also stored as columns. A command loads the instance, applies the change, and saves only if `version` is unchanged. On a conflict it reloads and retries.

| Table | Purpose |
|---|---|
| `deployment` | Process key, version, BPMN XML, content hash, deploy time |
| `instance` | Id, process key and version, business key, state, `doc` (JSON), `version` |
| `subscription` | Waiting message name, signal name, or timer due time, with the instance and execution. Used by `correlate-message`, `send-signal`, and the timer scan. |
| `job` | Type, instance, due time, retries left, lock owner, lock expiry |
| `incident` | Failed job, error message, time |
| `history` | Append-only log of node entry and exit, for diagnosing fallout |

A worker claims a job with `UPDATE job SET lock_owner = ?, lock_expiry = ? WHERE id = ? AND (lock_owner IS NULL OR lock_expiry < ?)`. An expired lock can be reclaimed, so a crashed worker's jobs are picked up again.

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
| Dependency management | JUnit BOM plus explicitly pinned versions | The project no longer uses Spring, so it no longer imports the Spring Boot BOM. |
