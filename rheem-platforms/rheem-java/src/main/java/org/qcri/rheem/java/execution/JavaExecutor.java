package org.qcri.rheem.java.execution;

import org.qcri.rheem.core.api.Job;
import org.qcri.rheem.core.api.exception.RheemException;
import org.qcri.rheem.core.function.ExtendedFunction;
import org.qcri.rheem.core.optimizer.OptimizationContext;
import org.qcri.rheem.core.plan.executionplan.ExecutionTask;
import org.qcri.rheem.core.plan.rheemplan.ExecutionOperator;
import org.qcri.rheem.core.platform.ChannelInstance;
import org.qcri.rheem.core.platform.Executor;
import org.qcri.rheem.core.platform.PartialExecution;
import org.qcri.rheem.core.platform.PushExecutorTemplate;
import org.qcri.rheem.core.util.Tuple;
import org.qcri.rheem.java.compiler.FunctionCompiler;
import org.qcri.rheem.java.operators.JavaExecutionOperator;
import org.qcri.rheem.java.platform.JavaPlatform;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * {@link Executor} implementation for the {@link JavaPlatform}.
 */
public class JavaExecutor extends PushExecutorTemplate {

    private final JavaPlatform platform;

    private final FunctionCompiler compiler;

    public JavaExecutor(JavaPlatform javaPlatform, Job job) {
        super(job);
        this.platform = javaPlatform;
        this.compiler = new FunctionCompiler(job.getConfiguration());
    }

    @Override
    public JavaPlatform getPlatform() {
        return this.platform;
    }

    @Override
    protected Tuple<List<ChannelInstance>, PartialExecution> execute(
            ExecutionTask task,
            List<ChannelInstance> inputChannelInstances,
            OptimizationContext.OperatorContext producerOperatorContext,
            boolean isForceExecution
    ) {
        // Provide the ChannelInstances for the output of the task.
        final ChannelInstance[] outputChannelInstances = task.getOperator().createOutputChannelInstances(
                this, task, producerOperatorContext, inputChannelInstances
        );

        // Execute.
        final Collection<OptimizationContext.OperatorContext> operatorContexts;
        final Collection<ChannelInstance> producedChannelInstances;
        long startTime = System.currentTimeMillis();
        try {
            final Tuple<Collection<OptimizationContext.OperatorContext>, Collection<ChannelInstance>> results =
                    cast(task.getOperator()).evaluate(
                            toArray(inputChannelInstances),
                            outputChannelInstances,
                            this,
                            producerOperatorContext
                    );
            operatorContexts = results.getField0();
            producedChannelInstances = results.getField1();
        } catch (Exception e) {
            throw new RheemException(String.format("Executing %s failed.", task), e);
        }
        long endTime = System.currentTimeMillis();
        long executionDuration = endTime - startTime;

        // Check how much we executed.
        PartialExecution partialExecution = this.createPartialExecution(operatorContexts, executionDuration);

        // Collect any cardinality updates.
        this.registerMeasuredCardinalities(producedChannelInstances);

        // Force execution if necessary.
        if (isForceExecution) {
            if (partialExecution == null) {
                this.logger.warn("Execution of {} might not have been enforced properly. " +
                                "This might break the execution or cause side-effects with the re-optimization.",
                        task);
            }
        }

        return new Tuple<>(Arrays.asList(outputChannelInstances), partialExecution);
    }


    private static JavaExecutionOperator cast(ExecutionOperator executionOperator) {
        return (JavaExecutionOperator) executionOperator;
    }

    private static ChannelInstance[] toArray(List<ChannelInstance> channelInstances) {
        final ChannelInstance[] array = new ChannelInstance[channelInstances.size()];
        return channelInstances.toArray(array);
    }

    /**
     * Utility function to open an {@link ExtendedFunction}.
     *
     * @param operator        the {@link JavaExecutionOperator} containing the function
     * @param function        the {@link ExtendedFunction}; if it is of a different type, nothing happens
     * @param inputs          the input {@link ChannelInstance}s for the {@code operator}
     * @param operatorContext context information for the {@code operator}
     */
    public static void openFunction(JavaExecutionOperator operator,
                                    Object function,
                                    ChannelInstance[] inputs,
                                    OptimizationContext.OperatorContext operatorContext) {
        if (function instanceof ExtendedFunction) {
            ExtendedFunction extendedFunction = (ExtendedFunction) function;
            int iterationNumber = operatorContext.getOptimizationContext().getIterationNumber();
            extendedFunction.open(new JavaExecutionContext(operator, inputs, iterationNumber));
        }
    }

    public FunctionCompiler getCompiler() {
        return this.compiler;
    }
}
