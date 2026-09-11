package com.jobscheduler;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class Job {
    private final String id;
    private final int sequenceNumber;
    private final JobFunction function;
    private JobStatus status;
    private LocalDateTime submittedAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private String error;
    private List<String> failedDependencies;
    private List<String> dependencies;

    @FunctionalInterface
    public interface JobFunction {
        void execute() throws Exception;
    }

    public Job(int sequenceNumber, JobFunction function) {
        this.id = UUID.randomUUID().toString();
        this.sequenceNumber = sequenceNumber;
        this.function = function;
        this.status = JobStatus.PENDING;
        this.submittedAt = LocalDateTime.now();
        this.failedDependencies = new ArrayList<>();
        this.dependencies = new ArrayList<>();
    }

    public void markAsRunning() {
        if (status != JobStatus.PENDING) {
            throw new IllegalStateException("Cannot mark job as running. Current status: " + status);
        }
        this.status = JobStatus.RUNNING;
        this.startedAt = LocalDateTime.now();
    }

    public void markAsCompleted() {
        if (status != JobStatus.RUNNING) {
            throw new IllegalStateException("Cannot mark job as completed. Current status: " + status);
        }
        this.status = JobStatus.COMPLETED;
        this.completedAt = LocalDateTime.now();
    }

    public void markAsFailed(String errorMessage) {
        if (status == JobStatus.COMPLETED || status == JobStatus.CANCELLED) {
            throw new IllegalStateException("Cannot mark job as failed. Current status: " + status);
        }
        this.status = JobStatus.FAILED;
        this.error = errorMessage;
        this.completedAt = LocalDateTime.now();
    }

    public void markAsFailed(String errorMessage, List<String> failedDependencies) {
        markAsFailed(errorMessage);
        this.failedDependencies = failedDependencies;
    }

    public void markAsCancelled() {
        if (status != JobStatus.PENDING) {
            throw new IllegalStateException("Cannot cancel job. Current status: " + status);
        }
        this.status = JobStatus.CANCELLED;
        this.completedAt = LocalDateTime.now();
    }

    public void executeFunction() throws Exception {
        if (status != JobStatus.RUNNING) {
            throw new IllegalStateException("Job is not in RUNNING state");
        }
        function.execute();
    }

    // Getters
    public String getId() {
        return id;
    }

    public int getSequenceNumber() {
        return sequenceNumber;
    }

    public JobStatus getStatus() {
        return status;
    }

    public LocalDateTime getSubmittedAt() {
        return submittedAt;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public String getError() {
        return error;
    }

    public List<String> getFailedDependencies() {
        return new ArrayList<>(failedDependencies);
    }

    public JobFunction getFunction() {
        return function;
    }

    public void addDependency(String jobId) {
        dependencies.add(jobId);
    }

    public List<String> getDependencies() {
        return new ArrayList<>(dependencies);
    }
}
