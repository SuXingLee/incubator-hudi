/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hudi.utilities.deltastreamer;

import org.apache.hudi.AvroConversionUtils;
import org.apache.hudi.DataSourceUtils;
import org.apache.hudi.client.HoodieWriteClient;
import org.apache.hudi.client.WriteStatus;
import org.apache.hudi.common.config.TypedProperties;
import org.apache.hudi.common.model.HoodieCommitMetadata;
import org.apache.hudi.common.model.HoodieRecord;
import org.apache.hudi.common.model.HoodieRecordPayload;
import org.apache.hudi.common.table.HoodieTableMetaClient;
import org.apache.hudi.common.table.timeline.HoodieInstant;
import org.apache.hudi.common.table.timeline.HoodieTimeline;
import org.apache.hudi.common.util.Option;
import org.apache.hudi.common.util.ValidationUtils;
import org.apache.hudi.common.util.collection.Pair;
import org.apache.hudi.config.HoodieCompactionConfig;
import org.apache.hudi.config.HoodieIndexConfig;
import org.apache.hudi.config.HoodieWriteConfig;
import org.apache.hudi.exception.HoodieException;
import org.apache.hudi.hive.HiveSyncConfig;
import org.apache.hudi.hive.HiveSyncTool;
import org.apache.hudi.index.HoodieIndex;
import org.apache.hudi.keygen.KeyGenerator;
import org.apache.hudi.utilities.UtilHelpers;
import org.apache.hudi.utilities.deltastreamer.HoodieDeltaStreamer.Operation;
import org.apache.hudi.utilities.exception.HoodieDeltaStreamerException;
import org.apache.hudi.utilities.schema.RowBasedSchemaProvider;
import org.apache.hudi.utilities.schema.SchemaProvider;
import org.apache.hudi.utilities.sources.InputBatch;
import org.apache.hudi.utilities.transform.Transformer;

import com.codahale.metrics.Timer;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import scala.collection.JavaConversions;

import static org.apache.hudi.utilities.schema.RowBasedSchemaProvider.HOODIE_RECORD_NAMESPACE;
import static org.apache.hudi.utilities.schema.RowBasedSchemaProvider.HOODIE_RECORD_STRUCT_NAME;

/**
 * Sync's one batch of data to hoodie table.
 */
public class DeltaSync implements Serializable {

  private static final long serialVersionUID = 1L;
  private static final Logger LOG = LogManager.getLogger(DeltaSync.class);
  public static final String CHECKPOINT_KEY = "deltastreamer.checkpoint.key";
  public static final String CHECKPOINT_RESET_KEY = "deltastreamer.checkpoint.reset_key";

  /**
   * Delta Sync Config.
   */
  private final HoodieDeltaStreamer.Config cfg;

  /**
   * Source to pull deltas from.
   */
  private transient SourceFormatAdapter formatAdapter;

  /**
   * Schema provider that supplies the command for reading the input and writing out the target table.
   */
  private transient SchemaProvider schemaProvider;

  /**
   * Allows transforming source to target table before writing.
   */
  private transient Option<Transformer> transformer;

  /**
   * Extract the key for the target table.
   */
  private KeyGenerator keyGenerator;

  /**
   * Filesystem used.
   */
  private transient FileSystem fs;

  /**
   * Spark context.
   */
  private transient JavaSparkContext jssc;

  /**
   * Spark Session.
   */
  private transient SparkSession sparkSession;

  /**
   * Hive Config.
   */
  private transient HiveConf hiveConf;

  /**
   * Bag of properties with source, hoodie client, key generator etc.
   */
  private final TypedProperties props;

  /**
   * Callback when write client is instantiated.
   */
  private transient Function<HoodieWriteClient, Boolean> onInitializingHoodieWriteClient;

  /**
   * Timeline with completed commits.
   */
  private transient Option<HoodieTimeline> commitTimelineOpt;

  /**
   * Write Client.
   */
  private transient HoodieWriteClient writeClient;

