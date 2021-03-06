package org.qcri.rheem.core.platform;

import org.qcri.rheem.core.optimizer.OptimizationContext;
import org.qcri.rheem.core.plan.executionplan.ExecutionTask;
import org.qcri.rheem.core.plan.rheemplan.ExecutionOperator;
import org.qcri.rheem.core.util.Tuple;

import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedList;

/**
 * Keeps track of lazily executed {@link ChannelInstance}s.
 */
public class LazyChannelLineage {

    private Node root;

    /**
     * Creates a new instance.
     *
     * @param channelInstance         the {@link ChannelInstance} being wrapped
     * @param producerOperatorContext the {@link OptimizationContext.OperatorContext} for the producing
     *                                {@link ExecutionOperator} or {@code null} if the {@link ExecutionOperator} should
     *                                not be considered
     * @param producerOutputIndex     the output index of the producer {@link ExecutionTask} (if {@code producerOperatorContext}
     *                                is not {@code null})
     */
    public LazyChannelLineage(ChannelInstance channelInstance,
                              OptimizationContext.OperatorContext producerOperatorContext,
                              int producerOutputIndex) {
        this(createNode(channelInstance, producerOperatorContext, producerOutputIndex));
    }

    private LazyChannelLineage(Node root) {
        this.root = root;
    }

    /**
     * Creates an appropriate {@link Node} for the given parameters
     *
     * @param channelInstance         the {@link ChannelInstance} being wrapped
     * @param producerOperatorContext the {@link OptimizationContext.OperatorContext} for the producing
     *                                {@link ExecutionOperator} or {@code null} if the {@link ExecutionOperator} should
     *                                not be considered
     * @param producerOutputIndex     the output index of the producer {@link ExecutionTask} (if {@code producerOperatorContext}
     *                                is not {@code null})
     * @return the {@link Node}
     */
    private static Node createNode(ChannelInstance channelInstance,
                                   OptimizationContext.OperatorContext producerOperatorContext,
                                   int producerOutputIndex) {
        return producerOperatorContext == null ?
                new EmptyNode(channelInstance) :
                new DefaultNode(channelInstance, producerOperatorContext, producerOutputIndex);
    }

    public void addPredecessor(LazyChannelLineage that) {
        this.root.add(that.root);
    }

    public <T> T traverseAndMark(T accumulator, Aggregator<T> aggregator) {
        return this.root.traverse(accumulator, aggregator, true);
    }

    public <T> T traverse(T accumulator, Aggregator<T> aggregator) {
        return this.root.traverse(accumulator, aggregator, false);
    }

    /**
     * Exchange the current root {@link Node} with that from a second instance. This procedure can be useful
     * to skip elements in the lineage.
     *
     * @param that the other {@link LazyChannelLineage}
     */
    public void copyRootFrom(LazyChannelLineage that) {
        this.root = that.root;
    }

    /**
     * Set all of the {@code inputs} as predecessors of each of the {@code outputs}.
     *
     * @param inputs  input {@link ChannelInstance}s
     * @param outputs output {@link ChannelInstance}s
     * @see ChannelInstance#addPredecessor(ChannelInstance)
     */
    public static void addAllPredecessors(ChannelInstance[] inputs, ChannelInstance[] outputs) {
        for (int outputIndex = 0; outputIndex < outputs.length; outputIndex++) {
            for (int inputIndex = 0; inputIndex < inputs.length; inputIndex++) {
                outputs[outputIndex].addPredecessor(inputs[inputIndex]);
            }
        }
    }

    /**
     * Collect and mark all unmarked {@link Node}s in this instance.
     *
     * @return the collected {@link OptimizationContext.OperatorContext}s and produced {@link ChannelInstance}s
     */
    public Tuple<Collection<OptimizationContext.OperatorContext>, Collection<ChannelInstance>> collectAndMark() {
        return this.collectAndMark(new LinkedList<>(), new LinkedList<>());
    }

