package org.qcri.rheem.core.api;

import de.hpi.isg.profiledb.instrumentation.StopWatch;
import de.hpi.isg.profiledb.store.model.Experiment;
import de.hpi.isg.profiledb.store.model.TimeMeasurement;
import org.qcri.rheem.core.api.exception.RheemException;
import org.qcri.rheem.core.mapping.PlanTransformation;
import org.qcri.rheem.core.optimizer.DefaultOptimizationContext;
import org.qcri.rheem.core.optimizer.OptimizationContext;
import org.qcri.rheem.core.optimizer.ProbabilisticDoubleInterval;
import org.qcri.rheem.core.optimizer.cardinality.CardinalityEstimate;
import org.qcri.rheem.core.optimizer.cardinality.CardinalityEstimatorManager;
import org.qcri.rheem.core.optimizer.costs.TimeEstimate;
import org.qcri.rheem.core.optimizer.costs.TimeToCostConverter;
import org.qcri.rheem.core.optimizer.enumeration.*;
import org.qcri.rheem.core.plan.executionplan.Channel;
import org.qcri.rheem.core.plan.executionplan.ExecutionPlan;
import org.qcri.rheem.core.plan.executionplan.ExecutionStage;
import org.qcri.rheem.core.plan.executionplan.ExecutionTask;
import org.qcri.rheem.core.plan.rheemplan.ExecutionOperator;
import org.qcri.rheem.core.plan.rheemplan.Operator;
import org.qcri.rheem.core.plan.rheemplan.RheemPlan;
import org.qcri.rheem.core.platform.*;
import org.qcri.rheem.core.profiling.*;
import org.qcri.rheem.core.util.Formats;
import org.qcri.rheem.core.util.OneTimeExecutable;
import org.qcri.rheem.core.util.ReflectionUtils;
import org.qcri.rheem.core.util.RheemCollections;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Describes a job that is to be executed using Rheem.
 */
public class Job extends OneTimeExecutable {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    /**
     * Guardian to avoid re-execution.
     */
    private final AtomicBoolean hasBeenExecuted = new AtomicBoolean(false);

    /**
     * References the {@link RheemContext} that spawned this instance.
     */
    private final RheemContext rheemContext;

    /**
     * {@link Job}-level {@link Configuration} based on the {@link RheemContext}-level configuration.
     */
    private final Configuration configuration;

    /**
     * The {@link RheemPlan} to be executed by this instance.
     */
    private final RheemPlan rheemPlan;

    /**
     * {@link OptimizationContext} for the {@link #rheemPlan}.
     */
    private OptimizationContext optimizationContext;

    /**
     * Executes the optimized {@link ExecutionPlan}.
     */
    private CrossPlatformExecutor crossPlatformExecutor;

    /**
     * Manages the {@link CardinalityEstimate}s for the {@link #rheemPlan}.
     */
    private CardinalityEstimatorManager cardinalityEstimatorManager;

    /**
     * Collects metadata w.r.t. the processing of this instance.
     */
    private final Experiment experiment;

    /**
     * {@link StopWatch} to measure some key figures for the {@link #experiment}.
     */
    private final StopWatch stopWatch;

    /**
     * {@link TimeMeasurement}s for the optimization and the execution phases.
     */
    private final TimeMeasurement optimizationRound, executionRound;

    /**
     * Collects the {@link TimeEstimate}s of all (partially) executed {@link PlanImplementation}s.
     */
    private List<TimeEstimate> timeEstimates = new LinkedList<>();

    /**
     * Collects the cost estimates of all (partially) executed {@link PlanImplementation}s.
     */
    private List<ProbabilisticDoubleInterval> costEstimates = new LinkedList<>();

    /**
     * JAR files that are needed to execute the UDFs.
     */
    private final Set<String> udfJarPaths = new HashSet<>();

    /**
     * Name for this instance.
     */
    private final String name;

    /**
     * <i>Currently not used.</i>
     */
    private final StageAssignmentTraversal.StageSplittingCriterion stageSplittingCriterion =
            (producerTask, channel, consumerTask) -> false;

