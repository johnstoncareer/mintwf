---
name: view-instance
description: Draw a mintwf process instance on its BPMN diagram, showing which nodes ran, which are active, and which have incidents, plus the full node history. Use when the user wants to see, visualize, or show a workflow run, or asks which steps an instance went through.
---

# View a process instance

```sh
bin/mintwf view-instance <instanceId>
```

This writes an HTML page to `.mintwf/views/<instanceId>.html` and prints `{"instanceId": ..., "file": ...}`. Pass `--output FILE` to write it elsewhere. Give the user the `file` path to open in a browser. The page loads bpmn-js from cdn.jsdelivr.net, so it needs network access.

If the user gives a business key (such as an order id) rather than an instance id, run `bin/mintwf list-instances` and find the instance whose `businessKey` matches.

## What the page shows

- The diagram, with completed nodes in green, active nodes in blue, nodes with an incident in red, and nodes of a cancelled instance in grey. A badge on each node counts how many times it was visited.
- The incidents and their errors.
- The history: every node the instance visited, in order, with start and end times.

The page is a snapshot. Run the command again to refresh it.

To answer in text instead, run `bin/mintwf get-instance <instanceId> --history`. Its `history` array lists the same visits, oldest first, each with `nodeId`, `nodeType`, `state` (`ACTIVE`, `COMPLETED`, or `TERMINATED`), `startedAt`, and `endedAt`.

Instances started before mintwf recorded node history show only their current position.

`not_found` means there is no instance with that id.