  public DeltaSync(HoodieDeltaStreamer.Config cfg, SparkSession sparkSession, SchemaProvider schemaProvider,
                   TypedProperties props, JavaSparkContext jssc, FileSystem fs, HiveConf hiveConf,
                   Function<HoodieWriteClient, Boolean> onInitializingHoodieWriteClient) throws IOException {

    this.cfg = cfg;
    this.jssc = jssc;
    this.sparkSession = sparkSession;
    this.fs = fs;
    this.onInitializingHoodieWriteClient = onInitializingHoodieWriteClient;
    this.props = props;
    this.schemaProvider = schemaProvider;

    refreshTimeline();

    this.transformer = UtilHelpers.createTransformer(cfg.transformerClassNames);
    this.keyGenerator = DataSourceUtils.createKeyGenerator(props);

    this.formatAdapter = new SourceFormatAdapter(
        UtilHelpers.createSource(cfg.sourceClassName, props, jssc, sparkSession, schemaProvider));

    this.hiveConf = hiveConf;
    if (cfg.filterDupes) {
      cfg.operation = cfg.operation == Operation.UPSERT ? Operation.INSERT : cfg.operation;
    }

    // If schemaRegistry already resolved, setup write-client
    setupWriteClient();
  }

  /**
   * Refresh Timeline.
   */
  private void refreshTimeline() throws IOException {
    if (fs.exists(new Path(cfg.targetBasePath))) {
      HoodieTableMetaClient meta = new HoodieTableMetaClient(new Configuration(fs.getConf()), cfg.targetBasePath,
          cfg.payloadClassName);
      switch (meta.getTableType()) {
        case COPY_ON_WRITE:
          this.commitTimelineOpt = Option.of(meta.getActiveTimeline().getCommitTimeline().filterCompletedInstants());
          break;
        case MERGE_ON_READ:
          this.commitTimelineOpt = Option.of(meta.getActiveTimeline().getDeltaCommitTimeline().filterCompletedInstants());
          break;
        default:
          throw new HoodieException("Unsupported table type :" + meta.getTableType());
      }
    } else {
      this.commitTimelineOpt = Option.empty();
      HoodieTableMetaClient.initTableType(new Configuration(jssc.hadoopConfiguration()), cfg.targetBasePath,
          cfg.tableType, cfg.targetTableName, "archived", cfg.payloadClassName);
    }
  }

  /**
   * Run one round of delta sync and return new compaction instant if one got scheduled.
   */
  public Option<String> syncOnce() throws Exception {
    Option<String> scheduledCompaction = Option.empty();
    HoodieDeltaStreamerMetrics metrics = new HoodieDeltaStreamerMetrics(getHoodieClientConfig(schemaProvider));
    Timer.Context overallTimerContext = metrics.getOverallTimerContext();

    // Refresh Timeline
    refreshTimeline();

    Pair<SchemaProvider, Pair<String, JavaRDD<HoodieRecord>>> srcRecordsWithCkpt = readFromSource(commitTimelineOpt);

    if (null != srcRecordsWithCkpt) {
      // this is the first input batch. If schemaProvider not set, use it and register Avro Schema and start
      // compactor
      if (null == schemaProvider) {
        // Set the schemaProvider if not user-provided
        this.schemaProvider = srcRecordsWithCkpt.getKey();
        // Setup HoodieWriteClient and compaction now that we decided on schema
        setupWriteClient();
      }

      scheduledCompaction = writeToSink(srcRecordsWithCkpt.getRight().getRight(),
          srcRecordsWithCkpt.getRight().getLeft(), metrics, overallTimerContext);
    }

    // Clear persistent RDDs
    jssc.getPersistentRDDs().values().forEach(JavaRDD::unpersist);
    return scheduledCompaction;
  }

