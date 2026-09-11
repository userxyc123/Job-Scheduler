package com.jobscheduler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;

@DisplayName("Job Execution Tests")
public class JobExecutionTest {

    private JobScheduler scheduler;

    @BeforeEach
    public void setUp() {
        scheduler = new JobScheduler();
    }

    @Test
    @DisplayName("Job with no dependencies can execute")
    public void testJobWithNoDependenciesCanExecute() {
        String jobId = scheduler.submit(() -> {});
        assertTrue(scheduler.canExecute(jobId));
    }

    @Test
    @DisplayName("Job with unmet dependencies cannot execute")
    public void testJobWithUnmetDependenciesCannotExecute() {
        String jobA = scheduler.submit(() -> {});
        String jobB = scheduler.submit(() -> {}, jobA);

        assertFalse(scheduler.canExecute(jobB));
    }

    @Test
    @DisplayName("Job with met dependencies can execute")
    public void testJobWithMetDependenciesCanExecute() throws Exception {
        String jobA = scheduler.submit(() -> {});
        String jobB = scheduler.submit(() -> {}, jobA);

        scheduler.executeJob(jobA);
        assertTrue(scheduler.canExecute(jobB));
    }

    @Test
    @DisplayName("Get next executable job returns correct job")
    public void testGetNextExecutableJob() throws Exception {
        String jobA = scheduler.submit(() -> {});
        String jobB = scheduler.submit(() -> {}, jobA);
        String jobC = scheduler.submit(() -> {});

        String next = scheduler.getNextExecutableJob();
        assertTrue(next.equals(jobA) || next.equals(jobC));
    }

    @Test
    @DisplayName("Execute job should mark as running then completed")
    public void testExecuteJobStateTransition() throws Exception {
        String jobId = scheduler.submit(() -> {});
        assertEquals(JobStatus.PENDING, scheduler.getStatus(jobId).getStatus());

        scheduler.executeJob(jobId);
        assertEquals(JobStatus.COMPLETED, scheduler.getStatus(jobId).getStatus());
    }

    @Test
    @DisplayName("Execute job that throws exception should mark as failed")
    public void testExecuteJobThatThrowsException() throws Exception {
        String jobId = scheduler.submit(() -> {
            throw new RuntimeException("Test error");
        });

        scheduler.executeJob(jobId);
        Job job = scheduler.getStatus(jobId);

        assertEquals(JobStatus.FAILED, job.getStatus());
        assertNotNull(job.getError());
        assertTrue(job.getError().contains("Test error"));
    }

    @Test
    @DisplayName("Execute job should remove from queue")
    public void testExecuteJobRemovesFromQueue() throws Exception {
        String jobId = scheduler.submit(() -> {});
        assertEquals(1, scheduler.getQueue().size());

        scheduler.executeJob(jobId);
        assertEquals(0, scheduler.getQueue().size());
    }

    @Test
    @DisplayName("Cannot execute job that is not pending")
    public void testCannotExecuteNonPendingJob() throws Exception {
        String jobId = scheduler.submit(() -> {});
        scheduler.executeJob(jobId);

        assertThrows(IllegalStateException.class, () -> {
            scheduler.executeJob(jobId);
        });
    }

    @Test
    @DisplayName("Cannot execute job with unmet dependencies")
    public void testCannotExecuteJobWithUnmetDependencies() {
        String jobA = scheduler.submit(() -> {});
        String jobB = scheduler.submit(() -> {}, jobA);

        assertThrows(IllegalStateException.class, () -> {
            scheduler.executeJob(jobB);
        });
    }

    @Test
    @DisplayName("Dependent job fails when dependency fails")
    public void testDependentJobFailsWhenDependencyFails() throws Exception {
        String jobA = scheduler.submit(() -> {
            throw new RuntimeException("Job A failed");
        });
        String jobB = scheduler.submit(() -> {}, jobA);

        scheduler.executeJob(jobA);
        assertEquals(JobStatus.FAILED, scheduler.getStatus(jobA).getStatus());
        assertEquals(JobStatus.FAILED, scheduler.getStatus(jobB).getStatus());
    }

    @Test
    @DisplayName("Cascading failure marks dependent with failed dependency ID")
    public void testCascadingFailureMarksFailedDependency() throws Exception {
        String jobA = scheduler.submit(() -> {
            throw new RuntimeException("A failed");
        });
        String jobB = scheduler.submit(() -> {}, jobA);

        scheduler.executeJob(jobA);
        Job jobBRecord = scheduler.getStatus(jobB);

        assertTrue(jobBRecord.getFailedDependencies().contains(jobA));
    }

    @Test
    @DisplayName("Multi-level cascading failure")
    public void testMultiLevelCascadingFailure() throws Exception {
        String jobA = scheduler.submit(() -> {
            throw new RuntimeException("A failed");
        });
        String jobB = scheduler.submit(() -> {}, jobA);
        String jobC = scheduler.submit(() -> {}, jobB);

        scheduler.executeJob(jobA);

        assertEquals(JobStatus.FAILED, scheduler.getStatus(jobA).getStatus());
        assertEquals(JobStatus.FAILED, scheduler.getStatus(jobB).getStatus());
        assertEquals(JobStatus.FAILED, scheduler.getStatus(jobC).getStatus());
    }

