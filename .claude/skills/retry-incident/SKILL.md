---
name: retry-incident
description: Retry a mintwf service task that failed all of its attempts (an incident). Use when the user wants to retry, resume, or unstick a failed workflow step after fixing the underlying problem.
---

# Retry an incident

```sh
bin/mintwf retry-incident <instanceId> <jobId>
```

The job id comes from the instance's `incidents` list (`get-instance`). Each incident's `error` explains the last failure.

Before retrying, make sure the cause is addressed. Read the error with the user: a connection failure or 5xx response may just need the other system to recover, but a 4xx response or a missing variable will fail again unless something changes.

Retrying gives the job a fresh set of attempts, due immediately. It only runs if a worker is running (`start-worker`). Afterwards, check the instance with `get-instance` to confirm it moved on, or to see a new incident.

`not_found` means the instance has no incident with that job id, perhaps because it was already retried.
