/*
 Licensed to the Apache Software Foundation (ASF) under one
 or more contributor license agreements.  See the NOTICE file
 distributed with this work for additional information
 regarding copyright ownership.  The ASF licenses this file
 to you under the Apache License, Version 2.0 (the
 "License"); you may not use this file except in compliance
 with the License.  You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */

package me.tango.cassandra.bench;

import java.nio.charset.Charset;
import java.util.List;


import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.io.CharStreams;
import com.google.common.io.Files;
import org.apache.cassandra.db.*;
import org.apache.cassandra.db.composites.CellName;
import org.apache.cassandra.db.composites.CellNames;
import org.apache.cassandra.db.filter.QueryFilter;
import org.apache.cassandra.exceptions.ConfigurationException;
import org.apache.cassandra.service.StorageService;
import org.apache.cassandra.utils.ByteBufferUtil;


import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Date;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;


public class CassandraBenchmark {
  private final boolean useExisting;
  private final Integer writeBufferSize;
  private final String databaseDir;
  private final double compressionRatio;
  private long startTime;

  enum Order {
    SEQUENTIAL,
    RANDOM
  }

  enum DBState {
    FRESH,
    EXISTING
  }

  //    Cache cache_;
  private final List<String> benchmarks;
  private Keyspace db;
  private final int num;
  private int reads;
  private final int valueSize;
  private int heapCounter;
  private double lastOpFinish;
  private long bytes;
  private String message;
  private String postMessage;
  //    private Histogram hist_;
  private final RandomGenerator generator;
  private final Random random;

  // State kept for progress messages
  private int done;
  private int nextReport;     // When to report next


  public CassandraBenchmark(Map<Flag, Object> flags)
      throws Exception {

    ClassLoader cl = CassandraBenchmark.class.getClassLoader();
    benchmarks = (List<String>) flags.get(Flag.benchmarks);
    num = (Integer) flags.get(Flag.num);
    reads = (Integer) (flags.get(Flag.reads) == null ? flags.get(Flag.num) : flags.get(Flag.reads));
    valueSize = (Integer) flags.get(Flag.value_size);
    writeBufferSize = (Integer) flags.get(Flag.write_buffer_size);
    compressionRatio = (Double) flags.get(Flag.compression_ratio);
    useExisting = (Boolean) flags.get(Flag.use_existing_db);
    heapCounter = 0;
    bytes = 0;
    random = new Random(301);

    databaseDir = (String) flags.get(Flag.db);

    if (!useExisting) {
      destroyDb();
    }

    generator = new RandomGenerator(compressionRatio);
  }

  private void run()
      throws IOException, ConfigurationException {
    printHeader();
    SchemaLoader.loadSchema();
    open();

    for (String benchmark : benchmarks) {
      start();

      boolean known = true;

      if (benchmark.equals("fillseq")) {
        write(Order.SEQUENTIAL, DBState.FRESH, num, valueSize, 1);
      } else if (benchmark.equals("fillbatch")) {
        write(Order.SEQUENTIAL, DBState.FRESH, num, valueSize, 1000);
      } else if (benchmark.equals("fillrandom")) {
        write(Order.RANDOM, DBState.FRESH, num, valueSize, 1);
      } else if (benchmark.equals("overwrite")) {
        write(Order.RANDOM, DBState.EXISTING, num, valueSize, 1);
      } else if (benchmark.equals("fillsync")) {
        write(Order.RANDOM, DBState.FRESH, num / 1000, valueSize, 1);
      } else if (benchmark.equals("fill100K")) {
        write(Order.RANDOM, DBState.FRESH, num / 1000, 100 * 1000, 1);
      } else if (benchmark.equals("readseq")) {
        readSequential();
      } else if (benchmark.equals("readreverse")) {
        readReverse();
      } else if (benchmark.equals("readrandom")) {
        readRandom();
//      } else if (benchmark.equals("readhot")) {
//        readHot();
//      } else if (benchmark.equals("readrandomsmall")) {
//        int n = reads;
//        reads /= 1000;
//        readRandom();
//        reads = n;
//      } else if (benchmark.equals("compact")) {
//        compact();
//      } else if (benchmark.equals("crc32c")) {
//        crc32c(4096, "(4k per op)");
//      } else if (benchmark.equals("acquireload")) {
//        acquireLoad();
//      } else if (benchmark.equals("snappycomp")) {
//        if (Snappy.available()) {
//          snappyCompress();
//        }
//      } else if (benchmark.equals("snappyuncomp")) {
//        if (Snappy.available()) {
//          snappyUncompressDirectBuffer();
//        }
//      } else if (benchmark.equals("unsnap-array")) {
//        if (Snappy.available()) {
//          snappyUncompressArray();
//        }
//      } else if (benchmark.equals("unsnap-direct")) {
//        if (Snappy.available()) {
//          snappyUncompressDirectBuffer();
//        }
      } else if (benchmark.equals("heapprofile")) {
        heapProfile();
      } else if (benchmark.equals("stats")) {
        printStats();
      } else {
        known = false;
        System.err.println("Unknown benchmark: " + benchmark);
      }
      if (known) {
        stop(benchmark);
      }
    }

    SchemaLoader.stopGossiper();
  }

