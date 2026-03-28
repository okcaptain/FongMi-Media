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

import static java.lang.Math.min;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.extractor.Ac3Util;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.TrackOutput;
import androidx.media3.extractor.TrueHdSampleRechunker;
import androidx.media3.extractor.ts.TsPayloadReader.TrackIdGenerator;
import java.util.Objects;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

@UnstableApi
public final class TrueHdReader implements ElementaryStreamReader {

  private static final int[] THD_CHAN_COUNT = {2, 1, 1, 2, 2, 2, 2, 1, 1, 2, 2, 1, 1};
  private static final int HEADER_SIZE = Ac3Util.TRUEHD_SYNCFRAME_HEADER_SIZE;

  private static final int STATE_FINDING_SYNC = 0;
  private static final int STATE_READING_HEADER = 1;
  private static final int STATE_READING_SAMPLE = 2;
  private static final int STATE_READING_NONMAJOR_PREAMBLE = 3;
  private static final int STATE_SKIPPING_NON_THD = 4;

  private static final int PREAMBLE_SIZE = 8;
  private static final int FRAME_SIZE_MASK = 0x0FFF;
  private static final int RATEBITS_MLP = 0x0F;
  private static final int SAMPLES_PER_AU = 40;
  private static final int MAJOR_SYNC_B4 = 0xF8;
  private static final int MAJOR_SYNC_B5 = 0x72;
  private static final int MAJOR_SYNC_B6 = 0x6F;
  private static final int MAJOR_SYNC_B7_TRUEHD = 0xBA;
  private static final int MAJOR_SYNC_B7_MLP = 0xBB;
  private static final int AC3_SYNC_WORD_0 = 0x0B;
  private static final int AC3_SYNC_WORD_1 = 0x77;

  @Nullable
  private final String language;
  private final String containerMimeType;
  private final byte[] syncWindow = new byte[PREAMBLE_SIZE - 1];
  private final byte[] headerScratch = new byte[HEADER_SIZE];
  private final ParsableByteArray headerArray = new ParsableByteArray(headerScratch);
  private final TrueHdSampleRechunker rechunker;

  private @MonotonicNonNull TrackOutput output;
  @Nullable
  private String formatId;
  @Nullable
  private Format format;

  private int state;
  private int bytesRead;
  private int sampleSize;
  private int skipRemaining;
  private int syncWindowFill;
  private boolean isMajorSyncSample;
  private long sampleDurationUs;
  private long timeUs;

  public TrueHdReader(@Nullable String language, String containerMimeType) {
    this.language = language;
    this.containerMimeType = containerMimeType;
    rechunker = new TrueHdSampleRechunker();
    state = STATE_FINDING_SYNC;
    timeUs = C.TIME_UNSET;
  }

  private static int channelCountFromMap(int chanmap) {
    int channels = 0;
    for (int i = 0; i < THD_CHAN_COUNT.length; i++) {
      channels += THD_CHAN_COUNT[i] * ((chanmap >> i) & 1);
    }
    return channels;
  }

  @Override
  public void seek() {
    state = STATE_FINDING_SYNC;
    timeUs = C.TIME_UNSET;
    bytesRead = 0;
    rechunker.reset();
    skipRemaining = 0;
    syncWindowFill = 0;
    isMajorSyncSample = false;
  }

  @Override
  public void createTracks(ExtractorOutput extractorOutput, TrackIdGenerator idGenerator) {
    idGenerator.generateNewId();
    formatId = idGenerator.getFormatId();
    output = extractorOutput.track(idGenerator.getTrackId(), C.TRACK_TYPE_AUDIO);
  }

  @Override
  public void packetStarted(long pesTimeUs, @TsPayloadReader.Flags int flags) {
    timeUs = pesTimeUs;
  }

