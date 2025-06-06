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
package org.apache.beam.runners.flink.translation.wrappers;

import java.io.IOException;
import java.util.List;
import org.apache.beam.runners.core.construction.SerializablePipelineOptions;
import org.apache.beam.runners.flink.FlinkPipelineOptions;
import org.apache.beam.runners.flink.metrics.FlinkMetricContainer;
import org.apache.beam.runners.flink.metrics.ReaderInvocationUtil;
import org.apache.beam.sdk.io.BoundedSource;
import org.apache.beam.sdk.io.FileBasedSource;
import org.apache.beam.sdk.io.Source;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.transforms.windowing.GlobalWindow;
import org.apache.beam.sdk.transforms.windowing.PaneInfo;
import org.apache.beam.sdk.values.WindowedValue;
import org.apache.beam.sdk.values.WindowedValues;
import org.apache.flink.api.common.io.DefaultInputSplitAssigner;
import org.apache.flink.api.common.io.InputFormat;
import org.apache.flink.api.common.io.RichInputFormat;
import org.apache.flink.api.common.io.statistics.BaseStatistics;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.core.io.InputSplitAssigner;
import org.joda.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Wrapper for executing a {@link Source} as a Flink {@link InputFormat}. */
@SuppressWarnings({
  "rawtypes", // TODO(https://github.com/apache/beam/issues/20447)
  "nullness" // TODO(https://github.com/apache/beam/issues/20497)
})
public class SourceInputFormat<T> extends RichInputFormat<WindowedValue<T>, SourceInputSplit<T>> {
  private static final Logger LOG = LoggerFactory.getLogger(SourceInputFormat.class);

  private final String stepName;
  private final BoundedSource<T> initialSource;

  private transient PipelineOptions options;
  private final SerializablePipelineOptions serializedOptions;

  private transient BoundedSource.BoundedReader<T> reader;
  private boolean inputAvailable = false;

  private transient ReaderInvocationUtil<T, BoundedSource.BoundedReader<T>> readerInvoker;
  private transient FlinkMetricContainer metricContainer;

  public SourceInputFormat(
      String stepName, BoundedSource<T> initialSource, PipelineOptions options) {
    this.stepName = stepName;
    this.initialSource = initialSource;
    this.serializedOptions = new SerializablePipelineOptions(options);
  }

  @Override
  public void configure(Configuration configuration) {
    options = serializedOptions.get();
  }

  @Override
  public void open(SourceInputSplit<T> sourceInputSplit) throws IOException {
    metricContainer = new FlinkMetricContainer(getRuntimeContext());

    readerInvoker = new ReaderInvocationUtil<>(stepName, serializedOptions.get(), metricContainer);

    reader = ((BoundedSource<T>) sourceInputSplit.getSource()).createReader(options);
    inputAvailable = readerInvoker.invokeStart(reader);
  }

  @Override
  public BaseStatistics getStatistics(BaseStatistics baseStatistics) throws IOException {
    try {
      final long estimatedSize = initialSource.getEstimatedSizeBytes(options);

      return new BaseStatistics() {
        @Override
        public long getTotalInputSize() {
          return estimatedSize;
        }

        @Override
        public long getNumberOfRecords() {
          return BaseStatistics.NUM_RECORDS_UNKNOWN;
        }

        @Override
        public float getAverageRecordWidth() {
          return BaseStatistics.AVG_RECORD_BYTES_UNKNOWN;
        }
      };
    } catch (Exception e) {
      LOG.warn("Could not read Source statistics.", e);
    }

    return null;
  }

  private long getDesiredSizeBytes(int numSplits) throws Exception {
    long totalSize = initialSource.getEstimatedSizeBytes(options);
    long defaultSplitSize = totalSize / numSplits;
    long maxSplitSize = 0;
    if (options != null) {
      maxSplitSize = options.as(FlinkPipelineOptions.class).getFileInputSplitMaxSizeMB();
    }
    if (initialSource instanceof FileBasedSource && maxSplitSize > 0) {
      // Most of the time parallelism is < number of files in source.
      // Each file becomes a unique split which commonly create skew.
      // This limits the size of splits to reduce skew.
      return Math.min(defaultSplitSize, maxSplitSize * 1024 * 1024);
    } else {
      return defaultSplitSize;
    }
  }

  @Override
  @SuppressWarnings("unchecked")
  public SourceInputSplit<T>[] createInputSplits(int numSplits) throws IOException {
    try {
      long desiredSizeBytes = getDesiredSizeBytes(numSplits);
      List<? extends Source<T>> shards = initialSource.split(desiredSizeBytes, options);

      int numShards = shards.size();
      SourceInputSplit<T>[] sourceInputSplits = new SourceInputSplit[numShards];
      for (int i = 0; i < numShards; i++) {
        sourceInputSplits[i] = new SourceInputSplit<>(shards.get(i), i);
      }
      return sourceInputSplits;
    } catch (Exception e) {
      throw new IOException("Could not create input splits from Source.", e);
    }
  }

  @Override
  public InputSplitAssigner getInputSplitAssigner(final SourceInputSplit[] sourceInputSplits) {
    return new DefaultInputSplitAssigner(sourceInputSplits);
  }

  @Override
  public boolean reachedEnd() {
    return !inputAvailable;
  }

  @Override
  public WindowedValue<T> nextRecord(WindowedValue<T> t) throws IOException {
    if (inputAvailable) {
      final T current = reader.getCurrent();
      final Instant timestamp = reader.getCurrentTimestamp();
      // advance reader to have a record ready next time
      inputAvailable = readerInvoker.invokeAdvance(reader);
      return WindowedValues.of(current, timestamp, GlobalWindow.INSTANCE, PaneInfo.NO_FIRING);
    }

    return null;
  }

  @Override
  public void close() throws IOException {
    if (metricContainer != null) {
      metricContainer.registerMetricsForPipelineResult();
    }
    // TODO null check can be removed once FLINK-3796 is fixed
    if (reader != null) {
      reader.close();
    }
  }
}
