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
package org.apache.lucene.search.knn;

import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.HnswQueueSaturationCollector;
import org.apache.lucene.search.TopKnnCollector;
import org.apache.lucene.tests.util.LuceneTestCase;

public class TestKnnSearchStrategy extends LuceneTestCase {

  public void testHnswDefaultMinThreshold() {
    KnnSearchStrategy.Hnsw strategy = new KnnSearchStrategy.Hnsw(60);
    assertEquals(60, strategy.filteredSearchThreshold());
    assertEquals(
        KnnSearchStrategy.DEFAULT_MIN_FILTERED_SEARCH_THRESHOLD,
        strategy.minFilteredSearchThreshold());
    // no lower bound: any ratio below the threshold uses filtered search
    assertTrue(strategy.useFilteredSearch(0.0f));
    assertTrue(strategy.useFilteredSearch(0.01f));
    assertTrue(strategy.useFilteredSearch(0.59f));
    assertFalse(strategy.useFilteredSearch(0.60f));
    assertFalse(strategy.useFilteredSearch(1.0f));
  }

  public void testHnswMinThresholdBand() {
    KnnSearchStrategy.Hnsw strategy = new KnnSearchStrategy.Hnsw(60, 10);
    assertEquals(60, strategy.filteredSearchThreshold());
    assertEquals(10, strategy.minFilteredSearchThreshold());
    // below the floor, filtered search is never used
    assertFalse(strategy.useFilteredSearch(0.0f));
    assertFalse(strategy.useFilteredSearch(0.09f));
    // the floor is inclusive, the threshold exclusive
    assertTrue(strategy.useFilteredSearch(0.10f));
    assertTrue(strategy.useFilteredSearch(0.30f));
    assertTrue(strategy.useFilteredSearch(0.59f));
    assertFalse(strategy.useFilteredSearch(0.60f));
    assertFalse(strategy.useFilteredSearch(1.0f));
  }

  public void testHnswValidation() {
    expectThrows(IllegalArgumentException.class, () -> new KnnSearchStrategy.Hnsw(-1));
    expectThrows(IllegalArgumentException.class, () -> new KnnSearchStrategy.Hnsw(101));
    expectThrows(IllegalArgumentException.class, () -> new KnnSearchStrategy.Hnsw(60, -1));
    expectThrows(IllegalArgumentException.class, () -> new KnnSearchStrategy.Hnsw(60, 101));
    // an inverted band is rejected
    expectThrows(IllegalArgumentException.class, () -> new KnnSearchStrategy.Hnsw(10, 60));
    // an empty band is allowed and never uses filtered search
    KnnSearchStrategy.Hnsw empty = new KnnSearchStrategy.Hnsw(10, 10);
    assertFalse(empty.useFilteredSearch(0.10f));
    assertFalse(empty.useFilteredSearch(0.0f));
  }

  public void testHnswEqualsAndHashCode() {
    KnnSearchStrategy.Hnsw a = new KnnSearchStrategy.Hnsw(60, 10);
    assertEquals(a, new KnnSearchStrategy.Hnsw(60, 10));
    assertEquals(a.hashCode(), new KnnSearchStrategy.Hnsw(60, 10).hashCode());
    assertNotEquals(a, new KnnSearchStrategy.Hnsw(60));
    assertNotEquals(a, new KnnSearchStrategy.Hnsw(60, 20));
    assertNotEquals(a, new KnnSearchStrategy.Hnsw(50, 10));
    // the single-arg ctor stays equivalent to a zero floor
    assertEquals(new KnnSearchStrategy.Hnsw(60), new KnnSearchStrategy.Hnsw(60, 0));
  }

  public void testPatienceForwardsMinThreshold() {
    TopKnnCollector delegate = new TopKnnCollector(10, 1000, new KnnSearchStrategy.Hnsw(60, 10));
    HnswQueueSaturationCollector collector = new HnswQueueSaturationCollector(delegate, 0.9, 5);
    KnnSearchStrategy strategy = collector.getSearchStrategy();
    assertTrue(strategy instanceof KnnSearchStrategy.Patience);
    KnnSearchStrategy.Patience patience = (KnnSearchStrategy.Patience) strategy;
    assertEquals(60, patience.filteredSearchThreshold());
    assertEquals(10, patience.minFilteredSearchThreshold());
    assertFalse(patience.useFilteredSearch(0.05f));
    assertTrue(patience.useFilteredSearch(0.30f));
  }

  public void testPatienceForwardsMinThresholdThroughSeeded() {
    KnnSearchStrategy seeded =
        new KnnSearchStrategy.Seeded(
            DocIdSetIterator.empty(), 0, new KnnSearchStrategy.Hnsw(60, 10));
    TopKnnCollector delegate = new TopKnnCollector(10, 1000, seeded);
    HnswQueueSaturationCollector collector = new HnswQueueSaturationCollector(delegate, 0.9, 5);
    KnnSearchStrategy strategy = collector.getSearchStrategy();
    assertTrue(strategy instanceof KnnSearchStrategy.Seeded);
    KnnSearchStrategy original = ((KnnSearchStrategy.Seeded) strategy).originalStrategy();
    assertTrue(original instanceof KnnSearchStrategy.Patience);
    KnnSearchStrategy.Patience patience = (KnnSearchStrategy.Patience) original;
    assertEquals(60, patience.filteredSearchThreshold());
    assertEquals(10, patience.minFilteredSearchThreshold());
  }
}
