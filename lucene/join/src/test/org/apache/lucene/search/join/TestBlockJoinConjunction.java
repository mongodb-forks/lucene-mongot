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
package org.apache.lucene.search.join;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field.Store;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.ConstantScoreQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.Weight;
import org.apache.lucene.store.Directory;
import org.apache.lucene.tests.index.RandomIndexWriter;
import org.apache.lucene.tests.search.CheckHits;
import org.apache.lucene.tests.util.LuceneTestCase;
import org.apache.lucene.tests.util.TestUtil;
import org.apache.lucene.util.IOUtils;

/**
 * Tests scored conjunctions led by a block-join scorer, which opt out of the score-first window
 * path of {@code BlockMaxConjunctionBulkScorer} and back into doc-at-a-time window scoring via
 * {@link Scorer#preferDocAtATimeWindowScoring()}.
 */
public class TestBlockJoinConjunction extends LuceneTestCase {

  private static final String TYPE_FIELD = "type";
  private static final String PARENT_TYPE = "parent";
  private static final String PARENT_TEXT_FIELD = "parentText";
  private static final String CHILD_VALUE_FIELD = "childValue";

  private Directory dir;
  private IndexReader reader;
  private IndexSearcher searcher;
  private BitSetProducer parentsFilter;

  @Override
  public void setUp() throws Exception {
    super.setUp();
    dir = newDirectory();
    RandomIndexWriter w = new RandomIndexWriter(random(), dir);
    int numBlocks = atLeast(100);
    for (int i = 0; i < numBlocks; ++i) {
      List<Document> block = new ArrayList<>();
      int numChildren = random().nextInt(6);
      for (int j = 0; j < numChildren; ++j) {
        Document child = new Document();
        int numValues = random().nextInt(3);
        for (int k = 0; k < numValues; ++k) {
          child.add(
              newStringField(CHILD_VALUE_FIELD, Integer.toString(random().nextInt(4)), Store.NO));
        }
        block.add(child);
      }
      Document parent = new Document();
      parent.add(newStringField(TYPE_FIELD, PARENT_TYPE, Store.NO));
      // Repeat the term so that parents get different scores
      parent.add(
          newTextField(
              PARENT_TEXT_FIELD, "x ".repeat(TestUtil.nextInt(random(), 1, 10)).trim(), Store.NO));
      block.add(parent);
      w.addDocuments(block);
    }
    reader = w.getReader();
    w.close();
    // Disable search concurrency: CheckHits#checkTopScores requires no intra-segment concurrency
    // for its assertions to always be valid
    searcher = newSearcher(reader, random().nextBoolean(), random().nextBoolean(), false);
    parentsFilter = new QueryBitSetProducer(new TermQuery(new Term(TYPE_FIELD, PARENT_TYPE)));
  }

  @Override
  public void tearDown() throws Exception {
    IOUtils.close(reader, dir);
    super.tearDown();
  }

  public void testBlockJoinScorersPreferDocAtATimeWindowScoring() throws IOException {
    Query toChild =
        new ToChildBlockJoinQuery(new TermQuery(new Term(PARENT_TEXT_FIELD, "x")), parentsFilter);
    Query toParent =
        new ToParentBlockJoinQuery(
            new TermQuery(new Term(CHILD_VALUE_FIELD, "1")), parentsFilter, ScoreMode.Avg);
    // Use an unwrapped searcher so that we assert on the actual block-join scorers
    IndexSearcher plainSearcher = new IndexSearcher(reader);
    for (Query query : List.of(toChild, toParent)) {
      Weight weight =
          plainSearcher.createWeight(
              plainSearcher.rewrite(query), org.apache.lucene.search.ScoreMode.TOP_SCORES, 1f);
      int numScorers = 0;
      for (LeafReaderContext ctx : reader.leaves()) {
        Scorer scorer = weight.scorer(ctx);
        if (scorer != null) {
          ++numScorers;
          assertTrue(scorer.preferDocAtATimeWindowScoring());
        }
      }
      assertTrue(numScorers > 0);
    }
  }

  public void testToChildBlockJoinConjunctionTopScores() throws IOException {
    Query toChild =
        new ToChildBlockJoinQuery(new TermQuery(new Term(PARENT_TEXT_FIELD, "x")), parentsFilter);
    for (int i = 0; i < 4; ++i) {
      Query childFilter = new TermQuery(new Term(CHILD_VALUE_FIELD, Integer.toString(i)));
      if (random().nextBoolean()) {
        childFilter = new ConstantScoreQuery(childFilter);
      }
      Query conjunction =
          new BooleanQuery.Builder().add(toChild, Occur.MUST).add(childFilter, Occur.MUST).build();
      CheckHits.checkTopScores(random(), conjunction, searcher);
    }
  }

  public void testToParentBlockJoinConjunctionTopScores() throws IOException {
    for (ScoreMode scoreMode : ScoreMode.values()) {
      Query toParent =
          new ToParentBlockJoinQuery(
              new TermQuery(new Term(CHILD_VALUE_FIELD, "1")), parentsFilter, scoreMode);
      Query conjunction =
          new BooleanQuery.Builder()
              .add(toParent, Occur.MUST)
              .add(new TermQuery(new Term(PARENT_TEXT_FIELD, "x")), Occur.MUST)
              .build();
      CheckHits.checkTopScores(random(), conjunction, searcher);
    }
  }
}
