# Micro Intelligent Workflow (mintwf)

A lightweight workflow engine for telecom service and resource orchestration, written entirely by AI.

> **Status:** Early stage. The workflow engine is not implemented yet.

## Overview

mintwf is meant to be a small, embeddable engine that runs workflows: ordered or branching sets of steps that carry a business request from start to finish. Its main target is the telecom domain, where requests such as "provision a new fiber service" or "modify a customer's bandwidth" become many coordinated technical tasks across different systems.

"Micro" means the engine should stay small and focused. Instead of a heavyweight BPM suite, the goal is a minimal core that is easy to understand, deploy, and extend.

## Author

Adam Johnston ([Intelligent Workflows LLC](https://intwfs.com)) · adam@intwfs.com

## Workflow Definition

Workflows are defined in [BPMN 2.0](https://www.omg.org/spec/BPMN/2.0.2/), the OMG standard XML format for business processes. Definitions can be drawn in any BPMN modeler and run without conversion. mintwf will execute a subset of BPMN 2.0, which will be documented here as it is implemented.

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
             targetNamespace="https://intwfs.com/mintwf">
  <process id="ProvisionFiberService" isExecutable="true">
    <startEvent id="start"/>
    <sequenceFlow id="f1" sourceRef="start" targetRef="allocatePort"/>
    <serviceTask id="allocatePort" name="Allocate port"/>
    <sequenceFlow id="f2" sourceRef="allocatePort" targetRef="activate"/>
    <serviceTask id="activate" name="Activate service"/>
    <sequenceFlow id="f3" sourceRef="activate" targetRef="end"/>
    <endEvent id="end"/>
  </process>
</definitions>
```

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

This compiles every module, runs the tests, and writes jars to each module's `target/` directory.

### Project layout

| Module | Purpose |
|---|---|
| `mintwf-core` | Engine core: workflow definitions, execution, and state |

Java packages live under `com.intwfs.mintwf`.

## Contributing

The project is still in its early stages. Open an issue to discuss ideas or proposed designs before submitting large changes.

## License

Licensed under the [Apache License 2.0](LICENSE).
