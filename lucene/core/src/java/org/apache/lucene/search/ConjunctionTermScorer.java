package org.apache.lucene.search;

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

import org.apache.lucene.index.DocsEnum;
import org.apache.lucene.search.TermQuery.TermDocsEnumFactory;
import org.apache.lucene.search.TermScorer.TermPositions;
import org.apache.lucene.search.positions.ConjunctionPositionIterator;
import org.apache.lucene.search.positions.PositionIntervalIterator;
import org.apache.lucene.search.similarities.Similarity.ExactSimScorer;
import org.apache.lucene.util.ArrayUtil;

import java.io.IOException;
import java.util.Comparator;


/** Scorer for conjunctions, sets of terms, all of which are required. */
class ConjunctionTermScorer extends Scorer {
  protected final float coord;
  protected int lastDoc = -1;
  protected final DocsAndFreqs[] docsAndFreqs;
  private final DocsAndFreqs lead;
  private DocsAndFreqs[] origDocsAndFreqs;

  ConjunctionTermScorer(Weight weight, float coord,
      DocsAndFreqs[] docsAndFreqs) {
    super(weight);
    this.coord = coord;
    this.docsAndFreqs = docsAndFreqs;
    this.origDocsAndFreqs = new DocsAndFreqs[docsAndFreqs.length];
    System.arraycopy(docsAndFreqs, 0,origDocsAndFreqs, 0, docsAndFreqs.length);
    // Sort the array the first time to allow the least frequent DocsEnum to
    // lead the matching.
    ArrayUtil.mergeSort(docsAndFreqs, new Comparator<DocsAndFreqs>() {
      public int compare(DocsAndFreqs o1, DocsAndFreqs o2) {
        return o1.docFreq - o2.docFreq;
      }
    });

    lead = docsAndFreqs[0]; // least frequent DocsEnum leads the intersection
  }

  private int doNext(int doc) throws IOException {
    do {
      if (lead.doc == DocIdSetIterator.NO_MORE_DOCS) {
        return NO_MORE_DOCS;
      }
      advanceHead: do {
        for (int i = 1; i < docsAndFreqs.length; i++) {
          if (docsAndFreqs[i].doc < doc) {
            docsAndFreqs[i].doc = docsAndFreqs[i].docs.advance(doc);
          }
          if (docsAndFreqs[i].doc > doc) {
            // DocsEnum beyond the current doc - break and advance lead
            break advanceHead;
          }
        }
        // success - all DocsEnums are on the same doc
        return doc;
      } while (true);
      // advance head for next iteration
      doc = lead.doc = lead.docs.nextDoc();  
    } while (true);
  }

  @Override
  public int advance(int target) throws IOException {
    lead.doc = lead.docs.advance(target);
    return lastDoc = doNext(lead.doc);
  }

  @Override
  public int docID() {
    return lastDoc;
  }

  @Override
  public int nextDoc() throws IOException {
    lead.doc = lead.docs.nextDoc();
    return lastDoc = doNext(lead.doc);
  }

  @Override
  public float score() throws IOException {
    float sum = 0.0f;
    for (DocsAndFreqs docs : docsAndFreqs) {
      sum += docs.docScorer.score(lastDoc, docs.docs.freq());
    }
    return sum * coord;
  }

  static final class DocsAndFreqs {
    final DocsEnum docsAndFreqs;
    final DocsEnum docs;
    final int docFreq;
    final ExactSimScorer docScorer;
    int doc = -1;
    private final TermDocsEnumFactory factory;

    DocsAndFreqs( int docFreq, ExactSimScorer docScorer, TermDocsEnumFactory factory) throws IOException {
      this.docsAndFreqs = factory.docsAndFreqsEnum();
      this.docs = factory.docsEnum();
      this.docFreq = docFreq;
      this.docScorer = docScorer;
      this.factory = factory;
    }
  }

  @Override
  public PositionIntervalIterator positions(boolean needsPayloads, boolean needsOffsets) throws IOException {
    TermPositions[] positionIters = new TermPositions[origDocsAndFreqs.length];
    for (int i = 0; i < positionIters.length; i++) {
      DocsAndFreqs d = origDocsAndFreqs[i];
      positionIters[i] = new TermPositions(this, d.factory.docsAndPositionsEnum(needsOffsets), needsPayloads);
    }
    return new ConjunctionPositionIterator(this, positionIters);
  }

}
