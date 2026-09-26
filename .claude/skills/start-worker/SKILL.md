---
name: start-worker
description: Start the mintwf worker, which runs service task jobs and their retries. Use when the user wants workflows to make progress, when instances sit on a service task without incidents, or before starting processes that contain service tasks.
---

# Start the worker

Service tasks run as background jobs, so nothing happens to them unless a worker is running. Start one in the background (with the Bash tool's `run_in_background`), from the repository root:

```sh
bin/mintwf worker
```

It prints `mintwf worker started with 4 threads` and keeps running. One worker is enough. Starting a second is harmless, because workers share jobs safely. Options: `--threads`, `--poll-interval-ms`, `--batch-size`.

The worker uses the same database as the other commands: `.mintwf/mintwf` under the current directory by default. Start it from the directory where you run the other commands, or pass the same `--database` to all of them.

Handler failures are not printed. They show up as retries and then as `incidents` on the instance (`get-instance`).

To stop the worker, stop the background task. It finishes the jobs it is running before it exits.