  @Override
  public void consume(ParsableByteArray data) {
    if (output == null) {
      return;
    }
    while (data.bytesLeft() > 0) {
      switch (state) {
        case STATE_FINDING_SYNC:
          if (skipToMajorSync(data)) {
            bytesRead = PREAMBLE_SIZE;
            state = STATE_READING_HEADER;
          }
          break;
        case STATE_READING_HEADER:
          if (continueRead(data) && parseMajorSyncHeader()) {
            headerArray.reset(headerScratch, HEADER_SIZE);
            output.sampleData(headerArray, HEADER_SIZE);
            isMajorSyncSample = true;
            state = STATE_READING_SAMPLE;
          }
          break;
        case STATE_READING_SAMPLE:
          int bytesToRead = min(data.bytesLeft(), sampleSize - bytesRead);
          output.sampleData(data, bytesToRead);
          bytesRead += bytesToRead;
          if (bytesRead == sampleSize) {
            commitSampleToRechunker();
            bytesRead = 0;
            isMajorSyncSample = false;
            state = STATE_READING_NONMAJOR_PREAMBLE;
          }
          break;
        case STATE_READING_NONMAJOR_PREAMBLE:
          int toCopy = min(data.bytesLeft(), PREAMBLE_SIZE - bytesRead);
          data.readBytes(headerScratch, bytesRead, toCopy);
          bytesRead += toCopy;
          if (bytesRead == PREAMBLE_SIZE) {
            handleNonMajorPreamble();
          }
          break;
        case STATE_SKIPPING_NON_THD:
          int toSkip = min(data.bytesLeft(), skipRemaining);
          data.skipBytes(toSkip);
          skipRemaining -= toSkip;
          if (skipRemaining == 0) {
            bytesRead = 0;
            state = STATE_READING_NONMAJOR_PREAMBLE;
          }
          break;
        default:
          throw new IllegalStateException();
      }
    }
  }

  @Override
  public void packetFinished(boolean isEndOfInput) {
    if (isEndOfInput && output != null) {
      rechunker.outputPendingSampleMetadata(output, /* cryptoData= */ null);
    }
  }

  private void commitSampleToRechunker() {
    if (timeUs == C.TIME_UNSET) {
      return;
    }
    int flags = isMajorSyncSample ? C.BUFFER_FLAG_KEY_FRAME : 0;
    rechunker.sampleMetadata(output, timeUs, flags, sampleSize, 0, null);
    timeUs += sampleDurationUs;
  }

  private boolean continueRead(ParsableByteArray source) {
    int bytesToRead = min(source.bytesLeft(), HEADER_SIZE - bytesRead);
    source.readBytes(headerScratch, bytesRead, bytesToRead);
    bytesRead += bytesToRead;
    return bytesRead == HEADER_SIZE;
  }

  private void handleNonMajorPreamble() {
    boolean isMajorSync = (headerScratch[4] & 0xFF) == MAJOR_SYNC_B4 && (headerScratch[5] & 0xFF) == MAJOR_SYNC_B5 && (headerScratch[6] & 0xFF) == MAJOR_SYNC_B6 && ((headerScratch[7] & 0xFF) == MAJOR_SYNC_B7_TRUEHD || (headerScratch[7] & 0xFF) == MAJOR_SYNC_B7_MLP);
    if (isMajorSync) {
      state = STATE_READING_HEADER;
    } else if ((headerScratch[0] & 0xFF) == AC3_SYNC_WORD_0 && (headerScratch[1] & 0xFF) == AC3_SYNC_WORD_1) {
      int ac3Size = Ac3Util.parseAc3SyncframeSize(headerScratch);
      if (ac3Size > PREAMBLE_SIZE) {
        skipRemaining = ac3Size - PREAMBLE_SIZE;
        state = STATE_SKIPPING_NON_THD;
      } else if (ac3Size == C.LENGTH_UNSET) {
        resetToFindingSync();
      } else {
        bytesRead = 0;
      }
    } else {
      int frameSize = (((headerScratch[0] & 0xFF) << 8) | (headerScratch[1] & 0xFF)) & FRAME_SIZE_MASK;
      frameSize *= 2;
      if (frameSize < PREAMBLE_SIZE) {
        resetToFindingSync();
        return;
      }
      headerArray.reset(headerScratch, PREAMBLE_SIZE);
      output.sampleData(headerArray, PREAMBLE_SIZE);
      sampleSize = frameSize;
      state = STATE_READING_SAMPLE;
    }
  }

  private void resetToFindingSync() {
    syncWindowFill = 0;
    rechunker.outputPendingSampleMetadata(output, null);
    state = STATE_FINDING_SYNC;
  }