  private void printHeader()
      throws IOException {
    int kKeySize = 16;
    printEnvironment();
    System.out.printf("Keys:       %d bytes each\n", kKeySize);
    System.out.printf("Values:     %d bytes each (%d bytes after compression)\n",
        valueSize,
        (int) (valueSize * compressionRatio + 0.5));
    System.out.printf("Entries:    %d\n", num);
    System.out.printf("RawSize:    %.1f MB (estimated)\n",
        ((kKeySize + valueSize) * num) / 1048576.0);
    System.out.printf("FileSize:   %.1f MB (estimated)\n",
        (((kKeySize + valueSize * compressionRatio) * num)
            / 1048576.0));
    printWarnings();
    System.out.printf("------------------------------------------------\n");
  }

  static void printWarnings() {
    boolean assertsEnabled = true;
    assert assertsEnabled; // Intentional side effect!!!
    if (assertsEnabled) {
      System.out.printf("WARNING: Assertions are enabled; benchmarks unnecessarily slow\n");
    }

    // See if snappy is working by attempting to compress a compressible string
//    String text = "yyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy";
//    byte[] compressedText = null;
//    try {
//      compressedText = Snappy.compress(text);
//    } catch (Exception ignored) {
//    }
//    if (compressedText == null) {
//      System.out.printf("WARNING: Snappy compression is not enabled\n");
//    } else if (compressedText.length > text.length()) {
//      System.out.printf("WARNING: Snappy compression is not effective\n");
//    }
  }

  void printEnvironment()
      throws IOException {
    System.out.printf("Cassandra:    %s\n", databaseDir);

    System.out.printf("Date:       %tc\n", new Date());

    File cpuInfo = new File("/proc/cpuinfo");
    if (cpuInfo.canRead()) {
      int numberOfCpus = 0;
      String cpuType = null;
      String cacheSize = null;
      for (String line : CharStreams.readLines(Files.newReader(cpuInfo, Charset.forName("utf-8")))) {
        ImmutableList<String> parts = ImmutableList.copyOf(Splitter.on(':').omitEmptyStrings().trimResults().limit(2).split(line));
        if (parts.size() != 2) {
          continue;
        }
        String key = parts.get(0);
        String value = parts.get(1);

        if (key.equals("model name")) {
          numberOfCpus++;
          cpuType = value;
        } else if (key.equals("cache size")) {
          cacheSize = value;
        }
      }
      System.out.printf("CPU:        %d * %s\n", numberOfCpus, cpuType);
      System.out.printf("CPUCache:   %s\n", cacheSize);
    }
  }

  private void open()
      throws IOException {
    db = Keyspace.open(databaseDir);
  }

