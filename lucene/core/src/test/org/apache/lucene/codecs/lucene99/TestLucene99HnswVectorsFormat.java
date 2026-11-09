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
package org.apache.lucene.codecs.lucene99;

import static java.lang.String.format;
import static org.apache.lucene.index.VectorSimilarityFunction.DOT_PRODUCT;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.oneOf;

import java.io.IOException;
import java.util.Locale;
import org.apache.lucene.codecs.Codec;
import org.apache.lucene.codecs.FilterCodec;
import org.apache.lucene.codecs.KnnVectorsFormat;
import org.apache.lucene.codecs.KnnVectorsReader;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.index.CodecReader;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.store.Directory;
import org.apache.lucene.tests.index.BaseKnnVectorsFormatTestCase;
import org.apache.lucene.tests.util.TestUtil;
import org.apache.lucene.util.SameThreadExecutorService;

public class TestLucene99HnswVectorsFormat extends BaseKnnVectorsFormatTestCase {
  @Override
  protected Codec getCodec() {
    return TestUtil.getDefaultCodec();
  }

  public void testToString() {
    FilterCodec customCodec =
        new FilterCodec("foo", Codec.getDefault()) {
          @Override
          public KnnVectorsFormat knnVectorsFormat() {
            return new Lucene99HnswVectorsFormat(10, 20);
          }
        };
    String expectedPattern =
        "Lucene99HnswVectorsFormat(name=Lucene99HnswVectorsFormat, maxConn=10, beamWidth=20, tinySegmentsThreshold=100, flatVectorFormat=Lucene99FlatVectorsFormat(vectorsScorer=%s()))";
    var defaultScorer = format(Locale.ROOT, expectedPattern, "DefaultFlatVectorScorer");
    var memSegScorer =
        format(Locale.ROOT, expectedPattern, "Lucene99MemorySegmentFlatVectorsScorer");
    assertThat(customCodec.knnVectorsFormat().toString(), is(oneOf(defaultScorer, memSegScorer)));
  }

  public void testLimits() {
    expectThrows(IllegalArgumentException.class, () -> new Lucene99HnswVectorsFormat(-1, 20));
    expectThrows(IllegalArgumentException.class, () -> new Lucene99HnswVectorsFormat(0, 20));
    expectThrows(IllegalArgumentException.class, () -> new Lucene99HnswVectorsFormat(20, 0));
    expectThrows(IllegalArgumentException.class, () -> new Lucene99HnswVectorsFormat(20, -1));
    expectThrows(IllegalArgumentException.class, () -> new Lucene99HnswVectorsFormat(512 + 1, 20));
    expectThrows(IllegalArgumentException.class, () -> new Lucene99HnswVectorsFormat(20, 3201));
    expectThrows(
        IllegalArgumentException.class,
        () -> new Lucene99HnswVectorsFormat(20, 100, 1, new SameThreadExecutorService()));
  }

  public void testSimpleOffHeapSize() throws IOException {
    float[] vector = randomVector(random().nextInt(12, 500));
    try (Directory dir = newDirectory();
        IndexWriter w = new IndexWriter(dir, newIndexWriterConfig())) {
      Document doc = new Document();
      doc.add(new KnnFloatVectorField("f", vector, DOT_PRODUCT));
      w.addDocument(doc);
      w.commit();
      try (IndexReader reader = DirectoryReader.open(w)) {
        LeafReader r = getOnlyLeafReader(reader);
        if (r instanceof CodecReader codecReader) {
          KnnVectorsReader knnVectorsReader = codecReader.getVectorReader();
          knnVectorsReader = knnVectorsReader.unwrapReaderForField("f");
          var fieldInfo = r.getFieldInfos().fieldInfo("f");
          var offHeap = knnVectorsReader.getOffHeapByteSize(fieldInfo);
          assertEquals(vector.length * Float.BYTES, (long) offHeap.get("vec"));
          assertNotNull(offHeap.get("vex"));
          assertEquals(2, offHeap.size());
        }
      }
    }
  }

