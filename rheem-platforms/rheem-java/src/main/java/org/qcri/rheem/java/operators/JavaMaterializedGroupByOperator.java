package org.qcri.rheem.java.operators;

import org.qcri.rheem.basic.operators.MaterializedGroupByOperator;
import org.qcri.rheem.core.function.TransformationDescriptor;
import org.qcri.rheem.core.plan.rheemplan.ExecutionOperator;
import org.qcri.rheem.core.types.DataSetType;
import org.qcri.rheem.java.channels.ChannelExecutor;
import org.qcri.rheem.java.compiler.FunctionCompiler;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Java implementation of the {@link MaterializedGroupByOperator}.
 */
public class JavaMaterializedGroupByOperator<Type, KeyType>
        extends MaterializedGroupByOperator<Type, KeyType>
        implements JavaExecutionOperator {


    public JavaMaterializedGroupByOperator(TransformationDescriptor<Type, KeyType> keyDescriptor,
                                           DataSetType<Type> inputType,
                                           DataSetType<Iterable<Type>> outputType) {
        super(keyDescriptor, inputType, outputType);
    }

    @Override
    public void evaluate(ChannelExecutor[] inputs, ChannelExecutor[] outputs, FunctionCompiler compiler) {
        assert inputs.length == this.getNumInputs();
        assert outputs.length == this.getNumOutputs();

        final Function<Type, KeyType> keyExtractor = compiler.compile(this.keyDescriptor);
        final Map<KeyType, List<Type>> collocation = inputs[0].<Type>provideStream().collect(
                Collectors.groupingBy(
                        keyExtractor,
                        Collectors.toList())); // Not sure if this is thread-safe... Will we use #parallelStream()?

        outputs[0].acceptCollection(collocation.values());
    }

    @Override
    protected ExecutionOperator createCopy() {
        return new JavaMaterializedGroupByOperator<>(this.getKeyDescriptor(), this.getInputType(), this.getOutputType());
    }
}