  /**
   * Read from Upstream Source and apply transformation if needed.
   */
  private Pair<SchemaProvider, Pair<String, JavaRDD<HoodieRecord>>> readFromSource(
      Option<HoodieTimeline> commitTimelineOpt) throws Exception {
    // Retrieve the previous round checkpoints, if any
    Option<String> resumeCheckpointStr = Option.empty();
    if (commitTimelineOpt.isPresent()) {
      Option<HoodieInstant> lastCommit = commitTimelineOpt.get().lastInstant();
      if (lastCommit.isPresent()) {
        HoodieCommitMetadata commitMetadata = HoodieCommitMetadata
            .fromBytes(commitTimelineOpt.get().getInstantDetails(lastCommit.get()).get(), HoodieCommitMetadata.class);
        if (cfg.checkpoint != null && !cfg.checkpoint.equals(commitMetadata.getMetadata(CHECKPOINT_RESET_KEY))) {
          resumeCheckpointStr = Option.of(cfg.checkpoint);
        } else if (commitMetadata.getMetadata(CHECKPOINT_KEY) != null) {
          resumeCheckpointStr = Option.of(commitMetadata.getMetadata(CHECKPOINT_KEY));
        } else {
          throw new HoodieDeltaStreamerException(
              "Unable to find previous checkpoint. Please double check if this table "
                  + "was indeed built via delta streamer. Last Commit :" + lastCommit + ", Instants :"
                  + commitTimelineOpt.get().getInstants().collect(Collectors.toList()) + ", CommitMetadata="
                  + commitMetadata.toJsonString());
        }
      }
    } else {
      HoodieTableMetaClient.initTableType(new Configuration(jssc.hadoopConfiguration()), cfg.targetBasePath,
          cfg.tableType, cfg.targetTableName, "archived", cfg.payloadClassName);
    }

    if (!resumeCheckpointStr.isPresent() && cfg.checkpoint != null) {
      resumeCheckpointStr = Option.of(cfg.checkpoint);
    }
    LOG.info("Checkpoint to resume from : " + resumeCheckpointStr);

    final Option<JavaRDD<GenericRecord>> avroRDDOptional;
    final String checkpointStr;
    final SchemaProvider schemaProvider;
    if (transformer.isPresent()) {
      // Transformation is needed. Fetch New rows in Row Format, apply transformation and then convert them
      // to generic records for writing
      InputBatch<Dataset<Row>> dataAndCheckpoint =
          formatAdapter.fetchNewDataInRowFormat(resumeCheckpointStr, cfg.sourceLimit);

      Option<Dataset<Row>> transformed =
          dataAndCheckpoint.getBatch().map(data -> transformer.get().apply(jssc, sparkSession, data, props));
      checkpointStr = dataAndCheckpoint.getCheckpointForNextBatch();
      if (this.schemaProvider != null && this.schemaProvider.getTargetSchema() != null) {
        // If the target schema is specified through Avro schema,
        // pass in the schema for the Row-to-Avro conversion
        // to avoid nullability mismatch between Avro schema and Row schema
        avroRDDOptional = transformed
            .map(t -> AvroConversionUtils.createRdd(
                t, this.schemaProvider.getTargetSchema(),
                HOODIE_RECORD_STRUCT_NAME, HOODIE_RECORD_NAMESPACE).toJavaRDD());
      } else {
        avroRDDOptional = transformed
            .map(t -> AvroConversionUtils.createRdd(
                t, HOODIE_RECORD_STRUCT_NAME, HOODIE_RECORD_NAMESPACE).toJavaRDD());
      }

      // Use Transformed Row's schema if not overridden. If target schema is not specified
      // default to RowBasedSchemaProvider
      schemaProvider = this.schemaProvider == null || this.schemaProvider.getTargetSchema() == null
          ? transformed.map(r -> (SchemaProvider) new RowBasedSchemaProvider(r.schema())).orElse(
          dataAndCheckpoint.getSchemaProvider())
          : this.schemaProvider;
    } else {
      // Pull the data from the source & prepare the write
      InputBatch<JavaRDD<GenericRecord>> dataAndCheckpoint =
          formatAdapter.fetchNewDataInAvroFormat(resumeCheckpointStr, cfg.sourceLimit);
      avroRDDOptional = dataAndCheckpoint.getBatch();
      checkpointStr = dataAndCheckpoint.getCheckpointForNextBatch();
      schemaProvider = dataAndCheckpoint.getSchemaProvider();
    }

    if (Objects.equals(checkpointStr, resumeCheckpointStr.orElse(null))) {
      LOG.info("No new data, source checkpoint has not changed. Nothing to commit. Old checkpoint=("
          + resumeCheckpointStr + "). New Checkpoint=(" + checkpointStr + ")");
      return null;
    }

    if ((!avroRDDOptional.isPresent()) || (avroRDDOptional.get().isEmpty())) {
      LOG.info("No new data, perform empty commit.");
      return Pair.of(schemaProvider, Pair.of(checkpointStr, jssc.emptyRDD()));
    }

    JavaRDD<GenericRecord> avroRDD = avroRDDOptional.get();
    JavaRDD<HoodieRecord> records = avroRDD.map(gr -> {
      HoodieRecordPayload payload = DataSourceUtils.createPayload(cfg.payloadClassName, gr,
          (Comparable) DataSourceUtils.getNestedFieldVal(gr, cfg.sourceOrderingField, false));
      return new HoodieRecord<>(keyGenerator.getKey(gr), payload);
    });

    return Pair.of(schemaProvider, Pair.of(checkpointStr, records));
  }

