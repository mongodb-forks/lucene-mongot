/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.lucene.benchmark.jmh;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.apache.lucene.codecs.KnnVectorsReader;
import org.apache.lucene.codecs.lucene104.Lucene104Codec;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.FloatVectorValues;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.SegmentReader;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.search.AcceptDocs;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.TopKnnCollector;
import org.apache.lucene.store.MMapDirectory;
import org.apache.lucene.util.FixedBitSet;
import org.apache.lucene.util.IOUtils;
import org.apache.lucene.util.hnsw.HnswGraphSearcher;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Benchmarks the exhaustive-scan branch of {@code Lucene99HnswVectorsReader.search} (the "expected
 * HNSW visits >= filtered count" fallback), which scores the accepted vectors in bulk via {@code
 * VectorScorer.Bulk or RandomVectorScorer.bulkScore}.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 2)
@Fork(3)
@State(Scope.Benchmark)
public class ExhaustiveKnnScanBench {
  static final int DIM = 384;
  static final String FIELD = "v";

  @Param({"1.0", ".90", ".50"})
  public double density;

  @Param({"200000"})
  public int N;

  @Param({"2.0", "1.0", "0.10", "0.01"})
  public double selectivity;

  IndexReader reader;
  KnnVectorsReader knnReader;
  FloatVectorValues vectorValues;
  AcceptDocs acceptDocs;
  float[] target;
  int k;

  Path tmpDir;
  Path prebuiltIndex;
  boolean deleteIndexOnTearDown;

  @Setup(Level.Trial)
  public void setup() throws Exception {

    // check.main mode / JMH runs can supply a prebuilt index root (per density) to avoid
    // rebuilding the index in every trial
    String indexRoot = System.getProperty("bench.indexRoot");
    Path prebuilt =
        prebuiltIndex != null
            ? prebuiltIndex
            : (indexRoot != null ? Path.of(indexRoot, "bench-idx-" + N + "-" + density) : null);
    if (prebuilt != null) {
      Files.createDirectories(prebuilt);
    }
    tmpDir = prebuilt != null ? prebuilt : Files.createTempDirectory("ExhaustiveKnnScanBench");
    deleteIndexOnTearDown = prebuilt == null;
    // Separate seeded generators so the filter and query are identical whether or not this trial
    // had to build the index (index building consumes a variable number of draws).
    Random indexRnd = new Random(42);
    Random filterRnd = new Random(7);
    Random targetRnd = new Random(13);
    MMapDirectory dir = new MMapDirectory(tmpDir);
    if (DirectoryReader.indexExists(dir) == false) {
      IndexWriterConfig cfg =
          new IndexWriterConfig().setCodec(new Lucene104Codec()).setRAMBufferSizeMB(1024);
      try (IndexWriter writer = new IndexWriter(dir, cfg)) {
        for (int i = 0; i < N; ++i) {
          Document doc = new Document();
          if (indexRnd.nextDouble() < density) {
            float[] vec = new float[DIM];
            for (int d = 0; d < DIM; ++d) {
              vec[d] = indexRnd.nextFloat(-1f, 1f);
            }
            doc.add(new KnnFloatVectorField(FIELD, vec, VectorSimilarityFunction.COSINE));
          }
          writer.addDocument(doc);
        }
        writer.forceMerge(1);
      }
    }

    reader = DirectoryReader.open(new MMapDirectory(tmpDir));
    LeafReaderContext leaf = reader.leaves().getFirst();
    SegmentReader segReader = (SegmentReader) leaf.reader();
    if (reader.leaves().size() != 1) {
      throw new IllegalStateException("expected single segment");
    }
    knnReader = segReader.getVectorReader();
    vectorValues = segReader.getFloatVectorValues(FIELD);
    int numVectors = vectorValues.size();

    // Filter bits at the requested selectivity over maxDoc.
    int maxDoc = segReader.maxDoc();
    int accepted = 0;
    if (selectivity <= 1.0) {
      FixedBitSet bits = new FixedBitSet(maxDoc);
      for (int i = 0; i < maxDoc; ++i) {
        if (filterRnd.nextDouble() < selectivity) {
          bits.set(i);
          ++accepted;
        }
      }
      acceptDocs = AcceptDocs.fromLiveDocs(bits, maxDoc);
    } else {
      acceptDocs = AcceptDocs.fromLiveDocs(null, maxDoc);
      accepted = maxDoc;
    }
    checkWithinTolerance("vectors", numVectors, density * maxDoc);
    checkWithinTolerance("accepted docs", accepted, Math.max(1, selectivity) * maxDoc);

    target = new float[DIM];
    for (int d = 0; d < DIM; ++d) {
      target[d] = targetRnd.nextFloat(-1, 1);
    }

    // Ensure the exhaustive branch (not HNSW) will run by ensuring filterCount < ln(graphSize) * k
    k = (int) Math.ceil(accepted / Math.log(numVectors)) + 1;

    // Assert the exhaustive branch (not HNSW) will run for these parameters.
    int graphSize = numVectors;
    int filteredDocCount = Math.min(acceptDocs.cost(), graphSize);
    int unfilteredVisit = HnswGraphSearcher.expectedVisitedNodes(k, graphSize);
    // Mirrors the reader's HNSW condition (unfilteredVisit < filteredDocCount).
    if (unfilteredVisit < filteredDocCount) {
      throw new IllegalStateException(
          "parameters would take the HNSW branch: k="
              + k
              + " expectedVisit="
              + unfilteredVisit
              + " filteredDocCount="
              + filteredDocCount);
    }
  }

  /**
   * Fails setup when a generated count strays from its expected value, e.g. a cached index built by
   * different generation code or a filter wired to the wrong parameter.
   */
  private static void checkWithinTolerance(String what, int actual, double expected) {
    // 10% relative slack plus a small absolute slack for low expected counts.
    if (Math.abs(actual - expected) > 0.1 * expected + 50) {
      throw new IllegalStateException(
          "expected ~" + Math.round(expected) + " " + what + " but got " + actual);
    }
  }

  @TearDown(Level.Trial)
  public void tearDown() throws IOException {
    reader.close();
    // Only delete temp indexes; prebuilt ones (check.indexDir or bench.indexRoot) are reused.
    if (deleteIndexOnTearDown) {
      IOUtils.rm(tmpDir);
    }
  }

  @Benchmark
  public TopDocs searchExhaustive() throws IOException {
    TopKnnCollector collector = new TopKnnCollector(k, Integer.MAX_VALUE);
    knnReader.search(FIELD, target, collector, acceptDocs);
    return collector.topDocs();
  }
}
