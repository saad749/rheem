package org.qcri.rheem.core.api;

import org.qcri.rheem.core.api.configuration.*;
import org.qcri.rheem.core.api.exception.RheemException;
import org.qcri.rheem.core.function.FlatMapDescriptor;
import org.qcri.rheem.core.function.FunctionDescriptor;
import org.qcri.rheem.core.function.PredicateDescriptor;
import org.qcri.rheem.core.optimizer.cardinality.CardinalityEstimator;
import org.qcri.rheem.core.optimizer.cardinality.FallbackCardinalityEstimator;
import org.qcri.rheem.core.optimizer.costs.*;
import org.qcri.rheem.core.optimizer.enumeration.PlanEnumerationPruningStrategy;
import org.qcri.rheem.core.plan.rheemplan.ElementaryOperator;
import org.qcri.rheem.core.plan.rheemplan.ExecutionOperator;
import org.qcri.rheem.core.plan.rheemplan.OutputSlot;
import org.qcri.rheem.core.platform.Platform;
import org.qcri.rheem.core.profiling.InstrumentationStrategy;
import org.qcri.rheem.core.profiling.OutboundInstrumentationStrategy;
import org.qcri.rheem.core.util.fs.FileSystem;
import org.qcri.rheem.core.util.fs.FileSystems;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

/**
 * Describes both the configuration of a {@link RheemContext} and {@link Job}s.
 */
public class Configuration {

    private static final String BASIC_PLATFORM = "org.qcri.rheem.basic.plugin.RheemBasicPlatform";

    private final Configuration parent;

    private KeyValueProvider<OutputSlot<?>, CardinalityEstimator> cardinalityEstimatorProvider;

    private KeyValueProvider<PredicateDescriptor, Double> predicateSelectivityProvider;

    private KeyValueProvider<FlatMapDescriptor<?, ?>, Double> multimapSelectivityProvider;

    private KeyValueProvider<ExecutionOperator, LoadProfileEstimator> operatorLoadProfileEstimatorProvider;

    private KeyValueProvider<FunctionDescriptor, LoadProfileEstimator> functionLoadProfileEstimatorProvider;

    private KeyValueProvider<Platform, LoadProfileToTimeConverter> loadProfileToTimeConverterProvider;

    private CollectionProvider<Platform> platformProvider;

    private ConstantProvider<Comparator<TimeEstimate>> timeEstimateComparatorProvider;

    private CollectionProvider<PlanEnumerationPruningStrategy> pruningStrategiesProvider;

    private ConstantProvider<InstrumentationStrategy> instrumentationStrategyProvider;

    private KeyValueProvider<String, String> properties;

    /**
     * Creates a new top-level instance.
     */
    public Configuration() {
        this(null);
    }

    /**
     * Basic constructor.
     */
    private Configuration(Configuration parent) {
        this.parent = parent;

        if (this.parent != null) {
            // Providers for platforms.
            this.platformProvider = new CollectionProvider<>(this.parent.platformProvider);

            // Providers for cardinality estimation.
            this.cardinalityEstimatorProvider =
                    new MapBasedKeyValueProvider<>(this.parent.cardinalityEstimatorProvider);
            this.predicateSelectivityProvider =
                    new MapBasedKeyValueProvider<>(this.parent.predicateSelectivityProvider);
            this.multimapSelectivityProvider =
                    new MapBasedKeyValueProvider<>(this.parent.multimapSelectivityProvider);

            // Providers for cost functions.
            this.operatorLoadProfileEstimatorProvider =
                    new MapBasedKeyValueProvider<>(this.parent.operatorLoadProfileEstimatorProvider);
            this.functionLoadProfileEstimatorProvider =
                    new MapBasedKeyValueProvider<>(this.parent.functionLoadProfileEstimatorProvider);
            this.loadProfileToTimeConverterProvider =
                    new MapBasedKeyValueProvider<>(this.parent.loadProfileToTimeConverterProvider);

            // Providers for plan enumeration.
            this.pruningStrategiesProvider = new CollectionProvider<>(this.parent.pruningStrategiesProvider);
            this.timeEstimateComparatorProvider = new ConstantProvider<>(this.parent.timeEstimateComparatorProvider);
            this.instrumentationStrategyProvider = new ConstantProvider<>(
                    this.parent.instrumentationStrategyProvider);

            // Properties.
            this.properties = new MapBasedKeyValueProvider<>(this.parent.properties);

        }
    }

