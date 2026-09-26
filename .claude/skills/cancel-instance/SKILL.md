---
name: cancel-instance
description: Cancel a running mintwf process instance. Use when the user wants to cancel, stop, abort, or kill a workflow run.
---

# Cancel a process instance

```sh
bin/mintwf cancel-instance <instanceId>
```

Cancelling cannot be undone. Confirm with the user first unless they clearly asked to cancel that specific instance.

Cancelling ends the instance with status `CANCELLED` and deletes its pending jobs and incidents. It does not undo work already done by completed service tasks. Compensation is not supported yet, so if earlier steps changed other systems, tell the user those changes remain.

`not_found` means there is no instance with that id. `execution_failed` means it already completed or was already cancelled.