  private void start() {
    startTime = System.nanoTime();
    bytes = 0;
    message = null;
    lastOpFinish = startTime;
    // hist.clear();
    done = 0;
    nextReport = 100;
  }

  private void stop(String benchmark) {
    long endTime = System.nanoTime();
    double elapsedSeconds = 1.0d * (endTime - startTime) / TimeUnit.SECONDS.toNanos(1);

    // Pretend at least one op was done in case we are running a benchmark
    // that does nto call FinishedSingleOp().
    if (done < 1) {
      done = 1;
    }

    if (bytes > 0) {
      String rate = String.format("%6.1f MB/s", (bytes / 1048576.0) / elapsedSeconds);
      if (message != null) {
        message = rate + " " + message;
      } else {
        message = rate;
      }
    } else if (message == null) {
      message = "";
    }

    System.out.printf("%-12s : %11.5f micros/op;%s%s\n",
        benchmark,
        elapsedSeconds * 1.0e6 / done,
        (message == null ? "" : " "),
        message);
//        if (FLAGS_histogram) {
//            System.out.printf("Microseconds per op:\n%s\n", hist_.ToString().c_str());
//        }

    if (postMessage != null) {
      System.out.printf("\n%s\n", postMessage);
      postMessage = null;
    }

  }

  private void write(Order order, DBState state, int numEntries, int valueSize, int entriesPerBatch)
      throws IOException {
    if (state == DBState.FRESH) {
      if (useExisting) {
        message = "skipping (--use_existing_db is true)";
        return;
      }
      Keyspace.clear(db.getName());
      destroyDb();
      open();
      start(); // Do not count time taken to destroy/open
    }

    if (numEntries != num) {
      message = String.format("(%d ops)", numEntries);
    }

    ColumnFamilyStore cfs = db.getColumnFamilyStore("Standard1");
    ByteBuffer empty = ByteBuffer.allocate(0);
    for (int i = 0; i < numEntries; i += entriesPerBatch) {

      for (int j = 0; j < entriesPerBatch; j++) {
        int k = (order == Order.SEQUENTIAL) ? i + j : random.nextInt(num);
        byte[] key = formatNumber(k);
        Mutation rm = new Mutation(databaseDir, ByteBuffer.wrap(key));
        rm.add("Standard1", CellNames.simpleDense(ByteBuffer.wrap(generator.generate(valueSize))), empty , 1);
        bytes += valueSize + key.length;
        db.apply(rm, true);
        finishedSingleOp();
      }
    }
  }

  public static byte[] formatNumber(long n) {
    Preconditions.checkArgument(n >= 0, "number must be positive");

    byte[] slice = new byte[16];

    int i = 15;
    while (n > 0) {
      slice[i--] = (byte) ((long) '0' + (n % 10));
      n /= 10;
    }
    while (i >= 0) {
      slice[i--] = '0';
    }
    return slice;
  }

  private void finishedSingleOp() {
//        if (histogram) {
//            todo
//        }
    done++;
    if (done >= nextReport) {
      if (nextReport < 1000) {
        nextReport += 100;
      } else if (nextReport < 5000) {
        nextReport += 500;
      } else if (nextReport < 10000) {
        nextReport += 1000;
      } else if (nextReport < 50000) {
        nextReport += 5000;
      } else if (nextReport < 100000) {
        nextReport += 10000;
      } else if (nextReport < 500000) {
        nextReport += 50000;
      } else {
        nextReport += 100000;
      }
      System.out.printf("... finished %d ops%30s\r", done, "");

    }
  }

  private void readSequential() {
//    for (int loops = 0; loops < 5; loops++) {
//      DBIterator iterator = db.iterator();
//      for (int i = 0; i < reads && iterator.hasNext(); i++) {
//        Map.Entry<byte[], byte[]> entry = iterator.next();
//        bytes += entry.getKey().length + entry.getValue().length;
//        finishedSingleOp();
//      }
//    }
  }

