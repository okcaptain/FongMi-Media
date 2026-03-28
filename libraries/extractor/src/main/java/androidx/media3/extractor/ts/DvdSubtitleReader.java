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

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.TrackOutput;
import androidx.media3.extractor.ts.TsPayloadReader.TrackIdGenerator;
import java.util.Collections;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

@UnstableApi
public final class DvdSubtitleReader implements ElementaryStreamReader {

  private static final int MAX_SPU_SIZE = 65536;

  @Nullable
  private final String language;
  @Nullable
  private final byte[] initializationData;
  private @MonotonicNonNull TrackOutput output;

  private long timeUs;
  private long spuTimeUs;
  private byte[] spuBuffer;
  private int spuExpectedSize;
  private int spuBufferPosition;

  public DvdSubtitleReader(@Nullable String language, @Nullable byte[] initializationData) {
    this.language = language;
    this.initializationData = initializationData;
    spuExpectedSize = 0;
    spuBufferPosition = 0;
    spuBuffer = new byte[0];
    spuTimeUs = C.TIME_UNSET;
    timeUs = C.TIME_UNSET;
  }

  @Override
  public void seek() {
    spuExpectedSize = 0;
    spuBufferPosition = 0;
    timeUs = C.TIME_UNSET;
    spuTimeUs = C.TIME_UNSET;
  }

  @Override
  public void createTracks(ExtractorOutput extractorOutput, TrackIdGenerator idGenerator) {
    idGenerator.generateNewId();
    output = extractorOutput.track(idGenerator.getTrackId(), C.TRACK_TYPE_TEXT);
    Format.Builder builder = new Format.Builder()
        .setId(idGenerator.getFormatId())
        .setSampleMimeType(MimeTypes.APPLICATION_VOBSUB)
        .setLanguage(language)
        .setCueReplacementBehavior(Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE);
    if (initializationData != null) {
      builder.setInitializationData(Collections.singletonList(initializationData));
    }
    output.format(builder.build());
  }

  @Override
  public void packetStarted(long pesTimeUs, @TsPayloadReader.Flags int flags) {
    timeUs = pesTimeUs;
  }

  @Override
  public void consume(ParsableByteArray data) {
    if (output == null || timeUs == C.TIME_UNSET) {
      return;
    }
    int avail = data.bytesLeft();
    if (avail == 0) {
      return;
    }
    if (spuExpectedSize == 0) {
      if (avail < 2) {
        return;
      }
      spuExpectedSize = data.readUnsignedShort();
      if (spuExpectedSize < 4 || spuExpectedSize > MAX_SPU_SIZE) {
        spuExpectedSize = 0;
        return;
      }
      spuTimeUs = timeUs;
      spuBufferPosition = 0;
      if (spuBuffer.length < spuExpectedSize) {
        spuBuffer = new byte[spuExpectedSize];
      }
      spuBuffer[0] = (byte) ((spuExpectedSize >> 8) & 0xFF);
      spuBuffer[1] = (byte) (spuExpectedSize & 0xFF);
      spuBufferPosition = 2;
      avail = data.bytesLeft();
    }
    int needed = spuExpectedSize - spuBufferPosition;
    int toCopy = Math.min(avail, needed);
    data.readBytes(spuBuffer, spuBufferPosition, toCopy);
    spuBufferPosition += toCopy;
    if (spuBufferPosition >= spuExpectedSize) {
      emitCompleteSpu();
    }
  }

  @Override
  public void packetFinished(boolean isEndOfInput) {
  }

  private void emitCompleteSpu() {
    if (output == null || spuExpectedSize == 0 || spuTimeUs == C.TIME_UNSET) {
      return;
    }
    ParsableByteArray spuData = new ParsableByteArray(spuBuffer, spuExpectedSize);
    output.sampleData(spuData, spuExpectedSize);
    output.sampleMetadata(spuTimeUs, C.BUFFER_FLAG_KEY_FRAME, spuExpectedSize, 0, null);
    spuExpectedSize = 0;
    spuBufferPosition = 0;
    spuTimeUs = C.TIME_UNSET;
  }
}
