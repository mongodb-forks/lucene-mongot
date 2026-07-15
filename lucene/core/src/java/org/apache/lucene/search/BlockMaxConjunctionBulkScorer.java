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
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import org.apache.lucene.search.Weight.DefaultBulkScorer;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.MathUtil;

/**
 * BulkScorer implementation of {@link BlockMaxConjunctionScorer} that focuses on top-level
 * conjunctions over clauses that do not have two-phase iterators. Use a {@link DefaultBulkScorer}
 * around a {@link BlockMaxConjunctionScorer} if you need two-phase support. Another difference with
 * {@link BlockMaxConjunctionScorer} is that this scorer computes scores on the fly in order to be
 * able to skip evaluating more clauses if the total score would be under the minimum competitive
 * score anyway. This generally works well because computing a score is cheaper than decoding a
 * block of postings.
 */
final class BlockMaxConjunctionBulkScorer extends BulkScorer {

  private static final int MAX_WINDOW_SIZE = 65536;

  private final Scorer[] scorers;
  private final Scorable[] scorables;
  private final DocIdSetIterator[] iterators;
  private final DocIdSetIterator lead;
  private final SimpleScorable scorable = new SimpleScorable();
  private final double[] sumOfOtherClauses;
  private final int maxDoc;
  private final boolean scoreWindowDocAtATime;
  private final DocAndFloatFeatureBuffer docAndScoreBuffer = new DocAndFloatFeatureBuffer();
  private final DocAndScoreAccBuffer docAndScoreAccBuffer = new DocAndScoreAccBuffer();

  BlockMaxConjunctionBulkScorer(int maxDoc, List<Scorer> scorers) throws IOException {
    if (scorers.size() <= 1) {
      throw new IllegalArgumentException("Expected 2 or more scorers, got " + scorers.size());
    }
    this.scorers = scorers.toArray(Scorer[]::new);
    Arrays.sort(this.scorers, Comparator.comparingLong(scorer -> scorer.iterator().cost()));
    this.scorables =
        Arrays.stream(this.scorers).map(ScorerUtil::likelyTermScorer).toArray(Scorable[]::new);
    this.iterators =
        Arrays.stream(this.scorers)
            .map(Scorer::iterator)
            .map(ScorerUtil::likelyImpactsEnum)
            .toArray(DocIdSetIterator[]::new);
    lead = iterators[0];
    this.sumOfOtherClauses = new double[this.scorers.length];
    Arrays.fill(sumOfOtherClauses, Double.POSITIVE_INFINITY);
    this.maxDoc = maxDoc;
    // Most lead clauses are fastest with the score-first window path, but some (e.g. block-join
    // scorers) have an expensive per-document score and no useful impacts, so buffering and scoring
    // a whole window before applying the other clauses is pure overhead; those opt into a
    // doc-at-a-time window via Scorer#preferDocAtATimeWindowScoring. The gate is on scorers[0]
    // because that is the clause the score-first path drains via nextDocsAndScores.
    this.scoreWindowDocAtATime = this.scorers[0].preferDocAtATimeWindowScoring();
  }

  private float computeMaxScore(int windowMin, int windowMax) throws IOException {
    for (int i = 0; i < scorers.length; ++i) {
      scorers[i].advanceShallow(windowMin);
    }

    double maxWindowScore = 0;
    for (int i = 0; i < scorers.length; ++i) {
      float maxClauseScore = scorers[i].getMaxScore(windowMax);
      sumOfOtherClauses[i] = maxClauseScore;
      maxWindowScore += maxClauseScore;
    }
    for (int i = sumOfOtherClauses.length - 2; i >= 0; --i) {
      sumOfOtherClauses[i] += sumOfOtherClauses[i + 1];
    }
    return (float) maxWindowScore;
  }

