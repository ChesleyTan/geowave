/**
 * Copyright (c) 2013-2019 Contributors to the Eclipse Foundation
 *
 * <p> See the NOTICE file distributed with this work for additional information regarding copyright
 * ownership. All rights reserved. This program and the accompanying materials are made available
 * under the terms of the Apache License, Version 2.0 which accompanies this distribution and is
 * available at http://www.apache.org/licenses/LICENSE-2.0.txt
 */
package org.locationtech.geowave.adapter.vector.index;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.locationtech.geowave.core.index.IndexUtils;
import org.locationtech.geowave.core.index.sfc.data.MultiDimensionalNumericData;
import org.locationtech.geowave.core.store.CloseableIterator;
import org.locationtech.geowave.core.store.adapter.statistics.InternalDataStatistics;
import org.locationtech.geowave.core.store.adapter.statistics.StatisticsId;
import org.locationtech.geowave.core.store.api.Index;
import org.locationtech.geowave.core.store.query.constraints.BasicQueryByClass;
import org.opengis.feature.simple.SimpleFeature;

/**
 * This Query Strategy chooses the index that satisfies the most dimensions of the underlying query
 * first and then if multiple are found it will choose the one that most closely preserves locality.
 * It won't be optimized for a single prefix query but it will choose the index with the most
 * dimensions defined, enabling more fine-grained contraints given a larger set of indexable ranges.
 */
public class ChooseHeuristicMatchIndexQueryStrategy implements IndexQueryStrategySPI {
  public static final String NAME = "Heuristic Match";

  @Override
  public String toString() {
    return NAME;
  }

  @Override
  public CloseableIterator<Index> getIndices(
      final Map<StatisticsId, InternalDataStatistics<SimpleFeature, ?, ?>> stats,
      final BasicQueryByClass query,
      final Index[] indices,
      final Map<QueryHint, Object> hints) {
    return new CloseableIterator<Index>() {
      Index nextIdx = null;
      boolean done = false;
      int i = 0;

      @Override
      public boolean hasNext() {
        double bestIndexBitsUsed = -1;
        int bestIndexDimensionCount = -1;
        Index bestIdx = null;
        while (!done && (i < indices.length)) {
          nextIdx = indices[i++];
          if (nextIdx.getIndexStrategy().getOrderedDimensionDefinitions().length == 0) {
            continue;
          }
          final List<MultiDimensionalNumericData> queryRanges = query.getIndexConstraints(nextIdx);
          final int currentDimensionCount =
              nextIdx.getIndexStrategy().getOrderedDimensionDefinitions().length;
          if (IndexUtils.isFullTableScan(queryRanges)
              || !queryRangeDimensionsMatch(currentDimensionCount, queryRanges)) {
            // keep this is as a default in case all indices
            // result in a full table scan
            if (bestIdx == null) {
              bestIdx = nextIdx;
            }
          } else {
            double currentBitsUsed = 0;

            if (currentDimensionCount >= bestIndexDimensionCount) {
              for (final MultiDimensionalNumericData qr : queryRanges) {
                final double[] dataRangePerDimension = new double[qr.getDimensionCount()];
                for (int d = 0; d < dataRangePerDimension.length; d++) {
                  dataRangePerDimension[d] =
                      qr.getMaxValuesPerDimension()[d] - qr.getMinValuesPerDimension()[d];
                }
                currentBitsUsed +=
                    IndexUtils.getDimensionalBitsUsed(
                        nextIdx.getIndexStrategy(),
                        dataRangePerDimension);
              }

              if ((currentDimensionCount > bestIndexDimensionCount)
                  || (currentBitsUsed > bestIndexBitsUsed)) {
                bestIndexBitsUsed = currentBitsUsed;
                bestIndexDimensionCount = currentDimensionCount;
                bestIdx = nextIdx;
              }
            }
          }
        }
        nextIdx = bestIdx;
        done = true;
        return nextIdx != null;
      }

      @Override
      public Index next() throws NoSuchElementException {
        if (nextIdx == null) {
          throw new NoSuchElementException();
        }
        final Index returnVal = nextIdx;
        nextIdx = null;
        return returnVal;
      }

      @Override
      public void remove() {}

      @Override
      public void close() {}
    };
  }

  private static boolean queryRangeDimensionsMatch(
      final int indexDimensions,
      final List<MultiDimensionalNumericData> queryRanges) {
    for (final MultiDimensionalNumericData qr : queryRanges) {
      if (qr.getDimensionCount() != indexDimensions) {
        return false;
      }
    }
    return true;
  }
}