    /**
     * Creates a new instance.
     *
     * @param name       name for this instance or {@code null} if a default name should be picked
     * @param experiment an {@link Experiment} for that profiling entries will be created
     * @param udfJars    paths to JAR files needed to run the UDFs (see {@link ReflectionUtils#getDeclaringJar(Class)})
     */
    Job(RheemContext rheemContext, String name, RheemPlan rheemPlan, Experiment experiment, String... udfJars) {
        this.rheemContext = rheemContext;
        this.name = name == null ? "Rheem app" : name;
        this.configuration = this.rheemContext.getConfiguration().fork(this.name);
        this.rheemPlan = rheemPlan;
        for (String udfJar : udfJars) {
            this.addUdfJar(udfJar);
        }

        // Prepare instrumentation.
        this.experiment = experiment;
        this.stopWatch = new StopWatch(experiment);
        this.optimizationRound = this.stopWatch.getOrCreateRound("Optimization");
        this.executionRound = this.stopWatch.getOrCreateRound("Execution");
    }

    /**
     * Adds a {@code path} to a JAR that is required in one or more UDFs.
     *
     * @see ReflectionUtils#getDeclaringJar(Class)
     */
    public void addUdfJar(String path) {
        this.udfJarPaths.add(path);
    }

    /**
     * Run this instance. Must only be called once.
     *
     * @throws RheemException in case the execution fails for any reason
     */
    @Override
    public void execute() throws RheemException {
        try {
            super.execute();
        } catch (RheemException e) {
            throw e;
        } catch (Throwable t) {
            throw new RheemException("Job execution failed.", t);
        }
    }

    @Override
    protected void doExecute() {
        // Make sure that each job is only executed once.
        if (this.hasBeenExecuted.getAndSet(true)) {
            throw new RheemException("Job has already been executed.");
        }

        try {

            // Prepare the #rheemPlan for the optimization.
            this.optimizationRound.start();
            this.prepareRheemPlan();

            // Estimate cardinalities and execution times for the #rheemPlan.
            this.estimateKeyFigures();

            // Get an execution plan.
            ExecutionPlan executionPlan = this.createInitialExecutionPlan();
            this.optimizationRound.stop();

            // Take care of the execution.
            int executionId = 0;
            while (!this.execute(executionPlan, executionId)) {
                this.optimizationRound.start();
                this.postProcess(executionPlan, executionId);
                executionId++;
                this.optimizationRound.stop();
            }

            this.stopWatch.start("Post-processing");
            if (this.configuration.getBooleanProperty("rheem.core.log.enabled")) {
                this.logExecution();
            }
        } catch (RheemException e) {
            throw e;
        } catch (Throwable t) {
            throw new RheemException("Job execution failed.", t);
        } finally {
            this.stopWatch.stopAll();
            this.stopWatch.start("Post-processing", "Release Resources");
            this.releaseResources();
            this.stopWatch.stop("Post-processing");
            this.logger.info("StopWatch results:\n{}", this.stopWatch.toPrettyString());
        }
    }

    /**
     * Prepares the {@link #rheemPlan}: prunes unused {@link Operator}s, isolates loops, and applies all available
     * {@link PlanTransformation}s.
     */
    private void prepareRheemPlan() {

        // Prepare the RheemPlan for the optimization.
        this.optimizationRound.start("Prepare", "Prune&Isolate");
        this.rheemPlan.prepare();
        this.optimizationRound.stop("Prepare", "Prune&Isolate");

        // Apply the mappings to the plan to form a hyperplan.
        this.optimizationRound.start("Prepare", "Transformations");
        final Collection<PlanTransformation> transformations = this.gatherTransformations();
        this.rheemPlan.applyTransformations(transformations);
        this.optimizationRound.stop("Prepare", "Transformations");

        this.optimizationRound.start("Prepare", "Sanity");
        assert this.rheemPlan.isSane();
        this.optimizationRound.stop("Prepare", "Sanity");

        this.optimizationRound.stop("Prepare");
    }

    /**
     * Gather all available {@link PlanTransformation}s from the {@link #configuration}.
     */
    private Collection<PlanTransformation> gatherTransformations() {
        final Set<Platform> platforms = RheemCollections.asSet(this.configuration.getPlatformProvider().provideAll());
        return this.configuration.getMappingProvider().provideAll().stream()
                .flatMap(mapping -> mapping.getTransformations().stream())
                .filter(t -> t.getTargetPlatforms().isEmpty() || platforms.containsAll(t.getTargetPlatforms()))
                .collect(Collectors.toList());
    }


