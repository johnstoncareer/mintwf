---
name: start-process
description: Start a mintwf process instance, optionally with input variables and a business key. Use when the user wants to start, run, launch, or trigger a deployed workflow or process, such as "provision fiber for order 123".
---

# Start a process instance

```sh
bin/mintwf start-process <processKey> [--business-key <key>] [--vars '<json object>' | --vars-file <file.json>]
```

- `processKey` is the BPMN process `id` given at deploy time. If you don't know it, ask, or check recent `deploy-process` output.
- Use `--business-key` for the user's own reference, such as an order id.
- Variables must be a JSON object. Values can be strings, numbers, booleans, null, lists, and objects. Quote the JSON in single quotes for the shell; for anything long, write it to a file and use `--vars-file`.

The instance always starts on the latest deployed version.

## Reading the result

The output is the instance as JSON. Report the `id`, `status`, and what it is waiting on:

- `status: "COMPLETED"`: it already ran to the end.
- `tasks`: open user or receive tasks. Each needs `complete-task` with its `id`.
- `activeNodeIds` listing a service task: that task is waiting for the worker. If no worker is running, service tasks never progress, so offer to use the `start-worker` skill.
- `incidents`: failed service tasks; see `retry-incident`.

Errors print `{"error": {"type": ..., "message": ...}}` with exit code 1:

- `not_found`: no process with that key is deployed.
- `execution_failed`: the instance failed before its first wait state (for example, no exclusive gateway condition matched) and was not created.
