package org.qcri.rheem.core.platform;

import org.qcri.rheem.core.optimizer.OptimizationContext;
import org.qcri.rheem.core.plan.executionplan.Channel;
import org.qcri.rheem.core.plan.rheemplan.ExecutionOperator;

import java.util.OptionalLong;

/**
 * Represents the actual, allocated resource represented by {@link Channel}.
 */
public interface ChannelInstance extends ExecutionResource {

    /**
     * @return the {@link Channel} that is implemented by this instance
     */
    Channel getChannel();

    /**
     * Optionally provides the measured cardinality of this instance. However, such a cardinality might not be available
     * for several reasons. For instance, the measurement might not have been requested or could not be implemented
     * by the executing {@link Platform}.
     *
     * @return the measured cardinality if available
     */
    OptionalLong getMeasuredCardinality();

    /**
     * Register the measured cardinality with this instance.
     */
    void setMeasuredCardinality(long cardinality);

    /**
     * Tells whether this instance should be instrumented
     */
    default boolean isMarkedForInstrumentation() {
        return this.getChannel().isMarkedForInstrumentation();
    }

    /**
     * Provides a {@link LazyChannelLineage} that keeps around (at least) all non-executed predecessor
     * {@link ChannelInstance}s.
     *
     * @return the {@link LazyChannelLineage}
     */
    LazyChannelLineage getLazyChannelLineage();

    /**
     * Adds a {@link ChannelInstance} as a predecessor in the {@link LazyChannelLineage}.
     *
     * @param channelInstance the {@link ChannelInstance}
     */
    default void addPredecessor(ChannelInstance channelInstance) {
        if (!channelInstance.wasProduced()) {
            this.getLazyChannelLineage().addPredecessor(channelInstance.getLazyChannelLineage());
        }
    }

    /**
     * Tells whether this instance was already produced.
     *
     * @return whether this instance was already produced
     */
    boolean wasProduced();

    /**
     * Mark this instance as produced.
     */
    void markProduced();

    /**
     * Retrieve the {@link OptimizationContext.OperatorContext} of the {@link ExecutionOperator} producing this instance.
     *
     * @return the {@link OptimizationContext.OperatorContext}
     */
    OptimizationContext.OperatorContext getProducerOperatorContext();

}
