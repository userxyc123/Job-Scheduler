# Job Scheduler Specification

## 1. Overview

A Job Scheduler is a component that accepts units of work ("jobs"), executes them concurrently, tracks their execution state, and allows consumers to query their status and results. The scheduler supports job dependencies, allowing jobs to wait for other jobs to complete before executing.

---

## 2. Design Decisions & Rationale

| Aspect | Choice | Rationale |
|--------|--------|-----------|
| **Job Representation** | Bare functions | Simple, no premature optimization. Can wrap with metadata later if needed. |
| **Processing Order** | FIFO (First In, First Out) | Jobs are anonymous with no priority metrics. Simpler implementation, easier to reason about. Can evolve to priority queue if needed. |
| **Dependencies** | Support small chains | Real-world jobs often depend on prior steps. Keeping chain length small avoids complex DAG scheduling. |
| **Concurrency Level** | Configurable, default 5 | Small default balances throughput vs. unknown resource constraints. Allows tuning per deployment. |
| **Dependency Failure Handling** | Cascading failures | If A fails and B depends on A, B fails automatically. Prevents orphaned jobs waiting forever. |
| **Storage** | In-memory | Simple for MVP. Completed/failed job records persist for query/audit. |
| **Cancellation** | Pre-start only | Simplifies state machine. Once started, job must complete or fail naturally. |

---

## 3. Core Concepts

### 3.1 Job
- **Definition**: A unit of work represented as a function with signature: `() -> Promise<void>` or `() -> void`
- **ID**: Each submitted job gets a unique identifier (UUID or incrementing integer)
- **Lifecycle**: `Pending` → `Running` → `Completed` | `Failed` | `Cancelled`

### 3.2 Job States
- **Pending**: Submitted, waiting to run (may be waiting for dependencies or concurrency limit)
- **Running**: Currently executing
- **Completed**: Finished successfully (exit code 0 or no error thrown)
- **Failed**: Threw an error, or a dependency failed
- **Cancelled**: User cancelled before it started

### 3.3 Job Dependency
- Job B can depend on Job A (one-to-one or one-to-many)
- Job B cannot start until all dependencies are in `Completed` state
- If any dependency of B fails or is cancelled, B automatically fails (cascading)
- Support chains; avoid deeply nested (e.g., A → B → C → D is reasonable, but limit to ~10 levels for now)

### 3.4 Concurrency Limit
- Maximum number of jobs running simultaneously (default: 5, configurable)
- Additional jobs queue and start as slots open

---

## 4. System Architecture

```
┌─────────────────────────────────────────┐
│         External Consumer               │
│  (submits jobs, queries status)         │
└──────────────┬──────────────────────────┘
               │
               ├─ submit(jobFn, dependsOn?)
               ├─ cancel(jobId)
               ├─ getStatus(jobId)
               └─ getAllJobs(filter?)
               │
┌──────────────▼──────────────────────────┐
│      JobScheduler (Main Component)      │
├─────────────────────────────────────────┤
│ ✓ Job Queue (FIFO)                      │
│ ✓ Running Set (concurrent jobs)         │
│ ✓ Completed/Failed Record Store         │
│ ✓ Dependency Graph                      │
│ ✓ Worker Pool (execute jobs)            │
└─────────────────────────────────────────┘
```

---

## 5. Data Structures

### 5.1 JobRecord
```
{
  id: string | number                    // Unique identifier
  sequenceNumber: number                 // FIFO order (0-indexed, increments per submission)
  function: () => Promise<void> | void   // The work to execute
  status: 'pending' | 'running' | 'completed' | 'failed' | 'cancelled'
  dependencies: string[]                 // IDs of jobs this depends on
  submittedAt: Date
  startedAt?: Date
  completedAt?: Date
  error?: string                         // Error message if failed
  failedDependencies?: string[]          // IDs of failed dependencies causing this to fail
}
```

### 5.2 Internal Structures
- **Queue**: FIFO queue of pending JobRecords waiting to run (ordered by sequenceNumber)
- **Running**: Set of currently executing job IDs
- **History**: Map of jobId → JobRecord for completed/failed/cancelled jobs
- **DependencyGraph**: Map of jobId → Set of jobIds that depend on it
- **SequenceCounter**: Incremental counter to assign sequenceNumber to each submitted job (starts at 0)

---

## 6. API / Public Interface

### 6.1 Constructor/Initialization
```
new JobScheduler(options?: {
  concurrencyLimit: number = 5
})
```

### 6.2 Submit a Job
```
submit(jobFn: () => void | Promise<void>, dependsOn?: string[]): string
// Returns: jobId
// Throws: error if dependsOn references non-existent jobs
// Throws: error if circular dependency detected (includes cycle in message)
```

### 6.3 Cancel a Job
```
cancel(jobId: string): boolean
// Returns: true if cancelled, false if already running/completed
// Side effect: removes from queue, does not stop if already running
```

### 6.4 Get Job Status
```
getStatus(jobId: string): JobRecord | undefined
// Returns: full JobRecord with current state and metadata
```

### 6.5 Get All Jobs
```
getAllJobs(filter?: { status?: string }): JobRecord[]
// Returns: array of JobRecords, optionally filtered by status
```

### 6.6 Shutdown (Optional)
```
async shutdown(): Promise<void>
// Gracefully wait for all running jobs to finish, then stop
```

---

## 7. Behavior & Job Lifecycle

### 7.1 Job Submission Flow
1. Create JobRecord with status = `pending` and sequenceNumber = (current counter)
2. Validate dependencies exist
3. **Detect circular dependencies**: Perform graph traversal from this job's dependencies; if a cycle is detected (i.e., any dependency chain leads back to this job), reject with error message indicating the cycle
4. Add to queue (FIFO)
5. Add to history map
6. Return jobId
7. Async: Try to start next job if slots available

