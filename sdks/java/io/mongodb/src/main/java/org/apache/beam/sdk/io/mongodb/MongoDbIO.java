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
package org.apache.beam.sdk.io.mongodb;

import static org.apache.beam.sdk.io.mongodb.FindQuery.bson2BsonDocument;
import static org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.base.Preconditions.checkArgument;

import com.google.auto.value.AutoValue;
import com.mongodb.BasicDBObject;
import com.mongodb.MongoBulkWriteException;
import com.mongodb.MongoClient;
import com.mongodb.MongoClientOptions;
import com.mongodb.MongoClientURI;
import com.mongodb.MongoCommandException;
import com.mongodb.client.AggregateIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.BulkWriteOptions;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.InsertManyOptions;
import com.mongodb.client.model.UpdateOneModel;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.WriteModel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.net.ssl.SSLContext;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.coders.SerializableCoder;
import org.apache.beam.sdk.io.BoundedSource;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.SerializableFunction;
import org.apache.beam.sdk.transforms.display.DisplayData;
import org.apache.beam.sdk.util.Preconditions;
import org.apache.beam.sdk.values.PBegin;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PDone;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.annotations.VisibleForTesting;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.dataflow.qual.Pure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * IO to read and write data on MongoDB.
 *
 * <h3>Reading from MongoDB</h3>
 *
 * <p>MongoDbIO source returns a bounded collection of String as {@code PCollection<String>}. The
 * String is the JSON form of the MongoDB Document.
 *
 * <p>To configure the MongoDB source, you have to provide the connection URI, the database name and
 * the collection name. The following example illustrates various options for configuring the
 * source:
 *
 * <pre>{@code
 * pipeline.apply(MongoDbIO.read()
 *   .withUri("mongodb://localhost:27017")
 *   .withDatabase("my-database")
 *   .withCollection("my-collection"))
 *   // above three are required configuration, returns PCollection<String>
 *
 *   // rest of the settings are optional
 *
 * }</pre>
 *
 * <p>The source also accepts an optional configuration: {@code withFilter()} allows you to define a
 * JSON filter to get subset of data.
 *
 * <h3>Writing to MongoDB</h3>
 *
 * <p>MongoDB sink supports writing of Document (as JSON String) in a MongoDB.
 *
 * <p>To configure a MongoDB sink and insert/replace, you must specify a connection {@code URI}, a
 * {@code Database} name, a {@code Collection} name. For instance:
 *
 * <pre>{@code
 * pipeline
 *   .apply(...)
 *   .apply(MongoDbIO.write()
 *     .withUri("mongodb://localhost:27017")
 *     .withDatabase("my-database")
 *     .withCollection("my-collection")
 *     .withNumSplits(30))
 *
 * }</pre>
 *
 * *
 *
 * <p>To configure a MongoDB sink and update, you must specify a connection {@code URI}, a {@code
 * Database} * name, a {@code Collection} name. It matches the key with _id in target collection.
 * For instance: * *
 *
 * <pre>{@code
 * * pipeline
 * *   .apply(...)
 * *   .apply(MongoDbIO.write()
 * *     .withUri("mongodb://localhost:27017")
 * *     .withDatabase("my-database")
 * *     .withCollection("my-collection")
 * *     .withUpdateConfiguration(UpdateConfiguration.create().withFindKey("key1").withUpdateKey("key2")
 * *     .withUpdateFields(UpdateField.fieldUpdate("$set", "source-field1", "dest-field1"),
 * *                       UpdateField.fieldUpdate("$set","source-field2", "dest-field2"),
 * *                       //pushes entire input doc to the dest field
 * *                        UpdateField.fullUpdate("$push", "dest-field3") )));
 * *
 * }</pre>
 */
public class MongoDbIO {

  private static final Logger LOG = LoggerFactory.getLogger(MongoDbIO.class);

  public static final String ERROR_MSG_QUERY_FN =
      " class is not supported. "
          + "Please provide one of the predefined classes in the MongoDbIO package "
          + "such as FindQuery or AggregationQuery.";

  /** Read data from MongoDB. */
  public static Read read() {
    return new AutoValue_MongoDbIO_Read.Builder()
        .setMaxConnectionIdleTime(60000)
        .setNumSplits(0)
        .setBucketAuto(false)
        .setSslEnabled(false)
        .setIgnoreSSLCertificate(false)
        .setSslInvalidHostNameAllowed(false)
        .setQueryFn(FindQuery.create())
        .build();
  }

  /** Write data to MongoDB. */
  public static Write write() {
    return new AutoValue_MongoDbIO_Write.Builder()
        .setMaxConnectionIdleTime(60000)
        .setBatchSize(1024L)
        .setSslEnabled(false)
        .setIgnoreSSLCertificate(false)
        .setSslInvalidHostNameAllowed(false)
        .setOrdered(true)
        .build();
  }

  private MongoDbIO() {}

  /** A {@link PTransform} to read data from MongoDB. */
  @AutoValue
  public abstract static class Read extends PTransform<PBegin, PCollection<Document>> {