  private boolean skipToMajorSync(ParsableByteArray pesBuffer) {
    int available = pesBuffer.bytesLeft();
    byte[] buf = pesBuffer.getData();
    int pos = pesBuffer.getPosition();
    int totalBytes = syncWindowFill + available;
    for (int i = 0; i < totalBytes - (PREAMBLE_SIZE - 1); i++) {
      int b4 = readCombinedByte(buf, pos, i + 4) & 0xFF;
      int b5 = readCombinedByte(buf, pos, i + 5) & 0xFF;
      int b6 = readCombinedByte(buf, pos, i + 6) & 0xFF;
      int b7 = readCombinedByte(buf, pos, i + 7) & 0xFF;
      if (b4 == MAJOR_SYNC_B4 && b5 == MAJOR_SYNC_B5 && b6 == MAJOR_SYNC_B6 && (b7 == MAJOR_SYNC_B7_TRUEHD || b7 == MAJOR_SYNC_B7_MLP)) {
        for (int j = 0; j < PREAMBLE_SIZE; j++) {
          headerScratch[j] = readCombinedByte(buf, pos, i + j);
        }
        int consumedFromBuffer = (i + PREAMBLE_SIZE) - syncWindowFill;
        if (consumedFromBuffer > 0) {
          pesBuffer.skipBytes(consumedFromBuffer);
        }
        syncWindowFill = 0;
        return true;
      }
    }
    int keepBytes = min(PREAMBLE_SIZE - 1, totalBytes);
    for (int i = 0; i < keepBytes; i++) {
      syncWindow[i] = readCombinedByte(buf, pos, totalBytes - keepBytes + i);
    }
    syncWindowFill = keepBytes;
    pesBuffer.skipBytes(available);
    return false;
  }

  private byte readCombinedByte(byte[] buf, int bufBase, int idx) {
    return idx < syncWindowFill ? syncWindow[idx] : buf[bufBase + idx - syncWindowFill];
  }

  private boolean parseMajorSyncHeader() {
    int frameSize = ((((headerScratch[0] & 0xFF) << 8) | (headerScratch[1] & 0xFF)) & FRAME_SIZE_MASK) * 2;
    if ((headerScratch[7] & 0xFF) != MAJOR_SYNC_B7_TRUEHD) {
      resetToFindingSync();
      return false;
    }
    int ratebits = (headerScratch[8] & 0xFF) >> 4;
    if (ratebits == RATEBITS_MLP) {
      resetToFindingSync();
      return false;
    }
    int sampleRate = ((ratebits & 8) != 0 ? 44100 : 48000) << (ratebits & 7);
    int chanmap5 = ((headerScratch[8] & 0x0F) << 1) | ((headerScratch[9] >> 7) & 1);
    int chanmap13 = ((headerScratch[9] & 0x7F) << 6) | ((headerScratch[10] >> 2) & 0x3F);
    int channelCount = channelCountFromMap(chanmap13 != 0 ? chanmap13 : chanmap5);
    if (channelCount <= 0) {
      channelCount = 2;
    }
    @Nullable String codecs = Ac3Util.isTrueHdAtmos(headerScratch) ? "atmos" : null;
    int peakDataRate = ((headerScratch[18] & 0x7F) << 8) | (headerScratch[19] & 0xFF);
    int peakBitrate = (int) Math.min(((long) peakDataRate * sampleRate) >> 4, Integer.MAX_VALUE);
    if (format == null
        || format.channelCount != channelCount
        || format.sampleRate != sampleRate
        || format.peakBitrate != peakBitrate
        || !Objects.equals(format.codecs, codecs)) {
      format = new Format.Builder()
          .setId(formatId)
          .setContainerMimeType(containerMimeType)
          .setSampleMimeType(MimeTypes.AUDIO_TRUEHD)
          .setCodecs(codecs)
          .setChannelCount(channelCount)
          .setSampleRate(sampleRate)
          .setPeakBitrate(peakBitrate)
          .setLanguage(language)
          .build();
      output.format(format);
    }
    sampleSize = frameSize;
    sampleDurationUs = sampleRate > 0 ? C.MICROS_PER_SECOND * (SAMPLES_PER_AU << (ratebits & 7)) / sampleRate : 0;
    rechunker.markSyncframeFound();
    return true;
  }
}
