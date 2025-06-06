/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.runners.spark.translation;

import static org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.base.Preconditions.checkArgument;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.beam.runners.core.construction.SerializablePipelineOptions;
import org.apache.beam.runners.spark.SparkPipelineOptions;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.runners.AppliedPTransform;
import org.apache.beam.sdk.transforms.GroupByKey;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.windowing.BoundedWindow;
import org.apache.beam.sdk.util.construction.TransformInputs;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PCollectionView;
import org.apache.beam.sdk.values.PValue;
import org.apache.beam.sdk.values.TupleTag;
import org.apache.beam.sdk.values.WindowedValue;
import org.apache.beam.sdk.values.WindowedValues;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.collect.Iterables;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.streaming.api.java.JavaStreamingContext;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The EvaluationContext allows us to define pipeline instructions and translate between {@code
 * PObject<T>}s or {@code PCollection<T>}s and Ts or DStreams/RDDs of Ts.
 */
@SuppressWarnings({
  "rawtypes", // TODO(https://github.com/apache/beam/issues/20447)
  "nullness" // TODO(https://github.com/apache/beam/issues/20497)
})
public class EvaluationContext {
  private final JavaSparkContext jsc;
  private JavaStreamingContext jssc;
  private final Pipeline pipeline;
  private final Map<PValue, Dataset> datasets = new LinkedHashMap<>();
  private final Map<PValue, Dataset> pcollections = new LinkedHashMap<>();
  private final Set<Dataset> leaves = new LinkedHashSet<>();
  private final Map<PCollection<?>, Integer> pCollectionConsumptionMap = new HashMap<>();
  private final Map<PValue, Object> pobjects = new LinkedHashMap<>();
  private AppliedPTransform<?, ?, ?> currentTransform;
  private final SparkPCollectionView pviews = new SparkPCollectionView();
  private final Map<PCollection, Long> cacheCandidates = new HashMap<>();
  private final Map<GroupByKey<?, ?>, String> groupByKeyCandidatesForMemoryOptimizedTranslation =
      new HashMap<>();
  private final PipelineOptions options;
  private final SerializablePipelineOptions serializableOptions;
  private boolean streamingSideInput = false;

  public EvaluationContext(JavaSparkContext jsc, Pipeline pipeline, PipelineOptions options) {
    this.jsc = jsc;
    this.pipeline = pipeline;
    this.options = options;
    this.serializableOptions = new SerializablePipelineOptions(options);
  }

  public EvaluationContext(
      JavaSparkContext jsc, Pipeline pipeline, PipelineOptions options, JavaStreamingContext jssc) {
    this(jsc, pipeline, options);
    this.jssc = jssc;
  }

  public JavaSparkContext getSparkContext() {
    return jsc;
  }

  public JavaStreamingContext getStreamingContext() {
    return jssc;
  }

  public Pipeline getPipeline() {
    return pipeline;
  }

  public PipelineOptions getOptions() {
    return options;
  }

  public SerializablePipelineOptions getSerializableOptions() {
    return serializableOptions;
  }

  public void setCurrentTransform(AppliedPTransform<?, ?, ?> transform) {
    this.currentTransform = transform;
  }

  public AppliedPTransform<?, ?, ?> getCurrentTransform() {
    return currentTransform;
  }

  public <T extends PValue> T getInput(PTransform<T, ?> transform) {
    @SuppressWarnings("unchecked")
    T input =
        (T) Iterables.getOnlyElement(TransformInputs.nonAdditionalInputs(getCurrentTransform()));
    return input;
  }

  public <T> Map<TupleTag<?>, PCollection<?>> getInputs(PTransform<?, ?> transform) {
    checkArgument(currentTransform != null, "can only be called with non-null currentTransform");
    checkArgument(
        currentTransform.getTransform() == transform, "can only be called with current transform");
    return currentTransform.getInputs();
  }

  public <T extends PValue> T getOutput(PTransform<?, T> transform) {
    @SuppressWarnings("unchecked")
    T output = (T) Iterables.getOnlyElement(getOutputs(transform).values());
    return output;
  }

  public Map<TupleTag<?>, PCollection<?>> getOutputs(PTransform<?, ?> transform) {
    checkArgument(currentTransform != null, "can only be called with non-null currentTransform");
    checkArgument(
        currentTransform.getTransform() == transform, "can only be called with current transform");
    return currentTransform.getOutputs();
  }