  @org.apache.lucene.util.SuppressForbidden(
      reason =
          "11.0 keeps PerFieldKnnVectorsFormat.FieldsReader private; single-field test "
              + "indexes need reflection to reach the Lucene99 reader")
  public void testAvgBottomLayerDegree() throws IOException, ReflectiveOperationException {
    // v2 (VERSION_CURRENT): avg degree is written on flush, recomputed on merge
    Codec v2 =
        new FilterCodec("testAvgDegreeV2", TestUtil.getDefaultCodec()) {
          @Override
          public KnnVectorsFormat knnVectorsFormat() {
            return new Lucene99HnswVectorsFormat(
                16, 100, 1, null, 0, Lucene99HnswVectorsFormat.VERSION_CURRENT);
          }
        };
    // v1: no avg degree in the meta, reader must default to 0
    Codec v1 =
        new FilterCodec("testAvgDegreeV1", TestUtil.getDefaultCodec()) {
          @Override
          public KnnVectorsFormat knnVectorsFormat() {
            return new Lucene99HnswVectorsFormat(
                16, 100, 1, null, 0, Lucene99HnswVectorsFormat.VERSION_GROUPVARINT);
          }
        };

    for (Codec codec : new Codec[] {v2, v1}) {
      try (Directory dir = newDirectory()) {
        IndexWriterConfig cfg = newIndexWriterConfig();
        cfg.setCodec(codec);
        try (IndexWriter w = new IndexWriter(dir, cfg)) {
          // two segments via flush() (no commit): the NRT reader pool holds them, so nothing
          // ever resolves the anonymous FilterCodec by name from a segments_N file
          for (int seg = 0; seg < 2; seg++) {
            for (int i = 0; i < 50; i++) {
              Document doc = new Document();
              doc.add(new KnnFloatVectorField("f", randomVector(16), DOT_PRODUCT));
              w.addDocument(doc);
            }
            w.flush();
          }
          // every leaf carries its own (legitimately different) average degree
          try (DirectoryReader reader = DirectoryReader.open(w)) {
            // 11.0 may flush more segments than flush() calls; the count is irrelevant here
            assertTrue("expected at least one segment", reader.leaves().size() >= 1);
            readAvgDegree(reader, codec == v2);
            // merge rebuilds the graph; the value must be present (v2) or still default 0 (v1)
            w.forceMerge(1);
            DirectoryReader merged = DirectoryReader.openIfChanged(reader);
            assertNotNull("expected a new reader after forceMerge", merged);
            try (DirectoryReader m = merged) {
              readAvgDegree(m, codec == v2);
            }
          }
        }
        // the anonymous FilterCodec is not in the SPI registry, so wipe the directory before
        // the framework's post-test CheckIndex would try to resolve it by name
        for (String f : dir.listAll()) {
          dir.deleteFile(f);
        }
      }
    }
  }

  private static void readAvgDegree(DirectoryReader reader, boolean expectPresent)
      throws IOException {
    {
      for (var ctx : reader.leaves()) {
        LeafReader r = ctx.reader();
        assertTrue("expected a CodecReader", r instanceof CodecReader);
        CodecReader codecReader = (CodecReader) r;
        // the test codec's knnVectorsFormat() returns the raw Lucene99 format, so the segment's
        // vector reader is the Lucene99 reader directly -- no per-field wrapper
        KnnVectorsReader knnVectorsReader = codecReader.getVectorReader();
        assertTrue(
            "unexpected vector reader: " + knnVectorsReader.getClass(),
            knnVectorsReader instanceof Lucene99HnswVectorsReader);
        assertTrue(
            "unexpected reader: " + knnVectorsReader.getClass(),
            knnVectorsReader instanceof Lucene99HnswVectorsReader);
        float avg = ((Lucene99HnswVectorsReader) knnVectorsReader).getAvgBottomLayerDegree("f");
        if (expectPresent) {
          assertTrue("expected positive avg degree, got " + avg, avg > 0f);
          assertTrue("avg degree above level-0 max: " + avg, avg <= 32f);
        } else {
          assertEquals("older format must default to 0", 0f, avg, 0f);
        }
      }
    }
  }

  @Override
  protected boolean supportsFloatVectorFallback() {
    return false;
  }
}
