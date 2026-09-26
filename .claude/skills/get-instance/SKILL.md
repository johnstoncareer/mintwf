---
name: get-instance
description: Show the state of one mintwf process instance, including its status, current position, open tasks, incidents, and variables. Use when the user asks about a specific instance, order, or workflow run, or why it is stuck.
---

# Get a process instance

```sh
bin/mintwf get-instance <instanceId>
```

If the user gives a business key (such as an order id) rather than an instance id, run `bin/mintwf list-instances` and find the instance whose `businessKey` matches.

## Reading the result

| Field | Meaning |
|---|---|
| `status` | `ACTIVE`, `COMPLETED`, or `CANCELLED` |
| `activeNodeIds` | BPMN node ids that hold a token, one per token |
| `tasks` | Open `userTask` and `receiveTask` entries. Complete one with `complete-task <instanceId> <task id>`. |
| `incidents` | Service tasks that failed every attempt, with the `error`. Fix the cause, then `retry-incident <instanceId> <jobId>`. |
| `variables` | The instance data |
| `processVersion` | The deployed version this instance runs |

A service task id in `activeNodeIds` with no incident means its job is pending or being retried. It needs a running worker (`start-worker`).

Explain the state in plain terms: where the instance is, what it is waiting for, and what the user can do next.

`not_found` means there is no instance with that id.
