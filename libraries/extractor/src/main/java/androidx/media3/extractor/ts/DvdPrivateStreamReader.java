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
package androidx.media3.extractor.ts;

import android.util.SparseArray;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.ParserException;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.ts.TsPayloadReader.TrackIdGenerator;
import java.util.Arrays;

@UnstableApi
public final class DvdPrivateStreamReader implements ElementaryStreamReader {

  private static final int AC3_MIN = 0x80;
  private static final int AC3_MAX = 0x87;
  private static final int DTS_MIN = 0x88;
  private static final int DTS_MAX = 0x8F;
  private static final int LPCM_MIN = 0xA0;
  private static final int LPCM_MAX = 0xA7;
  private static final int SUB_MIN = 0x20;
  private static final int SUB_MAX = 0x3F;
  private static final int RAW_AC3_SUB_ID = 0x0B;
  private static final int SUBP_STREAM_COUNT = SUB_MAX - SUB_MIN + 1;

  private final SparseArray<ElementaryStreamReader> subReaders = new SparseArray<>();
  private final String[] audioLanguages;
  private final String[] subpLanguages;
  private final int[] activeAudioStreams;
  private final int[] activeSubpStreams;
  @Nullable
  private final byte[] vobsubIdxBytes;

  @Nullable
  private ExtractorOutput extractorOutput;
  private int nextTrackId;

  private long timeUs;

  public DvdPrivateStreamReader(String[] audioLanguages, String[] subpLanguages, @Nullable byte[] vobsubIdxBytes, int[] activeAudioStreams, int[] activeSubpStreams) {
    this.audioLanguages = audioLanguages;
    this.subpLanguages = subpLanguages;
    this.vobsubIdxBytes = vobsubIdxBytes;
    this.activeAudioStreams = activeAudioStreams;
    this.activeSubpStreams = activeSubpStreams;
    timeUs = C.TIME_UNSET;
  }

  public DvdPrivateStreamReader(@Nullable String language) {
    this(defaultLanguageArray(language, 8), defaultLanguageArray(language, 32), null, new int[]{-1, -1, -1, -1, -1, -1, -1, -1}, filledIntArray(32, -1));
  }

  private static String[] defaultLanguageArray(@Nullable String lang, int size) {
    String[] arr = new String[size];
    Arrays.fill(arr, lang != null ? lang : "");
    return arr;
  }

  private static int[] filledIntArray(int size, int value) {
    int[] arr = new int[size];
    Arrays.fill(arr, value);
    return arr;
  }

  @Nullable
  private static String languageOrNull(String[] languages, int idx) {
    String lang = languages[idx];
    return lang.isEmpty() ? null : lang;
  }

  private static boolean isStreamExcluded(int[] activeStreams, int idx) {
    boolean anySpecified = false;
    for (int ac : activeStreams) {
      if (ac >= 0) {
        anySpecified = true;
        if (ac == idx) {
          return false;
        }
      }
    }
    return anySpecified;
  }

  @Override
  public void seek() {
    timeUs = C.TIME_UNSET;
    for (int i = 0; i < subReaders.size(); i++) {
      subReaders.valueAt(i).seek();
    }
  }

  @Override
  public void createTracks(ExtractorOutput extractorOutput, TrackIdGenerator idGenerator) {
    this.extractorOutput = extractorOutput;
    idGenerator.generateNewId();
    this.nextTrackId = idGenerator.getTrackId();
    for (int ac : activeSubpStreams) {
      if (ac < 0 || ac >= SUBP_STREAM_COUNT) {
        continue;
      }
      int subStreamId = SUB_MIN + ac;
      DvdSubtitleReader reader = new DvdSubtitleReader(languageOrNull(subpLanguages, ac), vobsubIdxBytes);
      subReaders.put(subStreamId, reader);
      TrackIdGenerator subIdGen = new TrackIdGenerator(nextTrackId, 1);
      nextTrackId++;
      reader.createTracks(extractorOutput, subIdGen);
    }
  }

  @Override
  public void packetStarted(long pesTimeUs, @TsPayloadReader.Flags int flags) {
    timeUs = pesTimeUs;
  }

  @Override
  public void consume(ParsableByteArray data) throws ParserException {
    if (extractorOutput == null || timeUs == C.TIME_UNSET) {
      return;
    }
    if (data.bytesLeft() < 1) {
      return;
    }
    int subStreamId = data.getData()[data.getPosition()] & 0xFF;
    if (subStreamId != RAW_AC3_SUB_ID) {
      data.skipBytes(1);
      if (subStreamId >= 0x80) {
        if (data.bytesLeft() < 3) {
          return;
        }
        data.skipBytes(3);
      }
    }
    int readerIndex = subReaders.indexOfKey(subStreamId);
    if (readerIndex < 0) {
      ElementaryStreamReader newReader = createReaderForSubStream(subStreamId);
      if (newReader == null) {
        return;
      }
      subReaders.put(subStreamId, newReader);
      TrackIdGenerator idGen = new TrackIdGenerator(nextTrackId, 1);
      nextTrackId++;
      newReader.createTracks(extractorOutput, idGen);
      readerIndex = subReaders.indexOfKey(subStreamId);
    }
    ElementaryStreamReader activeReader = subReaders.valueAt(readerIndex);
    activeReader.packetStarted(timeUs, TsPayloadReader.FLAG_DATA_ALIGNMENT_INDICATOR);
    activeReader.consume(data);
    activeReader.packetFinished(false);
  }

  @Override
  public void packetFinished(boolean isEndOfInput) {
  }

  @Nullable
  private ElementaryStreamReader createReaderForSubStream(int subStreamId) {
    if (subStreamId == RAW_AC3_SUB_ID) {
      return new Ac3Reader(null, 0, MimeTypes.VIDEO_PS);
    }
    if (subStreamId >= AC3_MIN && subStreamId <= AC3_MAX) {
      int idx = subStreamId - AC3_MIN;
      return isStreamExcluded(activeAudioStreams, idx) ? null : new Ac3Reader(languageOrNull(audioLanguages, idx), 0, MimeTypes.VIDEO_PS);
    }
    if (subStreamId >= DTS_MIN && subStreamId <= DTS_MAX) {
      int idx = subStreamId - DTS_MIN;
      return isStreamExcluded(activeAudioStreams, idx) ? null : new DtsReader(languageOrNull(audioLanguages, idx), 0, DtsReader.EXTSS_HEADER_SIZE_MAX, MimeTypes.VIDEO_PS);
    }
    if (subStreamId >= LPCM_MIN && subStreamId <= LPCM_MAX) {
      int idx = subStreamId - LPCM_MIN;
      return isStreamExcluded(activeAudioStreams, idx) ? null : new DvdLpcmReader(languageOrNull(audioLanguages, idx));
    }
    if (subStreamId >= SUB_MIN && subStreamId <= SUB_MAX) {
      int idx = subStreamId - SUB_MIN;
      return isStreamExcluded(activeSubpStreams, idx) ? null : new DvdSubtitleReader(languageOrNull(subpLanguages, idx), vobsubIdxBytes);
    }
    return null;
  }
}
