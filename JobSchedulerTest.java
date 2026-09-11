package com.jobscheduler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

@DisplayName("JobScheduler Tests")
public class JobSchedulerTest {

    private JobScheduler scheduler;

    @BeforeEach
    public void setUp() {
        scheduler = new JobScheduler();
    }

    @Test
    @DisplayName("Default scheduler should have concurrency limit of 5")
    public void testDefaultConcurrencyLimit() {
        assertEquals(5, scheduler.getConcurrencyLimit());
    }

    @Test
    @DisplayName("Scheduler with custom concurrency limit")
    public void testCustomConcurrencyLimit() {
        JobScheduler customScheduler = new JobScheduler(10);
        assertEquals(10, customScheduler.getConcurrencyLimit());
    }

    @Test
    @DisplayName("Submit a job should return a unique job ID")
    public void testSubmitJobReturnsId() {
        String jobId = scheduler.submit(() -> {});
        assertNotNull(jobId);
        assertFalse(jobId.isEmpty());
    }

    @Test
    @DisplayName("Submitted jobs should have unique IDs")
    public void testSubmitMultipleJobsHaveUniqueIds() {
        String id1 = scheduler.submit(() -> {});
        String id2 = scheduler.submit(() -> {});
        String id3 = scheduler.submit(() -> {});

        assertNotEquals(id1, id2);
        assertNotEquals(id2, id3);
        assertNotEquals(id1, id3);
    }

    @Test
    @DisplayName("Get status should return submitted job")
    public void testGetStatus() {
        String jobId = scheduler.submit(() -> {});
        Job job = scheduler.getStatus(jobId);

        assertNotNull(job);
        assertEquals(jobId, job.getId());
        assertEquals(JobStatus.PENDING, job.getStatus());
    }

    @Test
    @DisplayName("Get status for non-existent job should return null")
    public void testGetStatusNonExistent() {
        Job job = scheduler.getStatus("non-existent-id");
        assertNull(job);
    }

    @Test
    @DisplayName("Get all jobs should return all submitted jobs in sequence order")
    public void testGetAllJobs() {
        String id1 = scheduler.submit(() -> {});
        String id2 = scheduler.submit(() -> {});
        String id3 = scheduler.submit(() -> {});

        List<Job> jobs = scheduler.getAllJobs();

        assertEquals(3, jobs.size());
        assertEquals(0, jobs.get(0).getSequenceNumber());
        assertEquals(1, jobs.get(1).getSequenceNumber());
        assertEquals(2, jobs.get(2).getSequenceNumber());
    }

    @Test
    @DisplayName("Get all jobs with filter should return only matching status")
    public void testGetAllJobsWithFilter() {
        String id1 = scheduler.submit(() -> {});
        String id2 = scheduler.submit(() -> {});

        Job job1 = scheduler.getStatus(id1);
        job1.markAsRunning();
        job1.markAsCompleted();

        List<Job> completedJobs = scheduler.getAllJobs(JobStatus.COMPLETED);
        List<Job> pendingJobs = scheduler.getAllJobs(JobStatus.PENDING);

        assertEquals(1, completedJobs.size());
        assertEquals(1, pendingJobs.size());
        assertEquals(JobStatus.COMPLETED, completedJobs.get(0).getStatus());
        assertEquals(JobStatus.PENDING, pendingJobs.get(0).getStatus());
    }

    @Test
    @DisplayName("Cancel pending job should mark as cancelled")
    public void testCancelPendingJob() {
        String jobId = scheduler.submit(() -> {});
        boolean cancelled = scheduler.cancel(jobId);

        assertTrue(cancelled);
        assertEquals(JobStatus.CANCELLED, scheduler.getStatus(jobId).getStatus());
    }

    @Test
    @DisplayName("Cancel running job should return false")
    public void testCancelRunningJobReturnsFalse() {
        String jobId = scheduler.submit(() -> {});
        Job job = scheduler.getStatus(jobId);
        job.markAsRunning();

        boolean cancelled = scheduler.cancel(jobId);
        assertFalse(cancelled);
        assertEquals(JobStatus.RUNNING, job.getStatus());
    }

    @Test
    @DisplayName("Cancel completed job should return false")
    public void testCancelCompletedJobReturnsFalse() {
        String jobId = scheduler.submit(() -> {});
        Job job = scheduler.getStatus(jobId);
        job.markAsRunning();
        job.markAsCompleted();

        boolean cancelled = scheduler.cancel(jobId);
        assertFalse(cancelled);
        assertEquals(JobStatus.COMPLETED, job.getStatus());
    }

    @Test
    @DisplayName("Cancel non-existent job should return false")
    public void testCancelNonExistentJobReturnsFalse() {
        boolean cancelled = scheduler.cancel("non-existent-id");
        assertFalse(cancelled);
    }

    @Test
    @DisplayName("Submit with non-existent dependency should throw")
    public void testSubmitWithNonExistentDependencyShouldThrow() {
        assertThrows(IllegalArgumentException.class, () -> {
            scheduler.submit(() -> {}, "non-existent-job-id");
        });
    }

    @Test
    @DisplayName("Submit with valid single dependency should succeed")
    public void testSubmitWithValidSingleDependency() {
        String jobA = scheduler.submit(() -> {});
        String jobB = scheduler.submit(() -> {}, jobA);

        assertNotNull(jobB);
        assertTrue(scheduler.getStatus(jobB).getDependencies().contains(jobA));
    }