    @Pure
    abstract @Nullable String uri();

    @Pure
    abstract int maxConnectionIdleTime();

    @Pure
    abstract boolean sslEnabled();

    @Pure
    abstract boolean sslInvalidHostNameAllowed();

    @Pure
    abstract boolean ignoreSSLCertificate();

    @Pure
    abstract @Nullable String database();

    @Pure
    abstract @Nullable String collection();

    @Pure
    abstract int numSplits();

    @Pure
    abstract boolean bucketAuto();

    @Pure
    abstract SerializableFunction<MongoCollection<Document>, MongoCursor<Document>> queryFn();

    @Pure
    abstract Builder builder();

    @AutoValue.Builder
    abstract static class Builder {
      abstract Builder setUri(String uri);

      abstract Builder setMaxConnectionIdleTime(int maxConnectionIdleTime);

      abstract Builder setSslEnabled(boolean value);

      abstract Builder setSslInvalidHostNameAllowed(boolean value);

      abstract Builder setIgnoreSSLCertificate(boolean value);

      abstract Builder setDatabase(String database);

      abstract Builder setCollection(String collection);

      abstract Builder setNumSplits(int numSplits);

      abstract Builder setBucketAuto(boolean bucketAuto);

      abstract Builder setQueryFn(
          SerializableFunction<MongoCollection<Document>, MongoCursor<Document>> queryBuilder);

      abstract Read build();
    }

    /**
     * Define the location of the MongoDB instances using an URI. The URI describes the hosts to be
     * used and some options.
     *
     * <p>The format of the URI is:
     *
     * <pre>{@code
     * mongodb://[username:password@]host1[:port1]...[,hostN[:portN]]][/[database][?options]]
     * }</pre>
     *
     * <p>Where:
     *
     * <ul>
     *   <li>{@code mongodb://} is a required prefix to identify that this is a string in the
     *       standard connection format.
     *   <li>{@code username:password@} are optional. If given, the driver will attempt to login to
     *       a database after connecting to a database server. For some authentication mechanisms,
     *       only the username is specified and the password is not, in which case the ":" after the
     *       username is left off as well.
     *   <li>{@code host1} is the only required part of the URI. It identifies a server address to
     *       connect to.
     *   <li>{@code :portX} is optional and defaults to {@code :27017} if not provided.
     *   <li>{@code /database} is the name of the database to login to and thus is only relevant if
     *       the {@code username:password@} syntax is used. If not specified, the "admin" database
     *       will be used by default. It has to be equivalent with the database you specific with
     *       {@link Read#withDatabase(String)}.
     *   <li>{@code ?options} are connection options. Note that if {@code database} is absent there
     *       is still a {@code /} required between the last {@code host} and the {@code ?}
     *       introducing the options. Options are name=value pairs and the pairs are separated by
     *       "{@code &}". You can pass the {@code MaxConnectionIdleTime} connection option via
     *       {@link Read#withMaxConnectionIdleTime(int)}.
     * </ul>
     */
    public Read withUri(String uri) {
      checkArgument(uri != null, "MongoDbIO.read().withUri(uri) called with null uri");
      return builder().setUri(uri).build();
    }

    /** Sets the maximum idle time for a pooled connection. */
    public Read withMaxConnectionIdleTime(int maxConnectionIdleTime) {
      return builder().setMaxConnectionIdleTime(maxConnectionIdleTime).build();
    }

    /** Enable ssl for connection. */
    public Read withSSLEnabled(boolean sslEnabled) {
      return builder().setSslEnabled(sslEnabled).build();
    }

    /** Enable invalidHostNameAllowed for ssl for connection. */
    public Read withSSLInvalidHostNameAllowed(boolean invalidHostNameAllowed) {
      return builder().setSslInvalidHostNameAllowed(invalidHostNameAllowed).build();
    }

    /** Enable ignoreSSLCertificate for ssl for connection (allow for self signed certificates). */
    public Read withIgnoreSSLCertificate(boolean ignoreSSLCertificate) {
      return builder().setIgnoreSSLCertificate(ignoreSSLCertificate).build();
    }

    /** Sets the database to use. */
    public Read withDatabase(String database) {
      checkArgument(database != null, "database can not be null");
      return builder().setDatabase(database).build();
    }

    /** Sets the collection to consider in the database. */
    public Read withCollection(String collection) {
      checkArgument(collection != null, "collection can not be null");
      return builder().setCollection(collection).build();
    }

    /** Sets the user defined number of splits. */
    public Read withNumSplits(int numSplits) {
      checkArgument(numSplits >= 0, "invalid num_splits: must be >= 0, but was %s", numSplits);
      return builder().setNumSplits(numSplits).build();
    }

    /** Sets weather to use $bucketAuto or not. */
    public Read withBucketAuto(boolean bucketAuto) {
      return builder().setBucketAuto(bucketAuto).build();
    }