    public static Configuration load(String configurationUrl) {
        Configuration configuration = createDefaultConfiguration();

        final Optional<FileSystem> fileSystem = FileSystems.getFileSystem(configurationUrl);
        if (!fileSystem.isPresent()) {
            throw new RheemException(String.format("Could not access %s.", configurationUrl));
        }
        try (InputStream configInputStream = fileSystem.get().open(configurationUrl)) {
            final Properties properties = new Properties();
            properties.load(configInputStream);
            for (Map.Entry<Object, Object> propertyEntry : properties.entrySet()) {
                final String key = propertyEntry.getKey().toString();
                final String value = propertyEntry.getValue().toString();
                configuration.setProperty(key, value);
            }

        } catch (IOException e) {
            throw new RheemException(String.format("Could not load configuration from %s.", configurationUrl), e);
        }

        return configuration;
    }

    public static Configuration createDefaultConfiguration() {
        Configuration configuration = new Configuration();
        bootstrapPlatforms(configuration);
        bootstrapCardinalityEstimationProvider(configuration);
        bootstrapSelectivityProviders(configuration);
        bootstrapLoadAndTimeEstimatorProviders(configuration);
        bootstrapPruningProviders(configuration);
        bootstrapProperties(configuration);
        return configuration;
    }

    private static void bootstrapPlatforms(Configuration configuration) {
        CollectionProvider<Platform> platformProvider = new CollectionProvider<>();
        Platform platform = Platform.load(BASIC_PLATFORM);
        platformProvider.addToWhitelist(platform);
        configuration.setPlatformProvider(platformProvider);
    }

    private static void bootstrapCardinalityEstimationProvider(final Configuration configuration) {
        // Safety net: provide a fallback estimator.
        KeyValueProvider<OutputSlot<?>, CardinalityEstimator> fallbackProvider =
                new FunctionalKeyValueProvider<OutputSlot<?>, CardinalityEstimator>(
                        outputSlot -> new FallbackCardinalityEstimator()
                ).withSlf4jWarning("Creating fallback estimator for {}.");

        // Default option: Implementations define their estimators.
        KeyValueProvider<OutputSlot<?>, CardinalityEstimator> defaultProvider =
                new FunctionalKeyValueProvider<>(fallbackProvider, (outputSlot, requestee) -> {
                    assert outputSlot.getOwner().isElementary()
                            : String.format("Cannot provide estimator for composite %s.", outputSlot.getOwner());
                    return ((ElementaryOperator) outputSlot.getOwner())
                            .getCardinalityEstimator(outputSlot.getIndex(), configuration)
                            .orElse(null);
                });

        // Customizable layer: Users can override manually.
        KeyValueProvider<OutputSlot<?>, CardinalityEstimator> overrideProvider =
                new MapBasedKeyValueProvider<>(defaultProvider);

        configuration.setCardinalityEstimatorProvider(overrideProvider);
    }

    private static void bootstrapSelectivityProviders(Configuration configuration) {
        {
            // Safety net: provide a fallback selectivity.
            KeyValueProvider<PredicateDescriptor, Double> fallbackProvider =
                    new FunctionalKeyValueProvider<PredicateDescriptor, Double>(
                            predicateClass -> 0.5d
                    ).withSlf4jWarning("Creating fallback selectivity for {}.");

            // Customizable layer: Users can override manually.
            KeyValueProvider<PredicateDescriptor, Double> overrideProvider =
                    new MapBasedKeyValueProvider<>(fallbackProvider);

            configuration.setPredicateSelectivityProvider(overrideProvider);
        }
        {
            // No safety net here.

            // Customizable layer: Users can override manually.
            KeyValueProvider<FlatMapDescriptor<?, ?>, Double> overrideProvider =
                    new MapBasedKeyValueProvider<>(null);

            configuration.setMultimapSelectivityProvider(overrideProvider);
        }
    }

