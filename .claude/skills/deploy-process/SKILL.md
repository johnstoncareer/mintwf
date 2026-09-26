---
name: deploy-process
description: Deploy a BPMN 2.0 workflow definition to mintwf. Use when the user wants to deploy, publish, install, or update a process or workflow from a .bpmn or BPMN XML file.
---

# Deploy a process

Run from the repository root:

```sh
bin/mintwf deploy-process <file.bpmn>
```

The first run builds the CLI, which takes a minute. On Windows outside Git Bash, use `bin\mintwf.cmd`.

## Before deploying

If the user describes a process instead of giving a file, write the BPMN first. mintwf only accepts this subset; anything else is rejected:

- Exactly one `<process isExecutable="true">`, with an `id` that becomes the process key.
- `startEvent` (one, without an event definition), `endEvent`, `sequenceFlow` (optional `conditionExpression`), `userTask`, `receiveTask`, `exclusiveGateway` (conditions on every outgoing flow except the `default`), `parallelGateway`.
- `serviceTask` with `mintwf:type="<handler>"` (namespace `xmlns:mintwf="https://intwfs.com/mintwf"`). The built-in handler is `http`; its `mintwf:field` settings are listed in the README under "Service tasks".

Conditions use variable paths, literals, `== != < <= > >= && || !`, and parentheses, for example `bandwidth >= 1000 && order.region == 'EU'`.

## Reading the result

```json
{ "processKey": "ProvisionFiberService", "version": 2, "created": true, ... }
```

- `created: true`: a new version was deployed. New instances use it; running instances stay on their version.
- `created: false`: the file matches the latest version, so nothing changed.

On failure the command exits with 1 and prints `{"error": {"type": ..., "message": ...}}`. For `invalid_bpmn`, the message names the offending element or line: fix the file and deploy again.

Tell the user the process key and version.
