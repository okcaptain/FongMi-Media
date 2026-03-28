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
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.CacheDataReader;
import androidx.media3.common.MediaItem;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.IsoDataSource;
import androidx.media3.exoplayer.source.ConcatenatingMediaSource2;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.iso.IsoFileEntry;
import androidx.media3.extractor.iso.bdmv.BdmvConstants;
import androidx.media3.extractor.iso.bdmv.BdmvStructure;
import androidx.media3.extractor.iso.bdmv.BdmvTsExtractor;
import androidx.media3.extractor.iso.bdmv.ClpiInfo;
import androidx.media3.extractor.iso.bdmv.ClpiParser;
import androidx.media3.extractor.iso.bdmv.EpMapEntry;
import androidx.media3.extractor.iso.bdmv.MplsParser;
import androidx.media3.extractor.iso.bdmv.PlayItem;
import androidx.media3.extractor.iso.bdmv.Playlist;
import androidx.media3.extractor.iso.bdmv.StreamInfo;
import androidx.media3.common.util.Util;
import androidx.media3.extractor.iso.udf.UdfFileSystem;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class BdmvSourceHelper {

  static final int PLAYLIST_DURATION_SCORE_BITS = 40;
  private static final int MAX_CLIP_REPEAT_COUNT = 2;
  private static final int MAX_ENTRY_READ_BYTES = 8 * 1024 * 1024;
  private static final long BATCH_PREFETCH_MAX_SPAN = 4L * 1024 * 1024;

  static MediaSource buildSource(MediaItem mediaItem, DataSource.Factory dataSourceFactory, Uri isoUri, UdfFileSystem udf, CacheDataReader isoReader, int titleIndex) throws IOException {
    return buildSourceFromStructure(mediaItem, dataSourceFactory, isoUri, udf, isoReader, titleIndex, parseBdmv(isoReader, udf));
  }

  static MediaSource buildSourceFromStructure(MediaItem mediaItem, DataSource.Factory dataSourceFactory, Uri isoUri, UdfFileSystem udf, CacheDataReader isoReader, int titleIndex, BdmvStructure bdmv) throws IOException {
    Playlist main;
    if (titleIndex >= 0 && titleIndex < bdmv.allPlaylists.size()) {
      main = bdmv.allPlaylists.get(titleIndex);
    } else {
      main = bdmv.mainPlaylist;
    }
    Map<String, ClpiInfo> clipInfos = bdmv.clipInfos;
    if (main != bdmv.mainPlaylist) {
      clipInfos = new HashMap<>(clipInfos);
      clipInfos.putAll(loadClipInfos(isoReader, udf, main, clipInfos));
    }
    ConcatenatingMediaSource2.Builder builder = new ConcatenatingMediaSource2.Builder();
    builder.setMediaItem(mediaItem);
    boolean anyAdded = false;
    long cumulativeOffsetUs = 0L;
    for (PlayItem item : main.playItems) {
      ClpiInfo clipInfo = clipInfos.get(item.clipName);
      long durationUs = item.getDurationUs();
      if (durationUs <= 0 && clipInfo != null) {
        durationUs = clipInfo.getDurationUs();
      }
      if (durationUs <= 0) {
        continue;
      }
      IsoFileEntry m2tsEntry = findM2ts(udf, item.clipName);
      if (m2tsEntry == null) {
        continue;
      }
      long startM2ts = 0;
      long endM2ts = m2tsEntry.length;
      if (clipInfo != null && !clipInfo.epMap.isEmpty()) {
        startM2ts = epMapByteOffset(clipInfo.epMap, item.inTimeTicks, true);
        endM2ts = epMapByteOffset(clipInfo.epMap, item.outTimeTicks, false);
        if (endM2ts == Long.MAX_VALUE || endM2ts <= startM2ts) {
          endM2ts = m2tsEntry.length;
        }
      }
      startM2ts = (startM2ts / BdmvConstants.M2TS_PACKET_SIZE) * BdmvConstants.M2TS_PACKET_SIZE;
      endM2ts = Math.min(m2tsEntry.length, Util.ceilDivide(endM2ts, BdmvConstants.M2TS_PACKET_SIZE) * BdmvConstants.M2TS_PACKET_SIZE);
      long clipByteOffset = m2tsEntry.byteOffset + startM2ts;
      long clipByteLength = endM2ts - startM2ts;
      IsoDataSource.Factory clipFactory = new IsoDataSource.Factory(dataSourceFactory, clipByteOffset, clipByteLength, true);
      final long capturedStartM2ts = startM2ts;
      final long capturedInTimeTicks = item.inTimeTicks;
      final long capturedDurationUs = durationUs;
      final long capturedCumulativeOffsetUs = cumulativeOffsetUs;
      ExtractorsFactory bdmvFactory = () -> new Extractor[]{new BdmvTsExtractor(clipInfo, capturedStartM2ts, capturedInTimeTicks, capturedDurationUs, capturedCumulativeOffsetUs)};
      ProgressiveMediaSource.Factory psFactory = new ProgressiveMediaSource.Factory(clipFactory, bdmvFactory);
      MediaItem clipItem = new MediaItem.Builder().setUri(isoUri).setCustomCacheKey(item.clipName + "_" + startM2ts).build();
      builder.add(psFactory.createMediaSource(clipItem), durationUs / C.MILLIS_PER_SECOND);
      anyAdded = true;
      cumulativeOffsetUs += durationUs;
    }
    if (!anyAdded) {
      throw new IOException("Blu-ray: no playable M2TS clips found");
    }
    return builder.build();
  }

  static BdmvStructure parseBdmv(CacheDataReader isoReader, UdfFileSystem udf) throws IOException {
    Map<String, ClpiInfo> clpiCache = new HashMap<>();
    List<Playlist> sortedPlaylists = collectSortedPlaylists(isoReader, udf, clpiCache);
    if (sortedPlaylists.isEmpty()) {
      throw new IOException("Blu-ray: no playable MPLS playlist found");
    }
    Playlist mainPlaylist = sortedPlaylists.get(0);
    Map<String, ClpiInfo> clipInfos = loadClipInfos(isoReader, udf, mainPlaylist, clpiCache);
    return new BdmvStructure(mainPlaylist, sortedPlaylists, clipInfos);
  }

  static List<IsoFileEntry> listMplsEntries(UdfFileSystem udf) throws IOException {
    List<String> mplsFiles = udf.listFiles("BDMV/PLAYLIST");
    List<IsoFileEntry> mplsEntries = new ArrayList<>();
    for (String mplsName : mplsFiles) {
      if (!mplsName.toLowerCase(Locale.US).endsWith(".mpls")) {
        continue;
      }
      IsoFileEntry entry = udf.findFile("BDMV/PLAYLIST/" + mplsName);
      if (entry != null) {
        mplsEntries.add(entry);
      }
    }
    return mplsEntries;
  }

  private static List<Playlist> collectSortedPlaylists(CacheDataReader isoReader, UdfFileSystem udf, Map<String, ClpiInfo> clpiCache) throws IOException {
    List<IsoFileEntry> mplsEntries = listMplsEntries(udf);
    batchPrefetch(isoReader, mplsEntries);
    List<ScoredPlaylist> scored = new ArrayList<>();
    for (IsoFileEntry mplsEntry : mplsEntries) {
      try {
        String mplsName = mplsEntry.name;
        String name = mplsName.contains(".") ? mplsName.substring(0, mplsName.lastIndexOf('.')) : mplsName;
        Playlist playlist = MplsParser.parse(readEntry(isoReader, mplsEntry), name);
        long totalDurationUs = 0;
        for (PlayItem item : playlist.playItems) {
          totalDurationUs += item.getDurationUs();
        }
        if (totalDurationUs <= 0) {
          continue;
        }
        if (hasExcessiveRepeats(playlist.playItems)) {
          continue;
        }
        int videoTypeRank = 0;
        if (!playlist.playItems.isEmpty()) {
          String firstClip = playlist.playItems.get(0).clipName;
          ClpiInfo ci = getOrLoadClpi(isoReader, udf, firstClip, clpiCache);
          if (ci != null) {
            for (StreamInfo s : ci.streams) {
              int rank = videoTypeRank(s.streamType);
              if (rank > videoTypeRank) {
                videoTypeRank = rank;
              }
            }
          }
        }
        long durationBits = Math.min(totalDurationUs, (1L << PLAYLIST_DURATION_SCORE_BITS) - 1);
        long score = ((long) videoTypeRank << PLAYLIST_DURATION_SCORE_BITS) | durationBits;
        scored.add(new ScoredPlaylist(playlist, score));
      } catch (IOException ignored) {
      }
    }
    Collections.sort(scored, (a, b) -> Long.compare(b.score, a.score));
    List<Playlist> result = new ArrayList<>(scored.size());
    for (ScoredPlaylist sp : scored) {
      result.add(sp.playlist);
    }
    return result;
  }

  private static final class ScoredPlaylist {

    final Playlist playlist;
    final long score;

    ScoredPlaylist(Playlist playlist, long score) {
      this.playlist = playlist;
      this.score = score;
    }
  }

  private static Map<String, ClpiInfo> loadClipInfos(CacheDataReader isoReader, UdfFileSystem udf, Playlist playlist, Map<String, ClpiInfo> clpiCache) {
    List<IsoFileEntry> toPrefetch = new ArrayList<>();
    Map<String, IsoFileEntry> clipEntries = new HashMap<>();
    for (PlayItem item : playlist.playItems) {
      if (clpiCache.containsKey(item.clipName)) {
        continue;
      }
      try {
        IsoFileEntry clpiEntry = findClpi(udf, item.clipName);
        if (clpiEntry != null) {
          clipEntries.put(item.clipName, clpiEntry);
          toPrefetch.add(clpiEntry);
        }
      } catch (IOException ignored) {
      }
    }
    batchPrefetch(isoReader, toPrefetch);
    Map<String, ClpiInfo> clipInfos = new HashMap<>(clpiCache);
    for (PlayItem item : playlist.playItems) {
      if (clipInfos.containsKey(item.clipName)) {
        continue;
      }
      IsoFileEntry clpiEntry = clipEntries.get(item.clipName);
      if (clpiEntry == null) {
        continue;
      }
      try {
        clipInfos.put(item.clipName, ClpiParser.parse(readEntry(isoReader, clpiEntry), item.clipName));
      } catch (IOException ignored) {
      }
    }
    return clipInfos;
  }

  private static boolean hasExcessiveRepeats(List<PlayItem> playItems) {
    Map<String, Integer> counts = new HashMap<>();
    for (PlayItem item : playItems) {
      String key = item.clipName + ":" + item.inTimeTicks + ":" + item.outTimeTicks;
      int count = counts.containsKey(key) ? counts.get(key) + 1 : 1;
      if (count > MAX_CLIP_REPEAT_COUNT) {
        return true;
      }
      counts.put(key, count);
    }
    return false;
  }

  static int videoTypeRank(int streamType) {
    switch (streamType) {
      case BdmvConstants.STREAM_TYPE_HEVC:
        return 4;
      case BdmvConstants.STREAM_TYPE_AVC:
        return 3;
      case BdmvConstants.STREAM_TYPE_VC1:
        return 2;
      case BdmvConstants.STREAM_TYPE_MPEG2:
        return 1;
      default:
        return 0;
    }
  }

  private static long epMapByteOffset(List<EpMapEntry> epMap, long targetTicks, boolean floor) {
    int lo = 0, hi = epMap.size() - 1, best = -1;
    if (floor) {
      while (lo <= hi) {
        int mid = (lo + hi) >>> 1;
        if (epMap.get(mid).pts <= targetTicks) {
          best = mid;
          lo = mid + 1;
        } else {
          hi = mid - 1;
        }
      }
      return best >= 0 ? epMap.get(best).byteOffset : 0L;
    } else {
      while (lo <= hi) {
        int mid = (lo + hi) >>> 1;
        if (epMap.get(mid).pts > targetTicks) {
          best = mid;
          hi = mid - 1;
        } else {
          lo = mid + 1;
        }
      }
      return best >= 0 ? epMap.get(best).byteOffset : Long.MAX_VALUE;
    }
  }

  @Nullable
  private static IsoFileEntry findM2ts(UdfFileSystem udf, String clipName) throws IOException {
    return udf.findFile("BDMV/STREAM/" + clipName + ".m2ts");
  }

  @Nullable
  static IsoFileEntry findClpi(UdfFileSystem udf, String clipName) throws IOException {
    return udf.findFile("BDMV/CLIPINF/" + clipName + ".clpi");
  }

  static byte[] readEntry(CacheDataReader isoReader, IsoFileEntry entry) throws IOException {
    int maxRead = (int) Math.min(entry.length, MAX_ENTRY_READ_BYTES);
    byte[] data = new byte[maxRead];
    int pos = 0;
    while (pos < maxRead) {
      int read = isoReader.read(entry.byteOffset + pos, data, pos, maxRead - pos);
      if (read <= 0) {
        break;
      }
      pos += read;
    }
    return pos < data.length ? Arrays.copyOf(data, pos) : data;
  }

  @Nullable
  private static ClpiInfo getOrLoadClpi(CacheDataReader isoReader, UdfFileSystem udf, String clipName, Map<String, ClpiInfo> clpiCache) {
    ClpiInfo cached = clpiCache.get(clipName);
    if (cached != null) {
      return cached;
    }
    try {
      IsoFileEntry clpiEntry = findClpi(udf, clipName);
      if (clpiEntry == null) {
        return null;
      }
      ClpiInfo ci = ClpiParser.parse(readEntry(isoReader, clpiEntry), clipName);
      clpiCache.put(clipName, ci);
      return ci;
    } catch (IOException e) {
      return null;
    }
  }

  private static void batchPrefetch(CacheDataReader isoReader, List<IsoFileEntry> entries) {
    if (entries.isEmpty()) {
      return;
    }
    long minOffset = Long.MAX_VALUE;
    long maxEnd = 0;
    for (IsoFileEntry e : entries) {
      if (e.byteOffset < minOffset) {
        minOffset = e.byteOffset;
      }
      long end = e.byteOffset + e.length;
      if (end > maxEnd) {
        maxEnd = end;
      }
    }
    long span = maxEnd - minOffset;
    if (span > 0 && span <= BATCH_PREFETCH_MAX_SPAN) {
      isoReader.prefetchRange(minOffset, maxEnd);
    } else {
      for (IsoFileEntry e : entries) {
        isoReader.prefetchRange(e.byteOffset, e.byteOffset + e.length);
      }
    }
  }
}
