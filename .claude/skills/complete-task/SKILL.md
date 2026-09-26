---
name: complete-task
description: Complete a waiting userTask or receiveTask in a mintwf process instance, optionally setting variables. Use when the user approves, rejects, confirms, or provides input for a step a workflow is waiting on.
---

# Complete a task

```sh
bin/mintwf complete-task <instanceId> <taskId> [--vars '<json object>' | --vars-file <file.json>]
```

The task id comes from the instance's `tasks` list (`get-instance`). It is a short number that is unique only within that instance, so always pass the instance id too.

Variables set here are available to the rest of the process, including gateway conditions right after the task. Before completing, check the process's conditions if you can, so you set the variables they test. For example, a gateway with `approved == true` needs `--vars '{"approved": true}'`.

## Reading the result

The output is the instance after it has run on to its next wait state. Tell the user where it went: completed, a new open task, or a service task now waiting for the worker.

Errors print `{"error": {"type": ..., "message": ...}}` with exit code 1:

- `not_found`: no such instance, or no open task with that id. Re-read the instance, because the task may already be completed.
- `execution_failed`: the instance is not active, or it failed while running on (for example, no gateway condition matched). The instance is left unchanged, so fix the variables and try again.