    private static void bootstrapLoadAndTimeEstimatorProviders(Configuration configuration) {
        {
            // Safety net: provide a fallback selectivity.
            KeyValueProvider<ExecutionOperator, LoadProfileEstimator> fallbackProvider =
                    new FunctionalKeyValueProvider<ExecutionOperator, LoadProfileEstimator>(
                            operator -> new NestableLoadProfileEstimator(
                                    DefaultLoadEstimator.createIOLinearEstimator(operator, 10000),
                                    DefaultLoadEstimator.createIOLinearEstimator(operator, 10000),
                                    DefaultLoadEstimator.createIOLinearEstimator(operator, 1000),
                                    DefaultLoadEstimator.createIOLinearEstimator(operator, 1000)
                            )
                    ).withSlf4jWarning("Creating fallback selectivity for {}.");

            // Built-in option: let the ExecutionOperators provide the LoadProfileEstimator.
            KeyValueProvider<ExecutionOperator, LoadProfileEstimator> builtInProvider =
                    new FunctionalKeyValueProvider<>(
                            fallbackProvider,
                            operator -> operator.getLoadProfileEstimator(configuration).orElse(null)
                    );

            // Customizable layer: Users can override manually.
            KeyValueProvider<ExecutionOperator, LoadProfileEstimator> overrideProvider =
                    new MapBasedKeyValueProvider<>(builtInProvider);

            configuration.setOperatorLoadProfileEstimatorProvider(overrideProvider);
        }
        {
            // Safety net: provide a fallback selectivity.
            KeyValueProvider<FunctionDescriptor, LoadProfileEstimator> fallbackProvider =
                    new FunctionalKeyValueProvider<FunctionDescriptor, LoadProfileEstimator>(
                            functionDescriptor -> new NestableLoadProfileEstimator(
                                    DefaultLoadEstimator.createIOLinearEstimator(10000),
                                    DefaultLoadEstimator.createIOLinearEstimator(10000)
                            )
                    ).withSlf4jWarning("Creating fallback selectivity for {}.");

            // Built-in layer: let the FunctionDescriptors provide the LoadProfileEstimators themselves.
            KeyValueProvider<FunctionDescriptor, LoadProfileEstimator> builtInProvider =
                    new FunctionalKeyValueProvider<>(
                            fallbackProvider,
                            functionDescriptor -> functionDescriptor.getLoadProfileEstimator().orElse(null)
                    );

            // Customizable layer: Users can override manually.
            KeyValueProvider<FunctionDescriptor, LoadProfileEstimator> overrideProvider =
                    new MapBasedKeyValueProvider<>(builtInProvider);

            configuration.setFunctionLoadProfileEstimatorProvider(overrideProvider);
        }
        {
            // Safety net: provide a fallback converter.
            final LoadProfileToTimeConverter fallbackConverter = LoadProfileToTimeConverter.createDefault(
                    LoadToTimeConverter.createLinearCoverter(0.001d),
                    LoadToTimeConverter.createLinearCoverter(0.001d),
                    LoadToTimeConverter.createLinearCoverter(0.01d),
                    (cpuEstimate, diskEstimate, networkEstimate) -> cpuEstimate.plus(diskEstimate).plus(networkEstimate)
            );
            KeyValueProvider<Platform, LoadProfileToTimeConverter> fallbackProvider =
                    new FunctionalKeyValueProvider<Platform, LoadProfileToTimeConverter>(
                            platform -> fallbackConverter
                    ).withSlf4jWarning("Using fallback load-profile-to-time converter.");

            // Add provider to customize behavior on RheemContext level.
            KeyValueProvider<Platform, LoadProfileToTimeConverter> overrideProvider =
                    new MapBasedKeyValueProvider<>(fallbackProvider);
            configuration.setLoadProfileToTimeConverterProvider(overrideProvider);
        }
        {
            ConstantProvider<Comparator<TimeEstimate>> defaultProvider =
                    new ConstantProvider<>(TimeEstimate.expectationValueComparator());
            ConstantProvider<Comparator<TimeEstimate>> overrideProvider =
                    new ConstantProvider<>(defaultProvider);
            configuration.setTimeEstimateComparatorProvider(overrideProvider);
        }
    }

    private static void bootstrapPruningProviders(Configuration configuration) {
        {
            // By default, no pruning is applied.
            CollectionProvider<PlanEnumerationPruningStrategy> defaultProvider =
                    new CollectionProvider<>();
            configuration.setPruningStrategiesProvider(defaultProvider);

        }
        {
            ConstantProvider<Comparator<TimeEstimate>> defaultProvider =
                    new ConstantProvider<>(TimeEstimate.expectationValueComparator());
            ConstantProvider<Comparator<TimeEstimate>> overrideProvider =
                    new ConstantProvider<>(defaultProvider);
            configuration.setTimeEstimateComparatorProvider(overrideProvider);
        }
        {
            ConstantProvider<InstrumentationStrategy> defaultProvider =
                    new ConstantProvider<>(new OutboundInstrumentationStrategy());
            configuration.setInstrumentationStrategyProvider(defaultProvider);
        }
    }

    private static void bootstrapProperties(Configuration configuration) {
        // Here, we could put some default values.
        final KeyValueProvider<String, String> defaultProperties = new MapBasedKeyValueProvider<>(null);

        // Supplement with a customizable layer.
        final KeyValueProvider<String, String> customizableProperties = new MapBasedKeyValueProvider<>(defaultProperties);

        configuration.setProperties(customizableProperties);
    }

    /**
     * Creates a child instance.
     */
    public Configuration fork() {
        return new Configuration(this);
    }


    public KeyValueProvider<OutputSlot<?>, CardinalityEstimator> getCardinalityEstimatorProvider() {
        return this.cardinalityEstimatorProvider;
    }

    public void setCardinalityEstimatorProvider(
            KeyValueProvider<OutputSlot<?>, CardinalityEstimator> cardinalityEstimatorProvider) {
        this.cardinalityEstimatorProvider = cardinalityEstimatorProvider;
    }

