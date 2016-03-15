package org.qcri.rheem.core.optimizer.enumeration;

import org.apache.commons.lang3.Validate;
import org.qcri.rheem.core.api.Configuration;
import org.qcri.rheem.core.optimizer.costs.LoadProfile;
import org.qcri.rheem.core.optimizer.costs.LoadProfileEstimator;
import org.qcri.rheem.core.optimizer.costs.LoadProfileToTimeConverter;
import org.qcri.rheem.core.optimizer.costs.TimeEstimate;
import org.qcri.rheem.core.plan.executionplan.*;
import org.qcri.rheem.core.plan.rheemplan.ExecutionOperator;

import java.util.*;
import java.util.stream.Stream;

/**
 * Graph of {@link ExecutionTask}s and {@link Channel}s. Does not define {@link ExecutionStage}s and
 * {@link PlatformExecution}s - in contrast to a final {@link ExecutionPlan}.
 */
public class PreliminaryExecutionPlan {

    private final Collection<ExecutionTask> sinkTasks;

    private final Set<Channel> inputChannels;

    private TimeEstimate timeEstimate;

    public PreliminaryExecutionPlan(Collection<ExecutionTask> sinkTasks) {
        this(sinkTasks, Collections.emptySet());
    }

    public PreliminaryExecutionPlan(Collection<ExecutionTask> sinkTasks, Set<Channel> inputChannels) {
        Validate.notEmpty(sinkTasks, "Cannot build plan without sinks.");
        this.sinkTasks = sinkTasks;
        this.inputChannels = inputChannels;
    }

    public TimeEstimate estimateExecutionTime(Configuration configuration) {
        if (this.timeEstimate == null) {
            this.timeEstimate = this.collectAllTasks().stream()
                    .map(task -> this.getOrCalculateTimeEstimateFor(task, configuration))
                    .reduce(TimeEstimate::plus)
                    .orElseThrow(() -> new IllegalStateException("No time estimate found."));
        }
        return this.timeEstimate;
    }

    public Set<ExecutionTask> collectAllTasks() {
        Set<ExecutionTask> collector = new HashSet<>();
        this.collectAllTasksAux(this.sinkTasks.stream(), collector);
        return collector;
    }

    private void collectAllTasksAux(Stream<ExecutionTask> currentTasks, Set<ExecutionTask> collector) {
        currentTasks.forEach(task -> this.collectAllTasksAux(task, collector));
    }


    private void collectAllTasksAux(ExecutionTask currentTask, Set<ExecutionTask> collector) {
        if (collector.add(currentTask)) {
            final Stream<ExecutionTask> producerStream = Arrays.stream(currentTask.getInputChannels())
                    .filter(Objects::nonNull)
                    .map(Channel::getProducer)
                    .filter(Objects::nonNull);
            this.collectAllTasksAux(producerStream, collector);
        }
    }

    private TimeEstimate getOrCalculateTimeEstimateFor(ExecutionTask task, Configuration configuration) {
        final ExecutionOperator operator = task.getOperator();

        // Calculate and chache the TimeEstimate if it does not exist yet.
        if (operator.getTimeEstimate() == null) {
            final LoadProfileEstimator loadProfileEstimator =
                    configuration.getOperatorLoadProfileEstimatorProvider().provideFor(operator);
            final LoadProfile loadProfile = loadProfileEstimator.estimate(operator);
            final LoadProfileToTimeConverter converter = configuration.getLoadProfileToTimeConverterProvider().provide();
            final TimeEstimate timeEstimate = converter.convert(loadProfile);
            operator.setTimeEstimate(timeEstimate);
        }

        // Answer the cached TimeEstimate.
        return operator.getTimeEstimate();
    }

    public TimeEstimate getTimeEstimate() {
        return this.timeEstimate;
    }

    public ExecutionPlan toExecutionPlan(StageAssignmentTraversal.StageSplittingCriterion... splittingCriteria) {
        return new StageAssignmentTraversal(this, splittingCriteria).run();
    }

    public boolean isComplete() {
        final Set<ExecutionTask> allTasks = this.collectAllTasks();
        if (allTasks.isEmpty()) {
            return false;
        }
        for (ExecutionTask task : allTasks) {
            if (Arrays.stream(task.getOutputChannels()).anyMatch(Objects::isNull)) {
                return false;
            }
            if (Arrays.stream(task.getInputChannels()).anyMatch(Objects::isNull)) {
                return false;
            }
        }
        return true;
    }

    public Collection<ExecutionTask> getSinkTasks() {
        return this.sinkTasks;
    }

    public Set<Channel> getInputChannels() {
        return this.inputChannels;
    }
}