  private void readReverse() {
    //To change body of created methods use File | Settings | File Templates.
  }

  private void readRandom() {
    ColumnFamilyStore cfs = db.getColumnFamilyStore("Standard1");
    ByteBuffer byteBuffer = ByteBuffer.wrap("Standard1".getBytes());
    for (int i = 0; i < reads; i++) {
      byte[] key = formatNumber(random.nextInt(num));
      DecoratedKey rowKey = StorageService.getPartitioner().decorateKey(ByteBuffer.wrap(key));
      ColumnFamily cf = cfs.getColumnFamily(QueryFilter.getIdentityFilter(rowKey, "Standard1", 1));

      // int length = value.limit() -value.position();

      // Preconditions.checkNotNull(value, "db.get(%s) is null", new String(key, "utf-8"));
      // bytes += key.length + length;
      finishedSingleOp();
    }
  }

//  private void readHot() {
//    int range = (num + 99) / 100;
//    for (int i = 0; i < reads; i++) {
//      byte[] key = formatNumber(random.nextInt(range));
//      byte[] value = db.get(key);
//      bytes += key.length + value.length;
//      finishedSingleOp();
//    }
//  }
//
//  private void compact()
//      throws IOException {
//    if (db instanceof DbImpl) {
//      ((DbImpl) db).compactMemTable();
//      for (int level = 0; level < NUM_LEVELS - 1; level++) {
//        ((DbImpl) db).compactRange(level, Slices.copiedBuffer("", UTF_8), Slices.copiedBuffer("~", UTF_8));
//      }
//    }
//  }

//  private void crc32c(int blockSize, String message) {
//    // Checksum about 500MB of data total
//    byte[] data = new byte[blockSize];
//    for (int i = 0; i < data.length; i++) {
//      data[i] = 'x';
//
//    }
//
//    long bytes = 0;
//    int crc = 0;
//    while (bytes < 1000 * 1048576) {
//      PureJavaCrc32C checksum = new PureJavaCrc32C();
//      checksum.update(data, 0, blockSize);
//      crc = checksum.getMaskedValue();
//      finishedSingleOp();
//      bytes += blockSize;
//    }
//    System.out.printf("... crc=0x%x\r", crc);
//
//    this.bytes = bytes;
//    // Print so result is not dead
//    this.message = message;
//  }

  private void acquireLoad() {
    //To change body of created methods use File | Settings | File Templates.
  }

//  private void snappyCompress() {
//    byte[] raw = generator.generate(new Options().blockSize());
//    byte[] compressedOutput = new byte[Snappy.maxCompressedLength(raw.length)];
//
//    long produced = 0;
//
//    // attempt to compress the block
//    while (bytes < 1024 * 1048576) {  // Compress 1G
//      try {
//        int compressedSize = Snappy.compress(raw, 0, raw.length, compressedOutput, 0);
//        bytes += raw.length;
//        produced += compressedSize;
//      } catch (IOException ignored) {
//        throw Throwables.propagate(ignored);
//      }
//
//      finishedSingleOp();
//    }
//
//    message = String.format("(output: %.1f%%)", (produced * 100.0) / bytes);
//  }
//
//  private void snappyUncompressArray() {
//    int inputSize = new Options().blockSize();
//    byte[] compressedOutput = new byte[Snappy.maxCompressedLength(inputSize)];
//    byte[] raw = generator.generate(inputSize);
//    int compressedLength;
//    try {
//      compressedLength = Snappy.compress(raw, 0, raw.length, compressedOutput, 0);
//    } catch (IOException e) {
//      throw Throwables.propagate(e);
//    }
//    // attempt to uncompress the block
//    while (bytes < 5L * 1024 * 1048576) {  // Compress 1G
//      try {
//        Snappy.uncompress(compressedOutput, 0, compressedLength, raw, 0);
//        bytes += inputSize;
//      } catch (IOException ignored) {
//        throw Throwables.propagate(ignored);
//      }
//
//      finishedSingleOp();
//    }
//  }
//
//  private void snappyUncompressDirectBuffer() {
//    int inputSize = new Options().blockSize();
//    byte[] compressedOutput = new byte[Snappy.maxCompressedLength(inputSize)];
//    byte[] raw = generator.generate(inputSize);
//    int compressedLength;
//    try {
//      compressedLength = Snappy.compress(raw, 0, raw.length, compressedOutput, 0);
//    } catch (IOException e) {
//      throw Throwables.propagate(e);
//    }
//
//    ByteBuffer uncompressedBuffer = ByteBuffer.allocateDirect(inputSize);
//    ByteBuffer compressedBuffer = ByteBuffer.allocateDirect(compressedLength);
//    compressedBuffer.put(compressedOutput, 0, compressedLength);
//
//    // attempt to uncompress the block
//    while (bytes < 5L * 1024 * 1048576) {  // Compress 1G
//      try {
//        uncompressedBuffer.clear();
//        compressedBuffer.position(0);
//        compressedBuffer.limit(compressedLength);
//        Snappy.uncompress(compressedBuffer, uncompressedBuffer);
//        bytes += inputSize;
//      } catch (IOException ignored) {
//        throw Throwables.propagate(ignored);
//      }
//
//      finishedSingleOp();
//    }
//  }
//
  private void heapProfile() {
    //To change body of created methods use File | Settings | File Templates.
  }