    public KeyValueProvider<PredicateDescriptor, Double> getPredicateSelectivityProvider() {
        return this.predicateSelectivityProvider;
    }

    public void setPredicateSelectivityProvider(
            KeyValueProvider<PredicateDescriptor, Double> predicateSelectivityProvider) {
        this.predicateSelectivityProvider = predicateSelectivityProvider;
    }

    public KeyValueProvider<FlatMapDescriptor<?, ?>, Double> getMultimapSelectivityProvider() {
        return this.multimapSelectivityProvider;
    }

    public void setMultimapSelectivityProvider(
            KeyValueProvider<FlatMapDescriptor<?, ?>, Double> multimapSelectivityProvider) {
        this.multimapSelectivityProvider = multimapSelectivityProvider;
    }

    public KeyValueProvider<ExecutionOperator, LoadProfileEstimator> getOperatorLoadProfileEstimatorProvider() {
        return this.operatorLoadProfileEstimatorProvider;
    }

    public void setOperatorLoadProfileEstimatorProvider(KeyValueProvider<ExecutionOperator, LoadProfileEstimator> operatorLoadProfileEstimatorProvider) {
        this.operatorLoadProfileEstimatorProvider = operatorLoadProfileEstimatorProvider;
    }

    public KeyValueProvider<Platform, LoadProfileToTimeConverter> getLoadProfileToTimeConverterProvider() {
        return this.loadProfileToTimeConverterProvider;
    }

    public void setLoadProfileToTimeConverterProvider(KeyValueProvider<Platform, LoadProfileToTimeConverter> loadProfileToTimeConverterProvider) {
        this.loadProfileToTimeConverterProvider = loadProfileToTimeConverterProvider;
    }

    public KeyValueProvider<FunctionDescriptor, LoadProfileEstimator> getFunctionLoadProfileEstimatorProvider() {
        return this.functionLoadProfileEstimatorProvider;
    }

    public void setFunctionLoadProfileEstimatorProvider(KeyValueProvider<FunctionDescriptor, LoadProfileEstimator> functionLoadProfileEstimatorProvider) {
        this.functionLoadProfileEstimatorProvider = functionLoadProfileEstimatorProvider;
    }

    public CollectionProvider<Platform> getPlatformProvider() {
        return this.platformProvider;
    }

    public void setPlatformProvider(CollectionProvider<Platform> platformProvider) {
        this.platformProvider = platformProvider;
    }

    public ConstantProvider<Comparator<TimeEstimate>> getTimeEstimateComparatorProvider() {
        return this.timeEstimateComparatorProvider;
    }

    public void setTimeEstimateComparatorProvider(ConstantProvider<Comparator<TimeEstimate>> timeEstimateComparatorProvider) {
        this.timeEstimateComparatorProvider = timeEstimateComparatorProvider;
    }

    public CollectionProvider<PlanEnumerationPruningStrategy> getPruningStrategiesProvider() {
        return this.pruningStrategiesProvider;
    }


    public void setPruningStrategiesProvider(CollectionProvider<PlanEnumerationPruningStrategy> pruningStrategiesProvider) {
        this.pruningStrategiesProvider = pruningStrategiesProvider;
    }

    public ConstantProvider<InstrumentationStrategy> getInstrumentationStrategyProvider() {
        return this.instrumentationStrategyProvider;
    }

    public void setInstrumentationStrategyProvider(ConstantProvider<InstrumentationStrategy> instrumentationStrategyProvider) {
        this.instrumentationStrategyProvider = instrumentationStrategyProvider;
    }

    public void setProperties(KeyValueProvider<String, String> properties) {
        this.properties = properties;
    }

    private void setProperty(String key, String value) {
        this.properties.set(key, value);
    }

    public Optional<String> getStringProperty(String key) {
        return this.properties.optionallyProvideFor(key);
    }

    public String getStringProperty(String key, String fallback) {
        return this.getStringProperty(key).orElse(fallback);
    }

    public OptionalLong getLongProperty(String key) {
        final Optional<String> longValue = this.properties.optionallyProvideFor(key);
        if (longValue.isPresent()) {
            return OptionalLong.of(Long.valueOf(longValue.get()));
        } else {
            return OptionalLong.empty();
        }
    }

    public long getLongProperty(String key, long fallback) {
        return this.getLongProperty(key).orElse(fallback);
    }

    public Optional<Boolean> getBooleanProperty(String key) {
        return this.properties.optionallyProvideFor(key).map(Boolean::valueOf);
    }

    public boolean getBooleanProperty(String key, boolean fallback) {
        return this.getBooleanProperty(key).orElse(fallback);
    }
}