    /**
     * Sets a queryFn. The provided queryFn must be one of the predefined classes in the MongoDbIO
     * package such as {@link FindQuery#FindQuery} or {@link AggregationQuery#AggregationQuery}.
     */
    public Read withQueryFn(
        SerializableFunction<MongoCollection<Document>, MongoCursor<Document>> queryBuilderFn) {
      checkArgument(
          Arrays.asList(AutoValue_FindQuery.class, AutoValue_AggregationQuery.class)
              .contains(queryBuilderFn.getClass()),
          String.format("[%s]" + ERROR_MSG_QUERY_FN, queryBuilderFn.getClass().getName()));
      return builder().setQueryFn(queryBuilderFn).build();
    }

    @Override
    public PCollection<Document> expand(PBegin input) {
      checkArgument(uri() != null, "withUri() is required");
      checkArgument(database() != null, "withDatabase() is required");
      checkArgument(collection() != null, "withCollection() is required");
      return input.apply(org.apache.beam.sdk.io.Read.from(new BoundedMongoDbSource(this)));
    }

    public long getDocumentCount() {
      checkArgument(uri() != null, "withUri() is required");
      checkArgument(database() != null, "withDatabase() is required");
      checkArgument(collection() != null, "withCollection() is required");
      return new BoundedMongoDbSource(this).getDocumentCount();
    }

    @Override
    public void populateDisplayData(DisplayData.Builder builder) {
      super.populateDisplayData(builder);
      builder.add(DisplayData.item("uri", uri()));
      builder.add(DisplayData.item("maxConnectionIdleTime", maxConnectionIdleTime()));
      builder.add(DisplayData.item("sslEnabled", sslEnabled()));
      builder.add(DisplayData.item("sslInvalidHostNameAllowed", sslInvalidHostNameAllowed()));
      builder.add(DisplayData.item("ignoreSSLCertificate", ignoreSSLCertificate()));
      builder.add(DisplayData.item("database", database()));
      builder.add(DisplayData.item("collection", collection()));
      builder.add(DisplayData.item("numSplit", numSplits()));
      builder.add(DisplayData.item("bucketAuto", bucketAuto()));
      builder.add(DisplayData.item("queryFn", queryFn().toString()));
    }
  }

  private static MongoClientOptions.Builder getOptions(
      int maxConnectionIdleTime,
      boolean sslEnabled,
      boolean sslInvalidHostNameAllowed,
      boolean ignoreSSLCertificate) {
    MongoClientOptions.Builder optionsBuilder = new MongoClientOptions.Builder();
    optionsBuilder.maxConnectionIdleTime(maxConnectionIdleTime);
    if (sslEnabled) {
      optionsBuilder.sslEnabled(sslEnabled).sslInvalidHostNameAllowed(sslInvalidHostNameAllowed);
      if (ignoreSSLCertificate) {
        SSLContext sslContext = SSLUtils.ignoreSSLCertificate();
        optionsBuilder.sslContext(sslContext);
        optionsBuilder.socketFactory(sslContext.getSocketFactory());
      }
    }
    return optionsBuilder;
  }

  /** A MongoDB {@link BoundedSource} reading {@link Document} from a given instance. */
  @VisibleForTesting
  static class BoundedMongoDbSource extends BoundedSource<Document> {
    private final Read spec;

    @VisibleForTesting
    BoundedMongoDbSource(Read spec) {
      this.spec = spec;
    }

    @Override
    public Coder<Document> getOutputCoder() {
      return SerializableCoder.of(Document.class);
    }

    @Override
    public void populateDisplayData(DisplayData.Builder builder) {
      spec.populateDisplayData(builder);
    }

    @Override
    public BoundedReader<Document> createReader(PipelineOptions options) {
      return new BoundedMongoDbReader(this);
    }

    /**
     * Returns number of Documents in a collection.
     *
     * @return Positive number of Documents in a collection or -1 on error.
     */
    long getDocumentCount() {
      String uri = Preconditions.checkStateNotNull(spec.uri());
      String database = Preconditions.checkStateNotNull(spec.database());
      String collection = Preconditions.checkStateNotNull(spec.collection());
      try (MongoClient mongoClient =
          new MongoClient(
              new MongoClientURI(
                  uri,
                  getOptions(
                      spec.maxConnectionIdleTime(),
                      spec.sslEnabled(),
                      spec.sslInvalidHostNameAllowed(),
                      spec.ignoreSSLCertificate())))) {
        return getDocumentCount(mongoClient, database, collection);
      } catch (Exception e) {
        return -1;
      }
    }

    private long getDocumentCount(MongoClient mongoClient, String database, String collection) {
      MongoDatabase mongoDatabase = mongoClient.getDatabase(database);

      // get the Mongo collStats object
      // it gives the size for the entire collection
      BasicDBObject stat = new BasicDBObject();
      stat.append("collStats", collection);
      Document stats = mongoDatabase.runCommand(stat);

      return stats.get("count", Number.class).longValue();
    }

