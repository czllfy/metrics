package io.ultrabrew.metrics.data;

import io.ultrabrew.metrics.util.DistributionBucket;
import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sun.misc.Unsafe;

public class BasicHistogramAggregator extends ConcurrentIntTable implements Aggregator {

  private static final Logger LOGGER = LoggerFactory.getLogger(BasicHistogramAggregator.class);
  private static final String[] AGG_FIELDS = {"count", "sum", "min", "max"};
  private static final int[] IDENTITY = {0, 0, Integer.MAX_VALUE, Integer.MIN_VALUE};

  private final String metricId;
  private final DistributionBucket buckets;
  private final int bucketCount;

  public BasicHistogramAggregator(final String metricId, final DistributionBucket buckets) {
    this(metricId, buckets, DEFAULT_CAPACITY, DEFAULT_MAX_CAPACITY);
  }

  public BasicHistogramAggregator(final String metricId, final DistributionBucket buckets,
      final int capacity) {
    this(metricId, buckets, capacity, DEFAULT_MAX_CAPACITY);
  }

  public BasicHistogramAggregator(final String metricId, final DistributionBucket buckets,
      final int capacity, final int maxCapacity) {
    super(AGG_FIELDS.length + buckets.getCount(), capacity, maxCapacity);
    this.metricId = metricId;
    this.buckets = buckets;
    this.bucketCount = buckets.getCount();
  }

  @Override
  public void apply(String[] tags, long latency, long timestamp) {
    apply(tags, (int) latency, timestamp);
  }

  @Override
  protected void combine(int[] table, long baseOffset, int latency) {
    add(table, baseOffset, 0, 1);
    add(table, baseOffset, 1, latency);
    min(table, baseOffset, 2, latency);
    max(table, baseOffset, 3, latency);

    //Increments the bucket counter by 1 responding to the given latency
    int bucketIndex = buckets.getBucketIndex(latency);
    add(table, baseOffset, AGG_FIELDS.length + bucketIndex, 1);
  }

  @Override
  public Cursor cursor() {
    return newCursor(false);
  }

  @Override
  public Cursor sortedCursor() {
    return newCursor(true);
  }

  private Cursor newCursor(boolean sorted) {
    String[] fields = buildFields();
    Type[] types = buildTypes(fields.length);
    int[] identity = buildIdentity(fields.length);
    return new CursorImpl(tagSets, fields, types, identity, sorted);
  }

  private int[] buildIdentity(int length) {
    int[] identity = new int[length];
    System.arraycopy(IDENTITY, 0, identity, 0, IDENTITY.length);
    return identity;
  }

  private String[] buildFields() {
    String[] fields = new String[AGG_FIELDS.length + bucketCount];
    String[] buckets = this.buckets.getBucketNames();
    System.arraycopy(AGG_FIELDS, 0, fields, 0, AGG_FIELDS.length);
    System.arraycopy(buckets, 0, fields, AGG_FIELDS.length, buckets.length);
    return fields;
  }

  private Type[] buildTypes(final int length) {
    Type[] types = new Type[length];
    for (int i = 0; i < length; i++) {
      types[i] = Type.LONG;
    }
    return types;
  }

  private class CursorImpl implements Cursor {

    final private String[] fields;
    final private Type[] types;
    private int[] identity;
    final private String[][] tagSets;
    private int i = -1;
    private long base = 0;
    private int[] table;

    private CursorImpl(final String[][] tagSets, final String[] fields, final Type[] types,
        int[] identity, final boolean sorted) {
      this.fields = fields;
      this.types = types;
      this.identity = identity;
      if (sorted) {
        this.tagSets = tagSets.clone();
        Arrays.sort(this.tagSets, TagSetsHelper::compare);
      } else {
        this.tagSets = tagSets;
      }
    }

    @Override
    public String getMetricId() {
      return metricId;
    }

    @Override
    public boolean next() {
      i++;
      if (i >= tagSets.length || tagSets[i] == null) {
        return false;
      }

      long index = index(tagSets[i], true);

      if (NOT_FOUND == index) {
        LOGGER.error("Missing index on Read. Tags: {}. Concurrency error or bug",
            Arrays.asList(tagSets[i]));
        return false;
      }

      // Decode table index and slot index from long.
      // Upper 32 bits represent the table index and lower 32 bits represent the slot index.
      // This logic is replicated in multiple places for performance reasons.
      int tableIndex = (int) ((index & TABLE_MASK) >> 32);
      int slotIndex = (int) (index & SLOT_MASK);

      table = tables.get(tableIndex);
      base = Unsafe.ARRAY_INT_BASE_OFFSET + slotIndex * Unsafe.ARRAY_INT_INDEX_SCALE;
      return true;
    }

    @Override
    public String[] getTags() {
      if (i < 0 || i >= tagSets.length || tagSets[i] == null) {
        throw new IndexOutOfBoundsException("Not a valid row index: " + i);
      }
      return tagSets[i];
    }

    @Override
    public long lastUpdated() {
      if (i < 0 || i >= tagSets.length || tagSets[i] == null) {
        throw new IndexOutOfBoundsException("Not a valid row index: " + i);
      }
      return readTime(table, base);
    }

    @Override
    public long readLong(final int index) {
      if (i < 0 || i >= tagSets.length || tagSets[i] == null) {
        throw new IndexOutOfBoundsException("Not a valid row index: " + i);
      }
      if (index < 0 || index >= fields.length) {
        throw new IndexOutOfBoundsException("Not a valid field index: " + index);
      }
      return read(table, base, index);
    }

    @Override
    public double readDouble(final int index) {
      throw new UnsupportedOperationException("Invalid operation");
    }

    @Override
    public long readAndResetLong(final int index) {
      if (i < 0 || i >= tagSets.length || tagSets[i] == null) {
        throw new IndexOutOfBoundsException("Not a valid row index: " + i);
      }
      if (index < 0 || index >= fields.length) {
        throw new IndexOutOfBoundsException("Not a valid field index: " + index);
      }
      return readAndReset(table, base, index, identity[index]);
    }

    @Override
    public double readAndResetDouble(final int index) {
      throw new UnsupportedOperationException("Invalid operation");
    }

    @Override
    public String[] getFields() {
      return fields;
    }

    @Override
    public Type[] getTypes() {
      return types;
    }
  }
}