    /**
     * Go over the given {@link RheemPlan} and estimate the cardinalities of data being passed between its
     * {@link Operator}s and the execution profile and time of {@link ExecutionOperator}s.
     */
    private void estimateKeyFigures() {
        this.optimizationRound.start("Cardinality&Load Estimation");
        if (this.cardinalityEstimatorManager == null) {
            this.optimizationRound.start("Cardinality&Load Estimation", "Create OptimizationContext");
            this.optimizationContext = new DefaultOptimizationContext(this.rheemPlan, this.configuration);
            this.optimizationRound.stop("Cardinality&Load Estimation", "Create OptimizationContext");

            this.optimizationRound.start("Cardinality&Load Estimation", "Create CardinalityEstimationManager");
            this.cardinalityEstimatorManager = new CardinalityEstimatorManager(
                    this.rheemPlan, this.optimizationContext, this.configuration);
            this.optimizationRound.stop("Cardinality&Load Estimation", "Create CardinalityEstimationManager");
        }

        this.optimizationRound.start("Cardinality&Load Estimation", "Push Estimation");
        this.cardinalityEstimatorManager.pushCardinalities();
        this.optimizationRound.stop("Cardinality&Load Estimation", "Push Estimation");

        this.optimizationRound.stop("Cardinality&Load Estimation");
    }


    /**
     * Determine a good/the best execution plan from a given {@link RheemPlan}.
     */
    private ExecutionPlan createInitialExecutionPlan() {
        this.optimizationRound.start("Create Initial Execution Plan");

        // Defines the plan that we want to use in the end.
        final Comparator<ProbabilisticDoubleInterval> costEstimateComparator =
                this.configuration.getCostEstimateComparatorProvider().provide();

        // Enumerate all possible plan.
        final PlanEnumerator planEnumerator = this.createPlanEnumerator();

        this.optimizationRound.start("Create Initial Execution Plan", "Enumerate");
        final PlanEnumeration comprehensiveEnumeration = planEnumerator.enumerate(true);
        this.optimizationRound.stop("Create Initial Execution Plan", "Enumerate");

        final Collection<PlanImplementation> executionPlans = comprehensiveEnumeration.getPlanImplementations();
        this.logger.debug("Enumerated {} plans.", executionPlans.size());
        for (PlanImplementation planImplementation : executionPlans) {
            this.logger.debug("Plan with operators: {}", planImplementation.getOperators());
        }

        // Pick an execution plan.
        // Make sure that an execution plan can be created.
        this.optimizationRound.start("Create Initial Execution Plan", "Pick Best Plan");
        final PlanImplementation planImplementation = this.pickBestExecutionPlan(costEstimateComparator, executionPlans, null, null, null);
        this.timeEstimates.add(planImplementation.getTimeEstimate());
        this.costEstimates.add(planImplementation.getCostEstimate());
        this.optimizationRound.stop("Create Initial Execution Plan", "Pick Best Plan");

        this.optimizationRound.start("Create Initial Execution Plan", "Split Stages");
        final ExecutionTaskFlow executionTaskFlow = ExecutionTaskFlow.createFrom(planImplementation);
        final ExecutionPlan executionPlan = ExecutionPlan.createFrom(executionTaskFlow, this.stageSplittingCriterion);
        this.optimizationRound.stop("Create Initial Execution Plan", "Split Stages");

        planImplementation.mergeJunctionOptimizationContexts();

        planImplementation.logTimeEstimates();

        //assert executionPlan.isSane();


        this.optimizationRound.stop("Create Initial Execution Plan");
        return executionPlan;
    }


    private PlanImplementation pickBestExecutionPlan(Comparator<ProbabilisticDoubleInterval> costEstimateComparator,
                                                     Collection<PlanImplementation> executionPlans,
                                                     ExecutionPlan existingPlan,
                                                     Set<Channel> openChannels,
                                                     Set<ExecutionStage> executedStages) {

        final PlanImplementation bestPlanImplementation = executionPlans.stream()
                .reduce((p1, p2) -> {
                    final ProbabilisticDoubleInterval t1 = p1.getCostEstimate();
                    final ProbabilisticDoubleInterval t2 = p2.getCostEstimate();
                    return costEstimateComparator.compare(t1, t2) < 0 ? p1 : p2;
                })
                .orElseThrow(() -> new RheemException("Could not find an execution plan."));
        this.logger.info("Picked {} as best plan.", bestPlanImplementation);
        return bestPlanImplementation;
    }

    /**
     * Go over the given {@link RheemPlan} and update the cardinalities of data being passed between its
     * {@link Operator}s using the given {@link ExecutionState}.
     */
    private void reestimateCardinalities(ExecutionState executionState) {
        this.cardinalityEstimatorManager.pushCardinalityUpdates(executionState);
    }