    /**
     * Collect and mark all unmarked {@link Node}s in this instance.
     *
     * @param operatorContextCollector collects the {@link OptimizationContext.OperatorContext} in the unmarked {@link Node}s
     * @param channelInstanceCollector collects the {@link ChannelInstance} in the unmarked {@link Node}s
     * @return the two collectors
     */
    public Tuple<Collection<OptimizationContext.OperatorContext>, Collection<ChannelInstance>> collectAndMark(
            Collection<OptimizationContext.OperatorContext> operatorContextCollector,
            Collection<ChannelInstance> channelInstanceCollector
    ) {
        return this.traverseAndMark(
                new Tuple<>(operatorContextCollector, channelInstanceCollector),
                (accumulator, channelInstance, operatorContext) -> {
                    accumulator.getField0().add(operatorContext);
                    accumulator.getField1().add(channelInstance);
                    return accumulator;
                }
        );
    }

    /**
     * Callback interface for traversals of {@link LazyChannelLineage}s, thereby accumulating the callback return values.
     *
     * @param <T> type of the accumulator
     */
    @FunctionalInterface
    public interface Aggregator<T> {

        /**
         * Visit a {@link Node}.
         *
         * @param accumulator     current accumulator value
         * @param channelInstance the {@link ChannelInstance} of wrapped by the visited {@link Node}
         * @param operatorContext the producer {@link OptimizationContext.OperatorContext} of the visited {@link Node}
         * @return the new accumulator value
         */
        T apply(T accumulator, ChannelInstance channelInstance, OptimizationContext.OperatorContext operatorContext);

    }

    /**
     * A node wraps a {@link ChannelInstance} and keeps track of predecessor nodes.
     */
    public static abstract class Node {

        /**
         * The wrapped {@link ChannelInstance}.
         */
        protected final ChannelInstance channelInstance;


        /**
         * Basically, wrapped {@link ChannelInstance}s that need to be evaluated before the {@link #channelInstance}.
         */
        private final Collection<Node> predecessors = new LinkedList<>();

        private Node(final ChannelInstance channelInstance) {
            this.channelInstance = channelInstance;
        }

        protected void add(Node predecessor) {
            assert !this.predecessors.contains(predecessor);
            this.predecessors.add(predecessor);
//            predecessor.channelInstance.noteObtainedReference();
        }


        protected <T> T traverse(T accumulator, Aggregator<T> aggregator, boolean isMark) {
            if (!this.channelInstance.wasProduced()) {
                for (Iterator<Node> i = this.predecessors.iterator(); i.hasNext(); ) {
                    Node predecessor = i.next();
                    accumulator = predecessor.traverse(accumulator, aggregator, isMark);
                    if (predecessor.channelInstance.wasProduced()) {
                        i.remove();
//                        next.channelInstance.noteDiscardedReference(true);
                    }
                }
                accumulator = this.accept(accumulator, aggregator);
                if (isMark) this.channelInstance.markProduced();
            }
            return accumulator;
        }

        protected abstract <T> T accept(T accumulator, Aggregator<T> aggregator);

        public ChannelInstance getChannelInstance() {
            return channelInstance;
        }

        @Override
        public String toString() {
            return String.format("%s[%s, %d predecessors]",
                    this.getClass().getSimpleName(), this.channelInstance, this.predecessors.size()
            );
        }
    }


    public static class EmptyNode extends Node {

        public EmptyNode(ChannelInstance channelInstance) {
            super(channelInstance);
        }

        @Override
        protected <T> T accept(T accumulator, Aggregator<T> aggregator) {
            return accumulator;
        }


    }

    /**
     * Encapsulates a single {@link ChannelInstance} in a {@link LazyChannelLineage}.
     */
    public static class DefaultNode extends Node {


        /**
         * The {@link OptimizationContext.OperatorContext} of the {@link ExecutionOperator} producing the
         * {@link #channelInstance}.
         */
        private final OptimizationContext.OperatorContext producerOperatorContext;

        /**
         * The ouput index of the {@link #channelInstance} w.r.t. the {@link #producerOperatorContext}.
         */
        private final int producerOutputIndex;


        private DefaultNode(final ChannelInstance channelInstance,
                            final OptimizationContext.OperatorContext producerOperatorContext,
                            final int producerOutputIndex) {
            super(channelInstance);
            this.producerOperatorContext = producerOperatorContext;
            this.producerOutputIndex = producerOutputIndex;
        }

        @Override
        protected <T> T accept(T accumulator, Aggregator<T> aggregator) {
            return aggregator.apply(accumulator, this.channelInstance, this.producerOperatorContext);
        }
    }

}
