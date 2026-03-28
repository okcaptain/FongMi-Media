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
package androidx.media3.extractor.iso.bdmv;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.util.TimestampAdjuster;
import androidx.media3.common.util.Util;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.ForwardingExtractorOutput;
import androidx.media3.extractor.IndexSeekMap;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.SeekMap;
import androidx.media3.extractor.text.SubtitleParser;
import androidx.media3.extractor.ts.TsExtractor;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

public final class BdmvTsExtractor implements Extractor {

  private final TsExtractor delegate;
  @Nullable
  private final IndexSeekMap epSeekMap;
  private final BdmvTsPayloadReaderFactory payloadReaderFactory;
  private final int hdrDynamicRangeType;

  public BdmvTsExtractor(@Nullable ClpiInfo clipInfo, long startM2ts, long inTimeTicks, long durationUs, long cumulativeOffsetUs) {
    payloadReaderFactory = new BdmvTsPayloadReaderFactory(clipInfo);
    delegate = new TsExtractor(TsExtractor.MODE_SINGLE_PMT, TsExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA | TsExtractor.FLAG_IGNORE_SECTION_CRC, SubtitleParser.Factory.UNSUPPORTED, new TimestampAdjuster(cumulativeOffsetUs), payloadReaderFactory, TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES);
    epSeekMap = buildEpSeekMap(clipInfo, startM2ts, inTimeTicks, durationUs);
    if (epSeekMap != null) {
      delegate.disableBinarySearchSeeking();
    }
    hdrDynamicRangeType = findHdrDynamicRangeType(clipInfo);
  }

  private static int findHdrDynamicRangeType(@Nullable ClpiInfo clipInfo) {
    if (clipInfo == null) {
      return 0;
    }
    int hdrType = 0;
    for (StreamInfo s : clipInfo.streams) {
      if (BdmvConstants.isVideoStreamType(s.streamType) && s.dynamicRangeType > 0) {
        if (s.dynamicRangeType == BdmvConstants.DYNAMIC_RANGE_DOLBY_VISION) {
          return s.dynamicRangeType;
        }
        if (hdrType == 0) {
          hdrType = s.dynamicRangeType;
        }
      }
    }
    return hdrType;
  }

  @Nullable
  private static IndexSeekMap buildEpSeekMap(@Nullable ClpiInfo clipInfo, long startM2ts, long inTimeTicks, long durationUs) {
    if (clipInfo == null || clipInfo.epMap.isEmpty()) {
      return null;
    }
    List<EpMapEntry> epMap = clipInfo.epMap;
    int n = epMap.size();
    long[] positions = new long[n];
    long[] timesUs = new long[n];
    int count = 0;
    long[] cumulativeStcOffsetUs = buildCumulativeStcOffsets(clipInfo);
    long inTimeOffsetUs = getInTimeOffsetUs(clipInfo, inTimeTicks, cumulativeStcOffsetUs);
    for (int i = 0; i < n; i++) {
      EpMapEntry entry = epMap.get(i);
      long relativeM2ts = entry.byteOffset - startM2ts;
      if (relativeM2ts < 0) {
        continue;
      }
      long virtualPos = (relativeM2ts / BdmvConstants.M2TS_PACKET_SIZE) * BdmvConstants.TS_PACKET_SIZE;
      long entrySpn = entry.byteOffset / BdmvConstants.M2TS_PACKET_SIZE;
      int stcIdx = Util.binarySearchFloor(clipInfo.spnStcStarts, entrySpn, true, true);
      long timeUs;
      if (stcIdx < clipInfo.stcPresentationStartTimes.length) {
        long stcStartTimeTicks = clipInfo.stcPresentationStartTimes[stcIdx];
        timeUs = cumulativeStcOffsetUs[stcIdx] + bdmvTicksToUs(entry.pts - stcStartTimeTicks) - inTimeOffsetUs;
      } else {
        timeUs = bdmvTicksToUs(entry.pts - inTimeTicks);
      }
      timeUs = Math.max(timeUs, 0);
      if (durationUs != C.TIME_UNSET && timeUs > durationUs) {
        break;
      }
      positions[count] = virtualPos;
      timesUs[count] = timeUs;
      count++;
    }
    if (count == 0) {
      return null;
    }
    return new IndexSeekMap(Arrays.copyOf(positions, count), Arrays.copyOf(timesUs, count), durationUs);
  }

  private static long[] buildCumulativeStcOffsets(ClpiInfo clipInfo) {
    int numStc = clipInfo.spnStcStarts.length;
    long[] offsets = new long[numStc];
    for (int stcIdx = 1; stcIdx < numStc; stcIdx++) {
      long prevDurUs = 0;
      int prev = stcIdx - 1;
      if (prev < clipInfo.stcPresentationEndTimes.length && prev < clipInfo.stcPresentationStartTimes.length) {
        prevDurUs = Math.max(0, bdmvTicksToUs(clipInfo.stcPresentationEndTimes[prev] - clipInfo.stcPresentationStartTimes[prev]));
      }
      offsets[stcIdx] = offsets[stcIdx - 1] + prevDurUs;
    }
    return offsets;
  }

  private static long getInTimeOffsetUs(@NonNull ClpiInfo clipInfo, long inTimeTicks, long[] cumulativeStcOffsetUs) {
    int inTimeStcIdx = clipInfo.stcPresentationStartTimes.length > 0 ? Util.binarySearchFloor(clipInfo.stcPresentationStartTimes, inTimeTicks, true, true) : 0;
    if (inTimeStcIdx < clipInfo.stcPresentationStartTimes.length) {
      return cumulativeStcOffsetUs[inTimeStcIdx] + bdmvTicksToUs(inTimeTicks - clipInfo.stcPresentationStartTimes[inTimeStcIdx]);
    }
    return 0;
  }

  private static long bdmvTicksToUs(long ticks) {
    return Util.scaleLargeTimestamp(ticks, C.MICROS_PER_SECOND, BdmvConstants.BDMV_TICKS_PER_SECOND);
  }

  @Override
  public boolean sniff(@NonNull ExtractorInput input) throws IOException {
    return delegate.sniff(input);
  }

  @Override
  public void init(@NonNull ExtractorOutput output) {
    ExtractorOutput hdrOutput = hdrDynamicRangeType > 0 ? new HdrUpgradeOutput(output, payloadReaderFactory) : output;
    if (epSeekMap != null) {
      delegate.init(new ForwardingExtractorOutput(hdrOutput) {
        @Override
        public void seekMap(@NonNull SeekMap seekMap) {
        }
      });
      output.seekMap(epSeekMap);
    } else {
      delegate.init(hdrOutput);
    }
  }

  @Override
  public void seek(long position, long timeUs) {
    delegate.seek(position, timeUs);
    if (epSeekMap != null) {
      delegate.enableNextVideoKeyFrame(timeUs);
    }
  }

  @Override
  public @ReadResult int read(@NonNull ExtractorInput input, @NonNull PositionHolder seekPosition) throws IOException {
    return delegate.read(input, seekPosition);
  }

  @Override
  public void release() {
    delegate.release();
  }
}