    /**
     * Creates a new {@link PlanEnumerator} for the {@link #rheemPlan} and {@link #configuration}.
     */
    private PlanEnumerator createPlanEnumerator() {
        return this.createPlanEnumerator(null, null);
    }

    /**
     * Creates a new {@link PlanEnumerator} for the {@link #rheemPlan} and {@link #configuration}.
     */
    private PlanEnumerator createPlanEnumerator(ExecutionPlan existingPlan, Set<Channel> openChannels) {
        return existingPlan == null ?
                new PlanEnumerator(this.rheemPlan, this.optimizationContext) :
                new PlanEnumerator(this.rheemPlan, this.optimizationContext, existingPlan, openChannels);
    }

    /**
     * Start executing the given {@link ExecutionPlan} with all bells and whistles, such as instrumentation,
     * logging of the plan, and measuring the execution time.
     *
     * @param executionPlan that should be executed
     * @param executionId   an identifier for the current execution
     * @return whether the execution of the {@link ExecutionPlan} is completed
     */
    private boolean execute(ExecutionPlan executionPlan, int executionId) {
        final TimeMeasurement currentExecutionRound = this.executionRound.start(String.format("Execution %d", executionId));

        // Ensure existence of the #crossPlatformExecutor.
        if (this.crossPlatformExecutor == null) {
            final InstrumentationStrategy instrumentation = this.configuration.getInstrumentationStrategyProvider().provide();
            this.crossPlatformExecutor = new CrossPlatformExecutor(this, instrumentation);
        }

        if (this.configuration.getOptionalBooleanProperty("rheem.core.debug.skipexecution").orElse(false)) {
            return true;
        }
        if (this.configuration.getBooleanProperty("rheem.core.optimizer.reoptimize")) {
            this.setUpBreakpoint(executionPlan, currentExecutionRound);
        }

        // Log the current executionPlan.
        this.logStages(executionPlan);

        // Trigger the execution.
        currentExecutionRound.start("Execute");
        boolean isExecutionComplete = this.crossPlatformExecutor.executeUntilBreakpoint(
                executionPlan, this.optimizationContext
        );
        executionRound.stop();

        // Return.
        return isExecutionComplete;
    }

    /**
     * Sets up a {@link Breakpoint} for an {@link ExecutionPlan}.
     *
     * @param executionPlan for that the {@link Breakpoint} should be set
     * @param round         {@link TimeMeasurement} to be extended for any interesting time measurements
     */
    private void setUpBreakpoint(ExecutionPlan executionPlan, TimeMeasurement round) {

        // Set up appropriate Breakpoints.
        final TimeMeasurement breakpointRound = round.start("Configure Breakpoint");
        FixBreakpoint immediateBreakpoint = new FixBreakpoint();
        final Set<ExecutionStage> completedStages = this.crossPlatformExecutor.getCompletedStages();
        if (completedStages.isEmpty()) {
            executionPlan.getStartingStages().forEach(immediateBreakpoint::breakAfter);
        } else {
            completedStages.stream()
                    .flatMap(stage -> stage.getSuccessors().stream())
                    .filter(stage -> !completedStages.contains(stage))
                    .forEach(immediateBreakpoint::breakAfter);
        }
        this.crossPlatformExecutor.setBreakpoint(new ConjunctiveBreakpoint(
                immediateBreakpoint,
                new CardinalityBreakpoint(this.configuration),
                new NoIterationBreakpoint() // Avoid re-optimization inside of loops.
        ));
        breakpointRound.stop();
    }

    private void logStages(ExecutionPlan executionPlan) {
        if (this.logger.isInfoEnabled()) {

            StringBuilder sb = new StringBuilder();
            Set<ExecutionStage> seenStages = new HashSet<>();
            Queue<ExecutionStage> stagedStages = new LinkedList<>(executionPlan.getStartingStages());
            ExecutionStage nextStage;
            while ((nextStage = stagedStages.poll()) != null) {
                sb.append(nextStage).append(":\n");
                nextStage.getPlanAsString(sb, "* ");
                nextStage.getSuccessors().stream()
                        .filter(seenStages::add)
                        .forEach(stagedStages::add);
            }

            this.logger.info("Current execution plan:\n{}", executionPlan.toExtensiveString());
        }
    }

    /**
     * Injects the cardinalities obtained from {@link Channel} instrumentation, potentially updates the {@link ExecutionPlan}
     * through re-optimization, and collects measured data.
     */
    private void postProcess(ExecutionPlan executionPlan, int executionId) {
        final TimeMeasurement round = this.optimizationRound.start(String.format("Post-processing %d", executionId));

        round.start("Reestimate Cardinalities&Time");
        this.reestimateCardinalities(this.crossPlatformExecutor);
        round.stop("Reestimate Cardinalities&Time");

        round.start("Update Execution Plan");
        this.updateExecutionPlan(executionPlan);
        round.stop("Update Execution Plan");

        round.stop();
    }