  /**
   * Perform Hoodie Write. Run Cleaner, schedule compaction and syncs to hive if needed.
   *
   * @param records             Input Records
   * @param checkpointStr       Checkpoint String
   * @param metrics             Metrics
   * @param overallTimerContext Timer Context
   * @return Option Compaction instant if one is scheduled
   */
  private Option<String> writeToSink(JavaRDD<HoodieRecord> records, String checkpointStr,
                                     HoodieDeltaStreamerMetrics metrics, Timer.Context overallTimerContext) throws Exception {

    Option<String> scheduledCompactionInstant = Option.empty();
    // filter dupes if needed
    if (cfg.filterDupes) {
      // turn upserts to insert
      cfg.operation = cfg.operation == Operation.UPSERT ? Operation.INSERT : cfg.operation;
      records = DataSourceUtils.dropDuplicates(jssc, records, writeClient.getConfig());
    }

    boolean isEmpty = records.isEmpty();

    String instantTime = startCommit();
    LOG.info("Starting commit  : " + instantTime);

    JavaRDD<WriteStatus> writeStatusRDD;
    if (cfg.operation == Operation.INSERT) {
      writeStatusRDD = writeClient.insert(records, instantTime);
    } else if (cfg.operation == Operation.UPSERT) {
      writeStatusRDD = writeClient.upsert(records, instantTime);
    } else if (cfg.operation == Operation.BULK_INSERT) {
      writeStatusRDD = writeClient.bulkInsert(records, instantTime);
    } else {
      throw new HoodieDeltaStreamerException("Unknown operation :" + cfg.operation);
    }

    long totalErrorRecords = writeStatusRDD.mapToDouble(WriteStatus::getTotalErrorRecords).sum().longValue();
    long totalRecords = writeStatusRDD.mapToDouble(WriteStatus::getTotalRecords).sum().longValue();
    boolean hasErrors = totalErrorRecords > 0;
    long hiveSyncTimeMs = 0;
    if (!hasErrors || cfg.commitOnErrors) {
      HashMap<String, String> checkpointCommitMetadata = new HashMap<>();
      checkpointCommitMetadata.put(CHECKPOINT_KEY, checkpointStr);
      if (cfg.checkpoint != null) {
        checkpointCommitMetadata.put(CHECKPOINT_RESET_KEY, cfg.checkpoint);
      }

      if (hasErrors) {
        LOG.warn("Some records failed to be merged but forcing commit since commitOnErrors set. Errors/Total="
            + totalErrorRecords + "/" + totalRecords);
      }

      boolean success = writeClient.commit(instantTime, writeStatusRDD, Option.of(checkpointCommitMetadata));
      if (success) {
        LOG.info("Commit " + instantTime + " successful!");

        // Schedule compaction if needed
        if (cfg.isAsyncCompactionEnabled()) {
          scheduledCompactionInstant = writeClient.scheduleCompaction(Option.empty());
        }

        if (!isEmpty) {
          // Sync to hive if enabled
          Timer.Context hiveSyncContext = metrics.getHiveSyncTimerContext();
          syncHive();
          hiveSyncTimeMs = hiveSyncContext != null ? hiveSyncContext.stop() : 0;
        }
      } else {
        LOG.info("Commit " + instantTime + " failed!");
        throw new HoodieException("Commit " + instantTime + " failed!");
      }
    } else {
      LOG.error("Delta Sync found errors when writing. Errors/Total=" + totalErrorRecords + "/" + totalRecords);
      LOG.error("Printing out the top 100 errors");
      writeStatusRDD.filter(WriteStatus::hasErrors).take(100).forEach(ws -> {
        LOG.error("Global error :", ws.getGlobalError());
        if (ws.getErrors().size() > 0) {
          ws.getErrors().forEach((key, value) -> LOG.trace("Error for key:" + key + " is " + value));
        }
      });
      // Rolling back instant
      writeClient.rollback(instantTime);
      throw new HoodieException("Commit " + instantTime + " failed and rolled-back !");
    }
    long overallTimeMs = overallTimerContext != null ? overallTimerContext.stop() : 0;

    // Send DeltaStreamer Metrics
    metrics.updateDeltaStreamerMetrics(overallTimeMs, hiveSyncTimeMs);

    return scheduledCompactionInstant;
  }

