/*
 * Copyright 2024 The Android Open Source Project
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
package androidx.media3.exoplayer.rtsp.reader;

import androidx.media3.common.C;
import androidx.media3.common.ParserException;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.text.SubtitleParser;
import androidx.media3.extractor.ts.TsExtractor;
import java.io.EOFException;
import java.io.IOException;

/**
 * Decodes RTP packets carrying MPEG-2 TS payloads (RFC 2250, payload type 33).
 *
 * <p>Each RTP payload is fed into a {@link TsExtractor} via a {@link FakeExtractorInput} bridge
 * that wraps a {@link ParsableByteArray} without copying.
 */
@UnstableApi
public final class RtpMp2tReader implements RtpPayloadReader {

  private static final byte TS_SYNC_BYTE = 0x47;
  private static final int HEADER_SIZE = 4;

  private final TsExtractor tsExtractor;
  private final FakeExtractorInput fakeInput;
  private final PositionHolder positionHolder;

  public RtpMp2tReader() {
    fakeInput = new FakeExtractorInput();
    positionHolder = new PositionHolder();
    tsExtractor = new TsExtractor(TsExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA | TsExtractor.FLAG_IGNORE_SECTION_CRC, SubtitleParser.Factory.UNSUPPORTED);
  }

  @Override
  public void createTracks(ExtractorOutput extractorOutput, int trackId) {
    tsExtractor.init(extractorOutput);
  }

  @Override
  public void onReceivingFirstPacket(long timestamp, int sequenceNumber) {
  }

  @Override
  public void consume(ParsableByteArray data, long timestamp, int sequenceNumber, boolean rtpMarker) throws ParserException {
    fakeInput.reset(maybeStripHeaders(data));
    try {
      int result;
      do {
        result = tsExtractor.read(fakeInput, positionHolder);
      } while (result == Extractor.RESULT_CONTINUE && fakeInput.hasData());
    } catch (IOException e) {
      throw ParserException.createForMalformedDataOfUnknownType(e.getMessage(), e);
    }
  }

  @Override
  public void seek(long nextRtpTimestamp, long timeUs) {
    tsExtractor.seek(0, timeUs);
  }

  private static ParsableByteArray maybeStripHeaders(ParsableByteArray data) {
    byte[] src = data.getData();
    int pos = data.getPosition();
    int length = data.bytesLeft();
    if (length % TsExtractor.TS_PACKET_SIZE == 0 && src[pos] == TS_SYNC_BYTE) {
      return data;
    }
    int stride = TsExtractor.TS_PACKET_SIZE + HEADER_SIZE;
    if (length < stride || length % stride != 0 || src[pos + HEADER_SIZE] != TS_SYNC_BYTE) {
      return data;
    }
    int packetCount = length / stride;
    byte[] dst = new byte[packetCount * TsExtractor.TS_PACKET_SIZE];
    ParsableByteArray reader = new ParsableByteArray(src, data.limit());
    reader.setPosition(pos);
    int dstOff = 0;
    for (int i = 0; i < packetCount; i++) {
      reader.skipBytes(HEADER_SIZE);
      reader.readBytes(dst, dstOff, TsExtractor.TS_PACKET_SIZE);
      dstOff += TsExtractor.TS_PACKET_SIZE;
    }
    return new ParsableByteArray(dst);
  }

  private static final class FakeExtractorInput implements ExtractorInput {

    private final ParsableByteArray buffer = new ParsableByteArray();
    private long streamPosition = 0;

    void reset(ParsableByteArray source) {
      buffer.reset(source.getData(), source.limit());
      buffer.setPosition(source.getPosition());
    }

    boolean hasData() {
      return buffer.bytesLeft() > 0;
    }

    @Override
    public int read(byte[] target, int offset, int length) {
      int available = buffer.bytesLeft();
      if (available == 0) {
        return C.RESULT_END_OF_INPUT;
      }
      int toRead = Math.min(length, available);
      buffer.readBytes(target, offset, toRead);
      streamPosition += toRead;
      return toRead;
    }

    @Override
    public long getPosition() {
      return streamPosition;
    }

    @Override
    public long getLength() {
      return C.LENGTH_UNSET;
    }

    @Override
    public boolean readFully(byte[] target, int offset, int length, boolean allowEndOfInput) throws EOFException {
      int available = buffer.bytesLeft();
      if (available == 0 && allowEndOfInput) {
        return false;
      }
      if (available < length) {
        throw new EOFException();
      }
      buffer.readBytes(target, offset, length);
      streamPosition += length;
      return true;
    }

    @Override
    public void readFully(byte[] target, int offset, int length) throws EOFException {
      readFully(target, offset, length, false);
    }

    @Override
    public int skip(int length) {
      int toSkip = Math.min(length, buffer.bytesLeft());
      buffer.skipBytes(toSkip);
      streamPosition += toSkip;
      return toSkip == 0 ? C.RESULT_END_OF_INPUT : toSkip;
    }

    @Override
    public boolean skipFully(int length, boolean allowEndOfInput) throws EOFException {
      if (buffer.bytesLeft() == 0 && allowEndOfInput) {
        return false;
      }
      if (buffer.bytesLeft() < length) {
        throw new EOFException();
      }
      buffer.skipBytes(length);
      streamPosition += length;
      return true;
    }

    @Override
    public void skipFully(int length) throws EOFException {
      skipFully(length, false);
    }

    @Override
    public int peek(byte[] target, int offset, int length) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean peekFully(byte[] target, int offset, int length, boolean allowEndOfInput) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void peekFully(byte[] target, int offset, int length) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean advancePeekPosition(int length, boolean allowEndOfInput) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void advancePeekPosition(int length) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void resetPeekPosition() {
      throw new UnsupportedOperationException();
    }

    @Override
    public long getPeekPosition() {
      throw new UnsupportedOperationException();
    }

    @Override
    public <E extends Throwable> void setRetryPosition(long position, E throwable) throws E {
      throw throwable;
    }
  }
}
