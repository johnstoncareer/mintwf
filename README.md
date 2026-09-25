# Micro Intelligent Workflow (mintwf)

A lightweight workflow engine for telecom service and resource orchestration, written entirely by AI.

> **Status:** Early stage. REST endpoints for the targeted TM Forum Open APIs are implemented with in-memory storage. The workflow engine itself is not implemented yet.

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

### Implementation

The `mintwf-tmf` module implements **v4.0.0** of each API, the one version all nine publish. Each API has one controller, and its paths match the official specs in the [tmforum-apis](https://github.com/tmforum-apis) GitHub organization.

| API | Base path | Resources |
|---|---|---|
| TMF622 | `/tmf-api/productOrderingManagement/v4` | `productOrder`, `cancelProductOrder` |
| TMF641 | `/tmf-api/serviceOrdering/v4` | `serviceOrder`, `cancelServiceOrder` |
| TMF652 | `/tmf-api/resourceOrderingManagement/v4` | `resourceOrder`, `cancelResourceOrder` |
| TMF640 | `/tmf-api/ServiceActivationAndConfiguration/v4` | `service`, `monitor` |
| TMF702 | `/tmf-api/ResourceActivationAndConfiguration/v4` | `resource`, `monitor` |
| TMF638 | `/tmf-api/serviceInventory/v4` | `service` |
| TMF639 | `/tmf-api/resourceInventoryManagement/v4` | `resource`, `physicalResource`, `logicalResource` |
| TMF701 | `/tmf-api/processFlowManagement/v4` | `processFlow`, `processFlow/{id}/taskFlow` |
| TMF688 | `/tmf-api/event/v4` | `topic`, `topic/{id}/event`, `topic/{id}/hub` |

Every API also has `POST /hub` and `DELETE /hub/{id}` for notification subscriptions.

All controllers follow the TMF630 REST guidelines:

- **Create:** The server assigns `id` and `href`, applies spec defaults (for example `state: acknowledged` and `orderDate` on orders), and returns `400` when a mandatory attribute is missing.
- **Read:** Lists support `fields`, `offset`, `limit`, and attribute filters such as `state=acknowledged,inProgress` or `serviceOrderItem.state=completed`. Responses include the `X-Total-Count` and `X-Result-Count` headers.
- **Update:** `PATCH` uses JSON merge patch (`application/merge-patch+json`) and rejects attributes the spec marks as non-patchable.
- **Errors:** Failures return the TMF `Error` body (`code`, `reason`, `message`, `status`).
- **Notifications:** Creates, attribute changes, state changes, and deletes are POSTed to `/hub` subscribers. A subscription's `query` can limit delivery by event type, for example `eventType=ServiceOrderStateChangeEvent`.

Current limitations:

- Data is held in memory and is lost on restart.
- Resources are stored as JSON rather than typed models. Every spec attribute round-trips, but only mandatory attributes are validated.
- The `/listener/*` paths in the specs are for clients to implement, so they are not provided here.
- Cancel requests are recorded but do not yet change the referenced order. The engine will handle that.
- TMF640 and TMF702 requests complete synchronously (`201`), so their `monitor` collections stay empty.
- The `sort` query parameter is accepted but ignored.

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

### Run the TMF API server

```sh
./mvnw -pl mintwf-tmf spring-boot:run
```

The server listens on `http://localhost:8080`. For example:

```sh
curl -X POST http://localhost:8080/tmf-api/serviceOrdering/v4/serviceOrder \
  -H 'Content-Type: application/json' \
  -d '{"serviceOrderItem":[{"id":"1","action":"add","service":{"name":"Home Fiber"}}]}'
```

### Project layout

| Module | Purpose |
|---|---|
| `mintwf-core` | Engine core: workflow definitions, execution, and state |
| `mintwf-tmf` | Spring Boot app with REST controllers for the TM Forum Open APIs |

Java packages live under `com.intwfs.mintwf`.

## Contributing

The project is still in its early stages. Open an issue to discuss ideas or proposed designs before submitting large changes.

## License

Licensed under the [Apache License 2.0](LICENSE).
