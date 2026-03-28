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
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

@UnstableApi
public final class DvdLpcmReader implements ElementaryStreamReader {

  private static final int AUDIO_HEADER_SIZE = 3;

  @Nullable
  private final String language;

  @Nullable
  private String formatId;
  private @MonotonicNonNull TrackOutput output;

  private long timeUs;
  private boolean formatSet;
  private int sampleBytesWritten;

  public DvdLpcmReader(@Nullable String language) {
    this.language = language;
    timeUs = C.TIME_UNSET;
  }

  @Override
  public void seek() {
    timeUs = C.TIME_UNSET;
    sampleBytesWritten = 0;
    formatSet = false;
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
    sampleBytesWritten = 0;
  }

  @Override
  public void consume(ParsableByteArray data) {
    if (output == null || timeUs == C.TIME_UNSET) {
      return;
    }
    if (data.bytesLeft() < AUDIO_HEADER_SIZE) {
      return;
    }
    data.skipBytes(1);
    int paramByte = data.readUnsignedByte();
    data.skipBytes(1);
    int quantCode = (paramByte >> 6) & 0x03;
    int rateCode = (paramByte >> 4) & 0x03;
    int channelCount = (paramByte & 0x07) + 1;
    int sampleRate;
    switch (rateCode) {
      case 0:
        sampleRate = 48000;
        break;
      case 1:
        sampleRate = 96000;
        break;
      default:
        return;
    }
    int pcmEncoding;
    switch (quantCode) {
      case 0:
        pcmEncoding = C.ENCODING_PCM_16BIT_BIG_ENDIAN;
        break;
      case 2:
        pcmEncoding = C.ENCODING_PCM_24BIT_BIG_ENDIAN;
        break;
      default:
        return;
    }
    if (!formatSet) {
      formatSet = true;
      output.format(
          new Format.Builder()
              .setId(formatId)
              .setSampleMimeType(MimeTypes.AUDIO_RAW)
              .setLanguage(language)
              .setChannelCount(channelCount)
              .setSampleRate(sampleRate)
              .setPcmEncoding(pcmEncoding)
              .build());
    }
    int avail = data.bytesLeft();
    output.sampleData(data, avail);
    sampleBytesWritten += avail;
  }

  @Override
  public void packetFinished(boolean isEndOfInput) {
    if (output == null || sampleBytesWritten == 0 || timeUs == C.TIME_UNSET) {
      return;
    }
    output.sampleMetadata(timeUs, C.BUFFER_FLAG_KEY_FRAME, sampleBytesWritten, 0, null);
    sampleBytesWritten = 0;
    timeUs = C.TIME_UNSET;
  }
}
