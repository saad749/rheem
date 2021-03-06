package org.qcri.rheem.core.optimizer.cardinality;

import org.qcri.rheem.core.api.Configuration;
import org.qcri.rheem.core.optimizer.OptimizationContext;
import org.qcri.rheem.core.plan.rheemplan.OutputSlot;
import org.qcri.rheem.core.plan.rheemplan.RheemPlan;
import org.qcri.rheem.core.platform.ChannelInstance;
import org.qcri.rheem.core.platform.ExecutionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;

/**
 * Handles the {@link CardinalityEstimate}s of a {@link RheemPlan}.
 */
public class CardinalityEstimatorManager {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    /**
     * The {@link RheemPlan} whose cardinalities are being managed.
     */
    private final RheemPlan rheemPlan;

    /**
     * Keeps the {@link CardinalityEstimate}s around.
     */
    private final OptimizationContext optimizationContext;

    /**
     * Provides {@link CardinalityEstimator}s etc.
     */
    private final Configuration configuration;

    private CardinalityEstimationTraversal planTraversal;

    public CardinalityEstimatorManager(RheemPlan rheemPlan,
                                       OptimizationContext optimizationContext,
                                       Configuration configuration) {
        this.rheemPlan = rheemPlan;
        this.optimizationContext = optimizationContext;
        this.configuration = configuration;
    }

    public void pushCardinalities() {
        this.getPlanTraversal().traverse(this.optimizationContext, this.configuration);
        this.optimizationContext.clearMarks();
        assert this.optimizationContext.isTimeEstimatesComplete();
    }

    public CardinalityEstimationTraversal getPlanTraversal() {
        if (this.planTraversal == null) {
            this.planTraversal = CardinalityEstimationTraversal.createPushTraversal(
                    Collections.emptyList(),
                    this.rheemPlan.collectReachableTopLevelSources(),
                    this.configuration
            );
        }
        return this.planTraversal;
    }

    /**
     * Injects the cardinalities of a current {@link ExecutionState} into its associated {@link RheemPlan}
     * (or its {@link OptimizationContext}, respectively) and then reperforms the cardinality estimation.
     */
    public void pushCardinalityUpdates(ExecutionState executionState) {
        this.injectMeasuredCardinalities(executionState);
        this.pushCardinalities();
    }

    /**
     * Injects the cardinalities of a current {@link ExecutionState} into its associated {@link RheemPlan}.
     */
    private void injectMeasuredCardinalities(ExecutionState executionState) {
        executionState.getCardinalityMeasurements().forEach(this::injectMeasuredCardinality);
    }

    /**
     * Injects the measured cardinality of a {@link ChannelInstance}.
     */
    private void injectMeasuredCardinality(ChannelInstance channelInstance) {
        assert channelInstance.wasProduced();
        assert channelInstance.isMarkedForInstrumentation();
        final long cardinality = channelInstance.getMeasuredCardinality().getAsLong();
        final OutputSlot<?> producerSlot = channelInstance.getChannel().getProducerSlot();
        int outputIndex = producerSlot == null ? 0 : producerSlot.getIndex();
        this.injectMeasuredCardinality(cardinality, channelInstance.getProducerOperatorContext(), outputIndex);
    }

    /**
     * Injects the measured {@code cardinality}.
     */
    private void injectMeasuredCardinality(long cardinality, OptimizationContext.OperatorContext targetOperatorContext, int outputIndex) {
        // Build the new CardinalityEstimate.
        final CardinalityEstimate newCardinality = new CardinalityEstimate(cardinality, cardinality, 1d, true);
        final CardinalityEstimate oldCardinality = targetOperatorContext.getOutputCardinality(outputIndex);
        if (!newCardinality.equals(oldCardinality)) {
            if (this.logger.isInfoEnabled()) {
                this.logger.info("Updating cardinality of {}'s output {} from {} to {}.",
                        targetOperatorContext.getOperator(),
                        outputIndex,
                        oldCardinality,
                        newCardinality
                );
            }
            targetOperatorContext.setOutputCardinality(outputIndex, newCardinality);
        }
    }

}
