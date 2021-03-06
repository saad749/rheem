package org.qcri.rheem.core.plan.rheemplan;

import org.qcri.rheem.core.api.Configuration;
import org.qcri.rheem.core.optimizer.OptimizationContext;
import org.qcri.rheem.core.optimizer.costs.LoadProfile;
import org.qcri.rheem.core.optimizer.costs.LoadProfileEstimator;
import org.qcri.rheem.core.optimizer.costs.LoadProfileEstimators;
import org.qcri.rheem.core.plan.executionplan.Channel;
import org.qcri.rheem.core.plan.executionplan.ExecutionTask;
import org.qcri.rheem.core.platform.*;
import org.qcri.rheem.core.util.Tuple;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * An execution operator is handled by a certain platform.
 */
public interface ExecutionOperator extends ElementaryOperator {

    /**
     * @return the platform that can run this operator
     */
    Platform getPlatform();

    /**
     * @return a copy of this instance; it's {@link Slot}s will not be connected
     */
    ExecutionOperator copy();

    /**
     * @return this instance or, if it was derived via {@link #copy()}, the original instance
     */
    ExecutionOperator getOriginal();

    /**
     * Developers of {@link ExecutionOperator}s can provide a default {@link LoadProfileEstimator} via this method.
     *
     * @param configuration in which the {@link LoadProfile} should be estimated.
     * @return an {@link Optional} that might contain the {@link LoadProfileEstimator} (but {@link Optional#empty()}
     * by default)
     */
    default Optional<LoadProfileEstimator<ExecutionOperator>> createLoadProfileEstimator(Configuration configuration) {
        String configurationKey = this.getLoadProfileEstimatorConfigurationKey();
        if (configurationKey == null) {
            return Optional.empty();
        }
        final Optional<String> optSpecification = configuration.getOptionalStringProperty(configurationKey);
        if (!optSpecification.isPresent()) {
            LoggerFactory
                    .getLogger(this.getClass())
                    .warn("Could not find an estimator specification associated with '{}'.", configuration);
            return Optional.empty();
        }
        return Optional.of(LoadProfileEstimators.createFromJuelSpecification(optSpecification.get()));
    }

    /**
     * Provide the {@link Configuration} key for the {@link LoadProfileEstimator} specification of this instance.
     *
     * @return the {@link Configuration} key or {@code null} if none
     */
    default String getLoadProfileEstimatorConfigurationKey() {
        return null;
    }

    /**
     * Display the supported {@link Channel}s for a certain {@link InputSlot}.
     *
     * @param index the index of the {@link InputSlot}
     * @return an {@link List} of {@link Channel}s' {@link Class}es, ordered by their preference of use
     */
    List<ChannelDescriptor> getSupportedInputChannels(int index);

    /**
     * Display the supported {@link Channel}s for a certain {@link OutputSlot}.
     *
     * @param index the index of the {@link OutputSlot}
     * @return an {@link List} of {@link Channel}s' {@link Class}es, ordered by their preference of use
     * @see #getOutputChannelDescriptor(int)
     * @deprecated {@link ExecutionOperator}s should only support a single {@link ChannelDescriptor}
     */
    @Deprecated
    List<ChannelDescriptor> getSupportedOutputChannels(int index);

    /**
     * Display the {@link Channel} used to implement a certain {@link OutputSlot}.
     *
     * @param index index of the {@link OutputSlot}
     * @return the {@link ChannelDescriptor} for the mentioned {@link Channel}
     */
    default ChannelDescriptor getOutputChannelDescriptor(int index) {
        final List<ChannelDescriptor> supportedOutputChannels = this.getSupportedOutputChannels(index);
        assert !supportedOutputChannels.isEmpty() : String.format("No supported output channels for %s.", this);
        if (supportedOutputChannels.size() > 1) {
            LoggerFactory.getLogger(this.getClass()).warn("Treat {} as the only supported channel for {}.",
                    supportedOutputChannels.get(0), this.getOutput(index)
            );
        }
        return supportedOutputChannels.get(0);
    }

    /**
     * Create output {@link ChannelInstance}s for this instance, thereby also setting up the
     * {@link org.qcri.rheem.core.platform.LazyChannelLineage} properly.
     *
     * @param task                    the {@link ExecutionTask} in which this instance is being wrapped
     * @param producerOperatorContext the {@link OptimizationContext.OperatorContext} for this instance
     * @param inputChannelInstances   the input {@link ChannelInstance}s for the {@code task}
     * @return
     */
    default ChannelInstance[] createOutputChannelInstances(Executor executor,
                                                           ExecutionTask task,
                                                           OptimizationContext.OperatorContext producerOperatorContext,
                                                           List<ChannelInstance> inputChannelInstances) {

        assert task.getOperator() == this;
        ChannelInstance[] channelInstances = new ChannelInstance[task.getNumOuputChannels()];
        for (int outputIndex = 0; outputIndex < channelInstances.length; outputIndex++) {
            final Channel outputChannel = task.getOutputChannel(outputIndex);
            final ChannelInstance outputChannelInstance = outputChannel.createInstance(executor, producerOperatorContext, outputIndex);
            channelInstances[outputIndex] = outputChannelInstance;
        }
        return channelInstances;
    }

    static Tuple<Collection<OptimizationContext.OperatorContext>, Collection<ChannelInstance>> modelEagerExecution(
            ChannelInstance[] inputs,
            ChannelInstance[] outputs,
            OptimizationContext.OperatorContext operatorContext) {
        final Tuple<Collection<OptimizationContext.OperatorContext>, Collection<ChannelInstance>> collectors;
        if (outputs.length == 0) {
            collectors = inputs[0].getLazyChannelLineage().collectAndMark();
            collectors.getField0().add(operatorContext);
        } else {
            collectors = new Tuple<>(new LinkedList<>(), new LinkedList<>());
            LazyChannelLineage.addAllPredecessors(inputs, outputs);
            for (ChannelInstance output : outputs) {
                output.getLazyChannelLineage().collectAndMark(collectors.getField0(), collectors.getField1());
            }
        }
        return collectors;
    }

    static Tuple<Collection<OptimizationContext.OperatorContext>, Collection<ChannelInstance>>
    modelLazyExecution(ChannelInstance[] inputs,
                       ChannelInstance[] outputs,
                       OptimizationContext.OperatorContext operatorContext) {
        LazyChannelLineage.addAllPredecessors(inputs, outputs);
        return new Tuple<>(Collections.emptyList(), Collections.emptyList());
    }

}
