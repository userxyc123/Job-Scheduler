package com.jobscheduler;

import java.util.*;

public class JobScheduler {
    private final int concurrencyLimit;
    private final Queue<Job> queue;
    private final Map<String, Job> allJobs;
    private final Map<String, Set<String>> dependencyGraph;
    private final Set<String> runningJobs;
    private int sequenceCounter;

    public JobScheduler(int concurrencyLimit) {
        this.concurrencyLimit = concurrencyLimit;
        this.queue = new LinkedList<>();
        this.allJobs = new HashMap<>();
        this.dependencyGraph = new HashMap<>();
        this.runningJobs = new HashSet<>();
        this.sequenceCounter = 0;
    }

    public JobScheduler() {
        this(5);
    }

    public String submit(Job.JobFunction jobFn, String... dependsOn) throws IllegalArgumentException {
        List<String> dependencies = Arrays.asList(dependsOn);

        validateDependenciesExist(dependencies);
        detectCircularDependencies(dependencies);

        Job job = new Job(sequenceCounter++, jobFn);
        for (String depId : dependencies) {
            job.addDependency(depId);
            dependencyGraph.computeIfAbsent(depId, k -> new HashSet<>()).add(job.getId());
        }

        allJobs.put(job.getId(), job);
        queue.offer(job);

        return job.getId();
    }

    public boolean cancel(String jobId) {
        Job job = allJobs.get(jobId);
        if (job == null) {
            return false;
        }

        if (job.getStatus() != JobStatus.PENDING) {
            return false;
        }

        job.markAsCancelled();
        queue.remove(job);
        return true;
    }

    public Job getStatus(String jobId) {
        return allJobs.get(jobId);
    }

    public List<Job> getAllJobs() {
        return getAllJobs(null);
    }

    public List<Job> getAllJobs(JobStatus filter) {
        List<Job> result = new ArrayList<>(allJobs.values());
        result.sort(Comparator.comparingInt(Job::getSequenceNumber));

        if (filter != null) {
            result.removeIf(job -> job.getStatus() != filter);
        }

        return result;
    }

    private void validateDependenciesExist(List<String> dependencies) {
        for (String depId : dependencies) {
            if (!allJobs.containsKey(depId)) {
                throw new IllegalArgumentException("Dependency job not found: " + depId);
            }
        }
    }

    private void detectCircularDependencies(List<String> dependencies) {
        if (dependencies.isEmpty()) {
            return;
        }

        Set<String> visited = new HashSet<>();
        Set<String> recursionStack = new HashSet<>();

        for (String depId : dependencies) {
            if (hasCycle(depId, visited, recursionStack)) {
                throw new IllegalArgumentException("Circular dependency detected in job chain");
            }
        }
    }

    private boolean hasCycle(String jobId, Set<String> visited, Set<String> recursionStack) {
        if (recursionStack.contains(jobId)) {
            return true;
        }

        if (visited.contains(jobId)) {
            return false;
        }

        visited.add(jobId);
        recursionStack.add(jobId);

        Job job = allJobs.get(jobId);
        if (job != null) {
            for (String dep : job.getDependencies()) {
                if (hasCycle(dep, visited, recursionStack)) {
                    return true;
                }
            }
        }

        recursionStack.remove(jobId);
        return false;
    }

    public int getConcurrencyLimit() {
        return concurrencyLimit;
    }

    public Queue<Job> getQueue() {
        return new LinkedList<>(queue);
    }

    public Map<String, Job> getAllJobsMap() {
        return new HashMap<>(allJobs);
    }

    public boolean canExecute(String jobId) {
        Job job = allJobs.get(jobId);
        if (job == null || job.getStatus() != JobStatus.PENDING) {
            return false;
        }

        List<String> dependencies = job.getDependencies();
        for (String depId : dependencies) {
            Job depJob = allJobs.get(depId);
            if (depJob == null) {
                return false;
            }
            if (depJob.getStatus() != JobStatus.COMPLETED) {
                return false;
            }
        }
        return true;
    }

    public String getNextExecutableJob() {
        for (Job job : queue) {
            if (canExecute(job.getId())) {
                return job.getId();
            }
        }
        return null;
    }

    public void executeJob(String jobId) throws Exception {
        Job job = allJobs.get(jobId);
        if (job == null) {
            throw new IllegalArgumentException("Job not found: " + jobId);
        }

        if (!canExecute(jobId)) {
            throw new IllegalStateException("Job cannot execute: dependencies not met or not pending");
        }

        queue.remove(job);
        runningJobs.add(jobId);
        job.markAsRunning();

        try {
            job.executeFunction();
            job.markAsCompleted();
        } catch (Exception e) {
            job.markAsFailed("Job execution failed: " + e.getMessage());
            cascadeFailure(jobId);
        } finally {
            runningJobs.remove(jobId);
        }
    }

    private void cascadeFailure(String jobId) {
        Set<String> dependents = dependencyGraph.getOrDefault(jobId, new HashSet<>());

        for (String dependentId : dependents) {
            Job dependentJob = allJobs.get(dependentId);
            if (dependentJob == null) {
                continue;
            }

            List<String> failedDeps = new ArrayList<>();
            for (String dep : dependentJob.getDependencies()) {
                Job depJob = allJobs.get(dep);
                if (depJob != null && depJob.getStatus() == JobStatus.FAILED) {
                    failedDeps.add(dep);
                }
            }

            if (dependentJob.getStatus() == JobStatus.PENDING) {
                dependentJob.markAsFailed("Dependency failed: " + jobId, failedDeps);
                queue.remove(dependentJob);
                cascadeFailure(dependentId);
            } else if (dependentJob.getStatus() == JobStatus.FAILED && !failedDeps.isEmpty()) {
                dependentJob.markAsFailed(dependentJob.getError(), failedDeps);
            }
        }
    }

    public int getRunningJobCount() {
        return runningJobs.size();
    }

    public Set<String> getRunningJobs() {
        return new HashSet<>(runningJobs);
    }
}
