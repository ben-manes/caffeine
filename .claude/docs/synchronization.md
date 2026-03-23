# Synchronization Reference

## Lock Hierarchy (strict total order)

```
evictionLock (outer, ReentrantLock)
  └─ CHM bin lock (implicit in compute/computeIfPresent)
      └─ synchronized(node) (inner)
```

Never acquire in reverse order. evictionLock is NEVER acquired while holding
synchronized(node). synchronized(node) is acquired in various contexts: inside
CHM compute lambdas, under evictionLock outside compute (makeDead, AddTask),
or standalone (put fast path, Policy API methods).

## What Each Lock Protects

### evictionLock
- Window/probation/protected deques
- Timer wheel
- Frequency sketch mutations
- Weight counters (weightedSize, windowWeightedSize)
- Drain status transitions (under lock)
- policyWeight field on nodes
- queueType field on nodes

### CHM bin lock (held implicitly during compute/computeIfPresent/merge)
- Atomic read-modify-write on map entries
- Ensures "at most once" for mapping functions

### synchronized(node)
- Value field mutations (setValue)
- Weight field mutations (setWeight)
- Access time / write time updates
- Node lifecycle transitions (alive → retired → dead)
- Expiration re-validation during eviction

## Node Field Access Modes

| Field | Read Mode | Write Mode | Guard |
|-------|-----------|------------|-------|
| value (strong) | getAcquire | setRelease | synchronized(node) for mutations |
| value (weak/soft) | getAcquire | setRelease + storeStoreFence | synchronized(node); fence publishes non-final fields of new reference |
| key (strong) | getOpaque | set (retire/die only) | immutable after construction |
| key (weak) | plain | set (retire/die only) | immutable after construction; getRef uses getOpaque |
| accessTime | getOpaque | setOpaque | benign races acceptable |
| writeTime | getOpaque | plain set | synchronized(node) |
| variableTime | getOpaque | setOpaque, CAS | synchronized(node) for CAS |
| weight | plain (volatile) | plain (volatile) | synchronized(node) |
| policyWeight | plain (volatile) | plain (volatile) | evictionLock |
| queueType | plain (volatile) | plain (volatile) | evictionLock |

## Drain Status State Machine

```
IDLE(0) ──CAS──► REQUIRED(1) ──schedule──► PROCESSING_TO_IDLE(2)
  ▲                                              │
  └──────────CAS success──────────────────────────┘
                                                  │
                                           CAS from REQUIRED
                                                  │
                                                  ▼
                                    PROCESSING_TO_REQUIRED(3)
                                           │
                                           └──► set REQUIRED, loop
```

- IDLE → REQUIRED: CAS by `scheduleAfterWrite()` (lock-free)
- REQUIRED → PROCESSING_TO_IDLE: set by `scheduleDrainBuffers()` or `maintenance()` (under evictionLock)
- PROCESSING_TO_IDLE → IDLE: CAS by `maintenance()` exit (under evictionLock)
- PROCESSING_TO_IDLE → PROCESSING_TO_REQUIRED: CAS by `scheduleAfterWrite()` (lock-free)
- PROCESSING_TO_REQUIRED → set REQUIRED: by `maintenance()` exit (under evictionLock)

Access modes: opaque and acquire reads, release and opaque writes, CAS for transitions.

## User Callback Invocation Points

### notifyEviction — INSIDE synchronized(node), BEFORE user code
Called before mapping functions, weighers, and expiry callbacks. Irrevocable.
- `put()`, `evictEntry()`, `remove()`, `removeNode()`
- `remap()`, `doComputeIfAbsent()` (before try block with user code)

### notifyRemoval — OUTSIDE synchronized(node) and CHM bin lock, AFTER mutations
Wraps the listener call in a task submitted to the executor. The actual
`onRemoval` runs in the executor thread outside all locks. Note: in `evictEntry`
and `removeNode`, notifyRemoval is called while evictionLock is still held (but
outside synchronized(node) and CHM bin lock).

### Mapping functions (compute, merge, etc.)
Lock context depends on path:
- **Existing node**: INSIDE CHM bin lock + synchronized(node)
- **New node (n == null)**: INSIDE CHM bin lock only (no node to synchronize on)
Re-entrant cache operations from these callbacks can deadlock.

### Weigher.weigh
Lock context depends on path:
- **put()/replace()**: OUTSIDE all locks (called before CHM operation)
- **compute/merge (existing node)**: INSIDE CHM bin lock + synchronized(node)
- **compute/merge (new node)**: INSIDE CHM bin lock only
Can throw (caught by catch-commit-rethrow in compute paths).

### Expiry callbacks
Lock context depends on path:
- **put()**: INSIDE synchronized(node) only (no CHM bin lock)
- **compute/merge (existing node)**: INSIDE CHM bin lock + synchronized(node)
- **New node creation**: varies by call site
Can throw (caught by catch-commit-rethrow in compute paths).

### CacheLoader.load
Lock context depends on path:
- **Initial load (new node)**: INSIDE CHM bin lock, NOT inside synchronized(node)
- **Expired/collected existing node**: INSIDE CHM bin lock + synchronized(node)

### RemovalListener.onRemoval — OUTSIDE all locks
Delivered asynchronously via executor. Safe for re-entrant cache operations.

### EvictionListener — INSIDE synchronized(node), varies for other locks
- In `evictEntry`/`removeNode`: evictionLock + CHM bin lock + synchronized(node)
- In `put()`: synchronized(node) only
- In `remove()`/compute paths: CHM bin lock + synchronized(node)
Synchronous. Re-entrant cache operations risk deadlock.

## Buffer Semantics

### Read buffer (BoundedBuffer — striped ring buffer)
- Lossy: drops are benign (affects eviction quality, not correctness)
- Lock-free CAS on per-stripe slots
- Drained under evictionLock during maintenance (single consumer)

### Write buffer (MpscGrowableArrayQueue)
- Guaranteed delivery: if offer fails after retries, acquires evictionLock
  and runs maintenance inline
- Tasks: AddTask, UpdateTask, RemovalTask
- Drained under evictionLock during maintenance (single consumer)
