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
import androidx.media3.common.CacheDataReader;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaTitle;
import androidx.media3.common.MimeTypes;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.IsoDataSource;
import androidx.media3.exoplayer.source.ConcatenatingMediaSource2;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.extractor.dsd.DsdExtractor;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.iso.sacd.SacdArea;
import androidx.media3.extractor.iso.sacd.SacdStructure;
import androidx.media3.extractor.iso.sacd.SacdTocParser;
import androidx.media3.extractor.iso.sacd.SacdTrack;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

final class SacdSourceHelper {

  static SacdStructure parseStructure(CacheDataReader isoReader) throws IOException {
    return SacdTocParser.parse(isoReader);
  }

  static MediaSource buildSource(MediaItem mediaItem, DataSource.Factory dataSourceFactory, Uri isoUri, int titleIndex, SacdStructure sacd) throws IOException {
    SacdArea area = selectArea(sacd, titleIndex);
    ConcatenatingMediaSource2.Builder builder = new ConcatenatingMediaSource2.Builder();
    builder.setMediaItem(mediaItem);
    boolean anyAdded = false;
    for (SacdTrack track : area.tracks) {
      long durationUs = track.getDurationUs();
      if (durationUs <= 0) {
        continue;
      }
      long byteOffset = area.trackStartByteOffset(track);
      long byteLength = area.trackByteLength(track);
      final int channelCount = area.channelCount;
      final String mimeType = (area.audioEncoding == SacdArea.ENCODING_DST) ? MimeTypes.AUDIO_DST : MimeTypes.AUDIO_DSD;
      final long trackLengthLsn = track.lengthLsn;
      IsoDataSource.Factory clipFactory = new IsoDataSource.Factory(dataSourceFactory, byteOffset, byteLength, false, false);
      ProgressiveMediaSource.Factory pmFactory = new ProgressiveMediaSource.Factory(clipFactory, () -> new Extractor[]{new DsdExtractor(channelCount, durationUs, mimeType, trackLengthLsn)});
      MediaItem trackItem = new MediaItem.Builder().setUri(isoUri).setCustomCacheKey("sacd_t" + track.trackNumber + "_" + byteOffset).build();
      builder.add(pmFactory.createMediaSource(trackItem), durationUs / 1000);
      anyAdded = true;
    }
    if (!anyAdded) {
      throw new IOException("SACD: no playable tracks found in " + (area.type == SacdArea.TYPE_STEREO ? "stereo" : "multi-channel") + " area");
    }
    return builder.build();
  }

  static List<MediaTitle> buildTitles(SacdStructure sacd) {
    List<MediaTitle> titles = new ArrayList<>(2);
    if (sacd.stereoArea != null) {
      long durationUs = totalDuration(sacd.stereoArea);
      titles.add(new MediaTitle(0, durationUs, sacd.stereoArea.tracks.size(), sacd.multiArea == null, MediaTitle.TYPE_SACD, "Stereo", sacd.multiArea == null));
    }
    if (sacd.multiArea != null) {
      long durationUs = totalDuration(sacd.multiArea);
      titles.add(new MediaTitle(1, durationUs, sacd.multiArea.tracks.size(), true, MediaTitle.TYPE_SACD, "Multi-channel", true));
    }
    return titles;
  }

  private static SacdArea selectArea(SacdStructure sacd, int titleIndex) throws IOException {
    @Nullable SacdArea area;
    if (titleIndex == 0) {
      area = sacd.stereoArea != null ? sacd.stereoArea : sacd.multiArea;
    } else if (titleIndex == 1) {
      area = sacd.multiArea != null ? sacd.multiArea : sacd.stereoArea;
    } else {
      area = sacd.getPreferredArea();
    }
    if (area == null) {
      throw new IOException("SACD: no audio area found (titleIndex=" + titleIndex + ")");
    }
    return area;
  }

  private static long totalDuration(SacdArea area) {
    long total = 0L;
    for (SacdTrack t : area.tracks) {
      total += t.getDurationUs();
    }
    return total;
  }
}
