---
name: view-instance
description: Draw a mintwf process instance on its BPMN diagram, showing which nodes ran, which are active, and which have incidents, plus the full node history. Use when the user wants to see, visualize, or show a workflow run, or asks which steps an instance went through.
---

# View a process instance

```sh
bin/mintwf view-instance <instanceId>
```

This writes one HTML page per instance of the call tree to `.mintwf/views/<instanceId>.html` and prints `{"instanceId": ..., "file": ..., "files": [...]}`. `file` is the page of the requested instance; `files` lists every page, root first. Pass `--directory DIR` to write them elsewhere. Give the user the `file` path to open in a browser. The pages load bpmn-js from cdn.jsdelivr.net, so they need network access.

If the user gives a business key (such as an order id) rather than an instance id, run `bin/mintwf list-instances` and find the instance whose `businessKey` matches.

## What the page shows

- The diagram, with completed nodes in green, active nodes in blue, nodes with an incident in red, and nodes of a cancelled instance in grey. A badge on each node counts how many times it was visited.
- The incidents and their errors.
- The instances its call activities started, with links to their pages. Clicking a call activity on the diagram opens the instance it started, and a called instance's page links back to its caller. A sub-agent is such a called instance.
- Inside an agent (`adHocSubProcess`), badges such as `#1` and `#3` give the order the agent ran each activity in. Agents and subprocesses are drawn collapsed; the arrow on them opens their inside.
- The history: every node the instance visited, in order, with start and end times.

The page is a snapshot. Run the command again to refresh it.

To answer in text instead, run `bin/mintwf get-instance <instanceId> --history`. Its `history` array lists the same visits, oldest first, each with `nodeId`, `nodeType`, `state` (`ACTIVE`, `COMPLETED`, or `TERMINATED`), `startedAt`, and `endedAt`.

Instances started before mintwf recorded node history show only their current position.

`not_found` means there is no instance with that id.