  public Map<TupleTag<?>, Coder<?>> getOutputCoders() {
    return currentTransform.getOutputs().entrySet().stream()
        .filter(e -> e.getValue() instanceof PCollection)
        .collect(Collectors.toMap(Map.Entry::getKey, e -> ((PCollection) e.getValue()).getCoder()));
  }

  /**
   * Cache PCollection if SparkPipelineOptions.isCacheDisabled is false or transform isn't
   * GroupByKey transformation and PCollection is used more then once in Pipeline.
   *
   * <p>PCollection is not cached in GroupByKey transformation, because Spark automatically persists
   * some intermediate data in shuffle operations, even without users calling persist.
   *
   * @param pvalue output of transform
   * @param transform the transform to check
   * @return if PCollection will be cached
   */
  public boolean shouldCache(PTransform<?, ? extends PValue> transform, PValue pvalue) {
    if (serializableOptions.get().as(SparkPipelineOptions.class).isCacheDisabled()
        || transform instanceof GroupByKey) {
      return false;
    }
    return pvalue instanceof PCollection && cacheCandidates.getOrDefault(pvalue, 0L) > 1;
  }

  /**
   * Add single output of transform to context map and possibly cache if it conforms {@link
   * #shouldCache(PTransform, PValue)}.
   *
   * @param transform from which Dataset was created
   * @param dataset created Dataset from transform
   */
  public void putDataset(PTransform<?, ? extends PValue> transform, Dataset dataset) {
    putDataset(transform, getOutput(transform), dataset);
  }

  /**
   * Add output of transform to context map and possibly cache if it conforms {@link
   * #shouldCache(PTransform, PValue)}. Used when PTransform has multiple outputs.
   *
   * @param pvalue one of multiple outputs of transform
   * @param dataset created Dataset from transform
   */
  public void putDataset(PValue pvalue, Dataset dataset) {
    putDataset(null, pvalue, dataset);
  }

  /**
   * Add output of transform to context map and possibly cache if it conforms {@link
   * #shouldCache(PTransform, PValue)}.
   *
   * @param transform from which Dataset was created
   * @param pvalue output of transform
   * @param dataset created Dataset from transform
   */
  private void putDataset(
      @Nullable PTransform<?, ? extends PValue> transform, PValue pvalue, Dataset dataset) {
    try {
      dataset.setName(pvalue.getName());
    } catch (IllegalStateException e) {
      // name not set, ignore
    }
    if (shouldCache(transform, pvalue)) {
      // we cache only PCollection
      Coder<?> coder = ((PCollection<?>) pvalue).getCoder();
      Coder<? extends BoundedWindow> wCoder =
          ((PCollection<?>) pvalue).getWindowingStrategy().getWindowFn().windowCoder();
      dataset.cache(storageLevel(), WindowedValues.getFullCoder(coder, wCoder));
    }
    datasets.put(pvalue, dataset);
    leaves.add(dataset);
  }

  public Dataset borrowDataset(PTransform<? extends PValue, ?> transform) {
    return borrowDataset(getInput(transform));
  }

  public Dataset borrowDataset(PValue pvalue) {
    Dataset dataset = datasets.get(pvalue);
    leaves.remove(dataset);
    return dataset;
  }

  /**
   * Computes the outputs for all RDDs that are leaves in the DAG and do not have any actions (like
   * saving to a file) registered on them (i.e. they are performed for side effects).
   */
  public void computeOutputs() {
    for (Dataset dataset : leaves) {
      dataset.action(); // force computation.
    }
  }

  /**
   * Retrieve an object of Type T associated with the PValue passed in.
   *
   * @param value PValue to retrieve associated data for.
   * @param <T> Type of object to return.
   * @return Native object.
   */
  @SuppressWarnings("TypeParameterUnusedInFormals")
  public <T> T get(PValue value) {
    if (pobjects.containsKey(value)) {
      return (T) pobjects.get(value);
    }
    if (pcollections.containsKey(value)) {
      JavaRDD<?> rdd = ((BoundedDataset) pcollections.get(value)).getRDD();
      T res = (T) Iterables.getOnlyElement(rdd.collect());
      pobjects.put(value, res);
      return res;
    }
    throw new IllegalStateException("Cannot resolve un-known PObject: " + value);
  }

  /**
   * Return the current views creates in the pipeline.
   *
   * @return SparkPCollectionView
   */
  public SparkPCollectionView getPViews() {
    return pviews;
  }