    @Override
    public long getEstimatedSizeBytes(PipelineOptions pipelineOptions) {
      String uri = Preconditions.checkStateNotNull(spec.uri());
      String database = Preconditions.checkStateNotNull(spec.database());
      String collection = Preconditions.checkStateNotNull(spec.collection());
      try (MongoClient mongoClient =
          new MongoClient(
              new MongoClientURI(
                  uri,
                  getOptions(
                      spec.maxConnectionIdleTime(),
                      spec.sslEnabled(),
                      spec.sslInvalidHostNameAllowed(),
                      spec.ignoreSSLCertificate())))) {
        try {
          return getEstimatedSizeBytes(mongoClient, database, collection);
        } catch (MongoCommandException exception) {
          LOG.warn("Cannot estimate size: ", exception);
          return 0L;
        }
      }
    }

    private long getEstimatedSizeBytes(
        MongoClient mongoClient, String database, String collection) {
      MongoDatabase mongoDatabase = mongoClient.getDatabase(database);

      // get the Mongo collStats object
      // it gives the size for the entire collection
      BasicDBObject stat = new BasicDBObject();
      stat.append("collStats", collection);
      Document stats = mongoDatabase.runCommand(stat);

      return stats.get("size", Number.class).longValue();
    }

    @Override
    public List<BoundedSource<Document>> split(
        long desiredBundleSizeBytes, PipelineOptions options) {
      String uri = Preconditions.checkStateNotNull(spec.uri());
      String database = Preconditions.checkStateNotNull(spec.database());
      String collection = Preconditions.checkStateNotNull(spec.collection());
      try (MongoClient mongoClient =
          new MongoClient(
              new MongoClientURI(
                  uri,
                  getOptions(
                      spec.maxConnectionIdleTime(),
                      spec.sslEnabled(),
                      spec.sslInvalidHostNameAllowed(),
                      spec.ignoreSSLCertificate())))) {
        MongoDatabase mongoDatabase = mongoClient.getDatabase(database);

        List<Document> splitKeys;
        List<BoundedSource<Document>> sources = new ArrayList<>();

        if (spec.queryFn().getClass() == AutoValue_FindQuery.class) {
          if (spec.bucketAuto()) {
            splitKeys = buildAutoBuckets(mongoDatabase, spec);
          } else {
            if (spec.numSplits() <= 0) {
              LOG.debug("Split keys disabled, using a unique source");
              return Collections.singletonList(this);
            } else {
              // the user defines his desired number of splits
              // calculate the batch size
              long estimatedSizeBytes = getEstimatedSizeBytes(mongoClient, database, collection);
              desiredBundleSizeBytes = estimatedSizeBytes / spec.numSplits();
            }

            // the desired batch size is small, using default chunk size of 1MB
            if (desiredBundleSizeBytes < 1024L * 1024L) {
              desiredBundleSizeBytes = 1024L * 1024L;
            }

            // now we have the batch size (provided by user or provided by the runner)
            // we use Mongo splitVector command to get the split keys
            BasicDBObject splitVectorCommand = new BasicDBObject();
            splitVectorCommand.append("splitVector", spec.database() + "." + spec.collection());
            splitVectorCommand.append("keyPattern", new BasicDBObject().append("_id", 1));
            splitVectorCommand.append("force", false);
            // maxChunkSize is the Mongo partition size in MB
            LOG.debug("Splitting in chunk of {} MB", desiredBundleSizeBytes / 1024 / 1024);
            splitVectorCommand.append("maxChunkSize", desiredBundleSizeBytes / 1024 / 1024);
            Document splitVectorCommandResult = mongoDatabase.runCommand(splitVectorCommand);
            splitKeys = (List<Document>) splitVectorCommandResult.get("splitKeys");
          }

          if (splitKeys.size() < 1) {
            LOG.debug("Split keys is low, using a unique source");
            return Collections.singletonList(this);
          }

          for (String shardFilter : splitKeysToFilters(splitKeys)) {
            SerializableFunction<MongoCollection<Document>, MongoCursor<Document>> queryFn =
                spec.queryFn();

            BsonDocument filters = bson2BsonDocument(Document.parse(shardFilter));
            FindQuery findQuery = (FindQuery) queryFn;
            final BsonDocument allFilters =
                bson2BsonDocument(
                    findQuery.filters() != null
                        ? Filters.and(findQuery.filters(), filters)
                        : filters);
            FindQuery queryWithFilter = findQuery.toBuilder().setFilters(allFilters).build();
            LOG.debug("using filters: " + allFilters.toJson());
            sources.add(new BoundedMongoDbSource(spec.withQueryFn(queryWithFilter)));
          }
        } else {
          SerializableFunction<MongoCollection<Document>, MongoCursor<Document>> queryFn =
              spec.queryFn();
          AggregationQuery aggregationQuery = (AggregationQuery) queryFn;
          if (aggregationQuery.mongoDbPipeline().stream()
              .anyMatch(s -> s.keySet().contains("$limit"))) {
            return Collections.singletonList(this);
          }
          splitKeys = buildAutoBuckets(mongoDatabase, spec);

          for (BsonDocument shardFilter : splitKeysToMatch(splitKeys)) {
            AggregationQuery queryWithBucket =
                aggregationQuery.toBuilder().setBucket(shardFilter).build();
            sources.add(new BoundedMongoDbSource(spec.withQueryFn(queryWithBucket)));
          }
        }
        return sources;
      }
    }