    /**
     * Enumerate possible execution plans from the given {@link RheemPlan} and determine the (seemingly) best one.
     */
    private void updateExecutionPlan(ExecutionPlan executionPlan) {
        // Defines the plan that we want to use in the end.
        final Comparator<ProbabilisticDoubleInterval> costEstimateComparator =
                this.configuration.getCostEstimateComparatorProvider().provide();

        // Find and copy the open Channels.
        final Set<ExecutionStage> completedStages = this.crossPlatformExecutor.getCompletedStages();
        final Set<ExecutionTask> completedTasks = completedStages.stream()
                .flatMap(stage -> stage.getAllTasks().stream())
                .collect(Collectors.toSet());

        // Find Channels that have yet to be consumed by unexecuted ExecutionTasks.
        // This must be done before scrapping the unexecuted ExecutionTasks!
        final Set<Channel> openChannels = completedTasks.stream()
                .flatMap(task -> Arrays.stream(task.getOutputChannels()))
                .filter(channel -> channel.getConsumers().stream().anyMatch(consumer -> !completedTasks.contains(consumer)))
                .collect(Collectors.toSet());

        // Scrap unexecuted bits of the plan.
        executionPlan.retain(completedStages);

        // Enumerate all possible plan.
        final PlanEnumerator planEnumerator = this.createPlanEnumerator(executionPlan, openChannels);
        final PlanEnumeration comprehensiveEnumeration = planEnumerator.enumerate(true);
        final Collection<PlanImplementation> executionPlans = comprehensiveEnumeration.getPlanImplementations();
        this.logger.debug("Enumerated {} plans.", executionPlans.size());
        for (PlanImplementation planImplementation : executionPlans) {
            this.logger.debug("Plan with operators: {}", planImplementation.getOperators());
        }

        // Pick an execution plan.
        // Make sure that an execution plan can be created.
        final PlanImplementation planImplementation = this.pickBestExecutionPlan(
                costEstimateComparator, executionPlans, executionPlan, openChannels, completedStages
        );

        ExecutionTaskFlow executionTaskFlow = ExecutionTaskFlow.recreateFrom(
                planImplementation, executionPlan, openChannels, completedStages
        );
        final ExecutionPlan executionPlanExpansion = ExecutionPlan.createFrom(executionTaskFlow, this.stageSplittingCriterion);
        executionPlan.expand(executionPlanExpansion);

        planImplementation.mergeJunctionOptimizationContexts();

        assert executionPlan.isSane();
    }

    /**
     * Asks this instance to release its critical resources to avoid resource leaks and to enhance durability and
     * consistency of accessed resources.
     */
    private void releaseResources() {
        this.rheemContext.getCardinalityRepository().sleep();
        if (this.crossPlatformExecutor != null) this.crossPlatformExecutor.shutdown();
    }