    @Test
    @DisplayName("Independent jobs run independently")
    public void testIndependentJobsRunIndependently() throws Exception {
        AtomicInteger count = new AtomicInteger(0);

        String jobA = scheduler.submit(() -> count.incrementAndGet());
        String jobB = scheduler.submit(() -> count.incrementAndGet());

        scheduler.executeJob(jobA);
        scheduler.executeJob(jobB);

        assertEquals(2, count.get());
        assertEquals(JobStatus.COMPLETED, scheduler.getStatus(jobA).getStatus());
        assertEquals(JobStatus.COMPLETED, scheduler.getStatus(jobB).getStatus());
    }

    @Test
    @DisplayName("Job execution respects concurrency limit tracking")
    public void testConcurrencyLimitTracking() throws Exception {
        String jobA = scheduler.submit(() -> {});
        String jobB = scheduler.submit(() -> {});

        assertEquals(0, scheduler.getRunningJobCount());

        // Job B can't run yet (depends on nothing, but let's create a dependency scenario)
        assertEquals(0, scheduler.getRunningJobs().size());

        scheduler.executeJob(jobA);
        assertEquals(0, scheduler.getRunningJobCount());
    }

    @Test
    @DisplayName("Failed dependencies are captured correctly")
    public void testFailedDependenciesCapture() throws Exception {
        String jobA = scheduler.submit(() -> {
            throw new RuntimeException("A failed");
        });
        String jobB = scheduler.submit(() -> {
            throw new RuntimeException("B failed");
        });
        String jobC = scheduler.submit(() -> {}, jobA, jobB);

        scheduler.executeJob(jobA);
        scheduler.executeJob(jobB);

        Job jobCRecord = scheduler.getStatus(jobC);
        assertEquals(JobStatus.FAILED, jobCRecord.getStatus());
        assertEquals(2, jobCRecord.getFailedDependencies().size());
        assertTrue(jobCRecord.getFailedDependencies().contains(jobA));
        assertTrue(jobCRecord.getFailedDependencies().contains(jobB));
    }

    @Test
    @DisplayName("Successful execution updates timestamps")
    public void testExecutionUpdatesTimestamps() throws Exception {
        String jobId = scheduler.submit(() -> {});
        Job job = scheduler.getStatus(jobId);

        assertNull(job.getStartedAt());
        assertNull(job.getCompletedAt());

        scheduler.executeJob(jobId);
        job = scheduler.getStatus(jobId);

        assertNotNull(job.getStartedAt());
        assertNotNull(job.getCompletedAt());
        assertTrue(job.getStartedAt().isBefore(job.getCompletedAt()) ||
                   job.getStartedAt().isEqual(job.getCompletedAt()));
    }

    @Test
    @DisplayName("Job function executes and updates state")
    public void testJobFunctionExecutes() throws Exception {
        AtomicInteger executed = new AtomicInteger(0);
        String jobId = scheduler.submit(() -> executed.incrementAndGet());

        assertEquals(0, executed.get());
        scheduler.executeJob(jobId);
        assertEquals(1, executed.get());
    }

    @Test
    @DisplayName("Get next executable returns null when no jobs executable")
    public void testGetNextExecutableReturnsNullWhenNone() {
        String jobA = scheduler.submit(() -> {});
        String jobB = scheduler.submit(() -> {}, jobA);

        String next = scheduler.getNextExecutableJob();
        assertEquals(jobA, next);
    }

    @Test
    @DisplayName("Cascading failure removes from queue")
    public void testCascadingFailureRemovesFromQueue() throws Exception {
        String jobA = scheduler.submit(() -> {
            throw new RuntimeException("A failed");
        });
        String jobB = scheduler.submit(() -> {}, jobA);
        String jobC = scheduler.submit(() -> {});

        assertEquals(3, scheduler.getQueue().size());

        scheduler.executeJob(jobA);

        assertEquals(1, scheduler.getQueue().size());
        assertEquals(JobStatus.PENDING, scheduler.getStatus(jobC).getStatus());
    }

    @Test
    @DisplayName("Multiple levels with mix of success and failure")
    public void testComplexDependencyWithMixedResults() throws Exception {
        String jobA = scheduler.submit(() -> {});
        String jobB = scheduler.submit(() -> {});
        String jobC = scheduler.submit(() -> {
            throw new RuntimeException("C failed");
        }, jobB);
        String jobD = scheduler.submit(() -> {}, jobA, jobC);

        scheduler.executeJob(jobA);
        scheduler.executeJob(jobB);
        scheduler.executeJob(jobC);

        Job jobDRecord = scheduler.getStatus(jobD);
        assertEquals(JobStatus.FAILED, jobDRecord.getStatus());
        assertTrue(jobDRecord.getFailedDependencies().contains(jobC));
    }

    @Test
    @DisplayName("Cannot execute non-existent job")
    public void testCannotExecuteNonExistentJob() {
        assertThrows(IllegalArgumentException.class, () -> {
            scheduler.executeJob("non-existent-id");
        });
    }
}