    /**
     * Transform a list of split keys as a list of filters containing corresponding range.
     *
     * <p>The list of split keys contains BSon Document basically containing for example:
     *
     * <ul>
     *   <li>_id: 56
     *   <li>_id: 109
     *   <li>_id: 256
     * </ul>
     *
     * <p>This method will generate a list of range filters performing the following splits:
     *
     * <ul>
     *   <li>from the beginning of the collection up to _id 56, so basically data with _id lower
     *       than 56
     *   <li>from _id 57 up to _id 109
     *   <li>from _id 110 up to _id 256
     *   <li>from _id 257 up to the end of the collection, so basically data with _id greater than
     *       257
     * </ul>
     *
     * @param splitKeys The list of split keys.
     * @return A list of filters containing the ranges.
     */
    @VisibleForTesting
    static List<String> splitKeysToFilters(List<Document> splitKeys) {
      ArrayList<String> filters = new ArrayList<>();
      String lowestBound = null; // lower boundary (previous split in the iteration)
      for (int i = 0; i < splitKeys.size(); i++) {
        String splitKey = splitKeys.get(i).get("_id").toString();
        String rangeFilter;
        if (i == 0) {
          // this is the first split in the list, the filter defines
          // the range from the beginning up to this split
          rangeFilter = String.format("{ $and: [ {\"_id\":{$lte:ObjectId(\"%s\")}}", splitKey);
          filters.add(String.format("%s ]}", rangeFilter));
          // If there is only one split, also generate a range from the split to the end
          if (splitKeys.size() == 1) {
            rangeFilter = String.format("{ $and: [ {\"_id\":{$gt:ObjectId(\"%s\")}}", splitKey);
            filters.add(String.format("%s ]}", rangeFilter));
          }
        } else if (i == splitKeys.size() - 1) {
          // this is the last split in the list, the filters define
          // the range from the previous split to the current split and also
          // the current split to the end
          rangeFilter =
              String.format(
                  "{ $and: [ {\"_id\":{$gt:ObjectId(\"%s\")," + "$lte:ObjectId(\"%s\")}}",
                  lowestBound, splitKey);
          filters.add(String.format("%s ]}", rangeFilter));
          rangeFilter = String.format("{ $and: [ {\"_id\":{$gt:ObjectId(\"%s\")}}", splitKey);
          filters.add(String.format("%s ]}", rangeFilter));
        } else {
          // we are between two splits
          rangeFilter =
              String.format(
                  "{ $and: [ {\"_id\":{$gt:ObjectId(\"%s\")," + "$lte:ObjectId(\"%s\")}}",
                  lowestBound, splitKey);
          filters.add(String.format("%s ]}", rangeFilter));
        }

        lowestBound = splitKey;
      }

      return filters;
    }

    /**
     * Transform a list of split keys as a list of filters containing corresponding range.
     *
     * <p>The list of split keys contains BSon Document basically containing for example:
     *
     * <ul>
     *   <li>_id: 56
     *   <li>_id: 109
     *   <li>_id: 256
     * </ul>
     *
     * <p>This method will generate a list of range filters performing the following splits:
     *
     * <ul>
     *   <li>from the beginning of the collection up to _id 56, so basically data with _id lower
     *       than 56
     *   <li>from _id 57 up to _id 109
     *   <li>from _id 110 up to _id 256
     *   <li>from _id 257 up to the end of the collection, so basically data with _id greater than
     *       257
     * </ul>
     *
     * @param splitKeys The list of split keys.
     * @return A list of filters containing the ranges.
     */
    @VisibleForTesting
    static List<BsonDocument> splitKeysToMatch(List<Document> splitKeys) {
      List<Bson> aggregates = new ArrayList<>();
      ObjectId lowestBound = null; // lower boundary (previous split in the iteration)
      for (int i = 0; i < splitKeys.size(); i++) {
        ObjectId splitKey = splitKeys.get(i).getObjectId("_id");
        if (i == 0) {
          aggregates.add(Aggregates.match(Filters.lte("_id", splitKey)));
          if (splitKeys.size() == 1) {
            aggregates.add(Aggregates.match(Filters.and(Filters.gt("_id", splitKey))));
          }
        } else if (i == splitKeys.size() - 1) {
          // this is the last split in the list, the filters define
          // the range from the previous split to the current split and also
          // the current split to the end
          aggregates.add(
              Aggregates.match(
                  Filters.and(Filters.gt("_id", lowestBound), Filters.lte("_id", splitKey))));
          aggregates.add(Aggregates.match(Filters.and(Filters.gt("_id", splitKey))));
        } else {
          aggregates.add(
              Aggregates.match(
                  Filters.and(Filters.gt("_id", lowestBound), Filters.lte("_id", splitKey))));
        }

        lowestBound = splitKey;
      }
      return aggregates.stream()
          .map(s -> s.toBsonDocument(BasicDBObject.class, MongoClient.getDefaultCodecRegistry()))
          .collect(Collectors.toList());
    }