  /**
   * Adds/Replaces a view to the current views creates in the pipeline.
   *
   * @param view - Identifier of the view
   * @param value - Actual value of the view
   * @param coder - Coder of the value
   */
  public void putPView(
      PCollectionView<?> view,
      Iterable<WindowedValue<?>> value,
      Coder<Iterable<WindowedValue<?>>> coder) {
    pviews.putPView(view, value, coder);
  }

  /**
   * Get the map of cache candidates hold by the evaluation context.
   *
   * @return The current {@link Map} of cache candidates.
   */
  public Map<PCollection, Long> getCacheCandidates() {
    return this.cacheCandidates;
  }

  /**
   * Get the map of GBK transforms to their full names, which are candidates for group by key and
   * window translation which aims to reduce memory usage.
   *
   * @return The current {@link Map} of candidates
   */
  public Map<GroupByKey<?, ?>, String> getCandidatesForGroupByKeyAndWindowTranslation() {
    return this.groupByKeyCandidatesForMemoryOptimizedTranslation;
  }

  /**
   * Returns if given GBK transform can be considered as candidate for group by key and window
   * translation aiming to reduce memory usage.
   *
   * @param transform to evaluate
   * @return true if given transform is a candidate; false otherwise
   * @param <K> type of GBK key
   * @param <V> type of GBK value
   */
  public <K, V> boolean isCandidateForGroupByKeyAndWindow(GroupByKey<K, V> transform) {
    return groupByKeyCandidatesForMemoryOptimizedTranslation.containsKey(transform);
  }

  /**
   * Reports that given {@link PCollection} is consumed by a {@link PTransform} in the pipeline.
   *
   * @see #isLeaf(PCollection)
   */
  public void reportPCollectionConsumed(PCollection<?> pCollection) {
    int count = this.pCollectionConsumptionMap.getOrDefault(pCollection, 0);
    this.pCollectionConsumptionMap.put(pCollection, count + 1);
  }

  /**
   * Reports that given {@link PCollection} is consumed by a {@link PTransform} in the pipeline.
   *
   * @see #isLeaf(PCollection)
   */
  public void reportPCollectionProduced(PCollection<?> pCollection) {
    this.pCollectionConsumptionMap.computeIfAbsent(pCollection, k -> 0);
  }

  /**
   * Get the map of {@link PCollection} to the number of {@link PTransform} consuming it.
   *
   * @return
   */
  public Map<PCollection<?>, Integer> getPCollectionConsumptionMap() {
    return Collections.unmodifiableMap(pCollectionConsumptionMap);
  }

  /**
   * Check if given {@link PCollection} is a leaf or not. {@link PCollection} is a leaf when there
   * is no other {@link PTransform} consuming it / depending on it.
   *
   * @param pCollection to be checked if it is a leaf
   * @return true if pCollection is leaf; otherwise false
   */
  public boolean isLeaf(PCollection<?> pCollection) {
    return this.pCollectionConsumptionMap.get(pCollection) == 0;
  }

  <T> Iterable<WindowedValue<T>> getWindowedValues(PCollection<T> pcollection) {
    @SuppressWarnings("unchecked")
    BoundedDataset<T> boundedDataset = (BoundedDataset<T>) datasets.get(pcollection);
    leaves.remove(boundedDataset);
    return boundedDataset.getValues(pcollection);
  }

  public String storageLevel() {
    return serializableOptions.get().as(SparkPipelineOptions.class).getStorageLevel();
  }

  /**
   * Checks if any of the side inputs in the pipeline are streaming side inputs.
   *
   * <p>If at least one of the side inputs is a streaming side input, this method returns true. When
   * streaming side inputs are present, the {@link
   * org.apache.beam.runners.spark.util.CachedSideInputReader} will not be used.
   *
   * @return true if any of the side inputs in the pipeline are streaming side inputs, false
   *     otherwise
   */
  public boolean isStreamingSideInput() {
    return streamingSideInput;
  }

  /**
   * Marks that the pipeline contains at least one streaming side input.
   *
   * <p>When this method is called, it sets the streamingSideInput flag to true, indicating that the
   * {@link org.apache.beam.runners.spark.util.CachedSideInputReader} should not be used for
   * processing side inputs.
   */
  public void useStreamingSideInput() {
    if (!this.streamingSideInput) {
      this.streamingSideInput = true;
    }
  }
}