  @Override
  public int score(LeafCollector collector, Bits acceptDocs, int min, int max) throws IOException {
    collector.setScorer(scorable);

    int windowMin = Math.max(lead.docID(), min);
    if (scorable.minCompetitiveScore == 0) {
      windowMin = scoreDocFirstUntilDynamicPruning(collector, acceptDocs, min, max);
    }

    while (windowMin < max) {
      // Use impacts of the least costly scorer to compute windows
      // NOTE: windowMax is inclusive
      int windowMax = Math.min(scorers[0].advanceShallow(windowMin), max - 1);
      // Ensure the scoring window not too big, this especially works for the default implementation
      // of `Scorer#advanceShallow` which may return `DocIdSetIterator#NO_MORE_DOCS`.
      windowMax = MathUtil.unsignedMin(windowMax, windowMin + MAX_WINDOW_SIZE);

      float maxWindowScore = computeMaxScore(windowMin, windowMax);
      if (scoreWindowDocAtATime) {
        scoreWindowDocAtATime(collector, acceptDocs, windowMin, windowMax + 1, maxWindowScore);
      } else {
        scoreWindowScoreFirst(collector, acceptDocs, windowMin, windowMax + 1, maxWindowScore);
      }
      windowMin = Math.max(lead.docID(), windowMax + 1);
    }

    return windowMin >= maxDoc ? DocIdSetIterator.NO_MORE_DOCS : windowMin;
  }

  /**
   * Score a window of doc IDs by first finding agreement between all iterators and only then
   * compute scores and call the collector until dynamic pruning kicks in.
   */
  private int scoreDocFirstUntilDynamicPruning(
      LeafCollector collector, Bits acceptDocs, int min, int max) throws IOException {
    int doc = lead.docID();
    if (doc < min) {
      doc = lead.advance(min);
    }

    outer:
    while (doc < max) {
      if (acceptDocs == null || acceptDocs.get(doc)) {
        for (int i = 1; i < iterators.length; ++i) {
          DocIdSetIterator iterator = iterators[i];
          int otherDoc = iterator.docID();
          if (otherDoc < doc) {
            otherDoc = iterator.advance(doc);
          }
          if (doc != otherDoc) {
            doc = lead.advance(otherDoc);
            continue outer;
          }
        }

        double score = 0;
        for (Scorable scorable : scorables) {
          score += scorable.score();
        }
        scorable.score = (float) score;
        collector.collect(doc);
        if (scorable.minCompetitiveScore > 0) {
          return lead.nextDoc();
        }
      }
      doc = lead.nextDoc();
    }
    return doc;
  }

  /**
   * Score a window of doc IDs by computing matches and scores on the lead costly clause, then
   * iterate other clauses one by one to remove documents that do not match and increase the global
   * score by the score of the current clause. This is often faster when a minimum competitive score
   * is set, as score computations can be more efficient (e.g. thanks to vectorization) and because
   * we can skip advancing other clauses if the global score so far is not high enough for a doc to
   * have a chance of being competitive.
   */
  private void scoreWindowScoreFirst(
      LeafCollector collector, Bits acceptDocs, int min, int max, float maxWindowScore)
      throws IOException {
    if (maxWindowScore < scorable.minCompetitiveScore) {
      // no hits are competitive
      return;
    }

    if (lead.docID() < min) {
      lead.advance(min);
    }
    if (lead.docID() >= max) {
      return;
    }

    for (scorers[0].nextDocsAndScores(max, acceptDocs, docAndScoreBuffer);
        docAndScoreBuffer.size > 0;
        scorers[0].nextDocsAndScores(max, acceptDocs, docAndScoreBuffer)) {

      docAndScoreAccBuffer.copyFrom(docAndScoreBuffer);

      for (int i = 1; i < scorers.length; ++i) {
        double sumOfOtherClause = sumOfOtherClauses[i];
        if (sumOfOtherClause != sumOfOtherClauses[i - 1]) {
          // two equal consecutive values mean that the first clause always returns a score of zero,
          // so we don't need to filter hits by score again.
          ScorerUtil.filterCompetitiveHits(
              docAndScoreAccBuffer, sumOfOtherClause, scorable.minCompetitiveScore, scorers.length);
        }

        ScorerUtil.applyRequiredClause(docAndScoreAccBuffer, iterators[i], scorables[i]);
      }

      for (int i = 0; i < docAndScoreAccBuffer.size; ++i) {
        scorable.score = (float) docAndScoreAccBuffer.scores[i];
        collector.collect(docAndScoreAccBuffer.docs[i]);
      }
    }

    int maxOtherDoc = -1;
    for (int i = 1; i < iterators.length; ++i) {
      maxOtherDoc = Math.max(iterators[i].docID(), maxOtherDoc);
    }
    if (lead.docID() < maxOtherDoc) {
      lead.advance(maxOtherDoc);
    }
  }

