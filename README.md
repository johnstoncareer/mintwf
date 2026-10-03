# Micro Intelligent Workflow (mintwf)

A lightweight workflow engine for telecom service and resource orchestration, written entirely by AI.

> **Status:** Early stage. The engine runs the BPMN subset below, stores state in H2, and runs service tasks as retried jobs in a background worker. Processes are managed through Claude skills or the `mintwf` command line. Messages, signals, timers, and compensation are not implemented yet.

## Overview

mintwf is meant to be a small, embeddable engine that runs workflows: ordered or branching sets of steps that carry a business request from start to finish. Its main target is the telecom domain, where requests such as "provision a new fiber service" or "modify a customer's bandwidth" become many coordinated technical tasks across different systems.

"Micro" means the engine should stay small and focused. Instead of a heavyweight BPM suite, the goal is a minimal core that is easy to understand, deploy, and extend.

## Author

Adam Johnston ([Intelligent Workflows LLC](https://intwfs.com)) · adam@intwfs.com

## Workflow Definition

Workflows are defined in [BPMN 2.0](https://www.omg.org/spec/BPMN/2.0.2/), the OMG standard XML format for business processes. Definitions can be drawn in any BPMN modeler and run without conversion. mintwf executes the subset of BPMN 2.0 listed under [Supported BPMN](#supported-bpmn).

Each workflow concept maps to a BPMN element:

| Concept | Description | BPMN 2.0 |
|---|---|---|
| **Workflow** | A named, versioned definition of a process, such as `ProvisionFiberService`. | `process` |
| **Step / Task** | One unit of work, such as calling an API, transforming data, or waiting for an event. | `serviceTask`, `scriptTask`, `userTask`, `receiveTask` |
| **Transition** | The rule that decides which step runs next, either sequentially or by a condition. | `sequenceFlow` with an optional `conditionExpression`, plus gateways such as `exclusiveGateway` and `parallelGateway` |
| **Input / Context** | Data that flows into the workflow and is shared or updated between steps. | Process variables, `dataObject` |
| **Instance** | One running execution of a workflow definition with its own state. | Process instance |
| **Compensation** | The action that undoes or rolls back a completed step when a later step fails. | Compensation `boundaryEvent` and a task marked `isForCompensation` |

A workflow definition describes *what* should happen. The engine handles *how* it runs, including ordering, state persistence, retries, error handling, and resumption.

A minimal definition:

```xml
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
             xmlns:mintwf="https://intwfs.com/mintwf"
             targetNamespace="https://intwfs.com/mintwf">
  <process id="ProvisionFiberService" isExecutable="true">
    <startEvent id="start"/>
    <sequenceFlow id="f1" sourceRef="start" targetRef="allocatePort"/>
    <serviceTask id="allocatePort" name="Allocate port" mintwf:type="inventory"/>
    <sequenceFlow id="f2" sourceRef="allocatePort" targetRef="activate"/>
    <serviceTask id="activate" name="Activate service" mintwf:type="activation"/>
    <sequenceFlow id="f3" sourceRef="activate" targetRef="end"/>
    <endEvent id="end"/>
  </process>
</definitions>
```

### Supported BPMN

A document must contain exactly one `process` with `isExecutable="true"`. Other processes, such as the pools of external participants, are ignored. Anything else inside the executable process is rejected at deploy time with the id of the unsupported element.

| Element | Behavior |
|---|---|
| `startEvent` | Exactly one, without an event definition. |
| `endEvent` | Without an event definition. The instance completes when every token has reached an end event. |
| `serviceTask` | Runs the task handler named by `mintwf:type` as a background job, with retries. `mintwf:async="false"` runs it immediately instead, without retries. Handler settings go in `<mintwf:field name="..." value="..."/>` inside `extensionElements`. See [Service tasks](#service-tasks). |
| `userTask`, `receiveTask` | Waits until the task is completed with `complete-task`. |
| `exclusiveGateway` | Takes the first outgoing flow, in document order, whose condition is true, or else the `default` flow. |
| `parallelGateway` | Waits for a token on every incoming flow, then continues on every outgoing flow. |
| `sequenceFlow` | An optional `conditionExpression`. A task leaves by every flow whose condition is true, or by its `default` flow if none is. |

`documentation`, `extensionElements`, `laneSet`, `textAnnotation`, `association`, and diagram information are allowed and ignored.

Conditions use a small expression language: variable paths such as `order.site.region`, number, string, `true`, `false` and `null` literals, `==`, `!=`, `<`, `<=`, `>`, `>=`, `&&`, `||`, `!`, and parentheses. A condition may be wrapped in `${...}`. Variables hold JSON values only: strings, numbers, booleans, null, lists, and maps.

### Service tasks

A service task runs as a job: the instance waits on the task while the `mintwf worker` process calls the handler. If the handler fails, the job is tried again after 10 seconds, then after 20 more. When all 3 attempts have failed, the job becomes an **incident**: the instance stays on that task, and `get-instance` shows the error. Fix the cause, then use `retry-incident` to give the job 3 more attempts.

A handler can run more than once for the same task, for example if the worker stops just after a call succeeded, so the systems it calls should tolerate repeats.

mintwf includes one handler, `http`:

```xml
<serviceTask id="allocatePort" name="Allocate port" mintwf:type="http">
  <extensionElements>
    <mintwf:field name="url" value="https://inventory.example.com/sites/${siteId}/ports"/>
    <mintwf:field name="inputVariables" value="bandwidth,customerId"/>
    <mintwf:field name="resultVariable" value="port"/>
    <mintwf:field name="header.Authorization" value="Bearer ..."/>
  </extensionElements>
</serviceTask>
```

| Field | Meaning |
|---|---|
| `url` | Required. `${name}` is replaced by the value of variable `name`, URL-encoded. |
| `method` | `GET`, `POST` (the default), `PUT`, `PATCH`, or `DELETE`. |
| `inputVariables` | Comma-separated variables to send as a JSON object. Without it, `POST`, `PUT`, and `PATCH` send every variable. |
| `resultVariable` | Variable to store the response in: parsed JSON when the response is JSON, otherwise the text. |
| `timeoutSeconds` | Request timeout. Defaults to 30. |
| `header.Name` | Sends header `Name` with this value. |

A response outside the 2xx range fails the attempt.

Other handlers can ship in their own jar: implement `com.intwfs.mintwf.core.spi.TaskHandlerProvider` and list the class in `META-INF/services/com.intwfs.mintwf.core.spi.TaskHandlerProvider`.

### Skill tasks

A service task with `mintwf:type="skill"` runs a [Claude skill](https://docs.claude.com/en/docs/claude-code/skills) as a workflow step. It sends the skill's `SKILL.md` instructions and the task's input variables to Claude and stores the reply, parsed as JSON when the reply is a JSON object or array:

```xml
<serviceTask id="classify" name="Classify order" mintwf:type="skill">
  <extensionElements>
    <mintwf:field name="skill" value="classify-order"/>
    <mintwf:field name="inputVariables" value="amount,customer"/>
    <mintwf:field name="resultVariable" value="classification"/>
  </extensionElements>
</serviceTask>
```

| Field | Meaning |
|---|---|
| `skill` | Required. The skill's directory name. |
| `skillsDirectory` | Where skills live. Defaults to `$MINTWF_SKILLS_DIR`, else `.claude/skills` under the working directory. |
| `inputVariables` | Comma-separated variables to send. Without it, every variable is sent. |
| `resultVariable` | Variable to store the reply in. Defaults to the task id followed by `Result`. |
| `model` | The Claude model. Defaults to `$MINTWF_CLAUDE_MODEL`, else `claude-opus-5`. |

The skill gets no tools: it cannot run commands or call systems, so it suits steps such as classifying, summarizing, or drafting. The worker needs Anthropic API credentials, usually `ANTHROPIC_API_KEY`. If Claude declines or the call fails, the task is retried and then becomes an incident like any other service task.

## Process Commands

mintwf has no REST API. The standard BPMN process commands are provided as [Claude skills](https://docs.claude.com/en/docs/claude-code/skills) instead, so processes are deployed, started, and managed by asking Claude in this repository, for example "deploy provision.bpmn and start it for order 42". The skills live in [.claude/skills/](.claude/skills/).

Each skill runs the matching `bin/mintwf` command, which you can also run yourself:

| Skill | Command |
|---|---|
| `deploy-process` | `bin/mintwf deploy-process FILE`: deploy a BPMN 2.0 file, creating a new version if the content changed |
| `start-process` | `bin/mintwf start-process PROCESS [--business-key KEY] [--vars JSON]`: start an instance |
| `list-instances` | `bin/mintwf list-instances [--process PROCESS] [--status STATUS]`: list instances |
| `get-instance` | `bin/mintwf get-instance INSTANCE [--history]`: show an instance's status, position, tasks, incidents, and variables. `--history` adds every node the instance visited, oldest first. |
| `complete-task` | `bin/mintwf complete-task INSTANCE TASK [--vars JSON]`: complete a waiting `userTask` or `receiveTask` |
| `retry-incident` | `bin/mintwf retry-incident INSTANCE JOB`: give a failed service task a fresh set of attempts |
| `cancel-instance` | `bin/mintwf cancel-instance INSTANCE`: cancel a running instance |
| `view-instance` | `bin/mintwf view-instance INSTANCE [--output FILE]`: write an HTML page that draws the instance on its BPMN diagram, marking the nodes that ran, are active, or have an incident, with the full node history. Opening it needs network access to cdn.jsdelivr.net. |
| `start-worker` | `bin/mintwf worker`: run service task jobs until stopped. Service tasks only progress while a worker runs. |
| `correlate-message` | Not implemented yet: deliver a message to the instance waiting for it |
| `send-signal` | Not implemented yet: broadcast a signal to every instance waiting for it |

Variables can also come from a file with `--vars-file FILE`. Commands print JSON. A failed command prints `{"error": {"type": ..., "message": ...}}` and exits with 1; the types are `not_found`, `invalid_bpmn`, `execution_failed`, `invalid_input`, `io_error`, and `internal_error`. Invalid arguments exit with 2.

All commands use the H2 database `.mintwf/mintwf` under the current directory. Choose another with `--database PATH` before the command name, or the `MINTWF_DATABASE` environment variable. The CLI and a running worker can use the same database at the same time.

`bin/mintwf` builds `mintwf-cli/target/mintwf.jar` on first use and whenever the sources are newer. On Windows cmd or PowerShell, use `bin\mintwf.cmd`, which builds the jar only when it is missing.

## Telecom Domain

Telecom operators (CSPs) run order-to-activation processes that fit a workflow model well:

- **Product ordering:** A customer buys a commercial product, such as a "1 Gbps Home Internet" plan.
- **Service decomposition:** The product becomes one or more customer-facing and resource-facing services.
- **Resource allocation:** Network resources are reserved and assigned, such as ports, IP addresses, VLANs, and CPE devices.
- **Activation / provisioning:** Network elements and OSS/BSS systems are configured.
- **Inventory update:** The service and resource inventories are updated with the new state.
- **Fallout handling:** Failed steps are retried, compensated, or sent to a human for manual resolution.

These processes are long-running, involve many external systems, and must survive partial failure. A workflow engine is designed to handle those problems.

### Key terms

- **CSP:** Communication Service Provider, meaning the telecom operator.
- **OSS / BSS:** Operations Support Systems (network-facing) and Business Support Systems (customer-facing).
- **CFS / RFS:** Customer-Facing Service and Resource-Facing Service.
- **Fallout:** An order that could not complete automatically and needs intervention.

## Getting Started

mintwf is written in **Java 25** and built with **Maven**.

### Prerequisites

- JDK 25 or newer, with `JAVA_HOME` set
- No Maven install is needed. The bundled Maven wrapper (`mvnw`) downloads the right version automatically.

### Build and test

```sh
./mvnw verify        # macOS / Linux / Git Bash
mvnw.cmd verify      # Windows cmd / PowerShell
```

This compiles every module, runs the tests, and writes jars to each module's `target/` directory, including the runnable `mintwf-cli/target/mintwf.jar`.

### Try it

```sh
bin/mintwf deploy-process my-process.bpmn  # the first run builds the CLI
bin/mintwf worker &                        # run service task jobs in the background
bin/mintwf start-process MyProcess --vars '{"orderId": "42"}'
bin/mintwf list-instances --status ACTIVE
```

### Project layout

| Module | Purpose |
|---|---|
| `mintwf-core` | Engine core: BPMN parser, model, interpreter, and extension points. No runtime dependencies. |
| `mintwf-store-jdbc` | Stores deployments, instances, and jobs in a database, H2 by default. |
| `mintwf-handler-http` | The `http` task handler. |
| `mintwf-claude` | The `skill` task handler, which calls Claude. |
| `mintwf-cli` | The `mintwf` command line and worker, packaged as `mintwf.jar`. |

Java packages live under `com.intwfs.mintwf`.

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the modules, packages, runtime design, and delivery phases.

## Contributing

The project is still in its early stages. Open an issue to discuss ideas or proposed designs before submitting large changes.

## License

Licensed under the [Apache License 2.0](LICENSE).
