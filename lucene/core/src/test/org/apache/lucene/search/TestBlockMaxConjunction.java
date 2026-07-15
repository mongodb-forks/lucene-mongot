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
package org.apache.lucene.search;

import java.io.IOException;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field.Store;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.store.Directory;
import org.apache.lucene.tests.search.AssertingQuery;
import org.apache.lucene.tests.search.BlockScoreQueryWrapper;
import org.apache.lucene.tests.search.CheckHits;
import org.apache.lucene.tests.search.RandomApproximationQuery;
import org.apache.lucene.tests.util.LuceneTestCase;
import org.apache.lucene.tests.util.TestUtil;

public class TestBlockMaxConjunction extends LuceneTestCase {

  private Query maybeWrap(Query query) {
    if (random().nextBoolean()) {
      query = new BlockScoreQueryWrapper(query, TestUtil.nextInt(random(), 2, 8));
      query = new AssertingQuery(random(), query);
    }
    return query;
  }

  private Query maybeWrapTwoPhase(Query query) {
    if (random().nextBoolean()) {
      query = new RandomApproximationQuery(query, random());
      query = new AssertingQuery(random(), query);
    }
    return query;
  }

  public void testRandom() throws IOException {
    Directory dir = newDirectory();
    IndexWriter w = new IndexWriter(dir, newIndexWriterConfig());
    int numDocs = atLeast(1000);
    for (int i = 0; i < numDocs; ++i) {
      Document doc = new Document();
      int numValues = random().nextInt(1 << random().nextInt(5));
      int start = random().nextInt(10);
      for (int j = 0; j < numValues; ++j) {
        doc.add(new StringField("foo", Integer.toString(start + j), Store.NO));
      }
      w.addDocument(doc);
    }
    IndexReader reader = DirectoryReader.open(w);
    w.close();
    // Disable search concurrency for this test: it requires a single segment, and no intra-segment
    // concurrency for its assertions to always be valid
    IndexSearcher searcher =
        newSearcher(reader, random().nextBoolean(), random().nextBoolean(), false);

    for (int iter = 0; iter < 100; ++iter) {
      int start = random().nextInt(10);
      int numClauses = random().nextInt(1 << random().nextInt(5));
      BooleanQuery.Builder builder = new BooleanQuery.Builder();
      for (int i = 0; i < numClauses; ++i) {
        builder.add(
            maybeWrap(new TermQuery(new Term("foo", Integer.toString(start + i)))), Occur.MUST);
      }
      Query query = builder.build();

      CheckHits.checkTopScores(random(), query, searcher);

      int filterTerm = random().nextInt(30);
      Query filteredQuery =
          new BooleanQuery.Builder()
              .add(query, Occur.MUST)
              .add(new TermQuery(new Term("foo", Integer.toString(filterTerm))), Occur.FILTER)
              .build();

      CheckHits.checkTopScores(random(), filteredQuery, searcher);

      builder = new BooleanQuery.Builder();
      for (int i = 0; i < numClauses; ++i) {
        builder.add(
            maybeWrapTwoPhase(new TermQuery(new Term("foo", Integer.toString(start + i)))),
            Occur.MUST);
      }

      Query twoPhaseQuery =
          new BooleanQuery.Builder()
              .add(query, Occur.MUST)
              .add(new TermQuery(new Term("foo", Integer.toString(filterTerm))), Occur.FILTER)
              .build();

      CheckHits.checkTopScores(random(), twoPhaseQuery, searcher);
    }
    reader.close();
    dir.close();
  }

