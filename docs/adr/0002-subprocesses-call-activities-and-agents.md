# ADR 0002: Subprocesses, call activities, skills, and agents

- **Status:** Accepted
- **Date:** 2026-10-03
- **Builds on:** [ADR 0001](0001-node-history-and-instance-viewer.md)

## Context

ADR 0001 mapped skills, agents, and sub-agents to BPMN: a skill is a service task, an agent is an `adHocSubProcess` whose activities are its tools, and a sub-agent is a `callActivity` that calls an agent process. Building that needs decisions ADR 0001 left open: how tokens live inside a subprocess, how a called instance stays consistent with its caller, how an agent turn runs, and what a skill task actually does.

## Decision

### 1. Scopes: a token on the subprocess, tokens inside it

A `subProcess` or `adHocSubProcess` is a **scope**. When a token reaches one, it stays on the subprocess node as the scope token, and each token inside carries the scope token's id as its `scopeId`. The scope token's node instance stays `ACTIVE` while the scope runs.

- An embedded `subProcess` starts a token at its own start event. When the last token inside it is consumed, the scope token leaves the subprocess by its outgoing flows.
- A joining parallel gateway only merges tokens from the same scope.
- Node ids are unique across the document, so the definition keeps one map of nodes and records each node's container.
- `scopeId` is an optional field of each token in the instance document. Documents written before it read as top-level tokens.

### 2. Call activities start a child instance in the same transaction

When a token reaches a `callActivity`, the engine starts an instance of the latest version of `calledElement` with a copy of the caller's variables. The caller's token waits. When the child completes, its variables are merged into the caller and the caller's token moves on.

- One command can now change several instances: the caller, the children it starts, and the callers a completing child resumes, up to the root. The engine collects the changes and the store saves them with `saveAll` in one transaction, each instance with its own revision check. A conflict on any of them retries the whole command.
- A child records its caller: the instance, the token, and the call activity node, plus the root instance of the tree. They are columns of `mintwf_instance` (schema V3), so children can be queried.
- Cancelling an instance cancels its active children. A child cannot be cancelled on its own, because its caller would wait forever; cancel the root instead.
- Calls nested more than 64 deep fail the command, which stops a process that calls itself without end.

Rejected alternatives:

- **A job that starts the child, and another that resumes the caller.** This needs no multi-instance save, but each step then waits for a worker and the caller is briefly inconsistent with its children.
- **Selective input and output mappings.** All variables in and all out is enough for now and is what Camunda does by default.

### 3. An agent turn is one job and one model call

An `adHocSubProcess` is an agent. Its `documentation` is the goal, and the activities inside it that have no incoming flow are its tools. Paths inside it may end without an end event.

- When a token enters the agent, and again whenever every activity it started has finished, the interpreter creates an `AGENT_TURN` job for the scope token.
- The worker runs the job like a service task job. It builds an `AgentContext` (goal, activities, variables, and the activities run so far in this scope), asks the `AgentPlanner` for a decision outside any transaction, then applies it in one save. The decision either starts activities, each with variables to set first, or completes the agent with result variables.
- A failed planner call is retried and becomes an incident like a failed service task.
- More than 50 activations in one scope (`mintwf:field name="maxActivations"` to change) fail the next turn, so an agent cannot loop without end.
- `AgentPlanner` is a core SPI with no dependencies. It is registered on the engine builder or found through `ServiceLoader`.

The `mintwf-claude` module implements the planner with the Anthropic Java SDK. Each turn is a single request built from the context, with one tool per activity and a `mintwf_finish` tool. The request carries no earlier model turns, so the engine keeps no model output and there is no conversation history to replay or edit. The node history is the agent's memory: it shows what ran, in what order, and what the variables are now.

### 4. A skill task runs a skill's instructions with the model alone

A `serviceTask` with `mintwf:type="skill"` and `<mintwf:field name="skill" value="<name>"/>` loads `<skills directory>/<name>/SKILL.md`, which defaults to `.claude/skills`. It sends the skill's instructions and the task's input variables to Claude and stores the reply in a result variable, parsed as JSON when it is JSON.

The skill gets no tools: it cannot run commands or call systems. Work that needs tools is modeled as service tasks, or as an agent whose activities do that work. The skill is a field rather than the `mintwf:skill` attribute named in ADR 0001, because handlers see their task only through its fields.

## Consequences

- `InstanceState` gains a caller link and `Execution` gains `scopeId`. Both default to absent for stored instances.
- A command that completes a deep chain of children writes one row per instance in the chain, in one transaction.
- Agent turns need a running worker, like async service tasks, and the Claude module needs `ANTHROPIC_API_KEY`, or another credential the SDK reads from the environment.
- The viewer writes one page per instance in a call tree and links call activities to the pages of the instances they started. Inside an agent, badges show the order the agent started its activities in.
