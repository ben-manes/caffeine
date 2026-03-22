---
name: audit-lifecycle
description: Analyze shutdown, close, and GC-of-cache lifecycle correctness
context: fork
agent: auditor
disable-model-invocation: true
---

Analyze the cache for defects during shutdown, close, and garbage collection
of the cache instance itself.

1. **Operations racing with close/cleanup**: get/put/compute during cleanUp()
   or GC? close() state transition? Operations after close()?

2. **Scheduler lifecycle**: Does the scheduled future prevent cache GC?
   Can scheduler fire after cache is collected? Is the task cancelled on close?

3. **Executor lifecycle**: Reference cycle between cache and executor?
   RejectedExecutionException handling when executor is shut down?

4. **Removal listener drain on shutdown**: Are pending notifications delivered
   or dropped? Notifications lost if executor shuts down first?

5. **In-flight operations during shutdown**:
   - CacheLoader.load() in progress when cache closes
   - Refresh future's whenComplete callback after close
   - CompletableFuture value pending when cache closes

6. **Multiple close calls**: Is close() idempotent? Concurrent close() corruption?

7. **Finalizer / Cleaner interactions**: Reference queues outliving the cache?
   Reference queue processing accessing dead cache?

For each defect: state the lifecycle transition, provide a concrete scenario,
state the observable incorrect behavior.
