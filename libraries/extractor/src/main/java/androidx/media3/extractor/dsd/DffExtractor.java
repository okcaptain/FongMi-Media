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

public final class DffExtractor implements Extractor {

  private static final int ID_FRM8 = tag('F', 'R', 'M', '8');
  private static final int ID_DSD_FORM = tag('D', 'S', 'D', ' ');
  private static final int ID_DST_FORM = tag('D', 'S', 'T', ' ');
  private static final int ID_FVER = tag('F', 'V', 'E', 'R');
  private static final int ID_PROP = tag('P', 'R', 'O', 'P');
  private static final int ID_DIIN = tag('D', 'I', 'I', 'N');
  private static final int ID_CHNL = tag('C', 'H', 'N', 'L');
  private static final int ID_CMPR = tag('C', 'M', 'P', 'R');
  private static final int ID_FS = tag('F', 'S', ' ', ' ');
  private static final int ID_DSD_DATA = tag('D', 'S', 'D', ' ');
  private static final int ID_DST_DATA = tag('D', 'S', 'T', ' ');
  private static final int ID_DSTF = tag('D', 'S', 'T', 'F');
  private static final int ID_FRTE = tag('F', 'R', 'T', 'E');

  private static final int DEFAULT_BLOCK_PER_CHANNEL = 4096;
  private static final int SNIFF_BUFFER_SIZE = 16;
  private static final int DST_FRAME_RATE_HZ = 75;

  private final ParsableByteArray scratch = new ParsableByteArray(64);
  private final ParsableByteArray blockBuffer = new ParsableByteArray();

  private @MonotonicNonNull ExtractorOutput extractorOutput;
  private @MonotonicNonNull TrackOutput trackOutput;
  private String mimeType = MimeTypes.AUDIO_DSD;

  private boolean isDst;
  private boolean headerParsed;

  private int blockSize;
  private int channelCount;
  private int sampleRateBits;
  private int bytesPerSecond;

  private long bodyEnd;
  private long bodyStart;
  private long durationUs;
  private long startTimeUs;
  private long totalBytesOutput;

  private static void readBytes(ExtractorInput input, ParsableByteArray buf, int count) throws IOException {
    buf.reset(count);
    input.readFully(buf.getData(), 0, count);
    buf.setPosition(0);
  }

  private static boolean tryReadChunkHeader(ExtractorInput input, ParsableByteArray buf) throws IOException {
    try {
      readBytes(input, buf, 12);
      return true;
    } catch (java.io.EOFException e) {
      return false;
    }
  }

  private static void skipBytes(ExtractorInput input, long count) throws IOException {
    while (count > 0) {
      long skipped = input.skip((int) Math.min(count, Integer.MAX_VALUE));
      if (skipped <= 0) {
        break;
      }
      count -= skipped;
    }
  }

  private static void skipToOffset(ExtractorInput input, long targetOffset) throws IOException {
    long current = input.getPosition();
    if (targetOffset > current) {
      skipBytes(input, targetOffset - current);
    }
  }

  private static int tag(char a, char b, char c, char d) {
    return (a & 0xFF) | ((b & 0xFF) << 8) | ((c & 0xFF) << 16) | ((d & 0xFF) << 24);
  }

  @Override
  public boolean sniff(ExtractorInput input) throws IOException {
    scratch.reset(SNIFF_BUFFER_SIZE);
    input.peekFully(scratch.getData(), 0, SNIFF_BUFFER_SIZE);
    input.resetPeekPosition();
    if (scratch.readLittleEndianInt() != ID_FRM8) {
      return false;
    }
    scratch.setPosition(12);
    int formType = scratch.readLittleEndianInt();
    return formType == ID_DSD_FORM || formType == ID_DST_FORM;
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
    if (isDst) {
      return readDstFrame(input);
    }
    return readDsdBlock(input);
  }

