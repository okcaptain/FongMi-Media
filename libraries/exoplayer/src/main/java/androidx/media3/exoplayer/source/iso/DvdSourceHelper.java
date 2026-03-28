/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.exoplayer.source.iso;

import android.net.Uri;
import androidx.media3.common.CacheDataReader;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.TimestampAdjuster;
import androidx.media3.common.util.Util;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.IsoDataSource;
import androidx.media3.exoplayer.source.ConcatenatingMediaSource2;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.IndexSeekMap;
import androidx.media3.extractor.iso.IsoConstants;
import androidx.media3.extractor.iso.dvd.DvdCell;
import androidx.media3.extractor.iso.dvd.DvdIfoParser;
import androidx.media3.extractor.iso.dvd.DvdStructure;
import androidx.media3.extractor.iso.dvd.DvdTitle;
import androidx.media3.extractor.iso.udf.UdfFileSystem;
import androidx.media3.extractor.ts.DvdPrivateStreamReader;
import androidx.media3.extractor.ts.PsExtractor;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

final class DvdSourceHelper {

  static DvdStructure parseStructure(CacheDataReader isoReader, UdfFileSystem udf) throws IOException {
    return new DvdIfoParser(isoReader, udf).parse();
  }

  static MediaSource buildSource(MediaItem mediaItem, DataSource.Factory dataSourceFactory, Uri isoUri, CacheDataReader isoReader, UdfFileSystem udf, int titleIndex) throws IOException {
    return buildSource(mediaItem, dataSourceFactory, isoUri, isoReader, udf, titleIndex, parseStructure(isoReader, udf));
  }

  static MediaSource buildSource(MediaItem mediaItem, DataSource.Factory dataSourceFactory, Uri isoUri, CacheDataReader isoReader, UdfFileSystem udf, int titleIndex, DvdStructure dvd) throws IOException {
    DvdTitle main;
    if (titleIndex >= 0 && titleIndex < dvd.titles.size()) {
      main = dvd.titles.get(titleIndex);
    } else {
      main = dvd.mainTitle;
    }
    byte[] vobsubIdxBytes = main.vobsubIdx != null ? Util.getUtf8Bytes(main.vobsubIdx) : null;
    DvdPrivateStreamReader privateStreamReader = new DvdPrivateStreamReader(
        main.audioLanguages,
        main.subpLanguages,
        vobsubIdxBytes,
        main.activeAudioStreams,
        main.activeSubpStreams);
    List<IndexSeekMap> cellSeekMaps = buildCellSeekMaps(main);
    ConcatenatingMediaSource2.Builder builder = new ConcatenatingMediaSource2.Builder();
    builder.setMediaItem(mediaItem);
    boolean anyAdded = false;
    int cellIndex = 0;
    for (DvdCell cell : main.cells) {
      long durationMs = cell.durationUs / 1000;
      if (durationMs <= 0) {
        cellIndex++;
        continue;
      }
      final IndexSeekMap seekMap = (cellIndex < cellSeekMaps.size()) ? cellSeekMaps.get(cellIndex) : null;
      IsoDataSource.Factory clipFactory = new IsoDataSource.Factory(dataSourceFactory, cell.byteOffset, cell.length, false);
      final DvdPrivateStreamReader psr = privateStreamReader;
      ProgressiveMediaSource.Factory psFactory = new ProgressiveMediaSource.Factory(clipFactory, () -> new Extractor[]{new PsExtractor(new TimestampAdjuster(0), psr, seekMap)});
      MediaItem cellItem = new MediaItem.Builder().setUri(isoUri).setCustomCacheKey("cell_" + cell.byteOffset).build();
      builder.add(psFactory.createMediaSource(cellItem), durationMs);
      anyAdded = true;
      cellIndex++;
    }
    if (!anyAdded) {
      throw new IOException("DVD: no playable cells found");
    }
    return builder.build();
  }

  private static List<IndexSeekMap> buildCellSeekMaps(DvdTitle title) {
    long[] vobuSectors = title.vobuSectors;
    List<IndexSeekMap> result = new ArrayList<>(title.cells.size());
    for (DvdCell cell : title.cells) {
      if (vobuSectors.length == 0 || cell.lastSector <= cell.firstSector || cell.durationUs <= 0) {
        result.add(null);
        continue;
      }
      long sectorSpan = cell.lastSector - cell.firstSector + 1;
      int startIdx = binarySearchFirstGe(vobuSectors, cell.firstSector);
      int endIdx = startIdx;
      while (endIdx < vobuSectors.length && vobuSectors[endIdx] <= cell.lastSector) {
        endIdx++;
      }
      int count = endIdx - startIdx;
      if (count == 0) {
        result.add(null);
        continue;
      }
      long[] posArray = new long[count];
      long[] timeArray = new long[count];
      for (int v = 0; v < count; v++) {
        long relSector = vobuSectors[startIdx + v] - cell.firstSector;
        posArray[v] = relSector * IsoConstants.SECTOR_SIZE;
        timeArray[v] = relSector * cell.durationUs / sectorSpan;
      }
      result.add(new IndexSeekMap(posArray, timeArray, cell.durationUs));
    }
    return result;
  }

  private static int binarySearchFirstGe(long[] arr, long target) {
    int lo = 0, hi = arr.length;
    while (lo < hi) {
      int mid = (lo + hi) >>> 1;
      if (arr[mid] < target) {
        lo = mid + 1;
      } else {
        hi = mid;
      }
    }
    return lo;
  }
}