  private String startCommit() {
    final int maxRetries = 2;
    int retryNum = 1;
    RuntimeException lastException = null;
    while (retryNum <= maxRetries) {
      try {
        return writeClient.startCommit();
      } catch (IllegalArgumentException ie) {
        lastException = ie;
        LOG.error("Got error trying to start a new commit. Retrying after sleeping for a sec", ie);
        retryNum++;
        try {
          Thread.sleep(1000);
        } catch (InterruptedException e) {
          // No-Op
        }
      }
    }
    throw lastException;
  }

  /**
   * Sync to Hive.
   */
  private void syncHive() {
    if (cfg.enableHiveSync) {
      HiveSyncConfig hiveSyncConfig = DataSourceUtils.buildHiveSyncConfig(props, cfg.targetBasePath);
      LOG.info("Syncing target hoodie table with hive table(" + hiveSyncConfig.tableName + "). Hive metastore URL :"
          + hiveSyncConfig.jdbcUrl + ", basePath :" + cfg.targetBasePath);

      new HiveSyncTool(hiveSyncConfig, hiveConf, fs).syncHoodieTable();
    }
  }

  /**
   * Note that depending on configs and source-type, schemaProvider could either be eagerly or lazily created.
   * SchemaProvider creation is a precursor to HoodieWriteClient and AsyncCompactor creation. This method takes care of
   * this constraint.
   */
  public void setupWriteClient() {
    LOG.info("Setting up Hoodie Write Client");
    if ((null != schemaProvider) && (null == writeClient)) {
      registerAvroSchemas(schemaProvider);
      HoodieWriteConfig hoodieCfg = getHoodieClientConfig(schemaProvider);
      writeClient = new HoodieWriteClient<>(jssc, hoodieCfg, true);
      onInitializingHoodieWriteClient.apply(writeClient);
    }
  }

  /**
   * Helper to construct Write Client config.
   *
   * @param schemaProvider Schema Provider
   */
  private HoodieWriteConfig getHoodieClientConfig(SchemaProvider schemaProvider) {
    HoodieWriteConfig.Builder builder =
        HoodieWriteConfig.newBuilder().withPath(cfg.targetBasePath).combineInput(cfg.filterDupes, true)
            .withCompactionConfig(HoodieCompactionConfig.newBuilder().withPayloadClass(cfg.payloadClassName)
                // Inline compaction is disabled for continuous mode. otherwise enabled for MOR
                .withInlineCompaction(cfg.isInlineCompactionEnabled()).build())
            .forTable(cfg.targetTableName)
            .withIndexConfig(HoodieIndexConfig.newBuilder().withIndexType(HoodieIndex.IndexType.BLOOM).build())
            .withAutoCommit(false).withProps(props);

    if (null != schemaProvider && null != schemaProvider.getTargetSchema()) {
      builder = builder.withSchema(schemaProvider.getTargetSchema().toString());
    }
    HoodieWriteConfig config = builder.build();

    // Validate what deltastreamer assumes of write-config to be really safe
    ValidationUtils.checkArgument(config.isInlineCompaction() == cfg.isInlineCompactionEnabled());
    ValidationUtils.checkArgument(!config.shouldAutoCommit());
    ValidationUtils.checkArgument(config.shouldCombineBeforeInsert() == cfg.filterDupes);
    ValidationUtils.checkArgument(config.shouldCombineBeforeUpsert());

    return config;
  }

  /**
   * Register Avro Schemas.
   *
   * @param schemaProvider Schema Provider
   */
  private void registerAvroSchemas(SchemaProvider schemaProvider) {
    // register the schemas, so that shuffle does not serialize the full schemas
    if (null != schemaProvider) {
      List<Schema> schemas = new ArrayList<>();
      schemas.add(schemaProvider.getSourceSchema());
      if (schemaProvider.getTargetSchema() != null) {
        schemas.add(schemaProvider.getTargetSchema());
      }

      LOG.info("Registering Schema :" + schemas);
      jssc.sc().getConf().registerAvroSchemas(JavaConversions.asScalaBuffer(schemas).toList());
    }
  }

  /**
   * Close all resources.
   */
  public void close() {
    if (null != writeClient) {
      writeClient.close();
      writeClient = null;
    }
  }
}