    @VisibleForTesting
    static List<Document> buildAutoBuckets(MongoDatabase mongoDatabase, Read spec) {
      String collection = Preconditions.checkStateNotNull(spec.collection());
      List<Document> splitKeys = new ArrayList<>();
      MongoCollection<Document> mongoCollection = mongoDatabase.getCollection(collection);
      BsonDocument bucketAutoConfig = new BsonDocument();
      bucketAutoConfig.put("groupBy", new BsonString("$_id"));
      // 10 is the default number of buckets
      bucketAutoConfig.put("buckets", new BsonInt32(spec.numSplits() > 0 ? spec.numSplits() : 10));
      BsonDocument bucketAuto = new BsonDocument("$bucketAuto", bucketAutoConfig);
      List<BsonDocument> aggregates = new ArrayList<>();
      aggregates.add(bucketAuto);
      AggregateIterable<Document> buckets = mongoCollection.aggregate(aggregates);

      for (Document bucket : buckets) {
        Document filter = new Document();
        filter.put("_id", ((Document) bucket.get("_id")).get("min"));
        splitKeys.add(filter);
      }

      return splitKeys;
    }
  }

  private static class BoundedMongoDbReader extends BoundedSource.BoundedReader<Document> {
    private final BoundedMongoDbSource source;

    private @Nullable MongoClient client;
    private @Nullable MongoCursor<Document> cursor;
    private @Nullable Document current;

    BoundedMongoDbReader(BoundedMongoDbSource source) {
      this.source = source;
    }

    @Override
    public boolean start() {
      Read spec = source.spec;
      String database = Preconditions.checkStateNotNull(spec.database());
      String collection = Preconditions.checkStateNotNull(spec.collection());

      // MongoDB Connection preparation
      client = createClient(spec);
      MongoDatabase mongoDatabase = client.getDatabase(database);
      MongoCollection<Document> mongoCollection = mongoDatabase.getCollection(collection);
      cursor = spec.queryFn().apply(mongoCollection);
      return advance();
    }

    @Override
    public boolean advance() {
      Preconditions.checkStateNotNull(cursor);
      if (cursor.hasNext()) {
        current = cursor.next();
        return true;
      }
      return false;
    }

    @Override
    public BoundedMongoDbSource getCurrentSource() {
      return source;
    }

    @Override
    public Document getCurrent() {
      if (current == null) {
        throw new NoSuchElementException();
      }
      return current;
    }

    @Override
    public void close() {
      try {
        if (cursor != null) {
          cursor.close();
        }
      } catch (Exception e) {
        LOG.warn("Error closing MongoDB cursor", e);
      }
      try {
        if (client != null) {
          client.close();
        }
      } catch (Exception e) {
        LOG.warn("Error closing MongoDB client", e);
      }
    }

    private MongoClient createClient(Read spec) {
      String uri = Preconditions.checkStateNotNull(spec.uri(), "withUri() is required");
      return new MongoClient(
          new MongoClientURI(
              uri,
              getOptions(
                  spec.maxConnectionIdleTime(),
                  spec.sslEnabled(),
                  spec.sslInvalidHostNameAllowed(),
                  spec.ignoreSSLCertificate())));
    }
  }

  /** A {@link PTransform} to write to a MongoDB database. */
  @AutoValue
  public abstract static class Write extends PTransform<PCollection<Document>, PDone> {

    @Pure
    abstract @Nullable String uri();

    @Pure
    abstract int maxConnectionIdleTime();

    @Pure
    abstract boolean sslEnabled();

    @Pure
    abstract boolean sslInvalidHostNameAllowed();

    @Pure
    abstract boolean ignoreSSLCertificate();

    @Pure
    abstract boolean ordered();

    @Pure
    abstract @Nullable String database();

    @Pure
    abstract @Nullable String collection();

    @Pure
    abstract long batchSize();

    @Pure
    abstract @Nullable UpdateConfiguration updateConfiguration();

    @Pure
    abstract Builder builder();

    @AutoValue.Builder
    abstract static class Builder {
      abstract Builder setUri(String uri);

      abstract Builder setMaxConnectionIdleTime(int maxConnectionIdleTime);

      abstract Builder setSslEnabled(boolean value);

      abstract Builder setSslInvalidHostNameAllowed(boolean value);

      abstract Builder setIgnoreSSLCertificate(boolean value);

      abstract Builder setOrdered(boolean value);

      abstract Builder setDatabase(String database);

      abstract Builder setCollection(String collection);

      abstract Builder setBatchSize(long batchSize);

