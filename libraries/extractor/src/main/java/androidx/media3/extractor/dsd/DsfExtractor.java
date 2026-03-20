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
package androidx.media3.extractor.dsd;

import androidx.annotation.NonNull;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.TrackOutput;
import java.io.IOException;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

public final class DsfExtractor implements Extractor {

  private static final int BIT_ORDER_LSBF = 1;
  private static final int BIT_ORDER_MSBF = 8;
  private static final int MAGIC_DSD = 0x20445344;
  private static final int DSD_CHUNK_SIZE = 28;
  private static final int OFFSET_DATA_SIZE = 84;
  private static final int CHUNK_HEADER_SIZE = 12;
  private static final int AUDIO_DATA_OFFSET = 92;
  private static final int OFFSET_CHANNEL_COUNT = 52;
  private static final int SNIFF_BUFFER_SIZE = CHUNK_HEADER_SIZE;
  private static final int HEADER_BUFFER_SIZE = AUDIO_DATA_OFFSET;

  private final ParsableByteArray headerBuffer = new ParsableByteArray(HEADER_BUFFER_SIZE);
  private final ParsableByteArray sampleBuffer = new ParsableByteArray();

  private @MonotonicNonNull ExtractorOutput extractorOutput;
  private @MonotonicNonNull TrackOutput trackOutput;
  private boolean headerParsed;

  private int blockSize;
  private int bytesPerSecond;

  private long startTimeUs;
  private long audioDataSize;
  private long totalBytesOutput;

  @Override
  public boolean sniff(ExtractorInput input) throws IOException {
    input.peekFully(headerBuffer.getData(), 0, SNIFF_BUFFER_SIZE);
    input.resetPeekPosition();
    headerBuffer.setPosition(0);
    return headerBuffer.readLittleEndianInt() == MAGIC_DSD && headerBuffer.readLittleEndianLong() == DSD_CHUNK_SIZE;
  }

  @Override
  public void init(ExtractorOutput output) {
    extractorOutput = output;
    trackOutput = output.track(0, C.TRACK_TYPE_AUDIO);
    output.endTracks();
  }

  @Override
  public void seek(long position, long timeUs) {
    startTimeUs = timeUs;
    totalBytesOutput = 0;
  }

  @Override
  public void release() {
  }

  @Override
  public @ReadResult int read(@NonNull ExtractorInput input, @NonNull PositionHolder seekPosition) throws IOException {
    if (!headerParsed) {
      parseHeader(input);
    }
    return readAudioBlock(input);
  }

  private void parseHeader(ExtractorInput input) throws IOException {
    input.readFully(headerBuffer.getData(), 0, HEADER_BUFFER_SIZE);
    headerBuffer.setPosition(OFFSET_CHANNEL_COUNT);
    int channelCount = headerBuffer.readLittleEndianInt();
    int sampleRateBits = headerBuffer.readLittleEndianInt();
    int bitOrder = headerBuffer.readLittleEndianInt();
    long audioSizeBits = headerBuffer.readLittleEndianLong();
    int blockAlign = headerBuffer.readLittleEndianInt();
    String mimeType = bitOrder == BIT_ORDER_LSBF ? MimeTypes.AUDIO_DSD_LSBF_PLANAR : MimeTypes.AUDIO_DSD_MSBF_PLANAR;
    blockSize = blockAlign * channelCount;
    bytesPerSecond = sampleRateBits / 8 * channelCount;
    long durationUs = audioSizeBits * C.MICROS_PER_SECOND / sampleRateBits;
    headerBuffer.setPosition(OFFSET_DATA_SIZE);
    audioDataSize = headerBuffer.readLittleEndianLong() - CHUNK_HEADER_SIZE;
    sampleBuffer.reset(blockSize);
    trackOutput.format(
        new Format.Builder()
            .setContainerMimeType(MimeTypes.AUDIO_DSD)
            .setSampleMimeType(mimeType)
            .setChannelCount(channelCount)
            .setSampleRate(sampleRateBits / 8)
            .setAverageBitrate(bytesPerSecond * 8)
            .setPeakBitrate(bytesPerSecond * 8)
            .setMaxInputSize(blockSize)
            .build());
    trackOutput.durationUs(durationUs);
    extractorOutput.seekMap(new DsdBitrateSeekMap(durationUs, bytesPerSecond, blockSize, AUDIO_DATA_OFFSET, C.LENGTH_UNSET, true));
    headerParsed = true;
  }

  private @ReadResult int readAudioBlock(ExtractorInput input) throws IOException {
    if (input.getPosition() >= AUDIO_DATA_OFFSET + audioDataSize) {
      return RESULT_END_OF_INPUT;
    }
    long remaining = AUDIO_DATA_OFFSET + audioDataSize - input.getPosition();
    int toRead = (int) Math.min(blockSize, remaining);
    if (toRead <= 0) {
      return RESULT_END_OF_INPUT;
    }
    sampleBuffer.reset(toRead);
    input.readFully(sampleBuffer.getData(), 0, toRead);
    sampleBuffer.setPosition(0);
    trackOutput.sampleData(sampleBuffer, toRead);
    long sampleTimeUs = sampleTimeUs();
    totalBytesOutput += toRead;
    trackOutput.sampleMetadata(sampleTimeUs, C.BUFFER_FLAG_KEY_FRAME, toRead, 0, null);
    return RESULT_CONTINUE;
  }

  private long sampleTimeUs() {
    return startTimeUs + totalBytesOutput * C.MICROS_PER_SECOND / bytesPerSecond;
  }
}