    private void logExecution() {
        this.stopWatch.start("Post-processing", "Log measurements");

        // For the last time, update the cardinalities and store them.
        this.reestimateCardinalities(this.crossPlatformExecutor);
        final CardinalityRepository cardinalityRepository = this.rheemContext.getCardinalityRepository();
        cardinalityRepository.storeAll(this.crossPlatformExecutor, this.optimizationContext);

        // Execution times.
        final Collection<PartialExecution> partialExecutions = this.crossPlatformExecutor.getPartialExecutions();

        // Add the execution times to the experiment.
        int nextPartialExecutionMeasurementId = 0;
        for (PartialExecution partialExecution : partialExecutions) {
            String id = String.format("par-ex-%03d", nextPartialExecutionMeasurementId++);
            final PartialExecutionMeasurement measurement = new PartialExecutionMeasurement(id, partialExecution);
            this.experiment.addMeasurement(measurement);
        }

        // Feed the execution log.
        try (ExecutionLog executionLog = ExecutionLog.open(this.configuration)) {
            executionLog.storeAll(partialExecutions);
        } catch (Exception e) {
            this.logger.error("Storing partial executions failed.", e);
        }
        this.optimizationRound.stop("Post-processing", "Log measurements");

        // Log the execution time.
        long effectiveExecutionMillis = partialExecutions.stream()
                .map(PartialExecution::getMeasuredExecutionTime)
                .reduce(0L, (a, b) -> a + b);
        long measuredExecutionMillis = this.executionRound.getMillis();
        this.logger.info(
                "Accumulated execution time: {} (effective: {}, overhead: {})",
                Formats.formatDuration(measuredExecutionMillis, true),
                Formats.formatDuration(effectiveExecutionMillis, true),
                Formats.formatDuration(measuredExecutionMillis - effectiveExecutionMillis, true)
        );
        int i = 1;
        for (TimeEstimate timeEstimate : timeEstimates) {
            this.logger.info("Estimated execution time (plan {}): {}", i, timeEstimate);
            TimeMeasurement lowerEstimate = new TimeMeasurement(String.format("Estimate %d (lower)", i));
            lowerEstimate.setMillis(timeEstimate.getLowerEstimate());
            this.stopWatch.getExperiment().addMeasurement(lowerEstimate);
            TimeMeasurement upperEstimate = new TimeMeasurement(String.format("Estimate %d (upper)", i));
            upperEstimate.setMillis(timeEstimate.getUpperEstimate());
            this.stopWatch.getExperiment().addMeasurement(upperEstimate);
            i++;
        }

        // Log the cost settings.
        final Collection<Platform> consideredPlatforms = this.configuration.getPlatformProvider().provideAll();
        for (Platform consideredPlatform : consideredPlatforms) {
            final TimeToCostConverter timeToCostConverter = this.configuration
                    .getTimeToCostConverterProvider()
                    .provideFor(consideredPlatform);
            this.experiment.getSubject().addConfiguration(
                    String.format("Costs per ms (%s)", consideredPlatform.getName()),
                    timeToCostConverter.getCostsPerMillisecond()
            );
            this.experiment.getSubject().addConfiguration(
                    String.format("Fix costs (%s)", consideredPlatform.getName()),
                    timeToCostConverter.getFixCosts()
            );
        }


        // Log the execution costs.
        double fixCosts = partialExecutions.stream()
                .flatMap(partialExecution -> partialExecution.getInvolvedPlatforms().stream())
                .map(platform -> this.configuration.getTimeToCostConverterProvider().provideFor(platform).getFixCosts())
                .reduce(0d, (a, b) -> a + b);
        double effectiveLowerCosts = fixCosts + partialExecutions.stream()
                .map(PartialExecution::getMeasuredLowerCost)
                .reduce(0d, (a, b) -> a + b);
        double effectiveUpperCosts = fixCosts + partialExecutions.stream()
                .map(PartialExecution::getMeasuredUpperCost)
                .reduce(0d, (a, b) -> a + b);
        this.logger.info("Accumulated costs: {} .. {}",
                String.format("%,.2f", effectiveLowerCosts),
                String.format("%,.2f", effectiveUpperCosts)
        );
        this.experiment.addMeasurement(
                new CostMeasurement("Measured cost", effectiveLowerCosts, effectiveUpperCosts, 1d)
        );
        i = 1;
        for (ProbabilisticDoubleInterval costEstimate : this.costEstimates) {
            this.logger.info("Estimated costs (plan {}): {}", i, costEstimate);
            this.experiment.addMeasurement(new CostMeasurement(
                    String.format("Estimated costs (%d)", i),
                    costEstimate.getLowerEstimate(),
                    costEstimate.getUpperEstimate(),
                    costEstimate.getCorrectnessProbability()
            ));
            i++;
        }
    }

    /**
     * Modify the {@link Configuration} to control the {@link Job} execution.
     */
    public Configuration getConfiguration() {
        return this.configuration;
    }

    public Set<String> getUdfJarPaths() {
        return this.udfJarPaths;
    }

    /**
     * Provide the {@link CrossPlatformExecutor} used during the execution of this instance.
     *
     * @return the {@link CrossPlatformExecutor} or {@code null} if there is none allocated
     */
    public CrossPlatformExecutor getCrossPlatformExecutor() {
        return this.crossPlatformExecutor;
    }

    public OptimizationContext getOptimizationContext() {
        return optimizationContext;
    }

    /**
     * Retrieves the name of this instance.
     *
     * @return the name
     */
    public String getName() {
        return this.name;
    }

    @Override
    public String toString() {
        return String.format("%s[%s]", this.getClass().getSimpleName(), this.name);
    }

    /**
     * Provide the {@link Experiment} being recorded with the execution of this instance.
     *
     * @return the {@link Experiment}
     */
    public Experiment getExperiment() {
        return this.experiment;
    }


}