### 7.2 Job Execution Flow
1. Check if all dependencies are `completed`
   - If any dependency is `failed` or `cancelled` → mark this job `failed`, stop
   - If any dependency is still `pending` or `running` → wait, retry later
   - If all are `completed` → proceed
2. Move job to `running`
3. Execute jobFn
4. On success → status = `completed`, set completedAt
5. On error → status = `failed`, capture error message, set completedAt
6. Notify dependent jobs to re-check if they can run

### 7.3 Dependency Cascading
- When Job A fails, iterate dependent jobs
- Mark each dependent as `failed` with failedDependencies = [A, ...]
- Cascade further down (B fails → jobs depending on B also fail)

### 7.4 Concurrency Management
- Maintain a "running" set with max size = concurrencyLimit
- After job finishes, check queue and start next eligible job (dependencies met + slots available)
- Run this check continuously (after each job completes)

---

## 8. Error Handling

### 8.1 Job Errors
- **Thrown Exception**: Catch, store error message, mark as `failed`
- **Promise Rejection**: Catch same way
- **Timeout**: Out of scope for v1 (jobs either complete or error naturally)

### 8.2 Invalid Operations
- **Submit with non-existent dependency**: Throw immediately
- **Submit with circular dependency**: Throw immediately with error message indicating detected cycle (e.g., "Circular dependency detected: A → B → A")
- **Cancel running job**: Return false (no-op)
- **Query non-existent jobId**: Return undefined
- **Cancel already-completed job**: Return false (no-op)

### 8.3 Storage Overflow
- Not in scope for v1 (in-memory storage assumed to fit)
- Could add a pruning policy later (e.g., keep last 1000 jobs)

---

## 9. Concurrency Model

### 9.1 Design
- Use a simple worker loop that checks the queue after each job finishes
- No thread pools; use async/await and single event loop
- Concurrency is achieved via promises/async, not OS threads

### 9.2 Race Conditions to Avoid
- **Job started twice**: Mark as `running` before execution starts; check before starting
- **Dependent job runs too early**: Always check dependencies before moving to `running`
- **History writes**: Since in-memory, single-threaded JS handles this safely

---

## 10. Storage & Querying

### 10.1 In-Memory Storage
- **Queue**: Array for pending jobs
- **History**: Map<jobId, JobRecord> for completed/failed/cancelled
- **Running**: Set<jobId> for currently executing jobs
- Data persists for the lifetime of the scheduler instance

### 10.2 Query Capabilities
- Get single job by ID
- Get all jobs with optional status filter
- Results can be ordered by sequenceNumber (oldest → recent)
- Include metadata: submission time, completion time, error details, failed dependencies, sequenceNumber

### 10.3 No Persistence
- Data is lost when scheduler shuts down (by design for MVP)

---

## 11. Test Strategy

### 11.1 Unit Tests
- **Submission**: Job accepts function, returns ID
- **Status Tracking**: Job moves through states correctly
- **Query**: Can retrieve job by ID, get all jobs, filter by status

### 11.2 Dependency Tests
- **Single Dependency**: B depends on A; B waits for A to complete
- **Multiple Dependencies**: C depends on A and B; C waits for both
- **Cascading Failure**: A fails → B (depends on A) fails → C (depends on B) fails
- **Cascading with Mix**: A fails, D succeeds; C depends on both → C fails
- **Circular Dependency Detection**: Submit A with dependency on B, then B with dependency on A → reject with cycle error on second submit

### 11.3 Concurrency Tests
- **Independent Jobs Run Parallel**: Submit 10 jobs, measure time; should use concurrency limit
- **Respect Limit**: With limit=2, submit 5 independent jobs; max 2 should run simultaneously
- **Dependent and Independent Mix**: A, B independent; C depends on A; B and C should run together if A is done

### 11.4 Cancellation Tests
- **Cancel Pending**: Cancel job in queue → status = `cancelled`
- **Cancel Running**: Cannot cancel; return false
- **Cancel Completed**: Cannot cancel; return false
- **Cancel Cascades (Optional)**: If A is cancelled and B depends on A, does B fail? (Define behavior)

### 11.5 Edge Cases
- **Empty Scheduler**: Query non-existent job → undefined
- **Circular Dependencies** (if applicable): Should detect and reject during submit
- **Job Throws Synchronously**: Catch and record error
- **Job Throws Asynchronously**: Catch promise rejection and record error
- **Very Fast Jobs**: Jobs completing faster than concurrent slot fills (edge case)

### 11.6 Integration Tests
- **End-to-End Flow**: Submit job → wait → query status → verify result
- **Stress Test**: Submit 100 jobs with mixed dependencies and concurrency limit
- **Timing**: Verify submittedAt, startedAt, completedAt are reasonable

---

## 12. Open Questions & Future Enhancements

1. **Job Timeouts**: Should jobs have a max execution time?
2. **Retries**: Should failed jobs auto-retry?
3. **Priority Levels**: Upgrade from FIFO to priority queue?
4. **Persistence**: Save job history to disk/database?
5. **Metrics**: Track job count, avg execution time, failure rate?

---

## 13. Success Criteria

- ✅ Jobs execute in FIFO order within concurrency constraints
- ✅ Dependencies are honored (job waits for all predecessors)
- ✅ Failures cascade (dependent jobs fail if dependency fails)
- ✅ Can query job status at any time (running, completed, failed, etc.)
- ✅ Concurrency limit is respected (max N jobs running simultaneously)
- ✅ All test scenarios pass (see section 11)
- ✅ Code is clear, testable, and extensible
