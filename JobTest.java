package com.jobscheduler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDateTime;
import java.util.Arrays;

@DisplayName("Job Lifecycle Tests")
public class JobTest {

    private Job job;
    private boolean functionExecuted;

    @BeforeEach
    public void setUp() {
        functionExecuted = false;
        job = new Job(0, () -> functionExecuted = true);
    }

    @Test
    @DisplayName("Job creation should initialize with PENDING status")
    public void testJobCreationInitialState() {
        assertEquals(JobStatus.PENDING, job.getStatus());
        assertNotNull(job.getId());
        assertEquals(0, job.getSequenceNumber());
        assertNotNull(job.getSubmittedAt());
        assertNull(job.getStartedAt());
        assertNull(job.getCompletedAt());
        assertNull(job.getError());
        assertTrue(job.getFailedDependencies().isEmpty());
    }

    @Test
    @DisplayName("Job should have unique IDs")
    public void testJobIdsAreUnique() {
        Job job1 = new Job(0, () -> {});
        Job job2 = new Job(1, () -> {});
        assertNotEquals(job1.getId(), job2.getId());
    }

    @Test
    @DisplayName("Job should transition from PENDING to RUNNING")
    public void testTransitionPendingToRunning() {
        job.markAsRunning();
        assertEquals(JobStatus.RUNNING, job.getStatus());
        assertNotNull(job.getStartedAt());
    }

    @Test
    @DisplayName("Job should transition from RUNNING to COMPLETED")
    public void testTransitionRunningToCompleted() {
        job.markAsRunning();
        job.markAsCompleted();
        assertEquals(JobStatus.COMPLETED, job.getStatus());
        assertNotNull(job.getCompletedAt());
    }

    @Test
    @DisplayName("Job should transition from RUNNING to FAILED")
    public void testTransitionRunningToFailed() {
        job.markAsRunning();
        job.markAsFailed("Test error");
        assertEquals(JobStatus.FAILED, job.getStatus());
        assertEquals("Test error", job.getError());
        assertNotNull(job.getCompletedAt());
    }

    @Test
    @DisplayName("Job should transition from PENDING to CANCELLED")
    public void testTransitionPendingToCancelled() {
        job.markAsCancelled();
        assertEquals(JobStatus.CANCELLED, job.getStatus());
        assertNotNull(job.getCompletedAt());
    }

    @Test
    @DisplayName("Job should fail if marking as RUNNING from non-PENDING state")
    public void testInvalidTransitionToRunning() {
        job.markAsRunning();
        assertThrows(IllegalStateException.class, () -> job.markAsRunning());
    }

    @Test
    @DisplayName("Job should fail if marking as COMPLETED from non-RUNNING state")
    public void testInvalidTransitionToCompleted() {
        assertThrows(IllegalStateException.class, () -> job.markAsCompleted());
    }

    @Test
    @DisplayName("Job should fail if marking as CANCELLED after starting")
    public void testInvalidCancellationAfterStart() {
        job.markAsRunning();
        assertThrows(IllegalStateException.class, () -> job.markAsCancelled());
    }

    @Test
    @DisplayName("Job should fail if marking as FAILED after completion")
    public void testInvalidFailureAfterCompletion() {
        job.markAsRunning();
        job.markAsCompleted();
        assertThrows(IllegalStateException.class, () -> job.markAsFailed("Error"));
    }

    @Test
    @DisplayName("Job should capture error message on failure")
    public void testErrorCapture() {
        String errorMsg = "Database connection failed";
        job.markAsRunning();
        job.markAsFailed(errorMsg);
        assertEquals(errorMsg, job.getError());
    }

    @Test
    @DisplayName("Job should track failed dependencies")
    public void testFailedDependenciesTracking() {
        job.markAsRunning();
        job.markAsFailed("Dependency failed", Arrays.asList("job1", "job2"));
        assertEquals(2, job.getFailedDependencies().size());
        assertTrue(job.getFailedDependencies().contains("job1"));
        assertTrue(job.getFailedDependencies().contains("job2"));
    }

    @Test
    @DisplayName("Job should track sequence number")
    public void testSequenceNumber() {
        Job job1 = new Job(0, () -> {});
        Job job2 = new Job(1, () -> {});
        Job job3 = new Job(5, () -> {});

        assertEquals(0, job1.getSequenceNumber());
        assertEquals(1, job2.getSequenceNumber());
        assertEquals(5, job3.getSequenceNumber());
    }

    @Test
    @DisplayName("Job should track timestamps correctly")
    public void testTimestampTracking() throws InterruptedException {
        LocalDateTime submittedBefore = job.getSubmittedAt();

        Thread.sleep(10);
        job.markAsRunning();
        LocalDateTime startedTime = job.getStartedAt();

        Thread.sleep(10);
        job.markAsCompleted();
        LocalDateTime completedTime = job.getCompletedAt();

        assertTrue(submittedBefore.isBefore(startedTime) || submittedBefore.isEqual(startedTime));
        assertTrue(startedTime.isBefore(completedTime) || startedTime.isEqual(completedTime));
    }

    @Test
    @DisplayName("Job should execute function when in RUNNING state")
    public void testFunctionExecution() throws Exception {
        job.markAsRunning();
        job.executeFunction();
        assertTrue(functionExecuted);
    }

    @Test
    @DisplayName("Job should fail to execute function if not RUNNING")
    public void testFunctionExecutionInInvalidState() {
        assertThrows(IllegalStateException.class, () -> job.executeFunction());
    }

    @Test
    @DisplayName("Failed dependencies list should be independent copy")
    public void testFailedDependenciesIndependentCopy() {
        job.markAsRunning();
        job.markAsFailed("Failed", Arrays.asList("job1", "job2"));

        var deps = job.getFailedDependencies();
        deps.add("job3");

        assertEquals(2, job.getFailedDependencies().size());
        assertFalse(job.getFailedDependencies().contains("job3"));
    }
}
