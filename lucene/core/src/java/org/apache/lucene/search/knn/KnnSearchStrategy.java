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

import java.util.Objects;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.HnswQueueSaturationCollector;

/**
 * KnnSearchStrategy is a strategy for kNN search, providing additional search strategy
 * configuration
 *
 * @lucene.experimental
 */
public abstract class KnnSearchStrategy {
  public static final int DEFAULT_FILTERED_SEARCH_THRESHOLD = 0;
  public static final int DEFAULT_MIN_FILTERED_SEARCH_THRESHOLD = 0;

  /** Override and implement search strategy instance equivalence properly in a subclass. */
  @Override
  public abstract boolean equals(Object obj);

  /**
   * Override and implement search strategy hash code properly in a subclass.
   *
   * @see #equals(Object)
   */
  @Override
  public abstract int hashCode();

  /** Signal processing of the next block of vectors. */
  public abstract void nextVectorsBlock();

  /**
   * A strategy for kNN search that uses HNSW
   *
   * @lucene.experimental
   */
  public static class Hnsw extends KnnSearchStrategy {
    public static final Hnsw DEFAULT = new Hnsw(DEFAULT_FILTERED_SEARCH_THRESHOLD);

    private final int filteredSearchThreshold;
    private final int minFilteredSearchThreshold;

    /**
     * Create a new Hnsw strategy
     *
     * @param filteredSearchThreshold threshold for filtered search, a percentage value from 0 to
     *     100 where 0 means never use filtered search and 100 means always use filtered search.
     */
    public Hnsw(int filteredSearchThreshold) {
      this(filteredSearchThreshold, DEFAULT_MIN_FILTERED_SEARCH_THRESHOLD);
    }

    /**
     * Create a new Hnsw strategy
     *
     * @param filteredSearchThreshold threshold for filtered search, a percentage value from 0 to
     *     100 where 0 means never use filtered search and 100 means always use filtered search.
     * @param minFilteredSearchThreshold lower bound for filtered search, a percentage value from 0
     *     to 100 below which filtered search is never used. Must be &lt;= filteredSearchThreshold;
     *     0 means no lower bound.
     */
    public Hnsw(int filteredSearchThreshold, int minFilteredSearchThreshold) {
      if (filteredSearchThreshold < 0 || filteredSearchThreshold > 100) {
        throw new IllegalArgumentException("filteredSearchThreshold must be >= 0 and <= 100");
      }
      if (minFilteredSearchThreshold < 0 || minFilteredSearchThreshold > 100) {
        throw new IllegalArgumentException("minFilteredSearchThreshold must be >= 0 and <= 100");
      }
      if (minFilteredSearchThreshold > filteredSearchThreshold) {
        throw new IllegalArgumentException(
            "minFilteredSearchThreshold must be <= filteredSearchThreshold");
      }
      this.filteredSearchThreshold = filteredSearchThreshold;
      this.minFilteredSearchThreshold = minFilteredSearchThreshold;
    }

    public int filteredSearchThreshold() {
      return filteredSearchThreshold;
    }

    public int minFilteredSearchThreshold() {
      return minFilteredSearchThreshold;
    }

    /**
     * Whether to use filtered search based on the ratio of vectors that pass the filter
     *
     * @param ratioPassingFilter ratio of vectors that pass the filter
     * @return true if filtered search should be used
     */
    public final boolean useFilteredSearch(float ratioPassingFilter) {
      assert ratioPassingFilter >= 0 && ratioPassingFilter <= 1;
      float percentPassingFilter = ratioPassingFilter * 100;
      return percentPassingFilter >= minFilteredSearchThreshold
          && percentPassingFilter < filteredSearchThreshold;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      Hnsw hnsw = (Hnsw) o;
      return filteredSearchThreshold == hnsw.filteredSearchThreshold
          && minFilteredSearchThreshold == hnsw.minFilteredSearchThreshold;
    }

    @Override
    public int hashCode() {
      return Objects.hash(filteredSearchThreshold, minFilteredSearchThreshold);
    }

    @Override
    public void nextVectorsBlock() {}
  }

  /**
   * A strategy for kNN search that uses a set of entry points to start the search
   *
   * @lucene.experimental
   */
  public static class Seeded extends KnnSearchStrategy {
    private final DocIdSetIterator entryPoints;
    private final int numberOfEntryPoints;
    private final KnnSearchStrategy originalStrategy;

    public Seeded(
        DocIdSetIterator entryPoints, int numberOfEntryPoints, KnnSearchStrategy originalStrategy) {
      if (numberOfEntryPoints < 0) {
        throw new IllegalArgumentException("numberOfEntryPoints must be >= 0");
      }
      this.numberOfEntryPoints = numberOfEntryPoints;
      if (numberOfEntryPoints > 0 && entryPoints == null) {
        throw new IllegalArgumentException("entryPoints must not be null");
      }
      this.entryPoints = entryPoints == null ? DocIdSetIterator.empty() : entryPoints;
      this.originalStrategy = originalStrategy;
    }

    /**
     * Iterator of valid entry points for the kNN search
     *
     * @return DocIdSetIterator of entry points
     */
    public DocIdSetIterator entryPoints() {
      return entryPoints;
    }

    /**
     * Number of valid entry points for the kNN search
     *
     * @return number of entry points
     */
    public int numberOfEntryPoints() {
      return numberOfEntryPoints;
    }

    /**
     * Original strategy to use after seeding
     *
     * @return original strategy
     */
    public KnnSearchStrategy originalStrategy() {
      return originalStrategy;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      Seeded seeded = (Seeded) o;
      return numberOfEntryPoints == seeded.numberOfEntryPoints
          && Objects.equals(entryPoints, seeded.entryPoints)
          && Objects.equals(originalStrategy, seeded.originalStrategy);
    }

    @Override
    public int hashCode() {
      return Objects.hash(entryPoints, numberOfEntryPoints, originalStrategy);
    }

    @Override
    public void nextVectorsBlock() {
      originalStrategy.nextVectorsBlock();
    }
  }

  /**
   * A strategy for kNN search on HNSW that early exits when nearest neighbor collection rate
   * saturates.
   *
   * @lucene.experimental
   */
  public static class Patience extends Hnsw {
    private final HnswQueueSaturationCollector collector;

    public Patience(HnswQueueSaturationCollector collector, int filteredSearchThreshold) {
      this(collector, filteredSearchThreshold, DEFAULT_MIN_FILTERED_SEARCH_THRESHOLD);
    }

    public Patience(
        HnswQueueSaturationCollector collector,
        int filteredSearchThreshold,
        int minFilteredSearchThreshold) {
      super(filteredSearchThreshold, minFilteredSearchThreshold);
      this.collector = collector;
    }

    @Override
    public boolean equals(Object obj) {
      return super.equals(obj) && Objects.equals(collector, ((Patience) obj).collector);
    }

    @Override
    public int hashCode() {
      return Objects.hash(super.hashCode(), collector);
    }

    @Override
    public void nextVectorsBlock() {
      collector.nextCandidate();
    }
  }
}
