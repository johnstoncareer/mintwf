---
name: list-instances
description: List mintwf process instances, optionally filtered by process and status. Use when the user asks what workflows are running, stuck, finished, or cancelled, or wants an overview of instances.
---

# List process instances

```sh
bin/mintwf list-instances [--process <processKey>] [--status ACTIVE|COMPLETED|CANCELLED]
```

The output is a JSON array of instances, oldest first, each with the same fields as `get-instance`.

Summarize rather than dump the JSON: count by status, and for active instances say what each is waiting on (open `tasks`, service tasks in `activeNodeIds`, or `incidents`). Call out any instance with incidents, since those are stuck until someone runs `retry-incident`.

The list can be long. Filter with `--process` and `--status` when the user's question allows it.
