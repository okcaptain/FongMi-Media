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
public final class LpcmReader implements ElementaryStreamReader {

  private static final int[] SAMPLE_RATES = {0, 48000, 0, 0, 96000, 192000};
  private static final int[] CHANNEL_COUNTS = {0, 1, 0, 2, 3, 3, 4, 4, 5, 6, 7, 8, 0, 0, 0, 0};

  @Nullable
  private final String language;
  private final String containerMimeType;
  private final byte[] header = new byte[4];
  private final ParsableByteArray outWrapper = new ParsableByteArray();

  private @MonotonicNonNull TrackOutput output;
  @Nullable
  private String formatId;
  private boolean formatSet;
  private boolean headerParsed;
  private int headerBytesLeft;
  private long sampleTimeUs;
  private int sampleBytesWritten;
  private int channelCount;
  private int bytesPerSample;
  private int frameSize;
  private int layoutIdx;
  private int frameBufPos;
  private byte[] frameBuf = new byte[0];
  private byte[] outBuf = new byte[0];

  public LpcmReader(@Nullable String language, String containerMimeType) {
    this.language = language;
    this.containerMimeType = containerMimeType;
    sampleTimeUs = C.TIME_UNSET;
  }

  @Override
  public void seek() {
    formatSet = false;
    headerParsed = false;
    headerBytesLeft = 4;
    sampleTimeUs = C.TIME_UNSET;
    sampleBytesWritten = 0;
    frameBufPos = 0;
  }

  @Override
  public void createTracks(ExtractorOutput extractorOutput, TrackIdGenerator idGenerator) {
    idGenerator.generateNewId();
    formatId = idGenerator.getFormatId();
    output = extractorOutput.track(idGenerator.getTrackId(), C.TRACK_TYPE_AUDIO);
  }

  @Override
  public void packetStarted(long pesTimeUs, @TsPayloadReader.Flags int flags) {
    sampleTimeUs = pesTimeUs;
    headerParsed = false;
    headerBytesLeft = 4;
    sampleBytesWritten = 0;
    frameBufPos = 0;
  }

  @Override
  public void consume(ParsableByteArray data) {
    if (output == null || sampleTimeUs == C.TIME_UNSET) {
      return;
    }
    while (!headerParsed && data.bytesLeft() > 0) {
      header[4 - headerBytesLeft] = (byte) data.readUnsignedByte();
      if (--headerBytesLeft == 0) {
        parseHeader();
        headerParsed = true;
      }
    }
    if (!headerParsed || channelCount <= 0) {
      return;
    }
    while (data.bytesLeft() > 0) {
      int needed = frameSize - frameBufPos;
      int avail = Math.min(needed, data.bytesLeft());
      data.readBytes(frameBuf, frameBufPos, avail);
      frameBufPos += avail;
      if (frameBufPos == frameSize) {
        writeFrame();
        frameBufPos = 0;
      }
    }
  }

  @Override
  public void packetFinished(boolean isEndOfInput) {
    if (output == null || sampleTimeUs == C.TIME_UNSET || sampleBytesWritten == 0) {
      return;
    }
    output.sampleMetadata(sampleTimeUs, C.BUFFER_FLAG_KEY_FRAME, sampleBytesWritten, 0, null);
    sampleBytesWritten = 0;
    sampleTimeUs = C.TIME_UNSET;
    frameBufPos = 0;
  }

  private void parseHeader() {
    layoutIdx = (header[2] & 0xFF) >> 4;
    int rateIdx = header[2] & 0x0F;
    int bitsCode = (header[3] & 0xFF) >> 6;
    channelCount = CHANNEL_COUNTS[layoutIdx];
    int sampleRate = rateIdx < SAMPLE_RATES.length ? SAMPLE_RATES[rateIdx] : 0;
    if (channelCount == 0 || sampleRate == 0 || bitsCode == 0) {
      return;
    }
    int pcmEncoding;
    if (bitsCode == 1) {
      bytesPerSample = 2;
      pcmEncoding = C.ENCODING_PCM_16BIT_BIG_ENDIAN;
    } else {
      bytesPerSample = 3;
      pcmEncoding = C.ENCODING_PCM_24BIT_BIG_ENDIAN;
    }
    int sourceChannels = (channelCount % 2 == 0) ? channelCount : channelCount + 1;
    frameSize = sourceChannels * bytesPerSample;
    if (frameBuf.length < frameSize) {
      frameBuf = new byte[frameSize];
    }
    int outFrameSize = channelCount * bytesPerSample;
    if (outBuf.length < outFrameSize) {
      outBuf = new byte[outFrameSize];
    }
    if (!formatSet) {
      formatSet = true;
      output.format(
          new Format.Builder()
              .setId(formatId)
              .setContainerMimeType(containerMimeType)
              .setSampleMimeType(MimeTypes.AUDIO_RAW)
              .setLanguage(language)
              .setChannelCount(channelCount)
              .setSampleRate(sampleRate)
              .setPcmEncoding(pcmEncoding)
              .build());
    }
  }

  private void writeFrame() {
    if (output == null) {
      return;
    }
    int bs = bytesPerSample;
    int outLen = channelCount * bs;
    switch (layoutIdx) {
      case 9:
        ch(0, 0, bs);
        ch(1, 1, bs);
        ch(2, 2, bs);
        ch(5, 3, bs);
        ch(3, 4, bs);
        ch(4, 5, bs);
        break;
      case 10:
        ch(0, 0, bs);
        ch(1, 1, bs);
        ch(2, 2, bs);
        ch(4, 3, bs);
        ch(5, 4, bs);
        ch(3, 5, bs);
        ch(6, 6, bs);
        break;
      case 11:
        ch(0, 0, bs);
        ch(1, 1, bs);
        ch(2, 2, bs);
        ch(7, 3, bs);
        ch(4, 4, bs);
        ch(5, 5, bs);
        ch(3, 6, bs);
        ch(6, 7, bs);
        break;
      default:
        System.arraycopy(frameBuf, 0, outBuf, 0, outLen);
        break;
    }
    outWrapper.reset(outBuf, outLen);
    output.sampleData(outWrapper, outLen);
    sampleBytesWritten += outLen;
  }

  private void ch(int src, int dst, int bs) {
    System.arraycopy(frameBuf, src * bs, outBuf, dst * bs, bs);
  }
}
