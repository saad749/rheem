package org.qcri.rheem.spark.operators;

import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.function.Function;
import org.qcri.rheem.basic.operators.MaterializedGroupByOperator;
import org.qcri.rheem.core.function.TransformationDescriptor;
import org.qcri.rheem.core.optimizer.costs.DefaultLoadEstimator;
import org.qcri.rheem.core.optimizer.costs.LoadProfileEstimator;
import org.qcri.rheem.core.optimizer.costs.NestableLoadProfileEstimator;
import org.qcri.rheem.core.plan.rheemplan.ExecutionOperator;
import org.qcri.rheem.core.types.DataSetType;
import org.qcri.rheem.spark.channels.ChannelExecutor;
import org.qcri.rheem.spark.compiler.FunctionCompiler;
import org.qcri.rheem.spark.platform.SparkExecutor;
import scala.Tuple2;

import java.util.Iterator;
import java.util.Optional;


/**
 * Spark implementation of the {@link MaterializedGroupByOperator}.
 */
public class SparkMaterializedGroupByOperator<Type, KeyType>
        extends MaterializedGroupByOperator<Type, KeyType>
        implements SparkExecutionOperator {


    public SparkMaterializedGroupByOperator(TransformationDescriptor<Type, KeyType> keyDescriptor,
                                            DataSetType<Type> inputType,
                                            DataSetType<Iterable<Type>> outputType) {
        super(keyDescriptor, inputType, outputType);
    }

    @Override
    public void evaluate(ChannelExecutor[] inputs, ChannelExecutor[] outputs, FunctionCompiler compiler, SparkExecutor sparkExecutor) {
        assert inputs.length == this.getNumInputs();
        assert outputs.length == this.getNumOutputs();

        final JavaRDD<Type> inputRdd = inputs[0].provideRdd();
        final Function<Type, KeyType> keyExtractor = compiler.compile(this.keyDescriptor, this, inputs);
        final Function<scala.Tuple2<KeyType, Iterable<Type>>, Iterable<Type>> projector = new GroupProjector<>();
        final JavaRDD<Iterable<Type>> outputRdd = inputRdd
                .groupBy(keyExtractor)
                .map(projector);

        // TODO: MaterializedGroupByOperator actually prescribes to return Iterators, not Iterables.
        outputs[0].acceptRdd(outputRdd);
    }

    @Override
    protected ExecutionOperator createCopy() {
        return new SparkMaterializedGroupByOperator<>(this.getKeyDescriptor(), this.getInputType(), this.getOutputType());
    }

    private static class GroupProjector<Key, Type> implements Function<scala.Tuple2<Key, Iterable<Type>>, Iterable<Type>> {

        @Override
        public Iterable<Type> call(Tuple2<Key, Iterable<Type>> groupWithKey) throws Exception {
            return groupWithKey._2;
        }

    }

    @Override
    public Optional<LoadProfileEstimator> getLoadProfileEstimator(org.qcri.rheem.core.api.Configuration configuration) {
        final NestableLoadProfileEstimator mainEstimator = new NestableLoadProfileEstimator(
                new DefaultLoadEstimator(1, 1, .9d, (inputCards, outputCards) -> 17000 * inputCards[0] + 6272516800L),
                new DefaultLoadEstimator(1, 1, .9d, (inputCards, outputCards) -> 0),
                new DefaultLoadEstimator(1, 1, .9d, (inputCards, outputCards) -> 0),
                new DefaultLoadEstimator(1, 1, .9d, (inputCards, outputCards) -> Math.round(0.3 * inputCards[0] + 430000)),
                0.09d,
                100
        );

        return Optional.of(mainEstimator);
    }

}