  private void destroyDb() {
    Keyspace.clear(databaseDir);
  }

  private void printStats() {
    //To change body of created methods use File | Settings | File Templates.
  }

  public static void main(String[] args)
      throws Exception {
    Map<Flag, Object> flags = new EnumMap<>(Flag.class);
    for (Flag flag : Flag.values()) {
      flags.put(flag, flag.getDefaultValue());
    }
    for (String arg : args) {
      boolean valid = false;
      if (arg.startsWith("--")) {
        try {
          ImmutableList<String> parts = ImmutableList.copyOf(Splitter.on("=").limit(2).split(arg.substring(2)));
          Flag key = Flag.valueOf(parts.get(0));
          Object value = key.parseValue(parts.get(1));
          flags.put(key, value);
          valid = true;
        } catch (Exception e) {
        }
      }

      if (!valid) {
        System.err.println("Invalid argument " + arg);
        System.exit(1);
      }

    }
    new CassandraBenchmark(flags).run();
  }

  private enum Flag {
    // Comma-separated list of operations to run in the specified order
    //   Actual benchmarks:
    //      fillseq       -- write N values in sequential key order in async mode
    //      fillrandom    -- write N values in random key order in async mode
    //      overwrite     -- overwrite N values in random key order in async mode
    //      fillsync      -- write N/100 values in random key order in sync mode
    //      fill100K      -- write N/1000 100K values in random order in async mode
    //      readseq       -- read N times sequentially
    //      readreverse   -- read N times in reverse order
    //      readrandom    -- read N times in random order
    //      readhot       -- read N times in random order from 1% section of DB
    //      crc32c        -- repeated crc32c of 4K of data
    //      acquireload   -- load N*1000 times
    //   Meta operations:
    //      compact     -- Compact the entire DB
    //      stats       -- Print DB stats
    //      heapprofile -- Dump a heap profile (if supported by this port)
    benchmarks(ImmutableList.of(
        "fillseq",
        "fillseq",
        "fillseq",
        "fillsync",
        "fillrandom",
        "overwrite",
        "fillseq",
        "readrandom",
        "readrandom",  // Extra run to allow previous compactions to quiesce
        "readseq",
        // "readreverse",
        "compact",
        "readrandom",
        "readseq",
        // "readreverse",
        "fill100K",
        // "crc32c",
        "snappycomp",
        "unsnap-array",
        "unsnap-direct"
        // "acquireload"
    )) {
      @Override
      public Object parseValue(String value) {
        return ImmutableList.copyOf(Splitter.on(",").trimResults().omitEmptyStrings().split(value));
      }
    },

