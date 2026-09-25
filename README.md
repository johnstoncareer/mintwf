# Micro Intelligent Workflow (mintwf)

A lightweight workflow engine for telecom service and resource orchestration, written entirely by AI.

> **Status:** Early stage. The repository has no source code yet. This README describes the project's goals and domain so contributors can get oriented before implementation begins.

## Overview

mintwf is meant to be a small, embeddable engine that runs workflows: ordered or branching sets of steps that carry a business request from start to finish. Its main target is the telecom domain, where requests such as "provision a new fiber service" or "modify a customer's bandwidth" become many coordinated technical tasks across different systems.

"Micro" means the engine should stay small and focused. Instead of a heavyweight BPM suite, the goal is a minimal core that is easy to understand, deploy, and extend.

## Author

Adam Johnston ([Intelligent Workflows LLC](https://intwfs.com)) · adam@intwfs.com

## Workflow Definition

A **workflow** is a declarative description of a process. It typically consists of:

| Concept | Description |
|---|---|
| **Workflow** | A named, versioned definition of a process, such as `ProvisionFiberService`. |
| **Step / Task** | One unit of work, such as calling an API, transforming data, or waiting for an event. |
| **Transition** | The rule that decides which step runs next, either sequentially or by a condition. |
| **Input / Context** | Data that flows into the workflow and is shared or updated between steps. |
| **Instance** | One running execution of a workflow definition with its own state. |
| **Compensation** | The action that undoes or rolls back a completed step when a later step fails. |

A workflow definition describes *what* should happen. The engine handles *how* it runs, including ordering, state persistence, retries, error handling, and resumption.

_The definition format (for example JSON, YAML, or code-first) is not decided yet and will be documented here._

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
- **CFS / RFS:** Customer-Facing Service and Resource-Facing Service in the TM Forum SID model.
- **Fallout:** An order that could not complete automatically and needs intervention.

## TMForum OpenAPIs

[TM Forum](https://www.tmforum.org/) publishes the [Open APIs](https://www.tmforum.org/oda/open-apis/), a set of standardized REST APIs that telecom systems use to interoperate. mintwf is meant to fit into this ecosystem. Workflows would be triggered by these APIs and call them as steps.

mintwf targets the following APIs:

| API | Name | Role in mintwf |
|---|---|---|
| TMF701 | Process Flow Management | How the engine exposes its running workflows and their tasks |
| TMF622 | Product Ordering | Incoming orders that start workflows |
| TMF641 | Service Ordering | Requests creation or modification of services |
| TMF652 | Resource Ordering | Requests allocation of network resources |
| TMF640 | Service Activation & Configuration | Activates or configures a service on the network |
| TMF702 | Resource Activation | Activates or configures network resources |
| TMF638 | Service Inventory | Where finished steps record service state |
| TMF639 | Resource Inventory | Where finished steps record resource state |
| TMF688 | Event Management | Lets workflows react to events instead of polling |

TMF701 matters most here because it defines a standard way to represent and query running process flows and their tasks.

## Getting Started

_Coming soon: build, run, and usage instructions will be added once source code is available._

## Contributing

The project is still in its early stages. Open an issue to discuss ideas or proposed designs before submitting large changes.

## License

Licensed under the [Apache License 2.0](LICENSE).