    @Test
    @DisplayName("Submit with multiple valid dependencies should succeed")
    public void testSubmitWithMultipleDependencies() {
        String jobA = scheduler.submit(() -> {});
        String jobB = scheduler.submit(() -> {});
        String jobC = scheduler.submit(() -> {}, jobA, jobB);

        Job jobCRecord = scheduler.getStatus(jobC);
        assertEquals(2, jobCRecord.getDependencies().size());
        assertTrue(jobCRecord.getDependencies().contains(jobA));
        assertTrue(jobCRecord.getDependencies().contains(jobB));
    }

    @Test
    @DisplayName("Dependency chain with multiple levels should be allowed")
    public void testMultipleLevelDependencyChainAllowed() {
        String jobA = scheduler.submit(() -> {});
        String jobB = scheduler.submit(() -> {}, jobA);
        String jobC = scheduler.submit(() -> {}, jobB, jobA);

        Job jobCRecord = scheduler.getStatus(jobC);
        assertEquals(2, jobCRecord.getDependencies().size());
        assertTrue(jobCRecord.getDependencies().contains(jobA));
        assertTrue(jobCRecord.getDependencies().contains(jobB));
    }

    @Test
    @DisplayName("Dependency chain A->B->C should be allowed")
    public void testThreeStepDependencyChainAllowed() {
        String jobA = scheduler.submit(() -> {});
        String jobB = scheduler.submit(() -> {}, jobA);
        String jobC = scheduler.submit(() -> {}, jobB);

        Job jobCRecord = scheduler.getStatus(jobC);
        assertEquals(1, jobCRecord.getDependencies().size());
        assertTrue(jobCRecord.getDependencies().contains(jobB));
    }

    @Test
    @DisplayName("Dependency chain A->B->C->D should be allowed")
    public void testFourStepDependencyChainAllowed() {
        String jobA = scheduler.submit(() -> {});
        String jobB = scheduler.submit(() -> {}, jobA);
        String jobC = scheduler.submit(() -> {}, jobB);
        String jobD = scheduler.submit(() -> {}, jobC);

        Job jobDRecord = scheduler.getStatus(jobD);
        assertEquals(1, jobDRecord.getDependencies().size());
        assertTrue(jobDRecord.getDependencies().contains(jobC));
    }

    @Test
    @DisplayName("Jobs should maintain sequence numbers for FIFO ordering")
    public void testSequenceNumbersForFIFOOrdering() {
        String id1 = scheduler.submit(() -> {});
        String id2 = scheduler.submit(() -> {});
        String id3 = scheduler.submit(() -> {});
        String id4 = scheduler.submit(() -> {});

        List<Job> jobs = scheduler.getAllJobs();
        for (int i = 0; i < jobs.size(); i++) {
            assertEquals(i, jobs.get(i).getSequenceNumber());
        }
    }

    @Test
    @DisplayName("Job queue should maintain FIFO order")
    public void testQueueMaintainsFIFOOrder() {
        String id1 = scheduler.submit(() -> {});
        String id2 = scheduler.submit(() -> {});
        String id3 = scheduler.submit(() -> {});

        List<Job> queuedJobs = new java.util.ArrayList<>(scheduler.getQueue());
        assertEquals(3, queuedJobs.size());
        assertEquals(0, queuedJobs.get(0).getSequenceNumber());
        assertEquals(1, queuedJobs.get(1).getSequenceNumber());
        assertEquals(2, queuedJobs.get(2).getSequenceNumber());
    }

    @Test
    @DisplayName("Cancelled job should be removed from queue")
    public void testCancelledJobRemovedFromQueue() {
        String id1 = scheduler.submit(() -> {});
        String id2 = scheduler.submit(() -> {});
        String id3 = scheduler.submit(() -> {});

        scheduler.cancel(id2);

        List<Job> queuedJobs = new java.util.ArrayList<>(scheduler.getQueue());
        assertEquals(2, queuedJobs.size());
        assertEquals(0, queuedJobs.get(0).getSequenceNumber());
        assertEquals(2, queuedJobs.get(1).getSequenceNumber());
    }

    @Test
    @DisplayName("Non-circular dependency chain should be allowed")
    public void testNonCircularDependencyChainAllowed() {
        String jobA = scheduler.submit(() -> {});
        String jobB = scheduler.submit(() -> {}, jobA);
        String jobC = scheduler.submit(() -> {}, jobB);
        String jobD = scheduler.submit(() -> {}, jobC);

        Job jobDRecord = scheduler.getStatus(jobD);
        assertEquals(1, jobDRecord.getDependencies().size());
        assertTrue(jobDRecord.getDependencies().contains(jobC));
    }

    @Test
    @DisplayName("Get all jobs should maintain order across different statuses")
    public void testGetAllJobsMaintainsOrderAcrossStatuses() {
        String id1 = scheduler.submit(() -> {});
        String id2 = scheduler.submit(() -> {});
        String id3 = scheduler.submit(() -> {});

        Job job1 = scheduler.getStatus(id1);
        job1.markAsRunning();

        Job job3 = scheduler.getStatus(id3);
        job3.markAsRunning();
        job3.markAsCompleted();

        List<Job> allJobs = scheduler.getAllJobs();
        assertEquals(3, allJobs.size());
        assertEquals(0, allJobs.get(0).getSequenceNumber());
        assertEquals(1, allJobs.get(1).getSequenceNumber());
        assertEquals(2, allJobs.get(2).getSequenceNumber());
    }
}