  private void parseHeader(ExtractorInput input) throws IOException {
    readBytes(input, scratch, 16);
    while (true) {
      if (!tryReadChunkHeader(input, scratch)) {
        break;
      }
      int id = scratch.readLittleEndianInt();
      long size = scratch.readLong();
      long chunkEnd = input.getPosition() + size;
      if (id == ID_FVER) {
        skipBytes(input, size);
      } else if (id == ID_PROP) {
        parsePropChunk(input, chunkEnd);
      } else if (id == ID_DIIN) {
        skipBytes(input, size);
      } else if (id == ID_DSD_DATA || id == ID_DST_DATA) {
        bodyStart = input.getPosition();
        bodyEnd = bodyStart + size;
        isDst = (id == ID_DST_DATA);
        break;
      } else {
        skipBytes(input, size);
      }
    }
    bytesPerSecond = sampleRateBits / 8 * channelCount;
    blockSize = DEFAULT_BLOCK_PER_CHANNEL * channelCount;
    if (!isDst && bytesPerSecond > 0) {
      durationUs = (bodyEnd - bodyStart) * C.MICROS_PER_SECOND / bytesPerSecond;
    }
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
    if (durationUs > 0) {
      trackOutput.durationUs(durationUs);
    }
    extractorOutput.seekMap(new DsdBitrateSeekMap(durationUs, bytesPerSecond, channelCount, bodyStart, bodyEnd, !isDst));
    headerParsed = true;
  }

  private void parsePropChunk(ExtractorInput input, long chunkEnd) throws IOException {
    readBytes(input, scratch, 4);
    while (input.getPosition() + 12 <= chunkEnd) {
      if (!tryReadChunkHeader(input, scratch)) {
        break;
      }
      int id = scratch.readLittleEndianInt();
      long size = scratch.readLong();
      long subEnd = input.getPosition() + size;
      if (id == ID_FS) {
        readBytes(input, scratch, 4);
        sampleRateBits = scratch.readInt();
      } else if (id == ID_CHNL) {
        readBytes(input, scratch, 2);
        channelCount = scratch.readUnsignedShort();
        skipBytes(input, (long) channelCount * 4);
      } else if (id == ID_CMPR) {
        readBytes(input, scratch, 4);
        int codecTag = scratch.readLittleEndianInt();
        if (codecTag == ID_DST_DATA) {
          mimeType = MimeTypes.AUDIO_DST;
        } else {
          mimeType = MimeTypes.AUDIO_DSD;
        }
      }
      skipToOffset(input, subEnd);
    }
    skipToOffset(input, chunkEnd);
  }

  private @ReadResult int readDsdBlock(ExtractorInput input) throws IOException {
    if (input.getPosition() >= bodyEnd) {
      return RESULT_END_OF_INPUT;
    }
    long remaining = bodyEnd - input.getPosition();
    int toRead = (int) Math.min(blockSize, remaining);
    if (toRead <= 0) {
      return RESULT_END_OF_INPUT;
    }
    blockBuffer.reset(toRead);
    input.readFully(blockBuffer.getData(), 0, toRead);
    blockBuffer.setPosition(0);
    trackOutput.sampleData(blockBuffer, toRead);
    long sampleTimeUs = sampleTimeUs();
    totalBytesOutput += toRead;
    trackOutput.sampleMetadata(sampleTimeUs, C.BUFFER_FLAG_KEY_FRAME, toRead, 0, null);
    return RESULT_CONTINUE;
  }

  private @ReadResult int readDstFrame(ExtractorInput input) throws IOException {
    while (input.getPosition() + 12 <= bodyEnd) {
      if (!tryReadChunkHeader(input, scratch)) {
        return RESULT_END_OF_INPUT;
      }
      int id = scratch.readLittleEndianInt();
      long size = scratch.readLong();
      long subEnd = input.getPosition() + size;
      if (id == ID_DSTF) {
        int frameSize = (int) Math.min(size, Integer.MAX_VALUE);
        blockBuffer.reset(frameSize);
        input.readFully(blockBuffer.getData(), 0, frameSize);
        blockBuffer.setPosition(0);
        trackOutput.sampleData(blockBuffer, frameSize);
        long sampleTimeUs = sampleTimeUs();
        totalBytesOutput += bytesPerSecond / DST_FRAME_RATE_HZ;
        trackOutput.sampleMetadata(sampleTimeUs, C.BUFFER_FLAG_KEY_FRAME, frameSize, 0, null);
        skipToOffset(input, subEnd + (size & 1));
        return RESULT_CONTINUE;
      } else if (id == ID_FRTE) {
        readBytes(input, scratch, 4);
        int frameCount = scratch.readInt();
        durationUs = frameCount * C.MICROS_PER_SECOND / DST_FRAME_RATE_HZ;
        if (durationUs > 0) {
          trackOutput.durationUs(durationUs);
        }
      }
      skipToOffset(input, subEnd + (size & 1));
    }
    return RESULT_END_OF_INPUT;
  }

  private long sampleTimeUs() {
    return startTimeUs + totalBytesOutput * C.MICROS_PER_SECOND / bytesPerSecond;
  }
}