  public void testRandomDocAtATimeWindowScoring() throws IOException {
    Directory dir = newDirectory();
    IndexWriter w = new IndexWriter(dir, newIndexWriterConfig());
    int numDocs = atLeast(1000);
    for (int i = 0; i < numDocs; ++i) {
      Document doc = new Document();
      int numValues = random().nextInt(1 << random().nextInt(5));
      int start = random().nextInt(10);
      for (int j = 0; j < numValues; ++j) {
        doc.add(new StringField("foo", Integer.toString(start + j), Store.NO));
      }
      w.addDocument(doc);
    }
    IndexReader reader = DirectoryReader.open(w);
    w.close();
    // Disable search concurrency for this test: it requires a single segment, and no intra-segment
    // concurrency for its assertions to always be valid
    IndexSearcher searcher =
        newSearcher(reader, random().nextBoolean(), random().nextBoolean(), false);

    for (int iter = 0; iter < 100; ++iter) {
      int start = random().nextInt(10);
      int numClauses = TestUtil.nextInt(random(), 2, 5);
      BooleanQuery.Builder builder = new BooleanQuery.Builder();
      for (int i = 0; i < numClauses; ++i) {
        // Opt every clause in, so that the lead prefers doc-at-a-time window scoring no matter
        // which clause ends up being the least costly one.
        builder.add(
            new PreferDocAtATimeWindowScoringQuery(
                new TermQuery(new Term("foo", Integer.toString(start + i)))),
            Occur.MUST);
      }
      Query query = builder.build();

      CheckHits.checkTopScores(random(), query, searcher);

      int filterTerm = random().nextInt(30);
      Query filteredQuery =
          new BooleanQuery.Builder()
              .add(query, Occur.MUST)
              .add(new TermQuery(new Term("foo", Integer.toString(filterTerm))), Occur.FILTER)
              .build();

      CheckHits.checkTopScores(random(), filteredQuery, searcher);
    }
    reader.close();
    dir.close();
  }

  /**
   * Wraps a query so that its scorer opts into doc-at-a-time window scoring via {@link
   * Scorer#preferDocAtATimeWindowScoring()}, like block-join scorers do, while delegating
   * everything else to the wrapped query's scorer.
   */
  private static class PreferDocAtATimeWindowScoringQuery extends Query {

    private final Query query;

    PreferDocAtATimeWindowScoringQuery(Query query) {
      this.query = query;
    }

    @Override
    public Query rewrite(IndexSearcher indexSearcher) throws IOException {
      Query rewritten = query.rewrite(indexSearcher);
      if (rewritten != query) {
        return new PreferDocAtATimeWindowScoringQuery(rewritten);
      }
      return super.rewrite(indexSearcher);
    }

    @Override
    public Weight createWeight(IndexSearcher searcher, ScoreMode scoreMode, float boost)
        throws IOException {
      Weight weight = query.createWeight(searcher, scoreMode, boost);
      return new FilterWeight(this, weight) {
        @Override
        public ScorerSupplier scorerSupplier(LeafReaderContext context) throws IOException {
          ScorerSupplier scorerSupplier = weight.scorerSupplier(context);
          if (scorerSupplier == null) {
            return null;
          }
          return new ScorerSupplier() {
            @Override
            public Scorer get(long leadCost) throws IOException {
              return new FilterScorer(scorerSupplier.get(leadCost)) {
                @Override
                public boolean preferDocAtATimeWindowScoring() {
                  return true;
                }

                @Override
                public float getMaxScore(int upTo) throws IOException {
                  return in.getMaxScore(upTo);
                }

                @Override
                public int advanceShallow(int target) throws IOException {
                  return in.advanceShallow(target);
                }

                @Override
                public void setMinCompetitiveScore(float minScore) throws IOException {
                  in.setMinCompetitiveScore(minScore);
                }
              };
            }

            @Override
            public long cost() {
              return scorerSupplier.cost();
            }

            @Override
            public void setTopLevelScoringClause() throws IOException {
              scorerSupplier.setTopLevelScoringClause();
            }
          };
        }
      };
    }

    @Override
    public void visit(QueryVisitor visitor) {
      query.visit(visitor.getSubVisitor(Occur.MUST, this));
    }

    @Override
    public String toString(String field) {
      return "PreferDocAtATimeWindowScoring(" + query.toString(field) + ")";
    }

    @Override
    public boolean equals(Object other) {
      return sameClassAs(other) && query.equals(((PreferDocAtATimeWindowScoringQuery) other).query);
    }

    @Override
    public int hashCode() {
      return 31 * classHash() + query.hashCode();
    }
  }
}