      abstract Builder setUpdateConfiguration(UpdateConfiguration updateConfiguration);

      abstract Write build();
    }

    /**
     * Define the location of the MongoDB instances using an URI. The URI describes the hosts to be
     * used and some options.
     *
     * <p>The format of the URI is:
     *
     * <pre>{@code
     * mongodb://[username:password@]host1[:port1],...[,hostN[:portN]]][/[database][?options]]
     * }</pre>
     *
     * <p>Where:
     *
     * <ul>
     *   <li>{@code mongodb://} is a required prefix to identify that this is a string in the
     *       standard connection format.
     *   <li>{@code username:password@} are optional. If given, the driver will attempt to login to
     *       a database after connecting to a database server. For some authentication mechanisms,
     *       only the username is specified and the password is not, in which case the ":" after the
     *       username is left off as well.
     *   <li>{@code host1} is the only required part of the URI. It identifies a server address to
     *       connect to.
     *   <li>{@code :portX} is optional and defaults to {@code :27017} if not provided.
     *   <li>{@code /database} is the name of the database to login to and thus is only relevant if
     *       the {@code username:password@} syntax is used. If not specified, the "admin" database
     *       will be used by default. It has to be equivalent with the database you specific with
     *       {@link Write#withDatabase(String)}.
     *   <li>{@code ?options} are connection options. Note that if {@code database} is absent there
     *       is still a {@code /} required between the last {@code host} and the {@code ?}
     *       introducing the options. Options are name=value pairs and the pairs are separated by
     *       "{@code &}". You can pass the {@code MaxConnectionIdleTime} connection option via
     *       {@link Write#withMaxConnectionIdleTime(int)}.
     * </ul>
     */
    public Write withUri(String uri) {
      checkArgument(uri != null, "uri can not be null");
      return builder().setUri(uri).build();
    }

    /** Sets the maximum idle time for a pooled connection. */
    public Write withMaxConnectionIdleTime(int maxConnectionIdleTime) {
      return builder().setMaxConnectionIdleTime(maxConnectionIdleTime).build();
    }

    /** Enable ssl for connection. */
    public Write withSSLEnabled(boolean sslEnabled) {
      return builder().setSslEnabled(sslEnabled).build();
    }

    /** Enable invalidHostNameAllowed for ssl for connection. */
    public Write withSSLInvalidHostNameAllowed(boolean invalidHostNameAllowed) {
      return builder().setSslInvalidHostNameAllowed(invalidHostNameAllowed).build();
    }

    /**
     * Enables ordered bulk insertion (default: true).
     *
     * @see <a href=
     *     "https://github.com/mongodb/specifications/blob/master/source/crud/crud.rst#basic">
     *     specification of MongoDb CRUD operations</a>
     */
    public Write withOrdered(boolean ordered) {
      return builder().setOrdered(ordered).build();
    }

    /** Enable ignoreSSLCertificate for ssl for connection (allow for self signed certificates). */
    public Write withIgnoreSSLCertificate(boolean ignoreSSLCertificate) {
      return builder().setIgnoreSSLCertificate(ignoreSSLCertificate).build();
    }

    /** Sets the database to use. */
    public Write withDatabase(String database) {
      checkArgument(database != null, "database can not be null");
      return builder().setDatabase(database).build();
    }

    /** Sets the collection where to write data in the database. */
    public Write withCollection(String collection) {
      checkArgument(collection != null, "collection can not be null");
      return builder().setCollection(collection).build();
    }

    /** Define the size of the batch to group write operations. */
    public Write withBatchSize(long batchSize) {
      checkArgument(batchSize >= 0, "Batch size must be >= 0, but was %s", batchSize);
      return builder().setBatchSize(batchSize).build();
    }

    public Write withUpdateConfiguration(UpdateConfiguration updateConfiguration) {
      return builder().setUpdateConfiguration(updateConfiguration).build();
    }

    @Override
    public PDone expand(PCollection<Document> input) {
      checkArgument(uri() != null, "withUri() is required");
      checkArgument(database() != null, "withDatabase() is required");
      checkArgument(collection() != null, "withCollection() is required");

      input.apply(ParDo.of(new WriteFn(this)));
      return PDone.in(input.getPipeline());
    }

    @Override
    public void populateDisplayData(DisplayData.Builder builder) {
      builder.add(DisplayData.item("uri", uri()));
      builder.add(DisplayData.item("maxConnectionIdleTime", maxConnectionIdleTime()));
      builder.add(DisplayData.item("sslEnable", sslEnabled()));
      builder.add(DisplayData.item("sslInvalidHostNameAllowed", sslInvalidHostNameAllowed()));
      builder.add(DisplayData.item("ignoreSSLCertificate", ignoreSSLCertificate()));
      builder.add(DisplayData.item("ordered", ordered()));
      builder.add(DisplayData.item("database", database()));
      builder.add(DisplayData.item("collection", collection()));
      builder.add(DisplayData.item("batchSize", batchSize()));
    }

