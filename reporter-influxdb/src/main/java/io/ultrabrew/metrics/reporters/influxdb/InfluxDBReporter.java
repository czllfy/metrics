// Copyright 2018, Oath Inc.
// Licensed under the terms of the Apache License 2.0 license. See LICENSE file in Ultrabrew Metrics
// for terms.

package io.ultrabrew.metrics.reporters.influxdb;

import io.ultrabrew.metrics.Metric;
import io.ultrabrew.metrics.data.Aggregator;
import io.ultrabrew.metrics.data.Cursor;
import io.ultrabrew.metrics.data.CursorEntry;
import io.ultrabrew.metrics.data.Type;
import io.ultrabrew.metrics.reporters.TimeWindowReporter;
import io.ultrabrew.metrics.util.Strings;
import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An aggregating reporter that periodically stores data to InfluxDB.
 */
public class InfluxDBReporter extends TimeWindowReporter {

  private static final Logger LOGGER = LoggerFactory.getLogger(InfluxDBReporter.class);

  private final InfluxDBClient dbClient;
  private long lastReportedTimestamp = 0;

  private InfluxDBReporter(final URI dbUri, final int windowSeconds, final int bufferSize,
      final Map<Class<? extends Metric>, Function<Metric, ? extends Aggregator>> defaultAggregators,
      final Map<String, Function<Metric, ? extends Aggregator>> metricAggregators) {

    super(dbUri.toString(), windowSeconds, defaultAggregators, metricAggregators);
    this.dbClient = new InfluxDBClient(dbUri, bufferSize);
    this.start();
  }

  /**
   * Create a fluent builder for constructing {@link InfluxDBReporter} instances.
   */
  public static Builder builder() {
    return new Builder();
  }

  @Override
  protected void doReport(Map<String, Aggregator> aggregators) {
    long newestTimestamp = 0;
    try {
      for (final Map.Entry<String, Aggregator> entry : aggregators.entrySet()) {
        final Aggregator aggregator = entry.getValue();
        final Cursor cursor = aggregator.cursor();
        final String metricName = entry.getKey();
        while (cursor.next()) {
          if (cursor.lastUpdated() > lastReportedTimestamp) {
            dbClient.write(metricName, cursor.getTags(), buildFields(cursor), -1);
            newestTimestamp = Math.max(newestTimestamp, cursor.lastUpdated());
          }
        }
      }
      dbClient.flush();
    } catch (IOException e) {
      LOGGER.error("Failed to send data", e);
    }
    if (newestTimestamp > 0) {
      lastReportedTimestamp = newestTimestamp;
    }
  }

  private String[] buildFields(CursorEntry cursor) {
    final String[] fields = cursor.getFields();
    final Type[] types = cursor.getTypes();

    String[] result = new String[fields.length * 2];
    for (int i = 0; i < fields.length; i++) {
      result[i * 2] = fields[i];
      result[i * 2 + 1] = types[i].readAndReset(cursor, i);
    }
    return result;
  }

  public static class Builder extends TimeWindowReporterBuilder<Builder, InfluxDBReporter> {

    private URI baseUri = null;
    private String endpoint = null;
    private String database = null;
    private int windowSeconds = 1;
    private int bufferSize = 64 * 1024;

    private Builder() {
    }

    /**
     * Set the base URI of the InfluxDB installation, for example "http://localhost:8086".
     *
     * @param baseUri base URI of the InfluxDB
     */
    public Builder withBaseUri(final URI baseUri) {
      this.baseUri = baseUri;
      return this;
    }
    
    /**
     * An optional endpoint to build the URI that, when provided, overrides the 
     * default `/write?db=$database`. You must supply the full URI string, e.g.
     * `/api/put/influx/write?db=mydatabase`. 
     * @param endpoint An optional non-null endpoint to apply to the base URI.
     * @return The builder.
     */
    public Builder withEndpoint(final String endpoint) {
      this.endpoint = endpoint;
      return this;
    }

    /**
     * Set the database name measurements are to be written to.
     *
     * @param database database name
     */
    public Builder withDatabase(final String database) {
      this.database = database;
      return this;
    }

    /**
     * Set the reporting time window size.
     *
     * @param windowSeconds reporting time window in seconds
     */
    public Builder withWindowSize(int windowSeconds) {
      this.windowSeconds = windowSeconds;
      return this;
    }

    /**
     * Set size of the buffer into which measurements are collected for sending to InfluxDB.
     *
     * @param bufferSize size of buffer in bytes
     */
    public Builder withBufferSize(int bufferSize) {
      this.bufferSize = bufferSize;
      return this;
    }

    /**
     * Create an {@link InfluxDBReporter} instance.
     */
    @Override
    public InfluxDBReporter build() {
      if (baseUri == null) {
        throw new IllegalArgumentException("Invalid baseUri");
      }
      if (Strings.isNullOrEmpty(database) && 
          Strings.isNullOrEmpty(endpoint)) {
        throw new IllegalArgumentException("Invalid database");
      }
      if (!Strings.isNullOrEmpty(endpoint)) {
        return new InfluxDBReporter(baseUri.resolve(endpoint), windowSeconds,
            bufferSize, defaultAggregators, metricAggregators);
      }
      return new InfluxDBReporter(baseUri.resolve("/write?db=" + database), windowSeconds,
          bufferSize, defaultAggregators, metricAggregators);
    }
  }
}