    // Arrange to generate values that shrink to this fraction of
    // their original size after compression
    compression_ratio(0.5d) {
      @Override
      public Object parseValue(String value) {
        return Double.parseDouble(value);
      }
    },

    // Print histogram of operation timings
    histogram(false) {
      @Override
      public Object parseValue(String value) {
        return Boolean.parseBoolean(value);
      }
    },

    // If true, do not destroy the existing database.  If you set this
    // flag and also specify a benchmark that wants a fresh database, that
    // benchmark will fail.
    use_existing_db(false) {
      @Override
      public Object parseValue(String value) {
        return Boolean.parseBoolean(value);
      }
    },

    // Number of key/values to place in database
    num(1000000) {
      @Override
      public Object parseValue(String value) {
        return Integer.parseInt(value);
      }
    },

    // Number of read operations to do.  If negative, do FLAGS_num reads.
    reads(null) {
      @Override
      public Object parseValue(String value) {
        return Integer.parseInt(value);
      }
    },

    // Size of each value
    value_size(100) {
      @Override
      public Object parseValue(String value) {
        return Integer.parseInt(value);
      }
    },

    // Number of bytes to buffer in memtable before compacting
    // (initialized to default value by "main")
    write_buffer_size(null) {
      @Override
      public Object parseValue(String value) {
        return Integer.parseInt(value);
      }
    },

    // Number of bytes to use as a cache of uncompressed data.
    // Negative means use default settings.
    cache_size(-1) {
      @Override
      public Object parseValue(String value) {
        return Integer.parseInt(value);
      }
    },

    // Maximum number of files to keep open at the same time (use default if == 0)
    open_files(0) {
      @Override
      public Object parseValue(String value) {
        return Integer.parseInt(value);
      }
    },

    // Use the db with the following name.
    db("Keyspace1") {
      @Override
      public Object parseValue(String value) {
        return value;
      }
    };

    private final Object defaultValue;

    Flag(Object defaultValue) {
      this.defaultValue = defaultValue;
    }

    protected abstract Object parseValue(String value);

    public Object getDefaultValue() {
      return defaultValue;
    }
  }

  private static class RandomGenerator {
    private final Slice data;
    private int position;

    private RandomGenerator(double compressionRatio) {
      // We use a limited amount of data over and over again and ensure
      // that it is larger than the compression window (32KB), and also
      // large enough to serve all typical value sizes we want to write.
      Random rnd = new Random(301);
      data = Slices.allocate(1048576 + 100);
      SliceOutput sliceOutput = data.output();
      while (sliceOutput.size() < 1048576) {
        // Add a short fragment that is as compressible as specified
        // by FLAGS_compression_ratio.
        sliceOutput.writeBytes(compressibleString(rnd, compressionRatio, 100));
      }
    }

    private byte[] generate(int length) {
      if (position + length > data.length()) {
        position = 0;
        assert (length < data.length());
      }
      Slice slice = data.slice(position, length);
      position += length;
      return slice.getBytes();
    }
  }

  private static Slice compressibleString(Random rnd, double compressionRatio, int len) {
    int raw = (int) (len * compressionRatio);
    if (raw < 1) {
      raw = 1;
    }
    Slice rawData = generateRandomSlice(rnd, raw);

    // Duplicate the random data until we have filled "len" bytes
    Slice dst = Slices.allocate(len);
    SliceOutput sliceOutput = dst.output();
    while (sliceOutput.size() < len) {
      sliceOutput.writeBytes(rawData, 0, Math.min(rawData.length(), sliceOutput.writableBytes()));
    }
    return dst;
  }

  private static Slice generateRandomSlice(Random random, int length) {
    Slice rawData = Slices.allocate(length);
    SliceOutput sliceOutput = rawData.output();
    while (sliceOutput.isWritable()) {
      sliceOutput.writeByte((byte) ((int) ' ' + random.nextInt(95)));
    }
    return rawData;
  }
}