  /**
   * Score a window of doc IDs one document at a time, computing the score as more clauses match so
   * that we can skip advancing (and scoring) further clauses once the partial score can no longer
   * be competitive. This is the counterpart to {@link #scoreWindowScoreFirst} used when the lead
   * clause opts in via {@link Scorer#preferDocAtATimeWindowScoring()}, typically because it has no
   * specialized {@link Scorer#nextDocsAndScores} and an expensive per-document score, so buffering
   * and scoring the whole window up front would be pure overhead. Matches and scores are identical
   * to {@link #scoreWindowScoreFirst}.
   */
  private void scoreWindowDocAtATime(
      LeafCollector collector, Bits acceptDocs, int min, int max, float maxWindowScore)
      throws IOException {
    final DocIdSetIterator lead1 = this.lead;
    final DocIdSetIterator lead2 = this.iterators[1];
    final Scorable scorer1 = this.scorables[0];
    final Scorable scorer2 = this.scorables[1];

    if (maxWindowScore < scorable.minCompetitiveScore) {
      // no hits are competitive
      return;
    }

    if (lead1.docID() < min) {
      lead1.advance(min);
    }

    final double sumOfOtherMaxScoresAt1 = sumOfOtherClauses[1];

    advanceHead:
    for (int doc = lead1.docID(); doc < max; ) {
      if (acceptDocs != null && acceptDocs.get(doc) == false) {
        doc = lead1.nextDoc();
        continue;
      }

      // Compute the score as we find more matching clauses, in order to skip advancing other
      // clauses if the total score has no chance of being competitive. This works well because
      // computing a score is usually cheaper than decoding a full block of postings and
      // frequencies.
      final boolean hasMinCompetitiveScore = scorable.minCompetitiveScore > 0;
      double currentScore;
      if (hasMinCompetitiveScore) {
        currentScore = scorer1.score();
      } else {
        currentScore = 0;
      }

      // This is the same logic as in the below for loop, specialized for the 2nd least costly
      // clause. This seems to help the JVM.

      // First check if we have a chance of having a match based on max scores
      if (hasMinCompetitiveScore
          && (float) MathUtil.sumUpperBound(currentScore + sumOfOtherMaxScoresAt1, scorers.length)
              < scorable.minCompetitiveScore) {
        doc = lead1.nextDoc();
        continue advanceHead;
      }

      // NOTE: lead2 may be on `doc` already if we `continue`d on the previous loop iteration.
      if (lead2.docID() < doc) {
        int next = lead2.advance(doc);
        if (next != doc) {
          doc = lead1.advance(next);
          continue advanceHead;
        }
      }
      assert lead2.docID() == doc;
      if (hasMinCompetitiveScore) {
        currentScore += scorer2.score();
      }

      for (int i = 2; i < iterators.length; ++i) {
        // First check if we have a chance of having a match based on max scores
        if (hasMinCompetitiveScore
            && (float) MathUtil.sumUpperBound(currentScore + sumOfOtherClauses[i], scorers.length)
                < scorable.minCompetitiveScore) {
          doc = lead1.nextDoc();
          continue advanceHead;
        }

        // NOTE: these iterators may be on `doc` already if we called `continue advanceHead` on the
        // previous loop iteration.
        if (iterators[i].docID() < doc) {
          int next = iterators[i].advance(doc);
          if (next != doc) {
            doc = lead1.advance(next);
            continue advanceHead;
          }
        }
        assert iterators[i].docID() == doc;
        if (hasMinCompetitiveScore) {
          currentScore += scorables[i].score();
        }
      }

      if (hasMinCompetitiveScore == false) {
        for (Scorable scorer : scorables) {
          currentScore += scorer.score();
        }
      }
      scorable.score = (float) currentScore;
      collector.collect(doc);
      // The collect() call may have updated the minimum competitive score.
      if (maxWindowScore < scorable.minCompetitiveScore) {
        // no more hits are competitive
        return;
      }

      doc = lead1.nextDoc();
    }
  }

  @Override
  public long cost() {
    return lead.cost();
  }
}