    static class WriteFn extends DoFn<Document, Void> {
      private final Write spec;
      private transient @Nullable MongoClient client;
      private @Nullable List<Document> batch;

      WriteFn(Write spec) {
        this.spec = spec;
      }

      @Setup
      public void createMongoClient() {
        String uri = Preconditions.checkStateNotNull(spec.uri());
        client =
            new MongoClient(
                new MongoClientURI(
                    uri,
                    getOptions(
                        spec.maxConnectionIdleTime(),
                        spec.sslEnabled(),
                        spec.sslInvalidHostNameAllowed(),
                        spec.ignoreSSLCertificate())));
      }

      @StartBundle
      public void startBundle() {
        batch = new ArrayList<>();
      }

      @ProcessElement
      public void processElement(ProcessContext ctx) {
        List<Document> batch = Preconditions.checkStateNotNull(this.batch);
        // Need to copy the document because mongoCollection.insertMany() will mutate it
        // before inserting (will assign an id).

        batch.add(new Document(ctx.element()));
        if (batch.size() >= spec.batchSize()) {
          flush();
        }
      }

      @FinishBundle
      public void finishBundle() {
        flush();
      }

      private void flush() {
        List<Document> batch = Preconditions.checkStateNotNull(this.batch);
        if (batch.isEmpty()) {
          return;
        }
        MongoClient client = Preconditions.checkStateNotNull(this.client);
        String database = Preconditions.checkStateNotNull(spec.database());
        String collection = Preconditions.checkStateNotNull(spec.collection());

        MongoDatabase mongoDatabase = client.getDatabase(database);
        MongoCollection<Document> mongoCollection = mongoDatabase.getCollection(collection);
        if (spec.updateConfiguration() == null) {
          insertDocuments(mongoCollection);
        } else {
          updateDocuments(mongoCollection);
        }
        batch.clear();
      }

      private void insertDocuments(MongoCollection<Document> mongoCollection) {
        Preconditions.checkStateNotNull(batch);
        try {
          mongoCollection.insertMany(batch, new InsertManyOptions().ordered(spec.ordered()));
        } catch (MongoBulkWriteException e) {
          if (spec.ordered()) {
            throw e;
          }
        }
      }

      private void updateDocuments(MongoCollection<Document> mongoCollection) {
        List<Document> batch = Preconditions.checkStateNotNull(this.batch);
        UpdateConfiguration updateConfiguration =
            Preconditions.checkStateNotNull(spec.updateConfiguration());
        if (batch.isEmpty()) {
          return;
        }
        List<WriteModel<Document>> actions = new ArrayList<>();
        List<UpdateField> updateFields =
            (updateConfiguration.updateFields() != null)
                ? updateConfiguration.updateFields()
                : Collections.emptyList();
        Map<String, List<UpdateField>> operatorFieldsMap = getOperatorFieldsMap(updateFields);
        try {
          for (Document doc : batch) {
            Document updateDocument = new Document();
            for (Map.Entry<String, List<UpdateField>> entry : operatorFieldsMap.entrySet()) {
              Document updateSubDocument = new Document();
              for (UpdateField field : entry.getValue()) {
                String destField = Preconditions.checkStateNotNull(field.destField());
                updateSubDocument.append(
                    destField, field.sourceField() == null ? doc : doc.get(field.sourceField()));
              }
              updateDocument.append(entry.getKey(), updateSubDocument);
            }
            String findKey = Optional.ofNullable(updateConfiguration.findKey()).orElse("_id");
            String updateKey = Preconditions.checkStateNotNull(updateConfiguration.updateKey());
            Document findCriteria = new Document(findKey, doc.get(updateKey));
            UpdateOptions updateOptions =
                new UpdateOptions().upsert(updateConfiguration.isUpsert());
            actions.add(new UpdateOneModel<>(findCriteria, updateDocument, updateOptions));
          }
          mongoCollection.bulkWrite(actions, new BulkWriteOptions().ordered(spec.ordered()));
        } catch (MongoBulkWriteException e) {
          if (spec.ordered()) {
            throw e;
          }
        }
      }

      private static Map<String, List<UpdateField>> getOperatorFieldsMap(
          List<UpdateField> updateFields) {
        Map<String, List<UpdateField>> operatorFieldsMap = new HashMap<>();
        for (UpdateField field : updateFields) {
          String updateOperator = Preconditions.checkStateNotNull(field.updateOperator());
          if (operatorFieldsMap.containsKey(updateOperator)) {
            List<UpdateField> fields = operatorFieldsMap.get(updateOperator);
            fields.add(field);
            operatorFieldsMap.put(updateOperator, fields);
          } else {
            List<UpdateField> fields = new ArrayList<>();
            fields.add(field);
            operatorFieldsMap.put(updateOperator, fields);
          }
        }
        return operatorFieldsMap;
      }

      @Teardown
      public void closeMongoClient() {
        if (client != null) {
          client.close();
          client = null;
        }
      }
    }
  }
}
