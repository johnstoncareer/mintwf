# ADR 0001: Node history, the instance viewer, and BPMN for agents and skills

- **Status:** Accepted
- **Date:** 2026-10-02

## Context

We want to see, on the BPMN diagram, which processes, nodes, skills, agents, and sub-agents ran for an instance. Today the engine keeps only the current tokens of an instance. Once a token moves on, nothing records that it passed through a node, so neither `get-instance` nor a diagram can show what ran.

The engine is written in Java with no runtime dependencies in core. One option was to rewrite it in TypeScript or Python, where the Claude Agent SDK is available. That would not solve the problem: what is missing is a record of what ran and a way to draw it, in any language. An agent that picks its tools inside the Agent SDK would also hide those calls from the engine.

## Decision

### 1. Keep Java for the engine; the viewer is a browser page

The engine, store, worker, and CLI stay in Java. The diagram is drawn by [bpmn-js](https://github.com/bpmn-io/bpmn-js), the standard BPMN renderer, which runs only in a browser. The CLI writes one self-contained HTML file per instance that loads bpmn-js from a CDN. This keeps the "no server, no REST API" constraint.

### 2. Record one node instance per visit

A **node instance** is one visit of a token to a flow node. It has an id, the instance and execution ids, the node id and BPMN element type, a state (`ACTIVE`, `COMPLETED`, or `TERMINATED`), and start and end times.

- The interpreter starts a node instance when a token arrives at a node and ends it when the token leaves. A token that stops at a wait state leaves its node instance `ACTIVE`; the command that moves it on completes it.
- A parallel gateway that joins records one node instance when it fires, not one per arriving token. Tokens waiting at the join have no node instance.
- Cancelling an instance marks its active node instances `TERMINATED`.
- Node instances are part of `InstanceChange`, so they are saved in the same transaction as the instance state and are rolled back with it. A store writes each one as an insert, or as an update of its state and end time if it already exists.
- An execution holds at most one active node instance. The engine loads the active node instances with the instance state at the start of each command; the revision check on save rejects the command if either was stale.
- `mintwf_node_instance` is append-only apart from that one update, and is ordered by insertion. The insertion order is the order in which nodes were entered, including the order in which an agent chose its activities.
- Instances started before this change have no node instances for the nodes they already passed. Their active tokens still show.

### 3. BPMN elements for skills, agents, and sub-agents (phases 3 to 5)

| Concept | BPMN 2.0 element |
|---|---|
| Skill | `serviceTask` with `mintwf:type="skill"` and `mintwf:skill="<name>"` |
| Agent | a `process` whose body is an `adHocSubProcess`; the skills it may use are the activities inside |
| Sub-agent | a `callActivity` whose `calledElement` is an agent process |

Every tool call an agent makes is an activation of an activity inside its ad-hoc subprocess. That gives each call a node instance, retries, incidents, and a place on the diagram. The agent loop runs one model turn per job, using the Anthropic Java SDK in its own handler module.

Skill, agent, and sub-agent instances are queries over node instances and process instances, not tables of their own. The parent link a call activity needs (`parent_instance_id`, `parent_node_instance_id`, `root_instance_id`) is added with `callActivity` in phase 4, when something writes it.

## Consequences

- Each command writes one row per node visited, and one indexed query per command loads the active node instances. Loops that revisit nodes add a row per visit.
- The viewer needs network access to the CDN when the page is opened.
- `get-instance --history` and `view-instance` read the history. Nothing else depends on it, so it can be pruned later without changing the engine.

## Alternatives considered

- **Rewrite in TypeScript or Python for the Claude Agent SDK.** Rejected: it means rewriting phases 1 to 3 and does not by itself make anything visible.
- **Derive history from the job table.** Rejected: only async service tasks create jobs, and jobs are deleted when they finish.
- **Store the node instance id on each token in the instance document.** Rejected: ending a node instance still needs its other fields, and the document format would change for every instance.
- **A separate table per instance kind.** Rejected: five schemas to migrate, and they could drift apart.
- **An agent that shells out to `claude -p`.** Rejected for now: the agent's internal steps would not be visible to the engine.
